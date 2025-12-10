package com.example.flashcards.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
// ... другие существующие импорты ...

// ДОБАВЬТЕ ЭТИ ИМПОРТЫ (если их нет):
import android.widget.ProgressBar

import kotlinx.coroutines.*
import java.net.URL
import androidx.appcompat.app.AlertDialog
import androidx.room.withTransaction
import com.example.flashcards.data.FlashcardDatabase
import com.example.flashcards.util.importCsvText


import android.view.*
import android.util.Log
import com.example.flashcards.R
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.flashcards.databinding.FragmentFlashcardBinding
import com.example.flashcards.viewmodel.FlashcardViewModel
import com.example.flashcards.viewmodel.FlashcardViewModelFactory
import com.example.flashcards.repository.FlashcardRepository

//import com.example.flashcards.util.importCsvText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
//import androidx.room.withTransaction
import android.widget.Button
import android.view.Gravity
import com.example.flashcards.data.CardSet
//mport androidx.lifecycle.lifecycleScope


class FlashcardFragment : Fragment() {
    private var lastRenderedSets: List<CardSet>? = null
    private var lastCardIndex: Int? = null

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentCardIdx(requireContext())
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveCurrentCardIdx(requireContext())
    }



//    binding.btnDeleteSet.setOnClickListener {
//        showDeleteCurrentSetDialog()
//    }

    // Debug logging switch: set to true only while developing.
    // In release builds keep it false so debug logs are effectively commented out.
    private companion object { const val DEBUG_LOGS = false }
    private var _binding: FragmentFlashcardBinding? = null
    private val binding get() = _binding!!
    private val viewModelFactory by lazy {
        val dao = FlashcardDatabase.getDatabase(requireContext().applicationContext).flashcardDao()
        FlashcardViewModelFactory(FlashcardRepository(dao))
    }
    private val viewModel: FlashcardViewModel by viewModels { viewModelFactory }
    private var showingSide1 = true
    private val TAG = "FlashcardFrag"
    // Track whether each side of the current card has been shown in this session
    private var seenSide1 = false
    private var seenSide2 = false
    // One-step back history of what was displayed last time (index + which side + seen flags)
    private data class DisplayState(
        val index: Int,
        val showingSide1: Boolean,
        val seenSide1: Boolean,
        val seenSide2: Boolean
    )
    private var lastState: DisplayState? = null

    private enum class Filter { ALL, LEARNED, UNLEARNED }
    private var currentFilter: Filter = Filter.ALL

    // Simple progress dialog shown during long operations (download/import)
    private var progressDialog: AlertDialog? = null
    private var isReloading = false

    private fun showDeleteCurrentSetDialog() {
        val ctx = requireContext()
        val activeId = viewModel.activeSetId.value ?: return

        AlertDialog.Builder(ctx)
            .setTitle("Удалить набор")
            .setMessage("Удалить текущий набор и все его карточки?")
            .setPositiveButton("Удалить") { d, _ ->
                d.dismiss()
                viewModel.deleteCurrentSet {
                    Toast.makeText(ctx, "Набор удалён", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .show()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFlashcardBinding.inflate(inflater, container, false)
        return binding.root
    }

    //override fun onPause() {
    //    super.onPause()
    //    viewModel.saveCurrentCardIdx(requireContext())
    //}
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)

        // Observer для cards - будет вызываться каждый раз когда cards обновляется
//        viewModel.cards.observe(viewLifecycleOwner) { cards ->
//            Log.d(TAG, "cards observer: ${cards.size} cards, isReloading=$isReloading")
//
//            if (cards.isNotEmpty()) {
//                val currentIndex = viewModel.getCurrentIndex()
//                Log.d(TAG, "  currentIndex=$currentIndex, lastCardIndex=$lastCardIndex")
//                if (!isReloading && currentIndex != lastCardIndex) {
//                    Log.d(TAG, "  → resetting seen flags")
//                    // Только если не вручную уже переключили карточку
//                    showingSide1 = viewModel.showSide1First
//                    resetSeenForCurrentCard()
//                    showCurrentCard()
//                    lastCardIndex = currentIndex
//                } else if (isReloading) {
//                    Log.d(TAG, "  → isReloading=true, skip reset")
//                    // Просто перерисовка после фильтра, флаги уже сброшены вручную
//                    lastCardIndex = currentIndex
//                } else {
//                    Log.d(TAG, "  → same card index, skip reset")
//                }
//                updateShowLearnedButtonText()
//            } else {
//                binding.cardText.text = "No cards available"
//            }
//
//            // Сбрасываем флаг после обработки
//            isReloading = false
//        }
        viewModel.cards.observe(viewLifecycleOwner) { cards ->
            Log.d(TAG, "cards observer: ${cards.size} cards, isReloading=$isReloading")

            if (cards.isNotEmpty()) {
                val currentIndex = viewModel.getCurrentIndex()

                if (!isReloading) {
                    // список обновился (например, при смене набора) → просто показать текущую
                    showingSide1 = viewModel.showSide1First
                    resetSeenForCurrentCard()
                    lastCardIndex = currentIndex
                    showCurrentCard()
                } else {
                    // перезагрузка после mark learned в фильтрах: индекс уже выбрал VM
                    lastCardIndex = currentIndex
                    showCurrentCard()
                }
                updateShowLearnedButtonText()
            } else {
                binding.cardText.text = getString(R.string.empty_state)
                lastCardIndex = null
            }

            isReloading = false
        }

        // Load card sets first
        viewModel.loadSets()

        // Восстановить позицию карточки
        viewModel.restoreCurrentCardIdx(requireContext())

        //viewModel.restoreCurrentCardIdx(requireContext())

        // Debug logs are printed only when DEBUG_LOGS is enabled; silenced in release.
        if (DEBUG_LOGS) Log.d(TAG, "onViewCreated")


        // Observe card sets
        viewModel.cardSets.observe(viewLifecycleOwner) { sets ->
            Log.d(TAG, "cardSets observer: ${sets.size} sets")
            updateSetsUI(sets)
        }


//        viewModel.activeSetId.observe(viewLifecycleOwner) { activeId ->
//            // Обновляем только прозрачность кнопок, не пересоздаём их
//            val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
//            if (container != null) {
//                for (i in 0 until container.childCount) {
//                    val button = container.getChildAt(i) as? Button
//                    if (button != null) {
//                        val setId = button.text.toString().toIntOrNull() ?: 0
//                        button.alpha = if (setId == activeId) 1.0f else 0.4f
//                    }
//                }
//            }
//        }

//        viewModel.activeSetId.observe(viewLifecycleOwner) { activeId ->
//            val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
//            if (container != null) {
//                for (i in 0 until container.childCount) {
//                    val button = container.getChildAt(i) as? Button ?: continue
//                    val setId = button.text.toString().toIntOrNull() ?: 0
//
//                    if (setId == activeId) {
//                        // Активный набор: яркий фон и белый текст
//                        button.setBackgroundColor(0xFF2196F3.toInt()) // синий
//                        button.setTextColor(0xFFFFFFFF.toInt())
//                    } else {
//                        // Неактивный: серый фон и тёмный текст
//                        button.setBackgroundColor(0xFFCCCCCC.toInt())
//                        button.setTextColor(0xFF000000.toInt())
//                    }
//                }
//            }
//        }

        viewModel.activeSetId.observe(viewLifecycleOwner) { activeId ->
            Log.d(TAG, "activeSetId observer: $activeId")
            val id = activeId ?: return@observe  // если null — выходим
            updateActiveSetHighlight(id)
        }


        // Add Set button
        requireView().findViewById<Button>(R.id.btnAddSet)?.setOnClickListener {
            showCreateSetDialog()
        }

        // Initialize order mode from saved preference before observing/initial loads
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedOrder = prefs.getString("order_mode", "SEQUENTIAL")
        val initialMode = if (savedOrder == "SEQUENTIAL")
            com.example.flashcards.viewmodel.FlashcardViewModel.OrderMode.SEQUENTIAL
        else com.example.flashcards.viewmodel.FlashcardViewModel.OrderMode.RANDOM

        viewModel.setOrderMode(initialMode)
        // Setup order toggle button
        binding.btnOrder.setOnClickListener {
            viewModel.toggleOrderMode()
            // Persist
            val mode = viewModel.getOrderMode()
            prefs.edit().putString("order_mode", if (mode == com.example.flashcards.viewmodel.FlashcardViewModel.OrderMode.SEQUENTIAL) "SEQUENTIAL" else "RANDOM").apply()
            // Update label and reload current filter
            updateOrderToggleLabel()
            reloadAccordingToFilter()
        }
        updateOrderToggleLabel()

//        viewModel.cards.observe(viewLifecycleOwner) {
//            if (DEBUG_LOGS) Log.d(TAG, "cards observer size=${it.size}")
//            if (!isReloading) {
//                clearHistory()
//                showingSide1 = viewModel.showSide1First
//                resetSeenForCurrentCard()
//                showCurrentCard()
//            }
//            //showCurrentCard()
//            isReloading = false
//        }

//        viewModel.cards.observe(viewLifecycleOwner) {
//            if (DEBUG_LOGS) Log.d(TAG, "cards observer size=${it.size}")
//            // New/reloaded list, reset state and show according to global preference
//            clearHistory()
//            showingSide1 = viewModel.showSide1First
//            resetSeenForCurrentCard()
//            showCurrentCard()
//        }
//        binding.btnLearned.setOnClickListener {
//            if (DEBUG_LOGS) Log.d(TAG, "btnLearned click (short)")
//            viewModel.getCurrentCard()?.let {
//                // Toggle learned status depending on which set we're viewing
//                if (currentFilter == Filter.LEARNED) {
//                    viewModel.markUnlearned(it)
//                } else {
//                    // For UNLEARNED or ALL -> mark as learned
//                    viewModel.markLearned(it)
//                }
//            }
//
//            // ВСЕГДА переходим на следующую карточку после пометки
//            viewModel.nextCard()
//           showingSide1 = viewModel.showSide1First
//            resetSeenForCurrentCard()
//            showCurrentCard()
//
//
//            // Если работаем под фильтром LEARNED или UNLEARNED — перезагружаем список,
//            // чтобы помеченная карточка исчезла из текущего набора
//            if (currentFilter == Filter.LEARNED || currentFilter == Filter.UNLEARNED) {
//                //isReloading = true
//                reloadAccordingToFilter()
//
//            } else {
//                // Для ALL фильтра — вручную переходим на следующую
//                viewModel.nextCard()
//                showingSide1 = viewModel.showSide1First
//                resetSeenForCurrentCard()
//                showCurrentCard()
//            }
//        }

//
//        binding.btnLearned.setOnClickListener {
//            if (DEBUG_LOGS) Log.d(TAG, "btnLearned click (short)")
//            val card = viewModel.getCurrentCard() ?: return@setOnClickListener
//
//            // Помечаем карточку
//            if (currentFilter == Filter.LEARNED) {
//                viewModel.markUnlearned(card)
//            } else {
//                viewModel.markLearned(card)
//            }
//
//            when (currentFilter) {
//                Filter.ALL -> {
//                    // В режиме ALL двигаем индекс вручную ОДИН раз
//                    viewModel.nextCard()
//                    showingSide1 = viewModel.showSide1First
//                    resetSeenForCurrentCard()
//                    lastState = null
//                    showCurrentCard()
//                }
//                Filter.UNLEARNED, Filter.LEARNED -> {
//                    // В режимах с фильтром НИГДЕ не вызываем nextCard!
//                    showingSide1 = viewModel.showSide1First
//                    isReloading = true
//                    lastState = null
//                    reloadAccordingToFilter()
//                }
//            }
//        }

        binding.btnLearned.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnLearned click (short)")
            val card = viewModel.getCurrentCard() ?: return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                // 1. Сначала помечаем и ЖДЁМ завершения
                if (currentFilter == Filter.LEARNED) {
                    viewModel.markUnlearned(card)
                } else {
                    viewModel.markLearned(card)
                }

                Log.d(TAG, "btnLearned: mark completed, now reloading")

                // 2. Только после этого перезагружаем
                when (currentFilter) {
                    Filter.ALL -> {
                        viewModel.nextCard()
                        showingSide1 = viewModel.showSide1First
                        resetSeenForCurrentCard()
                        lastState = null
                        showCurrentCard()
                    }
                    Filter.UNLEARNED, Filter.LEARNED -> {
                        showingSide1 = viewModel.showSide1First
                        isReloading = true
                        lastState = null
                        reloadAccordingToFilter()
                    }
                }
            }
        }


        binding.btnLearned.setOnLongClickListener {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.delete_title))
                .setMessage(getString(R.string.delete_message))
                .setPositiveButton(getString(R.string.btn_delete)) { d, _ ->
                    d.dismiss()
                    viewModel.deleteCurrentCard {
                        // After deletion, advance is handled by VM list update; just reset and show
                        showingSide1 = viewModel.showSide1First
                        resetSeenForCurrentCard()
                        showCurrentCard()
                        Toast.makeText(ctx, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
                .show()
            true
        }
        binding.btnSwitchSide.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnSwitchSide click")
            viewModel.toggleDirection()
            clearHistory()
            showingSide1 = viewModel.showSide1First
            resetSeenForCurrentCard()
            showCurrentCard()
        }
        // Use a GestureDetector to reliably detect long presses without
        // interfering with our custom tap logic handled in onTouch.
        val longPressDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // Toggle debug overlay strictly on long press only
                val dv = binding.debugInfo
                dv.visibility = if (dv.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateDebugInfo()
            }
        })
        // Prevent platform default long-click handling to avoid accidental toggles.
        binding.cardText.isLongClickable = false

//

        binding.cardText.setOnTouchListener { v, event ->
            longPressDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                Log.d(TAG, "tap: seenSide1=$seenSide1, seenSide2=$seenSide2, showingSide1=$showingSide1")
                val width = v.width
                val x = event.x
                val leftThird = width / 3f
                val total = viewModel.getTotalCount()

                if (x <= leftThird) {

                    if (viewModel.getOrderMode() == FlashcardViewModel.OrderMode.SEQUENTIAL) {
                        val startSide = viewModel.showSide1First

                        if (showingSide1 != startSide) {
                            showingSide1 = startSide
                            seenSide1 = false
                            seenSide2 = false
                            showCurrentCard()
                            return@setOnTouchListener true
                        }

                        if (total > 0) {
                            viewModel.prevCard()
                            showingSide1 = viewModel.showSide1First
                            resetSeenForCurrentCard()
                            showCurrentCard()
                        }
                        return@setOnTouchListener true
                    }

                    if (viewModel.getOrderMode() == FlashcardViewModel.OrderMode.RANDOM && showingSide1) {
                        saveCurrentDisplayState()
                        viewModel.nextCard()
                        showingSide1 = viewModel.showSide1First
                        resetSeenForCurrentCard()
                        showCurrentCard()
                        return@setOnTouchListener true
                    }

                    // Go back to previously displayed part (works in both modes)
                    val prev = lastState
                    if (prev != null && total > 0) {
                        val curIndex = viewModel.getCurrentIndex()
                        val prevIndexIfWrapped = if (curIndex - 1 < 0) total - 1 else curIndex - 1

                        if (prev.index == curIndex) {
                            // Restore same card previous side
                            showingSide1 = prev.showingSide1
                            seenSide1 = false          // ← ИЗМЕНЕНО
                            seenSide2 = false          // ← ИЗМЕНЕНО
                            showCurrentCard()
                            return@setOnTouchListener true
                        } else if (prev.index == prevIndexIfWrapped) {
                            // Restore previous card (one step back)
                            viewModel.prevCard()
                            showingSide1 = prev.showingSide1
                            seenSide1 = false          // ← ИЗМЕНЕНО
                            seenSide2 = false          // ← ИЗМЕНЕНО
                            showCurrentCard()
                            return@setOnTouchListener true
                        }
                    }

                    // Fallback
                    if (!showingSide1) {
                        showingSide1 = true
                        seenSide1 = false              // ← ДОБАВЛЕНО
                        seenSide2 = false              // ← ДОБАВЛЕНО
                        showCurrentCard()
                    } else if (total > 0) {
                        viewModel.prevCard()
                        showingSide1 = viewModel.showSide1First
                        resetSeenForCurrentCard()
                        showCurrentCard()
                    }
                    return@setOnTouchListener true
                }

                // Default behaviour (tap elsewhere): flip or advance if both sides already seen
                val bothSeen = seenSide1 && seenSide2
                Log.d(TAG, "tap center/right: bothSeen=$bothSeen")

                if (bothSeen) {
                    Log.d(TAG, "both sides seen -> next card")
                    saveCurrentDisplayState()
                    viewModel.nextCard()
                    showingSide1 = viewModel.showSide1First
                    resetSeenForCurrentCard()
                    showCurrentCard()
                } else {
                    Log.d(TAG, "flip side: showingSide1 was $showingSide1, now ${!showingSide1}")
                    saveCurrentDisplayState()
                    showingSide1 = !showingSide1
                    showCurrentCard()
                }
                return@setOnTouchListener true
            }
            true
        }


        // Remove separate long-click listener to avoid duplicate/accidental triggers; handled by GestureDetector above.
        binding.btnShowLearned.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnShowLearned click (toggle)")
            if (currentFilter == Filter.LEARNED) {
                // Currently showing learned -> switch to unlearned
                viewModel.loadUnlearned()
                currentFilter = Filter.UNLEARNED
            } else {
                // From ALL or UNLEARNED -> switch to learned
                viewModel.loadCards(learnedOnly = true)
                currentFilter = Filter.LEARNED
            }
            updateShowLearnedButtonText()
        }
        // Removed "Show all / Show not learnt" button per new requirements
        binding.btnDownload.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnDownload click")
            showDownloadDialog()
        }
        // Top bar actions
        binding.btnExit.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnExit click")
            requireActivity().finish()
        }
        binding.btnStats.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnStats click")
            viewModel.getStats { learned, notLearned, total ->
                val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                val lastRecords = prefs.getInt("last_import_records", 0)
                val lastInserted = prefs.getInt("last_import_inserted", 0)
                val lastUpdated = prefs.getInt("last_import_updated", 0)

                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.stats_title))
                    .setMessage(
                        getString(R.string.stats_learned, learned) + "\n" +
                        getString(R.string.stats_not_learned, notLearned) + "\n" +
                        getString(R.string.stats_total, total) + "\n\n" +
                        getString(R.string.stats_last_download_title) + "\n" +
                        getString(R.string.stats_words_processed, lastRecords) + "\n" +
                        getString(R.string.stats_added, lastInserted) + "\n" +
                        getString(R.string.stats_updated, lastUpdated)
                    )
                    .setPositiveButton(getString(R.string.btn_ok)) { d, _ -> d.dismiss() }
                    .show()
            }
        }
        binding.btnHelp.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnHelp click")
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.help_title))
                .setMessage(getString(R.string.help_message))
                .setPositiveButton(getString(R.string.btn_ok)) { d, _ -> d.dismiss() }
                .show()
        }
        // Language toggle: shows target language code to switch to
        binding.btnLang.setOnClickListener {
            toggleLanguage()
        }
        ensureInitialLanguage()
        updateLanguageToggleLabel()
        // Initial state: show NOT learned cards (uses current order mode)
        viewModel.loadUnlearned()
        currentFilter = Filter.UNLEARNED
        updateShowLearnedButtonText()
    }

    private fun showCurrentCard() {
        val card = viewModel.getCurrentCard()
        Log.d(TAG, "showCurrentCard: card=$card")
        Log.d(TAG, "  id=${card?.id}, side1='${card?.side1}', side2='${card?.side2}'")
        binding.cardText.text = when {
            card == null -> getString(R.string.empty_state)
            showingSide1 -> card.side1.joinToString("\n")
            else -> card.side2.joinToString("\n")
        }
        // Mark the side we just showed as seen
        if (card != null) {
            if (showingSide1) seenSide1 = true else seenSide2 = true
        }
        //val text = if (showingSide1) card.side1 else card.side2
        //Log.d(TAG, "  Displaying: showingSide1=$showingSide1, text='$text'")
        updateDebugInfo()
    }

    private fun updateDebugInfo() {
        val dv = binding.debugInfo
        if (dv.visibility != View.VISIBLE) return
        val id = viewModel.getCurrentCardId() ?: "<none>"
        val learned = viewModel.getCurrentCard()?.isLearned ?: false
        dv.text = "index=${viewModel.getCurrentIndex()} total=${viewModel.getTotalCount()} showSide1First=${viewModel.showSide1First} showingSide1=$showingSide1 seen1=$seenSide1 seen2=$seenSide2 id=$id learned=$learned"
    }

    private fun resetSeenForCurrentCard() {
        seenSide1 = false
        seenSide2 = false
    }

    private fun saveCurrentDisplayState() {
        lastState = DisplayState(
            index = viewModel.getCurrentIndex(),
            showingSide1 = showingSide1,
            seenSide1 = seenSide1,
            seenSide2 = seenSide2
        )
    }

    private fun clearHistory() {
        lastState = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun showDownloadDialog() {
        val ctx = requireContext()
        val input = EditText(ctx)
        input.hint = getString(R.string.download_hint)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.download_title))
            .setMessage(getString(R.string.download_message))
            .setView(input)
            .setPositiveButton(getString(R.string.download_go)) { d, _ ->
                val link = input.text?.toString()?.trim().orEmpty()
                if (link.isEmpty()) {
                    Toast.makeText(ctx, getString(R.string.toast_link_empty), Toast.LENGTH_SHORT).show()
                } else {
                    downloadAndImport(link)
                }
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .show()
    }


    private fun downloadAndImport(url: String) {

        val urlToUse = convertDriveLinkToDirect(url)
        //val downloadUrl = convertGoogleDriveUrl(url) // Добавить эту строку
        val progressBar = view?.findViewById<ProgressBar>(R.id.progressBar)
        progressBar?.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Загрузить CSV из URL
                val csvText = withContext(Dispatchers.IO) {
                    try {
                        val connection = URL(urlToUse).openConnection()
                        connection.connectTimeout = 30000
                        connection.readTimeout = 30000
                        connection.getInputStream().bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        Log.e("FlashcardFragment", "Download error", e)
                        throw e
                    }
                }

                // 2. Импортировать в БД (в активный набор)
                val summary = withTimeout(120_000) {
                    withContext(Dispatchers.IO) {
                        val ctx = requireContext()
                        val db = FlashcardDatabase.getDatabase(ctx)
                        val dao = db.flashcardDao()
                        val activeSetId = viewModel.activeSetId.value ?: 1

                        db.withTransaction {
                            importCsvText(csvText, dao, activeSetId)
                        }
                    }
                }

                // 3. Показать результат
                progressBar?.visibility = View.GONE

                val message = buildString {
                    append("Imported successfully!\n")
                    append("Records: ${summary.records}\n")
                    append("New: ${summary.inserted}\n")
                    append("Updated: ${summary.updated}")
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Import Complete")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()

                if (DEBUG_LOGS) {
                    Log.d(TAG, "Import summary: records=${summary.records}, inserted=${summary.inserted}, updated=${summary.updated}")
                }

                // 4. Перезагрузить карточки и показать первую
                Log.d(TAG, "Before loadUnlearned")
                viewModel.loadUnlearned()
                //delay(100)
                //Log.d(TAG, "After loadUnlearned, cards=${viewModel.cards.value?.size}")

                //showingSide1 = viewModel.showSide1First
                //resetSeenForCurrentCard()
                //showCurrentCard()
                //Log.d(TAG, "After showCurrentCard")


            } catch (e: TimeoutCancellationException) {
                progressBar?.visibility = View.GONE
                AlertDialog.Builder(requireContext())
                    .setTitle("Import Timeout")
                    .setMessage("Import took too long. The file may be too large.")
                    .setPositiveButton("OK", null)
                    .show()
                Log.e(TAG, "Import timeout", e)

            } catch (e: Exception) {
                progressBar?.visibility = View.GONE
                AlertDialog.Builder(requireContext())
                    .setTitle("Import Error")
                    .setMessage("Failed to import: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
                Log.e(TAG, "Import error", e)
            }
        }
    }



    private fun reloadAccordingToFilter() {
        //isReloading = true
        when (currentFilter) {
            Filter.ALL -> viewModel.loadCards(learnedOnly = false)
            Filter.LEARNED -> viewModel.loadCards(learnedOnly = true)
            Filter.UNLEARNED -> viewModel.loadUnlearned()
        }
    }

    private fun convertDriveLinkToDirect(link: String): String {
        // Supports common Google Drive and Google Sheets links by converting them to direct CSV downloads.
        // Drive file links:
        //   https://drive.google.com/file/d/{id}/view?... -> https://drive.google.com/uc?export=download&id={id}
        //   https://drive.google.com/open?id={id}        -> https://drive.google.com/uc?export=download&id={id}
        // Google Sheets links:
        //   https://docs.google.com/spreadsheets/d/{id}/edit#gid=0 -> https://docs.google.com/spreadsheets/d/{id}/export?format=csv&gid=0
        // If already a direct/export URL, return as-is.
        return try {
            val lower = link.lowercase()

            // Sheets: /spreadsheets/d/{id}/...
            if (lower.contains("docs.google.com/spreadsheets/")) {
                // Matches optional "/u/{num}/" segment that Google sometimes inserts
                val id = Regex("spreadsheets/(?:u/\\d+/)?d/([a-zA-Z0-9_-]+)").find(link)?.groupValues?.getOrNull(1)
                val gid = Regex("[?#&]gid=([0-9]+)").find(link)?.groupValues?.getOrNull(1)
                if (id != null) {
                    return buildString {
                        append("https://docs.google.com/spreadsheets/d/")
                        append(id)
                        append("/export?format=csv")
                        if (!gid.isNullOrBlank()) {
                            append("&gid=")
                            append(gid)
                        }
                    }
                }
            }

            // Drive file: /file/d/{id}/...
            val fileRegex = Regex("drive.google.com/file/d/([a-zA-Z0-9_-]+)/")
            val fileMatch = fileRegex.find(link)
            if (fileMatch != null) {
                val id = fileMatch.groupValues[1]
                return "https://drive.google.com/uc?export=download&id=$id"
            }

            // Drive file: open?id={id}
            if (lower.contains("drive.google.com") && lower.contains("id=")) {
                val id = Regex("[?&]id=([^&]+)").find(link)?.groupValues?.getOrNull(1)
                if (id != null) return "https://drive.google.com/uc?export=download&id=$id"
            }

            // Already a direct/export URL or unknown -> return original
            link
        } catch (_: Exception) {
            link
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun downloadText(urlStr: String): String {
        return withContext(Dispatchers.IO) {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FlashcardsApp/1.0 (Android)")
                setRequestProperty("Accept", "text/csv, text/plain, */*")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    throw IllegalStateException("HTTP $code${if (err.isNullOrBlank()) "" else ": $err"}")
                }
                val contentType = conn.contentType ?: ""
                val body = conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins)).use { br ->
                        br.readText()
                    }
                }
                // If Drive/Sheets returned an HTML page instead of CSV, hint about permissions/export
                if (contentType.contains("text/html", true) || body.startsWith("<!DOCTYPE", true)) {
                    throw IllegalStateException("Received HTML instead of CSV. Ensure the link is public. For Google Sheets paste the normal sheet link; the app will auto-convert to CSV export, but access must be 'Anyone with the link'.")
                }
                body
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun showProgress(message: String) {
        val current = progressDialog
        if (current != null && current.isShowing) {
            current.setMessage(message)
            return
        }
        val ctx = requireContext()
        val pb = ProgressBar(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 16)
            addView(pb)
        }
        progressDialog = AlertDialog.Builder(ctx)
            .setTitle(message)
            .setView(container)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun updateShowLearnedButtonText() {
        // When showing learned, offer to show unlearned; otherwise offer to show learned
        val text = when (currentFilter) {
            Filter.LEARNED -> getString(R.string.show_not_learned)
            else -> getString(R.string.show_learned)
        }
        binding.btnShowLearned.text = text
    }

    private fun ensureInitialLanguage() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("lang", null)
        val target = saved ?: "en" // default English
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current != target) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(target))
        }
    }

    private fun toggleLanguage() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "en" }
        val next = if (current.startsWith("ru")) "en" else "ru"
        prefs.edit().putString("lang", next).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        // Recreate to apply strings
        requireActivity().recreate()
    }

    private fun updateLanguageToggleLabel() {
        // Show the CURRENT language code (EN or RU) similar to RND/SEQ toggle behavior
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "en" }
        val currentCode = if (current.startsWith("ru")) "RU" else "EN"
        binding.btnLang.text = currentCode
    }

    private fun updateOrderToggleLabel() {
        val mode = viewModel.getOrderMode()
        binding.btnOrder.text = if (mode == com.example.flashcards.viewmodel.FlashcardViewModel.OrderMode.SEQUENTIAL) "SEQ" else "RND"
    }


    private fun updateSetsUI(sets: List<CardSet>) {
        if (lastRenderedSets == sets) {
            Log.d(TAG, "updateSetsUI: sets unchanged, skipping")
            return
        }
        lastRenderedSets = sets

        val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
        Log.d(TAG, "updateSetsUI: ${sets.size} sets, container has ${container?.childCount} children")

        container?.removeAllViews()

        sets.forEach { set ->
            Log.d(TAG, "  Adding button for set: id=${set.id}, name=${set.name}")
            val button = Button(requireContext()).apply {
                text = set.name
                tag = set.id                    // ← сохраняем id набора
                layoutParams = LinearLayout.LayoutParams(
                    120, 120
                ).apply { marginEnd = 16 }
                textSize = 16f
                gravity = Gravity.CENTER

                setOnClickListener {
                    viewModel.switchToSet(set.id)
                }
                setOnLongClickListener {
                    showDeleteSetDialog(set)
                    true
                }
            }
            container?.addView(button)
        }

        // После создания сразу один раз обновим подсветку по текущему activeSetId
        viewModel.activeSetId.value?.let { id ->
            updateActiveSetHighlight(id)
        }
    }

    private fun updateActiveSetHighlight(activeId: Int) {
        val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
        if (container != null) {
            for (i in 0 until container.childCount) {
                val button = container.getChildAt(i) as? Button ?: continue
                val setId = button.tag as? Int ?: 0

                if (setId == activeId) {
                    button.setBackgroundColor(0xFF2196F3.toInt()) // активный
                    button.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    button.setBackgroundColor(0xFFCCCCCC.toInt()) // неактивный
                    button.setTextColor(0xFF000000.toInt())
                }
            }
        }
    }


//    private fun updateSetsUI(sets: List<CardSet>) {
//        // Если список наборов не изменился - не перерисовывать
//        if (lastRenderedSets == sets) {
//            Log.d(TAG, "updateSetsUI: sets unchanged, skipping")
//            return
//        }
//        lastRenderedSets = sets
//
//        val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
//        Log.d(TAG, "updateSetsUI: ${sets.size} sets, container has ${container?.childCount} children")
//
//        container?.removeAllViews()
//
//        val activeSetId = viewModel.activeSetId.value ?: 1
//
//        sets.forEach { set ->
//            Log.d(TAG, "  Adding button for set: id=${set.id}, name=${set.name}")
//            val button = Button(requireContext()).apply {
//                text = set.name
//                layoutParams = LinearLayout.LayoutParams(
//                    120, 120
//                ).apply {
//                    marginEnd = 16
//                }
//                alpha = if (set.id == activeSetId) 1.0f else 0.4f
//                textSize = 16f
//                gravity = Gravity.CENTER
//
//                setOnClickListener {
//                    viewModel.switchToSet(set.id)
//                }
//
//                setOnLongClickListener {
//                    showDeleteSetDialog(set)
//                    true
//                }
//            }
//            container?.addView(button)
//        }
//    }

//    private fun updateSetsUI(sets: List<CardSet>) {
//        val activeSetId = viewModel.activeSetId.value ?: 1  // ДОБАВЬТЕ эту строку!
//        // val container = binding.setsContainer
//        val container = requireView().findViewById<LinearLayout>(R.id.setsContainer)
//        Log.d(TAG, "updateSetsUI: ${sets.size} sets, container has ${container?.childCount} children")
////        val container = binding.setsContainer
//        container.removeAllViews()
//
//     //   val activeSetId = viewModel.activeSetId.value ?: 1
//     //   db.withTransaction {
//     //       importCsvText(text, dao, activeSetId)
//     //   }
//
//
//        sets.forEach { set ->
//            Log.d(TAG, "  Adding button for set: id=${set.id}, name=${set.name}")
//            val button = Button(requireContext()).apply {
//                text = set.id.toString()
//                val size = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) // или 48dp
//                layoutParams = LinearLayout.LayoutParams(size, size).apply {
//                    marginEnd = 16
//                }
//                alpha = if (set.id == activeSetId) 1.0f else 0.4f
//                textSize = 16f
//                gravity = Gravity.CENTER
//                setOnClickListener {
//                    viewModel.switchToSet(set.id)
//                }
//                setOnLongClickListener {
//                    showDeleteSetDialog(set)
//                    true
//                }
//            }
//            container.addView(button)
//        }
//    }

    private fun showCreateSetDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Set name (optional)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Create new card set")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createNewSet(name)
                } else {
                    val nextNumber = (viewModel.cardSets.value?.size ?: 0) + 1
                    viewModel.createNewSet("Set $nextNumber")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSetDialog(set: CardSet) {
        val ctx = requireContext()

        if (set.id == 1) {
            // Для набора 1 — только очистка карточек
            AlertDialog.Builder(ctx)
                .setTitle("Очистить набор")
                .setMessage("Удалить все карточки из набора \"${set.name}\"? Сам набор останется.")
                .setPositiveButton("Очистить") { d, _ ->
                    d.dismiss()
                    viewModel.deleteSet(set.id)
                    Toast.makeText(ctx, "Карточки удалены", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
                .show()
        } else {
            // Для остальных — полное удаление
            AlertDialog.Builder(ctx)
                .setTitle("Удалить набор")
                .setMessage("Удалить набор \"${set.name}\" и все его карточки?")
                .setPositiveButton("Удалить") { d, _ ->
                    d.dismiss()
                    viewModel.deleteSet(set.id)
                    Toast.makeText(ctx, "Набор удалён", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
                .show()
        }
    }


}
