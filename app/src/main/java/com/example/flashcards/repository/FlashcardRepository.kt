package com.example.flashcards.repository

import com.example.flashcards.data.Flashcard
import com.example.flashcards.data.FlashcardDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.flashcards.data.CardSet


/**
 * Repository layer that guarantees all DAO calls occur on Dispatchers.IO to avoid
 * any chance of blocking the main thread and contributing to UI jank/ANRs.
 */
class FlashcardRepository(private val dao: FlashcardDao) {
        // Card Sets operations
        suspend fun getAllSets() = withContext(Dispatchers.IO) {
                dao.getAllSets()
    }

        suspend fun createSet(name: String): Long = withContext(Dispatchers.IO) {
                dao.insertSet(CardSet(name = name))
           }

        suspend fun setActiveSet(id: Int) = withContext(Dispatchers.IO) {
                // Deactivate all sets first
                dao.getAllSets().forEach { dao.updateSetActive(it.id, false) }
                // Activate selected set
                dao.updateSetActive(id, true)
            }

        suspend fun getActiveSet() = withContext(Dispatchers.IO) {
                dao.getActiveSet()
            }

        suspend fun deleteSet(id: Int) = withContext(Dispatchers.IO) {
                dao.deleteAllBySet(id)
                dao.deleteSet(id)
            }

        // Flashcard operations (now with setId)
        suspend fun getAll(setId: Int) = withContext(Dispatchers.IO) {
               dao.getAllBySet(setId)
           }

        suspend fun getCardsByStatus(learned: Boolean, setId: Int) = withContext(Dispatchers.IO) {
                dao.getCardsByStatusAndSet(learned, setId)

    }

    suspend fun insertCard(card: Flashcard) = withContext(Dispatchers.IO) {
        dao.insertCard(card)
    }

        suspend fun updateLearnedStatus(id: String, learned: Boolean, setId: Int) = withContext(Dispatchers.IO) {
                dao.updateLearnedStatus(id, learned, setId)

    }

    suspend fun deleteById(id: String, setId: Int) = withContext(Dispatchers.IO) {
               dao.deleteById(id, setId)
    }
}
