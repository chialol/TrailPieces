package com.trailpieces.journey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JourneyNavTest {

    @Test
    fun backFromAPhotoReturnsToTheListThatOpenedIt() {
        val nav = moodThenPhoto()
        nav.back()
        assertEquals(JourneyPlace.MoodPhotos("quiet"), nav.current)
    }

    @Test
    fun continueReplacesThePhotoAndBackStillReturnsToTheOpeningList() {
        val nav = moodThenPhoto()
        nav.continueTo(photoId = "mountain", trailId = "obsidian")
        assertEquals(JourneyPlace.Playing("mountain", "obsidian"), nav.current)
        nav.back()
        assertEquals(JourneyPlace.MoodPhotos("quiet"), nav.current)
    }

    @Test
    fun rememberTrailStaysOnTheSamePhoto() {
        val nav = moodThenPhoto()
        nav.rememberTrail("obsidian")
        assertEquals(JourneyPlace.Playing("mossyrock", "obsidian"), nav.current)
    }

    @Test
    fun finishingFromAMoodOpensTheTrail() {
        val nav = moodThenPhoto()
        nav.rememberTrail("obsidian")
        nav.finishToTrail("obsidian")
        assertEquals(JourneyPlace.TrailStops("obsidian"), nav.current)
        nav.back()
        assertEquals(JourneyPlace.MoodPhotos("quiet"), nav.current)
    }

    @Test
    fun finishingOntoTheTrailAlreadyOpenDoesNotStackItAgain() {
        val nav = JourneyNav()
        nav.open(JourneyPlace.TrailList)
        nav.open(JourneyPlace.TrailStops("obsidian"))
        nav.open(JourneyPlace.Playing("mossyrock", "obsidian"))
        nav.finishToTrail("obsidian")
        assertEquals(JourneyPlace.TrailStops("obsidian"), nav.current)
        nav.back()
        assertEquals(JourneyPlace.TrailList, nav.current)
    }

    @Test
    fun finishingWithoutATrailReturnsToTheOpeningList() {
        val nav = moodThenPhoto()
        nav.finishToTrail(null)
        assertEquals(JourneyPlace.MoodPhotos("quiet"), nav.current)
        assertFalse(nav.current is JourneyPlace.Playing)
    }

    private fun moodThenPhoto(): JourneyNav {
        val nav = JourneyNav()
        nav.open(JourneyPlace.MoodList)
        nav.open(JourneyPlace.MoodPhotos("quiet"))
        nav.open(JourneyPlace.Playing("mossyrock", null))
        return nav
    }
}
