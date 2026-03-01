package com.example.m21hereiam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationService.Listener {

    private static final int PERM_REQUEST    = 1;
    private static final int PERM_REQUEST_BG = 2;

    // ── Views ─────────────────────────────────────────────────────────────────
    private MapView  mapView;
    private TextView tvLat, tvLon, tvAlt, tvAccuracy, tvSatellites, tvBattery, tvDate, tvTime;
    private Button   btnCancelAlert;

    // ── Service binding ───────────────────────────────────────────────────────
    private LocationService service;
    private boolean         bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder bnd) {
            service = ((LocationService.LocalBinder) bnd).getService();
            service.setListener(MainActivity.this);
            bound = true;
            // Populate UI with whatever the service already knows
            onLocationUpdate(service.csvLat, service.csvLon, service.csvAlt, service.csvAccuracy);
            onSatellitesUpdate(service.csvSatellites);
            onBatteryUpdate(service.csvBattery);
            loadTrackPoints();
            // Restore Cancel Alert button if alert was already active
            if (service.alertActive)
                btnCancelAlert.setVisibility(View.VISIBLE);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound   = false;
            service = null;
        }
    };

    // ── Clock ─────────────────────────────────────────────────────────────────
    private final Handler clockHandler = new Handler();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd",          Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss",            Locale.getDefault());
    private final SimpleDateFormat tsFmt   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            tvDate.setText("Date: " + dateFmt.format(now));
            tvTime.setText("Time: " + timeFmt.format(now));
            clockHandler.postDelayed(this, 1000);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

        btnCancelAlert = (Button) findViewById(R.id.btn_cancel_alert);
        btnCancelAlert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bound) service.cancelAlert();
            }
        });

        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startAndBindService();
            requestBackgroundLocationIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        if (bound) {
            service.setListener(null);
            unbindService(connection);
            bound = false;
        }
    }

    // ── LocationService.Listener ──────────────────────────────────────────────

    @Override
    public void onLocationUpdate(final double lat, final double lon,
                                 final double alt, final float acc) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                tvLat.setText(String.format("Lat: %.6f",          lat));
                tvLon.setText(String.format("Lon: %.6f",          lon));
                tvAlt.setText(String.format("Alt: %.1f m",        alt));
                tvAccuracy.setText(String.format("Accuracy: %.1f m", acc));
                mapView.setLocation(lat, lon);
            }
        });
    }

    @Override
    public void onSatellitesUpdate(final int count) {
        runOnUiThread(new Runnable() {
            @Override public void run() { tvSatellites.setText("Satellites: " + count); }
        });
    }

    @Override
    public void onBatteryUpdate(final int pct) {
        runOnUiThread(new Runnable() {
            @Override public void run() { tvBattery.setText("Battery: " + pct + "%"); }
        });
    }

    @Override
    public void onAlertStarted() {
        runOnUiThread(new Runnable() {
            @Override public void run() { btnCancelAlert.setVisibility(View.VISIBLE); }
        });
    }

    @Override
    public void onAlertStopped() {
        runOnUiThread(new Runnable() {
            @Override public void run() { btnCancelAlert.setVisibility(View.GONE); }
        });
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private void showSettingsDialog() {
        if (!bound) return;
        int dp8  = Math.round(8  * getResources().getDisplayMetrics().density);
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);
        int dp4  = Math.round(4  * getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp16, dp8, dp16, dp8);

        layout.addView(label("Session name"));
        final EditText editSession = editText(InputType.TYPE_CLASS_TEXT, service.session);
        layout.addView(editSession);

        layout.addView(label("Update interval (seconds)"));
        final EditText editInterval = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.updateInterval / 1000));
        layout.addView(editInterval);

        layout.addView(label("Num GPS fixes (averaged per log entry)"));
        final EditText editNumFixes = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.numGpsFixes));
        layout.addView(editNumFixes);

        layout.addView(label("Upload interval (seconds)"));
        final EditText editUpload = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.uploadInterval / 1000));
        layout.addView(editUpload);

        layout.addView(label("Nextcloud / OwnCloud URL"));
        final EditText editUrl = editText(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            service.nextcloudUrl);
        layout.addView(editUrl);

        layout.addView(label("Username"));
        final EditText editUser = editText(InputType.TYPE_CLASS_TEXT, service.nextcloudUser);
        layout.addView(editUser);

        layout.addView(label("Password"));
        final EditText editPass = editText(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            service.nextcloudPass);

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
        passRow.addView(editPass, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        passRow.addView(btnToggle, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(passRow);

        layout.addView(label("Alert code"));
        final EditText editAlertCode = editText(InputType.TYPE_CLASS_TEXT, service.alertCode);
        layout.addView(editAlertCode);

        layout.addView(label("Min satellites for map display"));
        final EditText editMinSat = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.minSat));
        layout.addView(editMinSat);

        layout.addView(label("Display period (hours)"));
        final EditText editDisplayPeriod = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.displayPeriodHours));
        layout.addView(editDisplayPeriod);

        final CheckBox checkBoot = new CheckBox(this);
        checkBoot.setText("Start on bootup");
        checkBoot.setChecked(service.startOnBoot);
        checkBoot.setPadding(0, dp8 * 2, 0, dp4);
        layout.addView(checkBoot);

        TextView tvBuildInfo = new TextView(this);
        tvBuildInfo.setText(getBuildInfo());
        tvBuildInfo.setTextSize(11);
        tvBuildInfo.setPadding(0, dp8 * 3, 0, dp4);
        layout.addView(tvBuildInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (!bound) return;
                    service.session = editSession.getText().toString().trim();
                    if (service.session.isEmpty()) service.session = "mobyphone";
                    try { service.updateInterval =
                        Math.max(10, Integer.parseInt(editInterval.getText().toString().trim())) * 1000L; }
                    catch (NumberFormatException ignored) {}
                    try { service.numGpsFixes =
                        Math.max(1, Integer.parseInt(editNumFixes.getText().toString().trim())); }
                    catch (NumberFormatException ignored) {}
                    try { service.uploadInterval =
                        Math.max(10, Integer.parseInt(editUpload.getText().toString().trim())) * 1000L; }
                    catch (NumberFormatException ignored) {}
                    service.nextcloudUrl  = editUrl.getText().toString().trim();
                    service.nextcloudUser = editUser.getText().toString().trim();
                    service.nextcloudPass = editPass.getText().toString();
                    service.alertCode    = editAlertCode.getText().toString().trim();
                    service.startOnBoot  = checkBoot.isChecked();
                    try { service.minSat =
                        Math.max(0, Integer.parseInt(editMinSat.getText().toString().trim())); }
                    catch (NumberFormatException ignored) {}
                    try { service.displayPeriodHours =
                        Math.max(1, Integer.parseInt(editDisplayPeriod.getText().toString().trim())); }
                    catch (NumberFormatException ignored) {}
                    // Persist to SharedPreferences
                    getSharedPreferences(LocationService.PREFS, MODE_PRIVATE).edit()
                        .putInt    (LocationService.PREF_INTERVAL,        (int) (service.updateInterval / 1000))
                        .putInt    (LocationService.PREF_UPLOAD_INTERVAL, (int) (service.uploadInterval / 1000))
                        .putString (LocationService.PREF_NC_URL,          service.nextcloudUrl)
                        .putString (LocationService.PREF_NC_USER,         service.nextcloudUser)
                        .putString (LocationService.PREF_NC_PASS,         service.nextcloudPass)
                        .putString (LocationService.PREF_SESSION,         service.session)
                        .putString (LocationService.PREF_ALERT_CODE,      service.alertCode)
                        .putBoolean(LocationService.PREF_START_ON_BOOT,   service.startOnBoot)
                        .putInt    (LocationService.PREF_MIN_SAT,         service.minSat)
                        .putInt    (LocationService.PREF_DISPLAY_PERIOD,  service.displayPeriodHours)
                        .putInt    (LocationService.PREF_NUM_GPS_FIXES,   service.numGpsFixes)
                        .apply();
                    service.applySettings();
                    loadTrackPoints(); // refresh map with new filters
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String getBuildInfo() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.app_name)
                + "  v" + pi.versionName + " (" + pi.versionCode + ")"
                + "\nBuilt: " + getString(R.string.build_date);
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.app_name);
        }
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        // WRITE_EXTERNAL_STORAGE is silently ignored on Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!needed.isEmpty())
            requestPermissions(needed.toArray(new String[0]), PERM_REQUEST);
        else
            startAndBindService();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == PERM_REQUEST) {
            startAndBindService();
            if (bound) service.startLocationUpdates();
            // On Android 10+, request background location separately after fine is granted
            requestBackgroundLocationIfNeeded();
        }
        // PERM_REQUEST_BG result: nothing to do, service will get GPS if granted
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                PERM_REQUEST_BG);
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent);
        else
            startService(intent);
        if (!bound)
            bindService(intent, connection, BIND_AUTO_CREATE);
    }

    // ── Track history ─────────────────────────────────────────────────────────

    private void loadTrackPoints() {
        if (!bound) return;
        final File dir    = service.docsDir();
        final int  minSat = service.minSat;
        final long cutoff = System.currentTimeMillis() - service.displayPeriodHours * 3600_000L;
        // Number of past days to scan: enough to cover the display period
        final int daysBack = (int) Math.ceil(service.displayPeriodHours / 24.0) + 1;
        new Thread(new Runnable() {
            @Override public void run() {
                List<double[]> points = new ArrayList<>();
                Date now = new Date();
                for (int d = daysBack; d >= 0; d--) {
                    Date day = new Date(now.getTime() - d * 24L * 60 * 60 * 1000);
                    File f = new File(dir, dateFmt.format(day) + "-hereiamnow.csv");
                    if (!f.exists()) continue;
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(f));
                        String line;
                        boolean header = true;
                        while ((line = br.readLine()) != null) {
                            if (header) { header = false; continue; }
                            String[] cols = line.split(",");
                            if (cols.length < 8) continue;
                            try {
                                Date ts = tsFmt.parse(cols[0]);
                                if (ts == null || ts.getTime() < cutoff) continue;
                                int sats = Integer.parseInt(cols[7]);
                                if (sats < minSat) continue;
                                double lat = Double.parseDouble(cols[3]);
                                double lon = Double.parseDouble(cols[4]);
                                points.add(new double[]{lat, lon});
                            } catch (Exception ignored) {}
                        }
                        br.close();
                    } catch (IOException ignored) {}
                }
                final List<double[]> result = points;
                runOnUiThread(new Runnable() {
                    @Override public void run() { mapView.setTrackPoints(result); }
                });
            }
        }).start();
    }
}
