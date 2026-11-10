package com.nemap.android.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** État global du service, lu par l'Activity, la notification, l'API et le dashboard. */
object AppState {

    const val API_PORT = 8090
    const val VERSION = "1.0.1"

    @Volatile var serviceRunning = false
    @Volatile var startedAt: Long = 0L
    @Volatile var activeProvider: String = "deepseek"
    @Volatile var providerLabel: String = "DeepSeek"
    @Volatile var webViewProvider: String = "inconnu"
    @Volatile var webViewIsGoogle: Boolean = true
    @Volatile var observationMode: String = "stream-hook"
    @Volatile var bearerEnabled: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var pageUrl: String = ""

    val busy = AtomicBoolean(false)

    val requests = AtomicInteger(0)
    val promptChars = AtomicLong(0)
    val completionChars = AtomicLong(0)
    val lastRequestAt = AtomicLong(0)
    val errors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    // ---- latence (50 derniers appels) ----
    class LatencySample(
        val at: Long, val stream: Boolean, val ttftMs: Long, val totalMs: Long,
        val chars: Int, val chunks: Int, val images: Int, val ok: Boolean
    )

    private val samples = java.util.concurrent.ConcurrentLinkedDeque<LatencySample>()

    fun noteLatency(stream: Boolean, ttftMs: Long, totalMs: Long, chars: Int, chunks: Int, images: Int, ok: Boolean) {
        samples.addLast(LatencySample(System.currentTimeMillis(), stream, ttftMs, totalMs, chars, chunks, images, ok))
        while (samples.size > 50) samples.pollFirst()
        lastTtftMs.set(ttftMs)
        lastTotalMs.set(totalMs)
        lastChunks.set(chunks)
    }

    val lastTtftMs = AtomicLong(0)
    val lastTotalMs = AtomicLong(0)
    val lastChunks = AtomicInteger(0)

    /** Résumé statistique (moyenne, médiane, p90, min, max) sur les appels reçus. */
    fun latencyJson(): String {
        val all = samples.toList()
        fun stat(name: String, values: List<Long>): String {
            if (values.isEmpty()) return "\"$name\": null"
            val v = values.sorted()
            val avg = v.sum() / v.size
            val p50 = v[(v.size * 0.50).toInt().coerceAtMost(v.size - 1)]
            val p90 = v[(v.size * 0.90).toInt().coerceAtMost(v.size - 1)]
            return "\"$name\": {\"n\": ${v.size}, \"avg\": $avg, \"p50\": $p50, \"p90\": $p90, \"min\": ${v.first()}, \"max\": ${v.last()}}"
        }
        val okSamples = all.filter { it.ok }
        return "{" +
            stat("ttft_ms", okSamples.map { it.ttftMs }) + "," +
            stat("total_ms", okSamples.map { it.totalMs }) + "," +
            stat("chars", okSamples.map { it.chars.toLong() }) + "," +
            stat("chunks", okSamples.map { it.chunks.toLong() }) + "," +
            "\"last\": " + (all.lastOrNull()?.let {
                "{\"stream\": ${it.stream}, \"ttft_ms\": ${it.ttftMs}, \"total_ms\": ${it.totalMs}, " +
                    "\"chars\": ${it.chars}, \"chunks\": ${it.chunks}, \"images\": ${it.images}, \"ok\": ${it.ok}}"
            } ?: "null") +
            "}"
    }

    fun noteError(code: String) {
        errors.getOrPut(code) { AtomicInteger(0) }.incrementAndGet()
        lastError = code
    }

    fun ipAddresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out.distinct()
    }

    private fun iso(ts: Long): String =
        if (ts <= 0) "" else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ts))

    fun statusJson(): String {
        val ips = ipAddresses()
        val base = if (ips.isNotEmpty()) "http://${ips[0]}:$API_PORT" else "http://127.0.0.1:$API_PORT"
        val errs = StringBuilder("{")
        errors.entries.forEachIndexed { i, e ->
            if (i > 0) errs.append(',')
            errs.append('"').append(e.key).append("\":").append(e.value.get())
        }
        errs.append('}')
        return """
        {
          "service": {
            "running": $serviceRunning,
            "foreground": $serviceRunning,
            "started_at": "${iso(startedAt)}",
            "observation_mode": "$observationMode",
            "api_port": $API_PORT,
            "ip_addresses": ${ips.joinToString(",", "[", "]") { "\"$it\"" }},
            "base_url": "$base/v1",
            "version": "$VERSION"
          },
          "provider": {
            "active_id": "$activeProvider",
            "active_label": "$providerLabel",
            "session_state": "inconnue",
            "webview_provider": "$webViewProvider",
            "webview_is_google": $webViewIsGoogle,
            "supported_providers": ["deepseek"],
            "configured_providers": ["deepseek"],
            "page_url": "$pageUrl"
          },
          "security": {
            "bearer_enabled": $bearerEnabled,
            "bearer_configured": false,
            "network_exposure_warning": true
          },
          "stats": {
            "requests": ${requests.get()},
            "prompt_chars": ${promptChars.get()},
            "completion_chars": ${completionChars.get()},
            "errors_by_code": $errs,
            "last_request_at": "${iso(lastRequestAt.get())}"
          },
          "latency": ${latencyJson()},
          "alerts": [
            { "level": "warning", "code": "bearer_disabled", "message": "L'API est accessible à tout le réseau local." }
          ]
        }
        """.trimIndent()
    }
}
