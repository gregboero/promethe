package dev.promethe.core

/** LAB heuristic only; callers supply distinct observations and a trusted remaining-work estimate. */
class HarnessExposurePolicy(
    private val minimumObservationBytes: Int = 1024,
    private val minimumLargeObservations: Int = 2,
    private val minimumRemainingObservations: Int = 4,
    private val estimatedExposureBytesPerCall: Int = 7_000,
    private val mutationSetupCalls: Int = 4,
) {
    init {
        require(minimumObservationBytes > 0 && minimumLargeObservations > 0 && minimumRemainingObservations > 0)
        require(estimatedExposureBytesPerCall >= 0 && mutationSetupCalls >= 0)
    }

    fun shouldExpose(
        observedByteSizes: List<Int>,
        remainingObservations: Int,
    ): Boolean {
        require(remainingObservations >= 0 && observedByteSizes.all { it >= 0 })
        if (remainingObservations < minimumRemainingObservations ||
            observedByteSizes.count { it >= minimumObservationBytes } < minimumLargeObservations
        ) {
            return false
        }
        // Optimistic bound: each future observation could disappear from every later request.
        // The 7 KB default rounds up the measured Koog tool schemas + prompt overhead (6,962 B).
        // This omits retained setup history, output tokens and compilation: eligibility, not profit.
        val remaining = remainingObservations.toDouble()
        val removableContextBytes = observedByteSizes.average() * remaining * (remaining + 1) / 2
        val exposureBytes = estimatedExposureBytesPerCall.toDouble() * (remaining + mutationSetupCalls + 1)
        return removableContextBytes > exposureBytes
    }
}
