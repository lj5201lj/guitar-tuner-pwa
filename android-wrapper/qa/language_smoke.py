"""Exercise real Activity recreation, persistence and audio cleanup in both supported languages."""
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
parser.add_argument("--serial", default="emulator-5556")
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
package = "io.github.lj5201lj.xianzhun"
checks = []


def adb(*command, binary=False):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def snapshot(name=None):
    result = adb("shell", "uiautomator", "dump", "/sdcard/xianzhun-language-qa.xml")
    assert "dumped to" in result, result
    data = adb("exec-out", "cat", "/sdcard/xianzhun-language-qa.xml")
    if name:
        (output / f"{name}.xml").write_text(data, encoding="utf-8")
        (output / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return ET.fromstring(data)


def find(tree, text=None, resource=None, klass=None):
    for node in tree.iter("node"):
        if text is not None and node.get("text", "").casefold() != text.casefold():
            continue
        if resource is not None and node.get("resource-id") != f"{package}:id/{resource}":
            continue
        if klass is not None and node.get("class") != klass:
            continue
        return node
    raise AssertionError(f"Missing control: {text or resource or klass}")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    assert x2 > x1 and y2 > y1, "Control is not visible"
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def check(name, passed=True):
    assert passed, name
    checks.append(name)
    print("PASS", name, flush=True)


def choose_language(tree, label):
    tap(find(tree, resource="language_button"))
    dialog = snapshot()
    tap(find(dialog, text=label))
    return snapshot()


def has_app_focus():
    dump = adb("shell", "dumpsys", "audio")
    block = dump.split("Audio Focus stack entries", 1)[-1].split("No external focus policy", 1)[0]
    return package in block.split("External focus policy", 1)[0]


def recording():
    pid = adb('shell', 'pidof', package).strip()
    dump = adb('shell', 'dumpsys', 'media.audio_flinger')
    return any(re.search(r'^\s+yes\s+' + re.escape(pid) + r'\s+', block, re.M)
               for block in dump.split('Input thread ')[1:])


adb("install", "-r", args.apk)
adb("shell", "am", "force-stop", package)
adb("shell", "am", "start", "-n", f"{package}/.MainActivity")
tree = snapshot()
tree = choose_language(tree, "English")
check("English tuner and transport", find(tree, text="Tuner") is not None and find(tree, text="Start tuning") is not None)
snapshot("01-english-tuner")
tree = choose_language(tree, "简体中文")
check("Chinese tuner and presets", find(tree, text="调音器") is not None and find(tree, text="标准调弦") is not None)
snapshot("02-chinese-tuner")
tap(find(tree, text="编辑自定义"))
dialog = snapshot("03-chinese-custom-tuning")
check("Chinese custom-tuning editor", find(dialog, text="自定义调弦（B1–E5）") is not None)
tap(find(dialog, text="取消"))
tree = snapshot()
tap(find(tree, resource="language_button"))
language_dialog = snapshot()
english_option = find(language_dialog, text="English")
tap(find(language_dialog, text="取消"))
tree = snapshot()
tap(find(tree, resource="tuning_toggle"))
if not recording():
    dialog = snapshot()
    if any(n.get("text") == "麦克风使用说明" for n in dialog.iter("node")):
        tap(find(dialog, text="继续"))
    if not recording():
        dialog = snapshot()
        allow = next((node for node in dialog.iter("node") if node.get("resource-id") in (
            "com.android.packageinstaller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
        )), None)
        if allow is not None:
            tap(allow)
check("Chinese microphone starts", recording())
tap(find(tree, resource="language_button"))
tap(english_option)
tree = snapshot()
check("Changing language stops recording", find(tree, text="Start tuning") is not None)
tap(find(tree, text="Custom tuning"))
dialog = snapshot("04-english-custom-tuning")
check("English custom-tuning editor", find(dialog, text="Custom tuning (B1–E5)") is not None and find(dialog, text="Save and use") is not None)
tap(find(dialog, text="Cancel"))
tree = snapshot()
tap(find(tree, klass="android.widget.Spinner"))
dialog = snapshot()
tap(find(dialog, text="Drop D"))
tree = snapshot()
tree = choose_language(tree, "简体中文")
check("Tuning selection survives language changes", find(tree, text="Drop D") is not None)
tree = choose_language(tree, "English")
tap(find(tree, resource="tab_metronome"))
tree = snapshot()
check("English metronome", find(tree, text="Metronome", klass="android.widget.TextView") is not None and find(tree, text="Tap tempo") is not None)
initial_bpm = next(int(n.get("text")) for n in tree.iter("node")
                   if n.get("class") == "android.widget.TextView" and n.get("text", "").isdigit()
                   and 30 <= int(n.get("text")) <= 300)
tap(find(tree, text="+1"))
tree = snapshot("05-english-metronome")
expected_bpm = str(min(initial_bpm + 1, 300))
check("Tempo changed", find(tree, text=expected_bpm) is not None)

# Obtain actual dialog bounds while stopped. These same bounds let us select a language while
# playback is active, when repeated beat-label updates prevent UiAutomator from reaching idle.
tap(find(tree, resource="language_button"))
dialog = snapshot()
chinese_option = find(dialog, text="简体中文")
tap(find(dialog, text="Cancel"))
tree = snapshot()
language_button = find(tree, resource="language_button")
tap(find(tree, text="Start metronome"))
time.sleep(0.2)
check("Metronome is playing before language switch", has_app_focus())
tap(language_button)
tap(chinese_option)
tree = snapshot("06-chinese-metronome")
check("Language switch retains current tool", find(tree, resource="tab_metronome").get("selected") == "true")
check("Language switch stops metronome and keeps BPM", not has_app_focus() and find(tree, text=expected_bpm) is not None and find(tree, text="开始节拍") is not None)

adb("shell", "am", "force-stop", package)
adb("shell", "am", "start", "-n", f"{package}/.MainActivity")
tree = snapshot()
check("Chosen language and tuning persist after restart", find(tree, text="调音器") is not None and find(tree, text="Drop D") is not None)
tree = choose_language(tree, "跟随系统")
check("System default restores emulator English", find(tree, text="Tuner") is not None)
tap(find(tree, resource="language_button"))
dialog = snapshot()
check("System-default selection is stored", find(dialog, text="System default").get("checked") == "true")
tap(find(dialog, text="Cancel"))

font_scale = adb("shell", "settings", "get", "system", "font_scale").strip()
try:
    adb("shell", "settings", "put", "system", "font_scale", "1.3")
    tree = snapshot("07-english-large-font")
    check("Language and tuning controls remain visible with large text", find(tree, resource="language_button") is not None and find(tree, resource="tuning_toggle") is not None)
finally:
    adb("shell", "settings", "put", "system", "font_scale", font_scale if font_scale != "null" else "1.0")

pid = adb("shell", "pidof", package).strip()
log = adb("logcat", "-d", "--pid", pid)
(output / "language-logcat.txt").write_text(log, encoding="utf-8")
check("No fatal crash", "FATAL EXCEPTION" not in log)
(output / "language-smoke-report.md").write_text(
    "# Language smoke test (Android 8.0 emulator)\n\n" + "\n".join(f"- PASS: {name}" for name in checks) + "\n",
    encoding="utf-8")
adb("shell", "input", "keyevent", "3")
