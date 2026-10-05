package com.trailpieces.app.journey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trailpieces.app.layers.v3.RevealScreen
import com.trailpieces.journey.Catalog
import com.trailpieces.journey.CatalogIndex
import com.trailpieces.journey.ContinueOffer
import com.trailpieces.journey.JourneyGraph
import com.trailpieces.journey.JourneyNav
import com.trailpieces.journey.JourneyPlace
import com.trailpieces.journey.Photo
import com.trailpieces.journey.PlayerProgress
import com.trailpieces.journey.continueAfter
import com.trailpieces.journey.withCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private val ReadingInk = Color(0xFFE9E5DA)

@Composable
fun JourneyScreen(
    graph: JourneyGraph,
    saveScope: CoroutineScope,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var progress by remember { mutableStateOf<PlayerProgress?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(graph) {
        runCatching {
            graph.catalog.load() to graph.progress.load()
        }.onSuccess { (loadedCatalog, loadedProgress) ->
            catalog = loadedCatalog
            progress = loadedProgress
        }.onFailure {
            failed = true
        }
    }

    val loadedCatalog = catalog
    val loadedProgress = progress
    when {
        failed -> JourneyMessage(
            title = "Trails are not available",
            body = "The catalog could not be read.",
            onBack = onExit,
            modifier = modifier,
        )
        loadedCatalog == null || loadedProgress == null -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> JourneyReady(
            graph = graph,
            saveScope = saveScope,
            catalog = loadedCatalog,
            initialProgress = loadedProgress,
            onExit = onExit,
            modifier = modifier,
        )
    }
}

private class WalkSession(initial: PlayerProgress) {
    val nav = JourneyNav()
    var progress by mutableStateOf(initial)
    var place by mutableStateOf<JourneyPlace>(nav.current)
    var offer by mutableStateOf<ContinueOffer?>(null)
    var offerPhotoId by mutableStateOf<String?>(null)
    var panelReady by mutableStateOf(false)

    fun sync() {
        place = nav.current
    }

    fun onPhotoCompleted(catalog: Catalog, graph: JourneyGraph, saveScope: CoroutineScope) {
        val playing = nav.current as? JourneyPlace.Playing ?: return
        val photoId = playing.photoId
        progress = progress.withCompletion(photoId)
        val next = continueAfter(catalog, progress, photoId, playing.trailId)
        nav.rememberTrail(next.trailId)
        sync()
        offer = next
        offerPhotoId = photoId
        panelReady = false
        // Starts immediately, so the photo is stored before Back can leave this screen.
        saveScope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.progress.recordCompletion(photoId)
        }
    }
}

@Composable
private fun JourneyReady(
    graph: JourneyGraph,
    saveScope: CoroutineScope,
    catalog: Catalog,
    initialProgress: PlayerProgress,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = remember(catalog) { CatalogIndex(catalog) }
    val session = remember { WalkSession(initialProgress) }

    BackHandler {
        if (session.nav.current is JourneyPlace.Playing) session.offer = null
        if (session.nav.canPop) {
            session.nav.back()
            session.sync()
        } else {
            onExit()
        }
    }

    when (val place = session.place) {
        JourneyPlace.Lens -> LensPage(
            onBack = onExit,
            onMood = {
                session.nav.open(JourneyPlace.MoodList)
                session.sync()
            },
            onScenery = {
                session.nav.open(JourneyPlace.SceneryList)
                session.sync()
            },
            onTrail = {
                session.nav.open(JourneyPlace.TrailList)
                session.sync()
            },
            modifier = modifier,
        )
        JourneyPlace.MoodList -> ChoicePage(
            title = "Mood",
            onBack = { pop(session) },
            modifier = modifier,
            empty = index.moodsWithPhotos().isEmpty(),
        ) {
            index.moodsWithPhotos().forEach { mood ->
                ChoiceCard(
                    title = mood.title,
                    detail = mood.summary,
                    onClick = {
                        session.nav.open(JourneyPlace.MoodPhotos(mood.id))
                        session.sync()
                    },
                )
            }
        }
        JourneyPlace.SceneryList -> ChoicePage(
            title = "Scenery",
            onBack = { pop(session) },
            modifier = modifier,
            empty = index.sceneriesWithPhotos().isEmpty(),
        ) {
            index.sceneriesWithPhotos().forEach { scenery ->
                ChoiceCard(
                    title = scenery.title,
                    detail = scenery.summary,
                    onClick = {
                        session.nav.open(JourneyPlace.SceneryPhotos(scenery.id))
                        session.sync()
                    },
                )
            }
        }
        JourneyPlace.TrailList -> ChoicePage(
            title = "Trails",
            onBack = { pop(session) },
            modifier = modifier,
            empty = catalog.trails.isEmpty(),
        ) {
            catalog.trails.forEach { trail ->
                val status = index.trailProgress(trail, session.progress)
                ChoiceCard(
                    title = trail.title,
                    detail = "${fraction(status.collected, status.total)} collected",
                    onClick = {
                        session.nav.open(JourneyPlace.TrailStops(trail.id))
                        session.sync()
                    },
                )
            }
        }
        is JourneyPlace.MoodPhotos -> {
            val mood = index.mood(place.moodId)
            PhotoListPage(
                title = mood?.title ?: "Mood",
                photos = index.photosForMood(place.moodId),
                index = index,
                progress = session.progress,
                trailId = null,
                onBack = { pop(session) },
                onPhoto = { photo -> openPhoto(session, catalog, photo.id, trailId = null) },
                modifier = modifier,
            )
        }
        is JourneyPlace.SceneryPhotos -> {
            val scenery = index.scenery(place.sceneryId)
            PhotoListPage(
                title = scenery?.title ?: "Scenery",
                photos = index.photosForScenery(place.sceneryId),
                index = index,
                progress = session.progress,
                trailId = null,
                onBack = { pop(session) },
                onPhoto = { photo -> openPhoto(session, catalog, photo.id, trailId = null) },
                modifier = modifier,
            )
        }
        is JourneyPlace.TrailStops -> {
            val trail = index.trail(place.trailId)
            if (trail == null) {
                JourneyMessage("Missing trail", "That trail is not in the catalog.", { pop(session) }, modifier)
            } else {
                val photos = trail.photoIds.mapNotNull { index.photo(it) }
                PhotoListPage(
                    title = trail.title,
                    photos = photos,
                    index = index,
                    progress = session.progress,
                    trailId = trail.id,
                    onBack = { pop(session) },
                    onPhoto = { photo -> openPhoto(session, catalog, photo.id, trail.id) },
                    modifier = modifier,
                )
            }
        }
        is JourneyPlace.Playing -> {
            val photo = index.photo(place.photoId)
            if (photo == null) {
                JourneyMessage("Missing photo", "That photo is not in the catalog.", { pop(session) }, modifier)
            } else {
                Box(modifier.fillMaxSize()) {
                    key(photo.id) {
                        RevealScreen(
                            onBack = {
                                session.offer = null
                                session.panelReady = false
                                pop(session)
                            },
                            initialSceneId = photo.revealId,
                            startCompleted = photo.id in session.progress.completedPhotoIds,
                            readingBand = true,
                            onCompleted = { session.onPhotoCompleted(catalog, graph, saveScope) },
                            onPresentationSettled = { session.panelReady = true },
                            onReplayStarted = { session.panelReady = false },
                            footer = {
                                val shown = session.offer?.takeIf {
                                    session.panelReady && session.offerPhotoId == photo.id
                                }
                                if (shown != null) {
                                    ReadingPanel(
                                        photo = photo,
                                        offer = shown,
                                        index = index,
                                        onContinue = { continueFrom(session, shown) },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

private fun pop(session: WalkSession) {
    session.nav.back()
    session.sync()
}

private fun openPhoto(session: WalkSession, catalog: Catalog, photoId: String, trailId: String?) {
    session.panelReady = false
    session.offer = null
    session.offerPhotoId = null
    val offer = if (photoId in session.progress.completedPhotoIds) {
        continueAfter(catalog, session.progress, photoId, trailId)
    } else {
        null
    }
    if (offer != null) {
        session.offer = offer
        session.offerPhotoId = photoId
    }
    session.nav.open(JourneyPlace.Playing(photoId, trailId ?: offer?.trailId))
    session.sync()
}

private fun continueFrom(session: WalkSession, offer: ContinueOffer) {
    session.offer = null
    session.offerPhotoId = null
    session.panelReady = false
    val nextPhotoId = offer.nextPhotoId
    if (nextPhotoId != null) {
        session.nav.continueTo(nextPhotoId, offer.trailId)
    } else {
        session.nav.finishToTrail(offer.trailId)
    }
    session.sync()
}

@Composable
private fun LensPage(
    onBack: () -> Unit,
    onMood: () -> Unit,
    onScenery: () -> Unit,
    onTrail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseColumn(title = "Walk a trail", onBack = onBack, modifier = modifier) {
        Text(
            text = "Choose a mood, a scenery, or a trail.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        ChoiceCard("Mood", "How you want the walk to feel.", onMood)
        ChoiceCard("Scenery", "Meadow, forest, and the other places.", onScenery)
        ChoiceCard("Trail", "A path of photos, collected in order.", onTrail)
    }
}

@Composable
private fun ChoicePage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    empty: Boolean,
    choices: @Composable ColumnScope.() -> Unit,
) {
    BrowseColumn(title = title, onBack = onBack, modifier = modifier) {
        if (empty) {
            Text(
                text = "Nothing is tagged for this yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            choices()
        }
    }
}

@Composable
private fun PhotoListPage(
    title: String,
    photos: List<Photo>,
    index: CatalogIndex,
    progress: PlayerProgress,
    trailId: String?,
    onBack: () -> Unit,
    onPhoto: (Photo) -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseColumn(title = title, onBack = onBack, modifier = modifier) {
        if (photos.isEmpty()) {
            Text(
                text = "No photos here yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        photos.forEach { photo ->
            PhotoCard(
                photo = photo,
                index = index,
                progress = progress,
                trailId = trailId,
                onClick = { onPhoto(photo) },
            )
        }
    }
}

@Composable
private fun BrowseColumn(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoCard(
    photo: Photo,
    index: CatalogIndex,
    progress: PlayerProgress,
    trailId: String?,
    onClick: () -> Unit,
) {
    val scenery = index.scenery(photo.sceneryId)?.title
    val trail = (trailId?.let { index.trail(it) } ?: index.trailsForPhoto(photo.id).firstOrNull())
    val status = trail?.let { index.trailProgress(it, progress) }
    val collected = photo.id in progress.completedPhotoIds
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(revealId = photo.revealId, title = photo.title)
            Column(Modifier.padding(start = 14.dp)) {
                Text(text = photo.title, style = MaterialTheme.typography.titleMedium)
                val line = listOfNotNull(
                    scenery,
                    trail?.title,
                    status?.let { fraction(it.collected, it.total) },
                ).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (collected) {
                    Text(
                        text = "Collected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(revealId: String, title: String) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data("file:///android_asset/reveal/$revealId/cover.webp")
            .crossfade(true)
            .build(),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ReadingPanel(
    photo: Photo,
    offer: ContinueOffer,
    index: CatalogIndex,
    onContinue: () -> Unit,
) {
    val trail = offer.trailId?.let { index.trail(it) }
    val scenery = index.scenery(photo.sceneryId)
    val next = offer.nextPhotoId?.let { index.photo(it) }
    val placeStory = photo.story.ifBlank { scenery?.story.orEmpty() }
    val trailStory = trail?.story.orEmpty()
    val label = when {
        next != null -> "Continue"
        trail != null -> "Back to ${trail.title}"
        else -> "Keep browsing"
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = trail?.title ?: "Collected",
            style = MaterialTheme.typography.titleMedium,
            color = ReadingInk,
        )
        Text(
            text = if (offer.total == 0) "Saved" else "${fraction(offer.collected, offer.total)} collected",
            style = MaterialTheme.typography.bodyLarge,
            color = ReadingInk,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (placeStory.isNotBlank()) {
            Text(
                text = placeStory,
                style = MaterialTheme.typography.bodyMedium,
                color = ReadingInk.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (trailStory.isNotBlank() && trailStory != placeStory) {
            Text(
                text = trailStory,
                style = MaterialTheme.typography.bodyMedium,
                color = ReadingInk.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (next != null) {
            Text(
                text = next.title,
                style = MaterialTheme.typography.labelLarge,
                color = ReadingInk.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun JourneyMessage(
    title: String,
    body: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
        )
        Button(onClick = onBack) { Text("Back") }
    }
}

private fun fraction(collected: Int, total: Int): String = "$collected of $total"
