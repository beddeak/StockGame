import unittest
from unittest.mock import Mock
from admin_panel import confirmed_reset, initial_cash_value, interval_value, load_settings

class IntervalInputTest(unittest.TestCase):
    def setUp(self):
        self.words = load_settings()["text"]
    def test_arbitrary_numbers_and_fractions(self):
        for value in ("0", "300", "600", "1200", "1.5", "0.05", "86400"):
            self.assertEqual(float(value), float(interval_value(value, 86400, self.words)))
    def test_invalid_or_command_input_rejected(self):
        for value in ("", "NaN", "Infinity", "-1", "600\nstock stop", "abc", "86401"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                interval_value(value, 86400, self.words)
    def test_maximum_from_server(self):
        with self.assertRaises(ValueError):
            interval_value("601", 600, self.words)

class InitialCashInputTest(unittest.TestCase):
    def setUp(self):
        settings = load_settings()
        self.words = settings["text"]
        self.maximum = settings["defaults"]["maximum_initial_cash"]

    def test_nonnegative_yuan_and_cents(self):
        for raw, expected in (("0", "0.00"), ("1000000", "1000000.00"), ("0.01", "0.01"), ("1.5", "1.50"), (" .50 ", "0.50"), ("1000000000000", "1000000000000.00")):
            with self.subTest(raw=raw):
                self.assertEqual(expected, initial_cash_value(raw, self.maximum, self.words))

    def test_nonfinite_negative_or_excess_precision_rejected(self):
        for raw in ("", "NaN", "Infinity", "-Infinity", "-1", "-0.01", "1.001", "0.000", "abc"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                initial_cash_value(raw, self.maximum, self.words)

    def test_money_maximum_rejected(self):
        for raw in ("1000000000000.01", "1000000000001"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                initial_cash_value(raw, self.maximum, self.words)

    def test_console_injection_or_special_numeric_forms_rejected(self):
        for raw in ("600\nstock reset confirm", "1; stock stop", "1\r\nstop", "1 000", "1_000", "1e6", "１００"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                initial_cash_value(raw, self.maximum, self.words)

class ResetConfirmationTest(unittest.TestCase):
    def setUp(self):
        self.words = load_settings()["text"]

    def test_cancel_never_enqueues_reset(self):
        confirm = Mock(return_value=False)
        enqueue = Mock()
        self.assertFalse(confirmed_reset(confirm, enqueue, self.words))
        confirm.assert_called_once_with(self.words["reset_title"], self.words["reset_confirm"])
        enqueue.assert_not_called()

    def test_only_explicit_confirmation_enqueues_one_reset(self):
        confirm = Mock(return_value=True)
        enqueue = Mock()
        self.assertTrue(confirmed_reset(confirm, enqueue, self.words))
        enqueue.assert_called_once_with("stock reset confirm")

if __name__ == "__main__":
    unittest.main()
