package com.trailpieces.app.journey

import android.content.Context
import com.trailpieces.journey.Catalog
import com.trailpieces.journey.CatalogRepository
import com.trailpieces.journey.PackageStatus
import com.trailpieces.journey.ProgressRepository
import com.trailpieces.journey.RemoteContent
import com.trailpieces.journey.RemoteLibrary
import com.trailpieces.journey.RemoteTransport
import com.trailpieces.journey.TransportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Bundled catalog first, then whatever packages are already on disk.
 * [refresh] is the only network call, and nothing waits on it to draw the walk.
 */
class RemoteCatalogRepository(
    private val bundled: CatalogRepository,
    private val progress: ProgressRepository,
    private val library: RemoteLibrary,
) : CatalogRepository, RemoteContent {
    override val generation: StateFlow<Int> = library.generation

    override suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        library.merged(bundled.load(), progress.load())
    }

    override fun updates(): Flow<Catalog> = kotlinx.coroutines.flow.flow {
        library.generation.collect {
            emit(load())
        }
    }

    override suspend fun refresh() {
        val snapshot = progress.load()
        withContext(Dispatchers.IO) { runCatching { library.refresh(snapshot) } }
    }

    override suspend fun reevaluate() {
        val snapshot = progress.load()
        withContext(Dispatchers.IO) { runCatching { library.reevaluate(snapshot) } }
    }

    override suspend fun retry(packageId: String) {
        val snapshot = progress.load()
        withContext(Dispatchers.IO) { runCatching { library.retry(packageId, snapshot) } }
    }

    override suspend fun statuses(): List<PackageStatus> = withContext(Dispatchers.IO) {
        library.statuses(progress.load())
    }

    override fun coverFile(photoId: String): File? = library.coverFile(photoId)
}

fun remoteLibrary(context: Context): RemoteLibrary? {
    val url = readCatalogUrl(context) ?: return null
    return RemoteLibrary(
        root = File(context.filesDir, "remote"),
        transport = HttpsTransport(),
        catalogUrl = url,
        appVersion = com.trailpieces.app.BuildConfig.VERSION_NAME,
    )
}

private fun readCatalogUrl(context: Context): String? {
    val url = runCatching {
        context.assets.open("remote/config.json").bufferedReader().use { reader ->
            JSONObject(reader.readText()).optString("catalogUrl", "")
        }
    }.getOrDefault("").trim()
    if (!url.startsWith("https://")) return null
    return url
}

class HttpsTransport : RemoteTransport {
    override fun get(url: String, headers: Map<String, String>): TransportResponse {
        val parsed = URI(url)
        if (parsed.scheme != "https") {
            throw IllegalArgumentException("Remote content requires HTTPS")
        }
        val connection = parsed.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return try {
            val code = connection.responseCode
            if (code in 300..399) {
                throw IllegalStateException("Redirects are not followed")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            TransportResponse(code, connection.getHeaderField("ETag"), body)
        } finally {
            connection.disconnect()
        }
    }
}
