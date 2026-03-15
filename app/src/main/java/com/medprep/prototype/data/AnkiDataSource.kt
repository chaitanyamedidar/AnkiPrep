package com.medprep.prototype.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Reads card and deck data from AnkiDroid's CardContentProvider.
 * This class is the sole interface between MedPrep and AnkiDroid's data store.
 * MedPrep is strictly read-only — no write operations are performed here.
 *
 * API reference: com.ichi2.anki.FlashCardsContract (AnkiDroid API module)
 * Authority: com.ichi2.anki.flashcards
 *
 * Deck columns: deck_id (Long), deck_name (String)
 * Note columns: _id (Long), flds (String, 0x1F separated), tags (String), mid (Long), mod (Long)
 */
class AnkiDataSource(private val context: Context) {

    companion object {
        private const val TAG = "AnkiDataSource"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"

        // CardContentProvider registers "decks/" with trailing slash — must match exactly
        private val DECKS_URI = Uri.parse("content://$AUTHORITY/decks/")

        // FlashCardsContract.Note.CONTENT_URI — selection is Anki browser search syntax
        private val NOTES_URI = Uri.parse("content://$AUTHORITY/notes")

        // FlashCardsContract.Note.CONTENT_URI_V2 — selection is direct SQL on notes table
        // Supports LIMIT/OFFSET in the selection string with ? placeholders
        private val NOTES_V2_URI = Uri.parse("content://$AUTHORITY/notes_v2")

        // Anki field separator: ASCII unit separator (0x1F), used between note fields
        private const val FIELD_SEPARATOR = '\u001f'

        // Regex for matching Anki cloze syntax: {{c1::answer}} or {{c1::answer::hint}}
        // The first capture group contains the answer text.
        private val CLOZE_SYNTAX_REGEX = Regex("""\{\{c\d+::([^:}]+)(?:::[^}]*)?\}\}""")

        // Regex for stripping HTML tags from card front text
        private val HTML_TAG_REGEX = Regex("<[^>]+>")

        // Pagination batch size: 1000 cards per ContentProvider query.
        // At 30,000 cards a single unbounded query takes 15-25 seconds and risks ANR.
        // Batching at 1000 allows progressive loading and keeps each query under 200ms.
        private const val PAGE_SIZE = 1000

        // FlashCardsContract.Deck column names
        private const val COL_DECK_ID = "deck_id"
        private const val COL_DECK_NAME = "deck_name"

        // FlashCardsContract.Note column names
        private const val COL_NOTE_ID = "_id"
        private const val COL_FLDS = "flds"
        private const val COL_TAGS = "tags"
        private const val COL_MID = "mid"
        private const val COL_MOD = "mod"
    }

    /**
     * Fetches all available decks from AnkiDroid.
     * Returns an empty list if AnkiDroid is not installed or permission is not granted.
     *
     * Uses FlashCardsContract.Deck.CONTENT_ALL_URI with columns deck_id and deck_name.
     */
    fun fetchAllDecks(): List<AnkiDeck> {
        val decks = mutableListOf<AnkiDeck>()

        val cursor = try {
            context.contentResolver.query(
                DECKS_URI,
                arrayOf(COL_DECK_ID, COL_DECK_NAME),
                null,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query decks: ${e.message}", e)
            null
        }

        if (cursor == null) {
            Log.e(TAG, "Decks cursor is null — AnkiDroid may not be installed or permission denied")
            return emptyList()
        }

        cursor.use {
            val columns = it.columnNames
            Log.d(TAG, "Decks cursor columns: ${columns.joinToString()}, row count: ${it.count}")

            val idIndex = it.getColumnIndex(COL_DECK_ID)
            val nameIndex = it.getColumnIndex(COL_DECK_NAME)

            if (idIndex == -1 || nameIndex == -1) {
                Log.e(TAG, "Required deck columns missing! " +
                        "Expected '$COL_DECK_ID' and '$COL_DECK_NAME'. " +
                        "Available: ${columns.joinToString()}")
                return emptyList()
            }

            while (it.moveToNext()) {
                decks.add(
                    AnkiDeck(
                        id = it.getLong(idIndex),
                        name = it.getString(nameIndex) ?: "Unnamed Deck"
                    )
                )
            }
        }

        Log.d(TAG, "Fetched ${decks.size} decks")
        return decks
    }

    /**
     * Fetches all cards belonging to [deckId] using paginated ContentProvider queries.
     *
     * Uses FlashCardsContract.Note.CONTENT_URI_V2 which accepts direct SQL selection
     * for filtering by deck and pagination via LIMIT/OFFSET.
     *
     * The [onPageLoaded] callback is invoked after each batch with the running total,
     * allowing the ViewModel to emit incremental progress updates to the UI.
     */
    suspend fun fetchCardsForDeck(
        deckId: Long,
        onPageLoaded: (loadedSoFar: Int) -> Unit = {}
    ): List<Card> {
        val cards = mutableListOf<Card>()
        var offset = 0

        while (true) {
            val batch = fetchCardsBatch(deckId, offset, PAGE_SIZE)
            if (batch.isEmpty()) break
            cards.addAll(batch)
            offset += batch.size
            onPageLoaded(cards.size)
            // Stop when we get fewer records than requested — that's the last page
            if (batch.size < PAGE_SIZE) break
        }

        Log.d(TAG, "Total cards fetched for deck $deckId: ${cards.size}")
        return cards
    }

    /**
     * Executes a single paginated query for [limit] notes starting at [offset].
     *
     * Uses notes_v2 URI which accepts direct SQL in the selection parameter.
     * The notes table has a column `did` (deck id) we can filter on directly.
     * Falls back to the standard notes URI with Anki browser search syntax.
     */
    private fun fetchCardsBatch(deckId: Long, offset: Int, limit: Int): List<Card> {
        val projection = arrayOf(COL_NOTE_ID, COL_FLDS, COL_TAGS, COL_MID, COL_MOD)

        // notes_v2: direct SQL on the `notes` table. Cards belong to a deck via the `cards` table
        // (cards.did = deckId), and each card references a note via cards.nid.
        // Simpler: filter notes by deck using a subquery on the cards table.
        val v2Selection = "id IN (SELECT nid FROM cards WHERE did = ?) LIMIT $limit OFFSET $offset"
        var cursor = try {
            context.contentResolver.query(
                NOTES_V2_URI,
                projection,
                v2Selection,
                arrayOf(deckId.toString()),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "notes_v2 query failed, trying notes URI: ${e.message}")
            null
        }

        // Fallback: NOTES URI accepts Anki browser search syntax in `selection`.
        // "did:12345" searches for notes in deck with id 12345.
        // NOTE: If findNotes() returns empty, the provider returns null (not an empty cursor).
        // Also, no LIMIT/OFFSET support here — only use for offset=0.
        if (cursor == null && offset == 0) {
            cursor = try {
                context.contentResolver.query(
                    NOTES_URI,
                    projection,
                    "did:$deckId",
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Notes fallback query failed: ${e.message}", e)
                null
            }
        }

        if (cursor == null) {
            Log.d(TAG, "Cursor null for deck $deckId offset=$offset (may be end of results)")
            return emptyList()
        }

        val batch = mutableListOf<Card>()
        cursor.use {
            if (it.count == 0) return emptyList()

            val columns = it.columnNames
            Log.d(TAG, "Notes cursor columns: ${columns.joinToString()}, rows: ${it.count}")

            val idIdx = it.getColumnIndex(COL_NOTE_ID)
            val fldsIdx = it.getColumnIndex(COL_FLDS)
            val tagsIdx = it.getColumnIndex(COL_TAGS)
            val midIdx = it.getColumnIndex(COL_MID)
            val modIdx = it.getColumnIndex(COL_MOD)

            if (idIdx == -1 || fldsIdx == -1) {
                Log.e(TAG, "Required note columns missing! " +
                        "Expected '$COL_NOTE_ID' and '$COL_FLDS'. " +
                        "Available: ${columns.joinToString()}")
                return emptyList()
            }

            while (it.moveToNext()) {
                val noteId = it.getLong(idIdx)
                val flds = it.getString(fldsIdx) ?: ""
                // We only need index 0 (the front/question field).
                val frontRaw = flds.split(FIELD_SEPARATOR).firstOrNull() ?: ""
                
                // Step 1: Strip cloze syntax {{c1::answer}} -> "answer"
                val declozed = CLOZE_SYNTAX_REGEX.replace(frontRaw) { matchResult ->
                    matchResult.groupValues[1]
                }
                
                // Step 2: Decode HTML entities (&nbsp;, &amp;, etc) to proper characters
                @Suppress("DEPRECATION")
                val decoded = android.text.Html.fromHtml(declozed, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                
                // Step 3: Strip any residual HTML tags for clean display text
                val frontText = HTML_TAG_REGEX.replace(decoded, " ").trim()

                batch.add(
                    Card(
                        id = noteId,
                        noteId = noteId,
                        deckId = deckId,
                        frontText = frontText,
                        tags = if (tagsIdx != -1) it.getString(tagsIdx) ?: "" else "",
                        mod = if (modIdx != -1) it.getLong(modIdx) else 0L
                    )
                )
            }
        }

        Log.d(TAG, "Batch: offset=$offset, fetched=${batch.size} cards for deck $deckId")
        return batch
    }

    /**
     * Builds an Intent that opens AnkiDroid's card browser filtered to [noteId].
     *
     * Primary strategy: direct URI navigation to the note's content URI.
     * AnkiDroid handles ACTION_VIEW on its note URIs by opening the browser
     * with that note selected.
     */
    fun buildAnkiDroidCardIntent(noteId: Long): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://$AUTHORITY/notes/$noteId")
            setPackage("com.ichi2.anki")
        }
    }

    /**
     * Fallback intent that opens AnkiDroid's card browser without a specific card.
     * Used when [buildAnkiDroidCardIntent] cannot be resolved on the device.
     */
    fun buildAnkiDroidFallbackIntent(): Intent {
        return context.packageManager.getLaunchIntentForPackage("com.ichi2.anki")
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.ichi2.anki")
            }
    }
}
