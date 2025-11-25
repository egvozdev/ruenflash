package com.example.flashcards.data

import androidx.room.*

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM flashcards")
    suspend fun getAll(): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE isLearned = :learned")
    suspend fun getCardsByStatus(learned: Boolean): List<Flashcard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: Flashcard)

    @Update
    suspend fun updateCard(card: Flashcard)

    @Query("UPDATE flashcards SET isLearned = :status WHERE id = :cardId")
    suspend fun updateLearnedStatus(cardId: String, status: Boolean)

    // Update only sides while preserving existing learned flag
    @Query("UPDATE flashcards SET side1 = :side1, side2 = :side2 WHERE id = :id")
    suspend fun updateSides(id: String, side1: List<String>, side2: List<String>): Int

    @Query("DELETE FROM flashcards WHERE id = :cardId")
    suspend fun deleteById(cardId: String)
}
