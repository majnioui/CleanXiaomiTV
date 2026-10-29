package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), followed by the saved user app order.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called when the user holds OK on a tile. */
        void onTileLongPressed(int position);

        /** Called by a normal OK click; return true when the launcher consumed it. */
        boolean onTileClicked(int position);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY_DOCK = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** Fill behind a square banner shown whole; 0 until sampled. */
        int squareFill;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(Context ctx, int slot) {
            this(EMPTY_DOCK, "", new ComponentName(ctx.getPackageName(), "EmptyDock" + slot), null, null, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private static final int TYPE_DOCK = 0;
    private static final int TYPE_GRID = 1;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Tiles per grid row below the dock (config.xml grid_columns). */
    private final int gridColumns;
    /** Dock order (saved user preferences, then config.xml or a theme's config.json). */
    private List<String> favourites;
    /** Order of the non-dock TV apps; new packages are appended by DockEditor.loadAppOrder(). */
    private List<String> appOrder;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg,
               List<String> dockApps, List<String> appOrder) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.gridColumns = cfg.gridColumns;
        this.favourites = new ArrayList<>(dockApps);
        this.appOrder = new ArrayList<>(appOrder);
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     * A card the owner chose to hide takes its app off the grid too (a dock slot the owner pinned stays).
     */
    void reload(List<Cards.Card> cards, Set<String> hiddenCards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        Map<String, ResolveInfo> byPackage = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            if (!byPackage.containsKey(pkg)) byPackage.put(pkg, ri);
        }

        // Keep the dock as a fixed first row. An empty or uninstalled slot stays empty instead of
        // allowing the alphabetical grid to slide up into the dock.
        List<Entry> fresh = new ArrayList<>();
        for (int slot = 0; slot < dockSize; slot++) {
            String pkg = slot < favourites.size() ? favourites.get(slot) : null;
            ResolveInfo ri = pkg == null ? null : byPackage.get(pkg);
            if (ri == null) {
                fresh.add(new Entry(ctx, slot));
                continue;
            }
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) card = null;
            Drawable banner = loadBanner(pm, ri);
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(String.valueOf(ri.loadLabel(pm)), component, banner, icon));
            }
        }

        // The remaining TV apps form the normal alphabetical grid. A user-pinned app wins over
        // hidden_apps for the dock, but hidden apps stay hidden everywhere else.
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || favourites.contains(pkg)
                    || hiddenCards.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(String.valueOf(ri.loadLabel(pm)), component, banner, icon));
            }
        }
        int dockCount = Math.min(dockSize, fresh.size());
        List<Entry> gridEntries = new ArrayList<>(fresh.subList(dockCount, fresh.size()));
        Collections.sort(gridEntries, (a, b) -> {
            // Cards follow the owner's order like any app; a newly shown card was put first in it (decideCard).
            int oa = appOrder.indexOf(a.pkg), ob = appOrder.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                return Integer.compare(oa, ob);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        fresh = new ArrayList<>(fresh.subList(0, dockCount));
        fresh.addAll(gridEntries);
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    void setDockApps(List<String> dockApps) {
        favourites = new ArrayList<>(dockApps);
    }

    void setAppOrder(List<String> appOrder) {
        this.appOrder = new ArrayList<>(appOrder);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        // Dock tiles fit `columns` per row, grid tiles `grid_columns` (smaller when that is more).
        int w = tileWidthPx(inner, viewType == TYPE_DOCK ? dockSize : gridColumns, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener);
    }

    @Override
    public int getItemViewType(int position) {
        return position < dockSize ? TYPE_DOCK : TYPE_GRID;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY_DOCK) {
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.card.setClickable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.name.setAlpha(0f);
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.card.setClickable(true);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            // Some apps reuse their square phone icon as the TV banner. Cropping that to 16:9 zooms in
            // and blurs it, so show it whole on its own edge colour instead.
            if (isSquare(e.banner)) {
                if (e.squareFill == 0) e.squareFill = edgeColor(e.banner);
                h.banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.banner.setBackgroundColor(e.squareFill);
            } else {
                h.banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.banner.setBackground(null);
            }
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && listener.onTileClicked(pos)) return;
            launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener == null || pos == RecyclerView.NO_POSITION) return false;
            listener.onTileLongPressed(pos);
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.EMPTY_DOCK) return;
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    boolean isFocusableAt(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind != Entry.EMPTY_DOCK;
    }

    /** True for a visible app in the fixed dock row (cards are valid dock entries too). */
    boolean isDockPosition(int position) {
        return position >= 0 && position < dockSize && isFocusableAt(position);
    }

    /** True for an installed application or a module card in the reorderable grid. */
    boolean isReorderableAppAt(int position) {
        return position >= dockSize && position < items.size()
                && (items.get(position).kind == Entry.APP || items.get(position).kind == Entry.CARD);
    }

    /** Package name for the visible tile at a position, or null for an empty/invalid slot. */
    String packageAt(int position) {
        return isFocusableAt(position) ? items.get(position).pkg : null;
    }

    List<String> dockApps() {
        return new ArrayList<>(favourites);
    }

    List<String> appOrder() {
        return new ArrayList<>(appOrder);
    }

    /**
     * Moves one visible tile in place, keeping the adapter list and its persisted-order list in
     * sync. The caller can use the returned position to keep focus on the moving app.
     */
    int moveForReorder(int position, int keyCode) {
        if (position < 0 || position >= items.size()) return -1;
        int target;
        if (isDockPosition(position)) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return -1;
            target = position + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (target < 0 || target >= dockSize || !isFocusableAt(target)) return -1;
            String movedPkg = favourites.remove(position);
            favourites.add(target, movedPkg);
        } else if (isReorderableAppAt(position)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                target = position + direction;
                // Grid rows hold gridColumns tiles, counted from the end of the dock.
                if ((target - dockSize) / gridColumns != (position - dockSize) / gridColumns) return -1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                target = position + direction * gridColumns;
            } else {
                return -1;
            }
            if (target < dockSize || target >= items.size() || !isReorderableAppAt(target)) return -1;
            String movedPkg = items.get(position).pkg;
            String targetPkg = items.get(target).pkg;
            int fromOrder = appOrder.indexOf(movedPkg);
            int targetOrder = appOrder.indexOf(targetPkg);
            if (fromOrder < 0 || targetOrder < 0) return -1;
            appOrder.remove(fromOrder);
            targetOrder = appOrder.indexOf(targetPkg);
            appOrder.add(targetOrder + (target > position ? 1 : 0), movedPkg);
        } else {
            return -1;
        }

        Entry moved = items.remove(position);
        items.add(target, moved);
        notifyItemMoved(position, target);
        return target;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    /** A banner closer to square than 4:3, i.e. an icon standing in for one. */
    private static boolean isSquare(Drawable d) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        return w > 0 && h > 0 && w < h * 4 / 3;
    }

    /** The colour at the top-left edge of the drawable, opaque; the background layer of an adaptive icon. */
    private static int edgeColor(Drawable live) {
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF1C1C1E);   // under a transparent corner: the dark tile colour
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int p = bmp.getPixel(1, 1);
        bmp.recycle();
        return p | 0xFF000000;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .withLayer()
                        .start();
                name.animate().cancel();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
