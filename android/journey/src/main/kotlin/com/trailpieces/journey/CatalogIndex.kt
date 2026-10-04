package com.trailpieces.journey

class CatalogIndex(val catalog: Catalog) {
    private val photosById = catalog.photos.associateBy { it.id }
    private val trailsById = catalog.trails.associateBy { it.id }
    private val moodsById = catalog.moods.associateBy { it.id }
    private val sceneriesById = catalog.sceneries.associateBy { it.id }

    fun photo(id: String): Photo? = photosById[id]

    fun trail(id: String): Trail? = trailsById[id]

    fun mood(id: String): Mood? = moodsById[id]

    fun scenery(id: String): Scenery? = sceneriesById[id]

    fun moodsWithPhotos(): List<Mood> {
        return catalog.moods.filter { mood -> catalog.photos.any { mood.id in it.moodIds } }
    }

    fun sceneriesWithPhotos(): List<Scenery> {
        val used = catalog.photos.map { it.sceneryId }.toSet()
        return catalog.sceneries.filter { it.id in used }
    }

    fun photosForMood(moodId: String): List<Photo> {
        return catalog.photos.filter { moodId in it.moodIds }
    }

    fun photosForScenery(sceneryId: String): List<Photo> {
        return catalog.photos.filter { it.sceneryId == sceneryId }
    }

    fun trailsForPhoto(photoId: String): List<Trail> {
        return catalog.trails.filter { photoId in it.photoIds }
    }

    /** First stop that is not collected, in walk order. */
    fun trailProgress(trail: Trail, progress: PlayerProgress): TrailProgress {
        val collected = trail.photoIds.count { it in progress.completedPhotoIds }
        val next = trail.photoIds.firstOrNull { it !in progress.completedPhotoIds }
        return TrailProgress(
            trailId = trail.id,
            collected = collected,
            total = trail.photoIds.size,
            nextPhotoId = next,
        )
    }
}

/**
 * Counts [photoId] as collected even when [progress] does not yet.
 * The next stop is the first later uncollected photo, wrapping once, and is never [photoId].
 * [trailId] is kept when that trail lists the photo; otherwise the first matching trail is used.
 */
fun continueAfter(
    catalog: Catalog,
    progress: PlayerProgress,
    photoId: String,
    trailId: String?,
): ContinueOffer {
    val trail = resolveTrail(catalog, photoId, trailId)
        ?: return ContinueOffer(trailId = null, collected = 0, total = 0, nextPhotoId = null)
    val completed = progress.completedPhotoIds + photoId
    val collected = trail.photoIds.count { it in completed }
    val start = trail.photoIds.indexOf(photoId)
    var next: String? = null
    if (start >= 0) {
        val count = trail.photoIds.size
        for (step in 1 until count) {
            val candidate = trail.photoIds[(start + step) % count]
            if (candidate !in completed) {
                next = candidate
                break
            }
        }
    }
    return ContinueOffer(
        trailId = trail.id,
        collected = collected,
        total = trail.photoIds.size,
        nextPhotoId = next,
    )
}

private fun resolveTrail(catalog: Catalog, photoId: String, trailId: String?): Trail? {
    if (trailId != null) {
        val named = catalog.trails.find { it.id == trailId }
        if (named != null && photoId in named.photoIds) return named
    }
    return catalog.trails.firstOrNull { photoId in it.photoIds }
}
