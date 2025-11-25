package com.example.flashcards.ui

import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.flashcards.R

// Debug logging switch: set to true only while developing.
// In release builds keep it false so debug logs are effectively commented out.
private const val DEBUG_LOGS = false

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostic log to help identify "Waiting for debugger" situations seen in Logcat
        val dbgConnected = Debug.isDebuggerConnected()
        val dbgWaiting = Debug.waitingForDebugger()
        // Only print debug info when DEBUG_LOGS is enabled. In release it's disabled.
        if (DEBUG_LOGS) {
            Log.d(
                "MainActivity",
                "onCreate: isDebuggerConnected=$dbgConnected waitingForDebugger=$dbgWaiting thread=${Thread.currentThread().name}"
            )
        }
        setContentView(R.layout.activity_main)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, FlashcardFragment())
            .commit()
    }
}
