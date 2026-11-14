package org.geoport.mobile.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class SimulationService extends Service {
    private static final String APPLY = "org.geoport.mobile.android.APPLY_LOCATION";
    private static final String STOP = "org.geoport.mobile.android.STOP_LOCATION";
    private static final String CHANNEL = "simulation";
    private static final int NOTIFICATION_ID = 1;
    private static final long REFRESH_MS = 1_000;

    record State(boolean active, boolean needsCleanup, String message) {}
    private static State state;
    // Service and Activity share a process and deliver state on its main thread.
    private static final Set<Runnable> stateListeners = new LinkedHashSet<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLocation;
    private MockLocationEngine engine;
    private boolean sessionTouched;
    private boolean foreground;
    private double latitude;
    private double longitude;

    static State getState(Context context) {
        if (state != null) {
            return state;
        }
        boolean pending = MockLocationEngine.hasPendingCleanup(context);
        String message = pending ? "上次模擬未正常結束，請先按「停止並恢復」。" : "尚未開始模擬";
        return new State(false, pending, message);
    }

    static void apply(Context context, double latitude, double longitude) {
        Intent intent = new Intent(context, SimulationService.class)
                .setAction(APPLY)
                .putExtra("latitude", latitude)
                .putExtra("longitude", longitude);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.startForegroundService(stopIntent(context));
    }

    static void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    static void removeStateListener(Runnable listener) {
        stateListeners.remove(listener);
    }

    private static Intent stopIntent(Context context) {
        return new Intent(context, SimulationService.class).setAction(STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new MockLocationEngine(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "定位模擬",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification("正在處理模擬定位…"));
            foreground = true;
        }

        switch (intent.getAction() == null ? "" : intent.getAction()) {
            case APPLY -> {
                latitude = intent.getDoubleExtra("latitude", Double.NaN);
                longitude = intent.getDoubleExtra("longitude", Double.NaN);
                refreshLocation();
            }
            case STOP -> clearLocation();
            default -> finishService();
        }
        return START_NOT_STICKY;
    }

    private void refreshLocation() {
        handler.removeCallbacks(refresh);
        boolean wasPending = engine.hasPendingCleanup();
        try {
            engine.setLocation(latitude, longitude);
        } catch (RuntimeException failure) {
            boolean pending = engine.hasPendingCleanup();
            sessionTouched = pending && (sessionTouched || !wasPending);
            publish(false, pending, errorMessage(failure, pending));
            if (pending) {
                updateNotification("請停止模擬以恢復定位");
            } else {
                finishService();
            }
            return;
        }
        sessionTouched = true;
        String coordinates = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
        String message = "模擬中  " + coordinates;
        if (!getState(this).message().equals(message)) {
            publish(true, false, message);
            updateNotification(coordinates);
        }
        handler.postDelayed(refresh, REFRESH_MS);
    }

    private void clearLocation() {
        handler.removeCallbacks(refresh);
        try {
            engine.stop();
            sessionTouched = false;
            publish(false, false, "已停止模擬定位");
            finishService();
        } catch (RuntimeException failure) {
            boolean pending = engine.hasPendingCleanup();
            sessionTouched = pending;
            publish(false, pending, errorMessage(failure, pending));
            if (pending) {
                updateNotification("恢復定位失敗，請檢查設定後再試");
            } else {
                finishService();
            }
        }
    }

    private String errorMessage(RuntimeException failure, boolean pending) {
        if (failure instanceof SecurityException) {
            return pending
                    ? "請重新把 GeoPort Mobile 選為模擬位置應用程式，再按停止。"
                    : "請在開發人員選項中選擇 GeoPort Mobile 為模擬位置應用程式。";
        }
        if (pending) {
            return "定位模擬需要清除，請按「停止並恢復」。";
        }
        return "無法設定模擬定位，請確認系統定位已開啟後重試。";
    }

    private void publish(boolean active, boolean needsCleanup, String message) {
        state = new State(active, needsCleanup, message);
        for (Runnable listener : stateListeners) {
            listener.run();
        }
    }

    private Notification notification(String message) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent, flags);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent(this), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_location_status)
                .setContentTitle("GeoPort Mobile · 定位模擬")
                .setContentText(message)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_location_status),
                        "停止並恢復", stop).build())
                .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    private void finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresh);
        if (sessionTouched && engine.hasPendingCleanup()) {
            try {
                engine.stop();
                publish(false, false, "已停止模擬定位");
            } catch (RuntimeException failure) {
                publish(false, engine.hasPendingCleanup(), errorMessage(failure, true));
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
