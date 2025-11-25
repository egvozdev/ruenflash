package com.example.flashcards.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Flashcard::class], version = 1)
@TypeConverters(StringListConverter::class)
abstract class FlashcardDatabase : RoomDatabase() {
    abstract fun flashcardDao(): FlashcardDao

    companion object {
        @Volatile
        private var INSTANCE: FlashcardDatabase? = null

        fun getDatabase(context: Context): FlashcardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FlashcardDatabase::class.java,
                    "flashcard_db"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // Prepopulate a few sample cards on first run (only if DB empty)
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val dao = database.flashcardDao()
                                    if (dao.getAll().isEmpty()) {
                                        try {
                                            dao.insertCard(
                                                Flashcard(
                                                    id = "sample_1",
                                                    side1 = listOf("Download cvs file", "with words from", "Google drive.", "Tap for file", "format example"),
                                                    side2 = listOf("1,1,1,1,1,2", "der Tisch, pl: Tische, , , , Стол", "essen, du isst, ihr esst, pII gegessen, , есть"),
                                                    isLearned = false
                                                )
                                            )
                                        } catch (_: Exception) {
                                            // Ignore prepopulate errors
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
