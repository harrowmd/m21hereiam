package com.example.helloworld;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapView extends View {

    private static final int ZOOM = 16;
    private static final int TILE_SIZE = 256;

    private double lat = 0, lon = 0;
    private boolean hasLocation = false;

    private final ConcurrentHashMap<String, Bitmap> tileCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlight  = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final Paint tilePaint        = new Paint();
    private final Paint placeholderPaint = new Paint();
    private final Paint dotFill          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotBorder        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotCenter        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MapView(Context context) { super(context); init(); }
    public MapView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        placeholderPaint.setColor(Color.parseColor("#D0D0D0"));

        dotFill.setColor(Color.parseColor("#2979FF"));
        dotFill.setStyle(Paint.Style.FILL);

        dotBorder.setColor(Color.WHITE);
        dotBorder.setStyle(Paint.Style.STROKE);
        dotBorder.setStrokeWidth(5f);

        dotCenter.setColor(Color.WHITE);
        dotCenter.setStyle(Paint.Style.FILL);

        accuracyPaint.setColor(Color.parseColor("#402979FF"));
        accuracyPaint.setStyle(Paint.Style.FILL);
    }

    public void setLocation(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        this.hasLocation = true;
        prefetchTiles();
        postInvalidate();
    }

    // --- Tile math ---

    private int tileX(double lon) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << ZOOM));
    }

    private int tileY(double lat) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << ZOOM));
    }

    private double pixelXInTile(double lon) {
        double exact = (lon + 180.0) / 360.0 * (1 << ZOOM);
        return (exact - Math.floor(exact)) * TILE_SIZE;
    }

    private double pixelYInTile(double lat) {
        double r = Math.toRadians(lat);
        double exact = (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << ZOOM);
        return (exact - Math.floor(exact)) * TILE_SIZE;
    }

    // Metres per pixel at this zoom/latitude (used to draw accuracy circle)
    private float metresPerPixel() {
        return (float) (156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << ZOOM));
    }

    // --- Tile fetching ---

    private void prefetchTiles() {
        int cx = tileX(lon);
        int cy = tileY(lat);
        int maxTile = (1 << ZOOM) - 1;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int ty = cy + dy;
                if (ty < 0 || ty > maxTile) continue;
                int tx = wrapX(cx + dx);
                fetchTile(ZOOM, tx, ty);
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

    private int wrapX(int x) {
        int max = 1 << ZOOM;
        return ((x % max) + max) % max;
    }

    // --- Drawing ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!hasLocation) {
            canvas.drawColor(Color.parseColor("#D0D0D0"));
            return;
        }

        int W = getWidth();
        int H = getHeight();

        int cx = tileX(lon);
        int cy = tileY(lat);
        float px = (float) pixelXInTile(lon);
        float py = (float) pixelYInTile(lat);

        // Top-left pixel of the centre tile on screen
        float tileLeft = W / 2f - px;
        float tileTop  = H / 2f - py;

        int maxTile = (1 << ZOOM) - 1;
        int range = 5;

        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                float drawX = tileLeft + dx * TILE_SIZE;
                float drawY = tileTop  + dy * TILE_SIZE;
                if (drawX + TILE_SIZE < 0 || drawX > W) continue;
                if (drawY + TILE_SIZE < 0 || drawY > H) continue;
                int ty = cy + dy;
                if (ty < 0 || ty > maxTile) continue;
                int tx = wrapX(cx + dx);
                String key = ZOOM + "/" + tx + "/" + ty;
                Bitmap tile = tileCache.get(key);
                if (tile != null) {
                    canvas.drawBitmap(tile, drawX, drawY, tilePaint);
                } else {
                    canvas.drawRect(drawX, drawY, drawX + TILE_SIZE, drawY + TILE_SIZE, placeholderPaint);
                    fetchTile(ZOOM, tx, ty);
                }
            }
        }

        // Location marker at screen centre
        float mx = W / 2f;
        float my = H / 2f;

        canvas.drawCircle(mx, my, 18f, dotFill);
        canvas.drawCircle(mx, my, 18f, dotBorder);
        canvas.drawCircle(mx, my,  6f, dotCenter);
    }
}
