package com.trailpieces.app.journey

import android.content.Context
import com.trailpieces.journey.FilePlayerRepository
import com.trailpieces.journey.FileProgressRepository
import com.trailpieces.journey.JourneyGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * One local graph for the process. The save scope is not tied to the walk screen,
 * so a completion still reaches disk if the player leaves during the finale.
 */
object LocalJourney {
    val saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var graph: JourneyGraph? = null

    fun graph(context: Context): JourneyGraph {
        graph?.let { return it }
        return synchronized(this) {
            graph ?: create(context.applicationContext).also { graph = it }
        }
    }

    private fun create(context: Context): JourneyGraph {
        val dir = File(context.filesDir, "journey")
        val players = FilePlayerRepository(File(dir, "player.json"))
        val progress = FileProgressRepository(File(dir, "progress.json"), players)
        val bundled = AssetCatalogRepository(context)
        val library = remoteLibrary(context)
        val remote = library?.let { RemoteCatalogRepository(bundled, progress, it) }
        return JourneyGraph(
            catalog = remote ?: bundled,
            progress = progress,
            players = players,
            remote = remote,
        )
    }
}
