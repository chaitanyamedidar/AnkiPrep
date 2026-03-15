package com.medprep.prototype.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import com.medprep.prototype.data.DuplicatePair
import com.medprep.prototype.data.DuplicateType

/**
 * Screen 3 — Results.
 *
 * Displays a LazyColumn of [DuplicatePairCard] composables.
 * Cross-deck pairs are guaranteed to appear before same-deck pairs because
 * the list is pre-sorted by the ViewModel (CosineSimilarity.score handles ordering).
 *
 * If no pairs were found above the 0.65 cosine similarity threshold, shows
 * an empty-state message rather than a blank list.
 */
@Composable
fun ResultsScreen(
    pairs: List<DuplicatePair>,
    onOpenInAnkiDroid: (DuplicatePair) -> Unit,
    onBack: () -> Unit
) {
    var selectedPair by remember { mutableStateOf<DuplicatePair?>(null) }
    val crossDeckCount = pairs.count { it.isCrossDeck }
    val sameDeckCount = pairs.size - crossDeckCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Duplicate Pairs Found: ${pairs.size}",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Cross-deck: $crossDeckCount | Same-deck: $sameDeckCount",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (pairs.isEmpty()) {
            Text(
                text = "No duplicates found between these decks.",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(pairs) { pair ->
                    DuplicatePairCard(
                        pair = pair,
                        onClick = { selectedPair = pair },
                        onOpenInAnkiDroid = { onOpenInAnkiDroid(pair) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Compare Different Decks")
        }
    }

    selectedPair?.let { pair ->
        DetailedAnalysisDialog(
            pair = pair,
            onDismiss = { selectedPair = null },
            onOpenInAnkiDroid = {
                selectedPair = null
                onOpenInAnkiDroid(pair)
            }
        )
    }
}

/**
 * A single card in the results list showing both card texts, the similarity
 * score, and the type badge. The "Open in AnkiDroid" button fires the Intent
 * via the ViewModel, which handles the ActivityNotFoundException fallback.
 */
@Composable
private fun DuplicatePairCard(
    pair: DuplicatePair,
    onClick: () -> Unit,
    onOpenInAnkiDroid: () -> Unit
) {
    val badgeColor = when (pair.type) {
        DuplicateType.DUPLICATE -> Color(0xFFD32F2F)  // Red — near-identical cards
        DuplicateType.PARTIAL -> Color(0xFFF57C00)    // Orange — overlapping concepts
    }
    val badgeLabel = pair.type.name
    val similarityPercent = (pair.similarity * 100).toInt()
    val scopeLabel = if (pair.isCrossDeck) "Cross-deck" else "Same-deck"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Type badge — color-coded for quick scanning
                Surface(
                    color = badgeColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = badgeLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = scopeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Card A front text — truncated to 80 characters to keep rows scannable
            Text(
                text = "A: ${pair.cardA.frontText.take(80)}${if (pair.cardA.frontText.length > 80) "…" else ""}",
                style = MaterialTheme.typography.bodySmall
            )

            // Card B front text — truncated to 80 characters
            Text(
                text = "B: ${pair.cardB.frontText.take(80)}${if (pair.cardB.frontText.length > 80) "…" else ""}",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$similarityPercent% similar",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onOpenInAnkiDroid) {
                    Text("Open in AnkiDroid →")
                }
            }
        }
    }
}

@Composable
fun DetailedAnalysisDialog(
    pair: DuplicatePair,
    onDismiss: () -> Unit,
    onOpenInAnkiDroid: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detailed Analysis") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val similarityPercent = (pair.similarity * 100).toInt()
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Similarity: $similarityPercent%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(if (pair.isCrossDeck) "Cross-deck" else "Same-deck", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                
                Text("Card A", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Note ID: ${pair.cardA.noteId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pair.cardA.tags.isNotBlank()) {
                    Text("Tags: ${pair.cardA.tags}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(pair.cardA.frontText, style = MaterialTheme.typography.bodyMedium)
                
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                
                Text("Card B", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Note ID: ${pair.cardB.noteId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pair.cardB.tags.isNotBlank()) {
                    Text("Tags: ${pair.cardB.tags}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(pair.cardB.frontText, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenInAnkiDroid) {
                Text("Open in AnkiDroid")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
