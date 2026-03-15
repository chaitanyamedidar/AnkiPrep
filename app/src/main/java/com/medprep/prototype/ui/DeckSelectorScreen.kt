package com.medprep.prototype.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medprep.prototype.data.AnkiDeck

/**
 * Screen 1 — Deck Selector.
 *
 * Shows two dropdowns populated from AnkiDroid's ContentProvider.
 * Blocks the "Find Duplicates" button until two distinct decks are selected.
 * If the AnkiDroid permission has not been granted, shows a permission prompt
 * instead of the deck list — this is the only gate in the entire app.
 */
@Composable
fun DeckSelectorScreen(
    decks: List<AnkiDeck>,
    isLoadingDecks: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartScan: (deckA: AnkiDeck, deckB: AnkiDeck) -> Unit
) {
    var selectedDeckA by remember { mutableStateOf<AnkiDeck?>(null) }
    var selectedDeckB by remember { mutableStateOf<AnkiDeck?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Select Two Decks to Compare",
            style = MaterialTheme.typography.headlineSmall
        )

        if (!hasPermission) {
            // Permission gate: without READ_WRITE_DATABASE, the ContentProvider
            // returns null cursors and no cards can be read.
            Text("AnkiDroid permission required")
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
            return@Column
        }

        if (isLoadingDecks) {
            CircularProgressIndicator()
            return@Column
        }

        if (decks.isEmpty()) {
            Text("No decks found. Make sure AnkiDroid is installed and has at least two decks.")
            Button(onClick = onRequestPermission) {
                Text("Retry")
            }
            return@Column
        }

        // Deck A dropdown
        DeckDropdown(
            label = "Deck A",
            decks = decks,
            selected = selectedDeckA,
            excludeDeck = selectedDeckB,  // Prevent selecting the same deck twice
            onSelected = { selectedDeckA = it }
        )

        // Deck B dropdown
        DeckDropdown(
            label = "Deck B",
            decks = decks,
            selected = selectedDeckB,
            excludeDeck = selectedDeckA,  // Prevent selecting the same deck twice
            onSelected = { selectedDeckB = it }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val a = selectedDeckA
                val b = selectedDeckB
                if (a != null && b != null) onStartScan(a, b)
            },
            enabled = selectedDeckA != null && selectedDeckB != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Find Duplicates")
        }
    }
}

/**
 * A single deck dropdown (exposed dropdown menu using Material3).
 * The [excludeDeck] parameter ensures the same deck cannot be selected
 * in both Deck A and Deck B positions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckDropdown(
    label: String,
    decks: List<AnkiDeck>,
    selected: AnkiDeck?,
    excludeDeck: AnkiDeck?,
    onSelected: (AnkiDeck) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected?.name ?: "Select $label",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                // MenuAnchorType.PrimaryNotEditable: readOnly field acts as trigger for the menu.
                // The typed parameter is required as of Material3 1.3.0 — the no-arg overload
                // was deprecated and is removed in BOM 2024.12+.
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            decks
                .filter { it.id != excludeDeck?.id } // Exclude the already-selected deck
                .forEach { deck ->
                    DropdownMenuItem(
                        text = { Text(deck.name) },
                        onClick = {
                            onSelected(deck)
                            expanded = false
                        }
                    )
                }
        }
    }
}
