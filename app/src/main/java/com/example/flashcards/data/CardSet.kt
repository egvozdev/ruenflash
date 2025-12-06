
package com.example.flashcards.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "card_sets")
data class CardSet(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
)
