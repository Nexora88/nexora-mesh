package org.nexora.mesh

/**
 * Bounded persistent replay window for authenticated message IDs.
 * The caller must invoke this only after successful AEAD authentication.
 */
internal class ReplayWindow(
    initial: Collection<String> = emptyList(),
    private val maxSize: Int = 4096,
    private val persist: (List<String>) -> Boolean
) {
    private val ids = LinkedHashSet<String>().apply {
        require(maxSize > 0)
        initial.filter { it.isNotBlank() }.takeLast(maxSize).forEach { add(it) }
    }

    @Synchronized
    fun remember(id: String): Boolean {
        if (id.isBlank() || ids.contains(id)) return false
        val before = ids.toList()
        ids.add(id)
        while (ids.size > maxSize) ids.remove(ids.first())
        if (!persist(ids.toList())) {
            ids.clear()
            ids.addAll(before)
            return false
        }
        return true
    }

    @Synchronized
    fun snapshot(): List<String> = ids.toList()
}
