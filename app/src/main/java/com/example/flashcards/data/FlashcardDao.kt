package com.example.flashcards.data

import androidx.room.*

@Dao
interface FlashcardDao {
    // Card Sets operations
    @Query("SELECT * FROM card_sets ORDER BY id")
    suspend fun getAllSets(): List<CardSet>

    @Insert
    suspend fun insertSet(set: CardSet): Long

    @Query("UPDATE card_sets SET isActive = :isActive WHERE id = :id")
    suspend fun updateSetActive(id: Int, isActive: Boolean)

    @Query("SELECT * FROM card_sets WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSet(): CardSet?

    @Query("DELETE FROM card_sets WHERE id = :id")
    suspend fun deleteSet(id: Int)



        @Query("SELECT * FROM flashcards WHERE setId = :setId")
        suspend fun getAllBySet(setId: Int): List<Flashcard>


        @Query("SELECT * FROM flashcards WHERE setId = :setId AND isLearned = :status")
        suspend fun getCardsByStatusAndSet(status: Boolean, setId: Int): List<Flashcard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: Flashcard)


         @Query("UPDATE flashcards SET isLearned = :learned WHERE id = :id AND setId = :setId")
        suspend fun updateLearnedStatus(id: String, learned: Boolean, setId: Int)


        @Query("DELETE FROM flashcards WHERE id = :id AND setId = :setId")
        suspend fun deleteById(id: String, setId: Int)




    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: Flashcard)

    @Update
    suspend fun updateCard(card: Flashcard)



    // Update only sides while preserving existing learned flag
   // @Query("UPDATE flashcards SET side1 = :side1, side2 = :side2 WHERE id = :id")
   // suspend fun updateSides(id: String, side1: List<String>, side2: List<String>): Int


       @Query("UPDATE flashcards SET side1 = :side1, side2 = :side2 WHERE id = :id AND setId = :setId")
        suspend fun updateSides(id: String, setId: Int, side1: List<String>, side2: List<String>): Int


    @Query("DELETE FROM flashcards WHERE setId = :setId")
    suspend fun deleteAllBySet(setId: Int)

}
