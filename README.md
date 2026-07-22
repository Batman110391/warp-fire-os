# WARP for Fire TV

Minimal Cloudflare WARP client for Amazon Fire TV: install the APK, open the app, press
**Connect**. No account, no config files, no key exchange — the app registers itself as an
independent WARP device on first launch (the same thing `wgcf` does, but in-process) and brings up
a full-tunnel WireGuard connection.

- Two routing modes, switchable with the remote:
  - **WARP** — full tunnel (`AllowedIPs = 0.0.0.0/0, ::/0`), your exit IP becomes Cloudflare's.
  - **DNS only** — only the resolver addresses are routed into the tunnel, like "1.1.1.1 without
    WARP" in the official app. DNS is encrypted, everything else goes out normally and your public
    IP does not change. `warp=off` is the expected result in this mode.
- MTU 1280, Cloudflare DNS (`1.1.1.1`, `1.0.0.1` + IPv6).
- **Per-app WARP**: pick which apps go through WARP; the rest keep their normal connection.
- **Auto-connect on boot**: the tunnel comes back on its own after a reboot (Fire OS hides the
  system always-on VPN, so this is the distributable equivalent — see below).
- One screen, fully D-pad navigable.
- Userspace WireGuard (`wireguard-go` via the official `com.wireguard.android:tunnel` library) — no root.
- In-app update from GitHub Releases.
- No MASQUE, no proxy mode, no accounts, no telemetry.

---

## Install on Fire TV

1. On the Fire TV: **Settings → My Fire TV → Developer Options → Install unknown apps** → enable
   **Downloader**.
2. Open **Downloader** and enter the direct URL:

   ```
   https://github.com/Batman110391/warp-fire-os/releases/latest/download/warp-firetv.apk
   ```

   (Or generate an AFTVnews numeric shortcode from that URL and type the code instead.)
3. Download → Install → Open → press **Connect**. Accept the system VPN dialog the first time; it
   is reachable with the remote.
4. *(Optional)* Leave **Auto-connect on boot** on (default) so the tunnel returns by itself after a
   reboot — see below for why this replaces the system always-on VPN.

The asset name `warp-firetv.apk` is stable across releases, so the URL above never changes.

### Auto-connect on boot (instead of always-on VPN)

Fire OS ships no VPN settings screen — even the `android.settings.VPN_SETTINGS` intent does not
resolve — so the OS-level always-on VPN can only be set through adb, which is no good for a
distributed app. Instead the app reconnects itself on boot: the VPN consent granted at first launch
stays valid, so a boot receiver can bring the tunnel back up with no dialog. A foreground service
does the actual bring-up, because a background service start is refused on API 30+.

It reconnects with your last settings (WARP on/off, selected apps). Note that Fire OS delivers the
boot broadcast to third-party apps ~1–2 minutes after startup, so the tunnel returns shortly after
boot, not instantly. Unlike a true always-on VPN there is **no kill switch** — traffic flows
normally until the tunnel is up. Toggle it off from the main screen if you don't want it.

### Verifying it works

When connected the app shows the assigned IP and the result of
`https://www.cloudflare.com/cdn-cgi/trace`; `warp=on` means traffic is going through WARP.

### Updating

On launch the app asks GitHub for the latest release tag and, if it is newer than the running
build, offers to download and install it. The download uses the stable `warp-firetv.apk` asset; the
system installer always asks you to confirm.

The check reads the tag from the redirect `/releases/latest` issues towards `/releases/tag/vX.Y.Z`
rather than calling the GitHub API, whose unauthenticated quota is 60 requests per hour **per IP** —
with the tunnel up, that IP is shared with every other WARP user on the same egress address.

On Fire OS you may need to allow this app under *Install unknown apps* the first time; the app
routes you to that settings screen and resumes when you come back.

### Resetting

Long-press the title (**WARP for Fire TV**) with the remote's select button to wipe the stored
registration and register a brand new WARP device on the next launch.

---

## Building

Requirements: JDK 17, Android SDK with API 37 platform.

```bash
./gradlew assembleRelease
```

Without signing secrets in the environment the release build falls back to the debug signing key,
so a local build always succeeds. The output is `app/build/outputs/apk/release/app-release.apk`.

### Release signing

The `release` signing config is driven entirely by environment variables / GitHub Secrets — no
keystore, password or key is ever committed:

| Variable | Content |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the `.jks` keystore, base64-encoded |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Generate a keystore:

```bash
keytool -genkeypair -v \
  -keystore warp-release.jks \
  -alias warp \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<STORE_PASSWORD>' -keypass '<KEY_PASSWORD>' \
  -dname "CN=WARP Fire TV, O=Personal, C=IT"
```

Base64-encode it for the secret:

```bash
base64 -w0 warp-release.jks > warp-release.jks.b64     # Linux
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("warp-release.jks")) | Set-Content warp-release.jks.b64
```

Then add the four values under **Settings → Secrets and variables → Actions → New repository
secret**. Keep `warp-release.jks` somewhere safe and out of the repo: losing it means future
releases cannot be installed as an update over the current one.

### CI

`.github/workflows/build.yml` runs on a `v*` tag push and on manual dispatch: JDK 17 (temurin) →
Gradle cache → `./gradlew assembleRelease` → verify the APK exists → attach it to the GitHub
Release as `warp-firetv.apk`.

Cut a release with:

```bash
git tag v1.2.0
git push origin v1.2.0
```

**The tag is the version.** On a tag build `versionName` comes from `GITHUB_REF_NAME`, and
`versionCode` is derived from it as `major*10000 + minor*100 + patch`. Nothing to bump by hand, and
the tag the updater compares against can never disagree with the version inside the APK. Local and
`workflow_dispatch` builds fall back to `devVersionName` in `app/build.gradle.kts`.

---

## How registration works

`WarpApi` ports the endpoint, API version string, client headers and payload from the current
[`wgcf`](https://github.com/ViRb3/wgcf) sources (`cloudflare/api.go`, `openapi/`) rather than
guessing them:

| | |
|---|---|
| Base URL | `https://api.cloudflareclient.com` |
| API version | `v0a1922` |
| Register | `POST /v0a1922/reg` |
| Enable WARP | `PATCH /v0a1922/reg/{id}` with `{"warp_enabled":true}` |
| Headers | `User-Agent: okhttp/3.12.1`, `CF-Client-Version: a-6.3-1922` |
| TLS | pinned to 1.2, like the official client (otherwise HTTP 403 / error 1020) |

The private key is generated on device by the WireGuard library, stored in
`EncryptedSharedPreferences` and never logged, exported or sent anywhere.

Registration is idempotent: a stored config short-circuits all network calls, so restarts never
re-register. Failures retry three times with exponential backoff and surface as an `Error` state
with manual retry.

---

## Legal notes

- In-process WARP registration uses Cloudflare's client API in an unofficial way and is
  technically against their Terms of Service. This is a personal, small-scale tool; each install is
  an independent free WARP device. Use at your own risk.
- WireGuard is a registered trademark of Jason A. Donenfeld. This project is not affiliated with
  Cloudflare, Amazon or the WireGuard project.
- No keystore, key or secret is committed to this repository, and the private key never leaves the
  device.

---

## Project layout

```
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml       # pinned dependency versions
├── gradlew / gradlew.bat
├── .github/workflows/build.yml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/batman110391/warpfiretv/
        │   ├── MainActivity.kt
        │   ├── WarpApp.kt                # always-on VPN callback
        │   ├── warp/WarpApi.kt
        │   ├── warp/WarpRegistration.kt
        │   ├── warp/WarpConfigStore.kt
        │   └── vpn/WireGuardTunnel.kt
        └── res/
```

### Pinned versions

| Component | Version | Note |
|---|---|---|
| Android Gradle Plugin | 9.3.0 | |
| Gradle | 9.6.1 | |
| Kotlin | 2.4.10 | |
| `com.wireguard.android:tunnel` | 1.0.20260102 | see below — earlier versions crash on disconnect |
| OkHttp | 5.4.0 | |
| `androidx.security:security-crypto` | 1.1.0 | |
| kotlinx.serialization | 1.11.0 | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 | see below |

**Why minSdk is 24 and not 22.** Every release of `com.wireguard.android:tunnel` up to and including
`1.0.20251231` calls `CompletableFuture.newIncompleteFuture()` unguarded in
`GoBackend.VpnService.onDestroy`. That method only exists from API 31, so bringing the tunnel down
threw `NoSuchMethodError` and killed the process on every device below Android 12 — reproduced on
Fire OS 8 (API 30). `1.0.20260102` adds the `SDK_INT` guard and is the only working version; it
requires minSdk 24. So API 22–23 support was never real, and Fire OS 6 (API 25) upwards is covered.

`android.builtInKotlin=false` and `android.newDsl=false` are set in `gradle.properties`: AGP 9's
bundled Kotlin support is incompatible with applying the standalone Kotlin Gradle Plugin, which is
required for the kotlinx.serialization compiler plugin.
