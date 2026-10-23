package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, ContentRow.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, PAGE = 8, MENU = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // The redesigned home: one page that scrolls with the focus. A small centred clock, the inputs row (TV, HDMI,
    // Mirroring), the content row (hidden until Kodi or SmartTube have something to show), the apps shelf and,
    // below the fold, the settings row and every installed app.
    private static final float P_CLOCK_BASE = 84, P_CLOCK_META = 124, P_CLOCK_SIZE = 48, P_META_SIZE = 20;
    private static final float P_INPUTS_TOP = 168, P_INPUTS_H = 64, P_INPUTS_GAP = 64;
    private static final float P_CONTENT_TOP = 264, P_CONTENT_H = 338;
    private static final float P_FOLD = 1140;   // the settings row always starts below the fold: it shows on scroll
    private static final float P_SHELF_GLASS = 828;   // the shelf sits low: the middle of the page is background
    private static final float P_SETTINGS_H = 84, P_APPS_GAP = 48, P_GRID_GAP = 56, P_PAGE_BOTTOM = 70;
    private static final float L_BUTTON = 64, L_BUTTON_GAP = 20, L_TILE_H = 112;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_SETTINGS = 6, Z_GRID = 7;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Wallpapers wallpapers;
    private final ContentRow contentRow;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, pageLayer, menuLayer, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    // The page below the fold: the settings row and every installed app, scrolled with the focus.
    private Spring[] settingsFocus = new Spring[0];
    private final List<HomeApps.Item> settingsButtons = new ArrayList<>();
    private RectF[] settingsRects = new RectF[0];
    private int settingsIndex;
    private final Spring pageScroll = new Spring(0.16f, 0.8f, 0);
    private float scrollMax;
    // The page's rows, in design pixels (drawn at *k): the content row stays hidden until it has something to show.
    private boolean contentOn;
    private float pContentTop = 316, pAppsGlass = 340, pSettingsTop = 594, pGridTop = 746, pPageH = 1533;
    // The status icons (top right): wifi connected and bluetooth on; the web remote's state will join them.
    private boolean wifiOn, btOn;
    private final BroadcastReceiver statesReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            readStates();
            heroDirty = true;
        }
    };
    // The long-press menu on an app (shelf or grid): Open, Move, Details, Uninstall/Disable, add to/take off the shelf.
    private static final int MENU_OPEN = 0, MENU_MOVE = 1, MENU_DETAILS = 2, MENU_UNINSTALL = 3, MENU_TOGGLE = 4;
    private boolean menuOpen, menuConfirm;
    private int menuIndex;
    private HomeApps.Item menuItem;
    private boolean menuFromShelf;
    private final List<String> menuLabels = new ArrayList<>(), menuSymbols = new ArrayList<>();
    private final List<Integer> menuActions = new ArrayList<>();
    private final RectF menuRect = new RectF();
    private final RectF rowRect = new RectF();
    private final RectF pageRect = new RectF();
    private float menuLayerY;
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote,
            lClockTop, lMetaTop, lCardSubtitle;
    private Shader skyTop, skyBottom;
    private float skyTopH = -1, skyNight = -1;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Content row (Kodi favourites, SmartTube subscriptions)
    private List<ContentRow.Item> contentItems = new ArrayList<>();
    private Spring[] contentFocus = new Spring[0];
    private RectF[] contentRects = new RectF[0];
    private int contentIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawContentRow(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case PAGE: drawPage(c); break;
                case MENU: drawAppMenu(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            contentRow.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            wallpapers.tick();   // a new random background photo every 15 minutes
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        wallpapers = new Wallpapers(a, handler, this::showBackdrop, this::updateCards);
        contentRow = ContentRow.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);   // the content row (Kodi favourites, SmartTube previews; empty for now)
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        pageLayer = new Layer(a, PAGE);
        menuLayer = new Layer(a, MENU);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, pageLayer, menuLayer, drawerLayer, sheetLayer})
            v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, pageLayer, menuLayer, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        setLightButtons(HomeApps.inputChips(getContext()));
        setSettingsButtons(HomeApps.settingsButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        // The status icons: read once, then on the system's wifi/bluetooth broadcasts.
        readStates();
        IntentFilter states = new IntentFilter();
        states.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        states.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        states.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        getContext().registerReceiver(statesReceiver, states);
    }

    @Override protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(statesReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDetachedFromWindow();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        contentRow.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        contentRow.refreshIfStale();
        wallpapers.tick();       // the first photo (or the last saved one) right away
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        closeAppMenu();
        zone = Z_SHELF;
        shelfIndex = 0;
        focusLast();
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        retarget(!animated);
        motionDirty = true;
    }

    /** Focuses the row or the app opened last (Prefs.lastFocus); the first app otherwise. */
    private void focusLast() {
        String last = Prefs.lastFocus();
        if (last.startsWith("top:")) {
            String key = last.substring(4);
            for (int i = 0; i < lightButtons.size(); i++) {
                if (key.equals(lightButtons.get(i).key)) {
                    zone = Z_TOP;
                    topIndex = 3 + i;
                    return;
                }
            }
        } else if (last.startsWith("app:")) {
            String pkg = last.substring(4);
            for (int i = 0; i < shelf.size(); i++) {
                if (pkg.equals(shelf.get(i).key)) {
                    shelfIndex = i;
                    return;
                }
            }
        } else if (last.startsWith("settings:")) {
            String key = last.substring(9);
            for (int i = 0; i < settingsButtons.size(); i++) {
                if (key.equals(settingsButtons.get(i).key)) {
                    zone = Z_SETTINGS;
                    settingsIndex = i;
                    return;
                }
            }
        } else if (last.startsWith("grid:")) {
            String pkg = last.substring(5);
            for (int i = 0; i < others.size(); i++) {
                if (pkg.equals(others.get(i).key)) {
                    zone = Z_GRID;
                    gridIndex = i;
                    return;
                }
            }
        }
    }

    /** The settings row's buttons (fixed), with their focus springs and rects sized to the list. */
    private void setSettingsButtons(List<HomeApps.Item> list) {
        settingsButtons.clear();
        settingsButtons.addAll(list);
        settingsFocus = new Spring[list.size()];
        settingsRects = new RectF[list.size()];
        for (int i = 0; i < list.size(); i++) {
            settingsFocus[i] = new Spring(0.26f, 0.85f, 0);
            settingsRects[i] = new RectF();
        }
    }

    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= 3 + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            if (topIndex < 3 && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < 3) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    @Override public void onContent() {
        wake();
        List<ContentRow.Item> list = contentRow.list();
        contentOn = !list.isEmpty();
        if (contentOn) setContentItems(list);
        statusDirty = true;
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, pageLayer, menuLayer, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        // The hero layer spans the clock at the top and the chips above the shelf (the middle stays transparent).
        measure(heroLayer, width, Math.round(840 * kk));
        // The content row (the status layer, repurposed): hidden while it has nothing to show.
        measure(status, width, Math.round((P_CONTENT_H + 40) * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        // The page below the fold: the settings row and the apps' grid (layoutPage keeps the height in step).
        measure(pageLayer, width, Math.round(Math.max(1, pPageH - pSettingsTop + 40) * kk));
        // The long-press menu: a small panel near the focused tile.
        measure(menuLayer, width, Math.round(520 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, 0, Math.round(pContentTop * kk));
        place(platter, 0, Math.round((pAppsGlass - 64) * kk));
        place(tiles, 0, Math.round((pAppsGlass - 24) * kk));
        place(pageLayer, 0, Math.round((pSettingsTop - 40) * kk));
        place(menuLayer, 0, Math.round(menuLayerY * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutStatus();
        layoutDrawer();
        layoutPage();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lCardSubtitle = Ui.text(Ui.regular, 20 * k, L_FAINT);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 23 * k, L_INK);
        lTip.setTextAlign(Paint.Align.LEFT);
        lClockTop = Ui.numbers(Ui.semibold, P_CLOCK_SIZE * k, L_INK);
        lMetaTop = Ui.text(Ui.regular, P_META_SIZE * k, L_DIM);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt) | step(contentFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean settingsMoving = step(settingsFocus, dt);
        boolean pageMoving = pageScroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty || pageMoving) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (gridMoving || gridDirty || settingsMoving || pageMoving) pageLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || settingsMoving || pageMoving || toastFading || heroFading || bgFading || nightMoving
                || sheetOpen || sheet.value > 0.001f || drawerOpen || drawer.value > 0.001f || toast != null
                || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift - pageScroll.value * k;   // the drawer's lift together with the page's scroll
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        show(pageLayer, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        menuLayer.setVisibility(menuOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        // The content row comes from Kodi and SmartTube now; the picks API stays unused.
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = pickBackdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** The background: the random photo when that setting is on; else the first pick's photo (or the gradient). */
    private Backdrop pickBackdrop(Recs.Pick first) {
        Backdrop w = wallpapers.current();
        return w != null ? w : recs.backdrop(first);
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
        lClockTop.setColor(tone(L_INK, WHITE));
        lMetaTop.setColor(tone(L_DIM, 0xFF98989D));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    /** Update the content row items (Kodi favourites, SmartTube subscriptions). */
    private void setContentItems(List<ContentRow.Item> list) {
        boolean same = list.size() == contentItems.size();
        if (!same || contentFocus.length != list.size()) {
            contentItems = list;
            contentFocus = new Spring[list.size()];
            contentRects = new RectF[list.size()];
            int n = list.size();
            float cardW = 300 * k, cardH = 196 * k, gap = 24 * k;
            float totalW = n * (cardW + gap) - gap;
            float rowW = w - 2 * (28 * k + 40 * k);
            float startX = (w - Math.min(totalW, rowW)) / 2f;
            for (int i = 0; i < n; i++) {
                contentFocus[i] = new Spring(0.28f, 0.85f, 0);
                float cx = startX + i * (cardW + gap);
                contentRects[i] = new RectF(cx, 0, cx + cardW, cardH);
            }
            contentIndex = Math.min(contentIndex, n - 1);
        }
        statusDirty = true;
        aim(contentFocus, zone == Z_STATUS ? statusIndex : -1, true);
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    /** The page below the fold: the whole background behind it frosted and darkened, with the settings and the grid. */
    private void drawPage(Canvas c) {
        applyTone();
        pageRect.set(0, (pSettingsTop - 24) * k, w, (pPageH - 12) * k);
        drawerGlass(c, pageRect, 0, 0.8f, 0x2E000000, 0);
        drawSettingsButtons(c);
        drawAllApps(c);
    }

    /** The two settings buttons (glass pills like the chips, a little bigger). */
    private void drawSettingsButtons(Canvas c) {
        for (int i = 0; i < settingsButtons.size(); i++) {
            HomeApps.Item it = settingsButtons.get(i);
            RectF r = settingsRects[i];
            float f = Ui.clamp(settingsFocus[i].value), s = 1 + 0.04f * f;
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                    r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
            float rad = cell.height() / 2;
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.12f, 0.34f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(cell, rad, rad, fill);
            }
            if (it.label.isEmpty()) {   // icon-only pill (the projector's focus, keystones, the web remote)
                Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 34 * k * s,
                        blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
                continue;
            }
            float iconX = cell.left + (30 + 16) * k * s;
            Symbols.draw(getContext(), c, it.symbol, iconX, cell.centerY(), 32 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            lTip.setAlpha(255);
            lTip.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            c.drawText(it.label, cell.left + (30 + 32 + 16) * k * s, cell.centerY() + Ui.halfCap(lTip), lTip);
        }
    }

    /** Every installed app, in the page's grid (the sheet's tiles, at their place in the page). */
    private void drawAllApps(Canvas c) {
        // The page's scroll already moves this layer (updateMotion): here only the off-screen rows are skipped.
        float shift = pageScroll.value * k;
        String title = Ui.tr("All Apps");
        c.drawText(title, w / 2f - gridLabel.measureText(title) / 2, (pGridTop + 34) * k, gridLabel);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (w - gridWidth * k) / 2f;
        float rowsTop = (pGridTop + 56) * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = rowsTop + r * GRID_ROW * k;
            if (y - shift > h || y + GRID_ROW * k - shift < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = Ui.clamp(gridFocus[i].value);
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (pAppsGlass + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap)
                | aim(contentFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        boolean settingsChanged = aim(settingsFocus, !sheetOpen && zone == Z_SETTINGS ? settingsIndex : -1, snap);
        boolean gridChanged = aim(gridFocus, !sheetOpen && zone == Z_GRID ? gridIndex : -1, snap);
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        gridDirty |= snap || settingsChanged || gridChanged;
        // The page scrolls with the focus — but only for what sits below the fold: the clock, the chips and the
        // shelf are already in view at scroll 0 (the content block is anchored to the bottom).
        float target = 0;
        if (zone == Z_SETTINGS) {
            target = pSettingsTop + P_SETTINGS_H / 2 - 480;
        } else if (zone == Z_GRID) {
            target = pGridTop + 56 + gridIndex / COLUMNS * GRID_ROW + GRID_H / 2 - 480;
        }
        pageScroll.target = Math.max(0, Math.min(scrollMax, target));
        if (snap) pageScroll.snap(pageScroll.target);
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
        layoutPage();   // the grid's rows change the page's height (and the scroll)
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            handler.post(() -> {
                setLightButtons(HomeApps.inputChips(c));   // the chips are fixed now: TV, HDMI, Mirroring
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        if (menuOpen) {   // the app menu takes the keys until it is closed (Back, or OK on a row)
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && menuIndex > 0) {
                        menuIndex--;
                        menuLayer.invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && menuIndex < menuLabels.size() - 1) {
                        menuIndex++;
                        menuLayer.invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        handler.removeCallbacks(holdStart);
                        holdArmed = false;
                    } else if (!e.isCanceled()) {
                        if (okHeld) okHeld = false;   // the release that ended the hold: it only closes the press
                        else applyMenuItem();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (!down && !e.isCanceled()) {
                        closeAppMenu();
                        click.move();
                    }
                    return true;
                default:
                    return true;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex)
                                : (zone == Z_SHELF && shelfIndex < shelf.size()) || (zone == Z_GRID && gridIndex < others.size());
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!contentItems.isEmpty() || !statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        if (contentOn && !contentItems.isEmpty()) {
                            statusIndex = shelf.isEmpty() ? 0 : nearest(contentRects, tileRects[shelfIndex].centerX());
                        } else {
                            statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                        }
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (contentItems.isEmpty() && statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int n = contentOn ? contentItems.size() : statusItems.size();
                    int next = Math.max(0, Math.min(n - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (contentOn && !contentItems.isEmpty()) {
                // Open a Kodi favourite or SmartTube subscription in its app
                ContentRow.Item ci = contentItems.get(Math.min(statusIndex, contentItems.size() - 1));
                String pkg = ContentRow.open(getContext(), ci);
                if (!pkg.isEmpty()) {
                    PackageManager pmm = getContext().getPackageManager();
                    if (pmm != null) {
                        Intent intent = pmm.getLeanbackLaunchIntentForPackage(pkg);
                        if (intent != null) {
                            click.open();
                            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    }
                }
                return;
            }
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) return;   // the tabs are gone; the chips sit at 3+
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_SETTINGS) {
            if (settingsButtons.isEmpty()) return;
            it = settingsButtons.get(settingsIndex);
            r = settingsRects[settingsIndex];
        } else if (zone == Z_GRID) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
            float left = (w - gridWidth * k) / 2f;
            float y = (pGridTop + 56 + gridIndex / COLUMNS * GRID_ROW) * k - pageScroll.value * k;
            float x = left + gridIndex % COLUMNS * (GRID_W + GRID_GAP) * k;
            cell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            r = cell;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (zone == Z_SETTINGS) Prefs.setLastFocus("settings:" + it.key);
        else if (zone == Z_GRID) Prefs.setLastFocus("grid:" + it.key);
        else if (it.kind == HomeApps.APP && it.key != null) Prefs.setLastFocus("app:" + it.key);
        else if (it.kind == HomeApps.INPUT) Prefs.setLastFocus("top:" + HomeApps.TOP_INPUTS);
        else if (it.kind == HomeApps.LIVE_TV || it.kind == HomeApps.INPUTS || it.kind == HomeApps.SETTINGS)
            Prefs.setLastFocus("top:" + it.key);
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else openAppMenu();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        if (canToggleShelf(gridIndex)) toggleShelfItem(others.get(gridIndex));
    }

    /** Adds the app to the shelf or takes it off; a toast says which. */
    private void toggleShelfItem(final HomeApps.Item it) {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= HomeApps.MAX_SHELF) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= HomeApps.MAX_SHELF) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - The app menu (long press on an app, on the shelf or in the grid)

    /** The long-press menu: Open, Move (the shelf), Details, Uninstall/Disable, add to/take off the shelf. */
    private void openAppMenu() {
        wake();
        HomeApps.Item it;
        RectF tile;
        if (zone == Z_SHELF && shelfIndex < shelf.size()) {
            it = shelf.get(shelfIndex);
            tile = tileRects[shelfIndex];
            menuFromShelf = true;
        } else if (zone == Z_GRID && gridIndex < others.size()) {
            it = others.get(gridIndex);
            float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
            float left = (w - gridWidth * k) / 2f;
            cell.set(left + gridIndex % COLUMNS * (GRID_W + GRID_GAP) * k,
                    (pGridTop + 56 + gridIndex / COLUMNS * GRID_ROW) * k - pageScroll.value * k,
                    left + (gridIndex % COLUMNS + 1) * (GRID_W + GRID_GAP) * k,
                    (pGridTop + 56 + gridIndex / COLUMNS * GRID_ROW) * k + GRID_H * k - pageScroll.value * k);
            tile = cell;
            menuFromShelf = false;
        } else {
            return;
        }
        if (it.kind != HomeApps.APP || it.pkg == null) return;
        menuItem = it;
        boolean user = isUserApp(it.pkg);
        menuLabels.clear();
        menuActions.clear();
        menuSymbols.clear();
        menuLabels.add(Ui.tr("Open"));
        menuActions.add(MENU_OPEN);
        menuSymbols.add("play");
        if (menuFromShelf && canReorder()) {
            menuLabels.add(Ui.tr("Move"));
            menuActions.add(MENU_MOVE);
            menuSymbols.add("move");
        }
        menuLabels.add(Ui.tr("App details"));
        menuActions.add(MENU_DETAILS);
        menuSymbols.add("info");
        menuLabels.add(Ui.tr(user ? "Uninstall" : "Disable"));
        menuActions.add(MENU_UNINSTALL);
        menuSymbols.add(user ? "trash" : "tv_settings");
        menuLabels.add(Ui.tr(menuFromShelf ? "Remove from the shelf" : "Add to the shelf"));
        menuActions.add(MENU_TOGGLE);
        menuSymbols.add("check");
        menuConfirm = false;
        menuIndex = 0;
        // The panel, next to the tile (under it, above it when there is no room left).
        float rowH = 58 * k, panelW = 430 * k, panelH = 2 * 16 * k + menuLabels.size() * rowH;
        float px = Math.max(MARGIN * k, Math.min(w - panelW - MARGIN * k, tile.centerX() - panelW / 2));
        float py = tile.bottom + 14 * k;
        if (py + panelH > h - 16 * k) py = Math.max(16 * k, tile.top - panelH - 14 * k);
        menuRect.set(px, py, px + panelW, py + panelH);
        menuLayerY = Math.max(0, py - 40 * k);
        place(menuLayer, 0, Math.round(menuLayerY));
        menuOpen = true;
        updateMotion();
        menuLayer.invalidate();
        okHeld = true;   // the release that ended the hold must not also open the app
        click.open();
    }

    private void closeAppMenu() {
        menuOpen = menuConfirm = false;
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        updateMotion();
    }

    /** The row the person chose: it acts, or (for the uninstall) it asks for a confirmation first. */
    private void applyMenuItem() {
        HomeApps.Item it = menuItem;
        if (menuConfirm) {
            boolean yes = menuIndex == 0;
            closeAppMenu();
            if (yes) uninstallApp(it);
            return;
        }
        int action = menuActions.get(menuIndex);
        closeAppMenu();
        switch (action) {
            case MENU_OPEN:
                activate();
                break;
            case MENU_MOVE:
                startReorder();
                break;
            case MENU_DETAILS:
                try {
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    showToast(Ui.tr("Couldn't open the app's details."));
                }
                break;
            case MENU_UNINSTALL:
                menuConfirm = true;
                menuIndex = 0;
                menuLabels.clear();
                menuLabels.add(Ui.trf(isUserApp(it.pkg) ? "Uninstall {app}?" : "Disable {app}?", "app", it.label));
                menuSymbols.clear();
                menuSymbols.add(isUserApp(it.pkg) ? "trash" : "tv_settings");
                menuLabels.add(Ui.tr("Cancel"));
                menuSymbols.add("check");
                menuOpen = true;
                updateMotion();
                menuLayer.invalidate();
                break;
            case MENU_TOGGLE:
                toggleShelfItem(it);
                break;
        }
    }

    /** An app is a user one when it isn't flagged as part of the system image. */
    private boolean isUserApp(String pkg) {
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(pkg, 0);
            return pi.applicationInfo == null || (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Uninstalls a user app (pm uninstall --user 0) or disables a system one (pm disable-user), through the app's own
     * adb connection: no root, the same channel the remote-keys settings already use.
     */
    private void uninstallApp(final HomeApps.Item it) {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            String message;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                boolean user = adb.shell("pm path " + it.pkg).contains("/data/app");
                String cmd = (user ? "pm uninstall --user 0 " : "pm disable-user --user 0 ") + it.pkg;
                String out = adb.shell(cmd).trim();
                AppLog.w("app menu: " + cmd + " -> " + out);
                message = out.contains("Success") || out.contains("disabled") || out.contains("uninstalled")
                        ? Ui.trf("{app}: done.", "app", it.label) : Ui.tr("Couldn't remove the app.");
            } catch (IOException e) {
                AppLog.w("app menu: " + e.getMessage());
                message = Ui.tr("Couldn't remove the app.");
            }
            final String text = message;
            handler.post(() -> {
                showToast(text);
                shelfEdited = true;
                reload();
            });
        });
    }

    /** The menu panel: glass, with the rows (icon + name); the focused row is white, like the chips. */
    private void drawAppMenu(Canvas c) {
        applyTone();
        float rowH = 58 * k, pad = 16 * k;
        drawerGlass(c, menuRect, 24 * k, 0.86f, 0x1A000000, 12 * k);
        for (int i = 0; i < menuLabels.size(); i++) {
            float f = i == menuIndex ? 1 : 0;
            rowRect.set(menuRect.left + 14 * k, menuRect.top + pad + i * rowH,
                    menuRect.right - 14 * k, menuRect.top + pad + (i + 1) * rowH - 10 * k);
            if (f > 0) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(rowRect, rowRect.height() / 2, rowRect.height() / 2, fill);
            }
            Symbols.draw(getContext(), c, menuSymbols.get(i), rowRect.left + 34 * k, rowRect.centerY(), 26 * k,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            lTip.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            c.drawText(menuLabels.get(i), rowRect.left + 62 * k, rowRect.centerY() + Ui.halfCap(lTip), lTip);
        }
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < contentItems.size(); i++) out.add(a11yId(Z_STATUS, i));
        for (int i = 0; i < settingsButtons.size(); i++) out.add(a11yId(Z_SETTINGS, i));
        for (int i = 0; i < others.size(); i++) out.add(a11yId(Z_GRID, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP: return i >= 3 ? label(lightButtons, i - 3) : "";
            case Z_STATUS:
                if (i >= contentItems.size()) return "";
                return contentItems.get(i).title + ", " + contentItems.get(i).subtitle;
            case Z_SETTINGS: return label(settingsButtons, i);
            case Z_GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i < 3 || i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_STATUS:
                if (i >= contentRects.length) return false;
                a11yRect.set(contentRects[i]);
                dy = status.getTranslationY();
                break;
            case Z_SETTINGS:
                if (i >= settingsButtons.size()) return false;
                a11yRect.set(settingsRects[i]);
                dy = pageLayer.getTranslationY();
                break;
            case Z_GRID: {
                if (sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = (w - gridWidth * k) / 2f + (i % COLUMNS) * (GRID_W + GRID_GAP) * k;
                float y = (pGridTop + 56 + i / COLUMNS * GRID_ROW) * k + pageLayer.getTranslationY();
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return topIndex >= 3 ? a11yId(Z_TOP, topIndex) : -1;
            case Z_STATUS: return contentItems.isEmpty() ? -1 : a11yId(Z_STATUS, statusIndex);
            case Z_SETTINGS: return settingsButtons.isEmpty() ? -1 : a11yId(Z_SETTINGS, settingsIndex);
            case Z_GRID: return others.isEmpty() ? -1 : a11yId(Z_GRID, gridIndex);
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i < 3 || i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                break;
            case Z_SETTINGS:
                if (i >= settingsButtons.size()) return;
                if (drawerOpen) closeDrawer();
                settingsIndex = i;
                break;
            case Z_GRID:
                if (i >= others.size()) return;
                if (drawerOpen) closeDrawer();
                gridIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    /** The page's rows: the content row (when it has something), the apps, then settings and every app below. */
    private void layoutPage() {
        if (lTip == null) return;
        pContentTop = P_CONTENT_TOP;
        contentOn = !contentRow.list().isEmpty();   // the content row shows only real previews (Kodi favourites, SmartTube)
        if (contentOn && contentItems.isEmpty()) setContentItems(contentRow.list());
        // The shelf sits low (the middle of the page stays background); the chips go just above it.
        pAppsGlass = P_SHELF_GLASS;
        // Below the fold, whatever the page above looks like: the settings and the apps' grid show on scroll only.
        pSettingsTop = Math.max(pAppsGlass + L_TILE_H + 2 * SHELF_PAD + P_APPS_GAP, P_FOLD);
        pGridTop = pSettingsTop + P_SETTINGS_H + P_GRID_GAP;
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        pPageH = pGridTop + 56 + rows * GRID_ROW + P_PAGE_BOTTOM;
        scrollMax = Math.max(0, pPageH - 1080);
        // The two settings buttons, centred.
        float bh = P_SETTINGS_H * k, cy = (pSettingsTop + P_SETTINGS_H / 2) * k, total = 0;
        for (int i = 0; i < settingsButtons.size(); i++) {
            float bw = settingsButtons.get(i).label.isEmpty() ? bh
                    : (30 + 32 + 16) * k + lTip.measureText(settingsButtons.get(i).label) + 30 * k;
            settingsRects[i].set(0, cy - bh / 2, bw, cy + bh / 2);
            total += bw;
        }
        total += Math.max(0, settingsButtons.size() - 1) * 32 * k;
        float bx = (w - total) / 2f;
        for (int i = 0; i < settingsButtons.size(); i++) {
            RectF r = settingsRects[i];
            r.offsetTo(bx, r.top);
            bx += r.width() + 32 * k;
        }
        layoutLight();
        layoutShelf();
        measure(pageLayer, w, Math.round(Math.max(1, pPageH - pSettingsTop + 40) * k));
        place(pageLayer, 0, Math.round((pSettingsTop - 40) * k));
        heroDirty = gridDirty = true;
        retarget(true);
    }

    private void layoutLight() {
        if (lTip == null) return;
        // The chips: labeled pills centred just above the shelf (TV, HDMI, Mirroring).
        float b = L_BUTTON * k, cy = (pAppsGlass - 28 - P_INPUTS_H / 2) * k, total = 0;
        for (int i = 0; i < lightButtons.size(); i++) {
            float bw = (24 + 26 + 14) * k + lTip.measureText(lightButtons.get(i).label) + 26 * k;   // pad + icon + gap + label + pad
            topRects[3 + i].set(0, cy - b / 2, bw, cy + b / 2);
            total += bw;
        }
        total += Math.max(0, lightButtons.size() - 1) * L_BUTTON_GAP * k;
        float bx = (w - total) / 2f;
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            r.offsetTo(bx, r.top);
            bx += r.width() + L_BUTTON_GAP * k;
        }
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = pickBackdrop(cards.isEmpty() ? null : cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the wallpaper sharp, with dark shading from the top and from the bottom (a bit more at night); the
     * frosted glass still samples the blurred backdrop, so the panels keep their depth. Without a photo, gradients.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawSkyImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawSkyImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        if (night > 0.001f) {   // the sharp photo darkens at night
            fill.setColor(Ui.alpha(0xFF000000, Math.round(0x59 * night)));
            c.drawRect(screen, fill);
        }
        // The dark shading, from the top and from the bottom (the clock and the shelf read on it).
        if (skyTop == null || skyTopH != h || Math.abs(skyNight - night) > 0.02f) {
            skyTop = new LinearGradient(0, 0, 0, 0.26f * h, tone(0x73000000, 0xB3000000), 0x00000000, Shader.TileMode.CLAMP);
            skyBottom = new LinearGradient(0, 0.60f * h, 0, h, 0x00000000, tone(0x8C000000, 0xD9000000), Shader.TileMode.CLAMP);
            skyTopH = h;
            skyNight = night;
        }
        fill.setShader(skyTop);
        c.drawRect(screen, fill);
        fill.setShader(skyBottom);
        c.drawRect(screen, fill);
        fill.setShader(null);
    }

    private void drawSkyImage(Canvas c, Backdrop b, float alpha) {
        drawImage(c, b.image, alpha);   // the sharp wallpaper (the frosted versions stay for the glass)
    }

    /** The page's top: the small centred clock (the full date under it) and the status icons at the right. */
    private void drawLightTop(Canvas c) {
        applyTone();
        c.drawText(clockText, w / 2f - lClockTop.measureText(clockText) / 2 - Ui.leftBearing(lClockTop, clockText),
                P_CLOCK_BASE * k, lClockTop);
        c.drawText(dateText, w / 2f - lMetaTop.measureText(dateText) / 2, P_CLOCK_META * k, lMetaTop);
        drawStatusIcons(c);
        drawChips(c);
        if (contentOn) drawContentRow(c);
    }

    /** The status icons at the top right (wifi connected, bluetooth on; the web remote's state will join them). */
    private void drawStatusIcons(Canvas c) {
        int color = tone(0x9E8E8E93, 0x9EFFFFFF);
        float cy = 60 * k, right = CONTENT_R * k - 24 * k;
        if (btOn) {
            float bw = Symbols.width(getContext(), "bluetooth", 24 * k);
            Symbols.draw(getContext(), c, "bluetooth", right - bw / 2, cy, 24 * k, color);
            right -= bw + 26 * k;
        }
        if (wifiOn) {
            float ww = Symbols.width(getContext(), "wifi", 24 * k);
            Symbols.draw(getContext(), c, "wifi", right - ww / 2, cy, 24 * k, color);
        }
    }

    /** Wifi connected and bluetooth on, read once and re-read when the system broadcasts a change. */
    private void readStates() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            wifiOn = ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            wifiOn = false;
        }
        try {
            btOn = Settings.Global.getInt(getContext().getContentResolver(), "bluetooth_on", 0) == 1;
        } catch (Throwable t) {
            btOn = false;
        }
    }

    /** The inputs row's chips (TV, HDMI, Mirroring): glass pills; the focused one turns white (the tvOS focus). */
    private void drawChips(Canvas c) {
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.05f * f;
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                    r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
            float rad = cell.height() / 2;
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(cell, rad, rad, fill);
            }
            float iconX = cell.left + (24 + 13) * k * s;
            Symbols.draw(getContext(), c, it.symbol, iconX, cell.centerY(), 26 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            lTip.setAlpha(255);
            lTip.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            c.drawText(it.label, cell.left + (24 + 26 + 14) * k * s, cell.centerY() + Ui.halfCap(lTip), lTip);
        }
    }

    /** The content row: previews from Kodi (favourites, recently added) and SmartTube. Empty for now. */
    /** The content row: previews from Kodi (favourites, recently added) and SmartTube subscriptions. */
    private void drawContentRow(Canvas c) {
        List<ContentRow.Item> items = contentItems;
        if (items.isEmpty()) return;
        float cardW = 300 * k, cardH = 196 * k, gap = 24 * k, rad = 18 * k;
        for (int i = 0; i < items.size(); i++) {
            if (i >= contentRects.length) break;
            RectF base = contentRects[i];
            float f = i < contentFocus.length ? Ui.clamp(contentFocus[i].value) : 0;
            float s = 1 + 0.04f * f;
            float cx = base.centerX(), cy = base.centerY() - 6 * k * f;
            cell.set(cx - cardW * s / 2, cy - cardH * s / 2, cx + cardW * s / 2, cy + cardH * s / 2);
            // Shadow
            tmp.set(cell);
            tmp.offset(0, (6 + 10 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, (0.14f + 0.24f * f)));
            shadows.draw(c, tmp, rad * s, SHADOW_BLUR * k, shadow);
            // Card background
            ContentRow.Item it = items.get(i);
            Bitmap poster = contentRow.poster(it);
            if (poster != null) {
                roundImage(c, poster, cell, rad * s);
            } else {
                fill.setShader(null);
                fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                c.drawRoundRect(cell, rad * s, rad * s, fill);
            }
            // Focus glow
            if (f > 0.01f) {
                sheen.setColor(Ui.alpha(0xFFFFFFFF, Math.round(128 * f)));
                c.drawRoundRect(cell, rad * s, rad * s, sheen);
                rim.setColor(Ui.alpha(0xFFFFFFFF, Math.round(128 * f)));
                rim.setStrokeWidth(2.5f * k);
                c.drawRoundRect(cell, rad * s, rad * s, rim);
                rim.setStrokeWidth(2.5f * k);
            }
            // Title
            if (lCardTitle != null && !it.title.isEmpty()) {
                fitPaint.set(lCardTitle);
                String label = TextUtils.ellipsize(it.title, fitPaint, cardW * 0.9f, TextUtils.TruncateAt.END).toString();
                c.drawText(label, cell.left + 12 * k, cell.bottom + 32 * k, lCardTitle);
            }
            // Subtitle
            if (lCardSubtitle != null && !it.subtitle.isEmpty()) {
                lCardSubtitle.setColor(tone(0xFF8E8E93, 0xFFAAAAAA));
                c.drawText(it.subtitle, cell.left + 12 * k, cell.bottom + 60 * k, lCardSubtitle);
            }
        }
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // The tiles' left/right (and the reorder) live in move(); here only the rows above and below.
                if (dx != 0) return false;
                if (dy < 0) {
                    if (lightButtons.isEmpty()) return true;
                    zone = Z_TOP;
                    topIndex = 3 + nearestButton(shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                } else {
                    if (settingsButtons.isEmpty()) return true;
                    zone = Z_SETTINGS;
                    settingsIndex = nearest(settingsRects, settingsButtons.size(),
                            shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                } else if (dy > 0) {
                    if (shelf.isEmpty()) return true;
                    zone = Z_SHELF;
                    shelfIndex = nearest(tileRects, shelf.size(), topRects[topIndex].centerX());
                } else {
                    return true;   // the chips are the top row
                }
                break;
            case Z_SETTINGS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(settingsButtons.size() - 1, settingsIndex + dx));
                    if (next == settingsIndex) return true;
                    settingsIndex = next;
                } else if (dy < 0) {
                    if (shelf.isEmpty()) return true;
                    zone = Z_SHELF;
                    shelfIndex = nearest(tileRects, shelf.size(), settingsRects[settingsIndex].centerX());
                } else {
                    if (others.isEmpty()) return true;
                    zone = Z_GRID;
                    gridIndex = Math.min(gridIndex, others.size() - 1);
                }
                break;
            case Z_GRID:
                if (dx != 0) {
                    int col = Math.max(0, Math.min(COLUMNS - 1, gridIndex % COLUMNS + dx));
                    int next = gridIndex / COLUMNS * COLUMNS + col;
                    if (next >= others.size()) next = others.size() - 1;
                    if (next == gridIndex) return true;
                    gridIndex = next;
                } else if (dy < 0) {
                    if (gridIndex < COLUMNS) {
                        if (settingsButtons.isEmpty()) return true;
                        zone = Z_SETTINGS;
                        settingsIndex = nearest(settingsRects, settingsButtons.size(), gridCenterX(gridIndex));
                    } else {
                        gridIndex -= COLUMNS;
                    }
                } else {
                    if (gridIndex + COLUMNS >= others.size()) return true;
                    gridIndex += COLUMNS;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /** The centre x of a grid cell (the settings row above takes the nearest button). */
    private float gridCenterX(int index) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (w - gridWidth * k) / 2f;
        return left + (index % COLUMNS) * (GRID_W + GRID_GAP) * k + GRID_W * k / 2;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** Index of the button (within lightButtons) whose pill is closest to x. */
    private int nearestButton(float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < lightButtons.size(); i++) {
            float d = Math.abs(topRects[3 + i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
