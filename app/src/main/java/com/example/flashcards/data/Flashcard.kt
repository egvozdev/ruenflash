package com.example.flashcards.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

@Entity(tableName = "flashcards")
@TypeConverters(StringListConverter::class)
data class Flashcard(
    @PrimaryKey val id: String,
    val side1: List<String>,
    val side2: List<String>,
    var isLearned: Boolean = false
)
