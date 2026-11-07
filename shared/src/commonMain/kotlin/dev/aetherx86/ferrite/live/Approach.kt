package dev.aetherx86.ferrite.live

/** Where a board's numbers come from. Shown on screen, so nothing passes for what it is not. */
enum class FeedSource {
    /** Reported by the operator for this train, just now. */
    LIVE,

    /** Worked out from typical service intervals, because no live report is available here. */
    ESTIMATE,

    /** Scripted, for showing what the board does. */
    DEMO,
}

/** One train on its way to one platform. */
data class Approach(
    /** Null when no one has reported which train this is. */
    val trainNumber: String?,
    val destination: String,
    val arrivalAtMs: Long,
    val express: Boolean = false,
    val lastTrain: Boolean = false,
)

data class ArrivalFeed(
    val source: FeedSource,
    val trains: List<Approach>,
    /** When this information was produced. */
    val atMs: Long,
)
