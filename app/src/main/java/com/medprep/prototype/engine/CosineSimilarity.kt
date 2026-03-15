package com.medprep.prototype.engine

import com.medprep.prototype.data.Card
import com.medprep.prototype.data.DuplicatePair
import com.medprep.prototype.data.DuplicateType
import kotlin.math.sqrt

/**
 * Stage 3 of the duplicate detection pipeline.
 *
 * Scores each candidate pair using cosine similarity over their TF-IDF vectors.
 * Cosine similarity was chosen over Euclidean distance because it is invariant
 * to card length — a card with 5 tokens covering the same concepts as a card
 * with 50 tokens scores equally high if the discriminative terms are shared.
 * This is critical in medical decks where card verbosity varies widely.
 *
 * Threshold rationale:
 *  - 0.85 (DUPLICATE): Near-identical phrasing across decks, likely copy-paste.
 *  - 0.65 (PARTIAL): Same concept, different phrasing — clinically overlapping.
 * Both thresholds were chosen to minimize false positives in high-vocabulary
 * medical content where incidental term overlap is common.
 */
class CosineSimilarity {

    companion object {
        const val THRESHOLD_DUPLICATE = 0.85
        const val THRESHOLD_PARTIAL = 0.65
    }

    /**
     * Scores all candidate pairs and returns flagged [DuplicatePair]s sorted by
     * similarity descending, with cross-deck pairs appearing before same-deck pairs
     * at equal similarity.
     *
     * Dot product is computed over SHARED TERMS ONLY (intersection of vector keys).
     * This makes each dot product O(min(|vA|, |vB|)) ≈ O(25) rather than O(|vocabulary|),
     * which is decisive when scoring 300,000 candidate pairs on a mobile device.
     *
     * @param candidatePairs Candidate pairs from Stage 1
     * @param vectors TF-IDF vectors from Stage 2, keyed by noteId
     * @param cardIndex Full card lookup map, keyed by noteId
     * @param deckAIds Set of noteIds belonging to Deck A (for cross-deck annotation)
     */
    fun score(
        candidatePairs: Set<Pair<Long, Long>>,
        vectors: Map<Long, Map<String, Double>>,
        cardIndex: Map<Long, Card>,
        deckAIds: Set<Long>
    ): List<DuplicatePair> {

        val results = mutableListOf<DuplicatePair>()

        for ((idA, idB) in candidatePairs) {
            val vectorA = vectors[idA] ?: continue
            val vectorB = vectors[idB] ?: continue

            // Find shared terms: intersection of term sets.
            // Iterating only shared terms makes dot product O(min(|vA|,|vB|))
            // rather than O(|vocabulary|) — critical at scale.
            val sharedTerms = vectorA.keys.intersect(vectorB.keys)
            if (sharedTerms.isEmpty()) continue

            // Dot product over shared terms only
            var dot = 0.0
            for (term in sharedTerms) {
                dot += vectorA[term]!! * vectorB[term]!!
            }

            // Magnitudes from full vector (not just shared terms)
            val magA = sqrt(vectorA.values.sumOf { it * it })
            val magB = sqrt(vectorB.values.sumOf { it * it })

            // Guard against zero-magnitude vectors (cards with no meaningful content)
            if (magA == 0.0 || magB == 0.0) continue

            val similarity = dot / (magA * magB)

            val type = when {
                similarity >= THRESHOLD_DUPLICATE -> DuplicateType.DUPLICATE
                similarity >= THRESHOLD_PARTIAL -> DuplicateType.PARTIAL
                else -> continue // Below threshold — discard this pair
            }

            val cardA = cardIndex[idA] ?: continue
            val cardB = cardIndex[idB] ?: continue

            // Cross-deck: one card from Deck A, the other from Deck B.
            // Cross-deck pairs are the primary actionable insight of this app.
            val isCrossDeck = (idA in deckAIds) != (idB in deckAIds)

            results.add(
                DuplicatePair(
                    cardA = cardA,
                    cardB = cardB,
                    similarity = similarity,
                    type = type,
                    isCrossDeck = isCrossDeck
                )
            )
        }

        // Sort: cross-deck pairs first, then by similarity descending.
        // This ordering places the most actionable results at the top —
        // same-deck duplicates are lower priority as they affect only one deck.
        return results.sortedWith(
            compareByDescending<DuplicatePair> { it.isCrossDeck }
                .thenByDescending { it.similarity }
        )
    }
}
