---
name: o2oagent-self-debug
description: Diagnose and fix errors in O2OAgent itself — crashes, failed tools, broken builds or wrong behavior — by reading logs and the phone screen, then patching and rebuilding the source. Use when O2OAgent or one of its tools fails.
category: repo
---

# Fix O2OAgent's own errors

When O2OAgent crashes, a tool fails, a build breaks or the app behaves wrongly, diagnose it from real evidence — logs and the screen — then fix the source and rebuild. Do not guess, and do not claim it is fixed until you have verified it on the phone.

## When to use
- O2OAgent crashes, shows an error, or a tool returns an unexpected failure
- A CI build or unit test for O2OAgent fails
- The user says "fix yourself", "you have a bug", "why did that fail"

## When not to use
- A bug in the user's own project — use systematic-debugging instead
- A web page or browser error — use browser_get_logs / browser_screenshot

## 1. Collect evidence
1. `o2o_crash_log` first — it returns O2OAgent's persisted crash reports with full stack traces, even after a restart. Then `read_logcat` with `package_name=com.androidharness.app.debug`, `buffer=crash`, `level=E`, `lines=300`. If that is empty, try `buffer=all` and `tag=AndroidRuntime`. Read the stack trace: find the first `Caused by`, the file/class and line number.
2. `device_screen` to see what is actually on the phone right now — an error dialog, a stuck screen, or the wrong UI. If the layout or an image matters, call `device_screenshot` and look at the attached image.
3. If a tool failed, read its exact error text. Check the backend first: device_screen and device_input prefer O2OAgent's own AccessibilityService (Setup -> Accessibility, or Device Owner can enable it through secure settings) and fall back to a privileged shell (Shizuku, Wireless ADB, root or Device Owner) for gestures and key events; device_screenshot always needs a privileged shell. Report that plainly instead of calling it a bug.
4. To inspect another app, call `device_input` with `action=open_app` and its package first, then `device_screen` again — the screen tools read whatever is currently in the foreground.

## 2. Locate the code
- Map the stack trace frame to a source file under `app/src/main/java/...`.
- Use `grep` and `read_file` in the O2OAgent checkout (see the o2oagent-self skill for getting one).
- If the failure is in a tool, check all three places it can be wired:
  1. the tool class and its `execute`,
  2. `ToolRegistry.default(...)` in `tools/Tool.kt` — is it added to `baseTools`?
  3. `WorkspaceToolRouting.independentTools` — without its name here, chat workspaces will not advertise it.
- If the failure is an install/update signature mismatch, compare the APK's SHA-256 with `signing-keys/debug.keystore` as in o2oagent-self.

## 3. Fix and verify
1. Make a targeted `edit_file`, add or update a unit test that reproduces the failure, and bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. Load the o2oagent-self skill for the commit, CI, download and verification steps. Confirm before pushing and installing.
3. After the new version installs, launch it, call `read_logcat` again with the same filters, and `device_screen`/`device_screenshot` to confirm the error is gone. Only then say it is fixed.
4. If the same error returns, stop and report the evidence instead of retrying builds in a loop.

## Pitfalls
- An empty logcat result does not prove there was no crash; check `o2o_crash_log`, the crash buffer and `AndroidRuntime` first.
- A screenshot of a secure screen, the lock screen or a payment sheet can be blank by design; rely on logs there.
- Never enter passwords, one-time codes or payment details, and never tap through a permission or payment prompt on the user's behalf.
- Keep package IDs, release URLs, the stable keystore and the OAuth `agent_name_hint` unchanged unless the user asked.
