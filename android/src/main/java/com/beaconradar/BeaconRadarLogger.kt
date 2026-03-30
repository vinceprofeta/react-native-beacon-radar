package com.beaconradar

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

object BeaconRadarLogger {
    private const val POSTHOG_EVENT_PREFIX = "HF_"
    private const val POSTHOG_URL = "https://us.i.posthog.com/i/v0/e/"
    private const val SESSION_AUTO_RESET_MS = 60_000L

    private val networkExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var sessionStartTimeMs: Long? = null

    @JvmStatic
    fun i(
        context: Context?,
        tag: String,
        message: String,
        type: String = tag,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        log(context, tag, message, "INFO", type, properties)
    }

    @JvmStatic
    fun w(
        context: Context?,
        tag: String,
        message: String,
        type: String = tag,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        log(context, tag, message, "WARN", type, properties)
    }

    @JvmStatic
    fun e(
        context: Context?,
        tag: String,
        message: String,
        type: String = tag,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        log(context, tag, message, "ERROR", type, properties)
    }

    @JvmStatic
    fun logKeyEvent(
        context: Context?,
        tag: String,
        event: String,
        type: String = "GENERAL",
        properties: Map<String, Any?> = emptyMap(),
    ) {
        val elapsed = nextElapsedLabel()
        val message = "[$elapsed] $event"
        log(
            context = context,
            tag = tag,
            message = message,
            level = "INFO",
            type = type,
            properties = properties + mapOf(
                "description" to event,
                "elapsed" to elapsed,
            ),
        )
    }

    private fun log(
        context: Context?,
        tag: String,
        message: String,
        level: String,
        type: String,
        properties: Map<String, Any?>,
    ) {
        val logMessage = "[$tag] $message"
        when (level) {
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }

        if (context == null) {
            return
        }

        val posthogKey = BeaconRadarPreferences.getPosthogKey(context)
        if (posthogKey.isBlank()) {
            if (BeaconRadarPreferences.isBeaconDebugEnabled(context)) {
                Log.w(tag, "PostHog key missing; skipping HF_ event for: $message")
            }
            return
        }

        val distinctId = context.getSharedPreferences("BeaconRadarPrefs", Context.MODE_PRIVATE)
            .getString("throneUserId", "") ?: ""
        val deviceTimestamp = iso8601Timestamp(System.currentTimeMillis())
        val deviceTimestampUnix = System.currentTimeMillis() / 1000.0

        val eventProperties = linkedMapOf<String, Any?>(
            "distinct_id" to distinctId,
            "device_timestamp" to deviceTimestamp,
            "device_timestamp_unix" to deviceTimestampUnix,
            "message" to logMessage,
            "level" to level,
            "event_type" to type,
            "type" to type,
            "tag" to tag,
        )
        eventProperties.putAll(properties)

        val eventName = POSTHOG_EVENT_PREFIX + type

        networkExecutor.execute {
            posthog(posthogKey, eventName, eventProperties, deviceTimestamp, tag)
        }
    }

    @Synchronized
    private fun nextElapsedLabel(): String {
        val now = System.currentTimeMillis()
        val startTime = sessionStartTimeMs
        return if (startTime == null) {
            sessionStartTimeMs = now
            "START"
        } else {
            val elapsedMs = now - startTime
            if (elapsedMs > SESSION_AUTO_RESET_MS) {
                sessionStartTimeMs = now
                "START (auto-reset)"
            } else {
                String.format("+%.3fs", elapsedMs / 1000.0)
            }
        }
    }

    private fun posthog(
        apiKey: String,
        eventName: String,
        properties: Map<String, Any?>,
        timestamp: String,
        tag: String
    ) {
        var connection: HttpURLConnection? = null
        try {
            val payload = JSONObject()
                .put("api_key", apiKey)
                .put("event", eventName)
                .put("properties", properties.toJsonObject())
                .put("timestamp", timestamp)
                .toString()

            connection = (URL(POSTHOG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            connection.outputStream.use { outputStream: OutputStream ->
                outputStream.write(payload.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && connection.errorStream != null) {
                connection.errorStream.close()
            }
        } catch (error: Exception) {
            Log.w(tag, "Failed to send PostHog event: ${error.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun iso8601Timestamp(timeMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timeMs))
    }

    private fun Map<String, Any?>.toJsonObject(): JSONObject {
        val json = JSONObject()
        for ((key, value) in this) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Map<*, *> -> json.put(key, value.filterKeys { it is String }.mapKeys { it.key as String }.toJsonObject())
                is Iterable<*> -> json.put(key, value.toList())
                is Array<*> -> json.put(key, value.toList())
                else -> json.put(key, value)
            }
        }
        return json
    }
}
