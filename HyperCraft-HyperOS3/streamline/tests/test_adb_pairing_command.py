"""Execute the production root pairing entry against named framework API recorders."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "src/main/java/com/dalong/pengpaitool/AdbPairingCommand.java"
CODE = "789123"
STUBS = {
    "android/os/Process.java": """package android.os;
public final class Process { public static int myUid() { return Integer.getInteger("fixture.uid", 0); } }
""",
    "android/os/IBinder.java": "package android.os; public interface IBinder {}\n",
    "fixture/Recorder.java": """package fixture;
import java.nio.file.Files;import java.nio.file.Path;import java.nio.file.StandardOpenOption;
public final class Recorder {
    public static void record(String value) {
        try { Files.writeString(Path.of(System.getProperty("fixture.calls")), value+"\\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
""",
    "android/os/ServiceManager.java": """package android.os;
public final class ServiceManager {
    public static IBinder getService(String name) {
        if (!"adb".equals(name)) throw new AssertionError("Unexpected service");
        fixture.Recorder.record("lookup:adb");
        return Boolean.getBoolean("fixture.missing") ? null : new IBinder() {};
    }
}
""",
    "android/debug/IAdbManager.java": """package android.debug;
import android.os.IBinder;
public interface IAdbManager {
    void disablePairing();
    void enablePairingByQrCode(String name, String code);
    String getAdbWirelessHostName();
    int getAdbWirelessPort();
    final class Stub {
        public static IAdbManager asInterface(IBinder binder) {
            fixture.Recorder.record("asInterface");
            if (binder == null) return null;
            return new IAdbManager() {
                public void disablePairing() {
                    fixture.Recorder.record("disablePairing");
                    failIfRequested();
                }
                public void enablePairingByQrCode(String name, String code) {
                    if (!"HyperCraft".equals(name) || !System.getProperty("fixture.code").equals(code))
                        throw new AssertionError("Pairing arguments changed");
                    fixture.Recorder.record("enablePairingByQrCode");
                    failIfRequested();
                }
                public String getAdbWirelessHostName() {
                    throw new AssertionError("Wrong Android 17 operation");
                }
                public int getAdbWirelessPort() {
                    fixture.Recorder.record("getAdbWirelessPort");
                    failIfRequested();
                    return Integer.getInteger("fixture.port", 39111);
                }
                private void failIfRequested() {
                    if (Boolean.getBoolean("fixture.throw"))
                        throw new IllegalStateException("Binder payload secret " + System.getProperty("fixture.code"));
                }
            };
        }
    }
}
""",
}


class AdbPairingCommandTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="hypercraft-pairing-command-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        sources = []
        for name, content in STUBS.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else "java"
        javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(cls.root),
                        *sources, str(SOURCE)], check=True, capture_output=True, text=True)

    def invoke(self, args, uid=0, missing=False, throws=False, port=39111, expected_out=""):
        calls = self.root / "calls.txt"
        calls.write_text("")
        result = subprocess.run([
            self.java, f"-Dfixture.calls={calls}", f"-Dfixture.uid={uid}",
            f"-Dfixture.missing={str(missing).lower()}", f"-Dfixture.throw={str(throws).lower()}",
            f"-Dfixture.port={port}",
            f"-Dfixture.code={CODE}", "-cp", str(self.root),
            "com.dalong.pengpaitool.AdbPairingCommand", *args,
        ], capture_output=True, text=True, timeout=10)
        self.assertNotIn(CODE, result.stdout + result.stderr)
        self.assertNotIn("Binder payload secret", result.stdout + result.stderr)
        self.assertEqual(expected_out, result.stdout)
        return result, calls.read_text().splitlines()

    def test_start_calls_named_qr_method_with_exact_arguments_and_no_output(self):
        result, calls = self.invoke(["start", CODE])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertEqual(["lookup:adb", "asInterface", "enablePairingByQrCode"], calls)

    def test_stop_calls_disable_pairing_not_android17_hostname_method(self):
        result, calls = self.invoke(["stop"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertEqual(["lookup:adb", "asInterface", "disablePairing"], calls)

    def test_nonroot_rejected_before_service_lookup(self):
        for args in (["start", CODE], ["stop"], ["port"]):
            with self.subTest(args=args[0]):
                result, calls = self.invoke(args, uid=2000)
                self.assertEqual(1, result.returncode)
                self.assertEqual([], calls)
                self.assertEqual("Pairing control failed: SecurityException\n", result.stderr)

    def test_invalid_arguments_rejected_before_service_lookup(self):
        for args in ([], ["start"], ["start", "12345"], ["start", "1234567"],
                     ["start", "12x456"], ["stop", CODE], ["unknown"], ["start", CODE, "extra"]):
            with self.subTest(args=args):
                result, calls = self.invoke(args)
                self.assertEqual(1, result.returncode)
                self.assertEqual([], calls)
                self.assertEqual("Pairing control failed: IllegalArgumentException\n", result.stderr)

    def test_missing_service_fails_without_invoking_pairing(self):
        result, calls = self.invoke(["start", CODE], missing=True)
        self.assertEqual(1, result.returncode)
        self.assertEqual(["lookup:adb", "asInterface"], calls)
        self.assertEqual("Pairing control failed: IllegalStateException\n", result.stderr)

    def test_binder_failure_never_exposes_exception_payload_or_pairing_code(self):
        for args, method in ((["start", CODE], "enablePairingByQrCode"), (["stop"], "disablePairing"), (["port"], "getAdbWirelessPort")):
            with self.subTest(method=method):
                result, calls = self.invoke(args, throws=True)
                self.assertEqual(1, result.returncode)
                self.assertEqual(["lookup:adb", "asInterface", method], calls)
                self.assertEqual("Pairing control failed: InvocationTargetException\n", result.stderr)

    def test_connection_port_reads_named_method_without_toggling_pairing(self):
        result, calls = self.invoke(["port"], expected_out="39111\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["lookup:adb", "asInterface", "getAdbWirelessPort"], calls)

    def test_invalid_connection_port_never_prints_a_partial_address(self):
        for port in (-1, 0, 65536):
            with self.subTest(port=port):
                result, calls = self.invoke(["port"], port=port)
                self.assertEqual(1, result.returncode)
                self.assertEqual(["lookup:adb", "asInterface", "getAdbWirelessPort"], calls)


if __name__ == "__main__":
    unittest.main()
