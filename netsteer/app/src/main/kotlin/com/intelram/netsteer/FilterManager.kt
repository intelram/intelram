package com.intelram.netsteer

import android.content.Context
import android.net.Network
import android.util.Log
import com.intelram.netsteer.core.DomainFilter
import com.intelram.netsteer.core.HostFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/**
 * Ad/tracker blocklists, AdGuard-style: the AdGuard DNS filter and the Steven
 * Black hosts list are downloaded on the device (HTTPS only), compiled into a
 * compact [DomainFilter], cached in binary form, and combined with a small
 * built-in list and the user's allowlist.
 */
class FilterManager(private val ctx: Context, private val settings: Settings) {
    data class Source(val id: String, val title: String, val urls: List<String>)

    data class State(
        val rules: Int = 0,
        val updatedAt: Long = 0,
        val updating: Boolean = false,
        val error: String? = null,
        val downloaded: Set<String> = emptySet(),
    )

    private val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var base: DomainFilter = DomainFilter.EMPTY
    @Volatile private var active: DomainFilter = DomainFilter.EMPTY
    private var appliedAllowlist: Set<String>? = null

    // Stats (cheap: called on relay threads for every blocked connection).
    private val statsPrefs = ctx.getSharedPreferences("netsteer_stats", Context.MODE_PRIVATE)
    val blockedTotal = AtomicLong(statsPrefs.getLong("total", 0))
    private val todayKey = AtomicLong(dayNumber())
    val blockedToday = AtomicLong(if (statsPrefs.getLong("day", -1) == dayNumber()) statsPrefs.getLong("today", 0) else 0)
    private val perDomain = ConcurrentHashMap<String, AtomicInteger>()

    /** The filter the proxy should apply right now, or null when ad blocking is off. */
    fun hostFilter(): HostFilter? {
        val s = settings.current
        if (!s.adBlock) return null
        if (appliedAllowlist !== s.allowlist) {
            synchronized(this) {
                if (appliedAllowlist !== s.allowlist) {
                    active = base.withAllowed(s.allowlist)
                    appliedAllowlist = s.allowlist
                }
            }
        }
        return active
    }

    fun recordBlocked(host: String) {
        val day = dayNumber()
        if (todayKey.getAndSet(day) != day) blockedToday.set(0)
        blockedToday.incrementAndGet()
        blockedTotal.incrementAndGet()
        val key = host.lowercase()
        if (perDomain.size < 2_000 || perDomain.containsKey(key)) perDomain.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    fun topBlocked(n: Int): List<Pair<String, Int>> =
        perDomain.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(n)

    fun saveStats() {
        statsPrefs.edit().putLong("total", blockedTotal.get()).putLong("today", blockedToday.get()).putLong("day", todayKey.get()).apply()
    }

    /** Load the compiled cache, or compile from whatever lists are on disk. Call off the main thread. */
    @Synchronized
    fun load() {
        val sig = signature()
        val cache = File(dir, "compiled.bin")
        val sigFile = File(dir, "compiled.sig")
        val filter = if (cache.exists() && sigFile.exists() && sigFile.readText() == sig) {
            try {
                DataInputStream(cache.inputStream().buffered()).use { DomainFilter.readFrom(it) }
            } catch (e: IOException) {
                Log.w(TAG, "filter cache unreadable, recompiling", e)
                null
            }
        } else {
            null
        } ?: compile().also { f ->
            runCatching {
                val tmp = File(dir, "compiled.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { f.writeTo(it) }
                tmp.renameTo(cache)
                sigFile.writeText(sig)
            }
        }
        base = filter
        appliedAllowlist = null
        _state.update {
            it.copy(rules = filter.size, updatedAt = enabledFiles().maxOfOrNull { f -> f.lastModified() } ?: 0, downloaded = downloadedIds())
        }
        Log.i(TAG, "filters: ${filter.size} rules (${downloadedIds().sorted()})")
    }

    /** True if an enabled list is missing or older than [UPDATE_EVERY_MS]. */
    fun needsUpdate(): Boolean = enabledSources().any { s ->
        val f = file(s.id)
        !f.exists() || System.currentTimeMillis() - f.lastModified() > UPDATE_EVERY_MS
    }

    fun hasAnyDownloadedList(): Boolean = enabledSources().any { file(it.id).exists() }

    /**
     * Download stale/missing enabled lists over [network] (null = default network),
     * then recompile. Call off the main thread. Returns true if anything changed.
     */
    fun update(network: Network?, force: Boolean = false): Boolean {
        _state.update { it.copy(updating = true, error = null) }
        var changed = false
        var error: String? = null
        try {
            for (s in enabledSources()) {
                val f = file(s.id)
                if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() <= UPDATE_EVERY_MS) continue
                val text = s.urls.firstNotNullOfOrNull { url ->
                    try {
                        download(url, network)
                    } catch (e: IOException) {
                        Log.w(TAG, "download failed: $url: ${e.message}")
                        null
                    }
                }
                if (text == null) {
                    error = "Couldn't download ${s.title}"
                    continue
                }
                // Sanity check: never replace a good list with an error page or a truncated file.
                val probe = DomainFilter.Builder()
                val accepted = probe.addList(text)
                if (accepted < MIN_RULES) {
                    error = "${s.title} looked invalid ($accepted rules)"
                    continue
                }
                val tmp = File(dir, "${s.id}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) throw IOException("rename failed")
                changed = true
            }
            if (changed) load()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            _state.update { it.copy(updating = false, error = error) }
        }
        return changed
    }

    private fun download(url: String, network: Network?): String {
        val u = URL(url)
        if (u.protocol != "https") throw IOException("refusing non-HTTPS list URL")
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpsURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NetSteer/0.3 (Android)")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val declared = c.contentLengthLong
            if (declared > MAX_LIST_BYTES) throw IOException("list too large")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_LIST_BYTES) throw IOException("list too large")
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private fun compile(): DomainFilter {
        val b = DomainFilter.Builder()
        ctx.resources.openRawResource(R.raw.builtin_blocklist).bufferedReader().use { b.addList(it.readText()) }
        for (f in enabledFiles()) {
            runCatching { b.addList(f.readText()) }.onFailure { Log.w(TAG, "skipping unreadable list ${f.name}", it) }
        }
        return b.build()
    }

    private fun enabledSources(): List<Source> {
        val s = settings.current
        return SOURCES.filter { (it.id == ADGUARD && s.listAdGuard) || (it.id == STEVEN_BLACK && s.listStevenBlack) }
    }

    private fun enabledFiles() = enabledSources().map { file(it.id) }.filter { it.exists() }
    private fun downloadedIds() = enabledSources().filter { file(it.id).exists() }.map { it.id }.toSet()
    private fun file(id: String) = File(dir, "$id.txt")

    /** Changes whenever the compiled result would change. */
    private fun signature(): String =
        "v1|builtin=${BuildInfo.BUILTIN_LIST_VERSION}|" + enabledSources().joinToString("|") { s ->
            val f = file(s.id)
            "${s.id}:${if (f.exists()) "${f.length()}@${f.lastModified()}" else "none"}"
        }

    private fun dayNumber(): Long = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toLong()

    companion object {
        private const val TAG = "NetSteer"
        const val ADGUARD = "adguard"
        const val STEVEN_BLACK = "stevenblack"
        private const val MAX_LIST_BYTES = 25L * 1024 * 1024
        private const val MIN_RULES = 1_000
        const val UPDATE_EVERY_MS = 4L * 24 * 3600 * 1000

        val SOURCES = listOf(
            Source(
                ADGUARD, "AdGuard DNS filter",
                listOf(
                    "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                    "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt",
                ),
            ),
            Source(STEVEN_BLACK, "Steven Black hosts", listOf("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")),
        )
    }
}

/** Bump when res/raw/builtin_blocklist.txt changes, so cached filters are recompiled. */
object BuildInfo {
    const val BUILTIN_LIST_VERSION = 1
}
