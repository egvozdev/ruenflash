package com.example.flashcards.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class StringListConverter {
    @TypeConverter
    fun fromList(list: List<String>): String = Gson().toJson(list)

    @TypeConverter
    fun fromString(value: String): List<String> {
        return if (value.isBlank()) emptyList()
        else Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
    }
}
