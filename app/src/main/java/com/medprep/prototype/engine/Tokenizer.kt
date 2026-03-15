package com.medprep.prototype.engine

import android.text.Html

/**
 * Tokenizes card front text into a list of clean, discriminative terms.
 *
 * HTML stripping must precede tokenization. AnkiDroid cards frequently embed
 * <b>, <br>, <div>, and <img> tags directly in field text. Without stripping,
 * "heart<b>failure</b>" tokenizes as "heartbfailure" rather than two separate
 * tokens, corrupting all downstream TF-IDF and cosine similarity computations.
 */
object Tokenizer {

    // Regex for matching Anki cloze syntax: {{c1::answer}} or {{c1::answer::hint}}
    // The first capture group contains the answer text.
    private val CLOZE_SYNTAX_REGEX = Regex("""\{\{c\d+::([^:}]+)(?:::[^}]*)?\}\}""")

    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    // Stopwords that appear in nearly every medical card and carry zero
    // discriminative signal for duplicate detection.
    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all",
        "can", "has", "was", "had", "his", "her", "its", "this",
        "that", "from", "have", "been", "with", "they", "will"
    )

    /**
     * Produces a list of clean tokens from raw card text.
     *
     * Pipeline:
     *  1. Strip cloze syntax ({{c1::answer}} -> "answer") so the ML pipeline
     *     doesn't build TF-IDF vectors out of internal Anki markers like "c1".
     *  2. Decode HTML entities via Html.fromHtml() — handles &nbsp;, &amp;, &lt;, &gt;,
     *     &quot;, &#39;, &mdash;, and all other named/numeric entities in one pass.
     *     Must run BEFORE tag stripping: &lt;b&gt; → "<b>" → stripped by step 3.
     *  3. Strip residual HTML tags (handles <b>, <br/>, <div class="...">, <img src="...">)
     *  4. Lowercase — "Receptor" and "receptor" must hash to the same token
     *  5. Split on any non-alphabetic character (spaces, digits, punctuation, hyphens)
     *  6. Drop tokens shorter than 3 characters (single letters, abbreviation noise)
     *  7. Remove domain-agnostic stopwords
     */
    fun tokenize(text: String): List<String> {
        // Step 1: Strip cloze syntax
        val declozed = CLOZE_SYNTAX_REGEX.replace(text) { matchResult ->
            matchResult.groupValues[1]
        }
        
        // Step 2: decode HTML entities (&nbsp; → " ", &amp; → "&", &#39; → "'", etc.)
        @Suppress("DEPRECATION")
        val decoded = Html.fromHtml(declozed, Html.FROM_HTML_MODE_LEGACY).toString()
        
        // Step 3: strip any residual HTML tags
        val stripped = HTML_TAG_REGEX.replace(decoded, " ")
        val lowered = stripped.lowercase()
        return lowered
            .split(Regex("[^a-z]+"))
            .filter { token ->
                token.length >= 3 && token !in STOPWORDS
            }
    }
}
