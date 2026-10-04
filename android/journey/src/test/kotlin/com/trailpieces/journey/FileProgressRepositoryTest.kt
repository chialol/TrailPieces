package com.trailpieces.journey

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileProgressRepositoryTest {

    @Test
    fun recordsAPhotoOnceAndKeepsTheSamePlayer() = runBlocking {
        val dir = Files.createTempDirectory("journey").toFile()
        val players = FilePlayerRepository(dir.resolve("player.json"))
        val progress = FileProgressRepository(dir.resolve("progress.json"), players)

        val first = progress.recordCompletion("mossyrock")
        val second = progress.recordCompletion("mossyrock")
        val loaded = progress.load()

        assertEquals(setOf("mossyrock"), first.completedPhotoIds)
        assertEquals(first, second)
        assertEquals(first.playerId, loaded.playerId)
        assertEquals(players.current().id, loaded.playerId)
    }
}
