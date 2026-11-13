package com.yoyostudios.clashcompanion.command

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import com.yoyostudios.clashcompanion.accessibility.ClashCompanionAccessibilityService
import com.yoyostudios.clashcompanion.BuildConfig
import com.yoyostudios.clashcompanion.api.GeminiClient
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.command.CommandParser.CommandTier
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.detection.ArenaDetector
import com.yoyostudios.clashcompanion.detection.HandDetector
import com.yoyostudios.clashcompanion.overlay.OverlayManager
import com.yoyostudios.clashcompanion.strategy.OpusCoach
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*

/**
 * Routes parsed commands to execution across five tiers:
 *
 *  - FAST path: card slot tap + zone tap via AccessibilityService (~170ms)
 *  - QUEUE path: buffered commands that auto-play when card appears in hand
 *  - SMART path: Gemini Flash real-time tactical decisions using Opus playbook
 *  - TARGETING path: spell placement on Roboflow-detected enemy troop
 *  - CONDITIONAL path: voice rules triggered by Roboflow arena detection
 *
 * Also supports AUTOPILOT mode: AI plays cards automatically every ~4 seconds.
 */
object CommandRouter {

    private const val TAG = "ClashCompanion"

    private const val VISION_ARENA_TARGET_W = 540
    private const val VISION_ARENA_JPEG_QUALITY = 60

    /** Set by OverlayManager when overlay is shown */
    var overlay: OverlayManager? = null

    /** Current deck card names — set by DeckManager, used by CommandParser */
    var deckCards = listOf(
        "Knight", "Archers", "Minions", "Arrows",
        "Fireball", "Giant", "Musketeer", "Mini P.E.K.K.A"
    )

    /** Prevents rapid-fire double plays during tap animation */
    private var isBusy = false

    // ── Last Played Tracking (for "follow up" lane coherence) ──────────
    private var lastPlayedCard: String? = null
    private var lastPlayedZone: String? = null
    private var lastPlayedTimeMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    // Coroutine scope for Smart Path and Autopilot async calls
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Autopilot ─────────────────────────────────────────────────────────

    /** Whether autopilot mode is active (AI plays cards automatically) */
    var isAutopilot = false
        private set

    private var autopilotJob: Job? = null

    /** Autopilot decision interval in milliseconds */
    private const val AUTOPILOT_INTERVAL_MS = 4000L

    /** Commands that toggle autopilot ON */
    private val AUTOPILOT_ON_COMMANDS = setOf(
        "autopilot", "play for me", "auto play", "auto", "autoplay",
        "take over", "ai play", "ai mode"
    )

    /** Commands that toggle autopilot OFF (exact match) */
    private val AUTOPILOT_OFF_COMMANDS = setOf(
        "stop", "manual", "stop autopilot", "i got it", "stop auto",
        "my turn", "cancel autopilot",
        // STT misrecognitions from live testing
        "top autopilot", "autopilot stop", "autopillot stop",
        "top auto", "stop pilot", "top autopillot",
        "cancel auto", "cancel", "enough", "top"
    )

    /** Keywords for contains-based stop detection (only when autopilot is already active) */
    private val AUTOPILOT_OFF_KEYWORDS = setOf("stop", "manual", "cancel", "my turn", "i got it", "enough")

    // ── Queue Path (M7) ────────────────────────────────────────────────

    /** A buffered command waiting for a card to appear in hand */
    data class QueuedCommand(
        val card: String,
        val zone: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** Tracks last play attempt for elixir retry. 0 = never attempted. */
        var lastAttemptMs: Long = 0L
    )

    /** FIFO queue of pending commands. Accessed only on main thread. */
    private val commandQueue = mutableListOf<QueuedCommand>()

    /** Queue entries expire after 2 minutes to prevent stale surprise plays */
    private const val QUEUE_TIMEOUT_MS = 120_000L

    /** Cooldown between retry attempts when card is in hand but elixir was low */
    private const val QUEUE_RETRY_COOLDOWN_MS = 1500L

    /** Grace period before removing a "played" entry — card may just be dimmed (low elixir) */
    private const val QUEUE_PLAYED_GRACE_MS = 5000L

    // ── Conditional Rules ──────────────────────────────────────────────

    /** A voice rule that triggers when Roboflow detects an enemy card */
    data class ConditionalRule(
        val triggerCard: String,     // Enemy card to watch for (e.g., "Hog Rider")
        val responseCard: String,   // Your card to play (e.g., "Skeleton Army")
        val responseZone: String,   // Where to play it (e.g., "center")
        val timestamp: Long = System.currentTimeMillis(),
        var consecutiveFrames: Int = 0  // Debounce counter
    )

    /** Active conditional rules. Accessed only on main thread. */
    private val activeRules = mutableListOf<ConditionalRule>()

    // ── Main Entry Point ──────────────────────────────────────────────

    /**
     * Main entry point. Called by SpeechService.onTranscript via OverlayManager.
     * Parses the transcript and routes to the appropriate execution tier.
     *
     * Supports "then"-chaining: "knight left then musketeer right" plays
     * knight immediately and queues musketeer for auto-play when it appears.
     *
     * @param transcript raw STT output text
     * @param sttLatencyMs time taken by STT inference in milliseconds
     */
    fun handleTranscript(transcript: String, sttLatencyMs: Long) {
        val cleaned = CommandParser.cleanTranscript(transcript)

        // ── Autopilot toggle (check before parsing & before isBusy gate) ──
        // ON commands toggle: if already active, turn OFF (so "autopilot" works as on/off)
        if (cleaned in AUTOPILOT_ON_COMMANDS) {
            if (isAutopilot) stopAutopilot() else startAutopilot()
            return
        }
        if (cleaned in AUTOPILOT_OFF_COMMANDS) {
            stopAutopilot()
            return
        }
        // Contains-based fallback: when autopilot is active, catch STT misrecognitions
        if (isAutopilot && AUTOPILOT_OFF_KEYWORDS.any { cleaned.contains(it) }) {
            stopAutopilot()
            return
        }

        // Guard: don't fire while previous tap is animating.
        // Exception: when autopilot is active, user voice commands always take priority.
        if (isBusy && !isAutopilot) {
            Log.d(TAG, "CMD: Busy, skipping '$transcript'")
            overlay?.updateStatus("Wait...")
            return
        }

        val parseStart = System.currentTimeMillis()

        // Split on "then"/"than" for chained commands:
        // "knight left then musketeer right" -> play knight, queue musketeer
        val normalized = cleaned
            .replace(" and then ", " then ")
            .replace(" than ", " then ")
        val thenParts = normalized.split(" then ")

        // Parse and route the primary (first) segment
        val firstSegment = thenParts[0].trim()
        if (firstSegment.isNotEmpty()) {
            val cmd = CommandParser.parse(firstSegment, deckCards)
            val parseMs = System.currentTimeMillis() - parseStart

            Log.i(TAG, "CMD: tier=${cmd.tier} card='${cmd.card}' zone=${cmd.zone} " +
                    "conf=${String.format("%.2f", cmd.confidence)} " +
                    "parse=${parseMs}ms stt=${sttLatencyMs}ms raw='$transcript'")

            // Route by tier
            routeCommand(cmd, parseMs, sttLatencyMs, transcript)
        } else if (thenParts.size <= 1) {
            // Empty transcript with no "then" part — ignore
            Log.d(TAG, "CMD: Empty transcript, ignoring")
        }

        // Queue any "then"-chained segments
        for (i in 1 until thenParts.size) {
            val thenSegment = thenParts[i].trim()
            if (thenSegment.isEmpty()) continue
            queueThenSegment(thenSegment)
        }
    }

    /**
     * Route a parsed command to the correct execution tier.
     */
    private fun routeCommand(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTranscript: String
    ) {
        when (cmd.tier) {
            CommandTier.IGNORE -> {
                Log.d(TAG, "CMD: Ignored '$rawTranscript'")
            }

            CommandTier.FAST -> {
                executeFastPath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.QUEUE -> {
                executeQueuePath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.TARGETING -> {
                executeTargetingPath(cmd, parseMs, sttLatencyMs)
            }

            CommandTier.CONDITIONAL -> {
                executeConditionalPath(cmd)
            }

            CommandTier.SMART -> {
                executeSmartPath(cmd, parseMs, sttLatencyMs, rawTranscript)
            }
        }
    }

    /**
     * Queue a "then"-chained segment. Always adds to queue (never immediate).
     * The user said "X then Y" — Y should wait, even if Y is in hand right now.
     */
    private fun queueThenSegment(segment: String) {
        val cmd = CommandParser.parse(segment, deckCards)
        if (cmd.card.isEmpty()) {
            Log.w(TAG, "CMD: Then-chain couldn't parse: '$segment'")
            return
        }
        val zone = cmd.zone ?: CommandParser.getDefaultZone(cmd.card)
        if (zone == null) {
            overlay?.updateStatus("Where? Say '${cmd.card} left/right/center'")
            return
        }
        commandQueue.add(QueuedCommand(cmd.card, zone))
        val msg = "THEN: ${cmd.card} -> $zone (queued)"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg (${commandQueue.size} in queue)")
    }

    // ── Smart Path Execution (M8) ─────────────────────────────────────

    /**
     * Execute the Smart Path: send context to Gemini Flash for tactical AI decision.
     *
     * Assembles: deck + Opus playbook + current hand + arena state + user command.
     * Gemini returns: {"action":"play","card":"...","zone":"...","reasoning":"..."}.
     * Validates card in hand, executes via Fast Path, shows reasoning on overlay.
     */
    private fun executeSmartPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTranscript: String
    ) {
        // If parser found a card but no zone (5+ elixir), ask for zone instead of LLM
        if (cmd.card.isNotEmpty() && cmd.zone == null) {
            val msg = "Where? Say '${cmd.card} left/right/center'"
            overlay?.updateStatus(msg)
            Log.i(TAG, "CMD: $msg")
            return
        }

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            overlay?.updateStatus("Smart Path needs API key — use 'knight left' etc.")
            Log.w(TAG, "CMD: SMART path skipped — no GEMINI_API_KEY")
            return
        }

        isBusy = true
        val startMs = System.currentTimeMillis()
        overlay?.updateStatus("SMART: Thinking...")
        Log.i(TAG, "CMD: SMART path — calling Gemini Flash for '$rawTranscript'")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val systemPrompt = buildSmartPrompt()
                    val userMessage = rawTranscript

                    // Use Gemini Vision for defense calls when possible.
                    // This dramatically improves lane selection and timing without Roboflow.
                    if (wantsDefenseVision(userMessage)) {
                        val arenaImg = captureArenaImageBase64()
                        if (arenaImg != null) {
                            GeminiClient.chatWithImages(
                                systemInstruction = systemPrompt,
                                userText = "ARENA IMAGE PROVIDED. Use it to identify enemy threats and defend NOW.\n\n$userMessage",
                                images = listOf(arenaImg),
                                maxTokens = 256,
                                jsonMode = true
                            )
                        } else {
                            GeminiClient.chat(
                                systemInstruction = systemPrompt,
                                userMessage = userMessage,
                                maxTokens = 256,
                                jsonMode = true
                            )
                        }
                    } else {
                        GeminiClient.chat(
                            systemInstruction = systemPrompt,
                            userMessage = userMessage,
                            maxTokens = 256,
                            jsonMode = true
                        )
                    }
                }

                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "CMD: SMART response in ${elapsed}ms: ${result.take(200)}")

                // Parse JSON response
                val cleanJson = GeminiClient.cleanJsonResponse(result)
                val json = JsonParser.parseString(cleanJson).asJsonObject

                val action = json.get("action")?.asString ?: "wait"
                val reasoning = json.get("reasoning")?.asString ?: ""

                if (action == "wait") {
                    val msg = "SMART: Wait | $reasoning | ${elapsed}ms"
                    overlay?.updateStatus(msg)
                    Log.i(TAG, "CMD: $msg")
                    isBusy = false
                    return@launch
                }

                // action == "play"
                val card = json.get("card")?.asString ?: ""
                val zone = json.get("zone")?.asString ?: ""

                if (card.isBlank() || zone.isBlank()) {
                    overlay?.updateStatus("SMART: Bad response ($reasoning)")
                    Log.w(TAG, "CMD: SMART returned empty card/zone: $cleanJson")
                    isBusy = false
                    return@launch
                }

                // Resolve card name (Gemini may return slightly different casing)
                val resolvedCard = resolveCardName(card)
                if (resolvedCard == null) {
                    overlay?.updateStatus("SMART: Unknown card '$card'")
                    Log.w(TAG, "CMD: SMART returned unknown card: $card")
                    isBusy = false
                    return@launch
                }

                // Resolve zone (Gemini returns zone keys like "center", "left_bridge")
                val resolvedZone = resolveZoneName(zone)
                if (resolvedZone == null) {
                    overlay?.updateStatus("SMART: Unknown zone '$zone'")
                    Log.w(TAG, "CMD: SMART returned unknown zone: $zone")
                    isBusy = false
                    return@launch
                }

                // Check card is in hand
                if (!HandDetector.cardToSlot.containsKey(resolvedCard)) {
                    // Auto-queue if not in hand
                    commandQueue.add(QueuedCommand(resolvedCard, resolvedZone))
                    val msg = "SMART: $resolvedCard not in hand, queued | $reasoning | ${elapsed}ms"
                    overlay?.updateStatus(msg)
                    Log.i(TAG, "CMD: $msg")
                    isBusy = false
                    return@launch
                }

                // Execute!
                val msg = "SMART: $resolvedCard -> $resolvedZone | $reasoning | ${elapsed}ms"
                overlay?.updateStatus(msg)
                Log.i(TAG, "CMD: $msg")

                // isBusy will be released by executeFastPath's handler.postDelayed
                executeFastPath(
                    CommandParser.ParsedCommand(
                        tier = CommandTier.FAST,
                        card = resolvedCard,
                        zone = resolvedZone,
                        confidence = 1.0f,
                        rawTranscript = "SMART: $reasoning"
                    ),
                    parseMs = elapsed,
                    sttLatencyMs = sttLatencyMs
                )
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                val msg = "SMART: Error (${elapsed}ms) ${e.message?.take(40)}"
                overlay?.updateStatus(msg)
                Log.e(TAG, "CMD: $msg", e)
                isBusy = false
            }
        }
    }

    /**
     * Build the Gemini system prompt with deck, playbook, hand state, arena, and last played context.
     */
    private fun buildSmartPrompt(): String {
        val deckJson = DeckManager.getDeckJson()

        val playbook = OpusCoach.cachedPlaybook
        val playbookSection = if (!playbook.isNullOrBlank()) {
            "STRATEGIC PLAYBOOK (from deep analysis):\n$playbook"
        } else {
            "STRATEGIC PLAYBOOK: Not available. Use general Clash Royale knowledge."
        }

        val hand = HandDetector.currentHand
        val handCards = (0..3).mapNotNull { hand[it] }.joinToString(", ")
        val handSection = if (handCards.isNotBlank()) {
            "CURRENT HAND (cards available to play RIGHT NOW): $handCards"
        } else {
            "CURRENT HAND: Unknown (hand detection not ready)"
        }

        // Arena state from Roboflow (null-safe — may not be active)
        val arenaSection = buildArenaSection()

        // Last played context for "follow up" lane coherence
        val lastPlayedSection = if (lastPlayedCard != null && lastPlayedTimeMs > 0) {
            val agoSec = (System.currentTimeMillis() - lastPlayedTimeMs) / 1000
            "\nLAST PLAYED: $lastPlayedCard at $lastPlayedZone (${agoSec}s ago)"
        } else ""

        return """You are a real-time Clash Royale tactical AI. Speed is critical — be decisive.

DECK: $deckJson
$playbookSection
$handSection
$arenaSection$lastPlayedSection

VALID ZONES: left_bridge, right_bridge, center, behind_left, behind_right, back_center, front_left, front_right, opponent_left_tower, opponent_right_tower, opponent_king

ARENA VISION IS REQUIRED FOR EVERY AUTOPILOT DECISION. The supplied image is a crop of the live battlefield.
Orientation: YOUR side is the BOTTOM of the screen. ENEMY side is the TOP.
First inspect the image: list visible enemy troops/buildings, estimate each center position, classify lane (left/right/center), and infer movement only if multiple observations support it. Do not claim a troop identity when uncertain. Distinguish enemy units from friendly units and arena decorations.

Rules:
- ONLY suggest cards in CURRENT HAND. If hand detection is uncertain, return wait.
- THIS DECK IS LOG-BAIT / CONTROL: Princess, Prince, Goblin Barrel, The Log, Guards, Rocket, Bandit, Rascals. Primary win condition is Goblin Barrel.
- GOBLIN BARREL: target an ENEMY PRINCESS TOWER only. Use opponent_left_tower or opponent_right_tower, never center, bridge, back, or a friendly-side zone. Choose the weaker tower only if its HP is visibly known; otherwise choose the lane with less defensive pressure. Do not throw into a ready hard counter unless baited or a favorable prediction is justified.
- PRINCESS: deploy safely on your side in back_center / behind_left / behind_right; bridge pressure only when justified.
- PRINCE and BANDIT: counterpush or punish after opponent spends elixir; choose a safe lane-specific bridge placement, not random center.
- GUARDS: reserve for high-value ground threats and single-target defenders; place near the threat on your side.
- RASCALS: use as defensive anchor or split-lane pressure; choose placement based on lane pressure.
- THE LOG: target a visible line of enemy ground troops or a Goblin Barrel landing area; if target position is unclear, wait.
- ROCKET: use for grouped high-value troops/buildings or a justified tower finish; never waste it on a lone low-value troop.
- DEFENSE: identify enemy troop/building, lane, direction, and urgency from screenshot/detections. Choose the best available counter from THIS deck based on target type and elixir trade. Place on your side to pull, surround, or intercept. If enemy card or position cannot be identified confidently, wait.
- Track opponent counters to Goblin Barrel (Log, Arrows, Barbarian Barrel, Snowball) only after visibly observing them; bait them with Princess/Bandit/Rascals before the next Barrel.
- Manage elixir conservatively. Do not force a card every cycle. Prefer positive or strategically justified trades; wait when no safe play exists.
- Never invent unseen enemy cards, tower HP, elixir, or damage.
- Buildings go in center only if actually present in the deck.
- For follow-up, preserve lane coherence only when the lane remains safe.
Return ONLY JSON. Always include scene_confidence (0.0-1.0) and enemy_threats (array of {"card":"name or unknown","lane":"left/right/center/unknown","urgency":"low/medium/high"}). For play: {"action":"play","card":"<exact card name>","zone":"<zone_key>","scene_confidence":0.0,"enemy_threats":[],"reasoning":"<15 words max>"}. For wait: {"action":"wait","scene_confidence":0.0,"enemy_threats":[],"reasoning":"<15 words max>"}. If image is blurry, cropped incorrectly, or troop/lane cannot be determined reliably, lower scene_confidence and wait."""
    }

    private fun wantsDefenseVision(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("defend") || t.contains("defense") || t.contains("defence")
    }

    /**
     * Capture an arena screenshot suitable for Gemini vision.
     *
     * Returns a base64 JPEG (no data: prefix) or null if capture fails.
     */
    private fun captureArenaImageBase64(): String? {
        val frame = ScreenCaptureService.getLatestFrame()
        if (frame == null || frame.isRecycled) return null

        // Copy immediately to avoid ScreenCaptureService recycling races.
        val safeCopy = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w(TAG, "CMD: Arena frame copy failed: ${e.message}")
            null
        } ?: return null

        try {
            val roi = Coordinates.getArenaROI(safeCopy.width, safeCopy.height) ?: return null
            val crop = Bitmap.createBitmap(safeCopy, roi.x, roi.y, roi.w, roi.h)

            val targetW = VISION_ARENA_TARGET_W
            val targetH = (crop.height.toFloat() * (targetW.toFloat() / crop.width.toFloat())).toInt()
                .coerceAtLeast(1)
            val scaled = if (crop.width != targetW) {
                Bitmap.createScaledBitmap(crop, targetW, targetH, true)
            } else {
                crop
            }
            if (scaled !== crop) crop.recycle()

            val base64 = GeminiClient.bitmapToBase64(scaled, quality = VISION_ARENA_JPEG_QUALITY)
            scaled.recycle()
            return base64
        } catch (e: Exception) {
            Log.w(TAG, "CMD: Arena image capture failed: ${e.message}")
            return null
        } finally {
            safeCopy.recycle()
        }
    }

    /**
     * Build arena state section from ArenaDetector detections.
     * Returns empty string if no detections available.
     */
    private fun buildArenaSection(): String {
        val tracks = ArenaDetector.currentTracks
        if (tracks.isEmpty()) {
            return "ARENA TRACKING: No currently confirmed object tracks. Do not assume the arena is empty; visual detector may be unavailable or uncertain."
        }

        val now = System.currentTimeMillis()
        val items = tracks.joinToString("; ") { t ->
            val ageMs = (now - t.lastSeenMs).coerceAtLeast(0L)
            "${t.className}#${t.id} side=${t.side.name.lowercase()} lane=${t.lane.name.lowercase()} " +
                    "motion=${t.motion.name.lowercase()} pos=(${t.normalizedX.toInt()},${t.normalizedY.toInt()}) " +
                    "confidence=${String.format("%.0f", t.confidence * 100)}% age=${ageMs}ms"
        }
        return "ARENA TRACKING (model detections associated across frames; labels/side may be uncertain): $items. " +
                "Treat old/low-confidence tracks cautiously; confirm troop identity against the supplied screenshot."
    }

    /**
     * Resolve a card name from Gemini response to exact deck card name.
     * Handles case mismatches and minor variations.
     */
    private fun resolveCardName(geminiCard: String): String? {
        // Exact match
        deckCards.find { it.equals(geminiCard, ignoreCase = true) }?.let { return it }

        // Fuzzy match against deck
        val match = CommandParser.matchCard(geminiCard.lowercase(), deckCards)
        return if (match != null && match.second > 0.6f) match.first else null
    }

    /**
     * Resolve a zone name from Gemini response to a valid ZONE_MAP key.
     */
    private fun resolveZoneName(geminiZone: String): String? {
        val zone = geminiZone.lowercase().trim()
        // Direct match
        if (Coordinates.ZONE_MAP.containsKey(zone)) return zone
        // Try with underscores replaced by spaces and vice versa
        val withSpaces = zone.replace("_", " ")
        if (Coordinates.ZONE_MAP.containsKey(withSpaces)) return withSpaces
        val withUnderscores = zone.replace(" ", "_")
        if (Coordinates.ZONE_MAP.containsKey(withUnderscores)) return withUnderscores
        return null
    }

    // ── Autopilot ─────────────────────────────────────────────────────

    /**
     * Start autopilot mode: AI plays cards automatically every ~4 seconds.
     * Voice commands still work during autopilot (Fast Path overrides).
     */
    fun startAutopilot() {
        // Fail closed: the autopilot must not act from Gemini's interpretation
        // of a screenshot alone. Require an active arena detector as well.
        // This prevents blind play in builds where Roboflow/local vision is absent.
        if (!ArenaDetector.isPolling) {
            overlay?.updateStatus("Autopilot unavailable: arena detector is not running")
            Log.w(TAG, "CMD: Autopilot skipped — no active arena detector")
            return
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            overlay?.updateStatus("Autopilot needs API key — use 'knight left' etc.")
            Log.w(TAG, "CMD: Autopilot skipped — no GEMINI_API_KEY")
            return
        }
        if (isAutopilot) {
            overlay?.updateStatus("AUTOPILOT already active")
            return
        }
        isAutopilot = true
        overlay?.updateStatus("AUTOPILOT: AI is playing!")
        overlay?.setAutopilotActive(true)
        Log.i(TAG, "CMD: Autopilot STARTED")

        autopilotJob = scope.launch {
            while (isActive) {
                delay(AUTOPILOT_INTERVAL_MS)

                // Skip if busy (another command executing) or hand not ready
                if (isBusy) continue
                val hand = HandDetector.currentHand
                if (hand.filterKeys { it < 4 }.isEmpty()) continue

                // Call Gemini for decision
                try {
                    isBusy = true
                    val startMs = System.currentTimeMillis()

                    val result = withContext(Dispatchers.IO) {
                        val systemPrompt = buildSmartPrompt()
                        val userText = "AUTOPILOT: Evaluate the current screenshot and detections before acting. " +
                                "Play only when a safe, tactically justified move exists; return wait otherwise. " +
                                "Use this deck-specific Log-bait strategy: Goblin Barrel targets an enemy Princess Tower only; " +
                                "defend the highest-priority threat with the best available counter; track observed Barrel counters; " +
                                "avoid wasting Rocket and avoid random placements. Never invent unseen enemy cards or tower HP."

                        val arenaImg = captureArenaImageBase64()
                            ?: throw IllegalStateException("Arena screenshot unavailable; refusing blind autopilot play")
                        GeminiClient.chatWithImages(
                            systemInstruction = systemPrompt,
                            userText = userText,
                            images = listOf(arenaImg),
                            maxTokens = 384,
                            jsonMode = true
                        )
                    }

                    val elapsed = System.currentTimeMillis() - startMs
                    val cleanJson = GeminiClient.cleanJsonResponse(result)
                    val json = JsonParser.parseString(cleanJson).asJsonObject

                    val action = json.get("action")?.asString ?: "wait"
                    val reasoning = json.get("reasoning")?.asString ?: ""
                    val sceneConfidence = json.get("scene_confidence")?.asFloat ?: 0f
                    val threatSummary = json.getAsJsonArray("enemy_threats")?.joinToString { item ->
                        val threat = item.asJsonObject
                        "${threat.get("card")?.asString ?: "unknown"}:${threat.get("lane")?.asString ?: "?"}"
                    } ?: "none/uncertain"

                    // Fail closed: do not execute a move when visual interpretation is uncertain.
                    if (sceneConfidence < 0.70f) {
                        withContext(Dispatchers.Main) {
                            overlay?.updateStatus("AUTO: Vision uncertain (${(sceneConfidence * 100).toInt()}%) — waiting")
                        }
                        isBusy = false
                        continue
                    }

                    Log.i(TAG, "AUTO vision confidence=${sceneConfidence} threats=${threatSummary}")

                    if (action == "wait") {
                        withContext(Dispatchers.Main) {
                            overlay?.updateStatus("AUTO: Wait | $reasoning")
                        }
                        isBusy = false
                        continue
                    }

                    val card = json.get("card")?.asString ?: ""
                    val zone = json.get("zone")?.asString ?: ""
                    val resolvedCard = resolveCardName(card)
                    val resolvedZone = resolveZoneName(zone)

                    if (resolvedCard == null || resolvedZone == null) {
                        Log.w(TAG, "CMD: AUTOPILOT bad response: card=$card zone=$zone")
                        isBusy = false
                        continue
                    }

                    if (!HandDetector.cardToSlot.containsKey(resolvedCard)) {
                        withContext(Dispatchers.Main) {
                            overlay?.updateStatus("AUTO: $resolvedCard not in hand | $reasoning")
                        }
                        isBusy = false
                        continue
                    }

                    withContext(Dispatchers.Main) {
                        overlay?.updateStatus("AUTO: $resolvedCard -> $resolvedZone | $reasoning | ${elapsed}ms")
                    }

                    // Execute on main thread — executeFastPath releases isBusy via handler
                    withContext(Dispatchers.Main) {
                        executeFastPath(
                            CommandParser.ParsedCommand(
                                tier = CommandTier.FAST,
                                card = resolvedCard,
                                zone = resolvedZone,
                                confidence = 1.0f,
                                rawTranscript = "AUTOPILOT: $reasoning"
                            ),
                            parseMs = elapsed,
                            sttLatencyMs = 0
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "CMD: AUTOPILOT error: ${e.message}")
                    isBusy = false
                }
            }
        }
    }

    /**
     * Stop autopilot mode.
     */
    fun stopAutopilot() {
        if (!isAutopilot) return
        autopilotJob?.cancel()
        autopilotJob = null
        isAutopilot = false
        isBusy = false
        overlay?.setAutopilotActive(false)
        overlay?.updateStatus("AUTOPILOT stopped — you're in control")
        Log.i(TAG, "CMD: Autopilot STOPPED")
    }

    // ── Targeting Path Execution ──────────────────────────────────────

    /**
     * Execute the Targeting Path: place a spell on a Roboflow-detected enemy troop.
     * e.g. "fireball the hog rider" -> find hog rider on arena -> place fireball there.
     */
    private fun executeTargetingPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long
    ) {
        if (cmd.card.isEmpty() || cmd.targetCard == null) {
            overlay?.updateStatus("TARGET: couldn't parse")
            return
        }

        // Look up target in ArenaDetector cache
        val detections = ArenaDetector.currentDetections
        val targetName = cmd.targetCard.lowercase()

        // Fuzzy match detection class name against target card name
        val match = detections.firstOrNull { d ->
            val cls = d.className.lowercase().replace("-", " ").replace("_", " ")
            cls.contains(targetName) || targetName.contains(cls) ||
                    CommandParser.levenshteinSimilarity(cls, targetName) > 0.6f
        }

        if (match == null) {
            val msg = "TARGET: ${cmd.targetCard} not detected on arena"
            overlay?.updateStatus(msg)
            Log.i(TAG, "CMD: $msg (${detections.size} detections available)")
            return
        }

        // Check card is in hand
        val slotIndex = HandDetector.cardToSlot[cmd.card]
        if (slotIndex == null) {
            overlay?.updateStatus("TARGET: ${cmd.card} not in hand")
            return
        }
        if (slotIndex < 0 || slotIndex >= Coordinates.CARD_SLOTS.size) {
            overlay?.updateStatus("TARGET: Invalid slot $slotIndex")
            return
        }

        val svc = ClashCompanionAccessibilityService.instance
        if (svc == null) {
            overlay?.updateStatus("ERROR: Accessibility not connected")
            return
        }

        isBusy = true
        val (slotX, slotY) = Coordinates.CARD_SLOTS[slotIndex]

        // Use detection center as target coordinates
        val targetX = match.centerX.toFloat()
        val targetY = match.centerY.toFloat()

        val msg = "TARGET: ${cmd.card} -> ${cmd.targetCard} at (${match.centerX},${match.centerY})"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg conf=${String.format("%.0f", match.confidence * 100)}%")

        overlay?.setPassthrough(true)
        svc.playCard(slotX, slotY, targetX, targetY)

        handler.postDelayed({
            overlay?.setPassthrough(false)
            isBusy = false
        }, 500)
    }

    // ── Conditional Rules Execution ───────────────────────────────────

    /**
     * Execute the Conditional Path: store a rule that triggers when Roboflow
     * detects the specified enemy card.
     * e.g. "whenever hog rider play skeleton army center"
     */
    private fun executeConditionalPath(cmd: CommandParser.ParsedCommand) {
        if (cmd.triggerCard == null || cmd.card.isEmpty()) {
            overlay?.updateStatus("RULE: couldn't parse rule")
            Log.w(TAG, "CMD: Conditional parse incomplete: trigger=${cmd.triggerCard} card=${cmd.card}")
            return
        }

        val zone = cmd.zone ?: CommandParser.getDefaultZone(cmd.card) ?: "center"

        val rule = ConditionalRule(
            triggerCard = cmd.triggerCard,
            responseCard = cmd.card,
            responseZone = zone
        )
        activeRules.add(rule)

        val msg = "RULE SET: if ${cmd.triggerCard} -> ${cmd.card} $zone"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg (${activeRules.size} active rules)")
    }

    /**
     * Check active conditional rules against Roboflow arena detections.
     * Called from OverlayManager when ArenaDetector reports new detections.
     *
     * Debounce: requires 2 consecutive detections before triggering.
     * Fire-once: removes rule after trigger to prevent spam.
     */
    fun checkRules(detections: List<ArenaDetector.Detection>) {
        if (isBusy || activeRules.isEmpty()) return

        // Prune expired rules (>5 minutes old)
        val now = System.currentTimeMillis()
        activeRules.removeAll { now - it.timestamp > 300_000L }

        val triggeredRules = mutableListOf<ConditionalRule>()

        for (rule in activeRules) {
            val triggerName = rule.triggerCard.lowercase()

            // Check if any detection matches the trigger card
            val detected = detections.any { d ->
                val cls = d.className.lowercase().replace("-", " ").replace("_", " ")
                cls.contains(triggerName) || triggerName.contains(cls) ||
                        CommandParser.levenshteinSimilarity(cls, triggerName) > 0.6f
            }

            if (detected) {
                rule.consecutiveFrames++
                if (rule.consecutiveFrames >= 2) {
                    // Debounce passed — check if response card is in hand
                    if (HandDetector.cardToSlot.containsKey(rule.responseCard)) {
                        triggeredRules.add(rule)
                    } else {
                        overlay?.updateStatus("RULE: ${rule.triggerCard} seen but ${rule.responseCard} not in hand")
                    }
                }
            } else {
                rule.consecutiveFrames = 0
            }
        }

        // Execute first triggered rule (one at a time)
        val rule = triggeredRules.firstOrNull() ?: return
        activeRules.remove(rule) // Fire-once

        val msg = "RULE FIRED: ${rule.triggerCard} detected -> ${rule.responseCard} ${rule.responseZone}!"
        overlay?.updateStatus(msg)
        Log.i(TAG, "CMD: $msg")

        executeFastPath(
            CommandParser.ParsedCommand(
                tier = CommandTier.FAST,
                card = rule.responseCard,
                zone = rule.responseZone,
                confidence = 1.0f,
                rawTranscript = "RULE: if ${rule.triggerCard} -> ${rule.responseCard}"
            ),
            parseMs = 0,
            sttLatencyMs = 0
        )
    }

    /**
     * Get display string of active rules for the overlay.
     */
    fun getRulesDisplay(): String {
        if (activeRules.isEmpty()) return ""
        return activeRules.joinToString("\n") { r ->
            "  if ${r.triggerCard} -> ${r.responseCard} ${r.responseZone}"
        }
    }

    /**
     * Clear all active rules.
     */
    fun clearRules() {
        val count = activeRules.size
        activeRules.clear()
        if (count > 0) Log.i(TAG, "CMD: Cleared $count rules")
    }

    // ── Queue Path Execution ─────────────────────────────────────────

    /**
     * Handle a QUEUE-tier command: "queue balloon bridge" / "next hog left" / "then hog left"
     */
    private fun executeQueuePath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long
    ) {
        if (cmd.card.isEmpty()) {
            overlay?.updateStatus("Queue: couldn't parse card")
            Log.w(TAG, "CMD: Queue parse failed for '${cmd.rawTranscript}'")
            return
        }

        if (cmd.zone == null) {
            val msg = "Where? Say '${cmd.card} left/right/center'"
            overlay?.updateStatus(msg)
            Log.i(TAG, "CMD: Queue needs zone — $msg")
            return
        }

        commandQueue.add(QueuedCommand(cmd.card, cmd.zone))
        Log.i(TAG, "CMD: QUEUED: ${cmd.card} -> ${cmd.zone} (${commandQueue.size} in queue)")

        if (!isBusy && HandDetector.cardToSlot.containsKey(cmd.card)) {
            checkQueue()
        } else {
            overlay?.updateStatus("QUEUED: ${cmd.card} -> ${cmd.zone} (waiting...)")
        }
    }

    /**
     * Check if any queued card has appeared in the player's hand.
     * Called from OverlayManager's onHandChanged callback on the main thread.
     */
    fun checkQueue() {
        if (isBusy || commandQueue.isEmpty()) return

        val now = System.currentTimeMillis()

        // Prune expired entries (>2 minutes old)
        commandQueue.removeAll { now - it.timestamp > QUEUE_TIMEOUT_MS }
        if (commandQueue.isEmpty()) return

        val handSlots = HandDetector.cardToSlot

        // Remove entries where card has been absent for >5s after attempt
        commandQueue.removeAll { q ->
            q.lastAttemptMs > 0
                    && !handSlots.containsKey(q.card)
                    && now - q.lastAttemptMs > QUEUE_PLAYED_GRACE_MS
        }
        if (commandQueue.isEmpty()) return

        for (queued in commandQueue) {
            if (!handSlots.containsKey(queued.card)) continue
            if (queued.lastAttemptMs > 0 && now - queued.lastAttemptMs < QUEUE_RETRY_COOLDOWN_MS) continue

            val isRetry = queued.lastAttemptMs > 0
            queued.lastAttemptMs = now
            val waitMs = now - queued.timestamp

            Log.i(TAG, "CMD: Queue ${if (isRetry) "retry" else "triggered"} — " +
                    "${queued.card} in hand (waited ${waitMs}ms)")

            val msg = "QUEUE: ${queued.card} -> ${queued.zone}" +
                    if (isRetry) " (retry)" else ""
            overlay?.updateStatus(msg)

            executeFastPath(
                CommandParser.ParsedCommand(
                    tier = CommandTier.FAST,
                    card = queued.card,
                    zone = queued.zone,
                    confidence = 1.0f,
                    rawTranscript = "QUEUE auto-play"
                ),
                parseMs = 0,
                sttLatencyMs = waitMs
            )
            return
        }
    }

    fun clearQueue() {
        val count = commandQueue.size
        commandQueue.clear()
        if (count > 0) {
            overlay?.updateStatus("Queue cleared ($count removed)")
            Log.i(TAG, "CMD: Queue cleared ($count entries)")
        }
    }

    fun getQueueDisplay(): String {
        if (commandQueue.isEmpty()) return ""
        return commandQueue.joinToString("\n") { q ->
            val ago = (System.currentTimeMillis() - q.timestamp) / 1000
            "  ${q.card} -> ${q.zone} (${ago}s)"
        }
    }

    // ── Fast Path Execution ───────────────────────────────────────────

    /**
     * Execute the Fast Path: tap card slot, then tap zone.
     * Also used by Smart Path, Queue Path, Targeting, and Rules as final executor.
     *
     * Accepts optional raw pixel coordinates for targeting (overrides zone lookup).
     */
    private fun executeFastPath(
        cmd: CommandParser.ParsedCommand,
        parseMs: Long,
        sttLatencyMs: Long,
        rawTargetX: Float? = null,
        rawTargetY: Float? = null
    ) {
        val card = cmd.card
        val zone = cmd.zone

        // Confidence gate
        val threshold = CommandParser.getConfidenceThreshold(card)
        if (cmd.confidence < threshold) {
            val pct = (cmd.confidence * 100).toInt()
            val msg = "Low confidence: $card? ($pct% < ${(threshold * 100).toInt()}%)"
            overlay?.updateStatus(msg)
            Log.w(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        // Check card is in hand via classifier
        val slotIndex = HandDetector.cardToSlot[card]
        if (slotIndex == null) {
            val handCards = HandDetector.currentHand
                .filterKeys { it < 4 }
                .entries.sortedBy { it.key }
                .joinToString { it.value }
            val msg = "Not in hand: $card (hand: $handCards)"
            overlay?.updateStatus(msg)
            Log.w(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        if (slotIndex < 0 || slotIndex >= Coordinates.CARD_SLOTS.size) {
            val msg = "ERROR: Invalid slot index $slotIndex for $card"
            overlay?.updateStatus(msg)
            Log.e(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        val (slotX, slotY) = Coordinates.CARD_SLOTS[slotIndex]

        // Zone coordinates — use raw target if provided, otherwise look up zone map
        val zoneX: Float
        val zoneY: Float
        if (rawTargetX != null && rawTargetY != null) {
            zoneX = rawTargetX
            zoneY = rawTargetY
        } else {
            if (zone == null) {
                val msg = "ERROR: No zone for $card"
                overlay?.updateStatus(msg)
                Log.e(TAG, "CMD: $msg")
                isBusy = false
                return
            }
            val zoneCoords = Coordinates.ZONE_MAP[zone]
            if (zoneCoords == null) {
                val msg = "ERROR: Unknown zone '$zone'"
                overlay?.updateStatus(msg)
                Log.e(TAG, "CMD: $msg")
                isBusy = false
                return
            }
            zoneX = zoneCoords.first
            zoneY = zoneCoords.second
        }

        // Execute!
        val svc = ClashCompanionAccessibilityService.instance
        if (svc == null) {
            val msg = "ERROR: Accessibility service not connected"
            overlay?.updateStatus(msg)
            Log.e(TAG, "CMD: $msg")
            isBusy = false
            return
        }

        isBusy = true

        val totalMs = parseMs + sttLatencyMs
        val displayZone = zone ?: "(${zoneX.toInt()},${zoneY.toInt()})"
        if (!cmd.rawTranscript.startsWith("SMART:") &&
            !cmd.rawTranscript.startsWith("AUTOPILOT:") &&
            !cmd.rawTranscript.startsWith("QUEUE") &&
            !cmd.rawTranscript.startsWith("RULE:")) {
            val msg = "FAST: $card -> $displayZone | ${totalMs}ms"
            overlay?.updateStatus(msg)
        }
        Log.i(TAG, "CMD: PLAY $card slot=$slotIndex ($slotX,$slotY) -> ($zoneX,$zoneY) ${totalMs}ms")

        // Record last played for "follow up" lane coherence
        lastPlayedCard = card
        lastPlayedZone = zone
        lastPlayedTimeMs = System.currentTimeMillis()

        // Make overlay transparent to touches so dispatchGesture doesn't hit overlay
        overlay?.setPassthrough(true)
        svc.playCard(slotX, slotY, zoneX, zoneY)

        // Release busy lock and restore overlay touchability after gesture completes
        handler.postDelayed({
            overlay?.setPassthrough(false)
            isBusy = false
        }, 500)
    }

    /**
     * Clean up resources. Call when app is being destroyed.
     */
    fun destroy() {
        stopAutopilot()
        scope.cancel()
    }
}
