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
2. **Package allowlist** — only the application IDs listed in `Policy.ALLOWED_PACKAGES` are ever considered, even if the repo index lists more.
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

Open the app, tap **Sync + update from deemwar repo**. It fetches the repo
index, compares installed versions, and silently installs updates for
allowlisted packages. To have it also serve the work profile, install it as
profile owner instead: `dpm set-profile-owner` on the work user, or run
installs with the work user's context.

## License

Apache-2.0
