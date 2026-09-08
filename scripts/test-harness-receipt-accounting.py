import runpy
from pathlib import Path
import unittest

summarize = runpy.run_path(str(Path(__file__).with_name("harness-receipt-accounting.py")))["summarize"]


class ReceiptAccountingTest(unittest.TestCase):
    def test_mixed_receipts_preserve_uncertainty(self):
        result = summarize([
            dict(inputTokens=100, outputTokens=20, accountedUpperBoundMicroUsd=490),
            dict(inputTokens=None, outputTokens=None, accountedUpperBoundMicroUsd=None,
                 reservationMicroUsd=120000, accountingState="reserved_uncertain"),
            dict(inputTokens=100, outputTokens=20, accountedUpperBoundMicroUsd=490,
                 reservationMicroUsd=120000, accountingState="settled", errorCode="campaign_empty_content"),
        ])
        self.assertEqual((3, 2, 1, 1), tuple(result[k] for k in ("attemptedCalls", "settledCalls", "uncertainCalls", "unknownUsageCalls")))
        self.assertEqual(980, result["costUpperBoundMicroUsd"])
        self.assertEqual(120000, result["uncertainReservationMicroUsd"])
        self.assertFalse(result["usageComplete"])

    def test_known_zero_is_not_missing_usage(self):
        result = summarize([dict(inputTokens=0, outputTokens=0, accountedUpperBoundMicroUsd=0)])
        self.assertTrue(result["usageComplete"])
        self.assertEqual(1, result["settledCalls"])


if __name__ == "__main__":
    unittest.main()
