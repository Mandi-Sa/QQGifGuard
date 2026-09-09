package com.pjz.qqgifguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks whether QQ is interactively visible enough for GIF decoding to continue.
 *
 * L1 policy:
 * - Interactive when at least one activity is started, or process importance is
 *   IMPORTANCE_VISIBLE or better.
 * - Otherwise suppress render/start paths.
 *
 * This check is independent of OEM cgroup freeze behavior.
 */
final class UiVisibility {
    interface NonInteractiveListener {
        void onNonInteractive();
    }

    interface InteractiveListener {
        void onInteractive();
    }

    private static final AtomicBoolean sInteractive = new AtomicBoolean(true);
    private static final AtomicLong sLastEvalMs = new AtomicLong(0);
    private static final AtomicLong sBlockedRenderCount = new AtomicLong(0);
    private static final AtomicLong sAllowedRenderCount = new AtomicLong(0);

    private static volatile Context sAppContext;
    private static volatile int sStartedActivities = 0;
    private static volatile boolean sInstalled = false;
    private static volatile boolean sLifecycleInstalled = false;
    private static volatile NonInteractiveListener sNonInteractiveListener;
    private static volatile InteractiveListener sInteractiveListener;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private UiVisibility() {
    }

    static void install(final ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        XLog.boot("UiVisibility bootstrap");
    }

    static void setNonInteractiveListener(NonInteractiveListener listener) {
        sNonInteractiveListener = listener;
    }

    static void setInteractiveListener(InteractiveListener listener) {
        sInteractiveListener = listener;
    }

    static void onApplicationCreate(Application app) {
        if (app == null) {
            return;
        }
        sAppContext = app.getApplicationContext();
        if (sLifecycleInstalled) {
            reevaluate("app-create-again");
            return;
        }
        sLifecycleInstalled = true;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(Activity activity) {
                    sStartedActivities++;
                    markInteractive(true, "activity-started:" + safeName(activity));
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    markInteractive(true, "activity-resumed:" + safeName(activity));
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    scheduleReeval(150);
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    sStartedActivities = Math.max(0, sStartedActivities - 1);
                    scheduleReeval(50);
                }

                @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
            reevaluate("app-create");
            XLog.boot("ActivityLifecycleCallbacks registered");
        } catch (Throwable t) {
            sLifecycleInstalled = false;
            XLog.e("registerActivityLifecycleCallbacks failed", t);
        }
    }

    private static String safeName(Activity a) {
        return a == null ? "?" : a.getClass().getName();
    }

    private static void scheduleReeval(long delayMs) {
        sMain.postDelayed(() -> reevaluate("delayed"), delayMs);
    }

    private static void markInteractive(boolean value, String reason) {
        boolean prev = sInteractive.getAndSet(value);
        if (prev != value) {
            XLog.i("interactive " + prev + " -> " + value + " (" + reason + ")");
            if (!value) {
                NonInteractiveListener l = sNonInteractiveListener;
                if (l != null) {
                    try {
                        l.onNonInteractive();
                    } catch (Throwable t) {
                        XLog.w("nonInteractive listener failed: " + t.getMessage());
                    }
                }
            } else {
                InteractiveListener l = sInteractiveListener;
                if (l != null) {
                    try {
                        l.onInteractive();
                    } catch (Throwable t) {
                        XLog.w("interactive listener failed: " + t.getMessage());
                    }
                }
                GuardStats.forceSummary("interactive-resume:" + reason);
            }
        }
        sLastEvalMs.set(System.currentTimeMillis());
    }

    static void reevaluate(String reason) {
        boolean interactive = computeInteractive();
        markInteractive(interactive, reason);
    }

    private static boolean computeInteractive() {
        if (sStartedActivities > 0) {
            return true;
        }
        Context ctx = sAppContext;
        if (ctx == null) {
            // Fail-open until application context is available.
            return true;
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return sInteractive.get();
            }
            if (Build.VERSION.SDK_INT >= 23) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    int myPid = Process.myPid();
                    for (ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p != null && p.pid == myPid) {
                            return p.importance
                                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XLog.w("computeInteractive error: " + t.getMessage());
        }
        return sStartedActivities > 0;
    }

    static boolean isInteractive() {
        long now = System.currentTimeMillis();
        long last = sLastEvalMs.get();
        if (now - last > 1000L) {
            reevaluate("stale");
        }
        return sInteractive.get();
    }

    static void onRenderAllowed() {
        GuardStats.onRenderAllowed();
        long n = sAllowedRenderCount.incrementAndGet();
        if (n <= 5 || n % 200 == 0) {
            XLog.d("render allowed #" + n + " reason=interactive");
        }
    }

    static void onRenderBlocked() {
        GuardStats.onRenderBlocked();
        long n = sBlockedRenderCount.incrementAndGet();
        if (n <= 10 || n % 100 == 0) {
            XLog.i("render blocked #" + n
                    + " reason=render-guard"
                    + " interactive=" + sInteractive.get()
                    + " startedActs=" + sStartedActivities);
            GuardStats.maybeSummary("render-guard", 10_000L);
        }
    }

    static String stats() {
        return "interactive=" + sInteractive.get()
                + " startedActs=" + sStartedActivities
                + " allowed=" + sAllowedRenderCount.get()
                + " blocked=" + sBlockedRenderCount.get();
    }
}
