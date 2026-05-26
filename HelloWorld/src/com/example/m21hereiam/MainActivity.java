package com.example.m21hereiam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
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
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationService.Listener {

    private static final int PERM_REQUEST    = 1;
    private static final int PERM_REQUEST_BG = 2;

    // ── Near Me ───────────────────────────────────────────────────────────────

    private static final String PREF_NEARBY_RADIUS = "nearby_radius_km";

    private static final class NearbyCategory {
        final String label, prefKey, overpassFilter;
        final int    color;
        NearbyCategory(String label, String prefKey, String overpassFilter, int color) {
            this.label = label; this.prefKey = prefKey;
            this.overpassFilter = overpassFilter; this.color = color;
        }
    }

    // To add a new category later: one new NearbyCategory line here.
    // Put the most-selective INDEXED tag first in overpassFilter so Overpass
    // uses the tag index before applying any regex — keeps queries fast.
    private static final NearbyCategory[] CATEGORIES = {
        new NearbyCategory("Sainsbury's",        "nearby_sainsburys",
            "[\"shop\"][\"name\"~\"Sainsbury\",i]",              0xFF2E7D32),
        new NearbyCategory("Other supermarkets", "nearby_supermarkets",
            "[\"shop\"=\"supermarket\"]",                        0xFF81C784),
        new NearbyCategory("Petrol stations",    "nearby_petrol",
            "[\"amenity\"=\"fuel\"]",                            0xFFBF360C),
        new NearbyCategory("Restaurants",        "nearby_restaurants",
            "[\"amenity\"=\"restaurant\"]",                      0xFFFF9800),
        new NearbyCategory("Takeaways",          "nearby_takeaways",
            "[\"amenity\"~\"fast_food|takeaway\"]",              0xFFFFC107),
        new NearbyCategory("Car parks",          "nearby_carparks",
            "[\"amenity\"=\"parking\"]",                         0xFF607D8B),
        new NearbyCategory("Landmarks",          "nearby_landmarks",
            "[\"tourism\"~\"attraction|viewpoint|artwork|gallery|museum\"]", 0xFF00BCD4),
        new NearbyCategory("Churches",           "nearby_churches",
            "[\"amenity\"=\"place_of_worship\"]",                0xFF9C27B0),
    };

    private static final String[] OVERPASS_ENDPOINTS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    };

    private boolean[] nearbyEnabled  = new boolean[CATEGORIES.length];
    private int       nearbyRadiusKm = 5;

    // ── Views ─────────────────────────────────────────────────────────────────
    private MapView  mapView;
    private TextView tvLat, tvLon, tvAlt, tvAccuracy, tvSatellites, tvDist, tvSpeed, tvAscent;
    private TextView tvW3w1, tvW3w2, tvW3w3;
    private LinearLayout llW3w;
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

            onLapDistanceUpdate(service.lapDistanceKm);
            onLapAscentUpdate(service.lapAscentM);
            loadTrackPoints();
            mapView.setTrackColour(service.trackColour);
            mapView.setMapType(service.mapType);
            if ("Marine".equals(service.mapType)) {
                tvAscent.setText(Double.isNaN(service.courseDeg) ? "Course: --"
                    : String.format("Course: %03.0f°", service.courseDeg));
                tvAlt.setText((Double.isNaN(service.depthM) || service.depthM == 0) ? "Depth: --"
                    : String.format("Depth: %.0f m", service.depthM));
            }
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
        @Override public void run() { clockHandler.postDelayed(this, 1000); }
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
        tvDist       = (TextView) findViewById(R.id.tv_dist);
        tvSpeed      = (TextView) findViewById(R.id.tv_speed);
        tvAscent     = (TextView) findViewById(R.id.tv_ascent);
        tvW3w1       = (TextView) findViewById(R.id.tv_w3w_1);
        tvW3w2       = (TextView) findViewById(R.id.tv_w3w_2);
        tvW3w3       = (TextView) findViewById(R.id.tv_w3w_3);
        llW3w        = (LinearLayout) findViewById(R.id.ll_w3w);

        final Button btnRecentre = (Button) findViewById(R.id.btn_recentre);
        btnRecentre.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mapView.recentre(); }
        });
        btnRecentre.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                boolean on = mapView.toggleAutoRecentre();
                btnRecentre.setBackgroundColor(on ? 0xFF2979FF : 0xFF555555);
                android.widget.Toast.makeText(MainActivity.this,
                    on ? "Auto-centre ON" : "Auto-centre OFF",
                    android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
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

        // Load Near Me prefs
        android.content.SharedPreferences np =
            getSharedPreferences(LocationService.PREFS, MODE_PRIVATE);
        nearbyRadiusKm = np.getInt(PREF_NEARBY_RADIUS, 5);
        for (int i = 0; i < CATEGORIES.length; i++)
            nearbyEnabled[i] = np.getBoolean(CATEGORIES[i].prefKey, false);

        final Button btnNearby = (Button) findViewById(R.id.btn_nearby);
        btnNearby.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!mapView.hasGpsLocation()) {
                    android.widget.Toast.makeText(MainActivity.this,
                        "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                doNearbySearch(btnNearby);
            }
        });
        btnNearby.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showNearbySettingsDialog();
                return true;
            }
        });

        final View bottomBar = findViewById(R.id.bottom_bar);
        bottomBar.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    mapView.setBottomInset(bottomBar.getHeight());
                    bottomBar.getViewTreeObserver().removeOnGlobalLayoutListener(this);
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
                tvLat.setText(String.format("Lat: %.6f", lat));
                tvLon.setText(String.format("Lon: %.6f", lon));
                if (bound && "Marine".equals(service.mapType)) {
                    if (!tvAlt.getText().toString().startsWith("Depth"))
                        tvAlt.setText("Depth: --");
                } else {
                    tvAlt.setText(String.format("Alt: %.0f m", alt));
                }
                tvAccuracy.setText(String.format("Accuracy: %.0f m", acc));
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
        // Battery not displayed; kept in log files via LocationService
    }

    @Override
    public void onLapDistanceUpdate(final double km) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                tvDist.setText(km < 0.001
                    ? "Dist: 0.00 km"
                    : String.format("Dist: %.2f km", km));
                double speedKmh = bound && service.displayPeriodHours > 0
                    ? km / service.displayPeriodHours : 0;
                tvSpeed.setText(String.format("Speed: %.1f km/h", speedKmh));
            }
        });
    }

    @Override
    public void onLapAscentUpdate(final double m) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (bound && "Marine".equals(service.mapType)) return;
                tvAscent.setText(String.format("Ascent: %.0f m", m));
            }
        });
    }

    @Override
    public void onCourseUpdate(final double degrees) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!bound || !"Marine".equals(service.mapType)) return;
                if (Double.isNaN(degrees))
                    tvAscent.setText("Course: --");
                else
                    tvAscent.setText(String.format("Course: %03.0f°", degrees));
            }
        });
    }

    @Override
    public void onDepthUpdate(final double metres) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (Double.isNaN(metres) || metres == 0)
                    tvAlt.setText("Depth: --");
                else
                    tvAlt.setText(String.format("Depth: %.0f m", metres));
            }
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
                // Tint words light blue to indicate they are tappable
                int linkColour = 0xFF64B5F6;
                tvW3w1.setTextColor(linkColour);
                tvW3w2.setTextColor(linkColour);
                tvW3w3.setTextColor(linkColour);
                // Open https://w3w.co/word1.word2.word3 in browser on tap
                final String url = "https://w3w.co/" + words;
                llW3w.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    }
                });
            }
        });
    }

    // ── Near Me search ────────────────────────────────────────────────────────

    private void doNearbySearch(final Button btn) {
        final double lat = mapView.getGpsLat();
        final double lon = mapView.getGpsLon();
        final String query = buildOverpassQuery(lat, lon);
        if (query.isEmpty()) {
            android.widget.Toast.makeText(this,
                "No categories selected — long-press Near to configure",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        btn.setEnabled(false);
        btn.setText("...");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MapView.PoiMarker> results = new ArrayList<>();
                String errMsg = null;
                try {
                    String json = queryOverpass(query);
                    parseOverpassJson(json, results);
                } catch (Exception e) {
                    errMsg = "Search failed: " + e.getMessage();
                }
                final String finalErr = errMsg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btn.setEnabled(true);
                        btn.setText("NEAR ME");
                        if (finalErr != null) {
                            android.widget.Toast.makeText(MainActivity.this,
                                finalErr, android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            mapView.setPoiMarkers(results);
                            android.widget.Toast.makeText(MainActivity.this,
                                results.size() + " result"
                                    + (results.size() == 1 ? "" : "s") + " found",
                                android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private String buildOverpassQuery(double lat, double lon) {
        int radiusM = nearbyRadiusKm * 1000;
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon);
        StringBuilder q = new StringBuilder("[out:json][timeout:60];\n(\n");
        boolean any = false;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (!nearbyEnabled[i]) continue;
            q.append("  node").append(CATEGORIES[i].overpassFilter).append(around).append(";\n");
            q.append("  way").append(CATEGORIES[i].overpassFilter).append(around).append(";\n");
            any = true;
        }
        if (!any) return "";
        q.append(");\nout center 200;");
        return q.toString();
    }

    private String queryOverpass(String query) throws Exception {
        Exception lastEx = null;
        for (String endpoint : OVERPASS_ENDPOINTS) {
            try {
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
                byte[] body = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8");
                conn.getOutputStream().write(body);
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();
                    conn.disconnect();
                    return sb.toString();
                }
                conn.disconnect();
                if (code == 429 || code == 504 || code == 502) {
                    // Rate-limited or gateway error — pause then try mirror
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    lastEx = new Exception("HTTP " + code);
                } else {
                    throw new Exception("HTTP " + code);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP ")) throw e;
                lastEx = e;
            }
        }
        // All endpoints failed — give a clear message for 429
        String msg = lastEx != null ? lastEx.getMessage() : "unknown";
        if ("HTTP 429".equals(msg))
            throw new Exception("Server busy — please try again in a moment");
        throw new Exception(msg);
    }

    private void parseOverpassJson(String json, List<MapView.PoiMarker> out) {
        int arrStart = json.indexOf("\"elements\"");
        if (arrStart < 0) return;
        arrStart = json.indexOf('[', arrStart);
        if (arrStart < 0) return;

        int pos = arrStart + 1, depth = 0, elemStart = -1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') pos++;
                    else if (sc == '"') break;
                    pos++;
                }
            } else if (c == '{') {
                if (depth == 0) elemStart = pos;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && elemStart >= 0) {
                    parseOverpassElement(json.substring(elemStart, pos + 1), out);
                    elemStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
            pos++;
        }
    }

    private void parseOverpassElement(String elem, List<MapView.PoiMarker> out) {
        String type = extractJsonString(elem, "type");
        if (type == null) return;
        double lat, lon;
        try {
            if ("node".equals(type)) {
                String latS = extractJsonNumber(elem, "lat");
                String lonS = extractJsonNumber(elem, "lon");
                if (latS == null || lonS == null) return;
                lat = Double.parseDouble(latS);
                lon = Double.parseDouble(lonS);
            } else if ("way".equals(type)) {
                int ci = elem.indexOf("\"center\"");
                if (ci < 0) return;
                int cb = elem.indexOf('{', ci);
                int ce = findMatchingBrace(elem, cb);
                if (cb < 0 || ce < 0) return;
                String center = elem.substring(cb, ce + 1);
                String latS = extractJsonNumber(center, "lat");
                String lonS = extractJsonNumber(center, "lon");
                if (latS == null || lonS == null) return;
                lat = Double.parseDouble(latS);
                lon = Double.parseDouble(lonS);
            } else { return; }
        } catch (NumberFormatException e) { return; }

        String name = null, amenity = null, shop = null, tourism = null;
        int ti = elem.indexOf("\"tags\"");
        if (ti >= 0) {
            int tb = elem.indexOf('{', ti);
            int te = findMatchingBrace(elem, tb);
            if (tb >= 0 && te > tb) {
                String tags = elem.substring(tb, te + 1);
                name    = extractJsonString(tags, "name");
                amenity = extractJsonString(tags, "amenity");
                shop    = extractJsonString(tags, "shop");
                tourism = extractJsonString(tags, "tourism");
            }
        }

        // Match to a category to get the right colour
        int    color         = -1;
        String categoryLabel = null;
        String lname = name != null ? name.toLowerCase(Locale.ROOT) : "";
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (!nearbyEnabled[i]) continue;
            boolean match = false;
            switch (CATEGORIES[i].prefKey) {
                case "nearby_sainsburys":
                    match = lname.contains("sainsbury"); break;
                case "nearby_supermarkets":
                    match = "supermarket".equals(shop) && !lname.contains("sainsbury"); break;
                case "nearby_petrol":
                    match = "fuel".equals(amenity); break;
                case "nearby_restaurants":
                    match = "restaurant".equals(amenity); break;
                case "nearby_takeaways":
                    match = "fast_food".equals(amenity) || "takeaway".equals(amenity); break;
                case "nearby_carparks":
                    match = "parking".equals(amenity); break;
                case "nearby_landmarks":
                    match = tourism != null && (tourism.equals("attraction")
                        || tourism.equals("viewpoint") || tourism.equals("artwork")
                        || tourism.equals("gallery")   || tourism.equals("museum")); break;
                case "nearby_churches":
                    match = "place_of_worship".equals(amenity); break;
            }
            if (match) { color = CATEGORIES[i].color; categoryLabel = CATEGORIES[i].label; break; }
        }
        if (color == -1) return; // no enabled category matched

        String displayName = (name != null && !name.isEmpty()) ? name : categoryLabel;
        out.add(new MapView.PoiMarker(lat, lon, displayName, color));
    }

    private int findMatchingBrace(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '{') return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                while (i < s.length()) {
                    char sc = s.charAt(i);
                    if (sc == '\\') i++;
                    else if (sc == '"') break;
                    i++;
                }
            } else if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
        return -1;
    }

    private String extractJsonNumber(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && "-0123456789.eE+".indexOf(json.charAt(end)) >= 0) end++;
        return end > start ? json.substring(start, end) : null;
    }

    // ── Near Me settings dialog ───────────────────────────────────────────────

    private void showNearbySettingsDialog() {
        int dp8  = Math.round(8  * getResources().getDisplayMetrics().density);
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);
        int dp4  = Math.round(4  * getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp16, dp8, dp16, dp8);

        layout.addView(label("Search radius (km)"));
        final EditText editRadius = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(nearbyRadiusKm));
        layout.addView(editRadius);

        layout.addView(label("Search for:"));
        final CheckBox[] checks = new CheckBox[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(CATEGORIES[i].label);
            cb.setChecked(nearbyEnabled[i]);
            cb.setPadding(0, dp4, 0, dp4);
            layout.addView(cb);
            checks[i] = cb;
        }

        new AlertDialog.Builder(this)
            .setTitle("Near Me Settings")
            .setView(layout)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    try {
                        nearbyRadiusKm = Math.max(1,
                            Integer.parseInt(editRadius.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {}
                    android.content.SharedPreferences.Editor ed =
                        getSharedPreferences(LocationService.PREFS, MODE_PRIVATE).edit();
                    ed.putInt(PREF_NEARBY_RADIUS, nearbyRadiusKm);
                    for (int i = 0; i < CATEGORIES.length; i++) {
                        nearbyEnabled[i] = checks[i].isChecked();
                        ed.putBoolean(CATEGORIES[i].prefKey, nearbyEnabled[i]);
                    }
                    ed.apply();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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

        Button btnNearbySettings = new Button(this);
        btnNearbySettings.setText("Near Me Settings");
        btnNearbySettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNearbySettingsDialog(); }
        });
        layout.addView(btnNearbySettings);

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

        layout.addView(label("Number of alert photos (0\u20139, per camera)"));
        final EditText editAlertPhotos = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.alertPhotos));
        layout.addView(editAlertPhotos);

        layout.addView(label("Min satellites for map display"));
        final EditText editMinSat = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.minSat));
        layout.addView(editMinSat);

        layout.addView(label("Display period (hours)"));
        final EditText editDisplayPeriod = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.displayPeriodHours));
        layout.addView(editDisplayPeriod);

        layout.addView(label("Log file retention (days)"));
        final EditText editRetention = editText(InputType.TYPE_CLASS_NUMBER,
            String.valueOf(service.retentionDays));
        layout.addView(editRetention);

        layout.addView(label("Track colour"));
        final String[] trackColours = {"None", "Blue", "Red", "Yellow", "Black"};
        final android.widget.Spinner spinnerTrackColour = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> trackColourAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, trackColours);
        trackColourAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTrackColour.setAdapter(trackColourAdapter);
        for (int i = 0; i < trackColours.length; i++) {
            if (trackColours[i].equals(service.trackColour)) {
                spinnerTrackColour.setSelection(i);
                break;
            }
        }
        layout.addView(spinnerTrackColour);

        layout.addView(label("Map type"));
        final String[] mapTypes = {"Land (OpenStreetMap)", "Marine (OpenSeaMap overlay)"};
        final android.widget.Spinner spinnerMapType = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> mapTypeAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, mapTypes);
        mapTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMapType.setAdapter(mapTypeAdapter);
        spinnerMapType.setSelection("Marine".equals(service.mapType) ? 1 : 0);
        layout.addView(spinnerMapType);

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
                    if (service.uploadInterval < service.updateInterval) {
                        service.uploadInterval = service.updateInterval;
                        android.widget.Toast.makeText(MainActivity.this,
                            "Upload interval must be \u2265 update interval — set to "
                                + (service.uploadInterval / 1000) + "s",
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                    service.nextcloudUrl  = editUrl.getText().toString().trim();
                    service.nextcloudUser = editUser.getText().toString().trim();
                    service.nextcloudPass = editPass.getText().toString();
                    service.alertCode    = editAlertCode.getText().toString().trim();
                    try { service.alertPhotos =
                        Math.max(0, Math.min(9, Integer.parseInt(editAlertPhotos.getText().toString().trim()))); }
                    catch (NumberFormatException ignored) {}
                    service.w3wBackoffTicks = 0; // allow immediate retry after settings saved
                    service.w3wFailCount    = 0;
                    service.startOnBoot  = checkBoot.isChecked();
                    try { service.minSat =
                        Math.max(0, Integer.parseInt(editMinSat.getText().toString().trim())); }
                    catch (NumberFormatException ignored) {}
                    try { service.displayPeriodHours =
                        Math.max(1, Integer.parseInt(editDisplayPeriod.getText().toString().trim())); }
                    catch (NumberFormatException ignored) {}
                    service.trackColour = trackColours[spinnerTrackColour.getSelectedItemPosition()];
                    mapView.setTrackColour(service.trackColour);
                    service.mapType = spinnerMapType.getSelectedItemPosition() == 1 ? "Marine" : "Land";
                    mapView.setMapType(service.mapType);
                    try { service.retentionDays =
                        Math.max(1, Integer.parseInt(editRetention.getText().toString().trim())); }
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
                        .putInt    (LocationService.PREF_ALERT_PHOTOS,    service.alertPhotos)
                        .putBoolean(LocationService.PREF_START_ON_BOOT,   service.startOnBoot)
                        .putInt    (LocationService.PREF_MIN_SAT,         service.minSat)
                        .putInt    (LocationService.PREF_DISPLAY_PERIOD,  service.displayPeriodHours)
                        .putInt    (LocationService.PREF_NUM_GPS_FIXES,   service.numGpsFixes)
                        .putString (LocationService.PREF_TRACK_COLOUR,    service.trackColour)
                        .putInt    (LocationService.PREF_RETENTION_DAYS,  service.retentionDays)
                        .putString (LocationService.PREF_MAP_TYPE,         service.mapType)
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

            + "<b>Buttons</b><br>"
            + "<b>NEAR ME</b> (green, top-left) — search for nearby points of interest on the map. "
            + "Long-press to open Near Me Settings.<br>"
            + "<b>&#9881;</b> (grey, top-right) — open Settings.<br>"
            + "<b>&#8982;</b> (blue, bottom-right) — re-centre map on your GPS position. "
            + "Long-press to toggle auto-centre on/off (button turns grey when off).<br><br>"

            + "<b>How it works</b><br>"
            + "A background service records a GPS fix every <i>Update interval</i> seconds. "
            + "Each fix is averaged from multiple samples (see <i>Num GPS fixes</i>) to improve accuracy. "
            + "Fixes are written to four daily log files in the phone&#39;s Documents folder. "
            + "Files are uploaded to your Nextcloud server every <i>Upload interval</i> seconds. "
            + "The map shows your current position as a solid blue dot and recent track history "
            + "as smaller dots, filtered by <i>Min satellites</i> and <i>Display period</i>. "
            + "An optional coloured line can be drawn connecting the dots. "
            + "While the app is in the foreground, the map auto-recentres whenever the GPS dot "
            + "comes within 10% of any screen edge. Long-press the blue &#8982; button to "
            + "toggle auto-centre on/off.<br><br>"

            + "<b>Near Me search</b><br>"
            + "Tap the green <b>NEAR ME</b> button to search for points of interest around your "
            + "current GPS position using OpenStreetMap data. Results appear as coloured "
            + "5-pointed stars on the map. Tap any star to see its name and distance from you.<br>"
            + "Categories and their star colours:<br>"
            + "&nbsp;&nbsp;&#9733; <b>Sainsbury&#39;s</b> — dark green<br>"
            + "&nbsp;&nbsp;&#9733; <b>Other supermarkets</b> — light green<br>"
            + "&nbsp;&nbsp;&#9733; <b>Petrol stations</b> — dark orange<br>"
            + "&nbsp;&nbsp;&#9733; <b>Restaurants</b> — orange<br>"
            + "&nbsp;&nbsp;&#9733; <b>Takeaways</b> — amber<br>"
            + "&nbsp;&nbsp;&#9733; <b>Car parks</b> — blue-grey<br>"
            + "&nbsp;&nbsp;&#9733; <b>Landmarks</b> — cyan<br>"
            + "&nbsp;&nbsp;&#9733; <b>Churches</b> — purple<br>"
            + "Long-press <b>NEAR ME</b> (or tap <b>Near Me Settings</b> in the &#9881; Settings dialog) "
            + "to choose which categories to search and set the search radius. "
            + "All categories are off by default.<br><br>"

            + "<b>Data overlay — Land mode</b><br>"
            + "<b>Dist</b> — Total distance (km) between GPS fixes within the display period.<br>"
            + "<b>Speed</b> — Average speed over the display period (Dist &divide; hours), in km/h.<br>"
            + "<b>Ascent</b> — Cumulative altitude climbed (m) within the display period. "
            + "Only gains of 5 m or more per step are counted to filter GPS altitude noise.<br>"
            + "<b>Alt</b> — Altitude above sea level (m) from the GPS fix.<br>"
            + "<b>Satellites</b> — Number of GPS satellites currently used in fix.<br>"
            + "Battery percentage is recorded in log files but not shown on screen.<br><br>"

            + "<b>Data overlay — Marine mode</b><br>"
            + "In Marine mode two fields change:<br>"
            + "<b>Course</b> (replaces Ascent) — Average bearing in degrees (000&#176;&#8211;360&#176;) "
            + "computed from the last four logged GPS positions. Shows &#39;--&#39; until two fixes are available.<br>"
            + "<b>Depth</b> (replaces Alt) — Sea depth in metres from GEBCO global bathymetric data "
            + "(via opentopodata.org). Fetched at most once every 15 minutes to respect rate limits. "
            + "Shows &#39;--&#39; on land or when unavailable.<br><br>"

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
            + "<b>Display period</b> — Hours of track history shown on the map and used to calculate "
            + "Dist, Speed, and Ascent. Default: 12 h.<br>"
            + "<b>Track colour</b> — Line drawn between GPS dots on the map. "
            + "Options: None (default), Blue, Red, Yellow, Black.<br>"
            + "<b>Map type</b> — <i>Land</i> shows OpenStreetMap tiles. "
            + "<i>Marine</i> adds an OpenSeaMap nautical overlay (buoys, lights, seamarks) on top of OSM, "
            + "and switches the data overlay to show Course and Depth instead of Ascent and Alt.<br>"
            + "<b>Start on bootup</b> — Start automatically when the phone switches on.<br><br>"

            + "<b>Remote Alert</b><br>"
            + "Upload a file named <i>{alert code}.mp3</i> to the Nextcloud session folder. "
            + "At the next upload check the app downloads it, silently takes front and rear photos "
            + "(uploaded immediately), then plays the sound 4 times at maximum volume with the "
            + "torch flashing and phone vibrating. Tap <b>Cancel Alert</b> to stop. "
            + "The trigger file is renamed to <i>YYYY-MM-DD-{alert code}.mp3</i> as a timestamped record.<br><br>"

            + "<b>Log files</b> (Documents folder, auto-deleted after retention period)<br>"
            + "YYYY-MM-DD-hia.csv — one row per GPS fix, columns:<br>"
            + "&nbsp;&nbsp;timestamp, date, time, latitude, longitude,<br>"
            + "&nbsp;&nbsp;distance_km, speed_kmh, course_deg, depth_m,<br>"
            + "&nbsp;&nbsp;altitude_m, ascent_m, accuracy_m, satellites, battery_pct, what3words<br>"
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
                        boolean newFmt = false;
                        while ((line = br.readLine()) != null) {
                            if (header) {
                                newFmt = line.contains("distance_km");
                                header = false;
                                continue;
                            }
                            String[] cols = line.split(",", -1);
                            int satCol = newFmt ? 12 : 7;
                            if (cols.length <= satCol) continue;
                            try {
                                Date ts = tsFmt.parse(cols[0]);
                                if (ts == null || ts.getTime() < cutoff) continue;
                                int sats = Integer.parseInt(cols[satCol]);
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
