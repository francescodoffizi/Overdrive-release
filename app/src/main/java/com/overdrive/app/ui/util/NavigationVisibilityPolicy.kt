package com.overdrive.app.ui.util

/**
 * Pure policy kept separate from SharedPreferences so migrations and corrupt
 * stored values can be covered by local unit tests.
 */
object NavigationVisibilityPolicy {
    @JvmStatic
    fun resolve(storedKeys: Set<String>?, knownKeys: Set<String>): Set<String> {
        if (storedKeys == null) return knownKeys.toSet()
        return storedKeys.filterTo(linkedSetOf()) { it in knownKeys }
    }

    /**
     * Resolve, then opt NEW rail items in.
     *
     * The stored set is an allow-list, so a key added by a later release is absent
     * from it and would be invisible on every existing install until the user found
     * the Appearance toggle. [seenKeys] records which keys the user has actually
     * been offered; anything known but never seen is treated as new and shown, so
     * a fresh feature is discoverable while a deliberately hidden item stays hidden.
     */
    @JvmStatic
    fun resolveWithNewDefaults(
        storedKeys: Set<String>?,
        knownKeys: Set<String>,
        seenKeys: Set<String>,
    ): Set<String> {
        if (storedKeys == null) return knownKeys.toSet()
        val visible = resolve(storedKeys, knownKeys).toMutableSet()
        visible += knownKeys.filterNot { it in seenKeys }
        return visible
    }

    @JvmStatic
    fun setVisible(
        currentKeys: Set<String>,
        key: String,
        visible: Boolean,
        knownKeys: Set<String>,
    ): Set<String> {
        if (key !in knownKeys) return resolve(currentKeys, knownKeys)
        return resolve(currentKeys, knownKeys).toMutableSet().apply {
            if (visible) add(key) else remove(key)
        }
    }
}
