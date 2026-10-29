package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, Xiaomi remotes KEYCODE_TV_INPUT; on Google TV the stock launcher handles that key,
 * so with the stock launcher disabled the button does nothing. Xiaomi remotes also lose their YouTube button
 * (KEYCODE_BUTTON_3) and Apps button (KEYCODE_ALL_APPS, which Android sends to a Settings screen that doesn't
 * exist); those open YouTube and this launcher. Their Settings button (KEYCODE_SETTINGS) goes to Xiaomi's partner
 * customizer, which opens the stock launcher's settings panel: with that launcher gone it crashes, and after a few
 * presses Android stops giving it any key, Input, Netflix and Prime included, until a reboot. Here it opens the
 * TV's Settings instead. Only these keys are consumed; every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    /** restore_key_services was on when the service connected: it is noted for ServiceRepair. */
    private boolean repairRecord;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        repairRecord = getResources().getBoolean(R.bool.restore_key_services);
        if (repairRecord) ServiceRepair.connected(this, getClass());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Intent intent;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_TV_INPUT:
                intent = new Intent(ACTION_VIEW_INPUTS);
                break;
            case KeyEvent.KEYCODE_BUTTON_3:
                intent = getPackageManager().getLeanbackLaunchIntentForPackage(YOUTUBE);
                if (intent == null) return false;   // no YouTube: leave the key to whoever wants it
                break;
            case KeyEvent.KEYCODE_ALL_APPS:
                intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                break;
            case KeyEvent.KEYCODE_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
