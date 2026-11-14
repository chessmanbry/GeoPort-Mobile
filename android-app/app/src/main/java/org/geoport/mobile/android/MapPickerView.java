package org.geoport.mobile.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Online raster map for choosing coordinates, with no location permission of its own. */
public final class MapPickerView extends FrameLayout {
    public interface PointListener {
        void onPointSelected(double latitude, double longitude);
    }

    private static final String TILE_URL = "https://tile.openstreetmap.org/";
    private static final String USER_AGENT = "GeoPort-Mobile-Android/1.0 (org.geoport.mobile.android)";
    private static final String ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright";
    private static final int TILE_CACHE_SIZE = 32 * 1024 * 1024;
    private static final int MAX_TILE_BYTES = 1024 * 1024;
    private static final double MAX_MAP_LATITUDE = 85.0511287798066;
    private static final double LOG_2 = Math.log(2);
    private static final ExecutorService TILE_EXECUTOR = Executors.newFixedThreadPool(2);

    private static final class TileRequest {
        Future<?> task;
    }

    private final float density;
    private final MapSurface map;
    private PointListener pointListener;

    public MapPickerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        map = new MapSurface(context, installTileCache(context));
        addView(map, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout zoom = new LinearLayout(context);
        zoom.setOrientation(LinearLayout.VERTICAL);
        zoom.addView(zoomButton("+", "放大地圖", 1));
        zoom.addView(zoomButton("−", "縮小地圖", -1));
        LayoutParams zoomLayout = new LayoutParams(dp(44), LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        zoomLayout.setMargins(dp(8), dp(8), dp(8), 0);
        addView(zoom, zoomLayout);

        TextView attribution = new TextView(context);
        attribution.setText(R.string.map_attribution);
        attribution.setTextSize(11);
        attribution.setTextColor(Color.rgb(23, 49, 65));
        attribution.setBackgroundColor(0xF2FFFFFF);
        attribution.setPadding(dp(6), dp(3), dp(6), dp(3));
        attribution.setOnClickListener(view -> openAttribution());
        addView(attribution, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
    }

    public void select(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return;
        }
        map.selectedX = longitudeToX(longitude);
        map.selectedY = latitudeToY(latitude);
        map.centerX = wrap(map.selectedX);
        map.centerY = map.selectedY;
        map.invalidate();
    }

    public void setOnPointSelected(PointListener listener) {
        pointListener = listener;
    }

    private TextView zoomButton(String symbol, String label, int change) {
        TextView button = new TextView(getContext());
        button.setText(symbol);
        button.setContentDescription(label);
        button.setTextColor(Color.rgb(23, 49, 65));
        button.setTextSize(24);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0xF2FFFFFF);
        button.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
        button.setOnClickListener(view -> map.zoomBy(change, map.getWidth() / 2f, map.getHeight() / 2f));
        return button;
    }

    private void openAttribution() {
        try {
            getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ATTRIBUTION_URL)));
        } catch (ActivityNotFoundException ignored) {
            // The license remains visible on the map on devices without a browser.
        }
    }

    private static synchronized boolean installTileCache(Context context) {
        if (HttpResponseCache.getInstalled() != null) {
            return true;
        }
        try {
            HttpResponseCache.install(new File(context.getCacheDir(), "openstreetmap-tiles"), TILE_CACHE_SIZE);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int dp(float value) {
        return Math.round(density * value);
    }

    private static double longitudeToX(double longitude) {
        return (longitude + 180) / 360;
    }

    private static double latitudeToY(double latitude) {
        double radians = Math.toRadians(clamp(latitude, -MAX_MAP_LATITUDE, MAX_MAP_LATITUDE));
        return clamp((1 - Math.log(Math.tan(Math.PI / 4 + radians / 2)) / Math.PI) / 2, 0, 1);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double wrap(double value) {
        return value - Math.floor(value);
    }

    private final class MapSurface extends View {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint decoration = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tileBounds = new RectF();
        private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getAllocationByteCount();
            }
        };
        private final LruCache<String, Long> retryTimes = new LruCache<>(80);
        private final Map<String, TileRequest> pending = new HashMap<>();
        private final Set<String> visibleTiles = new HashSet<>();
        private final GestureDetector gestureDetector;
        private final ScaleGestureDetector scaleDetector;
        private final boolean tileCacheAvailable;

        private double selectedX = longitudeToX(121.5654);
        private double selectedY = latitudeToY(25.0330);
        private double centerX = selectedX;
        private double centerY = selectedY;
        private double zoom = 13;
        private boolean touching;
        private boolean multiplePointers;
        private MotionEvent tapEvent;

        MapSurface(Context context, boolean tileCacheAvailable) {
            super(context);
            this.tileCacheAvailable = tileCacheAvailable;
            setContentDescription("地圖，輕點選擇位置。也可以直接輸入經緯度。");
            setClickable(true);
            setFocusable(true);
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent down, MotionEvent current, float dx, float dy) {
                    if (!multiplePointers) {
                        double world = worldSize();
                        centerX = wrap(centerX + dx / world);
                        centerY = clamp(centerY + dy / world, 0, 1);
                        invalidate();
                    }
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent event) {
                    if (!multiplePointers) {
                        tapEvent = event;
                        performClick();
                        tapEvent = null;
                    }
                    return true;
                }
            });
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    double factor = detector.getScaleFactor();
                    if (factor > 0 && Double.isFinite(factor)) {
                        zoomBy(Math.log(factor) / LOG_2, detector.getFocusX(), detector.getFocusY());
                    }
                    return true;
                }
            });
            scaleDetector.setQuickScaleEnabled(false);
        }

        // GestureDetector routes actual taps to performClick().
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
                multiplePointers = false;
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                multiplePointers = true;
            }
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            choosePoint(tapEvent == null ? getWidth() / 2f : tapEvent.getX(),
                    tapEvent == null ? getHeight() / 2f : tapEvent.getY());
            return true;
        }

        private void choosePoint(float x, float y) {
            double world = worldSize();
            double latitudeY = centerY + (y - getHeight() / 2.0) / world;
            if (latitudeY < 0 || latitudeY > 1) {
                return;
            }
            selectedX = wrap(centerX + (x - getWidth() / 2.0) / world);
            selectedY = latitudeY;
            invalidate();
            if (pointListener != null) {
                double latitude = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * selectedY))));
                pointListener.onPointSelected(latitude, selectedX * 360 - 180);
            }
        }

        private double worldSize() {
            return dp(256) * Math.pow(2, zoom);
        }

        void zoomBy(double amount, float focusX, float focusY) {
            double worldBefore = worldSize();
            zoom = clamp(zoom + amount, 2, 19);
            double offsetScale = 1 / worldBefore - 1 / worldSize();
            centerX = wrap(centerX + (focusX - getWidth() / 2.0) * offsetScale);
            centerY = clamp(centerY + (focusY - getHeight() / 2.0) * offsetScale, 0, 1);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xFFDBE4E8);
            int level = (int) Math.floor(zoom);
            int tileCount = 1 << level;
            double world = worldSize();
            double tileSize = world / tileCount;
            double left = centerX * world - getWidth() / 2.0;
            double top = centerY * world - getHeight() / 2.0;
            int firstX = (int) Math.floor(left / tileSize);
            int lastX = (int) Math.ceil((left + getWidth()) / tileSize) - 1;
            int firstY = Math.max(0, (int) Math.floor(top / tileSize));
            int lastY = Math.min(tileCount - 1, (int) Math.ceil((top + getHeight()) / tileSize) - 1);
            visibleTiles.clear();
            boolean hasMap = false;
            boolean unavailable = !tileCacheAvailable;
            long now = SystemClock.elapsedRealtime();
            for (int y = firstY; y <= lastY; y++) {
                for (int x = firstX; x <= lastX; x++) {
                    String key = level + "/" + Math.floorMod(x, tileCount) + "/" + y + ".png";
                    visibleTiles.add(key);
                    Bitmap tile = bitmaps.get(key);
                    if (tile != null) {
                        float tileLeft = (float) (x * tileSize - left);
                        float tileTop = (float) (y * tileSize - top);
                        tileBounds.set(tileLeft, tileTop, tileLeft + (float) tileSize, tileTop + (float) tileSize);
                        canvas.drawBitmap(tile, null, tileBounds, bitmapPaint);
                        hasMap = true;
                    } else {
                        Long retryTime = retryTimes.get(key);
                        if (retryTime != null && now < retryTime) {
                            unavailable = true;
                        } else if (tileCacheAvailable && !touching && isAttachedToWindow()) {
                            requestTile(key);
                        }
                    }
                }
            }
            cancelUnusedRequests(visibleTiles);
            if (!hasMap) {
                drawMapStatus(canvas, unavailable ? "無法載入地圖，可直接輸入經緯度。" : "地圖載入中…");
            }
            drawPin(canvas, world);
        }

        private void drawPin(Canvas canvas, double world) {
            double dx = wrap(selectedX - centerX + 0.5) - 0.5;
            float x = (float) (getWidth() / 2.0 + dx * world);
            float y = (float) (getHeight() / 2.0 + (selectedY - centerY) * world);
            decoration.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(10), decoration);
            decoration.setColor(0xFF075985);
            canvas.drawCircle(x, y, dp(7), decoration);
        }

        private void drawMapStatus(Canvas canvas, String label) {
            decoration.setColor(0xFF345267);
            decoration.setTextSize(dp(14));
            decoration.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, getWidth() / 2f, dp(26), decoration);
        }

        private void requestTile(String key) {
            if (pending.containsKey(key)) {
                return;
            }
            TileRequest request = new TileRequest();
            request.task = TILE_EXECUTOR.submit(() -> {
                Bitmap bitmap = loadTile(key);
                if (!Thread.currentThread().isInterrupted()) {
                    mainHandler.post(() -> finishTile(key, request, bitmap));
                }
            });
            pending.put(key, request);
        }

        private void finishTile(String key, TileRequest request, Bitmap bitmap) {
            if (pending.get(key) != request) {
                return;
            }
            pending.remove(key);
            if (bitmap == null) {
                retryTimes.put(key, SystemClock.elapsedRealtime() + 60_000);
            } else {
                bitmaps.put(key, bitmap);
                retryTimes.remove(key);
            }
            invalidate();
        }

        private void cancelUnusedRequests(Set<String> visible) {
            Iterator<Map.Entry<String, TileRequest>> requests = pending.entrySet().iterator();
            while (requests.hasNext()) {
                Map.Entry<String, TileRequest> request = requests.next();
                if (!visible.contains(request.getKey())) {
                    request.getValue().task.cancel(true);
                    requests.remove();
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelUnusedRequests(Collections.emptySet());
            super.onDetachedFromWindow();
        }
    }

    private static Bitmap loadTile(String key) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(TILE_URL + key).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setUseCaches(true);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || !"image/png".equals(connection.getContentType())) {
                return null;
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8 * 1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted() || output.size() + count > MAX_TILE_BYTES) {
                        return null;
                    }
                    output.write(chunk, 0, count);
                }
                byte[] png = output.toByteArray();
                return BitmapFactory.decodeByteArray(png, 0, png.length);
            }
        } catch (IOException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
