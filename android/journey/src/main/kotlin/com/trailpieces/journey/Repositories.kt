package com.trailpieces.journey

/**
 * Seams for a later login. Screens take these interfaces.
 * Local files implement them today; a remote client can replace any one of them.
 */
interface CatalogRepository {
    suspend fun load(): Catalog
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
)
