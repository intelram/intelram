package com.threadprotection.app.scan

import java.io.File

/**
 * Local listening-socket enumeration via `/proc/net/tcp` and `/proc/net/tcp6` — README's
 * suggested "Open ports" source. Android 10+ generally sandboxes this file to an app's own
 * connections for non-privileged apps (SELinux), so this degrades to an empty, clearly-labelled
 * result rather than pretending to see ports it can't.
 */
object PortScanner {

    data class OpenPort(val port: Int, val uid: Int, val ipv6: Boolean)

    private const val TCP_LISTEN_STATE = "0A"

    fun listeningPorts(): List<OpenPort> {
        val results = mutableListOf<OpenPort>()
        for ((path, isV6) in listOf("/proc/net/tcp" to false, "/proc/net/tcp6" to true)) {
            val file = File(path)
            if (!file.canRead()) continue
            runCatching {
                file.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("sl")) return@forEachLine
                    val fields = line.split(Regex("\\s+"))
                    if (fields.size < 8 || fields[3] != TCP_LISTEN_STATE) return@forEachLine
                    val portHex = fields[1].substringAfter(':', "")
                    val port = portHex.toIntOrNull(16) ?: return@forEachLine
                    val uid = fields[7].toIntOrNull() ?: -1
                    results += OpenPort(port, uid, isV6)
                }
            }
        }
        return results.distinctBy { it.port to it.uid }.sortedBy { it.port }
    }

    fun readable(): Boolean = File("/proc/net/tcp").canRead() || File("/proc/net/tcp6").canRead()
}
