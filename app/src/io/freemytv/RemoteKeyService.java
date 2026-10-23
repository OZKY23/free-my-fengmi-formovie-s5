package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        RemoteWeb.start(this);
        // BOOT_COMPLETED never reaches a freshly updated app (Android puts it in the stopped state), so the pseudo
        // home opens itself from here: the system binds this service at boot whatever the app's state is. Only
        // shortly after a boot, so a later rebind never pops the home over something the person opened.
        if (HomeMode.pseudoHome(this) && SystemClock.elapsedRealtime() < 180_000) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (HomeMode.pseudoHome(this)) {
                    AppLog.w("boot: opening the home (pseudo home)");
                    startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }, 8_000);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onInterrupt() {}

    /** The package of the boot-time home (FallbackHome): the screen the system shows after a boot or a wake. */
    private static final String FALLBACK = "com.android.tv.settings";

    /** When we last started the home: skipping the fallback's window events that follow right after. */
    private long homeStartedAt;

    /**
     * Pseudo home: whenever FallbackHome comes to the foreground, ours replaces it. That happens after every boot
     * and after every wake from standby (the system then drops the activities), and BOOT_COMPLETED doesn't always
     * reach us (an update leaves the app in the stopped state). Any other app (Kodi, Netflix…) is left alone.
     */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !FALLBACK.contentEquals(pkg)) return;
        if (!HomeMode.pseudoHome(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - homeStartedAt < 4000) return;   // already on it
        homeStartedAt = now;
        AppLog.w("fallback home -> Free My TV");
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
