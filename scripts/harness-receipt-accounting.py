"""Account for legacy settled receipts and v2 uncertain attempts without erasing uncertainty."""


def summarize(receipts):
    settled = [r for r in receipts if r.get("accountedUpperBoundMicroUsd") is not None
               and r.get("accountingState", "settled") == "settled"]
    uncertain = [r for r in receipts if r not in settled]
    unknown = [r for r in receipts if r.get("inputTokens") is None or r.get("outputTokens") is None]
    return {
        "attemptedCalls": len(receipts),
        "settledCalls": len(settled),
        "uncertainCalls": len(uncertain),
        "unknownUsageCalls": len(unknown),
        "usageComplete": not unknown,
        # Numeric sums contain known usage only; consumers must consult usageComplete.
        "inputTokens": sum(r["inputTokens"] for r in receipts if r.get("inputTokens") is not None),
        "outputTokens": sum(r["outputTokens"] for r in receipts if r.get("outputTokens") is not None),
        "costUpperBoundMicroUsd": sum(r["accountedUpperBoundMicroUsd"] for r in settled),
        "uncertainReservationMicroUsd": sum(r["reservationMicroUsd"] for r in uncertain),
    }
