package com.intelram.netsteer

import com.intelram.netsteer.core.Decision
import com.intelram.netsteer.core.FailoverPolicy
import com.intelram.netsteer.core.PolicyConfig
import com.intelram.netsteer.core.Preference
import com.intelram.netsteer.core.SwitchReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic loop: sample every network, ask [FailoverPolicy] for a decision,
 * apply it with [NetworkSelector], and keep an audit log of every switch.
 */
class FailoverController(
    private val monitor: NetworkMonitor,
    private val selector: NetworkSelector,
    private val prober: Prober,
    private val scope: CoroutineScope,
    config: PolicyConfig = PolicyConfig(),
    private val intervalMs: Long = 5_000,
) {
    private val policy = FailoverPolicy(config)
    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)

    @Volatile var preference: Preference = Preference.AUTO

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch {
            // Bound network vanished: re-decide now instead of waiting for the next round.
            launch {
                monitor.networks.collect { m ->
                    selector.boundId?.let { if (it !in m) kick.trySend(Unit) }
                }
            }
            var sample = true
            while (isActive) {
                tick(sample)
                sample = withTimeoutOrNull(intervalMs) { kick.receive() } == null
            }
        }
        record("controller started (pref=$preference)")
    }

    fun stop() {
        job?.cancel()
        job = null
        selector.bind(null)
        record("controller stopped; process follows system default")
    }

    /** @param sample false on an emergency kick: decide from OS state alone, no probe delay. */
    private suspend fun tick(sample: Boolean) {
        if (sample) {
            val states = monitor.networks.value.values.toList()
            // Sample all links in parallel; each probe is pinned to its own network.
            states.map { s -> scope.async { s.id to prober.sample(s) } }.awaitAll()
                .forEach { (id, stats) -> monitor.recordProbe(id, stats) }
        }

        val snaps = monitor.networks.value.values.map { it.toSnapshot() }
        when (val d = policy.evaluate(selector.boundId, snaps, preference, System.currentTimeMillis())) {
            is Decision.Switch -> {
                val target = monitor.find(d.to) ?: return
                if (selector.bind(target)) {
                    record("SWITCH ${label(d.from)} -> ${label(d.to)} (${d.reason})")
                    if (d.reason == SwitchReason.CURRENT_DEGRADED) {
                        d.from?.let { from -> monitor.find(from)?.let { selector.report(it, false) } }
                    }
                } else {
                    record("bind to ${label(d.to)} refused by OS")
                }
            }
            is Decision.NoUsableNetwork -> {
                if (selector.boundId != null) {
                    selector.bind(null)
                    record("no usable network for pref=$preference; unbound")
                }
            }
            is Decision.Stay -> Unit
        }
    }

    private fun label(id: Long?): String =
        id?.let { monitor.networks.value[it]?.transport?.name ?: "net#$it" } ?: "default"

    private fun record(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.update { (listOf("$ts $msg") + it).take(200) }
    }
}
