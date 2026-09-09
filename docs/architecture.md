# Architecture

## Goals

- Prevent recurrence of background/freeform GIF CPU spin
- Keep foreground GIF playback working
- Prefer Java-layer LSPosed hooks over native binary patching
- Make stop / recycle / block decisions observable from short log windows

## Non-goals

- Killing QQ processes as a feature
- Relying on OEM cgroup freeze as a fix
- Shipping Frida scripts as the long-term solution
- Instant free on every hide (breaks quick scroll resume)

## Runtime model

```text
QQ process load
  -> MainHook.handleLoadPackage
      -> UiVisibility lifecycle tracking
      -> GuardStats counters
      -> hook RenderTask / GifInfoHandle / GifDrawable
      -> maintenance sweeper (stop orphans + delayed recycle)

Foreground / visible drawable
  -> allow renderFrame path
  -> cancel hidden timer

Hidden / detached / non-interactive
  -> stop immediately (L1 / L1.5)
  -> recycle only after timeout (L2)
  -> suppress render/start as safety net
```

## Key classes

| Class | Role |
|---|---|
| `MainHook` | LSPosed entry, installs hooks once per process, runs maintenance sweeper |
| `UiVisibility` | Interactive-state estimator |
| `GifDrawableTracker` | Per-drawable track / stop / delayed recycle |
| `GuardStats` | tracked / stopped / recycled / blocked counters + summaries |
| `XLog` | Stable `QQGifGuard` prefix; release prints boot lines, debug keeps the full trace |

## Hook surface (9.1.25)

| Target | Method | Behavior when not interactive / not keep-animating |
|---|---|---|
| `RenderTask` | `e()` | skip body; may stop detached drawable |
| `GifInfoHandle` | `x(Bitmap)` | return `0L` |
| `GifInfoHandle` | `renderFrame(long,Bitmap)` | return `0L` |
| `GifInfoHandle` | `startDecoderThread(long)` | no-op |
| `GifDrawable` | `start()` | no-op when blocked/recycled/not animatable |
| `GifDrawable` | `setVisible(false, ...)` | force `stop()` and start hidden timer |
| `GifDrawable` | `setVisible(true, ...)` | clear hidden timer; try `start()` if interactive |

## Interactive policy

A process is considered interactive when:

1. `startedActivityCount > 0`, or
2. `RunningAppProcessInfo.importance <= IMPORTANCE_VISIBLE`

Otherwise GIF animation paths are suppressed and tracked drawables are stopped more aggressively.

## Release strategy layers

| Layer | Behavior |
|---|---|
| L1 | Block render/start while non-interactive; `setVisible(false)` stops |
| L1.5 | Periodic orphan sweep stops invisible/detached running drawables |
| L2 | Delayed `recycle()` after hidden/detached timeout (recoverable destruction) |

Current timeouts (code constants):

- foreground hidden / detached: about 8s
- app non-interactive: about 5s
- maintenance interval: 2s

## Diagnostics

Action reasons commonly seen in logs:

- `setVisible`
- `sweep-orphans`
- `sweep-stale-recycle`
- `sweep-bg-recycle`
- `app-non-interactive`
- `render-guard`
- `maintenance`

`stats reason=...` summaries include live tracked count plus cumulative stop/recycle/block counters.

## Failure modes

| Mode | Mitigation |
|---|---|
| Method renamed by hotfix | log method dump; fail soft |
| Lifecycle callbacks missing | fail-open until context ready |
| Already leaked old process | require cold start |
| Freezer hides residual work | validate after UID unfreeze |
| Quick scroll resume broken | delay recycle; cancel timer on visible again |

## Roadmap

### L1 + L1.5 + limited L2 (implemented)

Background/freeform render suppression, visibility stop, orphan sweep, delayed recycle, diagnostics.

### Next hardening

- tighter lifecycle hooks (detach / unbind / page destroy)
- stricter recycle predicates
- timeout tuning and scene allowlists
- process-scope noise reduction

### Optional later

- multi-instance occupancy counting if needed
- native path only if Java remains insufficient
