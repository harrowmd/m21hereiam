# Here I Am Now — User Guide

**Here I Am Now** is an Android GPS tracking app that records your location,
saves it to log files on the phone, and uploads them automatically to a
Nextcloud or OwnCloud server. It runs continuously in the background and can
alert you remotely by playing a sound, flashing the torch, vibrating the
phone, and taking photographs.

The app requires no Google Play Services and works on any Android phone
running Android 5.0 (API 21) or later, including de-Googled and custom ROM
devices.

---

## Contents

1. [Installation](#1-installation)
2. [Main Screen](#2-main-screen)
3. [Data Overlay](#3-data-overlay)
4. [Map Controls](#4-map-controls)
5. [Near Me Search](#5-near-me-search)
6. [Settings](#6-settings)
7. [Log Files](#7-log-files)
8. [Nextcloud Upload](#8-nextcloud-upload)
9. [Remote Alert System](#9-remote-alert-system)
10. [Background Operation](#10-background-operation)
11. [Start on Bootup](#11-start-on-bootup)

---

## 1. Installation

Install the APK file directly on the phone (sideload). You do not need the
Google Play Store.

1. On the phone go to **Settings → Security** and enable **Install unknown
   apps** (or **Unknown sources**) for your browser or file manager.
2. Download or copy `m21hereiamnow.apk` to the phone.
3. Open the APK file and tap **Install**.
4. When the app first opens, grant the **Location** and **Camera** permissions
   when prompted.

> On Android 11 and later the app uses the standard `Documents` folder
> without needing storage permission.

---

## 2. Main Screen

![Main screen](screen_main.png)

The full screen shows an **OpenStreetMap** map centred on your current
location. Three buttons are always visible:

| Button | Position | Function |
|--------|----------|----------|
| **NEAR ME** (green) | Top-left | Search for nearby points of interest |
| ⚙ (grey) | Top-right | Opens the Settings dialog |
| ⊕ (blue) | Bottom-right | Re-centres the map on your current position |

Your **current location** is shown as a solid blue dot. A trail of smaller
blue dots shows previously recorded positions within the **Display period**
and with at least **Min satellites** in fix (see [Settings](#5-settings)).
An optional **track line** can be drawn connecting the dots in a colour of
your choice (see [Track colour](#track-colour) in Settings).

The notification bar at the top of the phone will show a persistent
**"Here I Am Now"** notification while the app is running, displaying your
current coordinates. This keeps the background service alive.

---

## 3. Data Overlay

A **white bar** at the bottom of the screen shows data updated at each
**Update interval**. The fields shown depend on the **Map type** setting.

Most fields refresh only at the update interval, using the best averaged
GPS position calculated during that cycle. The exception is **GPS fix age**,
which counts up in real time every second.

### Land mode (default)

| Left column | Centre column | Right column (right-justified) |
|-------------|---------------|-------------------------------|
| `Dist: X.XX km` | Latitude | "what3words" |
| `Alt: X m` | Longitude | word 1 |
| `Ascent: X m` | `GPS fix age: Xs` | word 2 |
| Satellites in fix | Accuracy (m) | word 3 |

### Marine mode

| Left column | Centre column | Right column (right-justified) |
|-------------|---------------|-------------------------------|
| `Dist: X.XX km` | Latitude | "what3words" |
| `Alt / Depth` | Longitude | word 1 |
| `Course: XXX°` | `GPS fix age: Xs` | word 2 |
| Satellites in fix | Accuracy (m) | word 3 |

### Dist (distance)
Total distance in kilometres between consecutive GPS fixes recorded within
the current **Display period**. Calculated using the Haversine formula.
Resets when the service is restarted or when the display period rolls over.

### Alt *(Land mode only)*
Altitude above sea level in metres, averaged from all GPS fixes in the
current update cycle.

### Ascent *(Land mode only)*
Cumulative altitude climbed (metres) within the display period. Only upward
altitude changes of 5 m or more are counted, filtering out GPS altitude noise.
Descents are ignored.

### GPS fix age
Time in seconds since the last successful averaged GPS position was
calculated. Under normal conditions this counts up from 0 to the
**Update interval** (e.g. 0 → 60 s) then resets.

If the GPS chip loses lock or location services are unavailable, the counter
keeps counting upward past the update interval, giving a clear indication
that position data is stale. It resets to 0 as soon as a good averaged fix
is calculated again.

### Course *(Marine mode only)*
Average bearing in degrees (000°–360°) computed from the last four logged GPS
positions using vector-averaged bearings. Shows `--` until at least two
positions have been logged.

### Depth *(Marine mode only)*
Sea depth in metres, fetched from the GEBCO global bathymetric dataset via
[opentopodata.org](https://api.opentopodata.org). Fetched at most once every
15 minutes to respect the public API rate limit. Shows `--` on land, in
shallow waters with no data, or when the lookup is unavailable.

> Battery percentage is **not** shown on screen but is still recorded in the
> log files at every GPS fix.

### What3Words
The three-word address for the most recently logged GPS fix, updated at each
**Update interval**. Shows `--` until the first fix is logged or while a
lookup is in progress. The label and words are shown in **dark blue**.

Tapping anywhere on the What3Words column opens
`https://w3w.co/word1.word2.word3` in the default web browser.

---

## 4. Map Controls

| Gesture / Button | Action |
|---------|--------|
| **Pinch in / out** | Zoom (levels 1–19) |
| **Single-finger drag** | Pan the map |
| **Tap ⊕ button** | Snap map back to current GPS position |
| **Long-press ⊕ button** | Toggle auto-centre on/off |

The blue location dot moves independently of the map centre — if you pan
away to look at another area, the dot continues to show your real position.
Tap ⊕ to return the view to your location.

**Auto-recentre:** while the app is in the foreground, the map automatically
snaps back to your position whenever the GPS dot comes within 10% of the
visible map edge. This keeps you on screen without any manual interaction.

**Disabling auto-recentre:** long-press the ⊕ button to toggle auto-centre
off. The button turns grey when off and blue when on. A brief message
confirms the change. Use this when you want to pan to another location
without the map jumping back to your position.

Map tiles are fetched from **OpenStreetMap** (and **OpenSeaMap** in Marine
mode) over the internet and cached while the app is running.

**Pinch zoom tile loading:** while your fingers are on the screen the
existing map tiles scale smoothly. New higher-resolution tiles are only
fetched after you lift your fingers, with an 800 ms pause before loading
begins. Zooming in defers the switch to higher-resolution tiles until you
have scaled to 4× the current tile size; zooming out switches back to
lower-resolution tiles promptly at 0.5×.

---

## 5. Near Me Search

Tap the green **NEAR ME** button to search for points of interest near your
current GPS position. Results are fetched from **OpenStreetMap** via the
free Overpass API and displayed on the map as coloured 5-pointed stars.

**Tap any star** to see its name and distance from your current position.

### Categories and colours

| Star colour | Category |
|-------------|----------|
| Dark green | Sainsbury's |
| Light green | Other supermarkets |
| Dark orange | Petrol stations |
| Orange | Restaurants |
| Amber | Takeaways / fast food |
| Blue-grey | Car parks |
| Cyan | Landmarks (attractions, viewpoints, museums, galleries) |
| Purple | Churches / places of worship |

All categories are **off by default**. Select which ones to search for in
Near Me Settings before tapping the button.

### Near Me Settings

Open Near Me Settings by either:
- **Long-pressing** the green NEAR ME button, or
- Tapping **Near Me Settings** in the ⚙ Settings dialog

| Setting | Description |
|---------|-------------|
| **Search radius (km)** | How far from your position to search. No hard maximum, but very large radii (50+ km) may time out. |
| **Category checkboxes** | Tick the categories you want to include in the search. |

Tap **Save** to apply. Settings are remembered between sessions.

### Notes

- Results are capped at 200 per search to keep queries fast.
- If the server is busy (HTTP 429 or 504) the app automatically retries
  on a mirror server after a 2-second pause.
- OSM coverage is excellent in the UK for all categories. Landmark
  coverage depends on local contributors.
- Results stay on the map until you run a new search.

---

## 6. Settings

Tap the ⚙ button to open the Settings dialog. Scroll down to see all
options. Tap **Save** to apply changes immediately; tap **Cancel** to
discard. Tap **Help** at the top to open the built-in help page.

![Settings — top](screen_settings.png)
![Settings — bottom](screen_settings2.png)

### Session name
A label used as the subfolder name when uploading to Nextcloud.
Default: `mobyphone`

Use a different name for each phone so their files are stored separately on
the server, e.g. `phone1`, `alice`, `car`.

### Update interval (seconds)
How often the app records a GPS fix and writes a row to the log files.
Default: `60` seconds. Minimum: `10` seconds.

### Upload interval (seconds)
How often the app uploads the log files to Nextcloud.
Default: `300` seconds (5 minutes). Minimum: `10` seconds.

The upload interval must be greater than or equal to the update interval.
If a smaller value is entered, it is automatically raised to match the
update interval when **Save** is tapped and a notification is shown.

### Nextcloud / OwnCloud URL
The base URL of your Nextcloud or OwnCloud server.
Example: `https://cloud.example.com`

### Username / Password
Your Nextcloud login credentials. Tap **Show** to reveal the password.

### Alert code
A numeric or text code used for the remote alert feature (see
[Section 8](#8-remote-alert-system)).
Default: `911911`

### Alert photos
Number of photographs to take from each available camera when an alert
fires. Default: `3`. Range: `0`–`9`.

Set to `0` to disable alert photography entirely — useful on devices
that have no accessible camera (e.g. car head units) or where the
camera permission has not been granted.

On normal Android phones each camera produces this many JPEG images.
On devices where the camera hardware cannot be accessed through the
standard Android Camera2 API, the app logs the failure and moves on
without error.

### Min satellites
The minimum number of GPS satellites required for a fix to be shown as a
small blue dot on the map. Fixes with fewer satellites than this value are
recorded in the log files but not displayed on the map trail.
Default: `4`

Set to `0` to display all recorded positions regardless of GPS quality.

### Display period (hours)
How far back in time the map trail of small blue dots extends.
Default: `12` hours.

This setting also defines the time window used to calculate **Dist**,
**Speed**, **Ascent**, and **Course** in the data overlay. Changing this
value and tapping **Save** updates the map and all figures immediately.

### Num GPS fixes
The minimum number of GPS fixes that must be collected during an update
cycle before the averaged position is considered reliable.
Default: `5`

The GPS receiver runs continuously at 1-second intervals, accumulating
all fixes received during the cycle. At the end of each update interval,
statistical outliers are removed and the remaining fixes are averaged to
produce the logged position. If fewer than this minimum were received
(e.g. due to poor GPS conditions), the app still logs the best available
average but records a warning in the `.txt` log.

**Movement detection:** the app automatically detects whether the phone
is stationary or moving by comparing the first and last fix of the cycle.

- **Stationary** (moved <25 m): all fixes in the cycle are averaged,
  giving the best possible noise reduction.
- **Moving** (moved >25 m, i.e. walking pace or faster): only the most
  recent **Num GPS fixes** fixes are averaged. This ensures the logged
  position reflects where you are *now*, not the midpoint of the
  journey. At 60 km/h this avoids a ~500 m lag in the recorded position.

The `.txt` log records which mode was used and how many fixes were included.

### Track colour
Colour of the line drawn between the GPS track dots on the map.
Default: `None` (no line, dots only).

| Option | Effect |
|--------|--------|
| None | No line drawn; only the small blue dots are shown |
| Blue | Fine blue line connecting the dots |
| Red | Fine red line |
| Yellow | Fine yellow line |
| Black | Fine black line |

The line is drawn beneath the dots so the dots remain clearly visible.
The selected colour is saved and restored when the app restarts.

### Map type
Controls the map tile source and the data overlay fields.

| Option | Map tiles | Data overlay |
|--------|-----------|--------------|
| **Land** (default) | OpenStreetMap only | Ascent + Alt |
| **Marine** | OSM base + OpenSeaMap nautical overlay | Course + Depth |

In **Marine** mode a transparent nautical layer from
[OpenSeaMap](https://www.openseamap.org) is rendered on top of the standard
OSM base map, adding buoys, lights, depth contours, harbour marks, and other
seamarks. The data overlay switches to show **Course** (average bearing from
the last four GPS fixes) and **Depth** (GEBCO bathymetric data, fetched at
most every 15 minutes).

### Log file retention (days)
How many days of log files to keep, both on the device and on Nextcloud.
Default: `31` days. Minimum: `1` day.

Local files in the Documents folder older than this are deleted automatically.
Nextcloud files in the session folder are also deleted via the server API
at each upload cycle. Deletions are recorded in the `.txt` log:

```
NC delete: 2025-12-01-hia.csv
NC deleted 4 old file(s)
```

### Start on bootup
When ticked (default), the app starts automatically when the phone is
switched on. No manual launch is needed.

### Help
Tap the **Help** button at the top of the Settings dialog to open the
built-in help page. The page is titled **Here I Am Now for Nextcloud**
and covers a summary of the app, how it works, all settings, the remote
alert system, log file formats, and the in-app update feature. A
contact email link is shown at the bottom of the page.

### App updates
Each time the Settings dialog is opened, the app checks GitHub for a
newer release. The result is shown below the build information:

| Message | Meaning |
|---------|---------|
| `Checking for updates…` | Check in progress |
| `Up to date (v1.8)` | You have the latest version |
| `New version available: v1.x` | A newer release exists |

When a new version is available, a **Download & Install vX.Y** button
appears. Tap it to download the new APK in the background (a progress
notification is shown) and launch the system installer automatically
when the download is complete.

A permanent **Reinstall from GitHub** button is always shown below the
version check. Use this to re-download and reinstall the current
release APK at any time — for example to pick up minor fixes that have
been published to the release without a version number change.

---

## 7. Log Files

The app writes four log files per day, all stored in the phone's **Documents**
folder (`Internal Storage / Documents`):

| File | Format | Contents |
|------|--------|----------|
| `YYYY-MM-DD-hia.csv` | CSV | One row per GPS fix — 15 columns including position, derived metrics, and What3Words |
| `YYYY-MM-DD-hia.gpx` | GPX 1.1 | GPS track, suitable for mapping software and OSM GPS Traces |
| `YYYY-MM-DD-hia.kml` | KML 2.2 | GPS track with both a route line and individual waypoints |
| `YYYY-MM-DD-hia.txt` | Plain text | Debug and status log, including What3Words lookup results |

Files roll over at midnight. Files older than the **Log file retention**
setting (default 31 days) are deleted automatically from both the device
and Nextcloud.

### CSV format

```
timestamp,date,time,latitude,longitude,distance_km,speed_kmh,course_deg,depth_m,altitude_m,ascent_m,accuracy_m,satellites,battery_pct,what3words
2026-02-28 14:18:40,2026-02-28,14:18:40,51.444040,0.143970,1.24,0.1,247.3,,42.0,8.5,5.2,7,85,https://w3w.co/word1.word2.word3
```

| Column | Description |
|--------|-------------|
| `timestamp` | Full date and time of the fix (`YYYY-MM-DD HH:MM:SS`) |
| `date` | Date only |
| `time` | Time only |
| `latitude` | Decimal degrees |
| `longitude` | Decimal degrees |
| `distance_km` | Cumulative distance within the display period (km) |
| `speed_kmh` | Average speed over the display period (km/h) |
| `course_deg` | Average bearing in degrees (000–360); empty until 2 fixes logged |
| `depth_m` | Sea depth from GEBCO (m); empty on land or if unavailable |
| `altitude_m` | GPS altitude above sea level (m) |
| `ascent_m` | Cumulative ascent within the display period (m) |
| `accuracy_m` | GPS horizontal accuracy (m) |
| `satellites` | Number of satellites used in fix |
| `battery_pct` | Battery percentage |
| `what3words` | `https://w3w.co/word1.word2.word3` link; empty if lookup failed |

The `course_deg` and `depth_m` columns are populated in Marine mode; they
are empty (but present) in Land mode. All derived columns (`distance_km`,
`speed_kmh`, `course_deg`, `ascent_m`) reflect values computed at the time
of the fix after the previous interval's data was processed.

### GPX format

Standard GPX 1.1 track file with `<trkpt>` elements containing latitude,
longitude, altitude, UTC timestamp, and satellite count. Compatible with
OsmAnd, GPSLogger, OpenStreetMap GPS Traces, and other mapping tools.

### KML format

Each GPS fix is written as both:
- An individual **`<Point>` placemark** named with the timestamp — imported
  as a waypoint/POI by apps such as Magic Earth.
- A **`<LineString>` track** connecting all fixes for the day — displayed as
  a route line in Google Earth and other KML viewers.

### GPS status in the TXT log

At every update interval the app writes a summary line showing how many
fixes were collected, satellite count, GPS fix age, and provider status:

```
Log tick: fixes-collected=58 sat=8 GPS-fix-age=61s provider=ok
```

This is followed by the averaging result. When stationary:

```
GPS avg: static (moved <25m) | 58 fixes | pos-filter: 56 kept, 2 rejected (outlier threshold ~7m from centroid) | alt-filter: 56 kept, 0 rejected | result: lat=53.899444 lon=-1.684526 alt=140.8m acc=12.6m (acc range 8-31m)
```

When moving (e.g. in a car):

```
GPS avg: MOVING 820m in cycle — using last 5 of 58 fixes | pos-filter: 5 kept, 0 rejected | alt-filter: 5 kept, 0 rejected | result: lat=53.912341 lon=-1.701234 alt=52.0m acc=8.3m (acc range 7-11m)
```

If GPS is struggling, warnings appear:

```
Log tick: fixes-collected=2 sat=2 GPS-fix-age=183s provider=ok
GPS avg: only 2 fix(es) this cycle (min=5) — using best available
WARNING: last raw fix was 183s ago — GPS may have lost lock
```

### What3Words in the TXT log

After each averaged GPS fix the app looks up the What3Words address for that
location and records the result in the debug log:

```
W3W: looking up 51.444040,0.143970
W3W: https://w3w.co/word1.word2.word3
```

If the lookup fails, the reason is logged and the app backs off automatically
before retrying:

```
W3W: HTTP 503 — backing off
W3W: will retry after 4 tick(s) (240s)
W3W: backing off (3 tick(s) remaining)
```

The backoff doubles after each consecutive failure (2 → 4 → 8 → 16 ticks,
capped at 16). It resets automatically on success or when **Save** is tapped
in Settings.

---

## 8. Nextcloud Upload

The app uploads today's four log files (`.csv`, `.gpx`, `.kml`, `.txt`) to
your Nextcloud server at every **upload interval**.

Files are stored at:
```
{Nextcloud URL}/remote.php/dav/files/{username}/hereiam/{session}/
```

For example, with session name `alice`:
```
https://cloud.example.com/remote.php/dav/files/myuser/hereiam/alice/
```

The `hereiam/` and session folders are created automatically if they do not
exist. Previous days' files are not re-uploaded.

Upload results are written to the `.txt` debug log, for example:

```
Upload starting: url=https://cloud.example.com user=myuser session=alice
MKCOL hereiam/: 405
MKCOL alice/: 405
PUT 2026-02-28-hia.csv (22497 bytes) → HTTP 204
PUT 2026-02-28-hia.gpx (39239 bytes) → HTTP 204
PUT 2026-02-28-hia.kml (10726 bytes) → HTTP 204
PUT 2026-02-28-hia.txt (60191 bytes) → HTTP 204
Upload done: 4 file(s) → alice
```

> HTTP 405 on MKCOL means the folder already exists — this is normal.
> HTTP 204 on PUT means the file was uploaded successfully.

---

## 9. Remote Alert System

You can trigger a full alert on the phone remotely by uploading an MP3 file
to Nextcloud. When triggered the phone plays an alarm, flashes the torch,
vibrates, and **takes photographs**.

### How it works

1. Upload a file named `{alert code}.mp3` (e.g. `911911.mp3`) into the
   session folder on Nextcloud:
   ```
   hereiam/{session}/911911.mp3
   ```
2. At the next upload interval the phone detects the file and immediately
   begins taking photographs from all available cameras and uploading them
   to the same Nextcloud session folder.
3. The phone then plays the MP3 **4 times** with 5-second pauses between
   plays. During each play:
   - The MP3 plays at **maximum alarm volume**, bypassing Do Not Disturb.
   - The **camera LED torch flashes** on and off.
   - The **phone vibrates** in sync with the flash.
4. A red **Cancel Alert** button appears at the top of the screen.
5. The alert repeats every upload cycle until **Cancel Alert** is tapped.

### Alert photographs

JPEG images are captured from every camera the device exposes and
uploaded to the Nextcloud session folder. Filenames include the camera ID
and its facing direction:

```
YYYY-MM-DD-HHmmss-hia-alert-cam0-back-1.jpg
YYYY-MM-DD-HHmmss-hia-alert-cam1-front-1.jpg
YYYY-MM-DD-HHmmss-hia-alert-cam1-front-2.jpg
...
```

The number of photos per camera is controlled by the **Alert photos**
setting (default 3, range 0–9).

Photos are taken silently in the background so the alarm starts without
delay. The **Camera** permission must be granted when the app first opens.

> **Devices without a standard camera** (e.g. Android car head units):
> the app will log each camera it finds and attempt to use it.
> If no usable camera is found, it logs the result and continues normally.
> Set **Alert photos** to `0` in Settings to skip camera access entirely
> on such devices.

### Cancelling the alert

Tap **Cancel Alert** on the screen. This:
- Stops playback immediately.
- Turns off the torch and vibration.
- **Renames** `{alert code}.mp3` to `YYYY-MM-DD-{alert code}.mp3` on Nextcloud,
  keeping a timestamped record of the alert and preventing it from repeating.

The debug log records each step:

```
Alert: 911911.mp3 found, downloading
Alert: downloaded 9030 bytes
Alert photos: 2 camera(s) found on this device
Alert photos: cam0 facing=back hw=limited
Alert photos: cam1 facing=front hw=limited
Alert photo: saved 2026-03-01-112233-hia-alert-cam0-back-1.jpg (243891 bytes)
Alert photo: uploaded 2026-03-01-112233-hia-alert-cam0-back-1.jpg → HTTP 201
Alert photos: cam0-back photo 1/3 done
...
Alert photo: saved 2026-03-01-112233-hia-alert-cam1-front-1.jpg (187432 bytes)
Alert photo: uploaded 2026-03-01-112233-hia-alert-cam1-front-1.jpg → HTTP 201
Alert photos: cam1-front photo 1/3 done
...
Alert: playing (1/4)
Alert: playing (2/4)
Alert: playing (3/4)
Alert: playing (4/4)
Alert: playback complete
Alert: cancelled by user
Alert: 911911.mp3 renamed to 2026-03-01-911911.mp3 on Nextcloud
```

---

## 10. Background Operation

The app runs as an Android **foreground service**. This means:

- It continues recording GPS and uploading files even when the screen is
  off or another app is in use.
- A persistent notification is shown in the notification bar. This is
  required by Android to keep background services running reliably.
- If the service is killed by the system (e.g. low memory), Android
  restarts it automatically (`START_STICKY`).
- As a foreground service it is exempt from Android Doze mode
  restrictions — GPS and network access continue to work normally even
  when the phone is stationary with the screen off.

### Battery efficiency

The app is designed to minimise battery use between log ticks:

- The CPU sleeps between Handler callbacks — there are no spin loops.
- The GPS receiver runs continuously at 1-second intervals. This ensures
  reliable fix acquisition on Android 15, where restarting GPS between
  cycles causes long warm-up delays. The OS manages chip power internally.
- The data overlay, map dot, log files, and Nextcloud notification are all
  updated only once per **Update interval** — not on every raw GPS fix.
- Nextcloud uploads and What3Words lookups run on background threads
  and do not block the main service loop.
- A 60-second GPS watchdog automatically restarts location updates if no
  raw fix is received within twice the update interval.

You can safely leave the app running continuously for days or weeks.

---

## 11. Start on Bootup

With **Start on bootup** ticked (default), the app starts automatically
when the phone is switched on or restarted. No manual launch is needed.

To disable this, open Settings, untick **Start on bootup**, and tap Save.

---

## Compatibility

- **Android version**: 5.0 (API 21) and later
- **Google Play Services**: not required
- **De-Googled / custom ROM**: fully compatible
- **Map tiles**: OpenStreetMap (internet connection required for tile loading)
- **Nextcloud / OwnCloud**: any self-hosted instance accessible over HTTPS

---

## Build information

The app version, build date, and update status are shown at the bottom
of the Settings dialog, for example:

```
Here I Am Now  v2.5.1 (17)
Built: 2026-06-05 20:24
Up to date (v2.5.1)
```

Source code and releases: https://github.com/harrowmd/m21hereiam
