package com.medprep.prototype.engine

import com.medprep.prototype.data.Card
import kotlin.math.ln

/**
 * Stage 2 of the duplicate detection pipeline.
 *
 * Computes sparse TF-IDF vectors for cards that appear in at least one
 * candidate pair. Vectorizing only candidate cards (not all cards) is a
 * deliberate optimization: after Stage 1 filtering, typical candidate sets
 * contain 1-5% of the original card pool, making full-collection vectorization
 * wasteful.
 *
 * Sparse representation rationale: a dense vector over an 800-term vocabulary
 * for 500 candidate cards = 400,000 entries. Sparse storage at ~25 non-zero
 * terms per card = 12,500 entries — a 97% reduction that makes Stage 3 dot
 * products computationally trivial even on mid-range Android devices.
 */
class TfIdfVectorizer {

    /**
     * Computes TF-IDF vectors for all cards referenced in [candidatePairs].
     *
     * IDF is computed across the FULL card collection (not just candidates) to
     * ensure accurate document frequency estimates. A term's IDF computed only
     * over candidate cards would be artificially inflated, biasing similarity scores.
     *
     * @param allCards Full card collection from both decks (needed for IDF)
     * @param tokenizedCards Pre-tokenized cards mapped by noteId (avoids re-tokenization)
     * @param candidatePairs Candidate pairs from Stage 1 (determines which cards to vectorize)
     * @return Map from noteId to sparse TF-IDF vector (term → weight)
     */
    fun compute(
        allCards: List<Card>,
        tokenizedCards: Map<Long, TokenizedCard>,
        candidatePairs: Set<Pair<Long, Long>>
    ): Map<Long, Map<String, Double>> {

        val totalCards = allCards.size.toDouble()
        if (totalCards == 0.0) return emptyMap()

        // Collect the unique set of noteIds that appear in at least one candidate pair
        val candidateIds = HashSet<Long>(candidatePairs.size * 2)
        for ((a, b) in candidatePairs) {
            candidateIds.add(a)
            candidateIds.add(b)
        }

        // Compute document frequency: how many cards contain each term?
        // Must use the FULL collection for accurate IDF — not just candidates.
        val docFrequency = HashMap<String, Int>(4096)
        for (tc in tokenizedCards.values) {
            for (term in tc.tokens.toSet()) { // toSet() counts term once per document
                docFrequency[term] = (docFrequency[term] ?: 0) + 1
            }
        }

        // Produce a TF-IDF vector for each candidate card
        val vectors = HashMap<Long, Map<String, Double>>(candidateIds.size)

        for (noteId in candidateIds) {
            val tc = tokenizedCards[noteId] ?: continue
            if (tc.tokens.isEmpty()) continue

            val totalTokens = tc.tokens.size.toDouble()
            // Count term occurrences within this card
            val termCounts = HashMap<String, Int>()
            for (token in tc.tokens) {
                termCounts[token] = (termCounts[token] ?: 0) + 1
            }

            // Compute TF-IDF for each unique term in this card
            val vector = HashMap<String, Double>(termCounts.size)
            for ((term, count) in termCounts) {
                val tf = count / totalTokens
                val df = docFrequency[term] ?: 1
                // IDF = ln(N / df): rare terms in medical decks (e.g., "rhabdomyolysis")
                // get high IDF, pushing their TF-IDF weight up substantially.
                val idf = ln(totalCards / df.toDouble())
                val weight = tf * idf
                // Sparse storage: only store non-zero weights
                if (weight > 0.0) vector[term] = weight
            }

            if (vector.isNotEmpty()) {
                vectors[noteId] = vector
            }
        }

        return vectors
    }
}
