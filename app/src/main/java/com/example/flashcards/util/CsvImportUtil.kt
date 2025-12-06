package com.example.flashcards.util

import android.util.Log
import com.example.flashcards.data.Flashcard
import com.example.flashcards.data.FlashcardDao

/** Simple summary of an import run. */
data class ImportSummary(
    val records: Int,
    val updated: Int,
    val inserted: Int,
)

/**
 * Imports CSV lines and updates local DB so that:
 * - If a card ID exists: only side1/side2 are updated to match CSV; learned flag is preserved.
 * - If a card ID does not exist: it is inserted with learned=false.
 * - Cards missing from CSV are left untouched (not removed).
 */
suspend fun importCsvLines(csvLines: List<String>, dao: FlashcardDao, setId: Int = 1): ImportSummary {
    if (csvLines.isEmpty()) return ImportSummary(records = 0, updated = 0, inserted = 0)


    // Parse header with CSV rules (quotes, commas, escaped quotes) and strip UTF-8 BOM if present
    var headers = parseCsvLine(csvLines[0])
    if (headers.isEmpty()) return ImportSummary(records = 0, updated = 0, inserted = 0)
    headers = headers.mapIndexed { idx, s -> if (idx == 0) s.removePrefix("\uFEFF") else s }

    // Precompute column indices for side1 and side2 according to header markers "1" and "2"
    val idxSide1 = headers.withIndex().filter { it.value.trim() == "1" }.map { it.index }
    val idxSide2 = headers.withIndex().filter { it.value.trim() == "2" }.map { it.index }

    var records = 0
    var updated = 0
    var inserted = 0

    for (line in csvLines.drop(1)) {
        if (line.isEmpty()) continue
        val elements = parseCsvLine(line)
        if (elements.isEmpty()) continue

        val rawId = elements.getOrNull(0)?.trim().orEmpty()
        Log.d("ImportTAG", "rawId: setId=$rawId")
        if (rawId.isEmpty()) continue

        records++

        val side1 = idxSide1.mapNotNull { idx -> elements.getOrNull(idx)?.trim() }.filter { it.isNotEmpty() }
        val side2 = idxSide2.mapNotNull { idx -> elements.getOrNull(idx)?.trim() }.filter { it.isNotEmpty() }

        // Try to update existing card's sides (preserving learned status)
        val wasUpdated: Int = dao.updateSides(rawId, setId, side1, side2)
        if (wasUpdated == 0) {
            // Not present -> insert new with learned=false by default
            dao.insertCard(Flashcard(id = rawId, setId = setId, side1 = side1, side2 = side2, isLearned = false))
            inserted++
        } else {
            updated++
        }
    }
    return ImportSummary(records = records, updated = updated, inserted = inserted)
}

/**
 * Import CSV from raw text. Unlike importCsvLines(), this function correctly groups records
 * across physical lines when fields contain embedded newlines inside quoted cells.
 */
suspend fun importCsvText(csvText: String, dao: FlashcardDao, setId: Int = 1): ImportSummary {
// Fast path: if empty text, nothing to do
    if (csvText.isBlank()) return ImportSummary(records = 0, updated = 0, inserted = 0)

    val records = splitCsvRecords(csvText)
    return importCsvLines(records, dao, setId)
}

// --- Simple RFC 4180-like CSV parser helpers ---
private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inQuotes) {
            if (c == '"') {
                // Escaped double quote
                if (i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                sb.append(c)
            }
        } else {
            when (c) {
                ',' -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                '"' -> inQuotes = true
                else -> sb.append(c)
            }
        }
        i++
    }
    result.add(sb.toString())
    return result
}

// Split entire CSV text into logical records, respecting quoted sections that may contain newlines.
private fun splitCsvRecords(text: String): List<String> {
    val records = mutableListOf<String>()
    val lineBuilder = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '"') {
            // toggle quotes unless it's an escaped quote within quotes
            if (inQuotes) {
                // If next char is another quote, it's an escaped quote
                if (i + 1 < text.length && text[i + 1] == '"') {
                    lineBuilder.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                inQuotes = true
            }
            i++
            continue
        }

        // Handle CRLF and LF record separators only when not inside quotes
        if (!inQuotes && (c == '\n' || c == '\r')) {
            // If CRLF, skip the LF
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                i++
            }
            val record = lineBuilder.toString().trimEnd('\r')
            if (record.isNotBlank()) records.add(record)
            lineBuilder.setLength(0)
            i++
            continue
        }

        lineBuilder.append(c)
        i++
    }
    // Add last record if any
    val last = lineBuilder.toString().trimEnd('\r')
    if (last.isNotBlank()) records.add(last)
    return records
}
