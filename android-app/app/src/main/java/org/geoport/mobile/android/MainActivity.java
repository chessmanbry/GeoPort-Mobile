package org.geoport.mobile.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(22, 45, 40);
    private static final int MUTED = Color.rgb(78, 101, 93);
    private static final int ACCENT = Color.rgb(8, 121, 106);
    private static final int PAPER = Color.rgb(245, 247, 244);
    private static final int SOFT = Color.rgb(228, 238, 233);
    private static final int MAX_FAVORITES = 30;
    private static final double TAIPEI_LAT = 25.033964;
    private static final double TAIPEI_LON = 121.564468;
    private static final int NOTIFICATION_PERMISSION = 1;

    private record Point(double latitude, double longitude) {
        String coordinates() {
            return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
        }
    }

    private record Favorite(String name, Point point) {}

    private final ExecutorService searches = Executors.newFixedThreadPool(2);
    private final Runnable stateListener = this::renderState;

    private SharedPreferences preferences;
    private EditText searchInput;
    private EditText latitudeInput;
    private EditText longitudeInput;
    private TextView searchStatus;
    private TextView simulationStatus;
    private MapPickerView map;
    private Button startButton;
    private Button stopButton;
    private int searchGeneration;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("places", MODE_PRIVATE);
        setContentView(buildScreen());
        Point point = new Point(
                Double.longBitsToDouble(preferences.getLong("latitude", Double.doubleToLongBits(TAIPEI_LAT))),
                Double.longBitsToDouble(preferences.getLong("longitude", Double.doubleToLongBits(TAIPEI_LON))));
        if (savedInstanceState != null) {
            point = new Point(savedInstanceState.getDouble("latitude", point.latitude()),
                    savedInstanceState.getDouble("longitude", point.longitude()));
        }
        selectPoint(point, true);
        renderState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SimulationService.addStateListener(stateListener);
        renderState();
    }

    @Override
    protected void onStop() {
        SimulationService.removeStateListener(stateListener);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Point point = readPoint();
        if (point != null) {
            outState.putDouble("latitude", point.latitude());
            outState.putDouble("longitude", point.longitude());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        searchGeneration++;
        searches.shutdownNow();
        super.onDestroy();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            }
            return insets;
        });

        LinearLayout page = column();
        page.setPadding(dp(18), dp(14), dp(18), dp(22));
        page.setFocusableInTouchMode(true);
        scroll.addView(page);

        TextView heading = label("GeoPort Mobile", 27, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, heading, 0);

        simulationStatus = label("", 15, ACCENT);
        simulationStatus.setPadding(dp(12), dp(9), dp(12), dp(9));
        simulationStatus.setBackground(surface(SOFT, 12));
        simulationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, simulationStatus, 8);

        LinearLayout searchRow = row();
        searchInput = field("搜尋城市、地址或地標", InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setSingleLine(true);
        searchInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            searchPlaces();
            return true;
        });
        weighted(searchRow, searchInput, 0);
        searchRow.addView(button("搜尋", false, this::searchPlaces));
        add(page, searchRow, 12);

        searchStatus = label("", 13, MUTED);
        searchStatus.setVisibility(View.GONE);
        searchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, searchStatus, 4);

        map = new MapPickerView(this);
        map.setOnPointSelected((latitude, longitude) -> selectPoint(new Point(latitude, longitude), false));
        LinearLayout.LayoutParams mapLayout = new LinearLayout.LayoutParams(-1, dp(270));
        mapLayout.topMargin = dp(8);
        page.addView(map, mapLayout);

        LinearLayout coordinates = row();
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED;
        latitudeInput = field("緯度", decimal);
        longitudeInput = field("經度", decimal);
        weighted(coordinates, coordinateField("緯度", latitudeInput), 8);
        weighted(coordinates, coordinateField("經度", longitudeInput), 0);
        add(page, coordinates, 10);

        LinearLayout actions = row();
        startButton = button("開始模擬", true, this::startSimulation);
        stopButton = button("停止並恢復", false, this::stopSimulation);
        weighted(actions, startButton, 8);
        weighted(actions, stopButton, 0);
        add(page, actions, 10);

        LinearLayout saved = row();
        weighted(saved, button("收藏目前位置", false, this::saveFavorite), 8);
        weighted(saved, button("開啟收藏", false, this::showFavorites), 0);
        add(page, saved, 4);

        Button settings = button("開啟開發人員選項", false, this::openDeveloperSettings);
        add(page, settings, 8);
        add(page, label("首次使用請把 GeoPort Mobile 設為模擬位置應用程式。", 13, MUTED), 4);
        return scroll;
    }

    private void renderState() {
        SimulationService.State state = SimulationService.getState(this);
        simulationStatus.setText(state.message());
        startButton.setText(state.active() ? "更新模擬位置" : "開始模擬");
        startButton.setEnabled(!state.needsCleanup());
        stopButton.setEnabled(state.active() || state.needsCleanup());
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.45f);
        stopButton.setAlpha(stopButton.isEnabled() ? 1f : 0.45f);
    }

    private void startSimulation() {
        Point point = validatedPoint();
        if (point == null) {
            return;
        }
        hideKeyboard();
        selectPoint(point, true);
        try {
            SimulationService.apply(this, point.latitude(), point.longitude());
        } catch (IllegalStateException | SecurityException failure) {
            simulationStatus.setText(R.string.simulation_start_error);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("asked_notifications", false)) {
            preferences.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void stopSimulation() {
        try {
            SimulationService.stop(this);
        } catch (IllegalStateException | SecurityException failure) {
            simulationStatus.setText(R.string.simulation_stop_error);
        }
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (android.content.ActivityNotFoundException failure) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void searchPlaces() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            searchInput.setError("請輸入地點");
            return;
        }
        hideKeyboard();
        int generation = ++searchGeneration;
        if (!Geocoder.isPresent()) {
            showSearchStatus("這部裝置沒有地點搜尋服務。可在地圖選點或輸入經緯度。");
            return;
        }
        showSearchStatus("搜尋中…");
        searches.execute(() -> {
            try {
                List<Address> results = geocode(query);
                runOnUiThread(() -> {
                    if (generation == searchGeneration && !isFinishing()) {
                        showResults(results);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (generation == searchGeneration && !isFinishing()) {
                        showSearchStatus("暫時無法搜尋。請檢查網路，或直接輸入經緯度。");
                    }
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private List<Address> geocode(String query) throws IOException {
        return new Geocoder(this, Locale.getDefault()).getFromLocationName(query, 8);
    }

    private void showResults(List<Address> results) {
        List<Point> points = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (results != null) {
            for (Address address : results) {
                if (address.hasLatitude() && address.hasLongitude()
                        && isValid(address.getLatitude(), address.getLongitude())) {
                    Point point = new Point(address.getLatitude(), address.getLongitude());
                    String name = address.getAddressLine(0);
                    if (name == null || name.trim().isEmpty()) {
                        name = address.getFeatureName();
                    }
                    points.add(point);
                    names.add(name == null || name.trim().isEmpty()
                            ? point.coordinates() : name + "\n" + point.coordinates());
                }
            }
        }
        if (points.isEmpty()) {
            showSearchStatus("找不到這個地點。試試其他名稱或直接輸入經緯度。");
            return;
        }
        searchStatus.setVisibility(View.GONE);
        new AlertDialog.Builder(this)
                .setTitle("選擇地點")
                .setItems(names.toArray(new String[0]), (dialog, index) -> selectPoint(points.get(index), true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSearchStatus(String message) {
        searchStatus.setText(message);
        searchStatus.setVisibility(View.VISIBLE);
    }

    private void saveFavorite() {
        Point point = validatedPoint();
        if (point == null) {
            return;
        }
        hideKeyboard();
        selectPoint(point, true);
        EditText name = field("收藏名稱", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout container = column();
        container.setPadding(dp(20), dp(4), dp(20), 0);
        container.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("收藏目前位置")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", (dialog, which) -> {
                    String title = name.getText().toString().trim();
                    List<Favorite> favorites = readFavorites();
                    favorites.add(0, new Favorite(title.isEmpty() ? point.coordinates() : title, point));
                    if (favorites.size() > MAX_FAVORITES) {
                        favorites.remove(favorites.size() - 1);
                    }
                    writeFavorites(favorites);
                })
                .show();
    }

    private void showFavorites() {
        List<Favorite> favorites = readFavorites();
        if (favorites.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("收藏")
                    .setMessage("目前沒有收藏。選好位置後可按「收藏目前位置」。")
                    .setPositiveButton("關閉", null)
                    .show();
            return;
        }
        String[] names = favorites.stream().map(Favorite::name).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle("收藏")
                .setItems(names, (dialog, index) -> {
                    Favorite favorite = favorites.get(index);
                    new AlertDialog.Builder(this)
                            .setTitle(favorite.name())
                            .setMessage(favorite.point().coordinates())
                            .setPositiveButton("選擇位置", (info, action) -> selectPoint(favorite.point(), true))
                            .setNeutralButton("移除收藏", (info, action) -> {
                                favorites.remove(index);
                                writeFavorites(favorites);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("關閉", null)
                .show();
    }

    private List<Favorite> readFavorites() {
        List<Favorite> favorites = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("favorites", "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                double latitude = item.optDouble("latitude");
                double longitude = item.optDouble("longitude");
                if (isValid(latitude, longitude)) {
                    favorites.add(new Favorite(item.optString("name"), new Point(latitude, longitude)));
                }
            }
        } catch (JSONException ignored) {
            // The next save replaces invalid stored data.
        }
        return favorites;
    }

    private void writeFavorites(List<Favorite> favorites) {
        JSONArray array = new JSONArray();
        for (Favorite favorite : favorites) {
            try {
                array.put(new JSONObject()
                        .put("name", favorite.name())
                        .put("latitude", favorite.point().latitude())
                        .put("longitude", favorite.point().longitude()));
            } catch (JSONException failure) {
                throw new IllegalStateException("Could not save a validated point", failure);
            }
        }
        preferences.edit().putString("favorites", array.toString()).apply();
    }

    private Point validatedPoint() {
        Point point = readPoint();
        if (point == null) {
            longitudeInput.setError("緯度須在 −90 至 90，經度須在 −180 至 180。");
        }
        return point;
    }

    private Point readPoint() {
        try {
            double latitude = parseCoordinate(latitudeInput);
            double longitude = parseCoordinate(longitudeInput);
            return isValid(latitude, longitude) ? new Point(latitude, longitude) : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static double parseCoordinate(EditText field) {
        return Double.parseDouble(field.getText().toString().trim()
                .replace('−', '-').replace(',', '.'));
    }

    private static boolean isValid(double latitude, double longitude) {
        return Double.isFinite(latitude) && Math.abs(latitude) <= 90
                && Double.isFinite(longitude) && Math.abs(longitude) <= 180;
    }

    private void selectPoint(Point point, boolean recenterMap) {
        latitudeInput.setText(String.format(Locale.ROOT, "%.6f", point.latitude()));
        longitudeInput.setText(String.format(Locale.ROOT, "%.6f", point.longitude()));
        longitudeInput.setError(null);
        if (recenterMap) {
            map.select(point.latitude(), point.longitude());
        }
        preferences.edit().putLong("latitude", Double.doubleToLongBits(point.latitude()))
                .putLong("longitude", Double.doubleToLongBits(point.longitude())).apply();
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
        }
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText field(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(15);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setSingleLine(true);
        edit.setPadding(dp(10), 0, dp(8), 0);
        edit.setMinimumHeight(dp(46));
        edit.setBackground(surface(Color.WHITE, 10));
        return edit;
    }

    private LinearLayout coordinateField(String title, EditText field) {
        LinearLayout container = column();
        TextView label = label(title, 13, MUTED);
        label.setLabelFor(field.getId() == View.NO_ID ? assignId(field) : field.getId());
        add(container, label, 0);
        add(container, field, 4);
        return container;
    }

    private int assignId(View view) {
        int id = View.generateViewId();
        view.setId(id);
        return id;
    }

    private Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : SOFT));
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(topMargin);
        parent.addView(child, layout);
    }

    private void weighted(LinearLayout parent, View child, int rightMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1f);
        layout.setMarginEnd(dp(rightMargin));
        parent.addView(child, layout);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
