# QQ GIF Guard

LSPosed module that prevents QQ chat GIFs from continuously decoding in the background or freeform window, which otherwise leads to sustained high CPU usage.

## Problem

On QQ `9.1.25 (8368)`, chat GIFs are rendered through Tencent Libra:

```text
RenderTask.e()
  -> GifInfoHandle.x(Bitmap)
     -> native renderFrame(long, Bitmap)   # libgiflibra.so
```

`GifDrawable.stop()` only saves remainder state. It does **not** free the native handle. After leaving a chat session or keeping QQ in freeform/background, scheduled render work can keep running.

OEM freezers may hide the symptom by freezing the whole UID. Once unfrozen, the same process often resumes GIF spin without returning to the foreground.

## Solution

Layered control for QQ Libra GIFs:

1. **L1** – when QQ is not interactively visible, suppress `RenderTask.e`, `GifInfoHandle.x` / `renderFrame`, decoder start, and `GifDrawable.start`; on `setVisible(false)` force `stop()`
2. **L1.5** – periodically stop orphan invisible/detached running drawables
3. **L2 (limited)** – if a drawable stays hidden/detached beyond a timeout, call `recycle()` to free native handle/bitmap (recoverable destruction, not instant free-on-hide)

Foreground visible GIFs still play. Quick scroll-back cancels the recycle timer.

## Project layout

```text
module/     # Android / LSPosed module sources
scripts/    # Windows / Linux build helpers
docs/       # Architecture and verification
dist/       # Build output (gitignored)
```

## Requirements

- JDK 17+ (JDK 21 recommended)
- Android SDK with `platforms;android-34` and `build-tools;34.x`
- LSPosed (Zygisk) on the target device
- Target app: `com.tencent.mobileqq` 9.1.25 (8368)

## Build

Requirements:

- JDK 17+ (21 recommended)
- Android SDK (platforms;android-34, build-tools;34.x)
- ANDROID_HOME or ANDROID_SDK_ROOT
- JAVA_HOME recommended

### Windows

```bat
scripts\build.bat
scripts\build.bat release
```

### Linux / macOS

```bash
chmod +x scripts/build.sh
./scripts/build.sh
./scripts/build.sh release
```

### Manual Gradle

```bash
cd module
# optional: create local.properties with sdk.dir=<Android SDK path>
./gradlew :app:assembleDebug
```

Output:

```text
dist/QQGifGuard-debug.apk
dist/QQGifGuard-release.apk
# or
module/app/build/outputs/apk/debug/app-debug.apk
module/app/build/outputs/apk/release/app-release.apk
```

`debug` keeps the full `QQGifGuard` trace. `release` prints startup/boot lines only.

## Install

```bash
adb install -r dist/QQGifGuard-debug.apk
```

In LSPosed Manager:

1. Enable **QQ GIF Guard**
2. Scope only **QQ** (`com.tencent.mobileqq`)
3. Force-stop QQ, then cold start it

> Installing the module does not repair an already leaking process. Always validate after a cold start.

## Verify

```bash
# Correct: filter by message prefix
adb logcat -v time | grep --line-buffered QQGifGuard

# On device / SSH
logcat -v time | grep --line-buffered QQGifGuard
# or
grep QQGifGuard /data/adb/lspd/log/modules_*.log | tail
```

Do **not** use `logcat -s QQGifGuard` — the Android tag is typically `LSPosedFramework`, while the stable message prefix is `QQGifGuard:`.

Expected:

- Module loads in `com.tencent.mobileqq`
- Foreground chat GIFs still play (`render allowed`)
- Leaving chat / freeform triggers stop / recycle / `render blocked` with explicit reasons
- Periodic `stats reason=...` lines show tracked / stopped / recycled / blocked counters
- After idle or UID unfreeze, no sustained `pool-*` / `libgiflibra` CPU spin

See `docs/verification.md` for the full 0.2.3 regression checklist and observation commands. Action lines (`render allowed`, `stats`, stop/recycle) are on the debug APK.

## Version lock

Validated against:

| Item | Value |
|---|---|
| Package | `com.tencent.mobileqq` |
| versionName | `9.1.25` |
| versionCode | `8368` |
| Native lib | `libgiflibra.so` |
| Java package | `com.tencent.libra.extension.gif` |

## Limitations

- L1/L1.5 stop animation work; L2 only delayed-recycles stale drawables (not instant free-on-hide)
- Timeout-based recycle is a complement to, not a full replacement for, real detach/unbind hooks
- Sticker panel / preview false positives need policy tuning if observed
- Installing over a already-leaking QQ process does not self-heal it; cold start is required
- Hooking QQ has residual detection surface; use at your own risk
