package org.nexora.mesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import java.util.LinkedHashMap

/**
 * Durable Android store-and-forward inbox for opaque Rust wire envelopes.
 * It never decrypts message payloads. Entries remain until a next-hop storage ACK.
 */
class RelayEnvelopeStore(context: Context) {
    companion object {
        private const val PREFS = "mesh_relay_store_v1"
        private const val QUEUE_KEY = "opaque_envelopes"
        private const val SEEN_KEY = "seen_message_ids"
        private const val OPT_IN_KEY = "relay_opt_in"
        private const val MAX_ITEMS = 128
        private const val MAX_SEEN = 512
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val queue = LinkedHashMap<String, ByteArray>()
    private val seen = LinkedHashMap<String, Unit>()

    init {
        synchronized(lock) {
            runCatching {
                val saved = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
                for (i in 0 until minOf(saved.length(), MAX_ITEMS)) {
                    val row = saved.getJSONObject(i)
                    val id = row.getString("id")
                    val bytes = Base64.decode(row.getString("frame"), Base64.NO_WRAP)
                    if (id.matches(Regex("[0-9a-f]{32}")) && bytes.size in 49..(256 * 1024)) queue[id] = bytes
                }
            }
            runCatching {
                val saved = JSONArray(prefs.getString(SEEN_KEY, "[]"))
                for (i in 0 until minOf(saved.length(), MAX_SEEN)) {
                    val id = saved.getString(i)
                    if (id.matches(Regex("[0-9a-f]{32}"))) seen[id] = Unit
                }
            }
        }
    }

    fun isOptedIn(): Boolean = prefs.getBoolean(OPT_IN_KEY, false)

    fun setOptIn(enabled: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(OPT_IN_KEY, enabled).commit()
            if (!enabled) {
                queue.clear()
                persistQueue()
            }
        }
    }

    /** Validate through Rust before persisting. Duplicate message IDs are rejected. */
    fun enqueue(envelope: ByteArray): Boolean = synchronized(lock) {
        if (!isOptedIn() || envelope.size !in 49..(256 * 1024)) return false
        if (runCatching { NativeMesh.validate(envelope) }.getOrDefault(-1) != 0) return false
        val id = envelope.copyOfRange(3, 19).joinToString("") { "%02x".format(it) }
        if (seen.containsKey(id) || queue.containsKey(id) || queue.size >= MAX_ITEMS) return false
        queue[id] = envelope.copyOf()
        seen[id] = Unit
        while (seen.size > MAX_SEEN) seen.remove(seen.keys.first())
        persistQueue() && persistSeen()
    }

    /**
     * Returns a hop-decremented copy, retaining the original until acknowledgeStoredByNextHop.
     * This prevents a radio disconnect from silently deleting the only stored copy.
     */
    fun peekForNextHop(nowUnixSeconds: Long): ByteArray? = synchronized(lock) {
        if (!isOptedIn()) return null
        val frame = queue.values.firstOrNull() ?: return null
        runCatching { NativeMesh.forward(frame, nowUnixSeconds) }.getOrNull()
    }

    /** Call only after the next device confirms durable acceptance of this message ID. */
    fun acknowledgeStoredByNextHop(messageIdHex: String): Boolean = synchronized(lock) {
        if (queue.remove(messageIdHex.lowercase()) == null) return false
        persistQueue()
    }

    fun pendingCount(): Int = synchronized(lock) { queue.size }

    private fun persistQueue(): Boolean {
        val rows = JSONArray()
        queue.forEach { (id, bytes) ->
            rows.put(org.json.JSONObject()
                .put("id", id)
                .put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        return prefs.edit().putString(QUEUE_KEY, rows.toString()).commit()
    }

    private fun persistSeen(): Boolean {
        val rows = JSONArray()
        seen.keys.forEach(rows::put)
        return prefs.edit().putString(SEEN_KEY, rows.toString()).commit()
    }
}
