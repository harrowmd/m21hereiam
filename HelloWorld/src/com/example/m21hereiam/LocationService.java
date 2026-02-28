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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
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

    private static final String[] LOG_SUFFIXES = {
        "-hereiamnow.csv", "-hereiamnow.gpx", "-hereiamnow.kml"
    };
    private static final String GPX_CLOSE =
        "    </trkseg>\n  </trk>\n</gpx>\n";
    private static final String KML_CLOSE =
        "        </coordinates>\n      </LineString>\n    </Placemark>\n  </Document>\n</kml>\n";

    // ── Settings ──────────────────────────────────────────────────────────────
    long   updateInterval = 60_000;
    long   uploadInterval = 300_000;
    String nextcloudUrl   = "https://cloud.manytwo.one";
    String nextcloudUser  = "";
    String nextcloudPass  = "";
    String session        = "mobyphone";

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
    private LocationManager     locationManager;
    private GnssStatus.Callback gnssCallback;

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

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            csvBattery = (scale > 0) ? (level * 100 / scale) : -1;
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
        startForeground(NOTIF_ID, buildNotification("Waiting for GPS\u2026"));
        writeLog("Service started");
        startLocationUpdates();
        logHandler.post(logTick);
        uploadHandler.postDelayed(uploadTick, uploadInterval);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
        session        = p.getString(PREF_SESSION, "mobyphone");
    }

    void applySettings() {
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
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, updateInterval, 0, this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
                locationManager.registerGnssStatusCallback(gnssCallback);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null)
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException ignored) {}
    }

    @Override
    public void onLocationChanged(Location loc) {
        csvLat      = loc.getLatitude();
        csvLon      = loc.getLongitude();
        csvAlt      = loc.getAltitude();
        csvAccuracy = loc.getAccuracy();
        updateNotification(String.format(Locale.US, "%.5f, %.5f", csvLat, csvLon));
        if (uiListener != null)
            uiListener.onLocationUpdate(csvLat, csvLon, csvAlt, csvAccuracy);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

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
        final File   dir   = docsDir();
        final String today = dateFmt.format(new Date());

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

                    // Only upload today's 3 log files
                    int uploaded = 0;
                    for (String suffix : LOG_SUFFIXES) {
                        File f = new File(dir, today + suffix);
                        if (f.exists()) {
                            putFile(f, sessionDir + enc(f.getName()), auth);
                            uploaded++;
                        }
                    }
                    writeLog("Uploaded " + uploaded + " file(s) \u2192 " + sess);
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

    private void putFile(File file, String url, String auth) throws IOException {
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
        c.getResponseCode();
        c.disconnect();
    }

    private String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    // ── CSV / GPX / KML saving ────────────────────────────────────────────────

    private void saveToCsv() {
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
        } catch (IOException ignored) {}
        deleteOldFiles(dir, "-hereiamnow.csv");
    }

    private void saveToGpx() {
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
        } catch (IOException ignored) {}
        deleteOldFiles(dir, "-hereiamnow.gpx");
    }

    private void saveToKml() {
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.kml");
        try {
            if (!file.exists()) {
                FileWriter fw = new FileWriter(file);
                fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                fw.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n  <Document>\n");
                fw.write("    <name>" + dateFmt.format(now) + "</name>\n");
                fw.write("    <Style id=\"track\"><LineStyle><color>ff0000ff</color><width>4</width></LineStyle></Style>\n");
                fw.write("    <Placemark><name>Track</name><styleUrl>#track</styleUrl>\n");
                fw.write("      <LineString><tessellate>1</tessellate><altitudeMode>absolute</altitudeMode>\n");
                fw.write("        <coordinates>\n");
                fw.close();
            } else {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(raf.length() - KML_CLOSE.getBytes("UTF-8").length);
                raf.close();
            }
            FileWriter fw = new FileWriter(file, true);
            fw.write(String.format(Locale.US, "          %.6f,%.6f,%.1f\n", csvLon, csvLat, csvAlt));
            fw.write(KML_CLOSE);
            fw.close();
        } catch (IOException ignored) {}
        deleteOldFiles(dir, "-hereiamnow.kml");
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    void writeLog(String message) {
        Log.d(TAG, message);
        File dir = docsDir();
        Date now = new Date();
        File logFile = new File(dir, dateFmt.format(now) + "-hereiamnow.log");
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
