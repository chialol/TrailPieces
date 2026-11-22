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
    val tutorialStore = remember { TutorialStore(context) }
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf(
            if (tutorialStore.isComplete()) AppDestination.Menu else AppDestination.Tutorial,
        )
    }
    // A restored menu from an older install still owes the first-run lesson.
    LaunchedEffect(Unit) {
        if (!tutorialStore.isComplete()) destination = AppDestination.Tutorial
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (destination) {
            AppDestination.Tutorial -> {
                TutorialScreen(
                    canExit = tutorialStore.isComplete(),
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
                    onWalkTrail = { destination = AppDestination.Journey },
                    onReplayTutorial = { destination = AppDestination.Tutorial },
                    modifier = contentModifier,
                )
            }
            AppDestination.Journey -> {
                JourneyScreen(
                    graph = LocalJourney.graph(context),
                    saveScope = LocalJourney.saveScope,
                    onExit = { destination = AppDestination.Menu },
                    modifier = contentModifier,
                )
            }
        }
    }
}
