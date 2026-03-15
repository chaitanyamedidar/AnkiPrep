package com.medprep.prototype.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medprep.prototype.data.AnkiDataSource
import com.medprep.prototype.data.AnkiDeck
import com.medprep.prototype.data.DuplicatePair
import com.medprep.prototype.engine.DuplicateEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Represents every possible state of the app across all 3 screens.
 * Single sealed class avoids scattered state variables and makes impossible
 * states unrepresentable — the ViewModel can never be simultaneously
 * scanning and showing results.
 */
sealed class AppState {
    /** Screen 1: User picks two decks. Decks list may be loading or loaded. */
    data class DeckSelection(
        val decks: List<AnkiDeck> = emptyList(),
        val isLoadingDecks: Boolean = true
    ) : AppState()

    /** Screen 2: Pipeline running. progress is 0–100. */
    data class Scanning(val progress: Int) : AppState()

    /** Screen 3: Pipeline complete. May be empty list. */
    data class Results(val pairs: List<DuplicatePair>) : AppState()

    /** Unrecoverable error shown inline on the deck selection screen. */
    data class Error(val message: String) : AppState()
}

/**
 * Single ViewModel for all three screens. Uses AndroidViewModel to access
 * Application context for ContentProvider queries (no Activity reference held).
 */
class MedPrepViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MedPrepViewModel"
    }

    private val dataSource = AnkiDataSource(application)
    private val engine = DuplicateEngine()

    private val _state = MutableStateFlow<AppState>(AppState.DeckSelection(isLoadingDecks = true))
    val state: StateFlow<AppState> = _state

    init {
        loadDecks()
    }

    /**
     * Fetches all decks from AnkiDroid and populates the deck selector.
     * Runs off the main thread to avoid blocking the UI while the ContentProvider responds.
     */
    fun loadDecks() {
        viewModelScope.launch {
            _state.value = AppState.DeckSelection(isLoadingDecks = true)
            try {
                val decks = withContext(Dispatchers.IO) {
                    dataSource.fetchAllDecks()
                }
                _state.value = AppState.DeckSelection(decks = decks, isLoadingDecks = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load decks", e)
                _state.value = AppState.Error("Failed to load decks: ${e.message}")
            }
        }
    }

    /**
     * Fetches cards from both selected decks, then runs the 3-stage pipeline.
     *
     * Card fetching is interleaved with progress reporting so Screen 2 shows
     * activity during the IO phase. The engine itself reports progress from
     * 40–100 as it progresses through the pipeline stages.
     */
    fun startScan(deckA: AnkiDeck, deckB: AnkiDeck) {
        viewModelScope.launch {
            _state.value = AppState.Scanning(0)

            try {
                val deckACards = withContext(Dispatchers.IO) {
                    dataSource.fetchCardsForDeck(deckA.id) { loaded ->
                        // Update progress during card fetch (0–20% for Deck A)
                        val progress = (loaded.coerceAtMost(1000) / 1000f * 15).toInt()
                        _state.value = AppState.Scanning(progress.coerceIn(1, 20))
                    }
                }

                _state.value = AppState.Scanning(20)

                val deckBCards = withContext(Dispatchers.IO) {
                    dataSource.fetchCardsForDeck(deckB.id) { loaded ->
                        // Update progress during card fetch (20–40% for Deck B)
                        val progress = 20 + (loaded.coerceAtMost(1000) / 1000f * 15).toInt()
                        _state.value = AppState.Scanning(progress.coerceIn(20, 40))
                    }
                }

                _state.value = AppState.Scanning(40)

                val results = withContext(Dispatchers.Default) {
                    engine.run(
                        deckACards = deckACards,
                        deckBCards = deckBCards,
                        onProgress = { engineProgress ->
                            // Engine reports 0–100; we map it to 40–100 in overall progress
                            val mapped = 40 + (engineProgress * 0.60).toInt()
                            _state.value = AppState.Scanning(mapped.coerceIn(40, 100))
                        }
                    )
                }

                _state.value = AppState.Results(results)

            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _state.value = AppState.Error("Scan failed: ${e.message}")
            }
        }
    }

    /**
     * Opens AnkiDroid to the specific card identified by [pair.cardA.noteId].
     * Falls back to launching AnkiDroid's main browser if direct intent fails.
     *
     * MedPrep deliberately hands off to AnkiDroid for any editing action —
     * the companion app mental model means MedPrep only surfaces insights.
     */
    fun openInAnkiDroid(pair: DuplicatePair, context: Context) {
        // Try to open the specific note first
        val primaryIntent = dataSource.buildAnkiDroidCardIntent(pair.cardA.noteId)
        try {
            context.startActivity(primaryIntent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Direct note intent not handled, trying fallback", e)
        }

        // Fallback: launch AnkiDroid's main UI
        try {
            val fallback = dataSource.buildAnkiDroidFallbackIntent()
            context.startActivity(fallback)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "AnkiDroid not installed", e)
        }
    }

    /** Returns from Results or Error state back to DeckSelection. */
    fun resetToSelection() {
        loadDecks()
    }
}
