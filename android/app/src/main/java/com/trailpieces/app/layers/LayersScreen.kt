package com.trailpieces.app.layers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trailpieces.app.layers.v1.DevelopPrintScreen
import com.trailpieces.app.layers.v2.SnapPlaceScreen

/**
 * Entry for layers mode. Mechanics are versioned under [LayersMechanicVersion]
 * so we can try alternate loops without rewriting the shell.
 */
@Composable
fun LayersScreen(
    onBack: () -> Unit,
    mechanic: LayersMechanicVersion = LayersMechanics.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manifest = remember { LayersLoader.loadDefault(context) }
    var activeMechanic by remember { mutableStateOf(mechanic) }

    if (manifest == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No layered scene yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Run tools/prep_layers/prep_layers.py then rebuild.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text("Back")
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (activeMechanic) {
            LayersMechanicVersion.DEVELOP_PRINT_V1 -> {
                DevelopPrintScreen(
                    manifest = manifest,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LayersMechanicVersion.SNAP_PLACE_V2 -> {
                SnapPlaceScreen(
                    manifest = manifest,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayersMechanicVersion.entries.forEach { version ->
                FilterChip(
                    selected = activeMechanic == version,
                    onClick = { activeMechanic = version },
                    label = {
                        Text(
                            text = version.id,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}
