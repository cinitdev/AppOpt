"""在临时模块目录验证真实 Shell 入口；模拟包管理器，不修改手机状态。"""

import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get("QIXIA_TEST_BASH") or (
    "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")
)
SERVICE = ROOT / "magisk_module/service.sh"
HOT = (ROOT / "magisk_module/config/tools/qixia_hot_update.sh").read_text(encoding="utf-8")
MOCK = r'''#!/usr/bin/env bash
fixture=$QIXIA_TEST_JOYOSE_DIR
read_value() { cat "$fixture/$1"; }
case "${0##*/}" in
getprop)
    case "$(read_value system):$1" in
        miui:ro.miui.ui.version.code|hyper:ro.mi.os.version.code) echo 2 ;;
    esac ;;
dumpsys)
    [ ! -e "$fixture/probe_fail" ] || exit 1
    package=$(read_value package)
    [ "$package" != missing ] || { echo 'Unable to find package: com.xiaomi.joyose'; exit 0; }
    enabled=0; installed=true; hidden=false; suspended=false
    case "$package" in
        frozen) enabled=3 ;; hidden) hidden=true ;; suspended) suspended=true ;;
        uninstalled) installed=false ;;
    esac
    printf 'Packages:\n  Package [com.xiaomi.joyose] (1234):\n'
    printf '    User 0: ceDataInode=0 installed=%s hidden=%s suspended=%s stopped=true enabled=%s\n' "$installed" "$hidden" "$suspended" "$enabled"
    case "$(read_value state)" in
        enabled|disabled) printf '      %sComponents:\n        com.xiaomi.joyose.smartop.SmartOpService\n' "$(read_value state)" ;;
    esac
    # 其他用户和隐藏的旧系统包不能覆盖用户 0 的状态。
    printf '    User 10: installed=true hidden=false suspended=false enabled=0\n      enabledComponents:\n        com.xiaomi.joyose.smartop.SmartOpService\n'
    printf 'Hidden system packages:\n  Package [com.xiaomi.joyose] (5678):\n    User 0: installed=true enabled=0\n      enabledComponents:\n        com.xiaomi.joyose.smartop.SmartOpService\n' ;;
cmd)
    [ ! -e "$fixture/query_fail" ] || exit 1
    [ "$1 $2 $3 $4 $5 $6" = 'package query-services --user 0 --components --query-flags' ] || exit 71
    [ "$8" = -n ] && [ "$9" = com.xiaomi.joyose/.smartop.SmartOpService ] || exit 72
    case "$7" in 786944|786432) ;; *) exit 73 ;; esac
    state=$(read_value state)
    if [ -e "$fixture/component_missing" ] ||
       { [ "$7" = 786432 ] && { [ "$state" = disabled ] || { [ "$state" = default ] && [ -e "$fixture/manifest_disabled" ]; }; }; }; then
        echo 'No services found'
    elif [ -e "$fixture/full_component" ]; then
        echo com.xiaomi.joyose/com.xiaomi.joyose.smartop.SmartOpService
    else echo com.xiaomi.joyose/.smartop.SmartOpService; fi ;;
pm)
    printf '%s\n' "$*" >> "$fixture/operations"
    [ "$2 $3 $4" = '--user 0 com.xiaomi.joyose/.smartop.SmartOpService' ] || exit 74
    [ ! -e "$fixture/pm_fail" ] || exit 1
    [ ! -e "$fixture/pm_no_effect" ] || exit 0
    case "$1" in disable) echo disabled ;; enable) echo enabled ;; default-state) echo default ;; *) exit 75 ;; esac > "$fixture/state" ;;
timeout) shift; "$@" ;;
esac
'''


def function(name):
    marker = name + "() {\n"
    return marker + HOT.split(marker, 1)[1].split("\n}\n", 1)[0] + "\n}\n"


def bash_path(path):
    value = path.as_posix()
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


class JoyoseServiceTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="joyose-test-", dir=ROOT / "build")
        self.root = Path(self.temporary.name)
        self.module = self.root / "module"
        (self.module / "config").mkdir(parents=True)
        self.policy = self.module / "config/calib_policy.conf"
        (self.module / "service.sh").write_text(SERVICE.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        shutil.copytree(ROOT / "magisk_module/tools", self.module / "tools")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("getprop", "dumpsys", "cmd", "pm", "timeout"):
            script = self.bin / name
            script.write_text(MOCK, encoding="utf-8", newline="\n")
            script.chmod(0o755)
        for name, value in (("state", "default"), ("package", "ready"), ("system", "miui")):
            (self.root / name).write_text(value, encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def shell(self, body):
        content = f"export QIXIA_TEST_JOYOSE_DIR={shlex.quote(bash_path(self.root))}\n"
        content += f"export PATH={shlex.quote(bash_path(self.bin))}:$PATH\n" + body
        return subprocess.run([BASH, "--noprofile", "--norc", "-s"], input=content,
                              encoding="utf-8", capture_output=True, timeout=15)

    def command(self, *args, success=True):
        result = self.shell("bash " + shlex.join([bash_path(self.module / "service.sh"), *args]))
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        self.assertEqual(values.get("version"), "1", result.stdout + result.stderr)
        self.assertFalse((self.module / "config/calib_policy.conf.lock").exists())
        return values

    def operations(self):
        path = self.root / "operations"
        return path.read_text(encoding="utf-8").splitlines() if path.exists() else []

    def test_first_install_toggle_restart_and_uninstall(self):
        self.policy.write_text("rule_output_format=single\ncpuset_name=Mine\n", encoding="utf-8")
        self.assertEqual(self.command("--joyose-init")["disabled"], "1")
        self.assertIn("joyose_smartop_original_state=default", self.policy.read_text())
        self.assertEqual(self.command("--joyose-set", "0")["disabled"], "0")
        self.command("--joyose-init")
        self.command("--joyose-apply")
        self.assertEqual(len(self.operations()), 2)
        (self.module / "remove").touch()
        self.command("--joyose-restore")
        self.assertEqual((self.root / "state").read_text().strip(), "default")
        self.assertEqual(self.command("--joyose-apply", success=False)["reason"], "pending_update")
        text = self.policy.read_text()
        self.assertIn("cpuset_name=Mine", text)
        self.assertIn("rule_output_format=single", text)
        self.assertEqual(text.count("joyose_smartop_disabled="), 1)

    def test_original_enabled_and_disabled_are_restored(self):
        for original in ("enabled", "disabled"):
            with self.subTest(original=original):
                self.policy.unlink(missing_ok=True)
                (self.root / "state").write_text(original)
                self.command("--joyose-init")
                self.command("--joyose-set", "0" if original == "disabled" else "1")
                self.command("--joyose-restore")
                self.assertEqual((self.root / "state").read_text().strip(), original)

    def test_upgrade_inherits_existing_restoration_marker(self):
        old = self.root / "old"
        old.mkdir()
        (old / "module.prop").write_text("id=QixiaThreads\n")
        (old / "uninstall.sh").write_text("# QIXIA_JOYOSE_SMARTOP_ORIGINAL=enabled\n")
        (self.root / "state").write_text("disabled")
        (self.root / "probe_fail").touch()
        self.command("--joyose-init", bash_path(old), success=False)
        self.assertIn("joyose_smartop_original_state=enabled", self.policy.read_text())
        (self.root / "probe_fail").unlink()
        self.command("--joyose-apply")
        self.command("--joyose-restore")
        self.assertEqual((self.root / "state").read_text().strip(), "enabled")

    def test_unavailable_devices_and_packages_never_mutate_components(self):
        for package in ("missing", "uninstalled", "frozen", "hidden", "suspended"):
            with self.subTest(package=package):
                (self.root / "package").write_text(package)
                self.assertEqual(self.command("--joyose-init")["available"], "0")
                self.command("--joyose-set", "1", success=False)
                self.command("--joyose-restore")
        (self.root / "package").write_text("ready")
        (self.root / "system").write_text("other")
        self.assertEqual(self.command("--joyose-init")["reason"], "unsupported_system")
        (self.root / "system").write_text("miui")
        (self.root / "component_missing").touch()
        self.assertEqual(self.command("--joyose-init")["reason"], "component_missing")
        self.assertEqual(self.operations(), [])

    def test_hyperos_default_disabled_full_component_and_status_are_read_only(self):
        (self.root / "system").write_text("hyper")
        (self.root / "manifest_disabled").touch()
        (self.root / "full_component").touch()
        self.assertEqual(self.command("--joyose-status")["disabled"], "1")
        self.assertFalse(self.policy.exists())
        self.command("--joyose-init")
        self.assertEqual(self.operations(), [])
        self.assertIn("joyose_smartop_original_state=default", self.policy.read_text())

    def test_command_failure_and_false_success_are_not_reported_as_applied(self):
        for failure in ("pm_fail", "pm_no_effect"):
            with self.subTest(failure=failure):
                (self.root / failure).touch()
                state = self.command("--joyose-set", "1", success=False)
                self.assertEqual((state["ok"], state["disabled"], state["requested"]), ("0", "0", "1"))
                self.assertIn("joyose_smartop_original_state=default", self.policy.read_text())
                (self.root / failure).unlink()
        (self.root / "query_fail").touch()
        self.assertEqual(self.command("--joyose-status", success=False)["available"], "0")

    def test_invalid_policy_and_pending_update_do_not_mutate_service(self):
        self.policy.mkdir()
        self.assertEqual(self.command("--joyose-init", success=False)["reason"], "policy_write_failed")
        self.policy.rmdir()
        (self.module / "update").touch()
        self.assertEqual(self.command("--joyose-set", "1", success=False)["reason"], "pending_update")
        self.command("--joyose-set", "bad", success=False)
        self.assertEqual(self.operations(), [])

    def test_uninstall_restores_before_refresh_helper_failure(self):
        self.command("--joyose-init")
        tools = self.module / "config/tools"
        tools.mkdir()
        (tools / "qixia_refresh_helper.sh").write_text("exit 1\n")
        uninstall = self.module / "uninstall.sh"
        uninstall.write_text((ROOT / "magisk_module/uninstall.sh").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        result = self.shell("bash " + shlex.quote(bash_path(uninstall)))
        self.assertEqual(result.returncode, 1)
        self.assertEqual((self.root / "state").read_text().strip(), "default")

    def test_hot_update_removes_only_completed_installer_even_with_pending_apk(self):
        active = self.module
        (active / "config/bin").mkdir()
        binary = active / "config/bin/QiXiaRs"
        binary.write_bytes(b"fixture")
        (active / "module.prop").write_text("versionCode=206\n")
        (active / "config/app").mkdir()
        tx = self.root / "transaction"
        tx.mkdir()
        status = tx / "status"
        base = f'''ACTIVE={shlex.quote(bash_path(active))}
STAGED={shlex.quote(bash_path(self.root / 'staged'))}
TX={shlex.quote(bash_path(tx))}
LOCK="$TX/lock"
OWNER=fixture-owner
readlink() {{ printf '%s' "$OWNER"; }}
state_file() {{ printf '%s' "$TX/status"; }}
prop() {{ sed -n "s/^$1=//p" "$2"; }}
phase() {{ prop phase "$TX/status"; }}
persist_result() {{ cp "$TX/status" "$ACTIVE/result"; }}
'''
        for phase, build, removed in (("failed", hashlib.sha256(b"fixture").hexdigest(), False),
                                      ("applied", "wrong-build", False),
                                      ("applied", hashlib.sha256(b"fixture").hexdigest(), True)):
            with self.subTest(phase=phase, build=build):
                status.write_text(f"phase={phase}\nversion=206\nbuild={build}\n")
                (active / "customize.sh").write_text("installer")
                result = self.shell(base + function("cleanup_completed_update") + "cleanup_completed_update\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(not (active / "customize.sh").exists(), removed)
                self.assertTrue((active / "config/app").exists())
                self.assertTrue(tx.exists())

    def test_hot_update_keeps_original_state_when_active_policy_missing(self):
        staged = self.root / "staged"
        (staged / "config").mkdir(parents=True)
        (staged / "config/calib_policy.conf").write_text("joyose_smartop_disabled=1\njoyose_smartop_original_state=enabled\n")
        base = f"ACTIVE={shlex.quote(bash_path(self.module))}\nSTAGED={shlex.quote(bash_path(staged))}\n"
        result = self.shell(base + function("copy_file") + function("merge_policy_defaults") + "merge_policy_defaults\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("joyose_smartop_original_state=enabled", self.policy.read_text())
        self.policy.write_text("joyose_smartop_disabled=0\ncpuset_name=Mine\n")
        result = self.shell(base + function("copy_file") + function("merge_policy_defaults") + "merge_policy_defaults\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("joyose_smartop_disabled=0", self.policy.read_text())
        self.assertIn("joyose_smartop_original_state=enabled", self.policy.read_text())

    def test_missing_tool_fails_before_starting_watchdog(self):
        (self.module / "tools/service_watchdog.sh").unlink()
        result = self.shell("QIXIA_SERVICE_HOT_UPDATE=1 bash " + shlex.quote(bash_path(self.module / "service.sh")))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("service_watchdog.sh", result.stderr)
        self.assertFalse((self.module / "config/state/service_watchdog.lock").exists())
        self.assertFalse((self.module / "logs").exists())

    def test_watchdog_runs_in_original_shell_after_loading_tools(self):
        source = SERVICE.read_text(encoding="utf-8")
        loader = "load_service_tool() {\n" + source.split("load_service_tool() {\n", 1)[1].split("\n}\n", 1)[0] + "\n}\n"
        result = self.shell(f'MODDIR={shlex.quote(bash_path(self.module))}\n' + loader + r'''
SERVICE_PID=$BASHPID
LOG="$MODDIR/watchdog.log"
WATCHDOG_DAEMON_PID=""
for library in service_joyose.sh service_watchdog.sh service_app_install.sh service_runtime.sh; do
    load_service_tool "$library" || exit 1
done
# 隔离外部服务，实际执行拆分后的看门狗循环两轮，检查始终由调用者进程执行。
java_service_healthy() { return 0; }
start_foreground_helper() { return 0; }
is_our_daemon_running() { return 0; }
round=0
sleep() {
    [ "$BASHPID" = "$SERVICE_PID" ] || exit 92
    printf 'watchdog_pid=%s\n' "$BASHPID"
    round=$((round + 1))
    [ "$round" -lt 2 ] || exit 0
}
watch_services
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = [line for line in result.stdout.splitlines() if line.startswith("watchdog_pid=")]
        self.assertEqual(len(lines), 2, result.stdout)
        self.assertEqual(lines[0], lines[1])

    def test_explicit_app_install_exits_without_watchdog(self):
        (self.module / "config/bin").mkdir()
        (self.module / "config/bin/QiXiaRs").write_text("fixture")
        app = self.module / "config/app"
        app.mkdir()
        (app / "app.prop").write_text("package=top.qixia.threads\nversionCode=206\nversionName=v2.0.6\napk=QixiaThreads.apk\n")
        (app / "QixiaThreads.apk").write_bytes(b"fixture")
        helpers = self.module / "config/tools"
        helpers.mkdir()
        (helpers / "qixia_pkg_helper.sh").write_text("printf 'ok=1\\ninstalled=1\\nversionCode=206\\napk_same=1\\n'\n")
        result = self.shell("bash " + shlex.join([bash_path(self.module / "service.sh"), "--install-app"]))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(app.exists())
        self.assertFalse((self.module / "config/state/service_watchdog.lock").exists())
        self.assertEqual(self.operations(), [])

    def test_hot_update_backs_up_and_restores_split_tools(self):
        staged = self.root / "staged"
        (staged / "tools").mkdir(parents=True)
        tx = self.root / "transaction"
        tx.mkdir()
        (self.module / "customize.sh").write_text("installer")
        before = (self.module / "tools/service_watchdog.sh").read_bytes()
        (staged / "tools/service_watchdog.sh").write_text("new watchdog")
        (staged / "tools/service_extra.sh").write_text("new library")
        base = f"ACTIVE={shlex.quote(bash_path(self.module))}\nSTAGED={shlex.quote(bash_path(staged))}\nTX={shlex.quote(bash_path(tx))}\n"
        code = base + "set -e\n" + "".join(function(name) for name in (
            "runtime_files", "runtime_manifest", "copy_file", "backup_runtime", "restore_runtime"))
        code += '''
backup_runtime
runtime_manifest "$ACTIVE" > "$TX/before"
copy_file "$STAGED/tools/service_watchdog.sh" "$ACTIVE/tools/service_watchdog.sh"
copy_file "$STAGED/tools/service_extra.sh" "$ACTIVE/tools/service_extra.sh"
runtime_manifest "$ACTIVE" > "$TX/changed"
if cmp -s "$TX/before" "$TX/changed"; then exit 93; fi
restore_runtime
runtime_manifest "$ACTIVE" > "$TX/restored"
cmp -s "$TX/before" "$TX/restored"
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.module / "tools/service_watchdog.sh").read_bytes(), before)
        self.assertFalse((self.module / "tools/service_extra.sh").exists())
        listed = (tx / "files").read_text().splitlines()
        self.assertIn("tools/service_joyose.sh", listed)
        self.assertIn("tools/service_extra.sh", listed)
        self.assertNotIn("customize.sh", listed)


if __name__ == "__main__":
    unittest.main()
