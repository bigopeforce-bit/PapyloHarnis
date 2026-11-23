---
name: o2oagent-self
description: Change O2OAgent's own source, prompts, skills or branding, then rebuild and install the APK. Use when the user asks to change the app itself, fix a bug in it, add a feature to it, or make it write its own code.
category: repo
---

# O2OAgent self-build

O2OAgent can edit its own source and rebuild itself. The source is a GitHub repo, and CI produces the APK. Treat the user's request as permission to edit the source, but confirm before pushing, triggering CI or installing.

## When to use
- The user asks to change O2OAgent itself: its features, prompts, skills, branding, update behavior or a bug in the app
- The user says "write your own code", "improve yourself", "change your own behavior"

## When not to use
- Ordinary code in the user's workspace — use the normal coding flow instead
- Only a UI mockup or concept — use create_artifact

## 1. Get a checkout
1. `env_status`. If the Linux environment is not ready, ask the user to install it with the card the app offers; do not pretend a checkout is possible without shell.
2. In a shell-backed workspace, clone the source (public anonymous HTTPS works):
   `git clone https://github.com/bigopeforce-bit/PapyloHarnis.git o2oagent-source`
   Then work only inside `o2oagent-source`.
3. If the workspace cannot run shell at all, use `github_file` / `github_api` to read files and `write_file` for edits, but say plainly that rebuilding still needs a shell-backed workspace.

## 2. Edit carefully
- Explore with `list_dir`, `grep`, `read_file` before editing. Prefer `edit_file` for targeted changes.
- Keep these unless the user explicitly asks to change them: package IDs `com.androidharness.app` / `com.androidharness.app.debug`, the committed stable key `signing-keys/debug.keystore`, external release/update URLs, and protocol identifiers such as the OAuth `agent_name_hint` = `AndroidHarness`.
- Keep the O2OAgent label, icon and user-facing strings O2OAgent. Update `app/build.gradle.kts` `versionCode` / `versionName` for every source change.
- Add or update unit tests for behavior changes. The CI run executes them.

## 3. Rebuild with CI
Local Gradle on this phone can run out of memory. GitHub Actions is the reliable route.
1. `git_status`, `git_diff`, then `git_commit` with a sentence explaining why.
2. Confirm with the user before `git_push` (outward-facing). Work on a feature branch unless they asked for `main` directly.
3. A GitHub token must be configured in Settings → GitHub for `gh` in shell to work; `env_status` reports it. If missing, ask the user to sign in there.
4. Trigger the debug build explicitly: `gh workflow run nightly.yml --repo bigopeforce-bit/PapyloHarnis --ref <branch>`. Use `workflow_dispatch` — a push-triggered run can be auto-cancelled by concurrency. If the `gh` CLI is unavailable, call `http_request` with method `POST`, url `https://api.github.com/repos/bigopeforce-bit/PapyloHarnis/actions/workflows/nightly.yml/dispatches`, and body `{"ref":"<branch>"}`; the GitHub token is attached automatically for api.github.com. A 204 means it started.
5. Watch it: `gh run watch <run-id> --repo bigopeforce-bit/PapyloHarnis --interval 30 --exit-status`. Without `gh`, poll `/repos/bigopeforce-bit/PapyloHarnis/actions/runs/<run-id>` with `github_api` until `status` is `completed` and `conclusion` is `success`. Only proceed on success.
6. Download the APK with `gh run download <run-id> --repo bigopeforce-bit/PapyloHarnis`. If that fails, get the artifact id from `gh api repos/bigopeforce-bit/PapyloHarnis/actions/runs/<run-id>/artifacts`, then request `gh api repos/bigopeforce-bit/PapyloHarnis/actions/artifacts/<id>/zip --verbose` and grab the `Location:` header as a fresh SAS URL; download it immediately with `curl -L` and unzip. Without `gh`, use `http_request` GET on that `/actions/artifacts/<id>/zip` path and read the `Location` header the same way. SAS URLs expire fast.

## 4. Verify before installing
1. `aapt dump badging <apk>`: check package `com.androidharness.app.debug`, the new versionCode/versionName, and label `O2OAgent`.
2. `apksigner verify --print-certs <apk> | grep SHA-256` must match `keytool -list -v -keystore signing-keys/debug.keystore -storepass android | grep SHA256`. A different key means the install will need an uninstall; stop and tell the user.
3. Install only after confirmation. With Shizuku/root use `pm install -r <apk>` and check for `Success`. Otherwise copy the APK into Downloads and ask the user to tap Install; never claim it is installed until they confirm.
4. Launch it and check that the change works. If it crashes, use the android-crash skill.

## Pitfalls
- Never trigger CI twice for the same commit "just in case" — check the run status first.
- Never replace the committed debug keystore or invent a different signature.
- Never push without asking, and never install an unverified APK.
- If a build or download fails, report the real error instead of claiming the app updated itself.

## Verification
A committed source change, a successful CI run, an APK with the expected version/label/signature, and a confirmed install/launch.
