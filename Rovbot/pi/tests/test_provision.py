"""Host-independent checks; do not execute any provisioning actions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[1] / "provision_pi.sh"


class ProvisionTests(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run(
            ["bash", str(SCRIPT), *args], text=True, capture_output=True, check=False
        )

    def test_help_needs_no_root_or_pi(self):
        result = self.run_script("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--arduino-device", result.stdout)

    def test_rejects_invalid_configuration_before_writing(self):
        cases = [
            ("--user", "root"), ("--user", "robot\nExecStart=bad"),
            ("--domain-id", "102"), ("--domain-id", "08"),
            ("--domain-id", "1+2"), ("--workspace", "/tmp/$(bad)"),
            ("--workspace", "relative"), ("--dds-peer", 'host"/><bad>'),
            ("--unknown", "x"), ("--user",),
        ]
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / "preview"
            for args in cases:
                with self.subTest(args=args):
                    result = self.run_script("--render-dir", str(dest), *args)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(dest.exists())

    def test_preview_is_deterministic_and_generated_shell_parses(self):
        with tempfile.TemporaryDirectory() as temp:
            outputs = []
            for name in ("first", "second"):
                dest = Path(temp) / name
                result = self.run_script("--render-dir", str(dest))
                self.assertEqual(result.returncode, 0, result.stderr)
                outputs.append({p.relative_to(dest): p.read_bytes()
                                for p in dest.rglob("*") if p.is_file()})
                for relative in ("etc/rovbot/setup.bash", "etc/rovbot/environment",
                                 "usr/local/libexec/rovbot-start",
                                 "usr/local/libexec/rovbot-performance"):
                    subprocess.run(["bash", "-n", str(dest / relative)], check=True)
                self.assertTrue(os.access(dest / "usr/local/libexec/rovbot-start", os.X_OK))
                self.assertFalse((dest / "etc/udev").exists())
                self.assertFalse(list(dest.rglob("*.wants")))
            self.assertEqual(outputs[0], outputs[1])

    def test_custom_environment_and_unicast_discovery(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / "preview"
            result = self.run_script(
                "--render-dir", str(dest), "--user", "robot", "--domain-id", "7",
                "--workspace", "/srv/rovbot/ros2_ws", "--dds-peer", "192.168.1.50",
                "--dds-peer", "workstation.local")
            self.assertEqual(result.returncode, 0, result.stderr)
            env = (dest / "etc/rovbot/environment").read_text()
            self.assertIn("ROS_DOMAIN_ID=7\n", env)
            self.assertIn("ROVBOT_WORKSPACE=/srv/rovbot/ros2_ws\n", env)
            root = ET.parse(dest / "etc/rovbot/cyclonedds.xml")
            ns = {"c": "https://cdds.io/config"}
            self.assertEqual(root.find(".//c:AllowMulticast", ns).text, "false")
            self.assertEqual([p.attrib["Address"] for p in root.findall(".//c:Peer", ns)],
                             ["127.0.0.1", "192.168.1.50", "workstation.local"])
            service = (dest / "etc/systemd/system/rovbot.service").read_text()
            self.assertIn("User=robot\n", service)
            self.assertIn("LimitRTPRIO=50\n", service)
            self.assertIn("LimitMEMLOCK=infinity\n", service)

    def test_preview_refuses_to_overwrite_existing_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            marker = Path(temp) / "keep"
            marker.write_text("original")
            self.assertNotEqual(self.run_script("--render-dir", temp).returncode, 0)
            self.assertEqual(marker.read_text(), "original")

    def test_preview_cannot_query_hardware(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / "preview"
            result = self.run_script("--render-dir", str(dest), "--arduino-device", "/dev/ttyACM0")
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(dest.exists())

    def test_usb_rule_matches_only_selected_board_and_rejects_injection(self):
        for pid, serial, valid in [
            ("1002", "ABC123", True), ("006d", "ABC-123", True),
            ("0369", "ABC123", False), ("1002", "", False),
            ("1002", 'bad", RUN+="bad', False),
        ]:
            with self.subTest(pid=pid, serial=serial):
                result = subprocess.run(
                    ["bash", "-c", 'source "$1"; write_udev_rule "$2" "$3"',
                     "test", str(SCRIPT), pid, serial],
                    text=True, capture_output=True, check=False)
                self.assertEqual(result.returncode == 0, valid, result.stderr)
                if valid:
                    self.assertIn(f'ATTRS{{serial}}=="{serial}"', result.stdout)
                    self.assertIn('ENV{ID_MM_DEVICE_IGNORE}="1"', result.stdout)
                    self.assertEqual(result.stdout.count('SYMLINK+='), 1)


if __name__ == "__main__":
    unittest.main()
