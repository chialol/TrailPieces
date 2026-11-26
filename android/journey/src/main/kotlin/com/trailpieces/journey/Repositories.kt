package com.trailpieces.journey

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Seams for a later login. Screens take these interfaces.
 * The bundled catalog still loads with no network. A remote library can sit beside it.
 */
interface CatalogRepository {
    suspend fun load(): Catalog

    /** Later merged catalogs. Empty until a remote library publishes one. */
    fun updates(): Flow<Catalog> = emptyFlow()
}

/**
 * Downloaded packages. Null when remote content is not configured.
 * Unlock checks are gameplay. They do not protect a public CDN file.
 */
interface RemoteContent {
    val generation: StateFlow<Int>
    suspend fun refresh()
    suspend fun reevaluate()
    suspend fun retry(packageId: String)
    suspend fun statuses(): List<PackageStatus>
    fun coverFile(photoId: String): java.io.File?
}

interface ProgressRepository {
    suspend fun load(): PlayerProgress
    suspend fun recordCompletion(photoId: String): PlayerProgress
}

interface PlayerRepository {
    suspend fun current(): Player
}

class JourneyGraph(
    val catalog: CatalogRepository,
    val progress: ProgressRepository,
    val players: PlayerRepository,
    val remote: RemoteContent? = null,
)
