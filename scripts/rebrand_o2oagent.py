#!/usr/bin/env python3
"""Rebrand AndroidHarness user-facing text to O2OAgent.

Only touches string *values* in res/values*/**.xml and selected Kotlin string
literals. Resource ids, package names, and release/update URLs stay unchanged.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Tokens that are protected verbatim (URLs / identifiers that must not change).
PROTECT = [
    "Sanuu7/AndroidHarness",
    "com.androidharness.app",
    "com/androidharness/",
]


def protect(text):
    subs = {}
    for i, p in enumerate(PROTECT):
        key = f"\x00PROTECT{i}\x00"
        subs[key] = p
        text = text.replace(p, key)
    return text, subs


def restore(text, subs):
    for key, val in subs.items():
        text = text.replace(key, val)
    return text


def rebrand(text):
    text, subs = protect(text)
    # Product-name spellings used in visible copy.
    text = text.replace("AndroidHarness", "O2OAgent")
    text = text.replace("AHarness", "O2OAgent")
    text = text.replace("AH-debug", "O2OAgent")
    # Standalone capitalised "Harness" in copy (not lowercase ids).
    text = re.sub(r"\bHarness\b", "O2OAgent", text)
    return restore(text, subs)


changed = []

# 1) All value resources (main + every locale).
for f in sorted((ROOT / "app/src/main/res").glob("values*/**/*.xml")):
    orig = f.read_text(encoding="utf-8")
    new = rebrand(orig)
    if new != orig:
        f.write_text(new, encoding="utf-8")
        changed.append(str(f.relative_to(ROOT)))

# 2) Debug app name.
f = ROOT / "app/src/debug/res/values/strings.xml"
orig = f.read_text(encoding="utf-8")
new = rebrand(orig)
if new != orig:
    f.write_text(new, encoding="utf-8")
    changed.append(str(f.relative_to(ROOT)))

# 3) Selected Kotlin string literals that are user-facing assistant identity,
#    while never touching URLs / package identifiers / plugin format markers.
kt_files = [
    "app/src/main/java/com/androidharness/app/AgentService.kt",
    "app/src/main/java/com/androidharness/app/AppRuntimePolicy.kt",
    "app/src/main/java/com/androidharness/app/agent/LiveMode.kt",
    "app/src/main/java/com/androidharness/app/agent/SideQuestions.kt",
    "app/src/main/java/com/androidharness/app/automation/AutomationWorker.kt",
    "app/src/main/java/com/androidharness/app/core/ChatExperience.kt",
    "app/src/main/java/com/androidharness/app/chatgpt/ChatGptAccounts.kt",
    "app/src/main/java/com/androidharness/app/chatgpt/ChatGptProtocol.kt",
    "app/src/main/java/com/androidharness/app/data/update/UpdateManager.kt",
]
for rel in kt_files:
    f = ROOT / rel
    orig = f.read_text(encoding="utf-8")
    new = rebrand(orig)
    if new != orig:
        f.write_text(new, encoding="utf-8")
        changed.append(rel)

print(f"Changed {len(changed)} files")
for c in changed:
    print("  " + c)
