package com.ambix.assistant.strategy

import com.ambix.assistant.vision.BattleSnapshot
import com.ambix.assistant.vision.Detection
import com.ambix.assistant.vision.ObjectKind
import com.ambix.assistant.vision.Team
import com.ambix.assistant.vision.TemporalDetectionFilter

data class PlannedMove(
    val card: String,
    val lane: String,
    val targetX: Float,
    val targetY: Float,
    val confidence: Float,
    val reason: String,
    val safeToExecute: Boolean
)

data class DeckCard(val name: String, val cost: Int)

object AmbiXTacticalEngine {
    private val detectionFilter = TemporalDetectionFilter(windowSize = 5, minHits = 3)
    private val deck = listOf(
        DeckCard("Princess", 3), DeckCard("Prince", 5),
        DeckCard("Goblin Barrel", 3), DeckCard("The Log", 2),
        DeckCard("Guards", 3), DeckCard("Rocket", 6),
        DeckCard("Bandit", 3), DeckCard("Rascals", 5)
    )

    private val counters = mapOf(
        "Hog Rider" to listOf("Guards", "Prince"),
        "Balloon" to listOf("Rocket", "Princess"),
        "Giant" to listOf("Prince", "Guards", "Rascals"),
        "Mega Knight" to listOf("Prince", "Guards", "Rascals"),
        "P.E.K.K.A" to listOf("Guards", "Rascals"),
        "Goblin Barrel" to listOf("The Log"),
        "Royal Hogs" to listOf("The Log", "Guards"),
        "Battle Ram" to listOf("Guards", "Prince")
    )

    /**
     * Conservative first-pass policy: never emits a move when the frame,
     * elixir, hand recognition, or threat classification is uncertain.
     * Coordinates are normalized to the full captured frame.
     */
    fun choose(snapshot: BattleSnapshot): PlannedMove? {
        if (!snapshot.isBattleScene || !snapshot.visionReady) return null
        val elixir = snapshot.elixir ?: return null
        if (snapshot.hand.size < 3) return null
        if (snapshot.hand.any { it.name.isNullOrBlank() || it.confidence < 0.90f }) return null

        val stableDetections = detectionFilter.update(snapshot.detections, snapshot.capturedAtMs)
        val threats = stableDetections
            .filter { it.team == Team.OPPONENT && it.kind in setOf(ObjectKind.TROOP, ObjectKind.BUILDING) && it.confidence >= 0.88f }
            .sortedByDescending { it.confidence }

        val threat = threats.firstOrNull()
        if (threat != null) {
            val threatName = threat.label
            val preferred = counters.entries
                .firstOrNull { (key, _) -> key.equals(threatName, ignoreCase = true) }
                ?.value.orEmpty()
            val available = snapshot.hand.mapNotNull { card ->
                val name = card.name ?: return@mapNotNull null
                deck.firstOrNull { it.name == name && it.cost <= elixir }
            }
            val counter = preferred.firstNotNullOfOrNull { name -> available.firstOrNull { it.name == name } }
            if (counter != null) {
                val lane = if (threat.bounds.centerX < 0.5f) "izquierda" else "derecha"
                val x = if (lane == "izquierda") 0.34f else 0.66f
                val y = (threat.bounds.bottom + 0.08f).coerceIn(0.55f, 0.82f)
                return PlannedMove(
                    counter.name, lane, x, y,
                    (threat.confidence * 0.92f).coerceAtMost(0.94f),
                    "Respuesta defensiva preliminar contra $threatName. Validar alcance y apoyos antes de automatizar.",
                    false
                )
            }
        }
        return null
    }

    fun resetBattleMemory() {
        detectionFilter.reset()
    }

    fun deckDefinition(): List<DeckCard> = deck
}
