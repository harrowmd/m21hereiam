package com.example.m21hereiam;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import java.util.Arrays;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.ByteArrayOutputStream;
import android.media.AudioAttributes;
import android.media.Image;
import android.media.ImageReader;
import android.os.HandlerThread;
import android.util.Size;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONObject;

public class LocationService extends Service implements LocationListener {

    static final String TAG = "HereIAmNow";

    private static final String CHANNEL_ID = "hereiamnow_tracking";
    private static final int    NOTIF_ID   = 1;

    static final String PREFS                = "hereiamnow";
    static final String PREF_INTERVAL        = "update_interval_sec";
    static final String PREF_UPLOAD_INTERVAL = "upload_interval_sec";
    static final String PREF_NC_URL          = "nextcloud_url";
    static final String PREF_NC_USER         = "nextcloud_user";
    static final String PREF_NC_PASS         = "nextcloud_pass";
    static final String PREF_SESSION         = "session";
    static final String PREF_ALERT_CODE      = "alert_code";
    static final String PREF_ALERT_PHOTOS    = "alert_photos";
    static final String PREF_START_ON_BOOT   = "start_on_boot";
    static final String PREF_MIN_SAT         = "min_sat";
    static final String PREF_DISPLAY_PERIOD  = "display_period_hours";
    static final String PREF_NUM_GPS_FIXES   = "num_gps_fixes";
    static final String PREF_W3W_KEY         = "w3w_api_key";
    static final String PREF_TRACK_COLOUR    = "track_colour";
    static final String PREF_RETENTION_DAYS  = "retention_days";
    static final String PREF_MAP_TYPE        = "map_type";
    static final String PREF_ALERT_VOLUME    = "alert_volume";

    private static final String[] LOG_SUFFIXES = {
        "-hia.csv", "-hia.gpx", "-hia.kml", "-hia.txt"
    };
    // Settings backup suffix — deliberately not in LOG_SUFFIXES: that array also drives the
    // upload loop's local-file-existence check (today + suffix), and the local settings snapshot
    // is never date-prefixed. Folded into cleanup separately instead — see deleteOldNextcloudFiles.
    private static final String SETTINGS_UPLOAD_SUFFIX = "-settings-hia.json";
    private static final String GPX_CLOSE =
        "    </trkseg>\n  </trk>\n</gpx>\n";
    // KML in-memory track (reloaded from CSV on service restart)
    private final java.util.List<String>   kmlTimestamps  = new java.util.ArrayList<>();
    private final java.util.List<double[]> kmlLatLon      = new java.util.ArrayList<>();
    private final java.util.List<Double>   lapAltitudes   = new java.util.ArrayList<>();
    private String kmlCurrentDate = "";

    // ── Settings ──────────────────────────────────────────────────────────────
    long   updateInterval = 60_000;
    long   uploadInterval = 300_000;
    String nextcloudUrl   = "https://cloud.example.com";
    String nextcloudUser  = "";
    String nextcloudPass  = "";
    String session        = "mobyphone";
    String  alertCode          = "911911";
    int     alertPhotos        = 3;
    boolean startOnBoot        = true;
    int     minSat             = 4;
    int     displayPeriodHours = 12;
    int     numGpsFixes        = 5;
    String  w3wApiKey          = "";
    String  trackColour        = "None";
    int     retentionDays      = 31;
    String  mapType            = "Land";
    String  alertVolume        = "High"; // Zero, Low, Medium, High
    volatile String w3wAddress = "";
    volatile int    w3wBackoffTicks = 0;
    volatile int    w3wFailCount    = 0;
    volatile double courseDeg = Double.NaN;
    volatile double depthM    = Double.NaN;
    private  long   lastDepthFetchTime = 0;
    private static final long MIN_DEPTH_INTERVAL_MS = 15 * 60 * 1000L;
    // updateInterval can now go as low as 10s, but a W3W lookup is a network call that doesn't
    // need to run nearly that often — gated independently so it never runs more than once/60s.
    // Also skipped outright if the position hasn't moved since the last lookup, however much
    // time has passed while stationary — same word address, no point re-querying the API and
    // risking a rate-limit block for zero benefit.
    private volatile long   lastW3wLookupTimeMs = 0;
    private static final long MIN_W3W_INTERVAL_MS = 60_000L;
    private volatile double lastW3wLat = Double.NaN;
    private volatile double lastW3wLon = Double.NaN;
    private static final double MIN_W3W_MOVE_KM = 0.005; // 5 m — roughly a what3words cell

    // Rolling buffer of recent GPS fixes for averaging: {lat, lon, alt, accuracy}
    private final java.util.List<double[]> fixBuffer = new java.util.ArrayList<>();

    // ── Current sensor values ─────────────────────────────────────────────────
    double csvLat        = 0;
    double csvLon        = 0;
    double csvAlt        = 0;
    float  csvAccuracy   = 0;
    int    csvSatellites = 0;
    // When this stops being refreshed, csvSatellites is a stale last-known value, not a live
    // reading — onSatelliteStatusChanged appears to stop firing during the same stalls that
    // block onLocationChanged, so a long-unchanged satellite count during a fix drought does NOT
    // mean satellites are still visible; it may just mean the callback itself has gone quiet.
    // 2026-08-20: confirmed from the log — csvSatellites sat frozen at one value for 164
    // consecutive ticks spanning multiple forced GPS restarts, which real tracking wouldn't do.
    volatile long lastSatStatusUpdateMs = 0;
    int    csvBattery    = 0;
    double lapDistanceKm = 0;
    double lapAscentM    = 0;

    // ── UI callback interface ──────────────────────────────────────────────────
    interface Listener {
        void onLocationUpdate(double lat, double lon, double alt, float accuracy);
        void onSatellitesUpdate(int count);
        void onBatteryUpdate(int pct);
        void onAlertStarted();
        void onAlertStopped();
        void onW3wUpdate(String words);
        void onLapDistanceUpdate(double km);
        void onLapAscentUpdate(double m);
        void onCourseUpdate(double degrees);
        void onDepthUpdate(double metres);
    }

    private Listener uiListener;
    void setListener(Listener l) { uiListener = l; }

    // ── Binder ────────────────────────────────────────────────────────────────
    class LocalBinder extends Binder {
        LocationService getService() { return LocationService.this; }
    }
    private final IBinder binder = new LocalBinder();

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ── Internals ─────────────────────────────────────────────────────────────
    private final Handler logHandler    = new Handler();
    private final Handler uploadHandler = new Handler();
    private final Handler gpsHandler    = new Handler();
    private boolean timersStarted = false;
    private LocationManager     locationManager;
    private GnssStatus.Callback gnssCallback;

    boolean hasLocation      = false; // true once a real GPS fix has been received
    long    lastFixTimeMs    = 0;    // time of last successful averaged-position calculation (UI display)
    long    lastRawFixTimeMs = 0;    // time of last raw GPS fix arrival (watchdog)

    // Confirmed live on Android 15/RugKing: once backgrounded, the OS clamps location request
    // intervals to ~10 minutes regardless of what's requested (location_background_throttle_
    // interval_ms). Cycling at the foreground rate while backgrounded just burns CPU on work
    // that gets thrown away, so the cycle interval (and the watchdog's staleness threshold)
    // switch to this once the screen is off, and back to `updateInterval` when it's on.
    private static final long BACKGROUND_INTERVAL_MS = 600_000L;
    private volatile boolean screenOn = true;
    private BroadcastReceiver screenReceiver;

    private long effectiveInterval() {
        return screenOn ? updateInterval : BACKGROUND_INTERVAL_MS;
    }

    // Held indefinitely for the life of the service. Without this, the OS can freeze the whole
    // process during idle periods (screen off, no interaction) even with the battery-optimization
    // exemption granted — Handler timers just stop firing for hours and everything (log ticks,
    // watchdog, uploads, alert checks) resumes in a burst once unfrozen. Must NOT be given a
    // timeout: an earlier version renewed it every 60s from the watchdog, but if a freeze hit in
    // the window before the first renewal, the renewal itself couldn't run, the lock quietly
    // expired, and the process stayed frozen for hours with nothing left to wake it.
    private PowerManager.WakeLock cpuWakeLock;

    // Diagnostics for the GPS watchdog's forced-teardown behaviour — added specifically so the
    // 120s→240s threshold change (2026-08-20) can be verified from the log rather than just
    // assumed: gpsWatchdogTeardownCount gives a running count of forced restarts since service
    // start, and each new teardown reports whether the *previous* one was followed by any raw
    // fix at all before this one fired. If teardowns keep reporting "no fix since previous
    // teardown", that's evidence the restarts themselves are the problem (tearing down GPS
    // before it can complete a fix); if fixes do land between teardowns, the threshold is doing
    // its job and some other cause explains any remaining gaps.
    private int  gpsWatchdogTeardownCount = 0;
    private long lastTeardownTimeMs      = 0;

    // "sat=N" for status/diagnostic log lines, flagged as stale when the underlying
    // GnssStatus callback hasn't actually fired recently — see lastSatStatusUpdateMs.
    private static final long SAT_STALE_MS = 15_000L;
    private String satForLog() {
        if (lastSatStatusUpdateMs == 0) return "sat=" + csvSatellites + " (never updated)";
        long ageS = (System.currentTimeMillis() - lastSatStatusUpdateMs) / 1000;
        return ageS * 1000 > SAT_STALE_MS
            ? String.format(Locale.US, "sat=%d (STALE %ds)", csvSatellites, ageS)
            : "sat=" + csvSatellites;
    }

    // ── Alert state ───────────────────────────────────────────────────────────
    volatile boolean     alertActive    = false;
    volatile boolean     alertCancelled = false;
    volatile MediaPlayer activePlayer   = null;
    volatile String      activeAlertUrl  = null;
    volatile String      activeAlertAuth = null;

    final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd",          Locale.getDefault());
    final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss",            Locale.getDefault());
    final SimpleDateFormat tsFmt   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    final SimpleDateFormat isoFmt;
    {
        isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    // GPS watchdog: fires every 60s; restarts GPS if no fix for 2× the current cycle interval
    // (min 4 min, raised from 2 min on 2026-08-20 — see gpsWatchdogTeardownCount comment above).
    // Threshold scales with effectiveInterval() so a backgrounded, OS-throttled gap (expected,
    // up to ~10 min) isn't mistaken for GPS having lost lock.
    private final Runnable gpsWatchdog = new Runnable() {
        @Override public void run() {
            long ageMs   = lastRawFixTimeMs > 0 ? System.currentTimeMillis() - lastRawFixTimeMs : Long.MAX_VALUE;
            long threshMs = Math.max(effectiveInterval() * 2, 240_000L);
            boolean provEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (ageMs > threshMs) {
                gpsWatchdogTeardownCount++;
                String prevOutcome = lastTeardownTimeMs == 0 ? "n/a (first teardown this run)"
                    : (lastRawFixTimeMs > lastTeardownTimeMs
                        ? "fix DID land after it, before this one fired"
                        : "NO fix landed after it before this one fired");
                writeLog(String.format(
                    "GPS WATCHDOG: no fix for %ds (threshold %ds) provider=%s %s — forcing GPS teardown #%d "
                    + "since service start (previous teardown: %s)",
                    ageMs == Long.MAX_VALUE ? -1 : ageMs / 1000, threshMs / 1000,
                    provEnabled ? "enabled" : "DISABLED", satForLog(),
                    gpsWatchdogTeardownCount, prevOutcome));
                lastTeardownTimeMs = System.currentTimeMillis();
                try { locationManager.removeUpdates(LocationService.this); } catch (Exception ignored) {}
                startLocationUpdates();
            }
            // Defensive: re-assert the wake lock in case it was ever released unexpectedly.
            // acquire() with no args on an already-held, non-reference-counted lock is a no-op.
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) cpuWakeLock.acquire();
            gpsHandler.postDelayed(this, 60_000L);
        }
    };

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            if (hasLocation) {
                long rawAgeMs = lastRawFixTimeMs > 0 ? System.currentTimeMillis() - lastRawFixTimeMs : Long.MAX_VALUE;
                long fixAgeS  = lastFixTimeMs > 0 ? (System.currentTimeMillis() - lastFixTimeMs) / 1000 : -1;
                boolean provEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                writeLog(String.format("Log tick: fixes-collected=%d %s GPS-fix-age=%s provider=%s",
                    fixBuffer.size(), satForLog(),
                    fixAgeS < 0 ? "--" : fixAgeS + "s",
                    provEnabled ? "ok" : "DISABLED"));
                if (rawAgeMs > effectiveInterval() * 2) {
                    writeLog(String.format("WARNING: last raw fix was %ds ago — GPS may have lost lock",
                        rawAgeMs == Long.MAX_VALUE ? -1 : rawAgeMs / 1000));
                }
                final boolean hadNewFixes = !fixBuffer.isEmpty();
                final double[] avg = computeAveragedPosition();
                if (hadNewFixes) {
                    lastFixTimeMs = System.currentTimeMillis();
                    updateNotification(String.format(Locale.US, "%.5f, %.5f", avg[0], avg[1]));
                } else {
                    writeLog("Log tick: no new fixes this cycle — using cached position, fix age unchanged");
                }
                fixBuffer.clear(); // reset for next cycle
                new Thread(new Runnable() {
                    @Override public void run() {
                        // Push averaged position + satellite count to UI at interval cadence
                        if (uiListener != null) {
                            uiListener.onLocationUpdate(avg[0], avg[1], avg[2], (float) avg[3]);
                            uiListener.onSatellitesUpdate(csvSatellites);
                        }
                        String w3w;
                        if (w3wBackoffTicks > 0) {
                            w3wBackoffTicks--;
                            writeLog("W3W: backing off (" + w3wBackoffTicks + " tick(s) remaining)");
                            w3w = "";
                        } else {
                            long sinceLastLookup = System.currentTimeMillis() - lastW3wLookupTimeMs;
                            double movedKm = Double.isNaN(lastW3wLat) ? Double.MAX_VALUE
                                : haversine(lastW3wLat, lastW3wLon, avg[0], avg[1]);
                            if (sinceLastLookup < MIN_W3W_INTERVAL_MS) {
                                writeLog(String.format(Locale.US,
                                    "W3W: skipped (%ds since last lookup, min %ds)",
                                    sinceLastLookup / 1000, MIN_W3W_INTERVAL_MS / 1000));
                                w3w = w3wAddress;
                            } else if (movedKm < MIN_W3W_MOVE_KM) {
                                writeLog(String.format(Locale.US,
                                    "W3W: skipped (position unchanged, moved %.1fm since last lookup)",
                                    movedKm * 1000));
                                w3w = w3wAddress;
                            } else {
                                lastW3wLookupTimeMs = System.currentTimeMillis();
                                lastW3wLat = avg[0];
                                lastW3wLon = avg[1];
                                w3w = lookupW3W(avg[0], avg[1]);
                            }
                        }
                        w3wAddress = w3w;
                        if (!w3w.isEmpty() && uiListener != null) uiListener.onW3wUpdate(w3w);
                        saveToGpx(avg);
                        saveToKml(avg);
                        lapDistanceKm = computeLapDistance();
                        if (uiListener != null) uiListener.onLapDistanceUpdate(lapDistanceKm);
                        lapAscentM = computeLapAscent();
                        if (uiListener != null) uiListener.onLapAscentUpdate(lapAscentM);
                        computeAndUpdateCourse();
                        if (uiListener != null) uiListener.onCourseUpdate(courseDeg);
                        if (Double.isNaN(courseDeg))
                            writeLog("Course: insufficient fixes (" + kmlLatLon.size() + " logged)");
                        else
                            writeLog(String.format(Locale.US, "Course: %.1f° (avg of %d bearings)",
                                courseDeg, Math.min(3, kmlLatLon.size() - 1)));
                        if ("Marine".equals(mapType)) {
                            long now = System.currentTimeMillis();
                            long secSinceFetch = (now - lastDepthFetchTime) / 1000;
                            if (now - lastDepthFetchTime >= MIN_DEPTH_INTERVAL_MS) {
                                lastDepthFetchTime = now;
                                writeLog(String.format(Locale.US,
                                    "Depth: fetching for %.6f,%.6f", avg[0], avg[1]));
                                depthM = fetchDepth(avg[0], avg[1]);
                                if (Double.isNaN(depthM))
                                    writeLog("Depth: unavailable (API error or on land)");
                                else if (depthM == 0)
                                    writeLog("Depth: 0 (surface / on land)");
                                else
                                    writeLog(String.format(Locale.US, "Depth: %.1f m", depthM));
                                if (uiListener != null) uiListener.onDepthUpdate(depthM);
                            } else {
                                writeLog(String.format(Locale.US,
                                    "Depth: skipped (%.0fs since last fetch, next in %.0fs)",
                                    (double) secSinceFetch,
                                    (MIN_DEPTH_INTERVAL_MS / 1000.0) - secSinceFetch));
                            }
                        }
                        saveToCsv(avg, w3w);
                    }
                }).start();
            } else {
                boolean provEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                writeLog(String.format("Log tick: no GPS fix yet — provider=%s %s",
                    provEnabled ? "enabled" : "DISABLED", satForLog()));
            }
            logHandler.postDelayed(this, effectiveInterval());
        }
    };

    private final Runnable uploadTick = new Runnable() {
        @Override public void run() {
            uploadFiles();
            uploadHandler.postDelayed(this, uploadInterval);
        }
    };

    private int lastLoggedBattery = -1;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            csvBattery = (scale > 0) ? (level * 100 / scale) : -1;
            if (csvBattery != lastLoggedBattery) {
                writeLog("Battery: " + csvBattery + "%");
                lastLoggedBattery = csvBattery;
            }
            if (uiListener != null) uiListener.onBatteryUpdate(csvBattery);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        restoreSettingsIfWiped();
        loadSettings();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean wasOn = screenOn;
                screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                if (screenOn == wasOn) return;
                writeLog("Screen " + (screenOn ? "on" : "off") + " — cycle interval now "
                    + (effectiveInterval() / 1000) + "s");
                if (screenOn) {
                    // Fresh fix promptly now that the user is likely looking at the app
                    logHandler.removeCallbacks(logTick);
                    logHandler.post(logTick);
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hereiamnow:tracking");
        cpuWakeLock.setReferenceCounted(false);
        cpuWakeLock.acquire();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onStarted() { writeLog("GNSS hardware started"); }
                @Override public void onStopped() { writeLog("GNSS hardware stopped"); }
                @Override public void onFirstFix(int ttffMillis) {
                    writeLog("GNSS first fix: TTFF=" + ttffMillis + "ms");
                }
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    csvSatellites = used;
                    lastSatStatusUpdateMs = System.currentTimeMillis();
                }
            };
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        reaffirmForeground();
        scheduleKeepAlive();
        if (!timersStarted) {
            timersStarted = true;
            String buildInfo = "";
            try {
                android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
                buildInfo = " v" + pi.versionName + " (" + pi.versionCode
                    + ") built " + getString(R.string.build_date);
            } catch (Exception ignored) {}
            writeLog("Service started: " + getString(R.string.app_name) + buildInfo
                + " | Android API " + Build.VERSION.SDK_INT
                + " | map=" + mapType);
            startLocationUpdates();
            logHandler.post(logTick);
            uploadHandler.postDelayed(uploadTick, uploadInterval);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        writeLog("Service stopped");
        super.onDestroy();
        logHandler.removeCallbacks(logTick);
        uploadHandler.removeCallbacks(uploadTick);
        gpsHandler.removeCallbacks(gpsWatchdog);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Clamped here too (not just in the settings UI) since a stale or remotely-edited
        // SharedPreferences value (see checkRemoteSettings) can bypass UI validation.
        updateInterval = Math.max(10, Math.min(3600, p.getInt(PREF_INTERVAL, 60))) * 1000L;
        uploadInterval = Math.max(60, Math.min(3600, p.getInt(PREF_UPLOAD_INTERVAL, 300))) * 1000L;
        nextcloudUrl   = p.getString(PREF_NC_URL,  "https://cloud.example.com");
        nextcloudUser  = p.getString(PREF_NC_USER, "");
        nextcloudPass  = p.getString(PREF_NC_PASS, "");
        session        = p.getString(PREF_SESSION,     "mobyphone");
        alertCode          = p.getString (PREF_ALERT_CODE,    "911911");
        alertPhotos        = Math.max(0, Math.min(9, p.getInt(PREF_ALERT_PHOTOS, 3)));
        startOnBoot        = p.getBoolean(PREF_START_ON_BOOT, true);
        minSat             = p.getInt    (PREF_MIN_SAT,        4);
        displayPeriodHours = p.getInt    (PREF_DISPLAY_PERIOD, 12);
        numGpsFixes        = Math.max(1, Math.min(9, p.getInt(PREF_NUM_GPS_FIXES, 5)));
        w3wApiKey          = p.getString (PREF_W3W_KEY,         "");
        trackColour        = p.getString (PREF_TRACK_COLOUR,    "None");
        retentionDays      = p.getInt    (PREF_RETENTION_DAYS,  31);
        mapType            = p.getString (PREF_MAP_TYPE,         "Land");
        alertVolume        = p.getString (PREF_ALERT_VOLUME,     "High");
        writeLog("Settings loaded: update=" + (updateInterval/1000) + "s upload=" + (uploadInterval/1000)
            + "s session=" + session + " alert=" + alertCode + " alertVolume=" + alertVolume
            + " boot=" + startOnBoot
            + " minSat=" + minSat + " displayPeriod=" + displayPeriodHours + "h"
            + " numGpsFixes=" + numGpsFixes + " map=" + mapType
            + " url=" + nextcloudUrl + " user=" + nextcloudUser);
    }

    void applySettings() {
        writeLog("Settings applied: update=" + (updateInterval/1000) + "s upload=" + (uploadInterval/1000)
            + "s session=" + session + " alert=" + alertCode + " map=" + mapType
            + " url=" + nextcloudUrl + " user=" + nextcloudUser);
        gpsHandler.removeCallbacks(gpsWatchdog);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        startLocationUpdates();
        logHandler.removeCallbacks(logTick);
        logHandler.postDelayed(logTick, effectiveInterval());
        uploadHandler.removeCallbacks(uploadTick);
        uploadHandler.postDelayed(uploadTick, uploadInterval);
        uploadFiles();
        lapDistanceKm = computeLapDistance();
        if (uiListener != null) uiListener.onLapDistanceUpdate(lapDistanceKm);
        lapAscentM = computeLapAscent();
        if (uiListener != null) uiListener.onLapAscentUpdate(lapAscentM);
    }

    // ── Location ──────────────────────────────────────────────────────────────

    void startLocationUpdates() {
        boolean fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        writeLog("startLocationUpdates: fine=" + fineGranted + " coarse=" + coarseGranted);
        if (!fineGranted) {
            writeLog("Location permission not granted — updates not started");
            return;
        }
        try {
            // Always request at 1s to keep GPS warm — duty cycling causes long TTFF on Android 15
            //
            // Both calls below explicitly bind callback delivery to the main Looper rather than
            // using the implicit-Looper overloads. startLocationUpdates() isn't only ever called
            // from the main thread — checkRemoteSettings() -> applySettings() runs on a plain
            // background Thread with no Looper, and the implicit-Looper overloads throw
            // "Can't create handler inside thread ... that has not called Looper.prepare()" when
            // called from there. That exception used to abort mid-restart: removeUpdates() had
            // already run, so GPS was left permanently deregistered (and the watchdog reschedule,
            // later in this same method, never ran either) until the app was restarted.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0, this, Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
                try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
                locationManager.registerGnssStatusCallback(gnssCallback, gpsHandler);
            }
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            writeLog("Requesting GPS updates every 1s (min " + numGpsFixes + " fixes/cycle, min "
                + minSat + " satellites required)"
                + " gps=" + (gpsEnabled ? "enabled" : "DISABLED"));
            // Start watchdog
            gpsHandler.removeCallbacks(gpsWatchdog);
            gpsHandler.postDelayed(gpsWatchdog, 60_000L);
            // Seed with last known GPS location if available. Network-provider fixes are
            // deliberately not used anywhere (acquisition, fallback, or averaging) — they're
            // coarse cell/Wi-Fi based and were previously found to be silently dragging down
            // the position average when mixed in with real GPS fixes.
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                long ageS = (System.currentTimeMillis() - last.getTime()) / 1000;
                writeLog("Last known location: age=" + ageS + "s acc=" + last.getAccuracy()
                    + "m provider=" + last.getProvider());
                onLocationChanged(last);
            } else {
                writeLog("No last known location available");
            }
        } catch (SecurityException e) {
            writeLog("SecurityException starting location updates: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        csvLat      = loc.getLatitude();
        csvLon      = loc.getLongitude();
        csvAlt      = loc.getAltitude();
        csvAccuracy = loc.getAccuracy();
        lastRawFixTimeMs = System.currentTimeMillis();
        // Only fixes meeting minSat count toward the averaged position — csvLat/csvLon/csvAlt
        // above are still updated unconditionally so hasLocation/diagnostics reflect the latest
        // raw fix, but a fix below minSat never enters the average. If a whole cycle produces no
        // qualifying fixes, computeAveragedPosition() already falls back to the last raw values
        // (i.e. the last good GPS position) when fixBuffer is empty.
        //
        // csvSatellites is fed by a separate callback (onSatelliteStatusChanged) that can stop
        // updating during the same conditions that make fixes scarce — confirmed live 2026-08-20:
        // a genuine background fix arrived with the satellite reading over 20 minutes stale. Using
        // that stale count to reject an otherwise-real fix would be worse than not gating at all,
        // so a stale reading is treated as "unknown" and never blocks a fix — only a *fresh*
        // reading below minSat does.
        boolean satFresh = lastSatStatusUpdateMs > 0
            && (System.currentTimeMillis() - lastSatStatusUpdateMs) <= SAT_STALE_MS;
        if (!satFresh || csvSatellites >= minSat) {
            fixBuffer.add(new double[]{csvLat, csvLon, csvAlt, csvAccuracy});
            if (!satFresh) {
                writeLog(String.format(Locale.US,
                    "GPS fix accepted despite stale satellite reading (sat=%d, minSat check skipped)",
                    csvSatellites));
            }
        } else {
            writeLog(String.format(Locale.US,
                "GPS fix rejected: sat=%d < minSat=%d — not added to average", csvSatellites, minSat));
        }
        if (!hasLocation) {
            hasLocation = true;
            writeLog(String.format(Locale.US,
                "First GPS fix: lat=%.6f lon=%.6f alt=%.1fm acc=%.1fm sat=%d bat=%d%%",
                csvLat, csvLon, csvAlt, csvAccuracy, csvSatellites, csvBattery));
        } else if (csvAccuracy > 100f) {
            writeLog(String.format(Locale.US,
                "GPS fix poor accuracy: acc=%.1fm sat=%d", csvAccuracy, csvSatellites));
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {
        writeLog("Provider enabled: " + p + " — restarting location updates");
        gpsHandler.removeCallbacks(gpsWatchdog);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        startLocationUpdates();
    }
    @Override public void onProviderDisabled(String p) { writeLog("Provider disabled: " + p); }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps Here I Am Now running in background");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("Here I Am Now")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        lastNotifText = text;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIF_ID, buildNotification(text));
    }

    // Android silently drops foreground-service status (isForeground -> false, service type bits
    // cleared) a few minutes into screen-off/idle, even for apps exempted from battery
    // optimizations — confirmed via `dumpsys activity services` on Android 15/RugKing, no
    // exception thrown, nothing logged. Re-issuing startForeground() restores isForeground=true
    // and the type bits immediately, so the watchdog calls this every 60s (well inside the
    // observed ~5 minute revocation window) to keep re-asserting it before it lapses.
    private volatile String lastNotifText = "Waiting for GPS…";

    private void reaffirmForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(lastNotifText),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION |
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIF_ID, buildNotification(lastNotifText));
            }
        } catch (Exception e) {
            writeLog("reaffirmForeground: startForeground failed: " + e.getMessage());
        }
    }

    // A plain in-process Handler tick calling startForeground() again does NOT work once
    // Android has already dropped foreground status (confirmed live: startForegroundCount never
    // increased, no exception thrown, isForeground stayed false) — self-triggered calls from a
    // background process don't satisfy Android 15's background-FGS-start limitation. An exact,
    // allow-while-idle alarm does: the OS itself wakes the app to deliver it, which is a calling
    // context startForegroundService() is allowed from. Re-armed every time onStartCommand runs
    // (initial start, and every keep-alive firing via KeepAliveReceiver), so the chain keeps
    // itself going — and survives the whole process being killed, since the pending alarm
    // outlives the process and will relaunch the service when it fires.
    private static final long KEEPALIVE_INTERVAL_MS = 3 * 60 * 1000L;

    private void scheduleKeepAlive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(this, KeepAliveReceiver.class);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, piFlags);
            long triggerAt = android.os.SystemClock.elapsedRealtime() + KEEPALIVE_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            writeLog("scheduleKeepAlive failed: " + e.getMessage());
        }
    }

    // ── Settings backup / remote sync ────────────────────────────────────────
    // settings-hia.json is a full snapshot of SharedPreferences, uploaded to Nextcloud
    // alongside the log files each cycle, with a settings-hia.bak kept from the previous write.
    // Two independent uses:
    //  1. Local self-heal: if SharedPreferences is ever found wiped (factory reset, OS bug,
    //     "clear app data"), restore from the local copy of json/bak at next startup.
    //  2. Remote config: every upload cycle, fetch Nextcloud's copy and apply any changes made
    //     by editing the file directly on Nextcloud — no restart needed, settings already apply
    //     live via applySettings(). Nextcloud URL/user/password are never applied this way: a
    //     bad edit there would cut off the phone's only channel back to Nextcloud, with no way
    //     to recover without physical access to the phone.

    private static final String SETTINGS_FILENAME     = "settings-hia.json";
    private static final String SETTINGS_BAK_FILENAME = "settings-hia.bak";
    // The remote copy the phone PUTs every cycle deliberately goes to a different, date-prefixed
    // filename (YYYY-MM-DD-settings-hia.json — see SETTINGS_UPLOAD_SUFFIX) rather than the fixed
    // name it GETs for remote-control purposes. PUTting and GETting the same fixed remote path
    // back to back, every upload cycle, forever, turned out to cause persistent HTTP 423 Locked
    // responses on this Nextcloud instance. A fresh filename per day means the phone's own upload
    // never collides with its own read-back and never repeatedly hits the same long-lived
    // resource, and SETTINGS_FILENAME on the server becomes purely something a human edits by
    // hand to push settings to the phone — the phone itself never writes to that path.
    private static final Set<String> REMOTE_SETTINGS_EXCLUDED = new HashSet<>(
        Arrays.asList(PREF_NC_URL, PREF_NC_USER, PREF_NC_PASS));

    // Numeric JSON values can round-trip as Integer or Long depending on the parser — compare
    // numerically rather than via equals() so a harmless type difference isn't seen as a change.
    private boolean settingsValuesEqual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number)
            return ((Number) a).longValue() == ((Number) b).longValue();
        return a.equals(b);
    }

    // Applies every key in json to the editor (except those in exclude) whose value differs
    // from what's in `compareAgainst`. Returns the list of keys actually changed.
    //
    // compareAgainst is deliberately a frozen JSONObject rather than "whatever SharedPreferences
    // says right now" — see checkRemoteSettings for why: comparing against live, possibly-since-
    // edited prefs is what caused a fresh local Save to get silently reverted moments later.
    private List<String> applyJsonToPrefs(JSONObject json, SharedPreferences.Editor editor,
                                           Set<String> exclude, JSONObject compareAgainst) {
        List<String> changed = new ArrayList<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (exclude != null && exclude.contains(key)) continue;
            Object value = json.opt(key);
            if (value == null || settingsValuesEqual(value, compareAgainst.opt(key))) continue;
            if (value instanceof Boolean)      editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Number)  editor.putInt(key, ((Number) value).intValue());
            else if (value instanceof String)  editor.putString(key, (String) value);
            else continue; // unsupported type — skip
            changed.add(key);
        }
        return changed;
    }

    // Snapshot all current settings to Documents/settings-hia.json, keeping the previous
    // version as settings-hia.bak. Atomic: written to a temp file then renamed into place, so a
    // write interrupted mid-way never leaves a half-written settings-hia.json behind.
    // Returns the JSONObject actually written (or null on failure) — callers that go on to
    // compare a later-fetched remote copy against "what we just told the server" should hold
    // onto this rather than re-reading live SharedPreferences, which may have moved on by then.
    JSONObject writeSettingsSnapshot() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONObject json = new JSONObject(p.getAll());
            File dir    = docsDir();
            File target = new File(dir, SETTINGS_FILENAME);
            File bak    = new File(dir, SETTINGS_BAK_FILENAME);
            File tmp    = new File(dir, SETTINGS_FILENAME + ".tmp");

            FileWriter fw = new FileWriter(tmp, false);
            fw.write(json.toString(2));
            fw.close();

            if (target.exists()) {
                if (bak.exists()) bak.delete();
                target.renameTo(bak);
            }
            tmp.renameTo(target);
            return json;
        } catch (Exception e) {
            writeLog("Settings snapshot failed: " + e.getMessage());
            return null;
        }
    }

    // Called once at service start, before loadSettings(). If SharedPreferences is completely
    // empty (fresh install, or wiped), try to self-heal from the local settings-hia.json, then
    // settings-hia.bak. If neither parses, do nothing — loadSettings() already falls back to
    // hardcoded defaults for every key on its own.
    private void restoreSettingsIfWiped() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getAll().isEmpty()) return;
        File dir = docsDir();
        for (String name : new String[]{SETTINGS_FILENAME, SETTINGS_BAK_FILENAME}) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            try {
                JSONObject json = new JSONObject(readFile(f));
                SharedPreferences.Editor editor = p.edit();
                List<String> restored = applyJsonToPrefs(json, editor, null, new JSONObject());
                editor.apply();
                writeLog("Settings restored from " + name + " (" + restored.size()
                    + " key(s), SharedPreferences was empty)");
                return;
            } catch (Exception e) {
                writeLog("Settings restore from " + name + " failed: " + e.getMessage());
            }
        }
    }

    // Internal bookkeeping only (not a user-facing setting, never included in the uploaded
    // snapshot) — the ETag of settings-hia.json the last time its content was actually applied.
    private static final String PREF_REMOTE_SETTINGS_ETAG = "_remote_settings_etag";

    // Fetch Nextcloud's copy of settings-hia.json and apply any changes (except the excluded
    // connection fields) — this is how settings get changed remotely, no restart involved.
    //
    // uploadedSnapshot is what writeSettingsSnapshot() captured and PUT earlier in this same
    // upload cycle. A GET here can easily land seconds after that PUT (after two MKCOLs and
    // several file uploads), which is plenty of time for the user to hit Save on a local edit in
    // between. Comparing the fetched copy against uploadedSnapshot — what we actually told the
    // server — rather than live SharedPreferences means only a genuine external edit (the remote
    // copy differing from what we ourselves last wrote) gets applied; a fresh local edit that
    // landed mid-cycle was never part of uploadedSnapshot, so it's left alone.
    //
    // That alone isn't enough, though: since the phone stopped writing to this file (see
    // SETTINGS_UPLOAD_SUFFIX), its content never converges back to match local reality — it's
    // frozen at whatever it last contained. Comparing by value alone meant *any* local edit that
    // happened to differ from that frozen snapshot got treated as "an external change to apply"
    // and reverted, forever, on every single upload cycle — not just once. The ETag check below
    // fixes that: the remote copy's content is only ever applied when its ETag has changed since
    // the last time we looked, i.e. a human actually edited it on Nextcloud. An unchanged file is
    // left alone no matter how far it's since drifted from local settings via the user's own edits.
    private void checkRemoteSettings(String sessionDir, String auth, JSONObject uploadedSnapshot) {
        if (uploadedSnapshot == null) {
            writeLog("Remote settings check skipped: no local snapshot to compare against");
            return;
        }
        try {
            String url = sessionDir + enc(SETTINGS_FILENAME);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Authorization", auth);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code != 200) {
                writeLog("Remote settings check: " + SETTINGS_FILENAME + " not found (HTTP " + code + ")");
                c.disconnect();
                return;
            }
            String remoteEtag = c.getHeaderField("ETag");
            if (remoteEtag == null) remoteEtag = c.getHeaderField("Last-Modified");
            InputStream is = c.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            c.disconnect();

            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (remoteEtag != null && remoteEtag.equals(p.getString(PREF_REMOTE_SETTINGS_ETAG, null))) {
                writeLog("Remote settings check: unchanged since last check (etag match) — not re-applying");
                return;
            }
            // Record this version as seen regardless of what the diff below finds, so an
            // unchanged-content-but-different-etag edit (or one that only touches excluded
            // connection fields) doesn't get re-inspected every cycle either.
            if (remoteEtag != null) p.edit().putString(PREF_REMOTE_SETTINGS_ETAG, remoteEtag).apply();
            JSONObject remote = new JSONObject(baos.toString("UTF-8"));

            List<String> ignored = new ArrayList<>();
            for (String key : REMOTE_SETTINGS_EXCLUDED) {
                if (remote.has(key) && !settingsValuesEqual(remote.opt(key), p.getAll().get(key)))
                    ignored.add(key);
            }
            if (!ignored.isEmpty())
                writeLog("Remote settings: ignoring change(s) to " + ignored
                    + " — connection fields are never applied remotely");

            SharedPreferences.Editor editor = p.edit();
            List<String> changed = applyJsonToPrefs(remote, editor, REMOTE_SETTINGS_EXCLUDED, uploadedSnapshot);
            if (changed.isEmpty()) {
                writeLog("Remote settings check: no changes");
                return;
            }
            editor.apply();
            writeLog("Remote settings: applied change(s) to " + changed);
            loadSettings();
            applySettings();
            writeSettingsSnapshot();
        } catch (Exception e) {
            writeLog("Remote settings check failed: " + e.getMessage());
        }
    }

    private String readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
        fis.close();
        return baos.toString("UTF-8");
    }

    // ── Nextcloud upload ──────────────────────────────────────────────────────

    void uploadFiles() {
        if (nextcloudUrl.isEmpty() || nextcloudUser.isEmpty()) return;
        final String url   = nextcloudUrl.replaceAll("/+$", "");
        final String user  = nextcloudUser;
        final String pass  = nextcloudPass;
        final String sess  = session.isEmpty() ? "mobyphone" : session;
        final String alert = alertCode;
        final File   dir   = docsDir();
        final String today = dateFmt.format(new Date());

        // Keep settings-hia.json current before it's uploaded below. The returned snapshot is
        // what checkRemoteSettings() compares the later GET against — see its comment.
        final JSONObject settingsSnapshot = writeSettingsSnapshot();
        writeLog("Upload starting: url=" + url + " user=" + user + " session=" + sess);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String auth = "Basic " + Base64.encodeToString(
                        (user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
                    String davRoot    = url + "/remote.php/dav/files/" + enc(user) + "/";
                    String hereibDir  = davRoot + "hereiam/";
                    String sessionDir = hereibDir + enc(sess) + "/";

                    int r1 = mkCol(hereibDir,  auth);
                    writeLog("MKCOL hereiam/: " + r1);
                    int r2 = mkCol(sessionDir, auth);
                    writeLog("MKCOL " + sess + "/: " + r2);
                    if (r1 >= 400 && r1 != 405) throw new IOException("hereiam/ MKCOL: " + r1);
                    if (r2 >= 400 && r2 != 405) throw new IOException(sess + "/ MKCOL: "  + r2);

                    // Only upload today's 4 log files
                    int uploaded = 0;
                    for (String suffix : LOG_SUFFIXES) {
                        File f = new File(dir, today + suffix);
                        if (f.exists()) {
                            int code = putFile(f, sessionDir + enc(f.getName()), auth);
                            writeLog("PUT " + f.getName() + " (" + f.length() + " bytes) \u2192 HTTP " + code);
                            if (code < 400) uploaded++;
                        } else {
                            writeLog("Skip (not found): " + today + suffix);
                        }
                    }
                    File settingsFile = new File(dir, SETTINGS_FILENAME);
                    boolean settingsPutOk = false;
                    if (settingsFile.exists()) {
                        // Uploaded under a fresh date-prefixed name each day, not SETTINGS_FILENAME
                        // \u2014 see SETTINGS_UPLOAD_SUFFIX's declaration for why.
                        String uploadName = today + SETTINGS_UPLOAD_SUFFIX;
                        int code = putFile(settingsFile, sessionDir + enc(uploadName), auth);
                        writeLog("PUT " + uploadName + " (" + settingsFile.length()
                            + " bytes) \u2192 HTTP " + code);
                        settingsPutOk = code < 400;
                    }
                    writeLog("Upload done: " + uploaded + " file(s) \u2192 " + sess);
                    if (!alert.isEmpty())
                        checkForAlert(alert, sessionDir, auth, dir);
                    // checkRemoteSettings() compares the server's copy against settingsSnapshot on
                    // the assumption the server now actually holds settingsSnapshot (just PUT
                    // above). If that PUT failed \u2014 e.g. HTTP 423 Locked \u2014 the server still holds
                    // whatever was there before, so the comparison would see a spurious "external"
                    // change and revert live prefs back to that stale value. Skip it in that case.
                    if (settingsPutOk) {
                        checkRemoteSettings(sessionDir, auth, settingsSnapshot);
                    } else {
                        writeLog("Remote settings check skipped: settings-hia.json upload did not succeed");
                    }
                    deleteOldNextcloudFiles(sessionDir, auth);
                } catch (Exception e) {
                    writeLog("Upload failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private int mkCol(String urlStr, String auth) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", auth);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        try {
            java.lang.reflect.Field mf =
                java.net.HttpURLConnection.class.getDeclaredField("method");
            mf.setAccessible(true);
            mf.set(c, "MKCOL");
            Class<?> cls = c.getClass();
            while (cls != null && cls != java.net.HttpURLConnection.class) {
                try {
                    java.lang.reflect.Field df = cls.getDeclaredField("delegate");
                    df.setAccessible(true);
                    Object delegate = df.get(c);
                    if (delegate instanceof java.net.HttpURLConnection)
                        mf.set(delegate, "MKCOL");
                    break;
                } catch (NoSuchFieldException ignored) {}
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            throw new IOException("Cannot set MKCOL method: " + e.getMessage());
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private int putFile(File file, String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("PUT");
        c.setRequestProperty("Authorization", auth);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        FileInputStream fis = new FileInputStream(file);
        OutputStream    os  = c.getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
        fis.close(); os.close();
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private void checkForAlert(String code, String sessionDir, String auth, File dir) {
        try {
            String fileName = code + ".mp3";
            String alertUrl = sessionDir + enc(fileName);

            // Check if alert file exists on Nextcloud
            HttpURLConnection hc = (HttpURLConnection) new URL(alertUrl).openConnection();
            hc.setRequestMethod("HEAD");
            hc.setRequestProperty("Authorization", auth);
            hc.setConnectTimeout(15000);
            hc.setReadTimeout(15000);
            int headCode = hc.getResponseCode();
            hc.disconnect();
            if (headCode != 200 && headCode != 204) {
                writeLog("Alert check: " + fileName + " not found (HTTP " + headCode + ")");
                return;
            }
            writeLog("Alert: " + fileName + " found, downloading");

            // Download to Documents
            File mp3 = new File(dir, fileName);
            HttpURLConnection gc = (HttpURLConnection) new URL(alertUrl).openConnection();
            gc.setRequestMethod("GET");
            gc.setRequestProperty("Authorization", auth);
            gc.setConnectTimeout(30000);
            gc.setReadTimeout(60000);
            InputStream is = gc.getInputStream();
            FileOutputStream fos = new FileOutputStream(mp3);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            is.close(); fos.close();
            gc.disconnect();
            writeLog("Alert: downloaded " + mp3.length() + " bytes");

            // Take front + rear photos and upload (runs in its own thread so alarm starts immediately)
            final String photoDest = sessionDir;
            final String photoAuth = auth;
            new Thread(new Runnable() {
                @Override public void run() { takeAlertPhotos(photoDest, photoAuth); }
            }).start();

            // Store URL/auth so cancelAlert() can rename file on Nextcloud
            activeAlertUrl  = alertUrl;
            activeAlertAuth = auth;
            alertCancelled  = false;
            alertActive     = true;
            if (uiListener != null) uiListener.onAlertStarted();

            // Play 4 times at max volume, LED flashing; stop early if cancelled
            writeLog("Alert: playing 4 times");
            playAlertAudio(mp3);
            torchOff();
            writeLog("Alert: playback complete" + (alertCancelled ? " (cancelled)" : ""));
            if (isIncognitoAlert()) {
                // No Cancel Alert button exists to rename the file — without this the same
                // alert file keeps being found and re-triggered forever.
                finishAlert("incognito playback complete — auto-cancelling (no Cancel button to do it)");
            }
            // Non-incognito: keep alertActive=true and button visible until user presses Cancel
        } catch (Exception e) {
            writeLog("Alert error: " + e.getMessage());
            torchOff();
            if (isIncognitoAlert() && alertActive) {
                finishAlert("auto-cancelling after error (incognito, no Cancel button to do it)");
            }
        }
    }

    // "Zero" runs the alert fully incognito: silent, no vibration, no torch flash, and (handled
    // in MainActivity) no Cancel Alert button — nothing observable gives away that it's active.
    boolean isIncognitoAlert() { return "Zero".equals(alertVolume); }

    private float alertVolumeGain() {
        switch (alertVolume) {
            case "Zero":   return 0f;
            case "Low":    return 0.25f;
            case "Medium": return 0.6f;
            case "High":
            default:       return 1f;
        }
    }

    // Play loop extracted so volume is always restored
    private void playAlertAudio(File mp3) throws Exception {
        AudioManager am  = (AudioManager) getSystemService(AUDIO_SERVICE);
        Vibrator      vib = (Vibrator)     getSystemService(VIBRATOR_SERVICE);
        boolean incognito = isIncognitoAlert();
        float   gain      = alertVolumeGain();
        int origVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
        int maxVol  = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        // Set the stream volume to match too — belt and braces, since some devices enforce a
        // non-zero floor on the alarm stream. The per-player setVolume() below is what actually
        // guarantees true silence for "Zero" regardless of any such floor.
        am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(maxVol * gain), 0);
        writeLog("Alert: volume=" + alertVolume + " (gain=" + gain + ")"
            + (incognito ? " — running incognito: no sound, no vibration, no torch" : ""));
        // Vibration pattern: 400ms on, 200ms off, repeating (in sync with torch)
        long[] vibePattern = {0, 400, 200};
        try {
            for (int i = 0; i < 4 && !alertCancelled; i++) {
                try {
                    activePlayer = new MediaPlayer();
                    activePlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                    activePlayer.setDataSource(mp3.getAbsolutePath());
                    activePlayer.prepare();
                    activePlayer.setVolume(gain, gain);
                    if (!incognito) {
                        torchOn();
                        vibrateStart(vib, vibePattern);
                    }
                    activePlayer.start();
                    writeLog("Alert: playing (" + (i + 1) + "/4)");
                    Thread.sleep(500);
                    while (activePlayer != null && activePlayer.isPlaying() && !alertCancelled)
                        Thread.sleep(200);
                    torchOff();
                    vib.cancel();
                    if (activePlayer != null) { activePlayer.release(); activePlayer = null; }
                } catch (Exception e) {
                    torchOff();
                    vib.cancel();
                    writeLog("Alert play error: " + e.getMessage());
                    break;
                }
                if (i < 3 && !alertCancelled) Thread.sleep(5000);
            }
        } finally {
            vib.cancel();
            am.setStreamVolume(AudioManager.STREAM_ALARM, origVol, 0);
            writeLog("Alert: alarm volume restored to " + origVol);
        }
    }

    private void vibrateStart(Vibrator vib, long[] pattern) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build());
            } else {
                vib.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
    }

    void cancelAlert() {
        alertCancelled = true;
        finishAlert("cancelled by user");
    }

    // Shared by user-initiated cancel and incognito auto-cancel: stop playback/vibration/torch,
    // rename the file on Nextcloud so it isn't found and re-triggered again, and notify the UI.
    private void finishAlert(String reason) {
        writeLog("Alert: " + reason);
        alertActive = false;
        MediaPlayer mp = activePlayer;
        if (mp != null) {
            try { mp.stop(); mp.release(); } catch (Exception ignored) {}
            activePlayer = null;
        }
        torchOff();
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).cancel(); } catch (Exception ignored) {}
        final String url  = activeAlertUrl;
        final String auth = activeAlertAuth;
        activeAlertUrl  = null;
        activeAlertAuth = null;
        if (url != null && auth != null)
            renameOnNextcloud(url, auth, alertCode + ".mp3");
        if (uiListener != null) uiListener.onAlertStopped();
    }

    private void renameOnNextcloud(final String sourceUrl, final String auth, final String fileName) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String today   = dateFmt.format(new Date());
                    String newName = today + "-" + fileName;
                    String destUrl = sourceUrl.substring(0, sourceUrl.lastIndexOf('/') + 1)
                                     + enc(newName);

                    // Read local copy (already downloaded during alert)
                    File localFile = new File(docsDir(), fileName);
                    byte[] data = new byte[(int) localFile.length()];
                    java.io.FileInputStream fis = new java.io.FileInputStream(localFile);
                    fis.read(data);
                    fis.close();

                    // PUT with new name
                    HttpURLConnection put = (HttpURLConnection) new URL(destUrl).openConnection();
                    put.setRequestMethod("PUT");
                    put.setRequestProperty("Authorization", auth);
                    put.setDoOutput(true);
                    put.setFixedLengthStreamingMode(data.length);
                    put.setConnectTimeout(15000);
                    put.setReadTimeout(30000);
                    put.getOutputStream().write(data);
                    put.getOutputStream().close();
                    int putCode = put.getResponseCode();
                    put.disconnect();
                    if (putCode >= 300) {
                        writeLog("Alert: PUT " + newName + " failed (HTTP " + putCode + ")");
                        return;
                    }

                    // DELETE original
                    HttpURLConnection del = (HttpURLConnection) new URL(sourceUrl).openConnection();
                    del.setRequestMethod("DELETE");
                    del.setRequestProperty("Authorization", auth);
                    del.setConnectTimeout(15000);
                    del.setReadTimeout(15000);
                    int delCode = del.getResponseCode();
                    del.disconnect();
                    if (delCode < 300)
                        writeLog("Alert: " + fileName + " renamed to " + newName + " on Nextcloud");
                    else
                        writeLog("Alert: DELETE " + fileName + " failed (HTTP " + delCode + ")");
                } catch (Exception e) {
                    writeLog("Alert: rename " + fileName + " failed: " + e.getMessage());
                }
            }
        }).start();
    }

    // ── Alert camera photos ───────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void takeAlertPhotos(String sessionDir, String auth) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            writeLog("Alert photos: CAMERA permission not granted, skipping");
            return;
        }
        if (alertPhotos == 0) {
            writeLog("Alert photos: disabled (alert_photos=0)");
            return;
        }
        // Wake screen before opening camera — some devices disable camera by policy when screen is off
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
            "hereiamnow:alertcam");
        wl.acquire(300000); // 5 min budget for all cameras
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] ids = cm.getCameraIdList();
            writeLog("Alert photos: " + ids.length + " camera(s) found on this device");
            // Log all cameras — diagnostic info for unknown devices
            for (String id : ids) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing  = ch.get(CameraCharacteristics.LENS_FACING);
                Integer hwLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                String facingStr = facing == null                                       ? "unknown"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT                ? "front"
                    : facing == CameraCharacteristics.LENS_FACING_BACK                 ? "back"
                    : facing == CameraCharacteristics.LENS_FACING_EXTERNAL             ? "external"
                    : "facing-" + facing;
                String hwStr = hwLevel == null                                                            ? "unknown"
                    : hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY              ? "legacy"
                    : hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED             ? "limited"
                    : hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL                ? "full"
                    : hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3                   ? "level3"
                    : "hw-" + hwLevel;
                writeLog("Alert photos: cam" + id + " facing=" + facingStr + " hw=" + hwStr);
            }
            // Try every camera — works on unusual devices (car headunits, etc.) that lack normal front/back sensors
            for (String id : ids) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == null                                       ? "unknown"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT                ? "front"
                    : facing == CameraCharacteristics.LENS_FACING_BACK                 ? "back"
                    : facing == CameraCharacteristics.LENS_FACING_EXTERNAL             ? "external"
                    : "f" + facing;
                String camLabel = "cam" + id + "-" + facingStr;
                try {
                    takeAndUploadPhotos(cm, id, camLabel, alertPhotos, 10000, sessionDir, auth);
                } catch (Exception e) {
                    writeLog("Alert photos: " + camLabel + " error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            writeLog("Alert photos: camera list error: " + e.getMessage());
        }
        if (wl.isHeld()) wl.release();
    }

    // Takes photoCount photos from one camera, with delayMs between shots.
    // First attempts JPEG still capture. If JPEG times out on the first shot (e.g. car
    // headunit cameras that support preview but not JPEG pipeline), switches to YUV preview
    // fallback for all remaining shots, avoiding 15s × N wasted timeouts.
    @SuppressWarnings("deprecation")
    private void takeAndUploadPhotos(CameraManager cm, String cameraId, final String facingName,
                                      int photoCount, int delayMs,
                                      final String sessionDir, final String auth) throws Exception {
        CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
        final int jpegOrientation = computeJpegOrientation(chars);
        writeLog("Alert photos: " + facingName + " JPEG orientation = " + jpegOrientation + "°");

        // Pick best JPEG size up to 2MP
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        Size picSize = sizes[0];
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            long best = (long) picSize.getWidth() * picSize.getHeight();
            if (area <= 2_000_000L && area > best) picSize = s;
            else if (best > 2_000_000L && area < best) picSize = s;
        }

        HandlerThread ht = new HandlerThread("AlertCam_" + facingName);
        ht.start();
        android.os.Handler handler = new android.os.Handler(ht.getLooper());

        // Preview reader — YUV frames used for AE convergence and as fallback if JPEG fails
        final ImageReader previewReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
        previewReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = r.acquireLatestImage();
                if (img != null) img.close();
            }
        }, handler);

        // Full-resolution still reader
        final ImageReader stillReader = ImageReader.newInstance(
            picSize.getWidth(), picSize.getHeight(), ImageFormat.JPEG, 1);

        final CameraDevice[]         camRef  = {null};
        final CameraCaptureSession[] sessRef = {null};

        // Open camera
        final CountDownLatch openLatch = new CountDownLatch(1);
        cm.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) {
                camRef[0] = camera; openLatch.countDown();
            }
            @Override public void onDisconnected(CameraDevice camera) {
                try { camera.close(); } catch (Exception ignored) {}
                openLatch.countDown();
            }
            @Override public void onError(CameraDevice camera, int error) {
                writeLog("Alert photo: camera error " + error);
                try { camera.close(); } catch (Exception ignored) {}
                openLatch.countDown();
            }
        }, handler);

        if (!openLatch.await(5, TimeUnit.SECONDS) || camRef[0] == null) {
            previewReader.close(); stillReader.close(); ht.quitSafely();
            writeLog("Alert photos: " + facingName + " camera open failed");
            return;
        }

        try {
            // Single session with both surfaces — stays open for all photos
            final CountDownLatch sessLatch = new CountDownLatch(1);
            camRef[0].createCaptureSession(
                Arrays.asList(previewReader.getSurface(), stillReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        sessRef[0] = s; sessLatch.countDown();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        writeLog("Alert photo: session config failed"); sessLatch.countDown();
                    }
                }, handler);

            if (!sessLatch.await(5, TimeUnit.SECONDS) || sessRef[0] == null) {
                writeLog("Alert photos: " + facingName + " session failed");
                return;
            }

            final CaptureRequest.Builder previewReq =
                camRef[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewReq.addTarget(previewReader.getSurface());
            previewReq.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            final CaptureRequest.Builder stillReq =
                camRef[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillReq.addTarget(stillReader.getSurface());
            stillReq.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            stillReq.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);

            boolean useYuvFallback = false;

            for (int photoNum = 1; photoNum <= photoCount; photoNum++) {
                // Run repeating preview; wait up to 3s for auto-exposure to converge
                final CountDownLatch aeLatch = new CountDownLatch(1);
                sessRef[0].setRepeatingRequest(previewReq.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                CaptureRequest request, TotalCaptureResult result) {
                            Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (state == null
                                    || state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                    || state == CaptureResult.CONTROL_AE_STATE_LOCKED
                                    || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                aeLatch.countDown();
                            }
                        }
                    }, handler);
                aeLatch.await(3, TimeUnit.SECONDS);
                sessRef[0].stopRepeating();

                String timestamp = new java.text.SimpleDateFormat("HHmmss", Locale.US).format(new Date());
                String today     = dateFmt.format(new Date());
                final String fileName = today + "-" + timestamp + "-hia-alert-" + facingName + "-" + photoNum + ".jpg";
                final File outFile    = new File(docsDir(), fileName);
                boolean photoSaved    = false;

                // ── JPEG still capture ────────────────────────────────────────────────
                if (!useYuvFallback) {
                    final boolean[] jpegSaved = {false};
                    final CountDownLatch captureLatch = new CountDownLatch(1);

                    stillReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override public void onImageAvailable(ImageReader r) {
                            Image img = r.acquireLatestImage();
                            if (img != null) {
                                ByteBuffer buf   = img.getPlanes()[0].getBuffer();
                                byte[]     bytes = new byte[buf.remaining()];
                                buf.get(bytes);
                                img.close();
                                if (bytes.length > 0) {
                                    try {
                                        FileOutputStream fos = new FileOutputStream(outFile);
                                        fos.write(bytes); fos.close();
                                        writeLog("Alert photo: saved " + fileName + " (" + bytes.length + " bytes)");
                                        int code = putFile(outFile, sessionDir + enc(fileName), auth);
                                        writeLog("Alert photo: uploaded " + fileName + " \u2192 HTTP " + code);
                                        jpegSaved[0] = true;
                                    } catch (Exception e) {
                                        writeLog("Alert photo: save/upload error: " + e.getMessage());
                                    }
                                }
                            }
                            captureLatch.countDown();
                        }
                    }, handler);

                    sessRef[0].capture(stillReq.build(), null, handler);
                    captureLatch.await(15, TimeUnit.SECONDS);
                    photoSaved = jpegSaved[0];

                    if (!photoSaved) {
                        // JPEG didn't arrive — switch all remaining photos to YUV fallback
                        writeLog("Alert photos: " + facingName + " JPEG timeout, switching to YUV preview fallback");
                        useYuvFallback = true;
                    }
                }

                // ── YUV preview fallback ──────────────────────────────────────────────
                // Used when JPEG capture pipeline is broken (common on car headunit HALs).
                // The camera still delivers YUV frames to the preview surface — grab one
                // and compress to JPEG in software.
                if (useYuvFallback && !photoSaved) {
                    final CountDownLatch yuvLatch = new CountDownLatch(1);
                    final Image[] yuvCapture = {null};
                    previewReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override public void onImageAvailable(ImageReader r) {
                            Image img = r.acquireLatestImage();
                            if (img != null) {
                                if (yuvCapture[0] == null) {
                                    yuvCapture[0] = img; // keep open for encoding
                                } else {
                                    img.close();
                                }
                                yuvLatch.countDown();
                            }
                        }
                    }, handler);
                    sessRef[0].setRepeatingRequest(previewReq.build(), null, handler);
                    yuvLatch.await(3, TimeUnit.SECONDS);
                    sessRef[0].stopRepeating();
                    // Restore discard listener
                    previewReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override public void onImageAvailable(ImageReader r) {
                            Image img = r.acquireLatestImage();
                            if (img != null) img.close();
                        }
                    }, handler);

                    if (yuvCapture[0] != null) {
                        try {
                            int imgW = yuvCapture[0].getWidth();
                            int imgH = yuvCapture[0].getHeight();
                            byte[] jpegBytes = yuvToJpeg(yuvCapture[0]);
                            if (jpegBytes.length > 0) {
                                FileOutputStream fos = new FileOutputStream(outFile);
                                fos.write(jpegBytes); fos.close();
                                // YuvImage.compressToJpeg() writes no EXIF — set orientation
                                // separately so the fallback path is corrected the same as JPEG capture
                                setExifOrientation(outFile, jpegOrientation);
                                writeLog("Alert photo: saved (YUV) " + fileName
                                    + " (" + jpegBytes.length + " bytes, " + imgW + "x" + imgH + ")");
                                int code = putFile(outFile, sessionDir + enc(fileName), auth);
                                writeLog("Alert photo: uploaded " + fileName + " \u2192 HTTP " + code);
                                photoSaved = true;
                            }
                        } catch (Exception e) {
                            writeLog("Alert photo: YUV encode error: " + e.getMessage());
                        } finally {
                            yuvCapture[0].close();
                        }
                    } else {
                        writeLog("Alert photos: " + facingName + " YUV frame not available");
                    }
                }

                writeLog("Alert photos: " + facingName + " photo " + photoNum + "/" + photoCount + " done");

                // Between shots: keep preview running so AE adapts to lighting conditions
                if (photoNum < photoCount) {
                    sessRef[0].setRepeatingRequest(previewReq.build(), null, handler);
                    Thread.sleep(delayMs);
                    sessRef[0].stopRepeating();
                }
            }
        } finally {
            if (sessRef[0] != null) try { sessRef[0].close(); } catch (Exception ignored) {}
            if (camRef[0] != null) try { camRef[0].close(); } catch (Exception ignored) {}
            previewReader.close();
            stillReader.close();
            ht.quitSafely();
        }
    }

    // Standard Camera2 formula (matches Google's Camera2Basic sample): combines the sensor's
    // fixed mounting angle with the device's current physical rotation so the saved JPEG is
    // right-side-up regardless of whether the phone was held in portrait or landscape when the
    // alert fired. Front camera needs the extra mirror correction since its sensor coordinate
    // system is flipped relative to the back camera.
    private int computeJpegOrientation(CameraCharacteristics chars) {
        int deviceDegrees = 0;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            switch (wm.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_0:   deviceDegrees = 0;   break;
                case Surface.ROTATION_90:  deviceDegrees = 90;  break;
                case Surface.ROTATION_180: deviceDegrees = 180; break;
                case Surface.ROTATION_270: deviceDegrees = 270; break;
            }
        } catch (Exception ignored) {}
        Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        int sensor = sensorOrientation != null ? sensorOrientation : 90;
        int result;
        if (isFront) {
            result = (sensor + deviceDegrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (sensor - deviceDegrees + 360) % 360;
        }
        // Confirmed by on-device testing: landscape (both cameras) and portrait back camera all
        // come out correct with the formula above, but portrait front camera is 180° off. The
        // mirror-correction math above is a known-inconsistent spot across OEMs/devices — rather
        // than guess at a different general formula and risk breaking the three confirmed-good
        // cases, flip only the specific case reported wrong.
        if (isFront && (deviceDegrees == 0 || deviceDegrees == 180)) {
            result = (result + 180) % 360;
        }
        return result;
    }

    private void setExifOrientation(File file, int degrees) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int value;
            switch (degrees) {
                case 90:  value = ExifInterface.ORIENTATION_ROTATE_90;  break;
                case 180: value = ExifInterface.ORIENTATION_ROTATE_180; break;
                case 270: value = ExifInterface.ORIENTATION_ROTATE_270; break;
                default:  value = ExifInterface.ORIENTATION_NORMAL;     break;
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(value));
            exif.saveAttributes();
        } catch (Exception e) {
            writeLog("Alert photo: EXIF orientation set failed: " + e.getMessage());
        }
    }

    // Convert a Camera2 YUV_420_888 Image to a JPEG byte array.
    // Camera2 uses a planar format; YuvImage requires NV21 (Y + interleaved V,U).
    private byte[] yuvToJpeg(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf  = planes[0].getBuffer();
        ByteBuffer uBuf  = planes[1].getBuffer();
        ByteBuffer vBuf  = planes[2].getBuffer();
        int yStride  = planes[0].getRowStride();
        int uvStride = planes[1].getRowStride();
        int uvPixel  = planes[1].getPixelStride();
        byte[] nv21 = new byte[w * h * 3 / 2];
        // Copy Y plane row by row (row stride may exceed width)
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yStride);
            yBuf.get(nv21, row * w, Math.min(w, yBuf.remaining()));
        }
        // Interleave V then U into NV21 chroma plane
        int uvOffset = w * h;
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                int i = row * uvStride + col * uvPixel;
                nv21[uvOffset++] = vBuf.get(i);
                nv21[uvOffset++] = uBuf.get(i);
            }
        }
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 85, bos);
        return bos.toByteArray();
    }

    private void torchOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                if (ids.length > 0) cm.setTorchMode(ids[0], true);
            } catch (Exception ignored) {}
        }
    }

    private void torchOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                if (ids.length > 0) cm.setTorchMode(ids[0], false);
            } catch (Exception ignored) {}
        }
    }

    private String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    // ── CSV / GPX / KML saving ────────────────────────────────────────────────

    /** Average the fix buffer, removing outliers beyond 2 std devs from centroid.
     *  When significant movement is detected, uses only the most recent numGpsFixes
     *  fixes to avoid averaging over the journey path (which gives a position from
     *  30s ago at speed). */
    private double[] computeAveragedPosition() {
        int n = fixBuffer.size();
        if (n == 0) return new double[]{csvLat, csvLon, csvAlt, csvAccuracy};
        if (n < numGpsFixes) {
            writeLog(String.format("GPS avg: only %d fix(es) this cycle (min=%d) — using best available",
                n, numGpsFixes));
        }
        if (n == 1) return new double[]{fixBuffer.get(0)[0], fixBuffer.get(0)[1],
                                        fixBuffer.get(0)[2], fixBuffer.get(0)[3]};

        // Detect movement: compare first and last fix in cycle
        double[] firstFix = fixBuffer.get(0);
        double[] lastFix  = fixBuffer.get(n - 1);
        double dlatM = (lastFix[0] - firstFix[0]) * 111000;
        double dlonM = (lastFix[1] - firstFix[1]) * 111000;
        double moveMetres = Math.sqrt(dlatM * dlatM + dlonM * dlonM);
        boolean moving = moveMetres > 25.0;

        // When moving, use only the most recent numGpsFixes fixes so the result
        // reflects current position rather than the midpoint of the journey
        java.util.List<double[]> pool;
        if (moving) {
            int useFrom = Math.max(0, n - Math.max(numGpsFixes, 1));
            pool = fixBuffer.subList(useFrom, n);
        } else {
            pool = fixBuffer;
        }
        int pn = pool.size();

        // Centroid of pool
        double meanLat = 0, meanLon = 0;
        for (double[] f : pool) { meanLat += f[0]; meanLon += f[1]; }
        meanLat /= pn; meanLon /= pn;

        // Distance of each fix from centroid
        double[] dists = new double[pn];
        double meanDist = 0;
        for (int i = 0; i < pn; i++) {
            double dlat = pool.get(i)[0] - meanLat;
            double dlon = pool.get(i)[1] - meanLon;
            dists[i] = Math.sqrt(dlat * dlat + dlon * dlon);
            meanDist += dists[i];
        }
        meanDist /= pn;

        // Standard deviation of distances
        double var = 0;
        for (double d : dists) var += (d - meanDist) * (d - meanDist);
        double stdDev = Math.sqrt(var / pn);
        double threshold = meanDist + 2 * stdDev;

        // Keep fixes within threshold
        java.util.List<double[]> kept = new java.util.ArrayList<>();
        for (int i = 0; i < pn; i++)
            if (dists[i] <= threshold) kept.add(pool.get(i));
        if (kept.isEmpty()) kept = new java.util.ArrayList<>(pool);

        // Altitude outlier rejection within kept fixes (same 2-std-dev approach)
        double meanAlt = 0;
        for (double[] f : kept) meanAlt += f[2];
        meanAlt /= kept.size();
        double altVar = 0;
        for (double[] f : kept) altVar += (f[2] - meanAlt) * (f[2] - meanAlt);
        double altStd  = Math.sqrt(altVar / kept.size());
        double altThreshold = 2 * altStd;
        java.util.List<double[]> keptAlt = new java.util.ArrayList<>();
        for (double[] f : kept)
            if (Math.abs(f[2] - meanAlt) <= altThreshold) keptAlt.add(f);
        if (keptAlt.isEmpty()) keptAlt = kept;

        // Average kept fixes
        double lat = 0, lon = 0, alt = 0, acc = 0;
        for (double[] f : keptAlt) { lat += f[0]; lon += f[1]; alt += f[2]; acc += f[3]; }
        int k = keptAlt.size();

        // Accuracy and altitude range across pool fixes (for diagnostics)
        double accMin = Double.MAX_VALUE, accMax = 0, altMin = Double.MAX_VALUE, altMax = -Double.MAX_VALUE;
        for (double[] f : pool) {
            if (f[3] < accMin) accMin = f[3];
            if (f[3] > accMax) accMax = f[3];
            if (f[2] < altMin) altMin = f[2];
            if (f[2] > altMax) altMax = f[2];
        }

        int posRejected = pn - kept.size();
        int altRejected = kept.size() - k;
        double threshMetres = threshold * 111000;

        StringBuilder sb = new StringBuilder();
        if (moving)
            sb.append(String.format(Locale.US,
                "GPS avg: MOVING %.0fm in cycle — using last %d of %d fixes | ", moveMetres, pn, n));
        else
            sb.append(String.format(Locale.US, "GPS avg: static (moved <25m) | %d fixes | ", n));
        sb.append(String.format(Locale.US, "pos-filter: %d kept, %d rejected", pn, posRejected));
        if (posRejected > 0)
            sb.append(String.format(Locale.US, " (outlier threshold ~%.0fm from centroid)", threshMetres));
        sb.append(String.format(Locale.US, " | alt-filter: %d kept, %d rejected", k, altRejected));
        if (altRejected > 0)
            sb.append(String.format(Locale.US, " (alt range %.1f-%.1fm)", altMin, altMax));
        sb.append(String.format(Locale.US,
            " | result: lat=%.6f lon=%.6f alt=%.1fm acc=%.1fm (acc range %.0f-%.0fm)",
            lat/k, lon/k, alt/k, acc/k, accMin, accMax));
        writeLog(sb.toString());
        return new double[]{lat / k, lon / k, alt / k, acc / k};
    }

    private static final String CSV_HEADER =
        "timestamp,date,time,latitude,longitude,distance_km,speed_kmh,"
      + "course_deg,depth_m,altitude_m,ascent_m,accuracy_m,"
      + "satellites,battery_pct,what3words";

    private void saveToCsv(double[] avg, String w3w) {
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hia.csv");
        boolean isNew = !file.exists();

        // Upgrade old-format header in-place (one-time cost per file)
        if (!isNew) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                String firstLine = br.readLine();
                if (firstLine != null && !firstLine.contains("distance_km")) {
                    java.util.List<String> rest = new java.util.ArrayList<>();
                    String line;
                    while ((line = br.readLine()) != null) rest.add(line);
                    br.close();
                    FileWriter fw2 = new FileWriter(file, false);
                    fw2.write(CSV_HEADER + "\n");
                    for (String l : rest) fw2.write(l + "\n");
                    fw2.close();
                } else {
                    br.close();
                }
            } catch (IOException e) {
                writeLog("CSV header upgrade error: " + e.getMessage());
            }
        }

        try {
            FileWriter fw = new FileWriter(file, true);
            if (isNew)
                fw.write(CSV_HEADER + "\n");
            double speedKmh = displayPeriodHours > 0 ? lapDistanceKm / displayPeriodHours : 0;
            String courseStr = Double.isNaN(courseDeg) ? "" : String.format(Locale.US, "%.1f", courseDeg);
            String depthStr  = (Double.isNaN(depthM) || depthM == 0) ? "" : String.format(Locale.US, "%.1f", depthM);
            String w3wVal    = w3w.isEmpty() ? "" : "https://w3w.co/" + w3w;
            fw.write(String.format(Locale.US,
                "%s,%s,%s,%.6f,%.6f,%.2f,%.1f,%s,%s,%.1f,%.1f,%.1f,%d,%d,%s\n",
                tsFmt.format(now), dateFmt.format(now), timeFmt.format(now),
                avg[0], avg[1],
                lapDistanceKm, speedKmh, courseStr, depthStr,
                avg[2], lapAscentM, avg[3],
                csvSatellites, csvBattery, w3wVal));
            fw.close();
            writeLog("Saved CSV: " + file.getName() + " (" + file.length() + " bytes)");
        } catch (IOException e) {
            writeLog("CSV write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hia.csv");
    }

    private void saveToGpx(double[] avg) {
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hia.gpx");
        try {
            if (!file.exists()) {
                FileWriter fw = new FileWriter(file);
                fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                fw.write("<gpx version=\"1.1\" creator=\"Here I Am Now\"\n");
                fw.write("    xmlns=\"http://www.topografix.com/GPX/1/1\"\n");
                fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
                fw.write("    xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
                fw.write("  <trk><name>" + dateFmt.format(now) + "</name><trkseg>\n");
                fw.close();
            } else {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(raf.length() - GPX_CLOSE.getBytes("UTF-8").length);
                raf.close();
            }
            FileWriter fw = new FileWriter(file, true);
            fw.write(String.format(Locale.US,
                "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%s</time><sat>%d</sat></trkpt>\n",
                avg[0], avg[1], avg[2], isoFmt.format(now), csvSatellites));
            fw.write(GPX_CLOSE);
            fw.close();
        } catch (IOException e) {
            writeLog("GPX write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hia.gpx");
    }

    private void saveToKml(double[] avg) {
        File   dir   = docsDir();
        Date   now   = new Date();
        String today = dateFmt.format(now);
        File   file  = new File(dir, today + "-hia.kml");

        // Clear list on date rollover
        if (!today.equals(kmlCurrentDate)) {
            kmlTimestamps.clear();
            kmlLatLon.clear();
            lapAltitudes.clear();
            kmlCurrentDate = today;
        }
        // Reload from CSV if list is empty (service restart)
        if (kmlTimestamps.isEmpty() && file.exists()) {
            loadKmlFromCsv(dir, today);
        }

        kmlTimestamps.add(tsFmt.format(now));
        kmlLatLon.add(new double[]{avg[0], avg[1]}); // averaged lat, lon
        lapAltitudes.add(avg[2]);                    // averaged altitude

        try {
            FileWriter fw = new FileWriter(file, false); // overwrite each time
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
            fw.write("  <Document>\n");
            fw.write("    <name>" + today + "</name>\n");
            fw.write("    <Style id=\"track\"><LineStyle><color>ff0000ff</color><width>4</width></LineStyle></Style>\n");
            // Individual Point placemarks (POIs)
            for (int i = 0; i < kmlTimestamps.size(); i++) {
                double[] ll = kmlLatLon.get(i);
                fw.write(String.format(Locale.US,
                    "    <Placemark><name>%s</name><Point><coordinates>%.6f,%.6f,0</coordinates></Point></Placemark>\n",
                    kmlTimestamps.get(i), ll[1], ll[0]));
            }
            // LineString track (only if 2+ points)
            if (kmlLatLon.size() >= 2) {
                fw.write("    <Placemark><name>Track</name><styleUrl>#track</styleUrl>\n");
                fw.write("      <LineString><tessellate>1</tessellate>\n");
                fw.write("        <coordinates>\n");
                for (double[] ll : kmlLatLon) {
                    fw.write(String.format(Locale.US, "          %.6f,%.6f,0\n", ll[1], ll[0]));
                }
                fw.write("        </coordinates>\n");
                fw.write("      </LineString>\n");
                fw.write("    </Placemark>\n");
            }
            fw.write("  </Document>\n</kml>\n");
            fw.close();
        } catch (IOException e) {
            writeLog("KML write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hia.kml");
    }

    private void loadKmlFromCsv(File dir, String today) {
        File csv = new File(dir, today + "-hia.csv");
        if (!csv.exists()) return;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(csv));
            String line;
            boolean header = true;
            boolean newFormat = false;
            while ((line = br.readLine()) != null) {
                if (header) {
                    // new format has 15 cols; old has 10
                    newFormat = line.contains("distance_km");
                    header = false;
                    continue;
                }
                String[] cols = line.split(",", -1);
                int altCol = newFormat ? 9 : 5;
                if (cols.length <= altCol) continue;
                try {
                    double lat = Double.parseDouble(cols[3]);
                    double lon = Double.parseDouble(cols[4]);
                    double alt = Double.parseDouble(cols[altCol]);
                    kmlTimestamps.add(cols[0]);
                    kmlLatLon.add(new double[]{lat, lon});
                    lapAltitudes.add(alt);
                } catch (NumberFormatException ignored) {}
            }
            br.close();
            writeLog("KML: reloaded " + kmlTimestamps.size() + " points from CSV");
        } catch (IOException e) {
            writeLog("KML reload error: " + e.getMessage());
        }
    }

    // ── Lap distance ──────────────────────────────────────────────────────────

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double computeLapDistance() {
        long cutoff = System.currentTimeMillis() - displayPeriodHours * 3600_000L;
        double total = 0;
        double[] prev = null;
        for (int i = 0; i < kmlTimestamps.size(); i++) {
            try {
                Date ts = tsFmt.parse(kmlTimestamps.get(i));
                if (ts == null || ts.getTime() < cutoff) continue;
                double[] ll = kmlLatLon.get(i);
                if (prev != null) total += haversine(prev[0], prev[1], ll[0], ll[1]);
                prev = ll;
            } catch (Exception ignored) {}
        }
        return total;
    }

    // Minimum altitude gain per step counted toward lap ascent.
    // Filters GPS altitude noise (typically ±5–15 m even after averaging).
    private static final double MIN_ASCENT_STEP_M = 5.0;

    private double computeLapAscent() {
        long cutoff = System.currentTimeMillis() - displayPeriodHours * 3600_000L;
        double total = 0;
        Double prevAlt = null;
        for (int i = 0; i < kmlTimestamps.size(); i++) {
            try {
                Date ts = tsFmt.parse(kmlTimestamps.get(i));
                if (ts == null || ts.getTime() < cutoff) continue;
                double alt = lapAltitudes.get(i);
                if (prevAlt != null) {
                    double gain = alt - prevAlt;
                    if (gain >= MIN_ASCENT_STEP_M) {
                        total += gain;
                        prevAlt = alt; // only advance baseline on a counted step
                    } else if (alt < prevAlt) {
                        prevAlt = alt; // track descents so we don't re-count the same climb
                    }
                } else {
                    prevAlt = alt;
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    // ── Course and depth ──────────────────────────────────────────────────────

    private void computeAndUpdateCourse() {
        int size = kmlLatLon.size();
        if (size < 2) { courseDeg = Double.NaN; return; }
        int n = Math.min(4, size);
        int start = size - n;
        double sinSum = 0, cosSum = 0;
        int count = 0;
        for (int i = start; i < size - 1; i++) {
            double[] a = kmlLatLon.get(i);
            double[] b = kmlLatLon.get(i + 1);
            double lat1 = Math.toRadians(a[0]), lat2 = Math.toRadians(b[0]);
            double dlon = Math.toRadians(b[1] - a[1]);
            double y = Math.sin(dlon) * Math.cos(lat2);
            double x = Math.cos(lat1) * Math.sin(lat2)
                     - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dlon);
            double bearing = Math.toDegrees(Math.atan2(y, x));
            sinSum += Math.sin(Math.toRadians(bearing));
            cosSum += Math.cos(Math.toRadians(bearing));
            count++;
        }
        if (count == 0) { courseDeg = Double.NaN; return; }
        double avg = Math.toDegrees(Math.atan2(sinSum / count, cosSum / count));
        courseDeg = (avg + 360) % 360;
    }

    private double fetchDepth(double lat, double lon) {
        try {
            String urlStr = String.format(Locale.US,
                "https://api.opentopodata.org/v1/gebco2020?locations=%.6f,%.6f", lat, lon);
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "M21HereIAmApp/1.0");
            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                writeLog("Depth: HTTP " + httpCode + " from GEBCO API");
                return Double.NaN;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            String json = sb.toString();
            int idx = json.indexOf("\"elevation\":");
            if (idx < 0) {
                writeLog("Depth: could not parse elevation from GEBCO response");
                return Double.NaN;
            }
            int s = idx + 12;
            while (s < json.length() && json.charAt(s) == ' ') s++;
            int e = s;
            while (e < json.length() && (Character.isDigit(json.charAt(e))
                    || json.charAt(e) == '-' || json.charAt(e) == '.')) e++;
            if (s >= e) return Double.NaN;
            double elev = Double.parseDouble(json.substring(s, e));
            writeLog(String.format(Locale.US,
                "Depth: GEBCO elevation=%.1f m → depth=%s",
                elev, elev < 0 ? String.format(Locale.US, "%.1f m", -elev) : "0 (above sea level)"));
            return elev < 0 ? -elev : 0.0;
        } catch (Exception e) {
            writeLog("Depth: fetch error — " + e.getMessage());
            return Double.NaN;
        }
    }

    // ── What3Words lookup (web scrape) ────────────────────────────────────────

    private String lookupW3W(double lat, double lon) {
        writeLog(String.format(Locale.US, "W3W: looking up %.6f,%.6f", lat, lon));
        try {
            String urlStr = String.format(Locale.US,
                "https://what3words.com/%.6f,%.6f", lat, lon);
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept", "text/html");
            c.setRequestProperty("Accept-Language", "en");
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                writeLog("W3W: HTTP " + code + " — backing off");
                applyW3wBackoff();
                return "";
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                if (sb.indexOf("og:title") >= 0) break; // found what we need
                if (sb.length() > 65536) break;         // safety limit
            }
            br.close();
            c.disconnect();
            String words = extractW3wFromHtml(sb.toString());
            if (words == null) {
                writeLog("W3W: could not parse og:title from response — backing off");
                applyW3wBackoff();
                return "";
            }
            w3wFailCount    = 0;
            w3wBackoffTicks = 0;
            writeLog("W3W: https://w3w.co/" + words);
            return words;
        } catch (Exception e) {
            writeLog("W3W: error: " + e.getMessage() + " — backing off");
            applyW3wBackoff();
            return "";
        }
    }

    private void applyW3wBackoff() {
        w3wFailCount++;
        // Exponential backoff: 2, 4, 8, 16 ticks (capped at 16)
        int skip = Math.min(2 << (w3wFailCount - 1), 16);
        w3wBackoffTicks = skip;
        writeLog("W3W: will retry after " + skip + " tick(s) ("
            + skip * (updateInterval / 1000) + "s)");
    }

    private String extractW3wFromHtml(String html) {
        // Find the chunk containing og:title
        int idx = html.indexOf("og:title");
        if (idx < 0) return null;
        // Find content=" within the same meta tag (up to closing >)
        int tagEnd = html.indexOf(">", idx);
        String tag = (tagEnd > idx) ? html.substring(idx, tagEnd) : html.substring(idx);
        int contentIdx = tag.indexOf("content=\"");
        if (contentIdx < 0) return null;
        contentIdx += "content=\"".length();
        int end = tag.indexOf("\"", contentIdx);
        if (end < 0) return null;
        String value = tag.substring(contentIdx, end).trim();
        // Strip leading slashes (e.g. "///word.word.word" → "word.word.word")
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') start++;
        value = value.substring(start).trim();
        // Validate: must be word.word.word (lowercase letters only)
        if (!value.matches("[a-z]+\\.[a-z]+\\.[a-z]+")) return null;
        return value;
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    void writeLog(String message) {
        Log.d(TAG, message);
        File dir = docsDir();
        Date now = new Date();
        File logFile = new File(dir, dateFmt.format(now) + "-hia.txt");
        try {
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(tsFmt.format(now) + " " + message + "\n");
            fw.close();
        } catch (IOException ignored) {}
    }

    File docsDir() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void deleteOldFiles(File dir, String suffix) {
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(suffix) || name.length() < 10) continue;
            try {
                Date d = dateFmt.parse(name.substring(0, 10));
                if (d != null && d.getTime() < cutoff) f.delete();
            } catch (java.text.ParseException ignored) {}
        }
    }

    // Returns true if the file existed and was deleted.
    private boolean deleteNextcloudFile(String sessionDir, String fileName, String auth) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL(sessionDir + enc(fileName)).openConnection();
            c.setRequestMethod("DELETE");
            c.setRequestProperty("Authorization", auth);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteOldNextcloudFiles(String sessionDir, String auth) {
        long now   = System.currentTimeMillis();
        long dayMs = 24L * 60 * 60 * 1000;
        int deleted = 0;
        // Try to DELETE files for dates from retentionDays to retentionDays+30 days ago
        for (int age = retentionDays; age <= retentionDays + 30; age++) {
            String dateStr = dateFmt.format(new Date(now - age * dayMs));
            for (String suffix : LOG_SUFFIXES) {
                String fileName = dateStr + suffix;
                if (deleteNextcloudFile(sessionDir, fileName, auth)) {
                    writeLog("NC delete: " + fileName);
                    deleted++;
                }
            }
            // Daily settings backups (see SETTINGS_UPLOAD_SUFFIX) follow the same retention window.
            String settingsBackupName = dateStr + SETTINGS_UPLOAD_SUFFIX;
            if (deleteNextcloudFile(sessionDir, settingsBackupName, auth)) {
                writeLog("NC delete: " + settingsBackupName);
                deleted++;
            }
        }
        if (deleted > 0) writeLog("NC deleted " + deleted + " old file(s)");
    }
}
