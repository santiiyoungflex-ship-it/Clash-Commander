package com.yoyostudios.clashcompanion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService\nimport com.yoyostudios.clashcompanion.api.ApiKeyStore\nimport com.yoyostudios.clashcompanion.api.GeminiClient
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.detection.CardClassifier
import com.yoyostudios.clashcompanion.detection.HandDetector
import com.yoyostudios.clashcompanion.overlay.OverlayManager
import com.yoyostudios.clashcompanion.speech.SpeechService
import com.yoyostudios.clashcompanion.strategy.OpusCoach
import com.yoyostudios.clashcompanion.ui.screens.MainScreen
import com.yoyostudios.clashcompanion.ui.theme.ClashCompanionTheme
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ClashCompanion"
        private const val REQUEST_MIC_PERMISSION = 100
    }

    // ── Reactive state for Compose ──
    val overlayGranted = mutableStateOf(false)
    val accessibilityEnabled = mutableStateOf(false)
    val micGranted = mutableStateOf(false)
    val captureRunning = mutableStateOf(false)
    val speechReady = mutableStateOf(false)
    val speechLoading = mutableStateOf(false)
    val deckCards = mutableStateOf<List<DeckManager.CardInfo>>(emptyList())
    val deckModelWarning = mutableStateOf<String?>(null)
    val opusStatus = mutableStateOf("")
    val opusComplete = mutableStateOf(false)\n    val geminiKeyConfigured = mutableStateOf(false)

    private var overlayManager: OverlayManager? = null
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ApiKeyStore.initialize(applicationContext)\n        geminiKeyConfigured.value = GeminiClient.isConfigured()\n        Coordinates.init(this)
        DeckManager.loadCardDatabase(assets)
        CardClassifier.init(this)

        // Register MediaProjection result handler
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                captureRunning.value = true
                // Poll until service instance is ready (for onResume race condition)
                val h = android.os.Handler(mainLooper)
                h.postDelayed(object : Runnable {
                    override fun run() {
                        if (ScreenCaptureService.instance != null) {
                            captureRunning.value = true
                        } else {
                            h.postDelayed(this, 300)
                        }
                    }
                }, 300)
            }
        }

        // Request mic on launch
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION
            )
        }

        // Load saved deck
        if (DeckManager.loadSavedDeck(this)) {
            deckCards.value = DeckManager.currentDeck
            updateDeckModelWarning(DeckManager.currentDeck)
            OpusCoach.loadSavedPlaybook(this)
            if (OpusCoach.cachedPlaybook != null) {
                opusStatus.value = "Playbook loaded"
                opusComplete.value = true
            }
        }

        // Handle incoming deck share intent
        handleDeckIntent(intent)

        // Set Compose UI
        setContent {
            ClashCompanionTheme {
                MainScreen(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeckIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    fun refreshPermissionStates() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        captureRunning.value = ScreenCaptureService.instance != null
        val svc = SpeechService.instance
        speechReady.value = svc?.isReady() == true
        speechLoading.value = svc != null && !speechReady.value
    }

    // ── Permission actions (called from Compose) ──

    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    fun requestAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION
            )
        }
    }

    fun startScreenCapture() {
        if (ScreenCaptureService.instance != null) return
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    fun startSpeechService() {
        if (SpeechService.instance != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(this, SpeechService::class.java)
        startForegroundService(intent)
        speechLoading.value = true

        // Poll until model loaded
        val pollHandler = android.os.Handler(mainLooper)
        val pollRunnable = object : Runnable {
            override fun run() {
                val svc = SpeechService.instance
                if (svc != null && svc.isReady()) {
                    speechReady.value = true
                    speechLoading.value = false
                } else {
                    pollHandler.postDelayed(this, 500)
                }
            }
        }
        pollHandler.postDelayed(pollRunnable, 500)
    }

    fun saveGeminiApiKey(key: String) {
        ApiKeyStore.saveGeminiKey(key)
        geminiKeyConfigured.value = GeminiClient.isConfigured()
    }

    fun openGeminiApiKeyPage() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
    }

    fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (!isAccessibilityServiceEnabled()) return
        if (overlayManager == null) {
            overlayManager = OverlayManager(applicationContext)
        }
        overlayManager?.show()
    }

    // ── Deck handling ──

    private fun handleDeckIntent(intent: Intent?) {
        if (intent == null) return
        Log.i(TAG, "DECK: handleDeckIntent action=${intent.action} data=${intent.data} clipData=${intent.clipData}")
        val cards = when (intent.action) {
            Intent.ACTION_SEND -> {
                // Try EXTRA_TEXT first, then fall back to clipData (CR uses clipData)
                var text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text == null) {
                    val clip = intent.clipData
                    if (clip != null && clip.itemCount > 0) {
                        text = clip.getItemAt(0).text?.toString()
                            ?: clip.getItemAt(0).uri?.toString()
                    }
                }
                if (text != null) {
                    Log.i(TAG, "DECK: Received shared text: $text")
                    DeckManager.parseDeckFromText(text)
                } else {
                    Log.w(TAG, "DECK: ACTION_SEND but no text found")
                    null
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString()
                if (url != null) {
                    Log.i(TAG, "DECK: Received view URL: $url")
                    DeckManager.parseDeckUrl(url)
                } else null
            }
            else -> null
        }
        if (cards != null && cards.isNotEmpty()) {
            DeckManager.setDeck(cards, this)
            deckCards.value = cards
            updateDeckModelWarning(cards)
            // If the STT engine is already running, reload hotwords so recognition
            // matches the newly loaded deck without requiring a full service restart.
            SpeechService.instance?.reloadHotwords()
            triggerOpusAnalysis(cards)
            Log.i(TAG, "DECK: Loaded ${cards.size} cards. ResNet classifier ready.")
        }
    }

    private fun updateDeckModelWarning(cards: List<DeckManager.CardInfo>) {
        val unsupported = CardClassifier.unsupportedDeckCards(cards)
        if (unsupported.isEmpty()) {
            deckModelWarning.value = null
            return
        }

        val names = unsupported.joinToString(", ") { it.name }
        deckModelWarning.value =
            "On-device hand detection doesn't support: $names. " +
                "Those cards may be misrecognized until the classifier is retrained."
        Log.w(TAG, "DECK: Unsupported by on-device classifier (${unsupported.size}): $names")
    }

    private fun triggerOpusAnalysis(deck: List<DeckManager.CardInfo>) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank()) {
            opusStatus.value = "No API key"
            Log.w(TAG, "OPUS: No API key, skipping")
            return
        }
        opusStatus.value = "Analyzing deck..."
        opusComplete.value = false
        scope.launch {
            OpusCoach.analyzeWithProgress(deck, this@MainActivity) { progress ->
                runOnUiThread {
                    opusStatus.value = progress
                }
            }
            // After coroutine completes, check if playbook was generated
            runOnUiThread {
                if (OpusCoach.cachedPlaybook != null) {
                    opusComplete.value = true
                    opusStatus.value = "Strategy Ready"
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_accessibility_services"
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager?.hide()
        HandDetector.stopScanning()
        // CardClassifier.destroy() — removed: destroys native mutex while scan may be mid-inference
        scope.cancel()
    }
}
