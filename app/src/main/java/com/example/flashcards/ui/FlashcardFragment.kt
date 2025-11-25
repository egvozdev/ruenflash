package com.example.flashcards.ui

import android.os.Bundle
import android.view.*
import android.util.Log
import com.example.flashcards.R
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.flashcards.databinding.FragmentFlashcardBinding
import com.example.flashcards.viewmodel.FlashcardViewModel
import com.example.flashcards.viewmodel.FlashcardViewModelFactory
import com.example.flashcards.repository.FlashcardRepository
import com.example.flashcards.data.FlashcardDatabase
import com.example.flashcards.util.importCsvText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.room.withTransaction

class FlashcardFragment : Fragment() {
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFlashcardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Debug logs are printed only when DEBUG_LOGS is enabled; silenced in release.
        if (DEBUG_LOGS) Log.d(TAG, "onViewCreated")
        // Initialize order mode from saved preference before observing/initial loads
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedOrder = prefs.getString("order_mode", "RANDOM")
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
        viewModel.cards.observe(viewLifecycleOwner) {
            if (DEBUG_LOGS) Log.d(TAG, "cards observer size=${it.size}")
            // New/reloaded list, reset state and show according to global preference
            clearHistory()
            showingSide1 = viewModel.showSide1First
            resetSeenForCurrentCard()
            showCurrentCard()
        }
        binding.btnLearned.setOnClickListener {
            if (DEBUG_LOGS) Log.d(TAG, "btnLearned click (short)")
            viewModel.getCurrentCard()?.let {
                // Toggle learned status depending on which set we're viewing
                if (currentFilter == Filter.LEARNED) {
                    viewModel.markUnlearned(it)
                } else {
                    // For UNLEARNED or ALL -> mark as learned
                    viewModel.markLearned(it)
                }
            }
            // When working under a filter (LEARNED or UNLEARNED), reload from DB to respect the filter
            // so the toggled card disappears from the current list. Otherwise just advance.
            if (currentFilter == Filter.LEARNED || currentFilter == Filter.UNLEARNED) {
                reloadAccordingToFilter()
            } else {
                viewModel.nextCard()
                showingSide1 = viewModel.showSide1First
                resetSeenForCurrentCard()
                showCurrentCard()
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

        binding.cardText.setOnTouchListener { v, event ->
            // Always feed events to the detector so long-press is recognized.
            longPressDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val width = v.width
                val x = event.x
                val leftThird = width / 3f
                val total = viewModel.getTotalCount()

                if (x <= leftThird) {
                    // Special rule: in RANDOM mode, while showing the first side,
                    // a left tap should advance to the next random card.
                    if (viewModel.getOrderMode() == com.example.flashcards.viewmodel.FlashcardViewModel.OrderMode.RANDOM && showingSide1) {
                        // Save current state for potential one-step back
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
                            seenSide1 = prev.seenSide1
                            seenSide2 = prev.seenSide2
                            showCurrentCard()
                            // do not overwrite history now
                            return@setOnTouchListener true
                        } else if (prev.index == prevIndexIfWrapped) {
                            // Restore previous card (one step back)
                            viewModel.prevCard()
                            showingSide1 = prev.showingSide1
                            seenSide1 = prev.seenSide1
                            seenSide2 = prev.seenSide2
                            showCurrentCard()
                            return@setOnTouchListener true
                        }
                    }
                    // Fallback if we don't have valid history: show first part of current if on second,
                    // otherwise go to previous card and start from configured order
                    if (!showingSide1) {
                        showingSide1 = true
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
                if (bothSeen) {
                    if (DEBUG_LOGS) Log.d(TAG, "both sides seen -> next card")
                    // Save state before changing
                    saveCurrentDisplayState()
                    viewModel.nextCard()
                    showingSide1 = viewModel.showSide1First
                    resetSeenForCurrentCard()
                    showCurrentCard()
                } else {
                    if (DEBUG_LOGS) Log.d(TAG, "flip side")
                    // Save state before changing
                    saveCurrentDisplayState()
                    showingSide1 = !showingSide1
                    showCurrentCard()
                }
                return@setOnTouchListener true
            }
            // For other touch events, consume to keep interaction consistent
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
        binding.cardText.text = when {
            card == null -> getString(R.string.empty_state)
            showingSide1 -> card.side1.joinToString("\n")
            else -> card.side2.joinToString("\n")
        }
        // Mark the side we just showed as seen
        if (card != null) {
            if (showingSide1) seenSide1 = true else seenSide2 = true
        }
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

    private fun downloadAndImport(link: String) {
        val ctx = requireContext().applicationContext
        val dao = FlashcardDatabase.getDatabase(ctx).flashcardDao()
        val urlToUse = convertDriveLinkToDirect(link)
        // Hide keyboard if visible
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } catch (_: Exception) {}

        binding.btnDownload.isEnabled = false
        showProgress(getString(R.string.downloading))

        lifecycleScope.launch {
            try {
                val text = withTimeout(60_000) { withContext(Dispatchers.IO) { downloadText(urlToUse) } }
                if (text.isBlank()) throw IllegalStateException("Empty CSV content")
                showProgress(getString(R.string.importing))
                // Perform bulk import within a single Room transaction to significantly
                // speed up many small writes and avoid timeouts on large datasets.
                val summary = withTimeout(120_000) {
                    withContext(Dispatchers.IO) {
                        val db = FlashcardDatabase.getDatabase(ctx)
                        db.withTransaction {
                            importCsvText(text, dao)
                        }
                    }
                }
                reloadAccordingToFilter()
                // Persist last import summary for statistics screen
                try {
                    val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("last_import_records", summary.records)
                        .putInt("last_import_inserted", summary.inserted)
                        .putInt("last_import_updated", summary.updated)
                        .apply()
                } catch (_: Exception) {}
                // Show concise import summary so the user can verify completeness quickly
                val msg = getString(R.string.toast_cards_updated) +
                        " (records=" + summary.records +
                        ", updated=" + summary.updated +
                        ", inserted=" + summary.inserted + ")"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download/import failed", e)
                Toast.makeText(requireContext(), getString(R.string.toast_failed_prefix) + ": ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                hideProgress()
                binding.btnDownload.isEnabled = true
            }
        }
    }

    private fun reloadAccordingToFilter() {
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
}
