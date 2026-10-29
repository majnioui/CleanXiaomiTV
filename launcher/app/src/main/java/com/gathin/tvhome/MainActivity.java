package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    /** Tiles per grid row below the dock (res/values/config.xml, grid_columns). Set in onCreate. */
    static int GRID_COLUMNS = 5;
    private static final long MINUTE_MS = 60_000L;
    private static final long WEATHER_MS = 30 * MINUTE_MS;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private boolean heroShown;
    private TextView greeting;
    private TextView reorderHint;
    private View btnInputs;
    /** Null when now_playing is off. */
    private NowPlayingCard nowCard;
    /** The blurred picture behind the glass (photo of the day or a theme's wallpaper). */
    private final Backdrop backdrop = new Backdrop();
    private boolean homePhotoOn;
    /** The three-hour slot (yyyy-MM-dd/h) the home photo is for; null until one is shown. */
    private String photoSlot;
    /** Hours a home photo stays before the next one. */
    private static final int PHOTO_HOURS = 3;
    /** Null when now_playing is off. */
    private NowPlaying nowPlaying;
    private NowPlaying.Track track;
    private TextView btnWifi;
    private TextView shamsiDate;
    private TextView weather;
    private String weatherUrl;
    private String weatherPlace;
    /** When the weather was last fetched (elapsedRealtime), successfully or not. */
    private long weatherAt = -WEATHER_MS;
    private boolean weatherLoading;
    /** Prayer pill under the clock (home_prayer); switched from the Settings menu. */
    private boolean homePrayer;
    private String prayerLat, prayerLon;
    private int prayerMethod;
    private View prayerPill;
    private TextView prayerName, prayerTime, prayerLeft;
    private ImageView prayerIcon;
    /** The last times loaded; kept past midnight until the new day's arrive, a minute out at most. */
    private Prayer.Day prayerDay;
    /** When the times were last requested (elapsedRealtime). */
    private long prayerAt = -5 * MINUTE_MS;
    private boolean prayerLoading;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    /** Saved dock order wins over config.xml and a theme's config.json. */
    private List<String> dockApps = Collections.emptyList();
    /** Saved non-dock app order; the normal label order remains the fresh-install fallback. */
    private List<String> appOrder = Collections.emptyList();
    /** Non-modal, on-home-screen reorder mode entered by holding OK on a tile. */
    private boolean reorderMode;
    /** Edit-dock mode (Settings menu): choose a dock place, then the app to put there. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private int reorderPosition = -1;
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWeather();
            updatePrayer();
            if (homePhotoOn) loadHomePhoto();   // a new photo when the next slot starts
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        // A force stop (TV Manager's cleaner) or a reinstall turns the remote's key helpers off; put them back.
        if (getResources().getBoolean(R.bool.restore_key_services)) ServiceRepair.restore(this);
        COLUMNS = cfg.columns;
        GRID_COLUMNS = cfg.gridColumns;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        homePhotoOn = cfg.homePhoto && cfg.wallpaper == null;
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        reorderHint = findViewById(R.id.reorderHint);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        weather = findViewById(R.id.weather);
        weatherPlace = cfg.weatherPlace;
        if (!cfg.weatherLatitude.isEmpty() && !cfg.weatherLongitude.isEmpty()) {
            weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + cfg.weatherLatitude
                    + "&longitude=" + cfg.weatherLongitude + "&current=temperature_2m,weather_code,is_day&timezone=auto";
        }
        prayerPill = findViewById(R.id.prayerPill);
        prayerIcon = findViewById(R.id.prayerIcon);
        prayerName = findViewById(R.id.prayerName);
        prayerTime = findViewById(R.id.prayerTime);
        prayerLeft = findViewById(R.id.prayerLeft);
        prayerPill.setBackground(new GlassDrawable(prayerPill, backdrop, 19 * density, density));
        // Prayer times use the weather coordinates; without them there is nothing to show.
        if (!cfg.weatherLatitude.isEmpty() && !cfg.weatherLongitude.isEmpty()) {
            prayerLat = cfg.weatherLatitude;
            prayerLon = cfg.weatherLongitude;
        }
        prayerMethod = cfg.prayerMethod;
        homePrayer = cfg.homePrayer && prayerLat != null;
        dockApps = DockEditor.loadDock(this, cfg.dockApps, COLUMNS);
        appOrder = DockEditor.loadAppOrder(this, dockApps);

        grid = findViewById(R.id.grid);
        // Spans = lcm of both row widths, so the dock row and grid rows each split it evenly.
        int gcd = COLUMNS, rem = GRID_COLUMNS;
        while (rem != 0) { int t = gcd % rem; gcd = rem; rem = t; }
        final int spans = COLUMNS / gcd * GRID_COLUMNS;
        GridLayoutManager glm = new GridLayoutManager(this, spans) {
            /**
             * Keep the rows just off screen laid out (half a screen beyond each edge). The first grid row
             * sits below the screen's edge under the dock, so without this Down found no tile there and
             * fell back to scrolling first and focusing after, which made the highlight jump.
             */
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                extra[0] = extra[1] = getHeight() / 2;
            }

            /** Same amount as RecyclerView's own focus scroll, but with the launcher's slower glide. */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                int top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
                int childTop = child.getTop() + rect.top - child.getScrollY();
                int childBottom = childTop + rect.height();
                int offTop = Math.min(0, childTop - top), offBottom = Math.max(0, childBottom - bottom);
                int dy = offTop != 0 ? offTop : Math.min(childTop - top, offBottom);
                if (dy == 0) return false;
                if (immediate) parent.scrollBy(0, dy);
                else glide(dy);
                return true;
            }
        };
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return spans / (position < COLUMNS ? COLUMNS : GRID_COLUMNS);
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density, backdrop, cfg.dockOpacity));
        backdrop.register(grid);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, cfg, dockApps, appOrder);
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int dockH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int gridH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, GRID_COLUMNS, density)), density);
            int dockRow = dockH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = gridH * rowsBelowDock;
            // With no rows below, the next row stays out of sight until Down: the dock item ends in an empty
            // label strip (its name shows above the dock), so it reaches a little past the screen's edge.
            // With rows below, a little room under them.
            int spare = rowsBelowDock > 0 ? (int) (30 * density) : -(int) (10 * density);
            int want = Math.max(0, h - dockRow - gridRows - spare);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        // Liquid glass top buttons: the blurred photo behind them; solid white while focused.
        for (View pill : new View[] {btnInputs, btnWifi, btnSettings}) pill.setBackground(glassPill(pill));
        if (cfg.nowPlaying) {
            nowCard = new NowPlayingCard(this, backdrop, getResources().getFont(R.font.outfit),
                    new NowPlayingCard.Exits() {
                        @Override
                        public void up() {
                            btnSettings.requestFocus();
                        }

                        @Override
                        public void down() {
                            focusTile(lastFocused % COLUMNS);
                        }
                    });
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ((ViewGroup) findViewById(R.id.nowSlot)).addView(nowCard, nlp);
            nowPlaying = new NowPlaying(this, handler, this::showNowPlaying);
        }
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnSettings.setOnLongClickListener(v -> {
            if (!moduleCards) return false;   // off: a long press does nothing, as before the feature
            showModuleCards();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // The now playing card when it shows, else back to the dock tile Up came from, not
                // wherever the focus finder lands.
                if (cardShowing()) nowCard.defaultButton().requestFocus();
                else focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is the first visible pill (Wi-Fi).
                ViewGroup bar = findViewById(R.id.topButtons);
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View c = bar.getChildAt(i);
                    if (c.getVisibility() == View.VISIBLE) return c == v;
                }
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // The right end is the last visible pill (Settings).
                ViewGroup bar = findViewById(R.id.topButtons);
                for (int i = bar.getChildCount() - 1; i >= 0; i--) {
                    View c = bar.getChildAt(i);
                    if (c.getVisibility() == View.VISIBLE) return c == v;
                }
            }
            return false;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        // Registered for the activity's whole life, not only while it is resumed: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            backdrop.set(Backdrop.blur(bmp, dm.widthPixels, dm.heightPixels), dm.widthPixels, dm.heightPixels);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        tileRefresh.onStart();
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
    }

    @Override
    protected void onResume() {
        super.onResume();
        promptsPaused = false;
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false);
                break;
            default:
                // Back from a side panel (Wi-Fi, Inputs): Home never left the screen, nothing to redo.
                break;
        }
        updateGreeting();
        updateWifiLabel();
        updateWeather();
        updatePrayer();
        updateInputsButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (nowPlaying != null) nowPlaying.start();
        if (homePhotoOn) loadHomePhoto();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /**
     * @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS),
     *                without the entrance animation: on a return to the same tiles it would mark a change that
     *                didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        if (rebuild) {
            reloadTiles();
            if (animate) grid.scheduleLayoutAnimation();
        }

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        Set<String> hiddenCards = new HashSet<>();
        for (Cards.Card c : cards) if (c.state == Cards.State.HIDDEN) hiddenCards.add(c.pkg);
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), hiddenCards);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        endDockEdit();
        handler.removeCallbacks(minuteTick);
        if (nowPlaying != null) nowPlaying.stop();
        if (nowCard != null) nowCard.stop();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible, as the key helpers keep the process in
        // the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        tileRefresh.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (reorderMode) {
            finishReorder();
            return;
        }
        if (dockEditing) {
            // One step back: from choosing the app to choosing the place, and from there out of the mode.
            if (dockEditSlot >= 0) {
                int slot = dockEditSlot;
                dockEditSlot = -1;
                dimTiles();
                reorderHint.setText(R.string.dock_edit_place_hint);
                focusTile(slot);
            } else {
                endDockEdit();
            }
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = AppAdapter.FOCUS_SCALE;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // Only when the row is actually off: a few pixels' mismatch must not start a scroll on every move.
            if (Math.abs(dy) > (int) (8 * density)) glide(dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    /** An ease-out curve close to tvOS: quick to start, a long soft landing. */
    private static final android.view.animation.Interpolator GLIDE =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    /** Scrolls the grid by dy over 380 to 560 ms, longer for longer distances. */
    private void glide(int dy) {
        int ms = Math.min(560, 380 + Math.round(Math.abs(dy) / density * 0.4f));
        grid.smoothScrollBy(0, dy, GLIDE, ms);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - DockDecoration.PAD_Y * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            TextView caption = findViewById(R.id.photoCaption);
            if (caption.length() > 0) caption.animate().alpha(1f).setDuration(600).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) setAmbient(color);   // cross-fades, see setAmbient
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture; changing apps only uploads it once.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        int w = 480;
        int h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (backdrop.bitmap() != null) {
            // Over a photo: an aurora in the app's colours. Its own colour low on the left, a warmer
            // shift to the right and a cooler one across the top, each a soft blob, so the picture
            // takes on the app's tint instead of being washed over.
            aurora(canvas, paint, w * 0.22f, h * 0.78f, w * 0.60f, color, 0f, 0x38);
            aurora(canvas, paint, w * 0.80f, h * 0.62f, w * 0.52f, color, 28f, 0x2B);
            aurora(canvas, paint, w * 0.50f, h * 0.08f, w * 0.64f, color, -32f, 0x22);
        } else {
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bmp);
        drawable.setGravity(Gravity.FILL);
        // Cross-fade from the colours on screen to the new ones, instead of dipping and coming back.
        android.graphics.drawable.Drawable old = ambient.getBackground();
        if (old instanceof android.graphics.drawable.TransitionDrawable) {
            old = ((android.graphics.drawable.TransitionDrawable) old).getDrawable(1);
        }
        if (old != null && ambient.getAlpha() > 0f) {
            android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[] {old, drawable});
            fade.setCrossFadeEnabled(true);
            ambient.setBackground(fade);
            fade.startTransition(700);
        } else {
            ambient.setBackground(drawable);
        }
    }

    /** One aurora blob: the colour turned by hueShift degrees and made vivid, fading out from its centre. */
    private static void aurora(Canvas canvas, Paint paint, float x, float y, float r, int color, float hueShift, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f;
        hsv[1] = Math.min(0.7f, Math.max(0.45f, hsv[1]));
        hsv[2] = Math.max(0.75f, hsv[2]);
        int c = Color.HSVToColor(hsv) & 0x00FFFFFF;
        paint.setShader(new RadialGradient(x, y, r,
                new int[]{(alpha << 24) | c, ((alpha / 3) << 24) | c, c},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), paint);
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        // As slow as they come in (showHero): leaving the dock while the grid glides up must not blink
        // the whole screen's colour away.
        heroName.animate().alpha(0f).setDuration(260).setInterpolator(GLIDE).start();
        findViewById(R.id.photoCaption).animate().alpha(0f).setDuration(260).start();
        ambient.animate().alpha(0f).setDuration(600).setInterpolator(GLIDE).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        if (reorderMode) {
            int current = reorderPosition >= 0 ? reorderPosition : position;
            int moved = adapter.moveForReorder(current, keyCode);
            if (moved >= 0 && moved != current) {
                reorderPosition = moved;
                lastFocused = moved;
                lastFocusedKey = adapter.keyAt(moved);
                // Persist each move immediately. OK and Back are deliberately identical: both
                // simply leave move mode, without a second confirm/cancel decision.
                dockApps = adapter.dockApps();
                appOrder = adapter.appOrder();
                DockEditor.saveDock(this, dockApps);
                DockEditor.saveAppOrder(this, appOrder);
                // Let RecyclerView's move animator place the tile, then put focus back on the
                // moving card. The delayed refocus is important: during notifyItemMoved the old
                // holder can still report itself as focused for one layout pass.
                grid.postDelayed(() -> {
                    if (reorderMode && reorderPosition == moved) focusTile(moved);
                }, 220L);
            }
            return true;
        }
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        // The dock row has COLUMNS slots, every grid row below it GRID_COLUMNS.
        boolean dock = position < COLUMNS;
        int g = position - COLUMNS;                        // index within the grid, when not in the dock
        int col = dock ? position : g % GRID_COLUMNS;
        int width = dock ? COLUMNS : GRID_COLUMNS;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return col == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return col == width - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (dock && cardShowing()) {                   // dock row: the now playing card above it
                nowCard.defaultButton().requestFocus();
                return true;
            }
            if (dock) return false;                        // dock row: let focus move to the top bar
            if (g < GRID_COLUMNS) {
                // First grid row: the dock slot under the same horizontal spot.
                target = Math.min((int) ((col + 0.5f) / GRID_COLUMNS * COLUMNS), Math.min(COLUMNS, count) - 1);
            } else {
                target = position - GRID_COLUMNS;
            }
        } else if (dock) {
            if (count <= COLUMNS) return true;             // nothing below the dock
            target = Math.min(COLUMNS + (int) ((col + 0.5f) / COLUMNS * GRID_COLUMNS), count - 1);
        } else {
            int lastRowStart = COLUMNS + ((count - 1 - COLUMNS) / GRID_COLUMNS) * GRID_COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + GRID_COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        int candidate = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        if (!adapter.isFocusableAt(candidate)) {
            int right = candidate;
            while (right < adapter.getItemCount() && !adapter.isFocusableAt(right)) right++;
            int left = candidate - 1;
            while (left >= 0 && !adapter.isFocusableAt(left)) left--;
            candidate = right < adapter.getItemCount() ? right : left;
        }
        if (candidate < 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = candidate;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Settings stays a compact launcher menu so dock editing is available with only the remote. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_settings, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (240 * density));

        addSettingsRow(rows, popup, R.drawable.ic_edit_dock, R.string.edit_dock, 1);
        addSettingsRow(rows, popup, R.drawable.ic_edit_dock, R.string.dock_list, 4);
        addSettingsRow(rows, popup, R.drawable.ic_settings, R.string.system_settings, 2);
        if (moduleCards) addSettingsRow(rows, popup, R.drawable.ic_home_settings, R.string.module_cards, 3);
        // Layout (Config.setChoice): applied when the menu closes, by rebuilding Home.
        addSeparator(rows);
        Config cfg = Config.load(this);
        final int[] layout = {cfg.gridColumns};
        final int[] atOpen = layout.clone();
        addChoiceRow(rows, R.drawable.ic_home_settings, R.string.apps_per_row,
                () -> String.valueOf(layout[0]), () -> layout[0] = layout[0] >= 7 ? 4 : Math.max(4, layout[0] + 1));
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;   // replaces glassPopup's listener
            if (java.util.Arrays.equals(layout, atOpen)) return;
            Config.setChoice(this, Config.GRID_COLUMNS, layout[0]);
            recreate();
        });
        // Switches that stay on the TV (Config.setToggle); the screensaver reads them when it next starts.
        addSeparator(rows);
        if (prayerLat != null) {
            addToggleRow(rows, R.drawable.ic_prayer, R.string.toggle_home_prayer, Config.HOME_PRAYER, homePrayer, on -> {
                homePrayer = on;
                updatePrayer();
            });
            addToggleRow(rows, R.drawable.ic_prayer, R.string.toggle_dream_prayer, Config.DREAM_PRAYER, cfg.dreamPrayer, null);
        }
        addToggleRow(rows, R.drawable.ic_athkar, R.string.toggle_dream_athkar, Config.DREAM_ATHKAR, cfg.dreamAthkar, null);
        addToggleRow(rows, R.drawable.ic_clock, R.string.toggle_dream_analog, Config.DREAM_ANALOG, cfg.dreamAnalog, null);

        for (int i = 0; i < rows.getChildCount(); i++) {
            final int index = i;
            View row = rows.getChildAt(i);
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int step = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = index + step;
                    while (next >= 0 && next < rows.getChildCount() && !rows.getChildAt(next).isFocusable()) next += step;
                    if (next >= 0 && next < rows.getChildCount()) rows.getChildAt(next).requestFocus();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
        }
        // About six rows show at once; the rest scroll into view as focus moves down.
        int visible = getResources().getDimensionPixelSize(R.dimen.setting_row_height) * 6 + (int) (12 * density);
        View scroll = panel.findViewById(R.id.scroll);
        rows.measure(View.MeasureSpec.makeMeasureSpec((int) (240 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        if (rows.getMeasuredHeight() > visible) scroll.getLayoutParams().height = visible;
        dropIn(popup, panel, anchor, Gravity.END);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void addSettingsRow(LinearLayout rows, PopupWindow popup, int icon, int title, int action) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            if (action == 1) startDockEdit();
            else if (action == 4) showDockEditor();
            else if (action == 2) open(new Intent(Settings.ACTION_SETTINGS));
            else showModuleCards();
        });
        rows.addView(row);
    }

    /** A row that steps through a few values in place (OK for the next one); the menu stays open. */
    private void addChoiceRow(LinearLayout rows, int icon, int title, java.util.function.Supplier<String> value,
                              Runnable next) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView status = row.findViewById(R.id.status);
        status.setText(value.get());
        row.setOnClickListener(v -> {
            next.run();
            status.setText(value.get());
        });
        rows.addView(row);
    }

    /** A row that switches a setting On and Off in place; the menu stays open. */
    private void addToggleRow(LinearLayout rows, int icon, int title, String key, boolean initial,
                              java.util.function.Consumer<Boolean> changed) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        final boolean[] on = {initial};
        Runnable show = () -> {
            status.setText(on[0] ? R.string.toggle_on : R.string.toggle_off);
            dot.setVisibility(on[0] ? View.VISIBLE : View.GONE);
        };
        show.run();
        row.setOnClickListener(v -> {
            on[0] = !on[0];
            Config.setToggle(this, key, on[0]);
            show.run();
            if (changed != null) changed.accept(on[0]);
        });
        rows.addView(row);
    }

    private void showDockEditor() {
        createDockEditor().show();
    }

    private DockEditor createDockEditor() {
        final List<String> fallback = new ArrayList<>(dockApps);
        Runnable refreshDockAndApps = () -> {
            dockApps = DockEditor.loadDock(this, fallback, COLUMNS);
            appOrder = DockEditor.loadAppOrder(this, dockApps);
            adapter.setDockApps(dockApps);
            adapter.setAppOrder(appOrder);
            reloadTiles();
            restoreFocus();
        };
        return new DockEditor(this, dockApps, COLUMNS, () -> refreshDockAndApps.run(), () -> {
            appOrder = DockEditor.loadAppOrder(this, dockApps);
            adapter.setAppOrder(appOrder);
            reloadTiles();
            restoreFocus();
        });
    }

    @Override
    public void onTileLongPressed(int position) {
        if (dockEditing || reorderMode
                || (!adapter.isDockPosition(position) && !adapter.isReorderableAppAt(position))) return;
        reorderMode = true;
        reorderHint.setText(R.string.reorder_hint);
        reorderPosition = position;
        DefaultItemAnimator moveAnimator = new DefaultItemAnimator();
        moveAnimator.setMoveDuration(180L);
        grid.setItemAnimator(moveAnimator);
        reorderHint.setVisibility(View.VISIBLE);
        reorderHint.setAlpha(0f);
        reorderHint.setTranslationY(10 * density);
        reorderHint.animate().alpha(1f).translationY(0f).setDuration(180).start();
        View card = tileCardAt(position);
        if (card != null) {
            card.animate().scaleX(AppAdapter.FOCUS_SCALE * 1.06f)
                    .scaleY(AppAdapter.FOCUS_SCALE * 1.06f)
                    .setDuration(180).start();
        }
    }

    @Override
    public boolean onTileClicked(int position) {
        if (dockEditing) {
            onDockEditOk(position);
            return true;   // never opens the app while editing
        }
        if (!reorderMode) return false;
        finishReorder();
        return true;
    }

    // ---- edit dock -------------------------------------------------------------------------------

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reorderMode) finishReorder();
        if (!adapter.isDockPosition(0)) {
            showDockEditor();   // an empty dock: the list editor can add apps
            return;
        }
        dockEditing = true;
        dockEditSlot = -1;
        reorderHint.setText(R.string.dock_edit_place_hint);
        reorderHint.setVisibility(View.VISIBLE);
        reorderHint.setAlpha(0f);
        reorderHint.setTranslationY(10 * density);
        reorderHint.animate().alpha(1f).translationY(0f).setDuration(180).start();
        focusTile(0);
    }

    /**
     * First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves to the grid; then OK on
     * any tile puts that app in the place, and the app it replaces takes the chosen app's old spot.
     */
    private void onDockEditOk(int position) {
        if (dockEditSlot < 0) {
            if (!adapter.isDockPosition(position)) return;
            dockEditSlot = position;
            dimTiles();
            reorderHint.setText(R.string.dock_edit_app_hint);
            focusTile(adapter.getItemCount() > COLUMNS ? COLUMNS : position);
            return;
        }
        swapIntoDock(dockEditSlot, position);
    }

    private void swapIntoDock(int slot, int position) {
        String chosen = adapter.packageAt(position);
        List<String> dock = adapter.dockApps();
        if (chosen != null && slot < dock.size() && !chosen.equals(dock.get(slot))) {
            String replaced = dock.get(slot);
            List<String> order = adapter.appOrder();
            int inDock = dock.indexOf(chosen);
            if (inDock >= 0) {
                dock.set(inDock, replaced);   // both in the dock: they trade places
            } else {
                int at = order.indexOf(chosen);
                if (at >= 0) order.set(at, replaced);
                else order.add(replaced);
            }
            dock.set(slot, chosen);
            dockApps = dock;
            appOrder = order;
            DockEditor.saveDock(this, dockApps);
            DockEditor.saveAppOrder(this, appOrder);
            adapter.setDockApps(dockApps);
            adapter.setAppOrder(appOrder);
            reloadTiles();
        }
        endDockEdit();
        lastFocused = slot;
        lastFocusedKey = adapter.keyAt(slot);
        grid.post(() -> focusTile(slot));
    }

    private void endDockEdit() {
        if (!dockEditing) return;
        dockEditing = false;
        dockEditSlot = -1;
        dimTiles();
        reorderHint.animate().alpha(0f).translationY(10 * density).setDuration(150)
                .withEndAction(() -> reorderHint.setVisibility(View.GONE)).start();
    }

    private void dimTiles() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** Dim in edit-dock mode: the dock tiles other than the chosen place. */
    private float tileAlpha(int position) {
        return dockEditSlot >= 0 && position >= 0 && position < COLUMNS && position != dockEditSlot ? 0.45f : 1f;
    }

    private View tileCardAt(int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView.findViewById(R.id.card);
    }

    private void finishReorder() {
        if (!reorderMode) return;
        View selectedCard = tileCardAt(reorderPosition);
        if (selectedCard != null) {
            selectedCard.animate().scaleX(AppAdapter.FOCUS_SCALE)
                    .scaleY(AppAdapter.FOCUS_SCALE)
                    .setDuration(160).start();
        }
        reorderMode = false;
        reorderPosition = -1;
        grid.setItemAnimator(null);
        reorderHint.animate().alpha(0f).translationY(10 * density).setDuration(150)
                .withEndAction(() -> reorderHint.setVisibility(View.GONE)).start();
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    // ---- weather --------------------------------------------------------------------------------

    /** Refreshes the weather line at most every 30 minutes, off the main thread. */
    private void updateWeather() {
        if (weatherUrl == null || weatherLoading) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - weatherAt < WEATHER_MS) return;
        weatherAt = now;
        weatherLoading = true;
        new Thread(() -> {
            String text = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(weatherUrl).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    long temp = Math.round(cur.getDouble("temperature_2m"));
                    String icon = weatherIcon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                    text = icon + "  " + temp + "°" + (weatherPlace.isEmpty() ? "" : "  ·  " + weatherPlace);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                // Offline or the service is down: keep the last reading, retry in 5 minutes.
                android.util.Log.w("tvhome", "weather: " + e);
            }
            final String result = text;
            handler.post(() -> {
                weatherLoading = false;
                if (result == null) {
                    weatherAt -= WEATHER_MS - 5 * MINUTE_MS;
                    return;
                }
                weather.setText(result);
                weather.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    // ---- prayer times ---------------------------------------------------------------------------

    /** Gold for the moon and for a prayer that is due: warm against the photo, unlike the green of "connected". */
    static final int PRAYER_GOLD = 0xFFF2C879;

    /** Updates the pill every minute; today's times come from the saved file, or once a day from Aladhan. */
    private void updatePrayer() {
        if (!homePrayer) {
            prayerPill.setVisibility(View.GONE);
            return;
        }
        String key = Prayer.key(prayerLat, prayerLon, prayerMethod);
        if (prayerDay == null || !prayerDay.key.equals(key)) {
            Prayer.Day saved = Prayer.cached(this, key);
            if (saved != null) prayerDay = saved;
            else fetchPrayer();
        }
        if (prayerDay == null) return;
        Prayer.Next next = Prayer.next(prayerDay);
        boolean due = next.minutesLeft <= Prayer.NOW_MINUTES;   // the last quarter hour, and "now"
        prayerName.setText(Prayer.name(this, next.index));
        prayerName.setTextColor(due ? PRAYER_GOLD : 0xFFFFFFFF);
        prayerTime.setText(Prayer.time(this, prayerDay.minutes[next.index]));
        prayerLeft.setText(Prayer.countdown(this, next));
        prayerLeft.setTextColor(due ? PRAYER_GOLD : 0xB3FFFFFF);
        prayerIcon.setImageTintList(android.content.res.ColorStateList.valueOf(PRAYER_GOLD));
        if (prayerPill.getVisibility() != View.VISIBLE) {
            prayerPill.setAlpha(0f);
            prayerPill.setVisibility(View.VISIBLE);
            prayerPill.animate().alpha(1f).setDuration(400).start();
        }
    }

    /** At most every 5 minutes while the times are missing, off the main thread. */
    private void fetchPrayer() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (prayerLoading || now - prayerAt < 5 * MINUTE_MS) return;
        prayerAt = now;
        prayerLoading = true;
        final String lat = prayerLat, lon = prayerLon;
        final int method = prayerMethod;
        new Thread(() -> {
            Prayer.Day day = Prayer.fetch(getApplicationContext(), lat, lon, method);
            handler.post(() -> {
                prayerLoading = false;
                if (day == null) return;
                prayerDay = day;
                updatePrayer();
            });
        }).start();
    }

    /** WMO weather code (as used by Open-Meteo) to an emoji. Also used by ClockDream. */
    static String weatherIcon(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";                  // clear: sun / moon
        if (code <= 2) return day ? "\u26C5" : "\u2601\uFE0F";                          // partly cloudy
        if (code == 3) return "\u2601\uFE0F";                                           // overcast
        // Fog as a cloud: older Android emoji fonts draw the fog emoji as a plain grey box.
        if (code == 45 || code == 48) return "\u2601\uFE0F";                           // fog
        if (code >= 51 && code <= 57) return "\uD83C\uDF26\uFE0F";                      // drizzle
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "\u2744\uFE0F"; // snow
        if (code >= 95) return "\u26C8\uFE0F";                                          // thunderstorm
        return "\uD83C\uDF27\uFE0F";                                                    // rain and showers
    }

    // ---- now playing ----------------------------------------------------------------------------

    private void showNowPlaying(NowPlaying.Track t) {
        track = t;
        nowCard.bind(t);
        updateCard();
    }

    private boolean cardShowing() {
        return nowCard != null && nowCard.getVisibility() == View.VISIBLE;
    }

    /** The pill shows while something plays or is paused. */
    private void updateCard() {
        if (nowCard == null) return;
        boolean show = nowCard.hasTrack();
        if (show && nowCard.getVisibility() != View.VISIBLE) {
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).setDuration(300).start();
        } else if (!show && nowCard.getVisibility() == View.VISIBLE) {
            if (nowCard.hasFocus()) focusTile(lastFocused % COLUMNS);
            nowCard.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (!nowCard.hasTrack()) nowCard.setVisibility(View.GONE);
            }).start();
        }
    }

    // ---- photo of the day -----------------------------------------------------------------------

    /** Today's photo behind the home screen, once a day; the glass follows it. */
    private void loadHomePhoto() {
        Calendar now = Calendar.getInstance();
        String slot = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(now.getTime())
                + '/' + now.get(Calendar.HOUR_OF_DAY) / PHOTO_HOURS;
        if (slot.equals(photoSlot)) return;
        photoSlot = slot;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = dm.widthPixels, h = dm.heightPixels;
        new Thread(() -> {
            java.util.List<DailyPhoto.Photo> list = DailyPhoto.list(this);
            // A random photo from the list for each slot, the same one all through the slot. If it can't
            // be had (offline and not cached), the next ones in the list, up to three.
            int start = list.isEmpty() ? 0 : new java.util.Random(slot.hashCode()).nextInt(list.size());
            DailyPhoto.Photo chosen = null;
            Bitmap loaded = null;
            for (int i = 0; i < Math.min(3, list.size()) && loaded == null; i++) {
                chosen = list.get((start + i) % list.size());
                loaded = DailyPhoto.load(this, chosen, w);
            }
            final Bitmap bmp = loaded;
            final DailyPhoto.Photo p = chosen;
            if (bmp != null) bakeScrim(bmp);
            Bitmap blurred = bmp == null ? null : Backdrop.blur(bmp, w, h);
            handler.post(() -> {
                if (bmp == null) {
                    photoSlot = null;   // offline with nothing cached: try again in a minute
                    return;
                }
                ImageView photo = findViewById(R.id.homePhoto);
                Drawable old = photo.getDrawable();
                if (old != null && photo.getAlpha() > 0f) {
                    // Already showing one: cross-fade to the new photo, then drop the old bitmap.
                    android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                            new Drawable[] {old, new BitmapDrawable(getResources(), bmp)});
                    fade.setCrossFadeEnabled(true);
                    photo.setImageDrawable(fade);
                    fade.startTransition(1500);
                    photo.postDelayed(() -> {
                        if (photo.getDrawable() == fade) photo.setImageBitmap(bmp);
                    }, 1600);
                } else {
                    photo.setImageBitmap(bmp);
                }
                photo.animate().alpha(1f).setDuration(900).withEndAction(this::dropHiddenLayers).start();
                backdrop.set(blurred, w, h);
                TextView caption = findViewById(R.id.photoCaption);
                caption.setText(p.credit.isEmpty() ? p.title : p.title + "  ·  " + p.credit);
                if (heroShown) caption.animate().alpha(1f).setDuration(900).start();
            });
        }).start();
    }

    /**
     * Darkens the top and bottom of the photo so the text reads, drawn into the bitmap once instead of
     * as a full-screen layer the TV's GPU would blend on every frame.
     */
    private static void bakeScrim(Bitmap bmp) {
        if (!bmp.isMutable()) return;
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, bmp.getHeight(),
                new int[] {0x99000000, 0x14000000, 0x29000000, 0xD9000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), p);
    }

    /**
     * Once the photo covers the screen, nothing under it is ever seen: drop the window and root
     * backgrounds, two full-screen layers the GPU would otherwise fill on every frame.
     */
    private void dropHiddenLayers() {
        getWindow().setBackgroundDrawable(null);
        root.setBackground(null);
        // The ambient glow was drawn for the plain background; repaint it as an aurora for the photo.
        int c = ambientColor;
        ambientColor = 0;
        if (c != 0) setAmbient(c);
    }

    /** A top button: liquid glass over the photo, solid white while focused. */
    private Drawable glassPill(View pill) {
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(0xF5FFFFFF);
        on.setCornerRadius(21 * density);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new GlassDrawable(pill, backdrop, 21 * density, density));
        return bg;
    }

    /** Album art scaled to a square with rounded corners. Also used by ClockDream. */
    static Bitmap roundedArt(Bitmap art, int size) {
        return roundedArt(art, size, 0.18f);
    }

    /** The cover cropped square with corners of radius × size; 0.5 gives a circle. */
    static Bitmap roundedArt(Bitmap art, int size, float radius) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float r = size * radius;
        c.drawRoundRect(0, 0, size, size, r, r, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        int side = Math.min(art.getWidth(), art.getHeight());
        Rect src = new Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2,
                (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
        c.drawBitmap(art, src, new Rect(0, 0, size, size), p);
        return out;
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        // Over a photo the menus are glass too, with a darker veil than the buttons so every row reads.
        if (backdrop.bitmap() != null) panel.setBackground(new GlassDrawable(panel, backdrop, 20 * density, density, 0x80000000));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (5 * density), h = (int) (10 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        } else if (show && !dockApps.contains(card.pkg) && !DockEditor.isPlaced(this, card.pkg)) {
            // A newly shown card starts first in the grid; once the owner has placed it, it keeps that place.
            appOrder = DockEditor.loadAppOrder(this, dockApps);
            appOrder.remove(card.pkg);
            appOrder.add(0, card.pkg);
            DockEditor.saveAppOrder(this, appOrder);
            adapter.setAppOrder(appOrder);
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
