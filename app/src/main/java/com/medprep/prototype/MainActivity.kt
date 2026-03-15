package com.medprep.prototype

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.medprep.prototype.ui.AppState
import com.medprep.prototype.ui.DeckSelectorScreen
import com.medprep.prototype.ui.MedPrepViewModel
import com.medprep.prototype.ui.ResultsScreen
import com.medprep.prototype.ui.ScanningScreen

/**
 * Single-activity host for the entire MedPrep app.
 *
 * Architecture note: Compose replaces Android Navigation here. Three screens
 * are rendered by observing [AppState] from the ViewModel — no NavHost,
 * no FragmentManager, no back stack complexity. Navigation is driven entirely
 * by state transitions in [MedPrepViewModel].
 *
 * Permission handling: AnkiDroid's READ_WRITE_DATABASE is a custom permission
 * declared by AnkiDroid's AndroidManifest. Android treats it as a normal
 * permission (not dangerous), so it's granted at install time — no runtime
 * request dialog is shown on modern Android. We still check it at startup
 * to surface a clear error if AnkiDroid is not installed or was uninstalled
 * after MedPrep was granted access.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MedPrepViewModel by viewModels()

    // Permission launcher for READ_WRITE_DATABASE.
    // Even though this is a normal permission, the launcher provides a
    // clean way to re-check and refresh the UI after a permission state change.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.loadDecks()
    }

    private val ankiPermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current

            val hasPermission = checkSelfPermission(ankiPermission) == PackageManager.PERMISSION_GRANTED

            when (val s = state) {
                is AppState.DeckSelection -> {
                    DeckSelectorScreen(
                        decks = s.decks,
                        isLoadingDecks = s.isLoadingDecks,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(ankiPermission)
                        },
                        onStartScan = { deckA, deckB ->
                            viewModel.startScan(deckA, deckB)
                        }
                    )
                }

                is AppState.Scanning -> {
                    ScanningScreen(progress = s.progress)
                }

                is AppState.Results -> {
                    ResultsScreen(
                        pairs = s.pairs,
                        onOpenInAnkiDroid = { pair ->
                            viewModel.openInAnkiDroid(pair, context)
                        },
                        onBack = {
                            viewModel.resetToSelection()
                        }
                    )
                }

                is AppState.Error -> {
                    // Error is shown inline on the deck selection screen so the
                    // user can retry without losing their app context.
                    DeckSelectorScreen(
                        decks = emptyList(),
                        isLoadingDecks = false,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(ankiPermission)
                        },
                        onStartScan = { deckA, deckB ->
                            viewModel.startScan(deckA, deckB)
                        }
                    )
                }
            }
        }
    }
}
