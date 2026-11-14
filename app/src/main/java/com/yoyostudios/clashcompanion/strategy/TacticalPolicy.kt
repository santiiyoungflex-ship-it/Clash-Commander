package com.yoyostudios.clashcompanion.strategy

/**
 * Deterministic, fail-closed tactical selector.
 *
 * This layer deliberately does not infer elixir, card availability, unit identity,
 * or coordinates. It only acts on a caller-supplied, fresh and confidence-qualified
 * snapshot. Unknown or ambiguous state always produces WAIT.
 *
 * It is intentionally not wired to automatic taps until local perception and
 * Moto e32 calibration have passed their qualification gates.
 */
object TacticalPolicy {

    enum class Side { ENEMY, FRIENDLY, UNKNOWN }
    enum class Lane { LEFT, CENTER, RIGHT, UNKNOWN }
    enum class Action { WAIT, PLAY }

    data class Threat(
        val className: String,
        val confidence: Float,
        val side: Side,
        val lane: Lane,
        val normalizedY: Float,
        val ageMs: Long
    )

    data class Snapshot(
        val threats: List<Threat>,
        val hand: Set<String>,
        val handAgeMs: Long?,
        val elixir: Int?,
        val nowMs: Long
    )

    data class Decision(
        val action: Action,
        val card: String? = null,
        val lane: Lane? = null,
        val reason: String
    )

    private data class Counter(val card: String, val maxElixir: Int, val reason: String)

    private const val MAX_THREAT_AGE_MS = 700L
    private const val MAX_HAND_AGE_MS = 650L
    private const val MIN_CONFIDENCE = 0.78f
    private const val MIN_ELIXIR_RESERVE = 0

    // Only exact, high-confidence interactions are enabled. This avoids pretending
    // that a generic troop-to-troop counter table is reliable without validated CV.
    private val exactCounters = mapOf(
        "goblin barrel" to Counter("The Log", 2, "spell counter for Goblin Barrel"),
        "skeleton army" to Counter("The Log", 2, "area spell against Skeleton Army"),
        "goblin gang" to Counter("The Log", 2, "area spell against Goblin Gang")
    )

    fun decide(snapshot: Snapshot): Decision {
        if (snapshot.handAgeMs == null || snapshot.handAgeMs > MAX_HAND_AGE_MS) {
            return wait("hand state missing or stale")
        }
        if (snapshot.elixir == null || snapshot.elixir !in 0..10) {
            return wait("elixir unknown or invalid")
        }

        val freshThreats = snapshot.threats.filter {
            it.side == Side.ENEMY &&
                it.ageMs in 0..MAX_THREAT_AGE_MS &&
                it.confidence >= MIN_CONFIDENCE &&
                it.normalizedY in 0f..1f &&
                it.lane != Lane.UNKNOWN
        }

        if (freshThreats.isEmpty()) return wait("no qualified fresh enemy threat")

        // Prioritize the threat closest to the player's side (larger normalized Y).
        val threat = freshThreats.maxByOrNull { it.normalizedY }
            ?: return wait("no actionable threat")

        val key = normalize(threat.className)
        val counter = exactCounters[key] ?: return wait("no validated counter rule for '$key'")

        val available = snapshot.hand.any { normalize(it) == normalize(counter.card) }
        if (!available) return wait("${counter.card} is not confirmed in hand")
        if (snapshot.elixir < counter.maxElixir + MIN_ELIXIR_RESERVE) {
            return wait("insufficient confirmed elixir for ${counter.card}")
        }

        return Decision(
            action = Action.PLAY,
            card = counter.card,
            lane = threat.lane,
            reason = counter.reason
        )
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun wait(reason: String) = Decision(Action.WAIT, reason = reason)
}
