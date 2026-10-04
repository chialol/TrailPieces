package com.trailpieces.journey

sealed interface JourneyPlace {
    data object Lens : JourneyPlace
    data object MoodList : JourneyPlace
    data object SceneryList : JourneyPlace
    data object TrailList : JourneyPlace
    data class MoodPhotos(val moodId: String) : JourneyPlace
    data class SceneryPhotos(val sceneryId: String) : JourneyPlace
    data class TrailStops(val trailId: String) : JourneyPlace
    data class Playing(val photoId: String, val trailId: String?) : JourneyPlace
}

/**
 * Browse stack for one walk. Continue replaces the playing photo so Back
 * returns to the list that opened it, not to the previous stop.
 */
class JourneyNav {
    private val stack = ArrayDeque<JourneyPlace>().apply { add(JourneyPlace.Lens) }

    val current: JourneyPlace get() = stack.last()

    val canPop: Boolean get() = stack.size > 1

    fun open(place: JourneyPlace) {
        stack.addLast(place)
    }

    fun back() {
        if (stack.size > 1) stack.removeLast()
    }

    fun continueTo(photoId: String, trailId: String?) {
        check(stack.last() is JourneyPlace.Playing)
        stack.removeLast()
        stack.addLast(JourneyPlace.Playing(photoId, trailId))
    }

    /** Keeps the trail a completion just resolved, without leaving the photo. */
    fun rememberTrail(trailId: String?) {
        val playing = stack.last() as? JourneyPlace.Playing ?: return
        stack.removeLast()
        stack.addLast(playing.copy(trailId = trailId))
    }

    /**
     * Leaves the photo. Opens [trailId] unless that trail is already showing.
     * A null trail returns to the list that opened the photo.
     */
    fun finishToTrail(trailId: String?) {
        check(stack.last() is JourneyPlace.Playing)
        stack.removeLast()
        if (trailId == null) return
        val top = stack.last()
        if (top is JourneyPlace.TrailStops && top.trailId == trailId) return
        stack.addLast(JourneyPlace.TrailStops(trailId))
    }
}
