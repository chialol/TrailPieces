package com.trailpieces.journey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinueAfterTest {

    private val catalog = twoTrailCatalog()
    private val empty = PlayerProgress(playerId = "p", completedPhotoIds = emptySet())

    @Test
    fun countsThePhotoJustFinishedEvenWhenProgressIsStale() {
        val offer = continueAfter(catalog, empty, photoId = "a", trailId = "east")
        assertEquals("east", offer.trailId)
        assertEquals(1, offer.collected)
        assertEquals(3, offer.total)
        assertEquals("b", offer.nextPhotoId)
    }

    @Test
    fun finishingTheLastOpenStopDoesNotOfferThatPhotoAgain() {
        val progress = PlayerProgress("p", setOf("a", "b"))
        val offer = continueAfter(catalog, progress, photoId = "c", trailId = "east")
        assertEquals(3, offer.collected)
        assertEquals(3, offer.total)
        assertNull(offer.nextPhotoId)
    }

    @Test
    fun wrapsOnceToAnEarlierGap() {
        val progress = PlayerProgress("p", setOf("c"))
        val offer = continueAfter(catalog, progress, photoId = "b", trailId = "east")
        assertEquals("a", offer.nextPhotoId)
        assertEquals(2, offer.collected)
    }

    @Test
    fun keepsTheTrailThatWasPassedIn() {
        val offer = continueAfter(catalog, empty, photoId = "shared", trailId = "west")
        assertEquals("west", offer.trailId)
        assertEquals("only-west", offer.nextPhotoId)
    }

    @Test
    fun fallsThroughWhenTheNamedTrailDoesNotListThePhoto() {
        val offer = continueAfter(catalog, empty, photoId = "a", trailId = "west")
        assertEquals("east", offer.trailId)
    }

    @Test
    fun photoOnNoTrailHasNothingToContinue() {
        val offer = continueAfter(catalog, empty, photoId = "loose", trailId = null)
        assertNull(offer.trailId)
        assertNull(offer.nextPhotoId)
        assertEquals(0, offer.total)
    }

    @Test
    fun trailProgressResumesAtTheFirstGap() {
        val progress = PlayerProgress("p", setOf("b"))
        val trail = catalog.trails.first { it.id == "east" }
        val status = CatalogIndex(catalog).trailProgress(trail, progress)
        assertEquals(1, status.collected)
        assertEquals("a", status.nextPhotoId)
    }
}

private fun twoTrailCatalog(): Catalog {
    return Catalog(
        version = 1,
        moods = emptyList(),
        sceneries = emptyList(),
        photos = listOf("a", "b", "c", "shared", "only-west", "loose").map { id ->
            Photo(id, id, sceneryId = "x", moodIds = emptyList(), revealId = id, blurb = "")
        },
        trails = listOf(
            Trail("east", "East", "", listOf("a", "b", "c")),
            Trail("west", "West", "", listOf("shared", "only-west")),
            Trail("both", "Both", "", listOf("shared")),
        ),
    )
}
