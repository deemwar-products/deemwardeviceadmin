# HANDOVER — Deemwar Device Admin (fdroidadmin)

For the next agent picking this up. Read this fully before touching anything.

## What this app is

`com.deemwar.deviceadmin` — a small Android app that acts as a **restricted
client for our own F-Droid repo** (`https://fdroid.deemwar.com/repo`):
- Lists every app in the repo index (index-v1.json)
- One-tap download + verify (APK sha256 vs index, signing cert vs pinned set)
- Silent install/update via `PackageInstaller` (silent when allowed, prompt
  otherwise)
- Daily WorkManager sync (charging + unmetered) for unattended updates
- DeviceAdminReceiver so it can be promoted to Device Owner on dedicated
  phones (never required for the basic flow)
- `ProvisioningActivity` handles `GET_PROVISIONING_MODE` /
  `ADMIN_POLICY_COMPLIANCE` (Android 12+ QR provisioning support)

Published in the repo itself; source is public:
`github.com/deemwar-products/deemwardeviceadmin` (local: this directory).
Repo skill/procedures: `~/muthu/deemwarworkspace/fdroid/skills/fdroid/SKILL.md`
— read that too.

## CURRENT BLOCKER (fix this first)

**On the owner's Motorola Edge 60 Fusion, tapping "Update" on an app in the
list does nothing.** No dialog, no install, no error surfaced in the status
line in some cases.

Prime suspect: `Installer.install()` in
`app/src/main/java/com/deemwar/deviceadmin/Installer.kt` sets
`params.setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on **every** install.
On Android 12+, when this app is NOT the installer-of-record of the target
package (first install, or package installed by F-Droid/adb), the session
commit with `USER_ACTION_NOT_REQUIRED` can be **rejected silently** instead
of falling back to a prompt. Result: tap → nothing.

Fix direction: detect ownership first
(`packageManager.getInstallerPackageName(pkg)` == our package, or
`packageManager.getAppMetadata`/`getInstallSourceInfo` update-owner check);
only set `USER_ACTION_NOT_REQUIRED` when we already own updates for that
package; otherwise use a plain session so the system prompt shows. Also
surface the session `STATUS_*` callback result in the UI log (currently the
`INSTALL_STATUS` broadcast is sent but never received/logged — there is no
BroadcastReceiver registered for it; add one and log status so failures stop
being invisible).

Second suspect: `PackageInstaller` status callback. The pending intent is
created with `FLAG_MUTABLE` but nothing on the receiver side parses
`EXTRA_STATUS` — silent failure is currently indistinguishable from success
except by watching `dumpsys package`. Add the receiver.

## Environment (Mac-side, all proven working)

- Build: `JAVA_HOME=~/.jenv/versions/openjdk64-17.0.19`, `gradle` (Homebrew),
  Android SDK at `/opt/homebrew/share/android-commandlinetools` (see
  `local.properties`).
- Tests: `gradle testDebugUnitTest` — 16 tests, must stay green
  (`PolicyTest`, `RepoIndexTest`, `InstallerTest`).
- Signing keystore: `~/upipay-release-local/deemwar-deviceadmin.jks`,
  alias `deemwar-deviceadmin`, password in `sec` as
  `DEVICEADMIN_APP_KS_PASS` (use `sec run DEVICEADMIN_APP_KS_PASS -- sh -c
  'KS_PASS=$DEVICEADMIN_APP_KS_PASS KS_PATH=<path> gradle assembleRelease'`).
  NEVER commit keys; never print the password.
- Publish: `~/muthu/deemwarworkspace/fdroid/scripts/publish-deviceadmin.sh
  <versionCode>` — builds (with tests), copies APK into
  `~/upipay-release-local/fdroid/repo/`, runs `fdroid update`, rsyncs to
  `site/`, deploys to Cloudflare Pages (`deemwar-fdroid`, live at
  https://fdroid.deemwar.com). Cloudflare token: `sec` name
  `CF_PAGES_TOKEN` (wrangler env var `$CLOUDFLARE_API_TOKEN`).
- Publish checklist: bump `versionCode`/`versionName` in
  `app/build.gradle.kts` FIRST; the repo serves every old version too; after
  publish verify with
  `curl -fsSL https://fdroid.deemwar.com/repo/index-v1.json` (check
  `suggestedVersionCode`) and compare APK sha256 local vs live.
- Git: push to `origin main` with author
  `sugunapaulraj <332025390+sugunapaulraj@users.noreply.github.com>`
  (identity-guard rejects the personal handle on deemwar-products repos).

## Device state (Motorola Edge 60 Fusion, ZA223853C2)

- App version on phone: **1.4 (versionCode 5)** — predates the index-hash
  parsing fix (v7) and the silent-update work (v8+). This is why the update
  experience is broken on the phone.
- F-Droid client on the phone shows no Update button for this app (suspect
  per-app "Ignore updates" flag or stale client cache) while showing updates
  for other repo apps — repo side verified correct (index has v10, all
  versions same signature `aa08147b…`, APK bytes local==live).
- Workaround that always works: download
  `https://fdroid.deemwar.com/repo/com.deemwar.deviceadmin_10.apk` in Chrome
  on the phone and install over the top (same signature).
- adb is currently **policy-blocked** ("device offline") — a since-removed
  third-party work profile's provisioning disabled USB debugging. Re-check
  `adb devices` after profile removal; if it comes back, verify with
  `adb shell dumpsys package com.deemwar.deviceadmin | grep -iE
  'installerPackageName|updateOwner'`.
- Moto Secure Vault profile (user 12) exists; user declined factory reset.
  Device Owner is therefore unreachable on THIS phone — do not chase it.
  Silent updates via installer-of-record (the v8 design) work WITHOUT owner
  status; that is the accepted end state for this phone.
- UPI Forwarder (`com.deemwar.upipay.forwarder`) v4 (0.3.1) is published as
  the silent-update test vehicle. Test: update it once through our app (one
  confirm + possible ownership-transfer dialog) → Settings > Apps shows
  0.3.1 → then the NEXT bump must land with no prompt at all.

## Key constraints (do not break)

1. Installs only from `Policy.REPO_URL` (compile-time constant, no settings).
2. Only pinned signing certs (`Policy.TRUSTED_CERT_SHA256`); the installer
   computes the APK cert SHA-256 itself before every install. Certs pinned:
   UPI Forwarder key `d16030fd…`, own key `c0d7354c…` (url-safe b64:
   `wNc1TG0bU1IHoRsv2B7an-Y3qb15Be4jBuc66g3VSQw`).
3. index-v1 field is `hash`+`hashType` (NOT `sha256` — that was bug #1);
   `normalizeHash` rejects non-sha256 hashTypes. Keep that strict.
4. This app hides itself from its own list (`MainActivity` filters
   `packageName != this`); F-Droid owns its updates.
5. Keep `targetSdk` current (34 today) or silent updates fall back to prompts.
6. Never put the APK file hash into the README's
   `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` — it must be the cert hash
   (bug #2, caught in review).

## Suggested order of work

1. Fix the installer: ownership check → conditional
   `USER_ACTION_NOT_REQUIRED`; register + log the install-status broadcast;
   surface failures in the UI status line.
2. Add unit tests for the ownership-decision logic (extract it to a pure
   function, e.g. `shouldRequestSilent(installerOfRecord, isOwner)`).
3. Bump versionCode to 11 (3.0), run tests, publish via the script, push to
   GitHub.
4. On the phone: install v11 over 1.4 via Chrome APK URL, then run the UPI
   Forwarder 0.3.1 → next-bump silent test and confirm.
5. If F-Droid still hides the update on the phone, check the per-app
   "Ignore updates" flag (⋮ menu on the app page in F-Droid) and clear the
   F-Droid client cache; re-add the repo as last resort.
