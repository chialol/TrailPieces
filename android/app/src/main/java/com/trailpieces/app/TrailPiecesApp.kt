package com.trailpieces.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.trailpieces.app.journey.JourneyScreen
import com.trailpieces.app.journey.LocalJourney
import com.trailpieces.app.tutorial.TutorialScreen
import com.trailpieces.app.tutorial.TutorialStore
import com.trailpieces.app.ui.HomeMenuScreen

private enum class AppDestination {
    Tutorial,
    Menu,
    Journey,
}

private val DestinationSaver = Saver<AppDestination, String>(
    save = { it.name },
    restore = { saved -> AppDestination.entries.firstOrNull { it.name == saved } ?: AppDestination.Menu },
)

@Composable
fun TrailPiecesApp() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val graph = remember { LocalJourney.graph(context) }
    val tutorialStore = remember { TutorialStore(context) }
    LaunchedEffect(graph, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.remote?.refresh()
        }
    }
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf(AppDestination.Menu)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (destination) {
            AppDestination.Tutorial -> {
                TutorialScreen(
                    canExit = true,
                    onExit = { destination = AppDestination.Menu },
                    onFinished = {
                        tutorialStore.markComplete()
                        destination = AppDestination.Menu
                    },
                    modifier = contentModifier,
                )
            }
            AppDestination.Menu -> {
                HomeMenuScreen(
                    tutorialComplete = tutorialStore.isComplete(),
                    onPlay = { destination = AppDestination.Tutorial },
                    onWalkTrail = { destination = AppDestination.Journey },
                    onReplayTutorial = { destination = AppDestination.Tutorial },
                    modifier = contentModifier,
                )
            }
            AppDestination.Journey -> {
                JourneyScreen(
                    graph = graph,
                    saveScope = LocalJourney.saveScope,
                    onExit = { destination = AppDestination.Menu },
                    modifier = contentModifier,
                )
            }
        }
    }
}
