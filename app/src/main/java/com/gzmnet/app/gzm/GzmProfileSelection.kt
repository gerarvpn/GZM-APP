package com.gzmnet.app.gzm

/**
 * Keeps the current profile only if it belongs to the imported subscription and can be decoded.
 * Otherwise it selects the first usable profile from that subscription, or null when none work.
 */
internal fun <T : Any> selectUsableGzmProfile(
    current: T?,
    importedProfiles: List<T>,
    canDecode: (T) -> Boolean,
): T? {
    fun usable(profile: T): Boolean = runCatching { canDecode(profile) }.getOrDefault(false)
    return current?.takeIf { it in importedProfiles && usable(it) }
        ?: importedProfiles.firstOrNull(::usable)
}
