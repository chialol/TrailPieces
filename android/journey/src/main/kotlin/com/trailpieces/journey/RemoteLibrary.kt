package com.trailpieces.journey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TransportResponse(
    val code: Int,
    val etag: String?,
    val body: ByteArray,
)

interface RemoteTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): TransportResponse
}

data class RefreshResult(
    val catalogUpdated: Boolean,
    val message: String,
)

/**
 * Disk cache for one content root. Network failures leave the last good catalog
 * and any downloaded packages where they are. Progress is not stored here.
 */
class RemoteLibrary(
    private val root: File,
    private val transport: RemoteTransport,
    private val catalogUrl: String,
    private val appVersion: String,
) {
    private val lock = ReentrantLock()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation
    private var covers: Map<String, String> = emptyMap()
    private val downloading = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()

    fun merged(bundled: Catalog, progress: PlayerProgress): Catalog = lock.withLock {
        val playable = playableDocuments(progress)
        covers = coverIndex(playable)
        mergeCatalogs(bundled, playable)
    }

    fun coverFile(photoId: String): File? = lock.withLock {
        val relative = covers[photoId] ?: return null
        val file = root.resolve(relative)
        file.takeIf { it.isFile && file.startsWith(root) }
    }

    fun statuses(progress: PlayerProgress): List<PackageStatus> = lock.withLock {
        retainedSummaries(readDiscovery())
            .sortedWith(compareBy({ it.order }, { it.title }))
            .map { summary -> statusOf(summary, progress) }
    }

    fun refresh(progress: PlayerProgress): RefreshResult = mutate {
        val fetched = fetchDiscovery()
        syncDownloads(progress)
        RefreshResult(fetched.updated, fetched.message)
    }

    fun reevaluate(progress: PlayerProgress): RefreshResult = mutate {
        syncDownloads(progress)
        RefreshResult(catalogUpdated = false, message = "Checked unlocks against saved progress.")
    }

    fun retry(packageId: String, progress: PlayerProgress): RefreshResult = mutate {
        errors.remove(packageId)
        errorFile(packageId).delete()
        syncDownloads(progress, only = packageId)
        RefreshResult(catalogUpdated = false, message = "Tried $packageId again.")
    }

    private fun <T> mutate(block: () -> T): T {
        val result = lock.withLock(block)
        _generation.value += 1
        return result
    }

    private fun fetchDiscovery(): Fetch {
        val current = readDiscovery()
        val headers = mutableMapOf("Accept" to "application/json")
        if (current?.etag != null) headers["If-None-Match"] = current.etag
        val response = try {
            transport.get(catalogUrl, headers)
        } catch (error: Exception) {
            return Fetch(false, "Catalog refresh failed. Kept the last catalog.")
        }
        if (response.code == 304) {
            return Fetch(false, "Catalog is unchanged.")
        }
        if (response.code !in 200..299) {
            return Fetch(false, "Catalog refresh failed (${response.code}). Kept the last catalog.")
        }
        return when (val parsed = DiscoveryParser.parse(response.body.decodeToString())) {
            is DiscoveryParse.Rejected -> Fetch(false, "Kept the last catalog. ${parsed.reason}.")
            is DiscoveryParse.Ok -> {
                writeDiscovery(parsed.catalog, response.etag)
                parsed.catalog.packages.forEach { writeSummary(it) }
                Fetch(true, "Catalog revision ${parsed.catalog.revision}.")
            }
        }
    }

    private fun syncDownloads(progress: PlayerProgress, only: String? = null) {
        val summaries = retainedSummaries(readDiscovery()).filter { only == null || it.id == only }
        for (summary in summaries) {
            if (!canFetch(summary)) continue
            downloading += summary.id
            try {
                ensureMetadata(summary)
            } catch (error: Exception) {
                errors[summary.id] = error.message ?: "Download failed."
                writeAtomic(errorFile(summary.id), errors[summary.id]!!.toByteArray())
            } finally {
                downloading -= summary.id
            }
        }
        for (summary in summaries) {
            if (!isPlayable(summary, progress)) continue
            val document = metadataFile(summary.id).takeIf { it.isFile }?.let { PackageDocumentParser.parse(it.readText()) }
                ?: continue
            downloading += summary.id
            try {
                if (ensureFiles(document)) {
                    errors.remove(summary.id)
                    errorFile(summary.id).delete()
                }
            } catch (error: Exception) {
                errors[summary.id] = error.message ?: "Download failed."
                writeAtomic(errorFile(summary.id), errors[summary.id]!!.toByteArray())
            } finally {
                downloading -= summary.id
            }
        }
        covers = coverIndex(playableDocuments(progress))
    }

    private fun canFetch(summary: PackageSummary): Boolean {
        if (summary.access != AccessKind.Public) return false
        if (summary.unlock is UnlockRule.Unsupported) return false
        if (!appSupports(appVersion, summary.minAppVersion)) return false
        // Metadata for a locked package is still fetched so a packageCompleted rule can see its photos.
        // Image bytes wait until the package itself is unlocked.
        return true
    }

    private fun ensureMetadata(summary: PackageSummary): RemotePackageDocument? {
        val file = metadataFile(summary.id)
        val savedRelease = releaseFile(summary.id).takeIf { it.isFile }?.readText()?.trim()
        if (file.isFile && savedRelease == summary.release) {
            val parsed = PackageDocumentParser.parse(file.readText())
            if (parsed != null) return parsed
        }
        val url = RemotePaths.resolve(catalogUrl, summary.metadataPath)
        val response = transport.get(url)
        if (response.code !in 200..299) {
            throw IllegalStateException("Metadata download failed (${response.code}).")
        }
        val parsed = PackageDocumentParser.parse(response.body.decodeToString())
            ?: throw IllegalStateException("Package metadata is not usable. Kept the previous copy.")
        if (parsed.packageId != summary.id || parsed.release != summary.release) {
            throw IllegalStateException("Package metadata does not match the catalog.")
        }
        writeAtomic(file, response.body)
        writeAtomic(releaseFile(summary.id), summary.release.toByteArray())
        return parsed
    }

    private fun ensureFiles(document: RemotePackageDocument): Boolean {
        for (asset in document.assets) {
            val image = imageFile(asset)
            if (!image.isFile || sha256(image) != asset.sha256) {
                download(RemotePaths.resolve(catalogUrl, asset.path), image, asset.sha256)
            }
            val scene = asset.scene ?: continue
            val marker = sceneMarker(asset.photoId)
            if (marker.isFile && marker.readText() == scene.sha256 && scene.files.all { sceneFile(asset.photoId, it).isFile }) {
                continue
            }
            for (name in scene.files) {
                download(
                    RemotePaths.resolve(catalogUrl, "${scene.path}/$name"),
                    sceneFile(asset.photoId, name),
                    expectedSha = null,
                )
            }
            writeAtomic(marker, scene.sha256.toByteArray())
        }
        return filesReady(document)
    }

    private fun filesReady(document: RemotePackageDocument): Boolean {
        return document.assets.all { asset ->
            val image = imageFile(asset)
            val imageReady = image.isFile && sha256(image) == asset.sha256
            val scene = asset.scene
            val sceneReady = scene == null || sceneMarker(asset.photoId).let { marker ->
                marker.isFile && marker.readText() == scene.sha256
            }
            imageReady && sceneReady
        }
    }

    private fun download(url: String, dest: File, expectedSha: String?) {
        val partial = File(dest.parentFile, dest.name + ".partial")
        partial.parentFile?.mkdirs()
        val offset = if (partial.isFile) partial.length() else 0L
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
        val response = transport.get(url, headers)
        when (response.code) {
            206 -> partial.appendBytes(response.body)
            200 -> writeAtomic(partial, response.body)
            else -> throw IllegalStateException("Download failed (${response.code}).")
        }
        if (expectedSha != null && sha256(partial) != expectedSha) {
            partial.delete()
            throw IllegalStateException("Downloaded file did not match its content hash.")
        }
        moveIntoPlace(partial, dest)
    }

    private fun playableDocuments(progress: PlayerProgress): List<RemotePackageDocument> {
        return retainedSummaries(readDiscovery()).mapNotNull { summary ->
            if (!isPlayable(summary, progress)) return@mapNotNull null
            val file = metadataFile(summary.id)
            if (!file.isFile) return@mapNotNull null
            PackageDocumentParser.parse(file.readText())
        }
    }

    private fun isPlayable(summary: PackageSummary, progress: PlayerProgress): Boolean {
        if (summary.access != AccessKind.Public) return false
        if (!appSupports(appVersion, summary.minAppVersion)) return false
        val packages = cachedDocuments()
        return UnlockEvaluator.isUnlocked(summary.unlock, progress, packages)
    }

    private fun statusOf(summary: PackageSummary, progress: PlayerProgress): PackageStatus {
        val error = errors[summary.id] ?: errorFile(summary.id).takeIf { it.isFile }?.readText()
        if (summary.access != AccessKind.Public) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Discovered, "This access policy isn't supported, so the package stays closed.")
        }
        if (summary.unlock is UnlockRule.Unsupported) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Locked, "This unlock rule isn't supported.")
        }
        if (!appSupports(appVersion, summary.minAppVersion)) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Locked, "Needs app ${summary.minAppVersion}.")
        }
        if (!UnlockEvaluator.isUnlocked(summary.unlock, progress, cachedDocuments())) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Locked, UnlockEvaluator.describe(summary.unlock, progress))
        }
        val document = metadataFile(summary.id).takeIf { it.isFile }?.let { PackageDocumentParser.parse(it.readText()) }
        if (summary.id in downloading) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Downloading, "Downloading.")
        }
        if (document != null && filesReady(document)) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Downloaded, "On this device.")
        }
        if (!error.isNullOrBlank()) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Failed, error)
        }
        if (document == null) {
            return PackageStatus(summary.id, summary.title, PackagePhase.Available, "Ready to download.")
        }
        return PackageStatus(summary.id, summary.title, PackagePhase.Available, "The pictures are still downloading.")
    }

    private fun retainedSummaries(discovery: StoredDiscovery?): List<PackageSummary> {
        val listed = discovery?.catalog?.packages.orEmpty().associateBy { it.id }
        val saved = summaryDir().listFiles()?.mapNotNull { file ->
            if (file.extension != "json") return@mapNotNull null
            readSummary(file.nameWithoutExtension)
        }.orEmpty().associateBy { it.id }
        val ids = (listed.keys + saved.keys).toList()
        return ids.mapNotNull { id -> listed[id] ?: saved[id] }
    }

    private fun cachedDocuments(): Map<String, RemotePackageDocument> {
        val dir = File(root, "packages")
        if (!dir.isDirectory) return emptyMap()
        return dir.listFiles().orEmpty().mapNotNull { packageDir ->
            val file = File(packageDir, "metadata.json")
            if (!file.isFile) return@mapNotNull null
            PackageDocumentParser.parse(file.readText())
        }.associateBy { it.packageId }
    }

    private fun coverIndex(documents: List<RemotePackageDocument>): Map<String, String> {
        val index = linkedMapOf<String, String>()
        for (document in documents) {
            for (asset in document.assets) {
                val relative = "images/${asset.photoId}.${asset.sha256}.webp"
                if (imageFile(asset).isFile) index.putIfAbsent(asset.photoId, relative)
            }
        }
        return index
    }

    private fun readDiscovery(): StoredDiscovery? {
        val file = File(root, "discovery.json")
        if (!file.isFile) return null
        return try {
            val rootObject = JSONObject(file.readText())
            val parsed = DiscoveryParser.parse(rootObject.getJSONObject("document").toString())
            val catalog = (parsed as? DiscoveryParse.Ok)?.catalog ?: return null
            StoredDiscovery(catalog, rootObject.optString("etag", "").ifBlank { null })
        } catch (error: Exception) {
            null
        }
    }

    private fun writeDiscovery(catalog: DiscoveryCatalog, etag: String?) {
        val document = JSONObject()
            .put("schemaVersion", catalog.schemaVersion)
            .put("revision", catalog.revision)
            .put("packages", org.json.JSONArray().also { array ->
                catalog.packages.forEach { array.put(summaryJson(it)) }
            })
        val wrapper = JSONObject().put("etag", etag ?: JSONObject.NULL).put("document", document)
        writeAtomic(File(root, "discovery.json"), wrapper.toString().toByteArray())
    }

    private fun writeSummary(summary: PackageSummary) {
        writeAtomic(summaryFile(summary.id), summaryJson(summary).toString().toByteArray())
    }

    private fun readSummary(id: String): PackageSummary? {
        val file = summaryFile(id)
        if (!file.isFile) return null
        val parsed = DiscoveryParser.parse(
            JSONObject().put("schemaVersion", 1).put("revision", "saved").put(
                "packages",
                org.json.JSONArray().put(JSONObject(file.readText())),
            ).toString(),
        )
        return (parsed as? DiscoveryParse.Ok)?.catalog?.packages?.singleOrNull()
    }

    private fun summaryJson(summary: PackageSummary): JSONObject {
        val unlock = when (val rule = summary.unlock) {
            UnlockRule.Immediate -> JSONObject().put("type", "immediate")
            is UnlockRule.PuzzlesCompleted -> JSONObject().put("type", "puzzlesCompleted").put("count", rule.count)
            is UnlockRule.PackageCompleted -> JSONObject().put("type", "packageCompleted").put("packageId", rule.packageId)
            is UnlockRule.Unsupported -> JSONObject().put("type", rule.type)
        }
        val json = JSONObject()
            .put("id", summary.id)
            .put("title", summary.title)
            .put("description", summary.description)
            .put("release", summary.release)
            .put("metadata", summary.metadataPath)
            .put("access", if (summary.access == AccessKind.Public) "public" else "unsupported")
            .put("unlock", unlock)
            .put("order", summary.order)
        if (summary.coverPath != null) json.put("cover", summary.coverPath)
        if (summary.photoCount != null) json.put("photoCount", summary.photoCount)
        if (summary.downloadBytes != null) json.put("downloadBytes", summary.downloadBytes)
        if (summary.minAppVersion != null) json.put("minAppVersion", summary.minAppVersion)
        return json
    }

    private fun imageFile(asset: RemoteAsset): File {
        return File(root, "images/${asset.photoId}.${asset.sha256}.webp")
    }

    private fun sceneFile(photoId: String, name: String): File {
        if (!RemotePaths.isSafeRelative(name)) throw IllegalStateException("Unsafe scene path")
        val file = File(root, "scenes/$photoId/$name")
        if (!file.startsWith(File(root, "scenes/$photoId"))) throw IllegalStateException("Unsafe scene path")
        return file
    }

    private fun sceneMarker(photoId: String) = File(root, "scenes/$photoId/.tree")

    private fun metadataFile(id: String) = File(root, "packages/$id/metadata.json")

    private fun releaseFile(id: String) = File(root, "packages/$id/release.txt")

    private fun summaryFile(id: String) = File(summaryDir(), "$id.json")

    private fun summaryDir() = File(root, "summaries")

    private fun errorFile(id: String) = File(root, "errors/$id.txt")

    private data class StoredDiscovery(val catalog: DiscoveryCatalog, val etag: String?)

    private data class Fetch(val updated: Boolean, val message: String)
}

private fun File.startsWith(dir: File): Boolean {
    return canonicalFile.path.startsWith(dir.canonicalFile.path)
}

private fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.writeBytes(bytes)
    if (file.exists()) file.delete()
    if (!temporary.renameTo(file)) {
        file.writeBytes(bytes)
        temporary.delete()
    }
}

private fun moveIntoPlace(source: File, dest: File) {
    dest.parentFile?.mkdirs()
    if (dest.exists()) dest.delete()
    if (!source.renameTo(dest)) {
        dest.writeBytes(source.readBytes())
        source.delete()
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
