package com.example.flashcards.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Flashcard::class, CardSet::class],  // добавьте CardSet
    version = 2,  // было 1
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class  FlashcardDatabase : RoomDatabase() {
    abstract fun flashcardDao(): FlashcardDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS card_sets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 0
                    )
                """)

                database.execSQL("ALTER TABLE flashcards ADD COLUMN setId INTEGER NOT NULL DEFAULT 1")

                database.execSQL("INSERT INTO card_sets (id, name, createdAt, isActive) VALUES (1, 'Set 1', ${System.currentTimeMillis()}, 1)")
            }
        }

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
                                    if (dao.getAllBySet(1).isEmpty()) {
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
