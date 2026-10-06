"""Run the signed combined app on an already booted emulator; only standard Python is required."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--apk", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--serial", default="emulator-5554")
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
package = "io.github.lj5201lj.xianzhun"
checks = []


def adb(*command, binary=False):
    result = subprocess.run([args.adb, "-s", args.serial, *command], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def snapshot(name=None):
    result = adb("shell", "uiautomator", "dump", "/sdcard/xianzhun-native-qa.xml")
    assert "dumped to" in result, f"UI dump failed: {result}"
    data = adb("exec-out", "cat", "/sdcard/xianzhun-native-qa.xml")
    if name:
        (out / f"{name}.xml").write_text(data, encoding="utf-8")
        (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return ET.fromstring(data)


def find(tree, text=None, desc=None, klass=None):
    for node in tree.iter("node"):
        if text is not None and node.get("text") != text:
            continue
        if desc is not None and desc not in node.get("content-desc", ""):
            continue
        if klass is not None and node.get("class") != klass:
            continue
        return node
    raise AssertionError(f"Missing UI control text={text!r}, desc={desc!r}, class={klass!r}")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    assert x2 > x1 and y2 > y1, "Control is outside visible screen"
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def check(name, condition=True):
    assert condition, name
    checks.append(name)
    print("PASS", name, flush=True)


def focus_owned():
    data = adb("shell", "dumpsys", "audio")
    # Event histories may mention old focus owners; inspect just the current focus stack.
    block = data.split("Audio Focus stack entries", 1)[-1].split("No external focus policy", 1)[0]
    block = block.split("External focus policy", 1)[0]
    return package in block, data


adb("install", "-r", args.apk)
adb("shell", "am", "force-stop", package)
adb("shell", "am", "start", "-n", f"{package}/.MainActivity")
time.sleep(0.6)
tree = snapshot("01-tuner")
check("Both tool tabs are visible", find(tree, text="调音器") is not None and find(tree, text="节拍器") is not None)
check("Microphone and all six strings are visible without scrolling", find(tree, text="开始调音") is not None and all(find(tree, desc=f"{n} 弦") is not None for n in range(1, 7)))
check("No manual stop-reference button", all(n.get("text") != "停止参考音" for n in tree.iter("node")))

tap(find(tree, text="开始调音"))
tree = snapshot()
allow = next((n for n in tree.iter("node") if n.get("resource-id") in (
    "com.android.packageinstaller:id/permission_allow_button",
    "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
)), None)
if allow is not None:
    tap(allow)
    tree = snapshot()
check("Native recording starts after permission", find(tree, text="停止调音") is not None)
string6 = find(tree, desc="6 弦")
tap(string6)
owned, audio_dump = focus_owned()
(out / "reference-playing-audio.txt").write_text(audio_dump, encoding="utf-8")
check("Reference tone acquires playback focus", owned)
time.sleep(1.25)
owned, audio_dump = focus_owned()
(out / "reference-ended-audio.txt").write_text(audio_dump, encoding="utf-8")
check("Reference tone stops automatically after one second", not owned)
tree = snapshot("02-reference-ended")
check("Recording remains enabled after reference ends", find(tree, text="停止调音") is not None)
check("Reference speaker marker disappears", all("♪" not in n.get("text", "") for n in tree.iter("node")))

# Replacing a tone cancels the previous tone's timer and any delayed resume.
tap(find(tree, desc="6 弦"))
time.sleep(0.2)
tap(find(tree, desc="1 弦"))
time.sleep(1.6)
tree = snapshot()
check("Quickly changing strings completes without disabling recording", find(tree, text="停止调音") is not None)
tap(find(tree, text="节拍器", klass="android.widget.Button"))
tree = snapshot("03-metronome")
check("Metronome has a visible start button", find(tree, text="开始节拍") is not None)
tap(find(tree, text="开始节拍"))
time.sleep(0.5)
owned, _ = focus_owned()
(out / "04-metronome-playing.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
(out / "metronome-audio-flinger.txt").write_text(adb("shell", "dumpsys", "media.audio_flinger"), encoding="utf-8")
check("Metronome starts audio output", owned)
# Use the known stable layout: changing beat labels does not let UiAutomator become idle.
tap(find(tree, text="+1"))
tap(find(tree, text="开始节拍"))
tree = snapshot()
check("Speed can change while playing", find(tree, text="121") is not None)
tap(find(tree, text="开始节拍"))
tap(find(tree, text="调音器", klass="android.widget.Button"))
tree = snapshot()
owned, _ = focus_owned()
check("Changing page stops metronome and leaves microphone off", not owned and find(tree, text="开始调音") is not None)
tap(find(tree, text="节拍器", klass="android.widget.Button"))
tree = snapshot()
tap(find(tree, text="开始节拍"))
adb("shell", "input", "keyevent", "3")
time.sleep(0.3)
owned, _ = focus_owned()
check("Going to background stops audio", not owned)
adb("shell", "am", "force-stop", package)
adb("shell", "am", "start", "-n", f"{package}/.MainActivity")
tree = snapshot()
tap(find(tree, text="节拍器", klass="android.widget.Button"))
tree = snapshot("05-settings-restored")
check("Metronome settings persist after restart", find(tree, text="121") is not None and find(tree, text="开始节拍") is not None)
pid = adb("shell", "pidof", package).strip()
log = adb("logcat", "-d", "--pid", pid)
(out / "app-logcat.txt").write_text(log, encoding="utf-8")
check("No fatal app crash", "FATAL EXCEPTION" not in log)
report = "# Native 2.1.0 emulator smoke test\n\n" + "\n".join(f"- PASS: {name}" for name in checks)
(out / "smoke-report.md").write_text(report + "\n", encoding="utf-8")
adb("shell", "input", "keyevent", "3")
