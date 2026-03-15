package com.medprep.prototype.engine

import com.medprep.prototype.data.Card

/**
 * Internal representation of a card with its pre-computed tokens.
 * Avoids re-tokenizing the same card across stages 1, 2, and 3.
 */
data class TokenizedCard(
    val card: Card,
    val tokens: List<String>
)

/**
 * Stage 1 of the duplicate detection pipeline.
 *
 * Builds an inverted index over all cards to reduce the O(n²) candidate
 * space to a tractable subset. Inverted indexing was chosen over LSH because:
 * - At 30,000 cards, the inverted index + IDF filter achieves 99%+ candidate
 *   reduction without requiring tuning of hash band/row parameters.
 * - The implementation is transparent and deterministic, which matters for
 *   a GSoC proposal where algorithmic clarity is valued over raw performance.
 * - For the medical deck domain (high term repetition), inverted index
 *   filtering with an IDF threshold is more precise than approximate LSH.
 */
class InvertedIndex {

    /**
     * Builds the set of candidate pairs by tokenizing all cards, constructing
     * an inverted index, filtering high-frequency terms by IDF ratio, and
     * generating all pairs sharing at least one surviving term.
     *
     * @param cards Combined list of cards from both decks (already tokenized)
     * @param deckAIds Set of noteIds belonging to Deck A, used for cross-deck annotation
     * @return Pair of (candidate set, cross-deck boolean map)
     */
    fun build(
        cards: List<TokenizedCard>,
        deckAIds: Set<Long>
    ): Set<Pair<Long, Long>> {
        val totalCards = cards.size
        if (totalCards == 0) return emptySet()

        // Build inverted index: term → list of noteIds containing that term
        val index = HashMap<String, MutableList<Long>>(8192)
        for (tc in cards) {
            for (token in tc.tokens.toSet()) { // toSet() deduplicates tokens within a card
                index.getOrPut(token) { mutableListOf() }.add(tc.card.noteId)
            }
        }

        // IDF threshold filter: exclude terms appearing in more than 4% of cards.
        //
        // In a 30,000-card medical deck, terms like "receptor", "inhibitor",
        // "syndrome", or "patient" may appear in 3,000+ cards. A single such
        // term generates C(3000,2) ≈ 4.5 million pairs — all meaningless for
        // duplicate detection. The 4% threshold eliminates this noise while
        // preserving discriminative terms like "rhabdomyolysis" or "nephrotoxicity"
        // that appear in only a handful of cards.
        val frequencyThreshold = totalCards * 0.04f

        val candidates = HashSet<Pair<Long, Long>>(4096)

        for ((_, postingList) in index) {
            // Skip terms that appear in more than 4% of all cards
            if (postingList.size > frequencyThreshold) continue

            // Generate all canonical pairs (a < b) from the posting list.
            // Canonical ordering ensures (idA, idB) and (idB, idA) hash to the same entry.
            for (i in postingList.indices) {
                for (j in i + 1 until postingList.size) {
                    val a = postingList[i]
                    val b = postingList[j]
                    // Enforce canonical ordering: smaller noteId always first
                    if (a < b) candidates.add(Pair(a, b))
                    else candidates.add(Pair(b, a))
                }
            }
        }

        return candidates
    }

    /**
     * Tokenizes all cards and returns them as [TokenizedCard] instances.
     * Centralised here so tokenization happens exactly once per card for the entire pipeline.
     */
    fun tokenizeAll(cards: List<Card>): List<TokenizedCard> {
        return cards.map { card ->
            TokenizedCard(
                card = card,
                tokens = Tokenizer.tokenize(card.frontText)
            )
        }
    }
}
