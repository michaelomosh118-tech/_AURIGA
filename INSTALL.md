# Installing Auriga — one-stop guide

Auriga ships as four separate Android apps. All four are built from the same
codebase with distinct `applicationId`s, so they can be installed side-by-side
on one device.

| Variant | applicationId | Download |
|---|---|---|
| AurigaNavi       | `com.drakosanctis.auriga`            | [AurigaNavi-release.apk](https://github.com/michaelomosh118-tech/auriga-releases/releases/latest/download/AurigaNavi-release.apk) |
| AurigaSentinel   | `com.drakosanctis.auriga.sentinel`   | [AurigaSentinel-release.apk](https://github.com/michaelomosh118-tech/auriga-releases/releases/latest/download/AurigaSentinel-release.apk) |
| AurigaAero       | `com.drakosanctis.auriga.aero`       | [AurigaAero-release.apk](https://github.com/michaelomosh118-tech/auriga-releases/releases/latest/download/AurigaAero-release.apk) |
| AurigaIndustrial | `com.drakosanctis.auriga.industrial` | [AurigaIndustrial-release.apk](https://github.com/michaelomosh118-tech/auriga-releases/releases/latest/download/AurigaIndustrial-release.apk) |

Those URLs always redirect to the latest tagged GitHub Release, so the
website never needs updating when a new build ships.

---

## 1. End-user install (Android, any OEM)

1. **Uninstall any previous Auriga** with the same name from **Settings →
   Apps** before installing a new build. A freshly-signed build cannot
   overwrite an install signed with a different key; Android will silently
   reject the update on most OEMs.
2. In a browser on the phone, open the link for your variant from the table
   above (or scan the QR code on the website). Save the file.
3. In Samsung's **My Files** (or Google **Files**), tap the downloaded APK.
4. If prompted, grant that app permission to install unknown apps.
5. Tap **Install**.
6. When the app opens, grant **Camera**, **Microphone**, and **Vibration**
   permissions — all three are required.

---

## 2. Per-OEM troubleshooting (for silent install failures)

Android's `PackageInstaller` can refuse an install for ~15 different reasons
and many OEMs deliberately suppress the on-screen error. If the install
dialog closes with no success toast, work down this list in order.

### Samsung (One UI 5.1+, including Galaxy A07 / Android 15 / One UI 7)

- **Settings → Security and privacy → Auto Blocker** → turn **OFF**
  - "Block app installs from unauthorized sources"
  - "Block harmful apps"
- **Settings → Biometrics and security → Install unknown apps** → allow the
  app you are opening the APK from (Samsung Internet, Chrome, **My Files**).
- **Use My Files, not Apk Installer.** Samsung Knox silently blocks
  third-party installer apps. My Files is whitelisted.
- Only install **release** APKs. Debug APKs have `android:debuggable="true"`
  and Samsung Knox silently blocks them on retail One UI 6+ devices with
  no error UI whatsoever.

### Xiaomi / Redmi / POCO (MIUI / HyperOS)

- **Settings → Passwords & security → Privacy → Special permissions →
  Install unknown apps** → allow the opening app.
- **Settings → Passwords & security → Privacy** → turn **OFF** "MIUI
  Optimization" if you see repeated rejections (requires reboot).
- Disable **Pure Mode** if enabled.

### Huawei / Honor (EMUI / HarmonyOS)

- **Settings → Biometrics & password → Security** → enable "Install
  external sources".
- **Optimizer app → Virus scan settings** → turn off "Scan before install"
  temporarily.

### Oppo / Realme / OnePlus (ColorOS / OxygenOS)

- **Settings → Privacy → Install unknown apps** → allow the opening app.
- Disable **App Security Check** in Security app if it intercepts.

### Google Play Protect (all OEMs)

- Play Store → profile icon → **Play Protect** → gear icon →
  turn off **Scan apps with Play Protect** for the install, then re-enable.
- If Play Protect shows "App was blocked for your safety", tap **More
  details → Install anyway**.

### Work profile / MDM

If the device is enrolled in an MDM (school / work / Android Enterprise),
sideloading is almost certainly policy-blocked. Ask the admin to whitelist
the `applicationId`s in the table above or to push the APK via MDM.

### Signature mismatch

If you previously installed an Auriga build signed with a different key,
Android rejects any new APK with "update incompatible" (usually silent).
Uninstall the old copy **then reboot** before installing the new one.

---

## 3. Install via `adb` (recommended for dev / QA)

`adb` bypasses every OEM UI layer and prints the actual rejection reason
when a package is refused, so this is the fastest way to debug install
failures.

```bash
# One-time setup
#   Settings → About phone → tap "Build number" 7× to enable Developer
#   Options, then Developer Options → USB debugging → ON.
#   Plug into a computer with adb installed.

# List connected devices
adb devices

# Install (replace path with the APK you downloaded)
adb install -r -g AurigaSentinel-release.apk
#       -r : reinstall, keep data
#       -g : grant all runtime permissions
```

Typical error codes and what they mean:

| Error | Meaning | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signing key differs from existing install | `adb uninstall <applicationId>` first |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | Play Protect / OEM verifier blocked it | Disable Play Protect temporarily |
| `INSTALL_FAILED_REJECTED_BY_SYSTEM` | OEM security layer (Knox etc.) | See OEM section above |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK unsigned or corrupt transfer | Re-download, verify SHA-256 |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | Missing system feature / framework | Device too old or too stripped-down |
| `INSTALL_FAILED_DUPLICATE_PERMISSION` | Another app declares the same permission | Uninstall the conflicting app |

Live log while installing (in a second terminal):

```bash
adb logcat -s PackageManager PackageInstaller | tee install.log
```

---

## 4. Distribution roadmap

For reliable installs across every Android phone and microcontroller target,
sideloading is the **last** channel to rely on. Recommended rollout:

### Stage 1 — today: GitHub Release + sideload
- CI publishes signed APKs + AABs on every `git tag v*` (this workflow).
- Website wires the **DIRECT DOWNLOAD** button to the release URLs above.
- Users sideload following this guide.

### Stage 2 — Google Play Store internal testing ($25 one-time)
- Open a Play Console account.
- Upload the `.aab` (not `.apk`) — Google signs per-device APKs for you.
- Add testers by email — they install from the Play Store link, which
  bypasses every OEM sideload block (Knox, MIUI Pure Mode, Play Protect,
  etc.) automatically.

### Stage 3 — Samsung Galaxy Store private distribution
- Useful specifically for Samsung fleets (AurigaIndustrial on forklift
  tablets, etc.).
- Signed APKs distributed via Galaxy Store are whitelisted by Knox.

### Stage 4 — Enterprise MDM
- AurigaIndustrial fleet deploy: push APKs via Samsung Knox Deploy, Google
  Android Enterprise, Scalefusion, Hexnode, or similar.
- Silent install on managed devices; no user interaction required.

### Stage 5 — Hardware SDK (Pi / ESP32 / STM32 / Jetson)
- Separate binary distribution channel; out of scope for this Android guide.
- The Hardware SDK request form on the website fires a `mailto:` to
  `sdk@drakosanctis.com` pre-filled with the arch + HFOV.

---

## 5. Generating a production release keystore (one-time, offline)

The build is already wired to sign with a production key when it's
available. Generate the keystore **locally** — never let CI generate it
and never commit it.

```bash
keytool -genkeypair -v \
  -keystore auriga-release.jks \
  -alias auriga-release \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<STRONG_STORE_PASSWORD>' \
  -keypass   '<STRONG_KEY_PASSWORD>' \
  -dname 'CN=DrakoSanctis Auriga, OU=Engineering, O=DrakoSanctis, L=Unknown, ST=Unknown, C=US'
```

Back the `.jks` up in at least two places you control (hardware token +
offline encrypted drive). Losing it means losing the ability to ship
updates to existing installs, ever. It also means Play Store upload is
blocked until a key reset is negotiated with Google support.

Then add four repo secrets at
`https://github.com/michaelomosh118-tech/_AURIGA/settings/secrets/actions`:

| Secret name | Value |
|---|---|
| `AURIGA_RELEASE_KEYSTORE_B64` | `base64 -w0 auriga-release.jks` output |
| `AURIGA_RELEASE_STORE_PASSWORD` | your `-storepass` |
| `AURIGA_RELEASE_KEY_ALIAS` | `auriga-release` |
| `AURIGA_RELEASE_KEY_PASSWORD` | your `-keypass` |

On the next `git tag v*` push, the release workflow decodes the keystore
and signs all 4 flavors with the production key automatically. If any of
those secrets is missing the workflow falls back to the committed debug
keystore and prints a warning.

---

## 6. Verifying an APK you downloaded

```bash
# Signature schemes (should show v1, v2, v3 verified true)
apksigner verify --verbose --min-sdk-version 24 AurigaSentinel-release.apk

# Manifest metadata (package, versionName, debuggable flag)
aapt dump badging AurigaSentinel-release.apk | \
    grep -E "^(package|application-label:|application-debuggable)"
# Release APKs must NOT print `application-debuggable` — that line means
# android:debuggable="true", which Samsung Knox will silently block.

# Certificate fingerprint (should match across successive builds because
# we sign with a committed stable keystore)
apksigner verify --print-certs AurigaSentinel-release.apk | grep SHA-256
```

The stable debug-keystore fingerprint is:

```
SHA-256 digest: 1b11c944af36770eebe597fb1803a4cb960872d58f609b2db1c86acc98d15dc0
```

If a build ever prints a different SHA-256, either a production keystore
secret is in play (good) or something is wrong with the CI config (bad —
open an issue).
