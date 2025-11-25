package com.example.flashcards.viewmodel

import android.util.Log
import androidx.lifecycle.*
import com.example.flashcards.data.Flashcard
import com.example.flashcards.repository.FlashcardRepository
import kotlinx.coroutines.launch

class FlashcardViewModel(private val repository: FlashcardRepository) : ViewModel() {
    // Debug logging switch: set to true only while developing.
    // In release builds keep it false so debug logs are effectively commented out.
    private companion object { const val DEBUG_LOGS = false }
    private val TAG = "FlashcardVM"
    private val _cards = MutableLiveData<List<Flashcard>>()
    val cards: LiveData<List<Flashcard>> = _cards

    private var cardOrder = mutableListOf<Flashcard>()
    private var currentIndex = 0
    var showSide1First = true
    enum class OrderMode { RANDOM, SEQUENTIAL }
    private var orderMode: OrderMode = OrderMode.RANDOM

    fun loadCards(learnedOnly: Boolean = false) {
        viewModelScope.launch {
            val list = if (learnedOnly) repository.getCardsByStatus(true) else repository.getAll()
            cardOrder = when (orderMode) {
                OrderMode.RANDOM -> list.shuffled().toMutableList()
                OrderMode.SEQUENTIAL -> list.toMutableList()
            }
            currentIndex = 0
            _cards.value = cardOrder
            // Debug-only logging; suppressed in release
            if (DEBUG_LOGS) Log.d(TAG, "loadCards(learnedOnly=$learnedOnly) size=${cardOrder.size}")
        }
    }

    fun loadUnlearned() {
        viewModelScope.launch {
            val list = repository.getCardsByStatus(false)
            cardOrder = when (orderMode) {
                OrderMode.RANDOM -> list.shuffled().toMutableList()
                OrderMode.SEQUENTIAL -> list.toMutableList()
            }
            currentIndex = 0
            _cards.value = cardOrder
            // Debug-only logging; suppressed in release
            if (DEBUG_LOGS) Log.d(TAG, "loadUnlearned size=${cardOrder.size}")
        }
    }
    fun getCurrentCard(): Flashcard? = cardOrder.getOrNull(currentIndex)
    fun nextCard(): Flashcard? {
        if (cardOrder.isEmpty()) {
            if (DEBUG_LOGS) Log.d(TAG, "nextCard -> no cards")
            return null
        }
        val lastIndex = cardOrder.size - 1
        if (currentIndex >= lastIndex) {
            // We are about to wrap
            when (orderMode) {
                OrderMode.RANDOM -> {
                    // reshuffle to avoid repeating a fixed order
                    cardOrder = cardOrder.shuffled().toMutableList()
                    if (DEBUG_LOGS) Log.d(TAG, "nextCard -> wrap & reshuffle, new order size=${cardOrder.size}")
                }
                OrderMode.SEQUENTIAL -> {
                    // restart from beginning keeping the same order
                    if (DEBUG_LOGS) Log.d(TAG, "nextCard -> wrap sequential, restart from 0")
                }
            }
            currentIndex = 0
        } else {
            currentIndex++
        }
        _cards.value = cardOrder
        if (DEBUG_LOGS) Log.d(TAG, "nextCard -> index=$currentIndex of ${cardOrder.size}")
        return getCurrentCard()
    }

    fun prevCard(): Flashcard? {
        if (cardOrder.isEmpty()) {
            if (DEBUG_LOGS) Log.d(TAG, "prevCard -> no cards")
            return null
        }
        // Move backwards; if we go before 0, wrap to last. We do not reshuffle here.
        currentIndex = if (currentIndex <= 0) cardOrder.size - 1 else currentIndex - 1
        _cards.value = cardOrder
        if (DEBUG_LOGS) Log.d(TAG, "prevCard -> index=$currentIndex of ${cardOrder.size}")
        return getCurrentCard()
    }
    fun markLearned(card: Flashcard) {
        viewModelScope.launch {
            repository.updateLearnedStatus(card.id, true)
            cardOrder.getOrNull(currentIndex)?.isLearned = true
            if (DEBUG_LOGS) Log.d(TAG, "markLearned id=${card.id}")
        }
    }
    fun markUnlearned(card: Flashcard) {
        viewModelScope.launch {
            repository.updateLearnedStatus(card.id, false)
            cardOrder.getOrNull(currentIndex)?.isLearned = false
            if (DEBUG_LOGS) Log.d(TAG, "markUnlearned id=${card.id}")
        }
    }
    fun toggleDirection() {
        showSide1First = !showSide1First
        _cards.value = cardOrder
        if (DEBUG_LOGS) Log.d(TAG, "toggleDirection -> showSide1First=$showSide1First")
    }

    fun deleteCurrentCard(onCompleted: (() -> Unit)? = null) {
        val card = getCurrentCard() ?: return
        viewModelScope.launch {
            repository.deleteById(card.id)
            // Remove from in-memory list and adjust index
            if (currentIndex in cardOrder.indices) {
                cardOrder.removeAt(currentIndex)
                if (cardOrder.isEmpty()) {
                    currentIndex = 0
                } else {
                    // keep index pointing to the next item in the cycle
                    if (currentIndex >= cardOrder.size) currentIndex = 0
                }
            } else {
                currentIndex = 0
            }
            _cards.value = cardOrder
            if (DEBUG_LOGS) Log.d(TAG, "deleteCurrentCard id=${card.id} remaining=${cardOrder.size}")
            onCompleted?.let { it() }
        }
    }

    // Debug helpers
    fun getCurrentIndex(): Int = currentIndex
    fun getTotalCount(): Int = cardOrder.size
    fun getCurrentCardId(): String? = getCurrentCard()?.id

    // Returns statistics via callback: learned, not learned, total.
    fun getStats(onResult: (learned: Int, notLearned: Int, total: Int) -> Unit) {
        viewModelScope.launch {
            // Repository methods already run on IO dispatcher
            val learnedCount = repository.getCardsByStatus(true).size
            val notLearnedCount = repository.getCardsByStatus(false).size
            val total = learnedCount + notLearnedCount
            if (DEBUG_LOGS) Log.d(TAG, "getStats -> learned=$learnedCount not=$notLearnedCount total=$total")
            onResult(learnedCount, notLearnedCount, total)
        }
    }

    // Order mode controls
    fun setOrderMode(mode: OrderMode) {
        if (orderMode == mode) return
        orderMode = mode
        // Rebuild current in-memory order according to new mode (without changing the dataset source).
        cardOrder = when (orderMode) {
            OrderMode.RANDOM -> cardOrder.shuffled().toMutableList()
            OrderMode.SEQUENTIAL -> cardOrder.toMutableList()
        }
        currentIndex = 0
        _cards.value = cardOrder
        if (DEBUG_LOGS) Log.d(TAG, "setOrderMode -> $orderMode")
    }
    fun toggleOrderMode() {
        setOrderMode(if (orderMode == OrderMode.RANDOM) OrderMode.SEQUENTIAL else OrderMode.RANDOM)
    }
    fun getOrderMode(): OrderMode = orderMode
}
