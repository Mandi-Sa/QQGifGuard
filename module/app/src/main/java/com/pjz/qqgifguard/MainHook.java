package com.pjz.qqgifguard;

import android.app.Application;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry for QQ GIF Guard.
 *
 * Target: QQ 9.1.25 / 9.1.60 Libra GIF path, package com.tencent.mobileqq.
 *
 * Strategy:
 * L1  - stop/block animation when not needed
 * L1.5- sweep orphan running drawables
 * L2  - conservative delayed recycle() only for stable detached orphans
 *       (no foreground recycle sweep; avoid black images until rebind)
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG = "com.tencent.mobileqq";
    private static final String CLS_GIF_INFO = "com.tencent.libra.extension.gif.GifInfoHandle";
    private static final String CLS_GIF_DRAWABLE = "com.tencent.libra.extension.gif.GifDrawable";
    private static final String CLS_RENDER_TASK = "com.tencent.libra.extension.gif.RenderTask";

    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sSweepStarted = new AtomicBoolean(false);

    private static final long ORPHAN_SWEEP_INTERVAL_MS = 2000L;
    /**
     * Foreground L2 is disabled (0 => skip). Black-image reports show current-screen
     * items can look "detached" briefly during QQ rebind; stop-only is safer.
     */
    private static final long RECYCLE_AFTER_HIDDEN_MS = 0L;
    /** Background only: stable orphans may be recycled after a longer grace period. */
    private static final long RECYCLE_AFTER_BG_MS = 20000L;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        if (!sHooked.compareAndSet(false, true)) {
            return;
        }

        XLog.boot("loading in " + lpparam.packageName
                + " process=" + lpparam.processName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        UiVisibility.install(lpparam.classLoader);
        UiVisibility.setNonInteractiveListener(() -> {
            // Immediate CPU protection only. Do not recycle on the transition edge:
            // QQ 9.1.60 may transiently detach callbacks while leaving chat.
            GifDrawableTracker.stopAll("app-non-interactive");
            GuardStats.forceSummary("app-non-interactive");
        });
        UiVisibility.setInteractiveListener(() -> {
            // Returning to QQ: restart still-attached GIFs that we only stopped.
            GifDrawableTracker.resumeAttached("app-interactive");
            // Second pass after first layout/visibility settles (QQ 9.1.60 leave/return).
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (UiVisibility.isInteractive()) {
                    GifDrawableTracker.resumeAttached("app-interactive-deferred");
                }
            }, 400L);
        });

        hookApplicationCreate(lpparam.classLoader);
        hookGifInfoHandle(lpparam.classLoader);
        hookGifDrawable(lpparam.classLoader);
        hookRenderTask(lpparam.classLoader);
        startMaintenanceSweeper();

        XLog.boot("hooks installed (L1+L2 conservative orphan recycle)");
    }

    private void startMaintenanceSweeper() {
        if (!sSweepStarted.compareAndSet(false, true)) {
            return;
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable sweep = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!UiVisibility.isInteractive()) {
                        GifDrawableTracker.stopAll("sweep-non-interactive");
                        if (RECYCLE_AFTER_BG_MS > 0L) {
                            GifDrawableTracker.recycleStale("sweep-bg-recycle", RECYCLE_AFTER_BG_MS);
                        }
                    } else {
                        GifDrawableTracker.stopOrphans("sweep-orphans");
                        // Foreground recycle intentionally off by default.
                        if (RECYCLE_AFTER_HIDDEN_MS > 0L) {
                            GifDrawableTracker.recycleStale("sweep-stale-recycle", RECYCLE_AFTER_HIDDEN_MS);
                        }
                    }
                    // Heartbeat summary so idle regressions are visible within ~30s.
                    GuardStats.maybeSummary("maintenance", 30_000L);
                } catch (Throwable t) {
                    XLog.w("maintenance sweep failed: " + t.getMessage());
                } finally {
                    h.postDelayed(this, ORPHAN_SWEEP_INTERVAL_MS);
                }
            }
        };
        h.postDelayed(sweep, ORPHAN_SWEEP_INTERVAL_MS);
        XLog.boot("maintenance sweeper started intervalMs=" + ORPHAN_SWEEP_INTERVAL_MS
                + " recycleAfterHiddenMs=" + RECYCLE_AFTER_HIDDEN_MS
                + " recycleAfterBgMs=" + RECYCLE_AFTER_BG_MS);
    }

    private void hookApplicationCreate(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object app = param.thisObject;
                            if (app instanceof Application) {
                                UiVisibility.onApplicationCreate((Application) app);
                            }
                        }
                    }
            );
            XLog.boot("hook Application.onCreate OK");
        } catch (Throwable t) {
            XLog.e("hook Application.onCreate failed", t);
        }
    }

    private void hookGifInfoHandle(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GIF_INFO, cl);

            XposedBridge.hookAllMethods(cls, "x", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        param.setResult(0L);
                    } else {
                        UiVisibility.onRenderAllowed();
                    }
                }
            });
            XLog.boot("hook GifInfoHandle.x OK");

            try {
                XposedBridge.hookAllMethods(cls, "renderFrame", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!UiVisibility.isInteractive()) {
                            UiVisibility.onRenderBlocked();
                            param.setResult(0L);
                        }
                    }
                });
                XLog.boot("hook GifInfoHandle.renderFrame OK");
            } catch (Throwable t) {
                XLog.boot("hook renderFrame skipped: " + t.getMessage());
            }

            try {
                XposedBridge.hookAllMethods(cls, "startDecoderThread", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!UiVisibility.isInteractive()) {
                            GuardStats.onStartBlocked();
                            XLog.i("block startDecoderThread reason=app-non-interactive (" + UiVisibility.stats() + ")");
                            param.setResult(null);
                        }
                    }
                });
                XLog.boot("hook GifInfoHandle.startDecoderThread OK");
            } catch (Throwable t) {
                XLog.boot("hook startDecoderThread skipped: " + t.getMessage());
            }

            dumpMethods(cls, "GifInfoHandle");
        } catch (Throwable t) {
            XLog.e("hook GifInfoHandle failed", t);
        }
    }

    private void hookGifDrawable(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GIF_DRAWABLE, cl);

            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    GifDrawableTracker.track(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(cls, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object d = param.thisObject;
                    GifDrawableTracker.track(d);

                    if (GifDrawableTracker.isRecycled(d)) {
                        // Already destroyed; let original start fail soft or no-op via block.
                        GuardStats.onStartBlocked();
                        XLog.d("block start reason=recycled");
                        param.setResult(null);
                        return;
                    }
                    if (!UiVisibility.isInteractive()) {
                        GuardStats.onStartBlocked();
                        XLog.i("block start reason=app-non-interactive (" + UiVisibility.stats() + ")");
                        param.setResult(null);
                        return;
                    }
                    if (!GifDrawableTracker.shouldKeepAnimating(d)) {
                        GifDrawableTracker.markHidden(d);
                        GuardStats.onStartBlocked();
                        XLog.d("block start reason=not-animatable");
                        param.setResult(null);
                        return;
                    }
                    GifDrawableTracker.markVisible(d);
                }
            });

            XposedBridge.hookAllMethods(cls, "setVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object d = param.thisObject;
                        GifDrawableTracker.track(d);
                        boolean visible = (Boolean) param.args[0];

                        if (!visible) {
                            GifDrawableTracker.markHidden(d);
                            if (safeStop(d)) {
                                GuardStats.onStopped(1);
                                XLog.d("stop reason=setVisible visible=false");
                            } else {
                                XLog.d("markHidden reason=setVisible visible=false");
                            }
                            GuardStats.maybeSummary("setVisible", 10_000L);
                            return;
                        }

                        // Visible again: cancel pending recycle timer.
                        GifDrawableTracker.markVisible(d);

                        if (!UiVisibility.isInteractive()
                                || !GifDrawableTracker.shouldKeepAnimating(d)
                                || GifDrawableTracker.isRecycled(d)) {
                            safeStop(d);
                            return;
                        }

                        boolean running = false;
                        try {
                            Object r = XposedHelpers.callMethod(d, "isRunning");
                            running = r instanceof Boolean && (Boolean) r;
                        } catch (Throwable ignored) {
                        }
                        if (!running) {
                            // If we recycled earlier, start() may no-op/fail; QQ usually recreates drawable.
                            try {
                                XposedHelpers.callMethod(d, "start");
                                XLog.d("GifDrawable.setVisible(true) -> start()");
                            } catch (Throwable t) {
                                XLog.w("resume start failed (maybe recycled): " + t.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        XLog.w("setVisible after failed: " + t.getMessage());
                    }
                }
            });

            // Observe recycle to mark state.
            try {
                XposedBridge.hookAllMethods(cls, "recycle", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        GifDrawableTracker.markHidden(param.thisObject);
                        XLog.d("GifDrawable.recycle() observed");
                    }
                });
            } catch (Throwable ignored) {
            }

            XLog.boot("hook GifDrawable.start/setVisible OK");
            dumpMethods(cls, "GifDrawable");
        } catch (Throwable t) {
            XLog.e("hook GifDrawable failed", t);
        }
    }

    private void hookRenderTask(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_RENDER_TASK, cl);
            final Field drawableField = findDrawableField(cls);

            XposedBridge.hookAllMethods(cls, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        param.setResult(null);
                        return;
                    }

                    Object drawable = null;
                    try {
                        if (drawableField != null) {
                            drawable = drawableField.get(param.thisObject);
                        } else {
                            drawable = XposedHelpers.getObjectField(param.thisObject, "e");
                        }
                    } catch (Throwable ignored) {
                    }

                    if (drawable != null) {
                        GifDrawableTracker.track(drawable);
                        if (GifDrawableTracker.isRecycled(drawable)
                                || !GifDrawableTracker.shouldKeepAnimating(drawable)) {
                            GifDrawableTracker.markHidden(drawable);
                            if (safeStop(drawable)) {
                                GuardStats.onStopped(1);
                            }
                            UiVisibility.onRenderBlocked();
                            param.setResult(null);
                            return;
                        }
                        GifDrawableTracker.markVisible(drawable);
                    }
                }
            });
            XLog.boot("hook RenderTask.e OK");
        } catch (Throwable t) {
            XLog.boot("hook RenderTask.e skipped: " + t.getMessage());
        }
    }

    private static boolean safeStop(Object d) {
        try {
            boolean running = true;
            try {
                Object r = XposedHelpers.callMethod(d, "isRunning");
                if (r instanceof Boolean) {
                    running = (Boolean) r;
                }
            } catch (Throwable ignored) {
            }
            if (running) {
                XposedHelpers.callMethod(d, "stop");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Field findDrawableField(Class<?> renderTaskCls) {
        try {
            Class<?> c = renderTaskCls;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField("e");
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void dumpMethods(Class<?> cls, String label) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(label).append(" methods:");
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("render") || n.contains("start") || n.contains("stop")
                        || n.equals("x") || n.equals("e") || n.equals("B") || n.equals("w")
                        || n.contains("Visible") || n.contains("recycle") || n.contains("Decoder")) {
                    sb.append(' ').append(n).append(params(m));
                }
            }
            XLog.i(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String params(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }
}
