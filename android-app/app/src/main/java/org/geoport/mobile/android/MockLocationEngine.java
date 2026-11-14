package org.geoport.mobile.android;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.LinkedHashSet;
import java.util.Set;

final class MockLocationEngine {
    private static final String PREFERENCES = "mock_location_cleanup";
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final String NETWORK = LocationManager.NETWORK_PROVIDER;

    private final LocationManager locationManager;
    private final AppOpsManager appOps;
    private final Context context;
    private final String packageName;
    private final SharedPreferences preferences;
    private final Set<String> activeProviders = new LinkedHashSet<>();

    MockLocationEngine(Context context) {
        this.context = context;
        locationManager = context.getSystemService(LocationManager.class);
        appOps = context.getSystemService(AppOpsManager.class);
        packageName = context.getPackageName();
        preferences = preferences(context);
    }

    static boolean hasPendingCleanup(Context context) {
        return hasPendingCleanup(preferences(context));
    }

    boolean hasPendingCleanup() {
        return hasPendingCleanup(preferences);
    }

    void setLocation(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordinates are outside their valid range.");
        }
        if (activeProviders.isEmpty() && hasPendingCleanup()) {
            throw new IllegalStateException("Stop the previous simulation before starting another.");
        }

        try {
            requireMockAppSelection();
            requireLocationEnabled();
            if (activeProviders.isEmpty()) {
                boolean hasNetworkProvider = locationManager.getAllProviders().contains(NETWORK);
                addProvider(GPS);
                if (hasNetworkProvider) {
                    addProvider(NETWORK);
                }
            }

            for (String provider : activeProviders) {
                Location location = new Location(provider);
                location.setLatitude(latitude);
                location.setLongitude(longitude);
                location.setAccuracy(GPS.equals(provider) ? 5.0f : 30.0f);
                location.setTime(System.currentTimeMillis());
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                locationManager.setTestProviderLocation(provider, location);
            }
            requireMockAppSelection();
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void stop() {
        // A failed stop must require cleanup before another simulation can start.
        activeProviders.clear();
        if (!hasPendingCleanup()) {
            return;
        }
        requireMockAppSelection();

        RuntimeException failure = null;
        for (String provider : new String[] { NETWORK, GPS }) {
            if (preferences.getBoolean(provider, false)) {
                try {
                    removeProvider(provider);
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // These inlined values match Criteria's constants used by this overload before API 31.
    @SuppressLint("InlinedApi")
    private void addProvider(String provider) {
        // Record cleanup before calling the system so process death cannot lose it.
        if (!preferences.edit().putBoolean(provider, true).commit()) {
            preferences.edit().remove(provider).apply();
            throw new IllegalStateException("Unable to save Android mock location cleanup state.");
        }
        boolean gps = GPS.equals(provider);
        try {
            locationManager.addTestProvider(provider, !gps, gps, !gps, false, false, false, false,
                    gps ? ProviderProperties.POWER_USAGE_HIGH : ProviderProperties.POWER_USAGE_LOW,
                    gps ? ProviderProperties.ACCURACY_FINE : ProviderProperties.ACCURACY_COARSE);
        } catch (IllegalArgumentException | SecurityException registrationFailure) {
            // The system rejected registration. Avoid removing a pre-existing provider.
            try {
                clearProviderRecord(provider);
            } catch (RuntimeException bookkeepingFailure) {
                registrationFailure.addSuppressed(bookkeepingFailure);
            }
            throw registrationFailure;
        }
        locationManager.setTestProviderEnabled(provider, true);
        activeProviders.add(provider);
    }

    private void removeProvider(String provider) {
        try {
            locationManager.removeTestProvider(provider);
        } catch (IllegalArgumentException missingProvider) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                throw missingProvider;
            }
            // Older Android versions throw if the test provider was already removed.
        }
        // Some Android versions silently ignore calls after the app-op is revoked.
        requireMockAppSelection();
        clearProviderRecord(provider);
    }

    private void requireMockAppSelection() {
        if (locationManager == null || appOps == null) {
            throw new IllegalStateException("Android's location service is unavailable.");
        }
        if (appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException("Select GeoPort as the mock location app in Developer options.");
        }
    }

    @SuppressWarnings("deprecation")
    private void requireLocationEnabled() {
        boolean enabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            enabled = locationManager.isLocationEnabled();
        } else {
            enabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        }
        if (!enabled) {
            throw new IllegalStateException("Turn on Android location before simulating a location.");
        }
    }

    private void clearProviderRecord(String provider) {
        if (!preferences.edit().remove(provider).commit()) {
            throw new IllegalStateException("Unable to save Android mock location cleanup state.");
        }
    }

    private static boolean hasPendingCleanup(SharedPreferences preferences) {
        return preferences.getBoolean(GPS, false) || preferences.getBoolean(NETWORK, false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
