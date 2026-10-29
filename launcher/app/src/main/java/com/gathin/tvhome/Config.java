package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * The few switches in the Settings menu (athkar, prayer times, the analog clock) are saved on the TV and win over both.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final int gridColumns;
    final int rowsBelowDock;
    /** How opaque the dock band's glass is, 0 to 100 (config.xml, dock_opacity); lower shows more of the photo. */
    final int dockOpacity;
    final boolean shamsiDate;
    final boolean dreamAthkar;
    /** Today's prayer times under the clock on Home, and on the screensaver; at the weather coordinates. */
    final boolean homePrayer;
    final boolean dreamPrayer;
    /** An analog clock on the screensaver instead of the digital one. */
    final boolean dreamAnalog;
    /** Aladhan calculation method (21 = Morocco, 3 = Muslim World League, ...). */
    final int prayerMethod;
    final boolean dreamPhotos;
    final boolean nowPlaying;
    final boolean homePhoto;
    final boolean moduleCards;
    final String vpnApp;
    /** Open-Meteo location for the weather line; an empty latitude hides it. */
    final String weatherLatitude;
    final String weatherLongitude;
    final String weatherPlace;
    final boolean showHomeSettingsShortcut;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int gridColumns, int rowsBelowDock, int dockOpacity,
                   boolean shamsiDate, boolean dreamAthkar, boolean homePrayer, boolean dreamPrayer, boolean dreamAnalog, int prayerMethod, boolean dreamPhotos, boolean nowPlaying, boolean homePhoto, boolean moduleCards, String vpnApp,
                   String weatherLatitude, String weatherLongitude, String weatherPlace, boolean showHomeSettingsShortcut, int backgroundColor,
                   File wallpaper, boolean overlayApplied) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.gridColumns = gridColumns;
        this.rowsBelowDock = rowsBelowDock;
        this.dockOpacity = dockOpacity;
        this.shamsiDate = shamsiDate;
        this.dreamAthkar = dreamAthkar;
        this.homePrayer = homePrayer;
        this.dreamPrayer = dreamPrayer;
        this.dreamAnalog = dreamAnalog;
        this.prayerMethod = prayerMethod;
        this.dreamPhotos = dreamPhotos;
        this.nowPlaying = nowPlaying;
        this.homePhoto = homePhoto;
        this.moduleCards = moduleCards;
        this.vpnApp = vpnApp;
        this.weatherLatitude = weatherLatitude;
        this.weatherLongitude = weatherLongitude;
        this.weatherPlace = weatherPlace;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int gridColumns = res.getInteger(R.integer.grid_columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        int dockOpacity = res.getInteger(R.integer.dock_opacity);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean athkar = res.getBoolean(R.bool.dream_athkar);
        boolean homePrayer = res.getBoolean(R.bool.home_prayer);
        boolean dreamPrayer = res.getBoolean(R.bool.dream_prayer);
        boolean dreamAnalog = res.getBoolean(R.bool.dream_analog);
        int prayerMethod = res.getInteger(R.integer.prayer_method);
        boolean photos = res.getBoolean(R.bool.dream_photos);
        boolean playing = res.getBoolean(R.bool.now_playing);
        boolean homePhoto = res.getBoolean(R.bool.home_photo);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        String vpn = res.getString(R.string.vpn_app).trim();
        String weatherLat = res.getString(R.string.weather_latitude).trim();
        String weatherLon = res.getString(R.string.weather_longitude).trim();
        String weatherPlace = res.getString(R.string.weather_place).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0xFF000000;
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            // A theme that sets only columns gets an even grid, as before grid_columns existed.
            gridColumns = integer(json, "grid_columns", json.has("columns") ? columns : gridColumns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            dockOpacity = integer(json, "dock_opacity", dockOpacity, 0, 100);
            shamsi = bool(json, "shamsi_date", shamsi);
            athkar = bool(json, "dream_athkar", athkar);
            homePrayer = bool(json, "home_prayer", homePrayer);
            dreamPrayer = bool(json, "dream_prayer", dreamPrayer);
            dreamAnalog = bool(json, "dream_analog", dreamAnalog);
            prayerMethod = integer(json, "prayer_method", prayerMethod, 0, 99);
            photos = bool(json, "dream_photos", photos);
            playing = bool(json, "now_playing", playing);
            homePhoto = bool(json, "home_photo", homePhoto);
            moduleCards = bool(json, "module_cards", moduleCards);
            vpn = string(json, "vpn_app", vpn);
            weatherLat = string(json, "weather_latitude", weatherLat);
            weatherLon = string(json, "weather_longitude", weatherLon);
            weatherPlace = string(json, "weather_place", weatherPlace);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
        }
        // Switched on the TV itself (Settings menu): wins over config.xml and a theme.
        SharedPreferences toggles = toggles(ctx);
        athkar = toggles.getBoolean(DREAM_ATHKAR, athkar);
        homePrayer = toggles.getBoolean(HOME_PRAYER, homePrayer);
        dreamPrayer = toggles.getBoolean(DREAM_PRAYER, dreamPrayer);
        dreamAnalog = toggles.getBoolean(DREAM_ANALOG, dreamAnalog);
        int chosenColumns = toggles.getInt(GRID_COLUMNS, 0);
        if (chosenColumns >= 4 && chosenColumns <= 7) gridColumns = chosenColumns;

        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(1, gridColumns), Math.max(0, rowsBelow), Math.max(0, Math.min(100, dockOpacity)),
                shamsi, athkar, homePrayer, dreamPrayer, dreamAnalog, prayerMethod, photos, playing, homePhoto, moduleCards, vpn,
                weatherLat, weatherLon, weatherPlace, homeSettings, background,
                wallpaper, applied);
    }

    /** Keys of the switches in the Settings menu; the same names as in config.xml and config.json. */
    static final String DREAM_ATHKAR = "dream_athkar", HOME_PRAYER = "home_prayer", DREAM_PRAYER = "dream_prayer",
            DREAM_ANALOG = "dream_analog", GRID_COLUMNS = "grid_columns";

    private static SharedPreferences toggles(Context ctx) {
        return ctx.getSharedPreferences("toggles", Context.MODE_PRIVATE);
    }

    /** Saves a switch from the Settings menu; it is read on the next {@link #load}. */
    static void setToggle(Context ctx, String key, boolean on) {
        toggles(ctx).edit().putBoolean(key, on).apply();
    }

    /** Saves a number chosen in the Settings menu (apps per row); it is read on the next {@link #load}. */
    static void setChoice(Context ctx, String key, int value) {
        toggles(ctx).edit().putInt(key, value).commit();
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
