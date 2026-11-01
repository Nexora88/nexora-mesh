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
        private const val RECEIPTS_KEY = "stored_receipts"
        private const val OPT_IN_KEY = "relay_opt_in"
        private const val MAX_ITEMS = 128
        private const val MAX_SEEN = 512
        private const val MAX_RECEIPTS = 512
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val queue = LinkedHashMap<String, ByteArray>()
    private val lastHopById = LinkedHashMap<String, ByteArray>()
    private val receipts = LinkedHashMap<String, ByteArray>()
    private val receiptLastHop = LinkedHashMap<String, ByteArray>()
    private val seen = LinkedHashMap<String, Unit>()

    init {
        synchronized(lock) {
            runCatching {
                val saved = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
                for (i in 0 until minOf(saved.length(), MAX_ITEMS)) {
                    val row = saved.getJSONObject(i)
                    val id = row.getString("id")
                    val bytes = Base64.decode(row.getString("frame"), Base64.NO_WRAP)
                    if (id.matches(Regex("[0-9a-f]{32}")) && bytes.size in 49..(256 * 1024)) {
                        queue[id] = bytes
                        val lastHop = row.optString("lastHop", "")
                        if (lastHop.isNotBlank()) {
                            val decoded = Base64.decode(lastHop, Base64.NO_WRAP)
                            if (decoded.size == 16) lastHopById[id] = decoded
                        }
                    }
                }
            }
            runCatching {
                val savedReceipts = JSONArray(prefs.getString(RECEIPTS_KEY, "[]"))
                for (i in 0 until minOf(savedReceipts.length(), MAX_RECEIPTS)) {
                    val row = savedReceipts.getJSONObject(i)
                    val id = row.getString("id")
                    val bytes = Base64.decode(row.getString("frame"), Base64.NO_WRAP)
                    if (id.matches(Regex("[0-9a-f]{32}")) && bytes.size in 49..(256 * 1024)) {
                        receipts[id] = bytes
                        val lastHop = row.optString("lastHop", "")
                        if (lastHop.isNotBlank()) {
                            val decoded = Base64.decode(lastHop, Base64.NO_WRAP)
                            if (decoded.size == 16) receiptLastHop[id] = decoded
                        }
                    }
                }
            }
            runCatching {
                val saved = JSONArray(prefs.getString(SEEN_KEY, "[]"))
                for (i in 0 until minOf(saved.length(), MAX_SEEN)) {
                    val id = saved.getString(i)
                    if (id.matches(Regex("[0-9a-f]{32}"))) seen[id] = Unit
                }
            }
            if (!prefs.getBoolean(OPT_IN_KEY, false)) {
                queue.clear()
                lastHopById.clear()
                receipts.clear()
                receiptLastHop.clear()
                persistAll()
            }
        }
    }

    fun isOptedIn(): Boolean = prefs.getBoolean(OPT_IN_KEY, false)

    fun setOptIn(enabled: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(OPT_IN_KEY, enabled).commit()
            if (!enabled) {
                queue.clear()
                lastHopById.clear()
                receipts.clear()
                receiptLastHop.clear()
                persistAll()
            }
        }
    }

    /** Validate through Rust before persisting. Duplicate message IDs are rejected. */
    fun enqueue(envelope: ByteArray, fromNodeId: ByteArray? = null): Boolean = synchronized(lock) {
        if (!isOptedIn() || envelope.size !in 49..(256 * 1024)) return false
        if (runCatching { NativeMesh.validate(envelope) }.getOrDefault(-1) != 0) return false
        val id = envelope.copyOfRange(3, 19).joinToString("") { "%02x".format(it) }
        if (seen.containsKey(id) || queue.containsKey(id) || queue.size >= MAX_ITEMS) return false
        if (fromNodeId != null && fromNodeId.size != 16) return false
        val previousSeen = seen.keys.toList()
        queue[id] = envelope.copyOf()
        if (fromNodeId != null) lastHopById[id] = fromNodeId.copyOf()
        seen[id] = Unit
        while (seen.size > MAX_SEEN) seen.remove(seen.keys.first())
        if (persistAll()) return true
        queue.remove(id)
        lastHopById.remove(id)
        seen.clear()
        previousSeen.forEach { seen[it] = Unit }
        false
    }

    /** Returns a hop-decremented copy of one selected stored envelope. */
    fun peekForNextHop(messageId: ByteArray, nowUnixSeconds: Long): ByteArray? = synchronized(lock) {
        if (!isOptedIn() || messageId.size != 16) return null
        val id = messageId.joinToString("") { "%02x".format(it) }
        val frame = queue[id] ?: return null
        runCatching { NativeMesh.forward(frame, nowUnixSeconds) }.getOrNull()
    }

    /** Returns the first queued item for a peer with a pending next-hop opportunity. */
    fun peekForNextHop(nowUnixSeconds: Long): ByteArray? = synchronized(lock) {
        if (!isOptedIn()) return null
        val frame = queue.values.firstOrNull() ?: return null
        runCatching { NativeMesh.forward(frame, nowUnixSeconds) }.getOrNull()
    }

    /** Remove a locally delivered envelope only after the chat layer accepts its ciphertext. */
    fun removeDeliveredLocally(messageId: ByteArray): Boolean = synchronized(lock) {
        if (messageId.size != 16) return false
        val id = messageId.joinToString("") { "%02x".format(it) }
        val removed = queue.remove(id) ?: return false
        val previousHop = lastHopById.remove(id)
        receipts[id] = removed
        if (previousHop != null) receiptLastHop[id] = previousHop
        while (receipts.size > MAX_RECEIPTS) {
            val oldest = receipts.keys.first()
            receipts.remove(oldest)
            receiptLastHop.remove(oldest)
        }
        if (persistAll()) return true
        receipts.remove(id)
        receiptLastHop.remove(id)
        queue[id] = removed
        if (previousHop != null) lastHopById[id] = previousHop
        false
    }

    /** Create a STORE_ACK only after enqueue() has durably committed this envelope. */
    fun createStoredAck(envelope: ByteArray, fromNode: ByteArray, toNode: ByteArray,
                        hopKey: ByteArray, nowUnixSeconds: Long, nonce: ByteArray): ByteArray? = synchronized(lock) {
        if (!isOptedIn() || envelope.size < 49 || NativeMesh.validate(envelope) != 0) return null
        if (fromNode.size != 16 || toNode.size != 16 || nonce.size != 16 || hopKey.size < 32) return null
        val id = envelope.copyOfRange(3, 19).joinToString("") { "%02x".format(it) }
        val stored = queue[id] ?: receipts[id]
        if (stored?.contentEquals(envelope) != true) return null
        runCatching {
            NativeMesh.createStoreAck(envelope.copyOfRange(3, 19), fromNode, toNode, nowUnixSeconds, nonce, hopKey)
        }.getOrNull()
    }

    /** A queue item is removed only after Rust verifies MAC, peer IDs and ACK freshness. */
    fun acknowledgeStoredByNextHop(ack: ByteArray, hopKey: ByteArray,
                                   expectedFrom: ByteArray, expectedTo: ByteArray,
                                   nowUnixSeconds: Long, maxAgeSeconds: Long): Boolean = synchronized(lock) {
        if (ack.size != 105 || NativeMesh.verifyStoreAck(
                ack, hopKey, expectedFrom, expectedTo, nowUnixSeconds, maxAgeSeconds) != 0) return false
        val id = ack.copyOfRange(1, 17).joinToString("") { "%02x".format(it) }
        val removed = queue.remove(id) ?: return false
        val previousHop = lastHopById.remove(id)
        receipts[id] = removed
        if (previousHop != null) receiptLastHop[id] = previousHop
        while (receipts.size > MAX_RECEIPTS) {
            val oldest = receipts.keys.first()
            receipts.remove(oldest)
            receiptLastHop.remove(oldest)
        }
        if (persistAll()) return true
        receipts.remove(id)
        receiptLastHop.remove(id)
        queue[id] = removed
        if (previousHop != null) lastHopById[id] = previousHop
        false
    }

    fun pendingMessageIds(): List<ByteArray> = synchronized(lock) {
        queue.values.map { it.copyOfRange(3, 19) }
    }

    fun firstPendingMessageId(): ByteArray? = pendingMessageIds().firstOrNull()

    fun lastHopNodeId(messageId: ByteArray): ByteArray? = synchronized(lock) {
        if (messageId.size != 16) return null
        val id = messageId.joinToString("") { "%02x".format(it) }
        (lastHopById[id] ?: receiptLastHop[id])?.copyOf()
    }

    fun pendingCount(): Int = synchronized(lock) { queue.size }

    /** Queue and replay window are committed together to avoid half-written state. */
    private fun persistAll(): Boolean {
        val rows = JSONArray()
        queue.forEach { (id, bytes) ->
            val row = org.json.JSONObject()
                .put("id", id)
                .put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
            lastHopById[id]?.let { row.put("lastHop", Base64.encodeToString(it, Base64.NO_WRAP)) }
            rows.put(row)
        }
        val receiptRows = JSONArray()
        receipts.forEach { (id, bytes) ->
            val row = org.json.JSONObject()
                .put("id", id)
                .put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
            receiptLastHop[id]?.let { row.put("lastHop", Base64.encodeToString(it, Base64.NO_WRAP)) }
            receiptRows.put(row)
        }
        val replay = JSONArray()
        seen.keys.forEach(replay::put)
        return prefs.edit()
            .putString(QUEUE_KEY, rows.toString())
            .putString(RECEIPTS_KEY, receiptRows.toString())
            .putString(SEEN_KEY, replay.toString())
            .commit()
    }
}
