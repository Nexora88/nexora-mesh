package org.nexora.mesh

/**
 * Bounded persistent replay window for authenticated message IDs.
 * The caller must invoke this only after successful AEAD authentication.
 */
internal enum class ReplayRememberResult {
    ADDED,
    DUPLICATE,
    PERSISTENCE_FAILED,
    INVALID
}

internal class ReplayWindow(
    initial: Collection<String> = emptyList(),
    private val maxSize: Int = 4096,
    private val persist: (List<String>) -> Boolean
) {
    private val ids = LinkedHashSet<String>().apply {
        require(maxSize > 0)
        initial.filter { it.isNotBlank() }.takeLast(maxSize).forEach { add(it) }
    }

    fun remember(id: String): Boolean =
        rememberResult(id) == ReplayRememberResult.ADDED

    @Synchronized
    fun rememberResult(id: String): ReplayRememberResult {
        if (id.isBlank()) return ReplayRememberResult.INVALID
        if (ids.contains(id)) return ReplayRememberResult.DUPLICATE
        val before = ids.toList()
        ids.add(id)
        while (ids.size > maxSize) ids.remove(ids.first())
        if (!persist(ids.toList())) {
            ids.clear()
            ids.addAll(before)
            return ReplayRememberResult.PERSISTENCE_FAILED
        }
        return ReplayRememberResult.ADDED
    }

    @Synchronized
    fun snapshot(): List<String> = ids.toList()
}
