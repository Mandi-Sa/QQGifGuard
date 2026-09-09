# Verification checklist

Baseline: module `0.2.3` behavior with L1 stop + L1.5 orphan sweep + L2 delayed recycle.
Always cold-start QQ after installing or updating the module before judging results.
Use the **debug** APK when grepping action lines (`stop` / `recycleStale` / `render allowed` / `stats`). The **release** APK prints boot/startup lines only (`loading`, `hooks installed`, hook OK).

## Functional (0.2.3 regression)

- [ ] Foreground chat GIF animates normally
- [ ] Fast scroll up/down and return within about 8s still resumes playback
- [ ] Off-screen for longer than about 8s may resume with a short delay after scroll-back, but must not crash or stay permanently dead
- [ ] Leaving chat or switching to another app stops sustained `pool-*-thread` GIF spin
- [ ] Idle 5-10 minutes: `render allowed` must not keep climbing at a steady rate
- [ ] Freeform / small-window GIF chat does not sustain high CPU after leaving focus

## Before / after metrics to capture

Record these on the same cold-started process:

| Metric | How | Pass signal |
|---|---|---|
| `pool-*` jiffies growth | sample `/proc/<pid>/task/*/stat` for GIF pool threads twice, 30-60s apart after leave-chat | growth stops or becomes near-zero |
| `chatraw` FD count | `ls -l /proc/$PID/fd \| grep -c chatraw` | no unbounded climb while idle / background |
| Guard action log | last 30-60s of `QQGifGuard` lines | stops / recycle / block reasons appear when expected; no endless allowed-only flood while idle |

## Log signals

**Correct filters** (message prefix is `QQGifGuard:`, log tag is usually `LSPosedFramework`):

```bash
logcat -v time | grep --line-buffered QQGifGuard
# or
adb logcat -v time | grep --line-buffered QQGifGuard
```

LSPosed on-device module logs:

```bash
grep QQGifGuard /data/adb/lspd/log/modules_*.log | tail -n 100
```

**Do not use** `logcat -s QQGifGuard` — that filter keys on the Android log tag, not the message prefix, so it often shows nothing.

Useful lines:

- `hooks installed`
- `stop ... reason=setVisible|sweep-orphans|app-non-interactive|...`
- `recycleStale reason=sweep-stale-recycle|sweep-bg-recycle|app-non-interactive ...`
- `render blocked ... reason=render-guard`
- `stats reason=... trackedCreates=... liveTracked=... stopped=... recycled=... startBlocked=... renderAllowed=... renderBlocked=...`

### How to read a failure in ~30s

| Symptom | Likely cause | What logs show |
|---|---|---|
| CPU keeps spinning after leave | stop path missed | `render allowed` still rising; few/no `stop` / `recycleStale` |
| GIF never resumes after quick scroll | recycle too aggressive | many `recycleStale` shortly after hide; resume start fails |
| Leave chat looks fine, idle later explodes | orphan/stale leak | delayed `pool-*` growth; `stopOrphans` / `recycleStale` absent or ineffective |
| Foreground frozen until re-enter chat | visibility resume gap | `setVisible(true)` without successful `start`, or start blocked while interactive |

## Observation commands

```bash
# 1) Logs
logcat -v time | grep --line-buffered QQGifGuard

# 2) QQ main pid
PID=$(pidof com.tencent.mobileqq)
echo "PID=$PID"

# 3) GIF-related pool threads
for t in /proc/$PID/task/*; do
  comm=$(cat "$t/comm" 2>/dev/null)
  case "$comm" in
    pool-*) echo "$(basename "$t") $comm";;
  esac
done | sort

# 4) chatraw FD pressure
ls -l /proc/$PID/fd 2>/dev/null | grep -c chatraw

# 5) Optional: unfreeze then re-check (OEM freezer can hide residual work)
# QQ_UID=$(stat -c %u /data/data/com.tencent.mobileqq)
# echo 0 > /sys/fs/cgroup/apps/uid_${QQ_UID}/cgroup.freeze
```

Windows SSH one-liners against the phone are fine as long as they ultimately run the device-side commands above.

## Pass criteria

- No sustained Libra GIF render loop after leave-chat / background / unfreeze
- No crash loop introduced by hooks
- Foreground playback remains acceptable
- Idle hang does not produce a steady `render allowed` staircase
- Diagnostics make the above decidable from a short log + thread/FD sample
