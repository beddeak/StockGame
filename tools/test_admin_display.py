import unittest
from decimal import Decimal
from admin_panel import display_number, load_settings


class AdminDisplayTest(unittest.TestCase):
    def test_default_money_and_percent_have_no_decimal_part(self):
        settings = load_settings()["display"]
        self.assertEqual("34,639", display_number("34638.68", settings["money_decimals"]))
        self.assertEqual("-19", display_number("-18.98", settings["percent_decimals"], signed=True))
        self.assertEqual("+2", display_number("1.5", 0, signed=True))

    def test_display_rounding_preserves_original_value(self):
        original = Decimal("-13690.50")
        self.assertEqual("-13,691", display_number(original, 0))
        self.assertEqual(Decimal("-13690.50"), original)
        self.assertEqual("-13,690.50", display_number(original, 2))

    def test_optional_decimal_setting_and_zero(self):
        self.assertEqual("0", display_number(0, 0))
        self.assertEqual("+0", display_number(0, 0, signed=True))
        self.assertEqual("1,234.50", display_number("1234.5", 2))


if __name__ == "__main__":
    unittest.main()
