package com.trailpieces.journey

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Discovery document at `content/v1/catalog.json`.
 * Package bytes are a separate download. This file only names them.
 *
 * [access] is authorization. [unlock] is a gameplay rule evaluated on the device.
 * A client-side unlock does not protect a public CDN object.
 */
data class DiscoveryCatalog(
    val schemaVersion: Int,
    val revision: String,
    val packages: List<PackageSummary>,
)

data class PackageSummary(
    val id: String,
    val title: String,
    val description: String,
    val release: String,
    val coverPath: String?,
    val metadataPath: String,
    val photoCount: Int?,
    val downloadBytes: Long?,
    val access: AccessKind,
    val unlock: UnlockRule,
    val order: Int,
    val minAppVersion: String?,
)

enum class AccessKind { Public, Unsupported }

sealed class UnlockRule {
    data object Immediate : UnlockRule()
    data class PuzzlesCompleted(val count: Int) : UnlockRule()
    data class PackageCompleted(val packageId: String) : UnlockRule()
    data class Unsupported(val type: String) : UnlockRule()
}

data class RemoteAsset(
    val photoId: String,
    val path: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val scene: RemoteScene?,
)

data class RemoteScene(
    val path: String,
    val sha256: String,
    val files: List<String>,
)

data class RemotePackageDocument(
    val packageId: String,
    val release: String,
    val editorial: Catalog,
    val assets: List<RemoteAsset>,
)

enum class PackagePhase {
    Discovered,
    Locked,
    Available,
    Downloading,
    Downloaded,
    Failed,
}

data class PackageStatus(
    val id: String,
    val title: String,
    val phase: PackagePhase,
    val detail: String,
)

sealed class DiscoveryParse {
    data class Ok(val catalog: DiscoveryCatalog) : DiscoveryParse()
    data class Rejected(val reason: String) : DiscoveryParse()
}

object RemotePaths {
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val releasePattern = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun isId(value: String): Boolean = idPattern.matches(value)

    fun isRelease(value: String): Boolean = releasePattern.matches(value)

    fun isSafeRelative(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/")) return false
        if ('\\' in path || "://" in path || "//" in path) return false
        return path.split("/").none { it.isEmpty() || it == "." || it == ".." }
    }

    fun contentRoot(catalogUrl: String): String {
        val slash = catalogUrl.lastIndexOf('/')
        if (slash <= "https://".length) {
            throw CatalogFormatException("Remote catalog URL has no content root")
        }
        return catalogUrl.substring(0, slash + 1)
    }

    fun resolve(catalogUrl: String, relative: String): String {
        if (!catalogUrl.startsWith("https://")) {
            throw CatalogFormatException("Remote content requires HTTPS")
        }
        if (!isSafeRelative(relative)) {
            throw CatalogFormatException("Unsafe content path")
        }
        return contentRoot(catalogUrl) + relative
    }
}

object DiscoveryParser {
    const val SCHEMA_VERSION = 1

    fun parse(json: String): DiscoveryParse {
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            return DiscoveryParse.Rejected(error.message ?: "Catalog is not JSON")
        }
        val version = root.optInt("schemaVersion", -1)
        if (version != SCHEMA_VERSION) {
            return DiscoveryParse.Rejected("Unsupported catalog schema $version")
        }
        val revision = root.optString("revision", "").trim()
        if (revision.isEmpty()) {
            return DiscoveryParse.Rejected("Catalog is missing a revision")
        }
        val array = root.optJSONArray("packages")
            ?: return DiscoveryParse.Rejected("Catalog is missing packages")
        val packages = mutableListOf<PackageSummary>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: return DiscoveryParse.Rejected("packages[$index] is not an object")
            packages += parsePackage(item)
                ?: return DiscoveryParse.Rejected("packages[$index] is not usable")
        }
        return DiscoveryParse.Ok(DiscoveryCatalog(version, revision, packages))
    }

    private fun parsePackage(item: JSONObject): PackageSummary? {
        val id = item.optString("id", "").trim()
        val title = item.optString("title", "").trim()
        val release = item.optString("release", "").trim()
        val metadata = item.optString("metadata", "").trim()
        if (!RemotePaths.isId(id) || title.isEmpty() || !RemotePaths.isRelease(release)) return null
        if (!RemotePaths.isSafeRelative(metadata)) return null
        val cover = item.optString("cover", "").trim().ifEmpty { null }
        if (cover != null && !RemotePaths.isSafeRelative(cover)) return null
        val access = when (item.optString("access", "")) {
            "public" -> AccessKind.Public
            "" -> return null
            else -> AccessKind.Unsupported
        }
        val unlock = parseUnlock(item.optJSONObject("unlock")) ?: UnlockRule.Unsupported("missing")
        val minimum = item.optString("minAppVersion", "").trim().ifEmpty { null }
        return PackageSummary(
            id = id,
            title = title,
            description = item.optString("description", ""),
            release = release,
            coverPath = cover,
            metadataPath = metadata,
            photoCount = if (item.has("photoCount")) item.optInt("photoCount") else null,
            downloadBytes = if (item.has("downloadBytes")) item.optLong("downloadBytes") else null,
            access = access,
            unlock = unlock,
            order = item.optInt("order", 0),
            minAppVersion = minimum,
        )
    }

    private fun parseUnlock(item: JSONObject?): UnlockRule? {
        if (item == null) return null
        return when (val type = item.optString("type", "")) {
            "immediate" -> UnlockRule.Immediate
            "puzzlesCompleted" -> {
                val count = item.optInt("count", -1)
                if (count >= 1) UnlockRule.PuzzlesCompleted(count) else UnlockRule.Unsupported(type)
            }
            "packageCompleted" -> {
                val packageId = item.optString("packageId", "").trim()
                if (RemotePaths.isId(packageId)) UnlockRule.PackageCompleted(packageId) else UnlockRule.Unsupported(type)
            }
            else -> UnlockRule.Unsupported(type.ifEmpty { "missing" })
        }
    }
}

object PackageDocumentParser {
    fun parse(json: String): RemotePackageDocument? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("schemaVersion", -1) != DiscoveryParser.SCHEMA_VERSION) return null
            val packageId = root.optString("packageId", "").trim()
            val release = root.optString("release", "").trim()
            if (!RemotePaths.isId(packageId) || !RemotePaths.isRelease(release)) return null
            val editorial = CatalogParser.parse(root.getJSONObject("editorial").toString())
            val assets = parseAssets(root.optJSONArray("assets") ?: JSONArray(), editorial)
                ?: return null
            RemotePackageDocument(packageId, release, editorial, assets)
        } catch (error: CatalogFormatException) {
            null
        } catch (error: JSONException) {
            null
        }
    }

    private fun parseAssets(array: JSONArray, editorial: Catalog): List<RemoteAsset>? {
        val photoIds = editorial.photos.map { it.id }.toSet()
        val assets = mutableListOf<RemoteAsset>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            val photoId = item.optString("photoId", "").trim()
            val path = item.optString("path", "").trim()
            val sha = item.optString("sha256", "").trim()
            if (photoId !in photoIds || photoId in seen) return null
            if (!RemotePaths.isSafeRelative(path) || !sha.matches(Regex("^[a-f0-9]{64}$"))) return null
            seen += photoId
            val scene = item.optJSONObject("scene")?.let { parseScene(it) } ?: if (item.has("scene")) return null else null
            assets += RemoteAsset(
                photoId = photoId,
                path = path,
                sha256 = sha,
                width = item.optInt("width"),
                height = item.optInt("height"),
                bytes = item.optLong("bytes"),
                scene = scene,
            )
        }
        return assets
    }

    private fun parseScene(item: JSONObject): RemoteScene? {
        val path = item.optString("path", "").trim()
        val sha = item.optString("sha256", "").trim()
        if (!RemotePaths.isSafeRelative(path) || !sha.matches(Regex("^[a-f0-9]{64}$"))) return null
        val files = item.optJSONArray("files") ?: return null
        val names = mutableListOf<String>()
        for (index in 0 until files.length()) {
            val name = files.optString(index, "").trim()
            if (!RemotePaths.isSafeRelative(name)) return null
            names += name
        }
        if (names.isEmpty()) return null
        return RemoteScene(path, sha, names)
    }
}

/**
 * Gameplay unlocks. These are not authorization.
 *
 * [UnlockRule.PuzzlesCompleted] counts distinct ids in [PlayerProgress.completedPhotoIds].
 * Bundled photos, remote photos, and photos whose package has since left the catalog all count.
 * Replaying a photo does not increase the count.
 *
 * [UnlockRule.PackageCompleted] is met when every photo id in that package's cached metadata
 * is in the progress set. A photo completed from another package or the bundled catalog counts
 * if it is the same id. A package whose metadata has never been stored is not complete.
 * A cycle does not unlock.
 */
object UnlockEvaluator {
    fun isUnlocked(
        rule: UnlockRule,
        progress: PlayerProgress,
        packages: Map<String, RemotePackageDocument>,
        stack: Set<String> = emptySet(),
    ): Boolean {
        return when (rule) {
            UnlockRule.Immediate -> true
            is UnlockRule.Unsupported -> false
            is UnlockRule.PuzzlesCompleted -> progress.completedPhotoIds.size >= rule.count
            is UnlockRule.PackageCompleted -> {
                if (rule.packageId in stack) return false
                val document = packages[rule.packageId] ?: return false
                val photos = document.editorial.photos.map { it.id }
                photos.isNotEmpty() && photos.all { it in progress.completedPhotoIds }
            }
        }
    }

    fun describe(rule: UnlockRule, progress: PlayerProgress): String {
        return when (rule) {
            UnlockRule.Immediate -> "Available"
            is UnlockRule.Unsupported -> "This unlock rule isn't supported."
            is UnlockRule.PuzzlesCompleted -> {
                val have = progress.completedPhotoIds.size
                "Finish $have of ${rule.count} puzzles."
            }
            is UnlockRule.PackageCompleted -> "Finish every photo in ${rule.packageId}."
        }
    }
}

fun appSupports(appVersion: String, required: String?): Boolean {
    if (required.isNullOrBlank()) return true
    val app = versionParts(appVersion) ?: return false
    val need = versionParts(required) ?: return false
    val width = maxOf(app.size, need.size)
    for (index in 0 until width) {
        val left = app.getOrElse(index) { 0 }
        val right = need.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return true
}

private fun versionParts(value: String): List<Int>? {
    if (!value.matches(Regex("""\d+(\.\d+){0,3}"""))) return null
    return value.split(".").map { it.toInt() }
}

/**
 * Bundled photos and trails keep their wording. A remote photo with a new id is appended.
 * The same photo id, bundled or remote, stays one photo and one completion.
 * A remote trail with a bundled id keeps the bundled wording and gains any new stops at the end.
 * A trail that exists only in remote packages takes its wording from the latest loaded package.
 * Each package should list only its own photos. Stops whose photos are not loaded are dropped,
 * so one package cannot point at a photograph that has not been downloaded.
 */
fun mergeCatalogs(bundled: Catalog, documents: List<RemotePackageDocument>): Catalog {
    val moods = bundled.moods.toMutableList()
    val moodIds = moods.map { it.id }.toMutableSet()
    val sceneries = bundled.sceneries.toMutableList()
    val sceneryIds = sceneries.map { it.id }.toMutableSet()
    val photos = bundled.photos.toMutableList()
    val photoIds = photos.map { it.id }.toMutableSet()
    val bundledTrailIds = bundled.trails.map { it.id }.toSet()
    val trails = bundled.trails.map { it.copy(photoIds = it.photoIds.toList()) }.toMutableList()

    for (document in documents) {
        for (mood in document.editorial.moods) {
            if (mood.id !in moodIds) {
                moods += mood
                moodIds += mood.id
            }
        }
        for (scenery in document.editorial.sceneries) {
            if (scenery.id !in sceneryIds) {
                sceneries += scenery
                sceneryIds += scenery.id
            }
        }
        for (photo in document.editorial.photos) {
            if (photo.id in photoIds) continue
            if (photo.sceneryId !in sceneryIds) continue
            if (photo.moodIds.any { it !in moodIds }) continue
            photos += photo
            photoIds += photo.id
        }
        for (trail in document.editorial.trails) {
            val stops = trail.photoIds.filter { it in photoIds }
            val existing = trails.indexOfFirst { it.id == trail.id }
            if (existing < 0) {
                if (stops.isNotEmpty()) trails += trail.copy(photoIds = stops)
            } else {
                val current = trails[existing]
                val base = if (trail.id in bundledTrailIds) {
                    current
                } else {
                    current.copy(title = trail.title, summary = trail.summary, story = trail.story)
                }
                val extra = stops.filter { it !in base.photoIds }
                trails[existing] = base.copy(photoIds = base.photoIds + extra)
            }
        }
    }
    return bundled.copy(moods = moods, sceneries = sceneries, photos = photos, trails = trails)
}
