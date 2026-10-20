package com.gathin.tvhome;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fire TV: Fire OS sends Home to Amazon's launcher and won't let it be disabled or out-ranked. This service
 * follows the system log and, the moment Amazon's home activity is started (Home, boot) or resumed (Back out of an
 * app that was not opened from here), opens this launcher on top of it. Needs READ_LOGS, granted over adb (see
 * FIRETV.md); without it the service stops itself.
 *
 * After a Home press Android holds back activity starts from other apps for five seconds, so the launcher only
 * appears then. To hide Amazon's home meanwhile, the service puts a snapshot of the launcher over the screen
 * (an overlay is not held back; needs the SYSTEM_ALERT_WINDOW app-op) and drops it once the launcher is up. Remote
 * presses made on the snapshot are kept and replayed into the launcher when it appears.
 * Amazon's launcher itself stays resident: Fire OS refuses to kill it ("Cannot kill protected package").
 *
 * It runs as a foreground service: Fire OS then neither kills it under memory pressure nor puts its log reader in
 * the throttled background CPU group, where a Home press once took 674 ms to be seen.
 */
public class HomeWatchService extends Service {

    private static final String TAG = "HomeWatch";
    /** Logged as "START u0 {act=… cat=[android.intent.category.HOME] … cmp=<this>…}" and as am_resume_activity. */
    private static final String AMAZON_HOME = "com.amazon.tv.launcher/.ui.HomeActivity";
    /** Set by BootReceiver: cover the screen at once, there is no launcher to bounce to yet. */
    private static final String EXTRA_COVER = "cover";
    /** Longer than Android's five-second app-switch hold, in case the launcher never comes up. */
    private static final long COVER_TIMEOUT_MS = 7000L;
    /** A Home press logs a start and then a resume of Amazon's home; act on the first only. */
    private static final long DEDUPE_MS = 2000L;
    private static final int MAX_KEYS = 12;
    private static final String CHANNEL = "homewatch";

    /** Latest frame of the launcher, kept fresh by MainActivity. Same process, so a static. */
    static Bitmap snapshot;
    private static HomeWatchService running;
    /**
     * Set while the person is in Amazon's own Settings row ("All settings"): Amazon's home is wanted then. A Home
     * press (started by the system, uid 1000), Back out of the row, or this launcher coming back ends it.
     */
    private static volatile boolean amazonHomeAllowed;
    /** Amazon's launcher logs this when its Settings row goes away: Back to its other tabs, or a screen opened. */
    private static final String AMAZON_SETTINGS_ROW_GONE = "onStop() is called by class com.amazon.tv.launcher.ui"
            + ".sceneflow.presenters.model1.SettingsNodePresenterDI";
    /** Amazon's home activity is paused (a settings screen is open over it). Reader thread only. */
    private boolean amazonPaused;
    /** Remote presses made on the cover, replayed by MainActivity. Main thread only. */
    private static final List<Integer> pendingKeys = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread reader;
    private volatile Process logcat;
    private View cover;
    private long lastBounce = -DEDUPE_MS;
    private final Runnable dropCover = this::removeCover;

    static void start(Context ctx) {
        start(ctx, false);
    }

    static void start(Context ctx, boolean cover) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return;
        Intent i = new Intent(ctx, HomeWatchService.class).putExtra(EXTRA_COVER, cover);
        // Android 8+: a plain startService() from the background (boot receiver, restart) is refused.
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            // Android 8+: a foreground notification without a valid channel crashes the app ("Bad notification").
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        @SuppressWarnings("deprecation")
        Notification n = b
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = this;
        if (reader == null || !reader.isAlive()) {
            reader = new Thread(this::follow, TAG);
            reader.start();
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_COVER, false)) showCover();
        return START_STICKY;
    }

    private void follow() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // ActivityManager's START lines are in the system buffer, am_resume/pause_activity in events,
                // Amazon's own SceneNodePresenter lines in main.
                // -T <now>: only lines from here on. "-T 1" replays the newest old line, which after a reinstall
                // is Amazon's home resuming, and put up a stray cover that swallowed keys.
                String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                logcat = new ProcessBuilder("logcat", "-b", "system", "-b", "events", "-b", "main", "-v", "brief",
                        "-T", since, "ActivityManager:I", "am_resume_activity:I", "am_pause_activity:I",
                        // Android 10+ (Fire OS 8): START is logged by ActivityTaskManager, events are wm_*.
                        "ActivityTaskManager:I", "wm_resume_activity:I", "wm_pause_activity:I",
                        "SceneNodePresenter:I", "*:S")
                        .redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.contains(AMAZON_SETTINGS_ROW_GONE)) {
                        // Left Amazon's Settings row with Back, not into one of its screens: back to this launcher.
                        if (amazonHomeAllowed && !amazonPaused) {
                            amazonHomeAllowed = false;
                            final long seen = SystemClock.uptimeMillis();
                            handler.postAtFrontOfQueue(() -> goHome(seen, false));
                        }
                        continue;
                    }
                    if (!line.contains(AMAZON_HOME)) {
                        // Some other app came to the front (the launcher could not): never leave the cover over it.
                        if ((line.startsWith("I/wm_resume_activity") || line.startsWith("I/am_resume_activity"))
                                && !line.contains(getPackageName())) {
                            handler.post(this::removeCover);
                        }
                        continue;
                    }
                    if (line.startsWith("I/am_pause_activity") || line.startsWith("I/wm_pause_activity")) {
                        amazonPaused = true;
                        continue;
                    }
                    boolean started = line.contains("START u0") && line.contains("android.intent.category.HOME");
                    boolean resumed = line.startsWith("I/am_resume_activity") || line.startsWith("I/wm_resume_activity");
                    if (resumed) amazonPaused = false;
                    if (amazonHomeAllowed) {
                        // The Home key's START comes from uid 1000 on Fire OS 6, uid 0 on Fire OS 8.
                        if (!started || !line.matches(".*from uid (0|1000)( .*)?$")) continue;
                        amazonHomeAllowed = false;   // Home pressed: back to this launcher
                    }
                    if (started || resumed) {
                        final long seen = SystemClock.uptimeMillis();
                        final boolean homeKey = started;
                        handler.postAtFrontOfQueue(() -> goHome(seen, homeKey));
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "logcat ended", e);
            }
            if (logcat != null) logcat.destroy();
            try {
                Thread.sleep(1000);   // logcat died (log rotation, OOM): follow it again
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** MainActivity is about to open Amazon's Settings row. */
    static void allowAmazonHome() {
        amazonHomeAllowed = true;
    }

    /** Called by MainActivity once it is on screen again. Returns the presses made on the cover, oldest first. */
    static List<Integer> launcherShown() {
        amazonHomeAllowed = false;
        HomeWatchService s = running;
        if (s != null && s.cover != null) s.handler.postDelayed(s.dropCover, 150);   // let its first frame land
        List<Integer> keys = new ArrayList<>(pendingKeys);
        if (!keys.isEmpty()) Log.i(TAG, "replaying " + keys.size() + " keys from the cover");
        pendingKeys.clear();
        return keys;
    }

    private void showCover() {
        if (cover != null) {
            handler.removeCallbacks(dropCover);   // hold restarted: keep the hard timeout ahead of it
            handler.postDelayed(dropCover, COVER_TIMEOUT_MS);
            return;
        }
        if ((Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))) return;
        pendingKeys.clear();
        // The cover's root view gets every key sent to its window, focused child or not. Keys meant for Amazon's
        // home underneath: keep the navigation ones for the launcher, drop the rest.
        ImageView v = new ImageView(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int keyCode = event.getKeyCode();
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                        && isNavigation(keyCode) && pendingKeys.size() < MAX_KEYS) {
                    pendingKeys.add(keyCode);
                }
                return true;
            }
        };
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setBackgroundColor(Color.BLACK);
        if (snapshot != null) v.setImageBitmap(snapshot);
        @SuppressWarnings("deprecation")
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp);
            cover = v;
            handler.postDelayed(dropCover, COVER_TIMEOUT_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "cover failed", e);
        }
    }

    private static boolean isNavigation(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return true;
            default:
                return false;
        }
    }

    private void removeCover() {
        handler.removeCallbacks(dropCover);
        if (cover == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (RuntimeException ignored) {
        }
        cover = null;
    }

    private void goHome(long seen, boolean homeKey) {
        // A second Home press drops the start still held back from the first one and restarts Android's
        // five-second hold, so every Home START must ask again; only the resume that follows a start is a duplicate.
        if (!homeKey && seen - lastBounce < DEDUPE_MS) return;
        lastBounce = seen;
        showCover();
        Log.i(TAG, "cover up after " + (SystemClock.uptimeMillis() - seen) + " ms");
        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
            Log.i(TAG, "home bounce after " + (SystemClock.uptimeMillis() - seen) + " ms");
        } catch (ActivityNotFoundException ignored) {
            // Launcher disabled: leave Home to Amazon.
        }
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        removeCover();
        if (reader != null) reader.interrupt();
        if (logcat != null) logcat.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
