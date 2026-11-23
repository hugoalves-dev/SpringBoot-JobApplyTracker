"""Pure tests for the one-time data migration (no database required)."""

import importlib.util
import unittest
from pathlib import Path
from uuid import UUID

PATH = Path(__file__).resolve().parents[1] / "scripts" / "migrate_mariadb_to_postgresql.py"
spec = importlib.util.spec_from_file_location("applywell_migrator", PATH)
m = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = m
spec.loader.exec_module(m)


class MigratorTests(unittest.TestCase):
    def test_uuid_binary_roundtrip_and_invalid_width(self):
        original = UUID("12345678-1234-5678-9abc-def012345678")
        self.assertEqual(m.convert(original.bytes, "uuid"), original)
        self.assertEqual(m.convert(str(original), "uuid"), original)
        with self.assertRaises(ValueError):
            m.convert(b"1234", "uuid")

    def test_boolean_strict(self):
        for raw in (0, b"\x00", False):
            self.assertIs(m.convert(raw, "bool"), False)
        for raw in (1, b"\x01", True):
            self.assertIs(m.convert(raw, "bool"), True)
        with self.assertRaises(ValueError):
            m.convert(2, "bool")

    def test_oauth_bytes_never_silently_corrupted(self):
        self.assertEqual(m.convert(b'{"a":1}', "text"), '{"a":1}')
        with self.assertRaises(UnicodeDecodeError):
            m.convert(b"\xff", "text")

    def test_content_fingerprint_is_order_independent(self):
        a, b = m.Fingerprint(), m.Fingerprint()
        data = [("abc", True, 12), ("def", False, 13)]
        for row in data:
            a.add(row)
        for row in reversed(data):
            b.add(row)
        self.assertEqual(a.summary(), b.summary())
        b.add(data[0])
        self.assertNotEqual(a.summary(), b.summary())

    def test_foreign_key_order(self):
        order = m.dependency_order({"users", "job_applications", "refresh_tokens"},
                                   [("job_applications", "users"), ("refresh_tokens", "users")])
        self.assertEqual(order[0], "users")
        with self.assertRaises(ValueError):
            m.dependency_order({"a", "b"}, [("a", "b"), ("b", "a")])

    def test_mariadb_time_timedelta_becomes_time_of_day(self):
        from datetime import time, timedelta
        value = m.convert(timedelta(hours=19, minutes=30, seconds=5, microseconds=12), "time")
        self.assertEqual(value, time(19, 30, 5, 12))
        self.assertEqual(m.canonical(value), ["temporal", "19:30:05.000012"])
        with self.assertRaises(ValueError):
            m.convert(timedelta(hours=25), "time")
        with self.assertRaises(ValueError):
            m.convert(timedelta(seconds=-1), "time")

    def test_schema_mismatch_fails_closed(self):
        with self.assertRaises(ValueError):
            m.ensure_schema({"a"}, {"a": {"id"}}, {"b"}, {"b": [m.Column("id", "uuid")]})
        with self.assertRaises(ValueError):
            m.ensure_schema({"a"}, {"a": {"id", "secret"}},
                            {"a"}, {"a": [m.Column("id", "uuid")]})


if __name__ == "__main__":
    unittest.main()
