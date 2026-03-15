package com.medprep.prototype.engine

import com.medprep.prototype.data.Card
import com.medprep.prototype.data.DuplicatePair

/**
 * Orchestrates the three-stage duplicate detection pipeline.
 *
 * Stage 1 — Inverted Index + IDF Filter: reduces O(n²) pairs to ~O(n log n) candidates.
 * Stage 2 — TF-IDF Vectorization: computes sparse vectors for candidate cards only.
 * Stage 3 — Cosine Similarity: scores and thresholds each candidate pair.
 *
 * Progress is reported via [onProgress] (0–100) so the UI can show a live indicator.
 * The pipeline runs entirely off the main thread (called from a coroutine in ViewModel).
 */
class DuplicateEngine {

    private val invertedIndex = InvertedIndex()
    private val tfidfVectorizer = TfIdfVectorizer()
    private val cosineSimilarity = CosineSimilarity()

    /**
     * Runs the full pipeline and returns all flagged duplicate pairs.
     *
     * @param deckACards Cards from the first selected deck
     * @param deckBCards Cards from the second selected deck
     * @param onProgress Callback receiving progress integers from 0 to 100.
     *                   Called at key pipeline boundaries, not per-card.
     */
    suspend fun run(
        deckACards: List<Card>,
        deckBCards: List<Card>,
        onProgress: (Int) -> Unit
    ): List<DuplicatePair> {

        onProgress(5)

        val allCards = deckACards + deckBCards
        val deckAIds: Set<Long> = deckACards.map { it.noteId }.toHashSet()

        // --- Stage 1: Tokenize all cards and build candidate pair set ---
        onProgress(10)
        val tokenizedCards = invertedIndex.tokenizeAll(allCards)
        val tokenizedByNoteId = tokenizedCards.associateBy { it.card.noteId }

        onProgress(20)
        val candidatePairs = invertedIndex.build(tokenizedCards, deckAIds)

        // --- Stage 2: Compute sparse TF-IDF vectors for candidate cards only ---
        onProgress(50)
        val vectors = tfidfVectorizer.compute(
            allCards = allCards,
            tokenizedCards = tokenizedByNoteId,
            candidatePairs = candidatePairs
        )

        // --- Stage 3: Score each candidate pair with cosine similarity ---
        onProgress(80)
        val cardIndex: Map<Long, Card> = allCards.associateBy { it.noteId }
        val results = cosineSimilarity.score(
            candidatePairs = candidatePairs,
            vectors = vectors,
            cardIndex = cardIndex,
            deckAIds = deckAIds
        )

        onProgress(100)
        return results
    }
}
