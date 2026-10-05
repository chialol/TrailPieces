package com.trailpieces.journey

/**
 * The catalog document. A later `GET /v1/catalog` returns this same shape.
 * [Photo.revealId] is the only link to reveal image files; the catalog does not store paths.
 */
data class Mood(
    val id: String,
    val title: String,
    val summary: String,
)

data class Scenery(
    val id: String,
    val title: String,
    val summary: String,
    val story: String = "",
)

data class Photo(
    val id: String,
    val title: String,
    val sceneryId: String,
    val moodIds: List<String>,
    /** Folder under `assets/reveal/`. Defaults to [id] when authoring omits it. */
    val revealId: String,
    val blurb: String,
    /** About this place. Shown after the photo settles. */
    val story: String = "",
)

data class Trail(
    val id: String,
    val title: String,
    val summary: String,
    /** Walk order. A photo may appear on more than one trail. */
    val photoIds: List<String>,
    val story: String = "",
)

data class Catalog(
    val version: Int,
    val moods: List<Mood>,
    val sceneries: List<Scenery>,
    val photos: List<Photo>,
    val trails: List<Trail>,
)

enum class PlayerKind { Local, Account }

data class Player(
    val id: String,
    val displayName: String?,
    val kind: PlayerKind,
)

data class PlayerProgress(
    val playerId: String,
    val completedPhotoIds: Set<String>,
)

/** Resume point for a trail screen: the first stop not yet collected, in walk order. */
data class TrailProgress(
    val trailId: String,
    val collected: Int,
    val total: Int,
    val nextPhotoId: String?,
)

/**
 * What to show the moment a photo is finished.
 * [nextPhotoId] is the next uncollected stop after this photo, wrapping once.
 * It is never the photo just finished.
 */
data class ContinueOffer(
    val trailId: String?,
    val collected: Int,
    val total: Int,
    val nextPhotoId: String?,
)

class CatalogFormatException(message: String) : IllegalArgumentException(message)
