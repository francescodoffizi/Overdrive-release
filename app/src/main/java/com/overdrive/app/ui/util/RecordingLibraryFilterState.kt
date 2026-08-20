package com.overdrive.app.ui.util

/**
 * Normalized narrowing state shared by the indexed and direct-filesystem
 * recording-library paths.
 *
 * The daemon currently accepts one exact place label. Keeping that contract
 * explicit here prevents the UI from presenting multi-select place chips
 * while silently sending only one selection.
 */
data class RecordingLibraryFilterState(
    val actorClasses: Set<String> = emptySet(),
    val severities: Set<String> = emptySet(),
    val places: Set<String> = emptySet(),
    val placeContains: String = "",
    val storages: Set<String> = emptySet(),
    val dateNarrowed: Boolean = false
) {
    val normalizedActorClasses: Set<String> =
        actorClasses.mapTo(linkedSetOf()) { it.trim().lowercase() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
    val normalizedSeverities: Set<String> =
        severities.mapTo(linkedSetOf()) { it.trim().uppercase() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
    val exactPlace: String? =
        places.asSequence().map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() }
    val normalizedPlaceContains: String = placeContains.trim().lowercase()
    val normalizedStorages: Set<String> =
        storages.mapTo(linkedSetOf()) { it.trim().uppercase() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }

    val hasActiveNarrowing: Boolean
        get() = dateNarrowed ||
            normalizedActorClasses.isNotEmpty() ||
            normalizedSeverities.isNotEmpty() ||
            exactPlace != null ||
            normalizedPlaceContains.isNotEmpty() ||
            normalizedStorages.isNotEmpty()

    fun matchesFallback(
        storageType: String?,
        placeShortLabel: String?,
        placeMediumLabel: String?,
        placeDisplayName: String?,
        actorClasses: Collection<String>,
        peakSeverity: String?,
        hasSidecar: Boolean
    ): Boolean {
        if (normalizedStorages.isNotEmpty() &&
            storageType?.uppercase() !in normalizedStorages) {
            return false
        }
        if (exactPlace != null && placeShortLabel?.lowercase() != exactPlace) {
            return false
        }
        if (normalizedPlaceContains.isNotEmpty()) {
            val searchablePlaces = sequenceOf(
                placeShortLabel,
                placeMediumLabel,
                placeDisplayName
            )
            if (searchablePlaces.none {
                    it?.contains(normalizedPlaceContains, ignoreCase = true) == true
                }) {
                return false
            }
        }
        if (!hasSidecar) {
            return normalizedActorClasses.isEmpty() && normalizedSeverities.isEmpty()
        }
        val actorMatches = normalizedActorClasses.isEmpty() ||
            actorClasses.any { it.lowercase() in normalizedActorClasses }
        val severityMatches = normalizedSeverities.isEmpty() ||
            peakSeverity?.uppercase() in normalizedSeverities
        return actorMatches && severityMatches
    }
}
