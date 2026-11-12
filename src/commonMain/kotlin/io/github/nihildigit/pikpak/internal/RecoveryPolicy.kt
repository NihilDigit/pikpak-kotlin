package io.github.nihildigit.pikpak.internal

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure ETA comparison policy for mid-stream path recovery and multi-block continuation vs
 * cancellation/repositioning.
 *
 * For remaining finite urgent bytes `G - F`:
 * - `T_keep = (G - F) / R_a`
 * - `T_replace = C + Q_b + L_b + (G - F) / R_b`
 *
 * Replacement qualifies only when:
 * `T_keep - T_replace >= max(MIN_ADVANTAGE_MS, MIN_ADVANTAGE_RATIO * T_keep)`.
 */
internal object RecoveryPolicy {
    const val MIN_ADVANTAGE_MS: Long = 500L
    const val MIN_ADVANTAGE_RATIO: Double = 0.25
    const val DEFAULT_SWITCH_OVERHEAD_MS: Long = 50L
    const val REQUIRED_CONSECUTIVE_OBSERVATIONS: Int = 2
    const val MIN_SHORT_WINDOW_MS: Long = 250L
    const val UNKNOWN_RATE_REPOSITION_GAP_BYTES: Long = 512L * 1024L

    data class CandidateEstimate(
        val hostKey: String,
        val url: String,
        val ttfbMillis: Long,
        val bodyBytesPerSecond: Long,
    )

    data class EtaComparison(
        val keepEtaMillis: Long,
        val replaceEtaMillis: Long,
        val advantageMillis: Long,
        val requiredAdvantageMillis: Long,
        val qualifies: Boolean,
    )

    data class SwitchoverDecision(
        val nextConsecutiveQualifying: Int,
        val shouldSwitch: Boolean,
        val candidate: CandidateEstimate?,
        val comparison: EtaComparison?,
    )

    data class ContinuationDecision(
        val shouldReposition: Boolean,
        val keepEtaMillis: Long?,
        val repositionEtaMillis: Long?,
        val advantageMillis: Long?,
    )

    fun compareEta(
        remainingBytes: Long,
        currentBytesPerSecond: Long,
        candidateTtfbMillis: Long,
        candidateBytesPerSecond: Long,
        switchOverheadMillis: Long = DEFAULT_SWITCH_OVERHEAD_MS,
        admissionQueueMillis: Long = 0L,
    ): EtaComparison {
        if (remainingBytes <= 0L ||
            currentBytesPerSecond <= 0L ||
            candidateBytesPerSecond <= 0L ||
            candidateTtfbMillis < 0L ||
            switchOverheadMillis < 0L ||
            admissionQueueMillis < 0L
        ) {
            return EtaComparison(
                keepEtaMillis = 0L,
                replaceEtaMillis = 0L,
                advantageMillis = 0L,
                requiredAdvantageMillis = MIN_ADVANTAGE_MS,
                qualifies = false,
            )
        }
        val keepMs = (remainingBytes.toDouble() * 1000.0) / currentBytesPerSecond.toDouble()
        val replaceTransferMs = (remainingBytes.toDouble() * 1000.0) / candidateBytesPerSecond.toDouble()
        val replaceMs = switchOverheadMillis.toDouble() +
            admissionQueueMillis.toDouble() +
            candidateTtfbMillis.toDouble() +
            replaceTransferMs
        val advantageMs = keepMs - replaceMs
        val requiredMs = max(MIN_ADVANTAGE_MS.toDouble(), MIN_ADVANTAGE_RATIO * keepMs)
        val qualifies = candidateBytesPerSecond > currentBytesPerSecond && advantageMs >= requiredMs
        return EtaComparison(
            keepEtaMillis = keepMs.roundToLong(),
            replaceEtaMillis = replaceMs.roundToLong(),
            advantageMillis = advantageMs.roundToLong(),
            requiredAdvantageMillis = requiredMs.roundToLong(),
            qualifies = qualifies,
        )
    }

    /**
     * Evaluates `RangeReader` same-offset host switchover at a safe point (`writeFully + flush`
     * already completed at [forwardedOffset]).
     */
    fun evaluateSameOffsetSwitchover(
        forwardedOffset: Long,
        goalEndOffset: Long,
        goalRevision: Long,
        consumedGoalRevision: Long,
        currentUrl: String,
        currentShortWindowBytesPerSecond: Long,
        hasSinkBackpressure: Boolean,
        candidate: CandidateEstimate?,
        previousConsecutiveQualifying: Int,
        switchOverheadMillis: Long = DEFAULT_SWITCH_OVERHEAD_MS,
        admissionQueueMillis: Long = 0L,
    ): SwitchoverDecision {
        val remainingBytes = goalEndOffset - forwardedOffset
        if (remainingBytes <= 0L ||
            goalEndOffset <= 0L ||
            goalRevision == consumedGoalRevision ||
            hasSinkBackpressure ||
            currentShortWindowBytesPerSecond <= 0L ||
            candidate == null ||
            candidate.url == currentUrl ||
            candidate.bodyBytesPerSecond <= 0L ||
            candidate.ttfbMillis < 0L
        ) {
            return SwitchoverDecision(
                nextConsecutiveQualifying = 0,
                shouldSwitch = false,
                candidate = null,
                comparison = null,
            )
        }

        val comparison = compareEta(
            remainingBytes = remainingBytes,
            currentBytesPerSecond = currentShortWindowBytesPerSecond,
            candidateTtfbMillis = candidate.ttfbMillis,
            candidateBytesPerSecond = candidate.bodyBytesPerSecond,
            switchOverheadMillis = switchOverheadMillis,
            admissionQueueMillis = admissionQueueMillis,
        )
        if (!comparison.qualifies) {
            return SwitchoverDecision(
                nextConsecutiveQualifying = 0,
                shouldSwitch = false,
                candidate = candidate,
                comparison = comparison,
            )
        }

        val nextCount = previousConsecutiveQualifying + 1
        val shouldSwitch = nextCount >= REQUIRED_CONSECUTIVE_OBSERVATIONS
        return SwitchoverDecision(
            nextConsecutiveQualifying = nextCount,
            shouldSwitch = shouldSwitch,
            candidate = candidate,
            comparison = comparison,
        )
    }

    /**
     * Evaluates whether `BlockCache` should keep an in-flight multi-block `Fetch` running through
     * [interveningBytes] to reach a later `Required` byte range, or cancel & reposition (`shouldReposition = true`).
     *
     * - Continuing transfers `interveningBytes + urgentRequiredBytes` on the already-open stream at [inFlightBytesPerSecond].
     * - Repositioning cancels the stream and pays `switchOverheadMillis + admissionQueueMillis + repositionTtfbMillis`
     *   plus transferring `urgentRequiredBytes` at [repositionBytesPerSecond].
     */
    fun evaluateInFlightContinuationOrReposition(
        interveningBytes: Long,
        urgentRequiredBytes: Long = 0L,
        inFlightBytesPerSecond: Long?,
        repositionTtfbMillis: Long = 1_100L,
        repositionBytesPerSecond: Long? = inFlightBytesPerSecond,
        switchOverheadMillis: Long = DEFAULT_SWITCH_OVERHEAD_MS,
        admissionQueueMillis: Long = 0L,
        unknownRateGapThresholdBytes: Long = UNKNOWN_RATE_REPOSITION_GAP_BYTES,
    ): ContinuationDecision {
        if (interveningBytes <= 0L) {
            return ContinuationDecision(
                shouldReposition = false,
                keepEtaMillis = 0L,
                repositionEtaMillis = null,
                advantageMillis = null,
            )
        }
        val activeRate = inFlightBytesPerSecond?.takeIf { it > 0L }
        if (activeRate == null) {
            return ContinuationDecision(
                shouldReposition = interveningBytes >= unknownRateGapThresholdBytes,
                keepEtaMillis = null,
                repositionEtaMillis = null,
                advantageMillis = null,
            )
        }
        val targetRate = (repositionBytesPerSecond?.takeIf { it > 0L } ?: activeRate)
        val clampedUrgent = urgentRequiredBytes.coerceAtLeast(0L)
        val keepMs = ((interveningBytes + clampedUrgent).toDouble() * 1000.0) / activeRate.toDouble()
        val repositionTransferMs = (clampedUrgent.toDouble() * 1000.0) / targetRate.toDouble()
        val repositionMs = switchOverheadMillis.coerceAtLeast(0L).toDouble() +
            admissionQueueMillis.coerceAtLeast(0L).toDouble() +
            repositionTtfbMillis.coerceAtLeast(0L).toDouble() +
            repositionTransferMs
        val advantageMs = keepMs - repositionMs
        val requiredMs = max(MIN_ADVANTAGE_MS.toDouble(), MIN_ADVANTAGE_RATIO * keepMs)
        return ContinuationDecision(
            shouldReposition = advantageMs >= requiredMs,
            keepEtaMillis = keepMs.roundToLong(),
            repositionEtaMillis = repositionMs.roundToLong(),
            advantageMillis = advantageMs.roundToLong(),
        )
    }
}
