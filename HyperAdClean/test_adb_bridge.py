"""Compile and exercise the real bridge parser without connecting a phone."""
import os
import pathlib
import subprocess
import tempfile
import unittest


BRIDGE = pathlib.Path(__file__).resolve().parents[1] / (
    "HyperAdCleanApp/app/src/main/java/com/dalong/pengpaitool/AdbBridge.java"
)


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        root = pathlib.Path(cls.tmp.name)
        stub = root / "android/os/IBinder.java"
        stub.parent.mkdir(parents=True)
        stub.write_text("package android.os; public interface IBinder {}")
        harness = root / "BridgeHarness.java"
        harness.write_text('''
import java.lang.reflect.Method;
import java.util.Collections;
import com.dalong.pengpaitool.AdbBridge;
public class BridgeHarness {
    public static class PairDevice {
        public String name = "Laptop|name\\nline\\r";
        public String guid = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
        public boolean connected = true;
    }
    public static class FingerprintAndPairDevice {
        public String keyFingerprint = "SECRET_PUBLIC_KEY user@host";
        public PairDevice device = new PairDevice();
    }
    public static void main(String[] args) throws Exception {
        Object response;
        if (args[0].equals("modern"))
            response = new FingerprintAndPairDevice[]{new FingerprintAndPairDevice()};
        else if (args[0].equals("legacy"))
            response = Collections.singletonMap("SECRET_PUBLIC_KEY", new PairDevice());
        else response = new FingerprintAndPairDevice[0];
        Method list = AdbBridge.class.getDeclaredMethod("list", Object.class);
        list.setAccessible(true);
        list.invoke(null, response);
    }
}
''')
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(pathlib.Path(java_home) / "bin/java") if java_home else "java"
        javac = str(pathlib.Path(java_home) / "bin/javac") if java_home else "javac"
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(root),
                        str(stub), str(BRIDGE), str(harness)], check=True,
                       capture_output=True, text=True)

    def parse(self, shape):
        return subprocess.run(
            [self.java, "-cp", self.tmp.name, "BridgeHarness", shape],
            check=True, capture_output=True, text=True,
        ).stdout

    def test_framework_array_uses_device_guid_not_public_key(self):
        row = self.parse("modern").strip().split("|")
        self.assertEqual(row, ["DEVICE", "Laptop name line ",
                              "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99", "true"])
        self.assertRegex(row[2], r"^[0-9A-Fa-f:]{16,128}$")

    def test_legacy_map_does_not_expose_public_key(self):
        output = self.parse("legacy")
        self.assertEqual(output, "DEVICE|Laptop name line |"
                         "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99|true\n")
        self.assertNotIn("SECRET_PUBLIC_KEY", output)

    def test_empty_framework_array(self):
        self.assertEqual(self.parse("empty"), "")


if __name__ == "__main__":
    unittest.main()
