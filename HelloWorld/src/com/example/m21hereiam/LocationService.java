package com.example.m21hereiam;

import android.Manifest;
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
import android.util.Base64;
import android.util.Log;

import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.VibrationEffect;
import android.os.Vibrator;

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
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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
    static final String PREF_START_ON_BOOT   = "start_on_boot";

    private static final String[] LOG_SUFFIXES = {
        "-hereiamnow.csv", "-hereiamnow.gpx", "-hereiamnow.kml", "-hereiamnow.txt"
    };
    private static final String GPX_CLOSE =
        "    </trkseg>\n  </trk>\n</gpx>\n";
    private static final String KML_CLOSE =
        "  </Document>\n</kml>\n";

    // ── Settings ──────────────────────────────────────────────────────────────
    long   updateInterval = 60_000;
    long   uploadInterval = 300_000;
    String nextcloudUrl   = "https://cloud.manytwo.one";
    String nextcloudUser  = "";
    String nextcloudPass  = "";
    String session        = "mobyphone";
    String  alertCode     = "911911";
    boolean startOnBoot   = true;

    // ── Current sensor values ─────────────────────────────────────────────────
    double csvLat        = 0;
    double csvLon        = 0;
    double csvAlt        = 0;
    float  csvAccuracy   = 0;
    int    csvSatellites = 0;
    int    csvBattery    = 0;

    // ── UI callback interface ──────────────────────────────────────────────────
    interface Listener {
        void onLocationUpdate(double lat, double lon, double alt, float accuracy);
        void onSatellitesUpdate(int count);
        void onBatteryUpdate(int pct);
        void onAlertStarted();
        void onAlertStopped();
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
    private boolean timersStarted = false;
    private LocationManager     locationManager;
    private GnssStatus.Callback gnssCallback;

    boolean hasLocation = false; // true once a real GPS fix has been received

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

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            saveToCsv();
            saveToGpx();
            saveToKml();
            logHandler.postDelayed(this, updateInterval);
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
        loadSettings();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    csvSatellites = used;
                    if (uiListener != null) uiListener.onSatellitesUpdate(csvSatellites);
                }
            };
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification("Waiting for GPS\u2026"));
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
        }
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
                + " | Android API " + Build.VERSION.SDK_INT);
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
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        updateInterval = p.getInt(PREF_INTERVAL,        60)  * 1000L;
        uploadInterval = p.getInt(PREF_UPLOAD_INTERVAL, 300) * 1000L;
        nextcloudUrl   = p.getString(PREF_NC_URL,  "https://cloud.manytwo.one");
        nextcloudUser  = p.getString(PREF_NC_USER, "");
        nextcloudPass  = p.getString(PREF_NC_PASS, "");
        session        = p.getString(PREF_SESSION,     "mobyphone");
        alertCode    = p.getString (PREF_ALERT_CODE,    "911911");
        startOnBoot  = p.getBoolean(PREF_START_ON_BOOT, true);
        writeLog("Settings loaded: update=" + (updateInterval/1000) + "s upload=" + (uploadInterval/1000)
            + "s session=" + session + " alert=" + alertCode + " boot=" + startOnBoot
            + " url=" + nextcloudUrl + " user=" + nextcloudUser);
    }

    void applySettings() {
        writeLog("Settings applied: update=" + (updateInterval/1000) + "s upload=" + (uploadInterval/1000)
            + "s session=" + session + " alert=" + alertCode
            + " url=" + nextcloudUrl + " user=" + nextcloudUser);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        startLocationUpdates();
        logHandler.removeCallbacks(logTick);
        logHandler.postDelayed(logTick, updateInterval);
        uploadHandler.removeCallbacks(uploadTick);
        uploadHandler.postDelayed(uploadTick, uploadInterval);
        uploadFiles();
    }

    // ── Location ──────────────────────────────────────────────────────────────

    void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            writeLog("Location permission not granted — updates not started");
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, updateInterval, 0, this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
                locationManager.registerGnssStatusCallback(gnssCallback);
            writeLog("Requesting GPS updates every " + (updateInterval/1000) + "s");
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null)
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) {
                writeLog("Using last known location age=" + ((System.currentTimeMillis() - last.getTime())/1000) + "s");
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
        if (!hasLocation) {
            hasLocation = true;
            writeLog("First GPS fix received");
        }
        writeLog(String.format(Locale.US,
            "GPS fix: lat=%.6f lon=%.6f alt=%.1fm acc=%.1fm sat=%d bat=%d%%",
            csvLat, csvLon, csvAlt, csvAccuracy, csvSatellites, csvBattery));
        updateNotification(String.format(Locale.US, "%.5f, %.5f", csvLat, csvLon));
        if (uiListener != null)
            uiListener.onLocationUpdate(csvLat, csvLon, csvAlt, csvAccuracy);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p)  { writeLog("Provider enabled: "  + p); }
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
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIF_ID, buildNotification(text));
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
                    writeLog("Upload done: " + uploaded + " file(s) \u2192 " + sess);
                    if (!alert.isEmpty())
                        checkForAlert(alert, sessionDir, auth, dir);
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

            // Store URL/auth so cancelAlert() can delete from Nextcloud
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
            // Keep alertActive=true and button visible until user presses Cancel
        } catch (Exception e) {
            writeLog("Alert error: " + e.getMessage());
            torchOff();
        }
    }

    // Play loop extracted so volume is always restored
    private void playAlertAudio(File mp3) throws Exception {
        AudioManager am  = (AudioManager) getSystemService(AUDIO_SERVICE);
        Vibrator      vib = (Vibrator)     getSystemService(VIBRATOR_SERVICE);
        int origVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
        int maxVol  = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0);
        writeLog("Alert: alarm volume set to max (" + maxVol + "), was " + origVol);
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
                    torchOn();
                    vibrateStart(vib, vibePattern);
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
        writeLog("Alert: cancelled by user");
        alertCancelled = true;
        alertActive    = false;
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
            deleteFromNextcloud(url, auth, alertCode + ".mp3");
        if (uiListener != null) uiListener.onAlertStopped();
    }

    private void deleteFromNextcloud(final String url, final String auth, final String fileName) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("DELETE");
                    c.setRequestProperty("Authorization", auth);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code < 300)
                        writeLog("Alert: " + fileName + " deleted from Nextcloud (HTTP " + code + ")");
                    else
                        writeLog("Alert: DELETE " + fileName + " failed (HTTP " + code + ")");
                } catch (Exception e) {
                    writeLog("Alert: DELETE " + fileName + " failed: " + e.getMessage());
                }
            }
        }).start();
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

    private void saveToCsv() {
        if (!hasLocation) { writeLog("Log tick: no GPS fix yet, skipping"); return; }
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.csv");
        boolean isNew = !file.exists();
        try {
            FileWriter fw = new FileWriter(file, true);
            if (isNew)
                fw.write("timestamp,date,time,latitude,longitude,altitude_m,accuracy_m,satellites,battery_pct\n");
            fw.write(String.format(Locale.US, "%s,%s,%s,%.6f,%.6f,%.1f,%.1f,%d,%d\n",
                tsFmt.format(now), dateFmt.format(now), timeFmt.format(now),
                csvLat, csvLon, csvAlt, csvAccuracy, csvSatellites, csvBattery));
            fw.close();
            writeLog("Saved CSV: " + file.getName() + " (" + file.length() + " bytes)");
        } catch (IOException e) {
            writeLog("CSV write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hereiamnow.csv");
    }

    private void saveToGpx() {
        if (!hasLocation) return;
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.gpx");
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
                "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%s</time><sat>%d</sat><hdop>%.1f</hdop></trkpt>\n",
                csvLat, csvLon, csvAlt, isoFmt.format(now), csvSatellites, csvAccuracy));
            fw.write(GPX_CLOSE);
            fw.close();
        } catch (IOException e) {
            writeLog("GPX write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hereiamnow.gpx");
    }

    private void saveToKml() {
        if (!hasLocation) return;
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.kml");
        try {
            if (!file.exists()) {
                FileWriter fw = new FileWriter(file);
                fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                fw.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
                fw.write("  <Document>\n");
                fw.write("    <name>" + dateFmt.format(now) + "</name>\n");
                fw.close();
            } else {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(raf.length() - KML_CLOSE.getBytes("UTF-8").length);
                raf.close();
            }
            FileWriter fw = new FileWriter(file, true);
            fw.write(String.format(Locale.US,
                "    <Placemark><name>%s</name><Point><coordinates>%.6f,%.6f,0</coordinates></Point></Placemark>\n",
                tsFmt.format(now), csvLon, csvLat));
            fw.write(KML_CLOSE);
            fw.close();
        } catch (IOException e) {
            writeLog("KML write error: " + e.getMessage());
        }
        deleteOldFiles(dir, "-hereiamnow.kml");
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    void writeLog(String message) {
        Log.d(TAG, message);
        File dir = docsDir();
        Date now = new Date();
        File logFile = new File(dir, dateFmt.format(now) + "-hereiamnow.txt");
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
        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
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
}
