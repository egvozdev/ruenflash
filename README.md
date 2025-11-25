# Flashcards (Карточки слов)

A minimal flashcards app built with Kotlin, AndroidX, and Room. It lets you flip a card, mark it learned, switch the display direction, and filter learned vs all cards. A small set of sample cards is prepopulated on first launch so you immediately see content.

## Prerequisites
- Android Studio (Giraffe/Koala or newer)
- Android SDK Platform 34
- JDK 17 (project is configured for Java/Kotlin 17)

Project root: `C:\evg\java\flashc`

## Build and Run (Android Studio)
1. Open Android Studio → File → Open… → select `C:\\evg\\java\\flashc`.
2. Wait for Gradle sync to finish. Build Variant should be `Debug` (default).
3. Start a device (AVD emulator or BlueStacks) or connect a physical device with USB debugging enabled.
4. Click Run ▶ (or Shift+F10). The app will install and start.

## Build APK (Debug) and Install Manually
- Build APK: Build → Build Bundle(s)/APK(s) → Build APK(s).
- The Debug APK will be at:
  - `app\\build\\outputs\\apk\\debug\\app-debug.apk`

Install options:
- Drag and drop `app-debug.apk` onto your emulator window, or
- Use ADB from a terminal:
  - `adb devices` (ensure your device/emulator is listed)
  - `adb install -r C:\\evg\\java\\flashc\\app\\build\\outputs\\apk\\debug\\app-debug.apk`
  - If you get a signature/version conflict: `adb uninstall com.example.flashcards` then install again.

## Debugging in Android Studio (attach to a running app)
1. Install and start the app using the APK above (or run from Studio).
2. In Android Studio: Run → Attach debugger to Android process → select your device and `com.example.flashcards`.
3. Set breakpoints in Kotlin files (Fragment/ViewModel/etc.). They will hit if sources match the installed app (same commit) and build is Debug.

## In‑app debug overlay
- On the main screen, long‑press the big card text area to toggle a debug overlay.
- It shows: current index, total cards, which side should be shown first, which side is currently shown, card id, and learned status.

## App usage
- Tap the big text to flip sides.
- “Следующая” (Next): goes to the next card.
- “Изучено” (Learned): marks the current card as learned and advances.
- “Сменить порядок” (Switch direction): toggles which side is shown first globally.
- “Показать изученные” (Show learned): loads only learned cards.
- “Показать все” (Show all): loads all cards.

Empty state: If there are no cards to show, you will see “Карточки кончились!”.

## Data and persistence
- Room database file name: `flashcard_db`.
- Entity: `Flashcard` (id, side1: List<String>, side2: List<String>, isLearned: Boolean).
- On first open (and only if DB is empty), 3 sample cards are inserted.

## CSV import (utility)
A helper is available at `app/src/main/java/com/example/flashcards/util/CsvImportUtil.kt` with `importCsvLines(...)` that can import cards given CSV lines where the header marks which columns belong to side1 or side2 (header values `1` and `2`). This is a utility function—there is no UI wired to it yet.

Example CSV format:
```
id,1,2
hello,Hello,Привет
thanks,Thank you,Спасибо
```
To use it, call from a coroutine with a `FlashcardDao` instance, e.g. in a one‑off dev task or future import screen.

## Troubleshooting
- White screen on launch:
  - Open Logcat (Android Studio → View → Tool Windows → Logcat). Filter by process `com.example.flashcards` and level `Error` or `Debug`.
  - Ensure the ViewModel factory wiring is present (already fixed in this repo via `FlashcardViewModelFactory`).
  - Try uninstalling and reinstalling the app if DB/schema got corrupted.
- Breakpoints don’t hit:
  - Confirm you’re attaching to the correct process and it’s a Debug build.
  - Ensure the sources in Android Studio match the APK you installed (same commit).
- ADB install errors:
  - `INSTALL_FAILED_VERSION_DOWNGRADE` or signature conflict → `adb uninstall com.example.flashcards`, then install again.
- BlueStacks not detected:
  - Enable ADB in BlueStacks settings, then `adb connect 127.0.0.1:5555` (or the port shown in settings).

## Project structure (high‑level)
- `ui/` — `MainActivity`, `FlashcardFragment` (UI and interactions)
- `viewmodel/` — `FlashcardViewModel`, `FlashcardViewModelFactory`
- `data/` — Room entities, DAO, database, type converters
- `repository/` — Data access abstraction over `FlashcardDao`
- `util/` — CSV import helper
- `res/layout/` — `activity_main.xml`, `fragment_flashcard.xml`

## License
This project has no explicit license specified. Use at your discretion or add a license file if needed.
