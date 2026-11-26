package com.trailpieces.journey

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLibraryTest {
    private val catalogUrl = "https://cdn.example/content/v1/catalog.json"
    private val picture = "picture-bytes".encodeToByteArray()
    private val pictureSha = sha(picture)

    @Test
    fun keepsBundledPhotoWordingAndAppendsATrailStop() {
        val bundled = sampleCatalog()
        val remote = document(
            photoId = "extra",
            title = "Extra",
            trailId = "obsidian",
            trailTitle = "Remote name",
        )
        val merged = mergeCatalogs(bundled, listOf(remote))
        assertEquals("Mossy Rock", merged.photos.first { it.id == "mossyrock" }.title)
        assertEquals("Extra", merged.photos.first { it.id == "extra" }.title)
        val trail = merged.trails.first { it.id == "obsidian" }
        assertEquals("Obsidian", trail.title)
        assertEquals(listOf("mossyrock", "extra"), trail.photoIds)
    }

    @Test
    fun addsANewSceneryAndMergesStopsFromEachPackage() {
        val first = checkNotNull(PackageDocumentParser.parse(railwayPackage("mianyue", "Mist on the grade", "Mianyue Line")))
        val second = checkNotNull(
            PackageDocumentParser.parse(railwayPackage("switchback", "The next cut", "Alishan Forest Railway", "alishan")),
        )
        val merged = mergeCatalogs(sampleCatalog(), listOf(first, second))
        assertEquals("Railway", merged.sceneries.first { it.id == "railway" }.title)
        assertEquals("Atmospheric", merged.moods.first { it.id == "atmospheric" }.title)
        assertEquals("railway", merged.photos.first { it.id == "mianyue" }.sceneryId)
        val trail = merged.trails.first { it.id == "mianyue-line" }
        assertEquals("Alishan Forest Railway", trail.title)
        assertEquals(listOf("mianyue", "switchback"), trail.photoIds)
        assertEquals("Obsidian", merged.trails.first { it.id == "obsidian" }.title)
    }

    @Test
    fun keepsTheBundledClassificationWhenAPackageDisagrees() {
        val bundled = sampleCatalog()
        val remote = document(photoId = "extra", title = "Extra").let { document ->
            document.copy(
                editorial = document.editorial.copy(
                    moods = listOf(Mood("quiet", "Loud", "Different.")),
                ),
            )
        }
        val merged = mergeCatalogs(bundled, listOf(remote))
        assertEquals("Quiet", merged.moods.first { it.id == "quiet" }.title)
    }

    @Test
    fun unknownAccessAndUnknownUnlockDoNotGrantThePackage() {
        val root = tempDir()
        val transport = ScriptTransport()
        transport.catalog = discovery(
            packageEntry(id = "closed", access = "entitlement", unlock = """{"type":"immediate"}"""),
            packageEntry(id = "mystery", access = "public", unlock = """{"type":"purchase"}"""),
        )
        val library = library(root, transport)
        library.refresh(emptyProgress())
        val merged = library.merged(Catalog(1, emptyList(), emptyList(), emptyList(), emptyList()), emptyProgress())
        assertTrue(merged.photos.isEmpty())
        assertTrue(transport.urls.none { it.contains("metadata.json") })
        val phases = library.statuses(emptyProgress()).associate { it.id to it.phase }
        assertEquals(PackagePhase.Discovered, phases["closed"])
        assertEquals(PackagePhase.Locked, phases["mystery"])
    }

    @Test
    fun puzzlesCompletedCountsDistinctSavedCompletions() {
        val finished = PlayerProgress("player", setOf("mossyrock", "mountain", "retired-photo"))
        assertFalse(
            UnlockEvaluator.isUnlocked(UnlockRule.PuzzlesCompleted(4), finished, emptyMap()),
        )
        assertTrue(
            UnlockEvaluator.isUnlocked(UnlockRule.PuzzlesCompleted(3), finished, emptyMap()),
        )
        assertFalse(
            UnlockEvaluator.isUnlocked(UnlockRule.Unsupported("purchase"), finished, emptyMap()),
        )
    }

    @Test
    fun packageCompletedUsesThatPackagesPhotoIdsAndRejectsACycle() {
        val cedar = document(photoId = "cedar-grove", title = "Cedar", packageId = "cedar")
        val other = document(photoId = "other-photo", title = "Other", packageId = "other")
        val progress = PlayerProgress("player", setOf("cedar-grove"))
        assertTrue(
            UnlockEvaluator.isUnlocked(
                UnlockRule.PackageCompleted("cedar"),
                progress,
                mapOf("cedar" to cedar),
            ),
        )
        assertFalse(
            UnlockEvaluator.isUnlocked(
                UnlockRule.PackageCompleted("other"),
                progress,
                mapOf("other" to other),
            ),
        )
        assertFalse(
            UnlockEvaluator.isUnlocked(
                UnlockRule.PackageCompleted("cedar"),
                progress,
                mapOf("cedar" to cedar),
                stack = setOf("cedar"),
            ),
        )
    }

    @Test
    fun downloadsAnUnlockedPackageAndSkipsBytesItAlreadyHas() {
        val root = tempDir()
        val transport = readyTransport()
        val library = library(root, transport)
        val progress = emptyProgress()
        val first = library.refresh(progress)
        assertTrue(first.catalogUpdated)
        val merged = library.merged(Catalog(1, emptyList(), emptyList(), emptyList(), emptyList()), progress)
        assertEquals("Cedar", merged.photos.single().title)
        assertTrue(library.coverFile("cedar-grove")!!.isFile)
        assertEquals(PackagePhase.Downloaded, library.statuses(progress).single().phase)
        val fetches = transport.urls.size
        library.refresh(progress)
        assertEquals(fetches + 1, transport.urls.size)
        assertTrue(transport.urls.last().endsWith("catalog.json"))
    }

    @Test
    fun aLockedPackageFetchesMetadataAndWaitsToDownloadImages() {
        val root = tempDir()
        val transport = readyTransport(unlock = """{"type":"puzzlesCompleted","count":1}""")
        val library = library(root, transport)
        library.refresh(emptyProgress())
        assertTrue(transport.urls.any { it.endsWith("metadata.json") })
        assertTrue(transport.urls.none { it.contains("/assets/") })
        assertTrue(library.merged(emptyCatalog(), emptyProgress()).photos.isEmpty())
        val played = PlayerProgress("player", setOf("anywhere"))
        library.reevaluate(played)
        assertTrue(transport.urls.any { it.contains("/assets/") })
        assertEquals("Cedar", library.merged(emptyCatalog(), played).photos.single().title)
    }

    @Test
    fun malformedAndUnsupportedCatalogsKeepTheLastGoodCopy() {
        val root = tempDir()
        val transport = readyTransport()
        val library = library(root, transport)
        library.refresh(emptyProgress())
        transport.catalog = """{"schemaVersion":9}"""
        val rejected = library.refresh(emptyProgress())
        assertFalse(rejected.catalogUpdated)
        assertTrue(rejected.message.contains("last catalog"))
        assertEquals("Cedar", library.merged(emptyCatalog(), emptyProgress()).photos.single().title)
        transport.fail = true
        val offline = library.refresh(emptyProgress())
        assertFalse(offline.catalogUpdated)
        assertEquals("Cedar", library.merged(emptyCatalog(), emptyProgress()).photos.single().title)
    }

    @Test
    fun aPackageThatLeavesTheCatalogStaysOnDisk() {
        val root = tempDir()
        val transport = readyTransport()
        val library = library(root, transport)
        library.refresh(emptyProgress())
        transport.catalog = """{"schemaVersion":1,"revision":"later","packages":[]}"""
        library.refresh(emptyProgress())
        val merged = library.merged(emptyCatalog(), emptyProgress())
        assertEquals("cedar-grove", merged.photos.single().id)
        assertEquals(PackagePhase.Downloaded, library.statuses(emptyProgress()).single().phase)
    }

    @Test
    fun aMetadataUpdateKeepsCompletionForTheSamePhotoId() {
        val root = tempDir()
        val transport = readyTransport()
        val library = library(root, transport)
        val players = FilePlayerRepository(File(root, "player.json"))
        val progressRepository = FileProgressRepository(File(root, "progress.json"), players)
        val progress = kotlinx.coroutines.runBlocking { progressRepository.recordCompletion("cedar-grove") }
        library.refresh(progress)
        transport.catalog = discovery(
            packageEntry(release = "2", metadata = "packages/cedar/releases/2/metadata.json"),
        )
        transport.bodies[metadataUrl("packages/cedar/releases/2/metadata.json")] =
            packageDocument(title = "Renamed cedar", release = "2").encodeToByteArray()
        library.refresh(progress)
        val saved = kotlinx.coroutines.runBlocking { progressRepository.load() }
        assertEquals(setOf("cedar-grove"), saved.completedPhotoIds)
        assertEquals("Renamed cedar", library.merged(emptyCatalog(), saved).photos.single().title)
    }

    @Test
    fun aBadImageHashDoesNotReplaceTheBundledCatalog() {
        val root = tempDir()
        val transport = readyTransport()
        transport.bodies[assetUrl()] = "not-the-picture".encodeToByteArray()
        val library = library(root, transport)
        library.refresh(emptyProgress())
        val bundled = sampleCatalog()
        val merged = library.merged(bundled, emptyProgress())
        assertEquals(bundled.photos.map { it.id }, merged.photos.filter { it.id == "mossyrock" }.map { it.id })
        assertEquals("Cedar", merged.photos.first { it.id == "cedar-grove" }.title)
        assertNull(library.coverFile("cedar-grove"))
        assertEquals(PackagePhase.Failed, library.statuses(emptyProgress()).first { it.id == "cedar" }.phase)
    }

    @Test
    fun anInterruptedImageResumesFromThePartialFile() {
        val root = tempDir()
        val transport = readyTransport()
        val library = library(root, transport)
        val partial = File(root, "images/cedar-grove.$pictureSha.webp.partial")
        partial.parentFile?.mkdirs()
        partial.writeBytes(picture.copyOfRange(0, 4))
        transport.ranged = picture.copyOfRange(4, picture.size)
        library.refresh(emptyProgress())
        assertEquals(pictureSha, sha256(File(root, "images/cedar-grove.$pictureSha.webp")))
        assertTrue(transport.urls.any { url -> transport.ranges[url] == "bytes=4-" })
    }

    @Test
    fun unsafePathsAndOldAppVersionsAreRefused() {
        assertFalse(RemotePaths.isSafeRelative("../secret.webp"))
        assertFalse(RemotePaths.isSafeRelative("https://elsewhere/a.webp"))
        val parsed = DiscoveryParser.parse(
            discovery(packageEntry(metadata = "../metadata.json")),
        )
        assertTrue(parsed is DiscoveryParse.Rejected)
        assertFalse(appSupports("0.1.0", "9.0.0"))
        assertTrue(appSupports("0.1.0", "0.1.0"))
        val root = tempDir()
        val transport = readyTransport(minApp = "9.0.0")
        val library = library(root, transport, appVersion = "0.1.0")
        library.refresh(emptyProgress())
        assertTrue(library.merged(emptyCatalog(), emptyProgress()).photos.isEmpty())
        assertEquals(PackagePhase.Locked, library.statuses(emptyProgress()).single().phase)
    }

    private fun library(root: File, transport: ScriptTransport, appVersion: String = "0.1.0") =
        RemoteLibrary(root, transport, catalogUrl, appVersion)

    private fun readyTransport(
        unlock: String = """{"type":"immediate"}""",
        minApp: String? = null,
    ): ScriptTransport {
        val transport = ScriptTransport()
        transport.catalog = discovery(packageEntry(unlock = unlock, minApp = minApp))
        transport.bodies[metadataUrl("packages/cedar/releases/1/metadata.json")] =
            packageDocument().encodeToByteArray()
        transport.bodies[assetUrl()] = picture
        return transport
    }

    private fun discovery(vararg packages: String) =
        """{"schemaVersion":1,"revision":"r1","packages":[${packages.joinToString(",")}]}"""

    private fun packageEntry(
        id: String = "cedar",
        release: String = "1",
        metadata: String = "packages/$id/releases/$release/metadata.json",
        access: String = "public",
        unlock: String = """{"type":"immediate"}""",
        minApp: String? = null,
    ): String {
        val minimum = if (minApp == null) "" else ""","minAppVersion":"$minApp""""
        return """
            {
              "id": "$id",
              "title": "Cedar",
              "description": "Forest",
              "release": "$release",
              "cover": "packages/$id/releases/$release/previews/cover.webp",
              "metadata": "$metadata",
              "photoCount": 1,
              "downloadBytes": 12,
              "access": "$access",
              "unlock": $unlock,
              "order": 1
              $minimum
            }
        """.trimIndent()
    }

    private fun packageDocument(
        photoId: String = "cedar-grove",
        title: String = "Cedar",
        release: String = "1",
        packageId: String = "cedar",
    ) = """
        {
          "schemaVersion": 1,
          "packageId": "$packageId",
          "release": "$release",
          "editorial": ${editorial(photoId, title)},
          "assets": [{
            "photoId": "$photoId",
            "path": "assets/$photoId.img.webp",
            "sha256": "$pictureSha",
            "width": 8,
            "height": 8,
            "bytes": ${picture.size}
          }]
        }
    """.trimIndent()

    private fun document(
        photoId: String,
        title: String,
        packageId: String = "cedar",
        trailId: String = "cedar-walk",
        trailTitle: String = "Cedar Walk",
    ): RemotePackageDocument {
        val parsed = PackageDocumentParser.parse(
            packageDocument(photoId, title, packageId = packageId).replace(
                """"id": "cedar-walk"""",
                """"id": "$trailId"""",
            ).replace(""""title": "Cedar Walk"""", """"title": "$trailTitle""""),
        )
        return checkNotNull(parsed)
    }

    private fun railwayPackage(
        photoId: String,
        title: String,
        trailTitle: String,
        packageId: String = "taiwan",
    ) = """
        {
          "schemaVersion": 1,
          "packageId": "$packageId",
          "release": "1",
          "editorial": {
            "version": 1,
            "moods": [{"id": "atmospheric", "title": "Atmospheric", "summary": "Mist."}],
            "sceneries": [{"id": "railway", "title": "Railway", "summary": "Track.", "story": ""}],
            "photos": [{
              "id": "$photoId",
              "title": "$title",
              "sceneryId": "railway",
              "moodIds": ["atmospheric"],
              "revealId": "$photoId",
              "blurb": "Track in the mist.",
              "story": ""
            }],
            "trails": [{
              "id": "mianyue-line",
              "title": "$trailTitle",
              "summary": "A mountain railway.",
              "story": "",
              "photoIds": ["$photoId"]
            }]
          },
          "assets": []
        }
    """.trimIndent()

    private fun editorial(photoId: String, title: String) = """
        {
          "version": 1,
          "moods": [{"id": "quiet", "title": "Quiet", "summary": "Still."}],
          "sceneries": [{"id": "forest", "title": "Forest", "summary": "Trees."}],
          "photos": [{
            "id": "$photoId",
            "title": "$title",
            "sceneryId": "forest",
            "moodIds": ["quiet"],
            "blurb": "Moss."
          }],
          "trails": [{
            "id": "cedar-walk",
            "title": "Cedar Walk",
            "summary": "A short walk.",
            "photoIds": ["$photoId"]
          }]
        }
    """.trimIndent()

    private fun metadataUrl(relative: String) = "https://cdn.example/content/v1/$relative"

    private fun assetUrl() = "https://cdn.example/content/v1/assets/cedar-grove.img.webp"

    private fun emptyCatalog() = Catalog(1, emptyList(), emptyList(), emptyList(), emptyList())

    private fun emptyProgress() = PlayerProgress("player", emptySet())

    private fun sampleCatalog(): Catalog {
        val quiet = Mood("quiet", "Quiet", "Still.")
        val forest = Scenery("forest", "Forest", "Trees.")
        val photo = Photo("mossyrock", "Mossy Rock", "forest", listOf("quiet"), "mossyrock", "Shade.")
        val trail = Trail("obsidian", "Obsidian", "High country.", listOf("mossyrock"))
        return Catalog(1, listOf(quiet), listOf(forest), listOf(photo), listOf(trail))
    }

    private fun tempDir(): File = File.createTempFile("remote", "").apply {
        delete()
        mkdirs()
    }

    private fun sha(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private class ScriptTransport : RemoteTransport {
        var catalog: String = """{"schemaVersion":1,"revision":"r1","packages":[]}"""
        var fail: Boolean = false
        var ranged: ByteArray? = null
        val bodies = mutableMapOf<String, ByteArray>()
        val urls = mutableListOf<String>()
        val ranges = mutableMapOf<String, String>()
        private var etag = ""
        private var servedCatalog = ""

        override fun get(url: String, headers: Map<String, String>): TransportResponse {
            if (fail) throw java.io.IOException("offline")
            urls += url
            headers["Range"]?.let { ranges[url] = it }
            if (url.endsWith("/catalog.json")) {
                if (catalog == servedCatalog && headers["If-None-Match"] == etag) {
                    return TransportResponse(304, etag, ByteArray(0))
                }
                servedCatalog = catalog
                etag = "etag-" + catalog.hashCode()
                return TransportResponse(200, etag, catalog.encodeToByteArray())
            }
            val range = headers["Range"]
            if (range != null && ranged != null) {
                return TransportResponse(206, null, ranged!!)
            }
            val body = bodies[url] ?: throw java.io.IOException("missing $url")
            return TransportResponse(200, null, body)
        }
    }
}
