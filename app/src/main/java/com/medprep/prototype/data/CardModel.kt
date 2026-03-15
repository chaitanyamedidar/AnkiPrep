package com.medprep.prototype.data

/**
 * Represents a single Anki deck returned by the CardContentProvider.
 */
data class AnkiDeck(
    val id: Long,
    val name: String
)

/**
 * Represents a single Anki card with its front text extracted and HTML-stripped.
 * Only the first field (index 0 after splitting on \x1f) is used as frontText;
 * subsequent fields (back text, extra notes) are irrelevant for duplicate detection.
 */
data class Card(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val frontText: String,  // HTML-stripped first field only
    val tags: String,
    val mod: Long
)

/**
 * A pair of cards identified as potential duplicates with a computed similarity score.
 *
 * @param cardA First card in the pair (always from Deck A, or lower noteId in same-deck pairs)
 * @param cardB Second card in the pair
 * @param similarity Cosine similarity score in [0.0, 1.0]
 * @param type Classification based on similarity threshold
 * @param isCrossDeck True when cardA and cardB originate from different decks.
 *                    Cross-deck pairs are the primary use case for this app.
 */
data class DuplicatePair(
    val cardA: Card,
    val cardB: Card,
    val similarity: Double,
    val type: DuplicateType,
    val isCrossDeck: Boolean
)

enum class DuplicateType {
    /** similarity >= 0.85: near-identical cards likely copied between decks */
    DUPLICATE,
    /** similarity in [0.65, 0.85): covers the same concept with different phrasing */
    PARTIAL
}

data class SubjectRetention(
    val subject: String,          // e.g. "Cardiology"
    val subtopics: List<String>,  // e.g. ["HeartFailure", "Arrhythmia"]
    val totalCards: Int,
    val reviewedCards: Int,
    val retentionRate: Double,    // 0.0 to 1.0
    val strength: RetentionStrength,
    val ankiSearchQuery: String   // pre-built query for AnkiDroid handoff
)

enum class RetentionStrength {
    STRONG,    // retention >= 0.85
    MODERATE,  // retention >= 0.70 and < 0.85
    WEAK       // retention < 0.70
}
