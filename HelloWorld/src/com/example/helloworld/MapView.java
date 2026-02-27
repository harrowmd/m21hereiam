package com.example.helloworld;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapView extends View {

    private static final int TILE_SIZE  = 256;
    private static final int MIN_ZOOM   = 1;
    private static final int MAX_ZOOM   = 19;

    private int   zoom         = 16;
    private float displayScale = 1.0f;   // visual scale between integer zoom levels

    private double  lat         = 0;
    private double  lon         = 0;
    private boolean hasLocation = false;

    private final ConcurrentHashMap<String, Bitmap>  tileCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlight  = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final Paint tilePaint        = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final Paint dotFill          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotBorder        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotCenter        = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF tileRect = new RectF();

    private final ScaleGestureDetector scaleDetector;

    public MapView(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context, new PinchListener());
        init();
    }

    public MapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = new ScaleGestureDetector(context, new PinchListener());
        init();
    }

    private void init() {
        placeholderPaint.setColor(Color.parseColor("#D0D0D0"));

        dotFill.setColor(Color.parseColor("#2979FF"));
        dotFill.setStyle(Paint.Style.FILL);

        dotBorder.setColor(Color.WHITE);
        dotBorder.setStyle(Paint.Style.STROKE);
        dotBorder.setStrokeWidth(5f);

        dotCenter.setColor(Color.WHITE);
        dotCenter.setStyle(Paint.Style.FILL);
    }

    // ── Pinch gesture ─────────────────────────────────────────────────────────

    private class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            displayScale *= detector.getScaleFactor();
            displayScale  = Math.max(0.25f, Math.min(displayScale, 4.0f));

            // Promote to next integer zoom level when we've scaled 2×
            while (displayScale >= 2.0f && zoom < MAX_ZOOM) {
                zoom++;
                displayScale /= 2.0f;
                prefetchTiles();
            }
            // Demote when we've shrunk to half
            while (displayScale <= 0.5f && zoom > MIN_ZOOM) {
                zoom--;
                displayScale *= 2.0f;
                prefetchTiles();
            }

            invalidate();
            return true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        return true;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setLocation(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        this.hasLocation = true;
        prefetchTiles();
        postInvalidate();
    }

    // ── Tile math ─────────────────────────────────────────────────────────────

    private int tileX(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    private int tileY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << z));
    }

    private double pixelXInTile(double lon, int z) {
        double exact = (lon + 180.0) / 360.0 * (1 << z);
        return (exact - Math.floor(exact)) * TILE_SIZE;
    }

    private double pixelYInTile(double lat, int z) {
        double r = Math.toRadians(lat);
        double exact = (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << z);
        return (exact - Math.floor(exact)) * TILE_SIZE;
    }

    private int wrapX(int x, int z) {
        int max = 1 << z;
        return ((x % max) + max) % max;
    }

    // ── Tile fetching ─────────────────────────────────────────────────────────

    private void prefetchTiles() {
        int cx      = tileX(lon, zoom);
        int cy      = tileY(lat, zoom);
        int maxTile = (1 << zoom) - 1;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int ty = cy + dy;
                if (ty < 0 || ty > maxTile) continue;
                fetchTile(zoom, wrapX(cx + dx, zoom), ty);
            }
        }
    }

    private void fetchTile(final int z, final int x, final int y) {
        final String key = z + "/" + x + "/" + y;
        if (tileCache.containsKey(key) || inFlight.containsKey(key)) return;
        inFlight.put(key, Boolean.TRUE);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://tile.openstreetmap.org/" + key + ".png");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "HelloWorldAndroidApp/1.0");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    InputStream is = conn.getInputStream();
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp != null) {
                        tileCache.put(key, bmp);
                        postInvalidate();
                    }
                } catch (IOException ignored) {
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!hasLocation) {
            canvas.drawColor(Color.parseColor("#D0D0D0"));
            return;
        }

        int   W             = getWidth();
        int   H             = getHeight();
        float scaledTile    = TILE_SIZE * displayScale;

        int   cx  = tileX(lon, zoom);
        int   cy  = tileY(lat, zoom);
        float px  = (float) pixelXInTile(lon, zoom) * displayScale;
        float py  = (float) pixelYInTile(lat, zoom) * displayScale;

        float tileLeft = W / 2f - px;
        float tileTop  = H / 2f - py;

        int maxTile = (1 << zoom) - 1;
        int range   = (int) Math.ceil(Math.max(W, H) / scaledTile / 2) + 1;

        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                float drawX = tileLeft + dx * scaledTile;
                float drawY = tileTop  + dy * scaledTile;
                if (drawX + scaledTile < 0 || drawX > W) continue;
                if (drawY + scaledTile < 0 || drawY > H) continue;
                int ty = cy + dy;
                if (ty < 0 || ty > maxTile) continue;
                int tx = wrapX(cx + dx, zoom);

                String key  = zoom + "/" + tx + "/" + ty;
                Bitmap tile = tileCache.get(key);
                tileRect.set(drawX, drawY, drawX + scaledTile, drawY + scaledTile);
                if (tile != null) {
                    canvas.drawBitmap(tile, null, tileRect, tilePaint);
                } else {
                    canvas.drawRect(tileRect, placeholderPaint);
                    fetchTile(zoom, tx, ty);
                }
            }
        }

        // Location dot at screen centre
        float mx = W / 2f;
        float my = H / 2f;
        canvas.drawCircle(mx, my, 18f, dotFill);
        canvas.drawCircle(mx, my, 18f, dotBorder);
        canvas.drawCircle(mx, my,  6f, dotCenter);
    }
}
