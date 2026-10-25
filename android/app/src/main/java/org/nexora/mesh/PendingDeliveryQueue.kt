package org.nexora.mesh

/**
 * In-memory state machine for pending encrypted wire frames.
 * The persistence callback must synchronously commit the entire snapshot before returning true.
 */
internal class PendingDeliveryQueue(
    initial: Map<String, String> = emptyMap(),
    private val maxSize: Int = 1000,
    private val persist: (Map<String, String>) -> Boolean
) {
    private val entries = LinkedHashMap<String, String>().apply {
        require(maxSize > 0) { "maxSize must be positive" }
        initial.entries.take(maxSize).forEach { (id, frame) -> put(id, frame) }
    }

    @Synchronized fun enqueue(id: String, frame: String): Boolean {
        if (id.isBlank() || frame.isBlank()) return false
        if (!entries.containsKey(id) && entries.size >= maxSize) return false
        val previous = LinkedHashMap(entries)
        entries[id] = frame
        if (persist(LinkedHashMap(entries))) return true
        entries.clear()
        entries.putAll(previous)
        return false
    }

    @Synchronized fun acknowledge(id: String): Boolean {
        if (!entries.containsKey(id)) return false
        val previous = LinkedHashMap(entries)
        entries.remove(id)
        if (persist(LinkedHashMap(entries))) return true
        entries.clear()
        entries.putAll(previous)
        return false
    }

    @Synchronized fun get(id: String): String? = entries[id]
    @Synchronized fun snapshot(): Map<String, String> = LinkedHashMap(entries)
    @Synchronized fun frames(): List<String> = entries.values.toList()
    @Synchronized fun size(): Int = entries.size
}
