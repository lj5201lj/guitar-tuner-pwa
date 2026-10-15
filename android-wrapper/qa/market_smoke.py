"""Verify market-readiness UX on a disposable emulator. Clears only this test app's emulator data."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--apk', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a disposable emulator, never a user phone'
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
package = 'io.github.lj5201lj.xianzhun'
checks = []
tools_cache = {}


def adb(*command, binary=False):
    result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')


def snapshot(name=None):
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/xianzhun-market.xml')
    assert 'dumped to' in result, result
    content = adb('exec-out', 'cat', '/sdcard/xianzhun-market.xml')
    if name:
        (output / f'{name}.xml').write_text(content, encoding='utf-8')
        (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    return ET.fromstring(content)


def find(tree, text=None, resource=None, contains=None):
    for node in tree.iter('node'):
        if text is not None and node.get('text', '').casefold() != text.casefold():
            continue
        if resource is not None and node.get('resource-id') != f'{package}:id/{resource}':
            continue
        if contains is not None and contains not in node.get('text', ''):
            continue
        return node
    raise AssertionError(f'Missing {text or resource or contains}')


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert x2 > x1 and y2 > y1
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def check(name, passed=True):
    assert passed, name
    checks.append(name)
    print('PASS', name, flush=True)


def back():
    adb('shell', 'input', 'keyevent', '4')
    return snapshot()


def language(tree, choice):
    tap(find(tree, resource='language_button'))
    tap(find(snapshot(), text=choice))
    return snapshot()


def open_tool(tree, resource):
    tap(find(tree, resource='more_button'))
    if not tools_cache:
        menu = snapshot()
        for key in ('microphone_check', 'about_button'):
            tools_cache[key] = find(menu, resource=key)
    # Stable modal geometry: live pitch/beat updates deliberately do not become idle.
    tap(tools_cache[resource])


def has_focus():
    dump = adb('shell', 'dumpsys', 'audio')
    block = dump.split('Audio Focus stack entries', 1)[-1].split('No external focus policy', 1)[0]
    return package in block.split('External focus policy', 1)[0]


def has_recording():
    pid = adb('shell', 'pidof', package).strip()
    dump = adb('shell', 'dumpsys', 'media.audio_flinger')
    return any(re.search(r'^\s+yes\s+' + re.escape(pid) + r'\s+', block, re.M)
               for block in dump.split('Input thread ')[1:])


def allow_microphone(tree):
    node = next((n for n in tree.iter('node') if n.get('resource-id') in (
        'com.android.packageinstaller:id/permission_allow_button',
        'com.android.permissioncontroller:id/permission_allow_foreground_only_button',
    )), None)
    if node is not None:
        tap(node)
    return tree


adb('install', '-r', args.apk)
adb('shell', 'am', 'force-stop', package)
adb('shell', 'pm', 'clear', package)
try:
    adb('logcat', '-c')
except subprocess.CalledProcessError:
    print('NOTE emulator cannot clear logcat; preserve logs and inspect app crashes only', flush=True)
adb('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
tree = language(snapshot(), '简体中文')
check('Detected note and target are distinct UI elements', find(tree, text='检测音名') is not None and '目标：' in find(tree, resource='target_note').get('text'))
check('Tools menu visible', find(tree, resource='more_button') is not None)
open_tool(tree, 'about_button')
about = snapshot('01-about')
check('Version and honest publishing-identity status', find(about, contains='2.3.0') is not None and find(about, contains='尚未提供正式商店发布主体信息') is not None)
tap(find(about, resource='haptics_toggle'))
check('Haptic confirmation can be disabled', find(snapshot(), resource='haptics_toggle').get('checked') == 'false')
tap(find(snapshot(), resource='privacy_button'))
policy = snapshot('02-privacy')
check('Offline policy describes audio and public feedback', find(policy, contains='不将麦克风声音保存为文件') is not None and find(policy, contains='GitHub 问题内容公开') is not None)
back()
tap(find(snapshot(), resource='licenses_button'))
licences = snapshot('03-licenses')
check('Visible sample credits and playback modifications', find(licences, contains='CC BY 3.0') is not None and find(licences, contains='one second') is not None)
back()
tap(find(snapshot(), resource='feedback_button'))
feedback = snapshot('04-feedback')
check('Feedback does not auto-send and warns about public content', find(feedback, contains='不会自动发送任何内容') is not None)
tap(find(feedback, text='复制诊断信息'))
back()
tree = snapshot()
tap(find(tree, resource='tuning_toggle'))
purpose = snapshot('05-before-permission')
check('Purpose notice precedes OS permission', find(purpose, text='麦克风使用说明') is not None)
tap(find(purpose, text='暂不使用'))
tree = snapshot()
check('Declining purpose notice does not start recording', find(tree, text='开始调音') is not None)
tap(find(tree, resource='tab_metronome'))
metronome_tree = snapshot()
tap(find(metronome_tree, text='开始节拍'))
time.sleep(0.2)
check('Metronome works without microphone permission', has_focus())
tap(find(metronome_tree, resource='tab_tuner'))
open_tool(snapshot(), 'microphone_check')
tap(find(snapshot(), text='继续'))
allow_microphone(snapshot())
time.sleep(0.2)
check('Self-test starts after first permission grant', has_recording())
time.sleep(6.2)
test = snapshot('06-test-result')
check('Self-test finishes and reports result', '自检已结束' in find(test, resource='microphone_check_status').get('text'))
tap(find(test, text='知道了'))
tree = snapshot()
check('Standalone self-test releases its microphone', find(tree, text='开始调音') is not None)
tap(find(tree, resource='tuning_toggle'))
time.sleep(0.2)
check('Recording starts without repeated purpose notice', has_recording())
open_tool(tree, 'microphone_check')
adb('shell', 'input', 'keyevent', '4')
check('Cancelling an in-session self-test preserves existing recording', has_recording())
open_tool(tree, 'about_button')
check('Disabled haptic choice persists', find(snapshot(), resource='haptics_toggle').get('checked') == 'false')
back()
check('Opening About stops ongoing recording', find(snapshot(), text='开始调音') is not None)
tree = language(snapshot(), 'English')
check('New detected note label translated', find(tree, text='Detected note') is not None)
open_tool(tree, 'about_button')
tap(find(snapshot(), resource='privacy_button'))
policy = snapshot('07-english-policy')
check('English offline policy available', find(policy, contains='Microphone audio is not saved') is not None)
back()
back()
open_tool(snapshot(), 'microphone_check')
adb('shell', 'input', 'keyevent', '3')
time.sleep(0.4)
adb('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
tree = snapshot('08-returned-after-test')
check('Background cancels test and releases recording', find(tree, text='Start tuning') is not None)
adb('shell', 'pm', 'revoke', package, 'android.permission.RECORD_AUDIO')
try:
    adb('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
    for attempt in range(2):
        tree = snapshot()
        tap(find(tree, resource='tuning_toggle'))
        permission = snapshot()
        checkbox = next((n for n in permission.iter('node') if n.get('resource-id', '').endswith(':id/do_not_ask_checkbox')), None)
        if checkbox is not None:
            tap(checkbox)
        deny = next((n for n in permission.iter('node') if n.get('resource-id', '').endswith(':id/permission_deny_button')), None)
        if deny is not None:
            tap(deny)
        denied = snapshot('09-permanently-denied')
        if any(n.get('text', '').casefold() == 'app permissions' for n in denied.iter('node')):
            break
    check('Permanent denial offers an actionable settings button', find(denied, text='App permissions') is not None and not has_recording())
    tap(find(denied, text='App permissions'))
    settings = snapshot('10-system-app-permissions')
    check('Settings shortcut opens Android app details', any(n.get('package') == 'com.android.settings' for n in settings.iter('node')))
    back()
finally:
    adb('shell', 'pm', 'grant', package, 'android.permission.RECORD_AUDIO')
package_dump = adb('shell', 'dumpsys', 'package', package)
check('APK does not request INTERNET or VIBRATE', 'android.permission.INTERNET' not in package_dump and 'android.permission.VIBRATE' not in package_dump)
logcat = adb('logcat', '-d', '-v', 'brief')
(output / 'logcat.txt').write_text(logcat, encoding='utf-8')
check('No fatal app exception', not re.search(r'FATAL EXCEPTION[^\n]*\n[^\n]*Process: ' + re.escape(package), logcat))
(output / 'market-smoke-report.md').write_text('# 2.3.0 market-readiness emulator checks\n\n' + '\n'.join(f'- PASS: {name}' for name in checks) + '\n\nSynthetic input and emulator checks do not replace real-phone string accuracy tests.\n', encoding='utf-8')
print(f'{len(checks)} checks passed; {output}', flush=True)
