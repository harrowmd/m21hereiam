package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1;

    private static final String PREFS                = "hereiamnow";
    private static final String PREF_INTERVAL        = "update_interval_sec";
    private static final String PREF_UPLOAD_INTERVAL = "upload_interval_sec";
    private static final String PREF_NC_URL          = "nextcloud_url";
    private static final String PREF_NC_USER         = "nextcloud_user";
    private static final String PREF_NC_PASS         = "nextcloud_pass";
    private static final String PREF_SESSION         = "session";

    private static final String[] LOG_SUFFIXES = {"-hereiamnow.csv", "-hereiamnow.gpx", "-hereiamnow.kml"};

    // Closing tags for GPX / KML
    private static final String GPX_CLOSE = "    </trkseg>\n  </trk>\n</gpx>\n";
    private static final String KML_CLOSE = "        </coordinates>\n      </LineString>\n    </Placemark>\n  </Document>\n</kml>\n";

    // ── Settings (persisted) ──────────────────────────────────────────────────
    private long   updateInterval = 60_000;
    private long   uploadInterval = 300_000;
    private String nextcloudUrl   = "https://cloud.manytwo.one";
    private String nextcloudUser  = "";
    private String nextcloudPass  = "";
    private String session        = "mobyphone";

    // ── Views ─────────────────────────────────────────────────────────────────
    private MapView  mapView;
    private TextView tvLat, tvLon, tvAlt, tvAccuracy, tvSatellites, tvBattery, tvDate, tvTime;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;

    // ── Current sensor values ─────────────────────────────────────────────────
    private double csvLat        = 0;
    private double csvLon        = 0;
    private double csvAlt        = 0;
    private float  csvAccuracy   = 0;
    private int    csvSatellites = 0;
    private int    csvBattery    = 0;

    // ── Handlers ──────────────────────────────────────────────────────────────
    private final Handler clockHandler  = new Handler();
    private final Handler logHandler    = new Handler();
    private final Handler uploadHandler = new Handler();

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd",          Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss",            Locale.getDefault());
    private final SimpleDateFormat tsFmt   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat isoFmt;

    {
        isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            tvDate.setText("Date: " + dateFmt.format(now));
            tvTime.setText("Time: " + timeFmt.format(now));
            clockHandler.postDelayed(this, 1000);
        }
    };

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
            tvBattery.setText("Battery: " + csvBattery + "%");
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadSettings();

        mapView      = (MapView)  findViewById(R.id.map_view);
        tvLat        = (TextView) findViewById(R.id.tv_lat);
        tvLon        = (TextView) findViewById(R.id.tv_lon);
        tvAlt        = (TextView) findViewById(R.id.tv_alt);
        tvAccuracy   = (TextView) findViewById(R.id.tv_accuracy);
        tvSatellites = (TextView) findViewById(R.id.tv_satellites);
        tvBattery    = (TextView) findViewById(R.id.tv_battery);
        tvDate       = (TextView) findViewById(R.id.tv_date);
        tvTime       = (TextView) findViewById(R.id.tv_time);

        ((Button) findViewById(R.id.btn_recentre)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mapView.recentre(); }
        });
        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsDialog(); }
        });

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
                    tvSatellites.setText("Satellites: " + used);
                }
            };
        }

        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
        logHandler.postDelayed(logTick, updateInterval);
        uploadHandler.postDelayed(uploadTick, uploadInterval);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        logHandler.removeCallbacks(logTick);
        uploadHandler.removeCallbacks(uploadTick);
        locationManager.removeUpdates(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            locationManager.unregisterGnssStatusCallback(gnssCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        updateInterval = p.getInt(PREF_INTERVAL,        60)  * 1000L;
        uploadInterval = p.getInt(PREF_UPLOAD_INTERVAL, 300) * 1000L;
        nextcloudUrl   = p.getString(PREF_NC_URL,  "https://cloud.manytwo.one");
        nextcloudUser  = p.getString(PREF_NC_USER, "");
        nextcloudPass  = p.getString(PREF_NC_PASS, "");
        session        = p.getString(PREF_SESSION, "mobyphone");
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_INTERVAL,        (int) (updateInterval / 1000))
            .putInt(PREF_UPLOAD_INTERVAL, (int) (uploadInterval / 1000))
            .putString(PREF_NC_URL,  nextcloudUrl)
            .putString(PREF_NC_USER, nextcloudUser)
            .putString(PREF_NC_PASS, nextcloudPass)
            .putString(PREF_SESSION, session)
            .apply();
    }

    private void applyUpdateInterval() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(this);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, updateInterval, 0, this);
        }
        logHandler.removeCallbacks(logTick);
        logHandler.postDelayed(logTick, updateInterval);
        uploadHandler.removeCallbacks(uploadTick);
        uploadHandler.postDelayed(uploadTick, uploadInterval);
    }

    private void showSettingsDialog() {
        int dp4  = Math.round(4  * getResources().getDisplayMetrics().density);
        int dp8  = Math.round(8  * getResources().getDisplayMetrics().density);
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp16, dp8, dp16, dp8);

        layout.addView(label("Session name"));
        final EditText editSession = editText(InputType.TYPE_CLASS_TEXT, session);
        layout.addView(editSession);

        layout.addView(label("Update interval (seconds)"));
        final EditText editInterval = editText(InputType.TYPE_CLASS_NUMBER, String.valueOf(updateInterval / 1000));
        layout.addView(editInterval);

        layout.addView(label("Upload interval (seconds)"));
        final EditText editUpload = editText(InputType.TYPE_CLASS_NUMBER, String.valueOf(uploadInterval / 1000));
        layout.addView(editUpload);

        layout.addView(label("Nextcloud / OwnCloud URL"));
        final EditText editUrl = editText(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, nextcloudUrl);
        layout.addView(editUrl);

        layout.addView(label("Username"));
        final EditText editUser = editText(InputType.TYPE_CLASS_TEXT, nextcloudUser);
        layout.addView(editUser);

        layout.addView(label("Password"));
        final EditText editPass = editText(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, nextcloudPass);

        final Button btnToggle = new Button(this);
        btnToggle.setText("Show");
        btnToggle.setTextSize(12);
        btnToggle.setTag(Boolean.FALSE);
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean hidden = (Boolean) btnToggle.getTag();
                editPass.setInputType(hidden
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnToggle.setText(hidden ? "Show" : "Hide");
                btnToggle.setTag(!hidden);
                editPass.setSelection(editPass.getText().length());
            }
        });

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.addView(editPass, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        passRow.addView(btnToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(passRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    session = editSession.getText().toString().trim();
                    if (session.isEmpty()) session = "mobyphone";
                    try { updateInterval = Math.max(10, Integer.parseInt(editInterval.getText().toString().trim())) * 1000L; }
                    catch (NumberFormatException ignored) {}
                    try { uploadInterval = Math.max(10, Integer.parseInt(editUpload.getText().toString().trim())) * 1000L; }
                    catch (NumberFormatException ignored) {}
                    nextcloudUrl  = editUrl.getText().toString().trim();
                    nextcloudUser = editUser.getText().toString().trim();
                    nextcloudPass = editPass.getText().toString();
                    saveSettings();
                    applyUpdateInterval();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        int dp4 = Math.round(4 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, dp4 * 3, 0, dp4);
        return tv;
    }

    private EditText editText(int inputType, String value) {
        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setText(value);
        return et;
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestNeededPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!needed.isEmpty())
            requestPermissions(needed.toArray(new String[0]), LOCATION_PERMISSION_REQUEST);
        else
            startLocationUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        startLocationUpdates();
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvLat.setText("Location permission denied");
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, updateInterval, 0, this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            locationManager.registerGnssStatusCallback(gnssCallback);
        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) updateDisplay(last);
    }

    private void updateDisplay(Location loc) {
        csvLat      = loc.getLatitude();
        csvLon      = loc.getLongitude();
        csvAlt      = loc.getAltitude();
        csvAccuracy = loc.getAccuracy();
        tvLat.setText(String.format("Lat: %.6f",       csvLat));
        tvLon.setText(String.format("Lon: %.6f",       csvLon));
        tvAlt.setText(String.format("Alt: %.1f m",     csvAlt));
        tvAccuracy.setText(String.format("Accuracy: %.1f m", csvAccuracy));
        mapView.setLocation(csvLat, csvLon);
    }

    @Override public void onLocationChanged(Location location) { updateDisplay(location); }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    // ── Nextcloud upload ──────────────────────────────────────────────────────

    private void uploadFiles() {
        if (nextcloudUrl.isEmpty() || nextcloudUser.isEmpty()) return;

        // Capture settings on the main thread before handing off
        final String url  = nextcloudUrl.replaceAll("/+$", "");
        final String user = nextcloudUser;
        final String pass = nextcloudPass;
        final String sess = session.isEmpty() ? "mobyphone" : session;
        final File   dir  = docsDir();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String auth = "Basic " + Base64.encodeToString(
                        (user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);

                    String davRoot    = url + "/remote.php/dav/files/" + enc(user) + "/";
                    String hereibDir  = davRoot + "hereiam/";
                    String sessionDir = hereibDir + enc(sess) + "/";

                    mkCol(hereibDir,  auth);
                    mkCol(sessionDir, auth);

                    File[] files = dir.listFiles();
                    if (files == null) return;
                    for (File f : files) {
                        for (String suffix : LOG_SUFFIXES) {
                            if (f.getName().endsWith(suffix)) {
                                putFile(f, sessionDir + enc(f.getName()), auth);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /** MKCOL — silently accepts 201 (created) and 405 (already exists). */
    private void mkCol(String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("MKCOL");
        c.setRequestProperty("Authorization", auth);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 201 && code != 405 && code != 301 && code != 302)
            throw new IOException("MKCOL " + url + " returned " + code);
    }

    /** PUT a local file to a WebDAV URL. */
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
        fis.close();
        os.close();

        c.getResponseCode();
        c.disconnect();
    }

    private String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    // ── CSV saving ────────────────────────────────────────────────────────────

    private void saveToCsv() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            return;
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

    // ── GPX saving ────────────────────────────────────────────────────────────

    private void saveToGpx() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            return;
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

    // ── KML saving ────────────────────────────────────────────────────────────

    private void saveToKml() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            return;
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

    private File docsDir() {
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
