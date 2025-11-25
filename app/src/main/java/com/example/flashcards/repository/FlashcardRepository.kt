package com.example.flashcards.repository

import com.example.flashcards.data.Flashcard
import com.example.flashcards.data.FlashcardDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository layer that guarantees all DAO calls occur on Dispatchers.IO to avoid
 * any chance of blocking the main thread and contributing to UI jank/ANRs.
 */
class FlashcardRepository(private val dao: FlashcardDao) {
    suspend fun getAll(): List<Flashcard> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getCardsByStatus(learned: Boolean): List<Flashcard> = withContext(Dispatchers.IO) {
        dao.getCardsByStatus(learned)
    }

    suspend fun insertCard(card: Flashcard) = withContext(Dispatchers.IO) {
        dao.insertCard(card)
    }

    suspend fun updateLearnedStatus(cardId: String, status: Boolean) = withContext(Dispatchers.IO) {
        dao.updateLearnedStatus(cardId, status)
    }

    suspend fun deleteById(cardId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(cardId)
    }
}
