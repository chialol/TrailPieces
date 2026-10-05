package com.trailpieces.journey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogParserTest {

    @Test
    fun parsesAndIgnoresUnknownKeys() {
        val catalog = CatalogParser.parse(
            """
            {
              "version": 1,
              "extra": true,
              "moods": [{ "id": "quiet", "title": "Quiet", "summary": "Still.", "note": "later" }],
              "sceneries": [{ "id": "forest", "title": "Forest", "summary": "" }],
              "photos": [{
                "id": "mossyrock",
                "title": "Mossy Rock",
                "sceneryId": "forest",
                "moodIds": ["quiet"],
                "blurb": "Shade."
              }],
              "trails": [{
                "id": "obsidian",
                "title": "Obsidian",
                "summary": "High country.",
                "photoIds": ["mossyrock"]
              }]
            }
            """.trimIndent(),
        )
        assertEquals("mossyrock", catalog.photos.single().revealId)
        assertEquals("quiet", catalog.photos.single().moodIds.single())
        assertEquals("", catalog.photos.single().story)
    }

    @Test
    fun readsStoryFromPhotoSceneryAndTrail() {
        val catalog = CatalogParser.parse(
            sampleJson()
                .replace(""""blurb": "A shaded boulder."""", """"blurb": "A shaded boulder.", "story": "A mossy place."""")
                .replace(""""summary": "Shade."""", """"summary": "Shade.", "story": "Trees close in."""")
                .replace(""""summary": "A short walk."""", """"summary": "A short walk.", "story": "Along the ridge.""""),
        )
        assertEquals("A mossy place.", catalog.photos.first().story)
        assertEquals("Trees close in.", catalog.sceneries.first { it.id == "forest" }.story)
        assertEquals("Along the ridge.", catalog.trails.single().story)
    }

    @Test
    fun revealIdDefaultsToPhotoId() {
        val catalog = sampleCatalog()
        assertEquals("mossyrock", catalog.photos.first { it.id == "mossyrock" }.revealId)
    }

    @Test
    fun rejectsUnknownVersion() {
        val error = assertFailsWith<CatalogFormatException> {
            CatalogParser.parse(sampleJson().replace(""""version": 1""", """"version": 2"""))
        }
        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun rejectsUnknownScenery() {
        val error = assertFailsWith<CatalogFormatException> {
            CatalogParser.parse(sampleJson().replace(""""sceneryId": "forest"""", """"sceneryId": "desert""""))
        }
        assertTrue(error.message!!.contains("desert"))
    }

    @Test
    fun rejectsTrailPhotoThatDoesNotExist() {
        val broken = sampleJson().replace(
            """"photoIds": ["mossyrock"]""",
            """"photoIds": ["mossyrock", "missing"]""",
        )
        val error = assertFailsWith<CatalogFormatException> { CatalogParser.parse(broken) }
        assertTrue(error.message!!.contains("missing"))
    }

    @Test
    fun shippedCatalogMatchesThePilots() {
        val file = shippedCatalogFile()
        val catalog = CatalogParser.parse(file.readText())
        val index = CatalogIndex(catalog)
        val obsidian = index.trail("obsidian")!!
        assertEquals(listOf("mossyrock", "mountain"), obsidian.photoIds)
        assertEquals("forest", index.photo("mossyrock")!!.sceneryId)
        assertEquals("alpine", index.photo("mountain")!!.sceneryId)
        assertTrue(index.sceneriesWithPhotos().none { it.id == "meadow" })
        assertTrue(catalog.sceneries.any { it.id == "meadow" })
        assertTrue(index.photo("multnomah")!!.story.contains("620"))
        assertTrue(index.trail("columbia")!!.story.isNotBlank())
    }
}

internal fun sampleCatalog(): Catalog = CatalogParser.parse(sampleJson())

internal fun sampleJson(): String = """
    {
      "version": 1,
      "moods": [
        { "id": "quiet", "title": "Quiet", "summary": "Still air." },
        { "id": "open", "title": "Open", "summary": "Wide light." }
      ],
      "sceneries": [
        { "id": "forest", "title": "Forest", "summary": "Shade." },
        { "id": "alpine", "title": "Alpine", "summary": "High rock." },
        { "id": "meadow", "title": "Meadow", "summary": "Open grass." }
      ],
      "photos": [
        {
          "id": "mossyrock",
          "title": "Mossy Rock",
          "sceneryId": "forest",
          "moodIds": ["quiet"],
          "blurb": "A shaded boulder."
        },
        {
          "id": "mountain",
          "title": "Mountain",
          "sceneryId": "alpine",
          "moodIds": ["open"],
          "revealId": "mountain",
          "blurb": "A long ridge."
        }
      ],
      "trails": [
        {
          "id": "obsidian",
          "title": "Obsidian",
          "summary": "A short walk.",
          "photoIds": ["mossyrock"]
        }
      ]
    }
""".trimIndent()

internal fun shippedCatalogFile(): java.io.File {
    val candidates = listOf(
        java.io.File("../app/src/main/assets/catalog/catalog.json"),
        java.io.File("android/app/src/main/assets/catalog/catalog.json"),
    )
    return candidates.firstOrNull { it.exists() }
        ?: error("catalog.json not found from ${java.io.File(".").absolutePath}")
}
