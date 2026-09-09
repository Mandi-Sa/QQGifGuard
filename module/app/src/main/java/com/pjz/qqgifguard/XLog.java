package com.pjz.qqgifguard;

import de.robv.android.xposed.XposedBridge;

/**
 * Thin logging helper with a stable tag for log filters.
 * Release prints boot/startup lines only. Debug keeps the full trace.
 */
final class XLog {
    private static final String TAG = "QQGifGuard";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private XLog() {
    }

    static void boot(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    static void i(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": " + msg);
        }
    }

    static void d(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": " + msg);
        }
    }

    static void w(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + ": WARN " + msg);
        }
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + ": ERROR " + msg);
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}
