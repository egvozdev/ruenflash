package com.example.flashcards.viewmodel

import android.util.Log
import androidx.lifecycle.*
import com.example.flashcards.data.Flashcard
import com.example.flashcards.repository.FlashcardRepository
import kotlinx.coroutines.launch
import android.content.Context
import com.example.flashcards.data.CardSet

class FlashcardViewModel(private val repository: FlashcardRepository) : ViewModel() {
    // Debug logging switch: set to true only while developing.
    // In release builds keep it false so debug logs are effectively commented out.
    private companion object { const val DEBUG_LOGS = false }
    private val TAG = "FlashcardVM"
    private val _cards = MutableLiveData<List<Flashcard>>()
    val cards: LiveData<List<Flashcard>> = _cards

    private val _cardSets = MutableLiveData<List<CardSet>>()
    val cardSets: LiveData<List<CardSet>> = _cardSets

    private val _activeSetId = MutableLiveData<Int?>()  // ← добавь ?
    val activeSetId: LiveData<Int?> get() = _activeSetId
    //private val _activeSetId = MutableLiveData<Int>(1)
    //val activeSetId: LiveData<Int> = _activeSetId


    private var cardOrder = mutableListOf<Flashcard>()
    private var currentIndex = 0
    var showSide1First = true
    enum class OrderMode { RANDOM, SEQUENTIAL }
    private var orderMode: OrderMode = OrderMode.RANDOM
    fun deleteSet(setId: Int) {
        viewModelScope.launch {
            if (setId == 1) {
                // Набор 1 нельзя удалить, только очистить карточки
                repository.clearSetCards(setId)
                loadUnlearned()
            } else {
                // Остальные наборы удаляются полностью
                repository.deleteSetWithCards(setId)
                loadSets()

                // Переключиться на первый оставшийся набор
                val newActive = _cardSets.value?.firstOrNull()?.id ?: 1
                _activeSetId.value = newActive
                loadUnlearned()
            }
        }
    }

        // Card Sets operations
        fun loadSets() {
            viewModelScope.launch {
                val sets = repository.getAllSets()

                // Если наборов нет — создать первый
                if (sets.isEmpty()) {
                    repository.createSet("1")
                    repository.setActiveSet(1)
                    _cardSets.value = repository.getAllSets()
                    _activeSetId.value = 1
                } else {
                    _cardSets.value = sets
                    val active = repository.getActiveSet()
                    _activeSetId.value = active?.id ?: sets.firstOrNull()?.id ?: 1
                }

                if (DEBUG_LOGS) Log.d(
                    TAG,
                    "loadSets: ${_cardSets.value?.size}, active=${_activeSetId.value}"
                )
            }
        }

        fun createNewSet(name: String) {
                viewModelScope.launch {
                        val newId = repository.createSet(name).toInt()
                        loadSets()
                        if (DEBUG_LOGS) Log.d(TAG, "createNewSet: $name, id=$newId")
                    }
            }

       fun switchToSet(setId: Int) {
                viewModelScope.launch {
                        repository.setActiveSet(setId)
                        _activeSetId.value = setId
                        // Reload cards for new set
                        loadUnlearned()
                        if (DEBUG_LOGS) Log.d(TAG, "switchToSet: $setId")
                    }
            }

        // Existing methods - save/restore position

    fun deleteCurrentSet(onDone: () -> Unit = {}) {
        val setId = _activeSetId.value ?: return
        viewModelScope.launch {
            repository.deleteSetWithCards(setId)
            // после удаления переключиться на первый оставшийся набор
            loadSets()
            val newActive = _cardSets.value?.firstOrNull()?.id
            _activeSetId.value = newActive
            if (newActive != null) {
                loadUnlearned()
            } else {
                _cards.value = emptyList()
            }
            onDone()
        }
    }

    // Сохранение индекса текущей карточки при выходе
    fun saveCurrentCardIdx(context: Context) {
        val prefs = context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("last_card_idx", currentIndex).apply()
    }

    // Восстановление индекса при входе
    fun restoreCurrentCardIdx(context: Context) {
        val prefs = context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)
        currentIndex = prefs.getInt("last_card_idx", 0)
    }

//    fun saveCurrentCardIdx(context: Context) {
//        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
//        prefs.edit().putInt("last_card_idx", currentCardIndex).apply()
//    }
//
//    fun restoreCurrentCardIdx(context: Context) {
//        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
//        currentCardIndex = prefs.getInt("last_card_idx", 0)
//    }

    fun loadCards(learnedOnly: Boolean = false) {
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            val list = if (learnedOnly) repository.getCardsByStatus(true, setId) else repository.getAll(setId)
            cardOrder = when (orderMode) {
                OrderMode.RANDOM -> list.shuffled().toMutableList()
                OrderMode.SEQUENTIAL -> list.toMutableList()
            }
            //currentIndex = 0
            if (currentIndex >= cardOrder.size) currentIndex = 0
            _cards.value = cardOrder
            // Debug-only logging; suppressed in release
            if (DEBUG_LOGS) Log.d(TAG, "loadCards(learnedOnly=$learnedOnly) size=${cardOrder.size}")
        }
    }

    fun loadUnlearned() {
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            val list = repository.getCardsByStatus(false, setId)
            Log.d(TAG, "loadUnlearned: setId=$setId, found ${list.size} cards")
            cardOrder = when (orderMode) {
                OrderMode.RANDOM -> list.shuffled().toMutableList()
                OrderMode.SEQUENTIAL -> list.toMutableList()
            }
            //currentIndex = 0
            if (currentIndex >= cardOrder.size) currentIndex = 0
            _cards.value = cardOrder
            // Debug-only logging; suppressed in release
            if (DEBUG_LOGS) Log.d(TAG, "loadUnlearned size=${cardOrder.size}")
        }
    }
    fun getCurrentCard(): Flashcard? = cardOrder.getOrNull(currentIndex)
    fun nextCard(): Flashcard? {
        Log.d(TAG, "nextCard -> index=$currentIndex of ${cardOrder.size}")
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
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            repository.updateLearnedStatus(card.id, true, setId)
            cardOrder.getOrNull(currentIndex)?.isLearned = true
            if (DEBUG_LOGS) Log.d(TAG, "markLearned id=${card.id}")
        }
    }
    fun markUnlearned(card: Flashcard) {
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            repository.updateLearnedStatus(card.id, false, setId)
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
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            repository.deleteById(card.id, setId)
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
        val setId = _activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку
        viewModelScope.launch {
            // Repository methods already run on IO dispatcher
            val learnedCount = repository.getCardsByStatus(true,setId).size
            val notLearnedCount = repository.getCardsByStatus(false, setId).size
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
