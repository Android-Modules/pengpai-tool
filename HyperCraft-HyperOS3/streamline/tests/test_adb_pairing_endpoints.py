"""Execute production endpoint resolution without connecting to any device."""
from pathlib import Path
import subprocess
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / "src/main/assets/backend/adclean.sh").read_text()
ENDPOINTS = "adb_valid_port()" + SOURCE.split("adb_valid_port()", 1)[1].split("cmd_adb_pair_start()", 1)[0]


class AdbPairingEndpointsTest(unittest.TestCase):
    def shell(self, api="39111", prop="", enabled="1", ip="192.168.50.31", api_ok=True):
        fixture = f'''
adb_pair_control() {{ [ "$1" = port ] || exit 70; printf '%s' '{api}'; return {0 if api_ok else 1}; }}
getprop() {{ [ "$1" = service.adb.tls.port ] || exit 71; printf '%s' '{prop}'; }}
settings() {{ [ "$1 $2 $3" = 'get global adb_wifi_enabled' ] || exit 72; echo '{enabled}'; }}
ip() {{ [ -z '{ip}' ] || echo '1: wlan0 inet {ip}/24 scope global wlan0'; }}
'''
        return subprocess.run(["sh"], input=ENDPOINTS + fixture + "\ncmd_adb_connect_address\n",
                              capture_output=True, text=True, timeout=10)

    def test_framework_port_is_preferred_over_property_without_pairing_side_effects(self):
        result = self.shell(prop="43210")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "192.168.50.31:39111\n")

    def test_property_fallback_when_framework_api_is_unavailable(self):
        for api, api_ok in (("", False), ("39111", False), ("-1", True), ("0", True)):
            with self.subTest(api=api):
                result = self.shell(api=api, prop="43210", api_ok=api_ok)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "192.168.50.31:43210\n")

    def test_missing_or_invalid_connection_port_never_produces_partial_endpoint(self):
        for value in ("", "-1", "0", "65536", "not-ready", "9999999999999999999999999"):
            with self.subTest(port=value):
                result = self.shell(api=value, prop=value)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")

    def test_disabled_wireless_or_missing_wlan_never_uses_stale_port(self):
        for kwargs in ({"enabled": "0"}, {"enabled": "null"}, {"ip": ""}):
            with self.subTest(kwargs=kwargs):
                result = self.shell(**kwargs)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
