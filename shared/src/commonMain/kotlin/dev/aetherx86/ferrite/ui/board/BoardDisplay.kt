package dev.aetherx86.ferrite.ui.board

import dev.aetherx86.ferrite.live.Approach
import dev.aetherx86.ferrite.live.BoardFrame
import dev.aetherx86.ferrite.live.BoardPath
import dev.aetherx86.ferrite.live.FeedSource
import dev.aetherx86.ferrite.live.Phase
import dev.aetherx86.ferrite.live.countdownParts

/** Just the words and numbers on a board, so the page only recomposes when one of them changes. */
data class BoardDisplay(
    val pill: String,
    val cornerLabel: String,
    val destination: String,
    val detail: String,
    val trainNumber: String?,
    val value: String,
    val unit: String,
    val valueIsUrgent: Boolean,
    val switched: Boolean,
    val trainKey: Long,
    val noService: Boolean,
) {
    companion object {
        fun of(frame: BoardFrame, path: BoardPath, source: FeedSource, englishOf: (String) -> String): BoardDisplay {
            // While the arrival color spreads or recedes, the page underneath already shows the following train.
            val focus = if (frame.atPlatform != null && frame.phase in setOf(Phase.ARRIVED, Phase.CLOSING)) frame.following else frame.focus
            val seconds = if (focus == null) 0 else frame.secondsToFocusOf(focus)
            val (value, unit) = countdownParts(seconds)
            val previous = path.upstream.firstOrNull()?.name
            val phase = if (frame.atPlatform != null && frame.phase in setOf(Phase.ARRIVED, Phase.CLOSING)) Phase.WAITING else frame.phase
            val pill = when (phase) {
                Phase.PREVIOUS_DEPARTED -> if (previous != null) "$previous 출발" else "출발"
                Phase.APPROACHING -> "열차 접근 중"
                Phase.ENTERING -> "열차 진입 중"
                else -> "다음 열차"
            }
            val corner = if (phase == Phase.WAITING) "다음 열차까지" else "도착까지"
            val english = focus?.let { englishOf(it.destination) }.orEmpty()
            val detail = buildList {
                if (english.isNotEmpty()) add(english)
                when {
                    focus == null -> add("운행 정보 없음")
                    focus.trainNumber != null -> add(buildString {
                        append("열차 ${focus.trainNumber}")
                        if (focus.express) append(" 급행")
                        if (focus.lastTrain) append(" 막차")
                    })
                    source == FeedSource.ESTIMATE -> add("배차 간격 기준 추정")
                }
            }.joinToString(" • ")
            return BoardDisplay(
                pill = pill,
                cornerLabel = corner,
                destination = focus?.destination?.let { "${it}행" } ?: "${path.terminus.name}행",
                detail = detail,
                trainNumber = focus?.trainNumber,
                value = if (focus == null) "-" else value,
                unit = if (focus == null) "" else unit,
                valueIsUrgent = focus != null && seconds <= 5 && unit == "초",
                switched = frame.justSwitched,
                trainKey = focus?.arrivalAtMs ?: 0L,
                noService = focus == null,
            )
        }
    }
}

private fun BoardFrame.secondsToFocusOf(train: Approach): Int {
    if (train === focus) return secondsToFocus
    val reference = focus ?: return 0
    return secondsToFocus + ((train.arrivalAtMs - reference.arrivalAtMs) / 1000).toInt()
}

/** What the arrival color page says. */
data class PlatformDisplay(
    val destination: String,
    val trainNumber: String?,
    val closing: Boolean,
    val nextLine: String,
    val trainKey: Long,
)

fun platformDisplayOf(frame: BoardFrame, path: BoardPath, nowMs: Long): PlatformDisplay? {
    val train = frame.atPlatform ?: return null
    val closing = frame.platformPhase == Phase.CLOSING
    val next = frame.following ?: return PlatformDisplay("${train.destination}행", train.trainNumber, closing, "", train.arrivalAtMs)
    val seconds = ((next.arrivalAtMs - nowMs) / 1000).toInt().coerceAtLeast(0)
    val (value, unit) = countdownParts(seconds)
    val nextLine = buildString {
        append("다음 열차")
        next.trainNumber?.let { append(" $it") }
        append(" · $value$unit 후")
    }
    return PlatformDisplay("${train.destination}행", train.trainNumber, closing, nextLine, train.arrivalAtMs)
}
