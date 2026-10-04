package com.trailpieces.journey

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

fun PlayerProgress.withCompletion(photoId: String): PlayerProgress {
    if (photoId in completedPhotoIds) return this
    return copy(completedPhotoIds = completedPhotoIds + photoId)
}

object PlayerJson {
    const val VERSION = 1

    fun parse(json: String): Player {
        val root = JSONObject(json)
        val version = root.getInt("version")
        if (version != VERSION) {
            throw CatalogFormatException("Unsupported player version $version")
        }
        val kind = when (val raw = root.getString("kind")) {
            "local" -> PlayerKind.Local
            "account" -> PlayerKind.Account
            else -> throw CatalogFormatException("Unknown player kind $raw")
        }
        val name = if (root.isNull("displayName")) null else root.optString("displayName", "").ifBlank { null }
        val id = root.getString("id").trim()
        if (id.isEmpty()) throw CatalogFormatException("Player is missing an id")
        return Player(id = id, displayName = name, kind = kind)
    }

    fun write(player: Player): String {
        return JSONObject()
            .put("version", VERSION)
            .put("id", player.id)
            .put("displayName", player.displayName ?: JSONObject.NULL)
            .put("kind", if (player.kind == PlayerKind.Local) "local" else "account")
            .toString(2)
    }
}

object ProgressJson {
    const val VERSION = 1

    fun parse(json: String): PlayerProgress {
        val root = JSONObject(json)
        val version = root.getInt("version")
        if (version != VERSION) {
            throw CatalogFormatException("Unsupported progress version $version")
        }
        val playerId = root.getString("playerId").trim()
        if (playerId.isEmpty()) throw CatalogFormatException("Progress is missing a playerId")
        val ids = root.getJSONArray("completedPhotoIds")
        val completed = buildSet {
            for (index in 0 until ids.length()) {
                val id = ids.optString(index, "").trim()
                if (id.isEmpty()) throw CatalogFormatException("Progress lists an empty photo id")
                add(id)
            }
        }
        return PlayerProgress(playerId = playerId, completedPhotoIds = completed)
    }

    fun write(progress: PlayerProgress): String {
        val ids = JSONArray()
        progress.completedPhotoIds.sorted().forEach { ids.put(it) }
        return JSONObject()
            .put("version", VERSION)
            .put("playerId", progress.playerId)
            .put("completedPhotoIds", ids)
            .toString(2)
    }
}

class FilePlayerRepository(
    private val file: File,
) : PlayerRepository {
    private val gate = Mutex()

    override suspend fun current(): Player = gate.withLock {
        if (file.exists()) {
            PlayerJson.parse(file.readText())
        } else {
            val created = Player(
                id = UUID.randomUUID().toString(),
                displayName = null,
                kind = PlayerKind.Local,
            )
            file.parentFile?.mkdirs()
            file.writeText(PlayerJson.write(created))
            created
        }
    }
}

class FileProgressRepository(
    private val file: File,
    private val players: PlayerRepository,
) : ProgressRepository {
    private val lock = Any()
    private var memory: PlayerProgress? = null

    override suspend fun load(): PlayerProgress = synchronized(lock) { cachedOrRead() }

    override suspend fun recordCompletion(photoId: String): PlayerProgress = synchronized(lock) {
        val next = cachedOrRead().withCompletion(photoId)
        memory = next
        file.parentFile?.mkdirs()
        file.writeText(ProgressJson.write(next))
        next
    }

    private fun cachedOrRead(): PlayerProgress {
        memory?.let { return it }
        val loaded = if (file.exists()) {
            ProgressJson.parse(file.readText())
        } else {
            PlayerProgress(playerId = runBlocking { players.current().id }, emptySet())
        }
        memory = loaded
        return loaded
    }
}
