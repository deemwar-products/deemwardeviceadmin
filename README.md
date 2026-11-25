# Deemwar Device Admin

A minimal Android Device Owner app that silently installs and updates **only**
apps signed by Deemwar and served from the Deemwar F-Droid repo at
`https://fdroid.deemwar.com/repo`.

It exists so a device can keep Deemwar apps current even when an Android
work profile restricts ordinary sideloading: a Device Owner is not subject to
per-profile "install unknown apps" limits, so updates land without prompts
and without being device-wide.

## Safety rails (all baked in, no settings screen)

1. **One repo** — every download comes from `https://fdroid.deemwar.com/repo`; the URL is a compile-time constant.
2. **Package allowlist** — only application IDs under the `com.deemwar.` namespace are ever considered, even if the repo index lists more.
3. **Index integrity** — the APK's SHA-256 must match the repo index before install.
4. **Pinned signing certs** — the APK's signing certificate SHA-256 must be in `Policy.TRUSTED_CERT_SHA256`, or the install is refused and the APK deleted.

There is no intent-based install entry point, no share-target, and no
user-configurable source. If it is not from that repo with a pinned cert,
it does not get installed.

## Setup (one time, per device)

USB debugging on, then:

```
adb install app-release.apk
adb shell dpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver
```

`set-device-owner` requires the device to have no accounts signed in
temporarily; remove the Google account, run it, add it back.

## Use

Open the app. It lists every `com.deemwar.*` package served by
`fdroid.deemwar.com`, shows what is installed versus available, and one tap
installs or updates silently (when Device Owner). It is a scoped F-Droid
client: one repo, one namespace, nothing else. To have it also serve the
work profile, install it as profile owner instead: `dpm set-profile-owner`
on the work user, or run installs with the work user's context.

## License

Apache-2.0

---

## The update path: who prompts, when

Silent updates (no dialog at all) work when **this app is the installer of
record** for the target package:

- The **first** install of an app via this client (or the first update of an
  app another installer — adb, F-Droid, Play — put there) shows the standard
  **one confirm tap**. After that, this app owns updates for that package:
  every later version lands **silently**, including via the daily WorkManager
  job (charging + unmetered).
- Self-updates of this app are handled by the F-Droid client (also unattended
  on recent versions) — this app hides itself from its own list.

Verify installer of record / update ownership on-device:

```
adb shell dumpsys package <pkg> | grep -i -E 'installerPackageName|updateOwner'
```

Should show `com.deemwar.deviceadmin` for every repo app once the first
update-through-this-app has run. If an update still prompts, it is almost
always one of: targetSdk too old for the device's Android version, or the
package is still owned by a different installer (run the first update once
through this app to take ownership).

## Runbook: provisioning a dedicated phone as Device Owner

For phones dedicated to deemwar apps (kiosk-style). Do this **before adding
any account or profile**; Device Owner can only be granted while the device
has exactly one user and no accounts.

1. Factory reset. Skip the Google account. Do not enable Moto Secure Vault
   (it creates user 12 and permanently blocks `set-device-owner`). Decline
   work-profile/MDM provisioning.
2. Preferred, no computer: on the setup-wizard welcome screen tap the same
   spot 6 times → connect Wi-Fi → scan the provisioning QR (payload below).
   The wizard downloads our APK from the repo and sets it as Device Owner.
3. Fallback, adb: enable Developer options → USB debugging, verify
   `adb shell pm list users` shows exactly one user and `dumpsys account`
   shows none, then
   `adb shell dpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver`.
4. Only after the owner is set: add the Google account, configure whatever
   else. Owner status survives reboots, OS updates and repo updates (same
   signing key). If a work profile is ever needed, our DPC must create it —
   never let a third-party MDM provision one (they disable adb and block
   `set-device-owner`).

Provisioning QR payload (JSON — render with any QR generator). Prefer
SIGNATURE_CHECKSUM (API 23+): it survives APK version bumps, unlike
PACKAGE_CHECKSUM which changes with every release. Required keys are
COMPONENT_NAME + DOWNLOAD_LOCATION + one checksum; on Android 12+ the DPC
must also handle GET_PROVISIONING_MODE and ADMIN_POLICY_COMPLIANCE, which
this app does since v10:

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "com.deemwar.deviceadmin/com.deemwar.deviceadmin.AdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://fdroid.deemwar.com/repo/com.deemwar.deviceadmin_10.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "wNc1TG0bU1IHoRsv2B7an-Y3qb15Be4jBuc66g3VSQw",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false
}
```

Checksum recipe (when the signing cert ever changes): from
`apksigner verify --print-certs app.apk` take the cert SHA-256 hex, then
`echo <hex> | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='`.
IMPORTANT: SIGNATURE_CHECKSUM is the hash of the **signing certificate**,
not of the APK file — do not put the APK file hash (PACKAGE_CHECKSUM) in
that field, provisioning fails with a vague "couldn't set up device".
Gotchas: tap the same spot 6 times on the FIRST welcome screen before
changing language/country (Motorola setup wizard); some carrier builds strip
the QR reader — the adb fallback is the plan B; never sign into Google or
enable Secure Vault before provisioning.

Note: on Android 12+ a DPC must handle `ACTION_GET_PROVISIONING_MODE` and
`ACTION_ADMIN_POLICY_COMPLIANCE` or QR provisioning fails — the first thing
to check if the wizard rejects the QR. The APK URL pins a versionCode; bump
it when publishing.
