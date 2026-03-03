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
import android.text.Html;
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
    private TextView tvW3w1, tvW3w2, tvW3w3;
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
            if (!service.w3wAddress.isEmpty()) onW3wUpdate(service.w3wAddress);
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
            tvDate.setText(dateFmt.format(now));
            tvTime.setText(timeFmt.format(now));
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
        tvW3w1       = (TextView) findViewById(R.id.tv_w3w_1);
        tvW3w2       = (TextView) findViewById(R.id.tv_w3w_2);
        tvW3w3       = (TextView) findViewById(R.id.tv_w3w_3);

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

    @Override
    public void onW3wUpdate(final String words) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                String[] parts = words.split("\\.");
                tvW3w1.setText(parts.length > 0 ? parts[0] : "--");
                tvW3w2.setText(parts.length > 1 ? parts[1] : "--");
                tvW3w3.setText(parts.length > 2 ? parts[2] : "--");
            }
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

        Button btnHelp = new Button(this);
        btnHelp.setText("Help");
        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelpDialog(); }
        });
        layout.addView(btnHelp);

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

        final TextView tvUpdateStatus = new TextView(this);
        tvUpdateStatus.setText("Checking for updates\u2026");
        tvUpdateStatus.setTextSize(12);
        tvUpdateStatus.setPadding(0, dp4, 0, dp4);
        layout.addView(tvUpdateStatus);

        final Button btnInstall = new Button(this);
        btnInstall.setVisibility(View.GONE);
        layout.addView(btnInstall);

        final Button btnReinstall = new Button(this);
        btnReinstall.setText("Reinstall from GitHub");
        btnReinstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                downloadAndInstall(
                    "https://github.com/harrowmd/m21hereiam/releases/latest/download/m21hereiamnow.apk",
                    "latest", tvUpdateStatus, btnReinstall);
            }
        });
        layout.addView(btnReinstall);

        checkForUpdate(tvUpdateStatus, btnInstall);

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
                    service.w3wBackoffTicks = 0; // allow immediate retry after settings saved
                    service.w3wFailCount    = 0;
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

    // ── Help dialog ───────────────────────────────────────────────────────────

    private void showHelpDialog() {
        int dp8  = Math.round(8  * getResources().getDisplayMetrics().density);
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);

        String html =
            "<b>Here I Am Now</b><br>"
            + "Android GPS tracking app. Records your location continuously in the background, "
            + "saves it to log files on the phone, and uploads them automatically to a "
            + "Nextcloud or OwnCloud server. No Google Play Services required.<br><br>"

            + "<b>How it works</b><br>"
            + "A background service records a GPS fix every <i>Update interval</i> seconds. "
            + "Each fix is averaged from multiple samples (see <i>Num GPS fixes</i>) to improve accuracy. "
            + "Fixes are written to four daily log files in the phone&#39;s Documents folder. "
            + "Files are uploaded to your Nextcloud server every <i>Upload interval</i> seconds. "
            + "The map shows your current position as a solid blue dot and recent track history "
            + "as smaller dots, filtered by <i>Min satellites</i> and <i>Display period</i>.<br><br>"

            + "<b>Settings</b><br>"
            + "<b>Session name</b> — Nextcloud subfolder for this device&#39;s files. "
            + "Use a different name per device (e.g. phone1, car).<br>"
            + "<b>Update interval</b> — How often a GPS fix is recorded. Default: 60 s.<br>"
            + "<b>Num GPS fixes</b> — Samples averaged per log entry to improve accuracy. Default: 5.<br>"
            + "<b>Upload interval</b> — How often files are sent to Nextcloud. Default: 300 s.<br>"
            + "<b>Nextcloud / OwnCloud URL</b> — Base URL of your server, e.g. https://cloud.example.com<br>"
            + "<b>Username / Password</b> — Your Nextcloud login credentials.<br>"
            + "<b>Alert code</b> — Code used to trigger a remote alert (default: 911911).<br>"
            + "<b>Min satellites</b> — Minimum satellites required for a fix to appear on the map trail. "
            + "Set to 0 to show all fixes.<br>"
            + "<b>Display period</b> — Hours of track history shown on the map. Default: 12 h.<br>"
            + "<b>Start on bootup</b> — Start automatically when the phone switches on.<br><br>"

            + "<b>Remote Alert</b><br>"
            + "Upload a file named <i>{alert code}.mp3</i> to the Nextcloud session folder. "
            + "At the next upload check the app downloads it, silently takes front and rear photos "
            + "(uploaded immediately), then plays the sound 4 times at maximum volume with the "
            + "torch flashing and phone vibrating. Tap <b>Cancel Alert</b> to stop. "
            + "The trigger file is renamed to <i>YYYY-MM-DD-{alert code}.mp3</i> as a timestamped record.<br><br>"

            + "<b>Log files</b> (Documents folder, 30-day auto-delete)<br>"
            + "YYYY-MM-DD-hia.csv — one row per GPS fix<br>"
            + "YYYY-MM-DD-hia.gpx — GPX 1.1 track<br>"
            + "YYYY-MM-DD-hia.kml — KML track with waypoints<br>"
            + "YYYY-MM-DD-hia.txt — debug and status log<br><br>"

            + "<b>App updates</b><br>"
            + "Each time Settings is opened the app checks GitHub for a newer release. "
            + "If one is found, tap <b>Download &amp; Install</b> to update automatically.<br><br>"

            + "Source: https://github.com/harrowmd/m21hereiam<br><br>"
            + "<a href=\"mailto:Martin.Harrow@talk21.com\">Martin.Harrow@talk21.com</a>";

        TextView tv = new TextView(this);
        tv.setPadding(dp16, dp8, dp16, dp8);
        tv.setTextSize(13);
        tv.setLineSpacing(0, 1.3f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            tv.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
        else
            tv.setText(Html.fromHtml(html));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);

        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        new AlertDialog.Builder(this)
            .setTitle("Here I Am Now for Nextcloud")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }

    // ── Update check ──────────────────────────────────────────────────────────

    private void checkForUpdate(final TextView tvStatus, final Button btnInstall) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/harrowmd/m21hereiam/releases/latest")
                            .openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    int code = c.getResponseCode();
                    if (code != 200) {
                        c.disconnect();
                        postUpdateStatus(tvStatus, "Update check failed (HTTP " + code + ")");
                        return;
                    }
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    c.disconnect();

                    String json        = sb.toString();
                    String tagName     = extractJsonString(json, "tag_name");
                    String downloadUrl = extractJsonString(json, "browser_download_url");
                    if (tagName == null || downloadUrl == null) {
                        postUpdateStatus(tvStatus, "Update check failed (parse error)");
                        return;
                    }

                    String latestVer = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    if (latestVer.equals(pi.versionName)) {
                        postUpdateStatus(tvStatus, "Up to date (v" + pi.versionName + ")");
                    } else {
                        final String dl  = downloadUrl;
                        final String tag = tagName;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvStatus.setText("New version available: " + tag);
                                tvStatus.setTextColor(0xFF006600);
                                btnInstall.setText("Download & Install " + tag);
                                btnInstall.setVisibility(View.VISIBLE);
                                btnInstall.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        downloadAndInstall(dl, tag, tvStatus, btnInstall);
                                    }
                                });
                            }
                        });
                    }
                } catch (Exception e) {
                    postUpdateStatus(tvStatus, "Update check failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void postUpdateStatus(final TextView tv, final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { tv.setText(msg); }
        });
    }

    private void downloadAndInstall(final String url, final String tag,
                                     final TextView tvStatus, final Button btnInstall) {
        tvStatus.setText("Downloading " + tag + "\u2026");
        btnInstall.setEnabled(false);
        // Remove any old download file first
        java.io.File old = new java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "m21hereiamnow-update.apk");
        if (old.exists()) old.delete();

        final android.app.DownloadManager dm =
            (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        android.app.DownloadManager.Request req =
            new android.app.DownloadManager.Request(android.net.Uri.parse(url));
        req.setTitle("Here I Am Now " + tag);
        req.setDescription("Downloading update\u2026");
        req.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setMimeType("application/vnd.android.package-archive");
        req.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS, "m21hereiamnow-update.apk");
        final long downloadId = dm.enqueue(req);

        new Thread(new Runnable() {
            @Override public void run() {
                boolean running = true;
                while (running) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    android.app.DownloadManager.Query q = new android.app.DownloadManager.Query();
                    q.setFilterById(downloadId);
                    android.database.Cursor cursor = dm.query(q);
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                            cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS));
                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            running = false;
                            cursor.close();
                            final android.net.Uri apkUri = dm.getUriForDownloadedFile(downloadId);
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    tvStatus.setText("Download complete");
                                    Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                                    install.setData(apkUri);
                                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(install);
                                }
                            });
                        } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                            running = false;
                            cursor.close();
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    tvStatus.setText("Download failed");
                                    btnInstall.setEnabled(true);
                                }
                            });
                        } else {
                            cursor.close();
                        }
                    } else {
                        if (cursor != null) cursor.close();
                    }
                }
            }
        }).start();
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + search.length());
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
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
                    File f = new File(dir, dateFmt.format(day) + "-hia.csv");
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
