package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The content row between the clock and the shelf: Kodi favourites/recently added and SmartTube
 * subscriptions, fetched from the local network (Kodi JSON-RPC) or from the app's data (SmartTube).
 * Each item shows a small poster card; focused and clicked, it opens in its app.
 *
 * Kodi: JSON-RPC at http://localhost:8080/jsonrpc (the default Kodi HTTP API port).
 * SmartTube: reads the subscriptions file from the SmartTube data directory.
 */
final class ContentRow {
    static final class Item {
        String title, subtitle;      // "The Matrix" / "Movie · 2026"
        String imageUrl;             // poster / thumbnail URL (empty = use a placeholder)
        String appPackage;           // Android package to open (tv.kodi... / tv.stick.smarttube)
        String action;               // "kodi:video:123" / "smarttube:channel:UC..."
        boolean placeholder;         // true when the item couldn't be resolved
    }

    interface Listener { void onContent(); }

    /** Kodi JSON-RPC endpoint (default port, no auth). Use 8080 or 9090 (TCP). */
    private static final String KODI_RPC = "http://localhost:8080/jsonrpc";
    /** Kodi thumbnail base: image:// paths translate to http://localhost:8080/image/ */
    private static final String KODI_IMAGE = "http://localhost:8080/image/";
    /** How long between refreshes (5 minutes for Kodi, 30 for SmartTube). */
    private static final long REFRESH_EVERY = 5 * 60_000L;
    /** Max items to show (the row is small: 8 fits comfortably). */
    private static final int MAX_ITEMS = 10;
    /** SmartTube data file on Android TV. */
    private static final String SMARTTUBE_PATH = "/storage/emulated/0/Android/data/tv.stick.smarttube/files/";
    private static final String SMARTTUBE_SUBS = "subscriptions.json";

    private static ContentRow instance;

    static synchronized ContentRow get(Context c) {
        if (instance == null) instance = new ContentRow(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Bitmap> images = new LinkedHashMap<>();   // title → poster bitmap
    private final java.util.Set<String> pending = new java.util.HashSet<>();

    private volatile List<Item> items = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    private ContentRow(Context c) {
        context = c;
        cache = c.getSharedPreferences("contentrow", Context.MODE_PRIVATE);
        try {
            String saved = cache.getString("items", null);
            if (saved != null) items = parseList(new JSONArray(saved));
            fetchedAt = cache.getLong("fetchedAt", 0);
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The current content items (Kodi favourites + SmartTube subscriptions). */
    List<Item> list() { return items; }

    /** The cached poster for a content item, or null to draw a placeholder. */
    Bitmap poster(Item it) { return images.get(it.title); }

    /** Called from the tick: fetches fresh data if stale. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || now - fetchedAt < REFRESH_EVERY && now >= fetchedAt) return;
        fetching = true;
        io.execute(this::fetchAll);
    }

    /** Force a refresh now (after settings changed, Kodi was just opened, etc.). */
    void refreshNow() {
        if (fetching) return;
        fetching = true;
        io.execute(this::fetchAll);
    }

    /** Fetch from both sources, merge, cache and notify. */
    private void fetchAll() {
        try {
            List<Item> all = new ArrayList<>();
            // 1. Kodi favourites and recently added
            List<Item> kodiItems = fetchKodi();
            all.addAll(kodiItems);
            // 2. SmartTube subscriptions
            List<Item> stItems = fetchSmartTube();
            all.addAll(stItems);
            // Limit
            if (all.size() > MAX_ITEMS) all = all.subList(0, MAX_ITEMS);
            // Save
            items = all;
            fetchedAt = System.currentTimeMillis();
            // Cache
            JSONArray arr = new JSONArray();
            for (Item it : all) arr.put(encode(it));
            cache.edit()
                    .putString("items", arr.toString())
                    .putLong("fetchedAt", fetchedAt)
                    .apply();
            // Fetch poster images in the background
            for (Item it : all) poster(it);   // triggers fetch if not cached
            AppLog.w("content: " + all.size() + " items (Kodi + SmartTube)");
        } catch (Exception e) {
            AppLog.w("content: " + e.getMessage());
        } finally {
            fetching = false;
        }
        main.post(() -> { for (Listener l : listeners) l.onContent(); });
    }

    // MARK: - Kodi

    /** Fetch favourites and recently added via JSON-RPC. */
    private List<Item> fetchKodi() throws JSONException {
        List<Item> result = new ArrayList<>();
        try {
            // Favourites
            JSONObject favResp = kodiCall("Favourites.GetFavourites",
                    new JSONObject().put("properties", new JSONArray()
                            .put("title").put("thumbnail").put("type").put("path")));
            if (favResp != null) {
                JSONArray favs = favResp.optJSONArray("favourites");
                if (favs != null) {
                    for (int i = 0; i < favs.length(); i++) {
                        JSONObject f = favs.getJSONObject(i);
                        Item it = new Item();
                        it.title = f.optString("title", "");
                        if (it.title.isEmpty()) continue;
                        String type = f.optString("type", "");
                        String path = f.optString("path", "");
                        it.subtitle = type;
                        it.action = "kodi:fav:" + path;
                        it.appPackage = "tv.kodi";   // standard Kodi Android package
                        String thumb = f.optString("thumbnail", "");
                        if (thumb.startsWith("image://")) {
                            it.imageUrl = KODI_IMAGE + encodeKodiPath(thumb.substring(8, thumb.length() - 1));
                        }
                        result.add(it);
                    }
                }
            }
            // Recently added movies (limit 5)
            JSONObject movResp = kodiCall("VideoLibrary.GetRecentlyAddedMovies",
                    new JSONObject().put("properties", new JSONArray()
                            .put("title").put("thumbnail").put("runtime")));
            if (movResp != null) {
                JSONArray movs = movResp.optJSONArray("movies");
                if (movs != null) {
                    for (int i = 0; i < Math.min(5, movs.length()); i++) {
                        JSONObject m = movs.getJSONObject(i);
                        Item it = new Item();
                        it.title = m.optString("title", "");
                        if (it.title.isEmpty()) continue;
                        it.subtitle = "Movie";
                        it.action = "kodi:movie:" + m.optInt("movieid", 0);
                        it.appPackage = "tv.kodi";
                        String thumb = m.optString("thumbnail", "");
                        if (thumb.startsWith("image://")) {
                            it.imageUrl = KODI_IMAGE + encodeKodiPath(thumb.substring(8, thumb.length() - 1));
                        }
                        result.add(it);
                    }
                }
            }
            // Recently added episodes (limit 5)
            JSONObject epResp = kodiCall("VideoLibrary.GetRecentlyAddedEpisodes",
                    new JSONObject().put("properties", new JSONArray()
                            .put("title").put("thumbnail").put("tvshow").put("season").put("episode")));
            if (epResp != null) {
                JSONArray eps = epResp.optJSONArray("episodes");
                if (eps != null) {
                    for (int i = 0; i < Math.min(5, eps.length()); i++) {
                        JSONObject ep = eps.getJSONObject(i);
                        Item it = new Item();
                        String show = ep.optString("tvshow", "");
                        String epTitle = ep.optString("title", "");
                        int season = ep.optInt("season", 0);
                        int episode = ep.optInt("episode", 0);
                        it.title = show + " — " + epTitle;
                        it.subtitle = "S" + season + "E" + episode;
                        it.action = "kodi:episode:" + ep.optInt("episodeid", 0);
                        it.appPackage = "tv.kodi";
                        String thumb = ep.optString("thumbnail", "");
                        if (thumb.startsWith("image://")) {
                            it.imageUrl = KODI_IMAGE + encodeKodiPath(thumb.substring(8, thumb.length() - 1));
                        }
                        result.add(it);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("content: kodi fetch error: " + e.getMessage());
        }
        return result;
    }

    /** Send a JSON-RPC call to Kodi and return the result object, or null on failure. */
    private JSONObject kodiCall(String method, JSONObject params) throws JSONException {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", method);
            req.put("params", params);
            req.put("id", 1);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL(KODI_RPC).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(body, 0, body.length);
            }
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                String resp = read(in);
                JSONObject jr = new JSONObject(resp);
                if (jr.has("result")) return jr.getJSONObject("result");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Encode a Kodi image:// path for HTTP access. */
    private static String encodeKodiPath(String path) {
        // Kodi uses a path like "nfs://server/path/file.jpg" or "smb://..."
        // The HTTP image API needs the path URL-encoded.
        try { return java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20"); } catch (Exception e) { return path; }
    }

    // MARK: - SmartTube

    /** Try to read SmartTube subscriptions from its data file. */
    private List<Item> fetchSmartTube() {
        List<Item> result = new ArrayList<>();
        try {
            // SmartTube on Android TV stores subscriptions in a JSON file
            File subsFile = new File(SMARTTUBE_PATH + SMARTTUBE_SUBS);
            if (!subsFile.exists()) {
                // Try alternate paths
                subsFile = new File(context.getFilesDir().getParent(), "tv.stick.smarttube/files/subscriptions.json");
                if (!subsFile.exists()) {
                    subsFile = new File("/storage/emulated/0/smarttube/data/subscriptions.json");
                    if (!subsFile.exists()) return result;
                }
            }
            try (FileInputStream in = new FileInputStream(subsFile)) {
                String data = read(in);
                JSONObject root = new JSONObject(data);
                JSONArray subs = root.optJSONArray("subscriptions");
                if (subs == null) {
                    // Maybe the file is a plain array or has a different structure
                    subs = new JSONArray(data);
                }
                for (int i = 0; i < Math.min(5, subs.length()); i++) {
                    JSONObject sub = subs.getJSONObject(i);
                    Item it = new Item();
                    it.title = sub.optString("name", sub.optString("title", ""));
                    if (it.title.isEmpty()) continue;
                    it.subtitle = "YouTube";
                    it.action = "smarttube:channel:" + sub.optString("id", sub.optString("channelId", ""));
                    it.appPackage = "tv.stick.smarttube";
                    // SmartTube doesn't expose thumbnails in the subs file; we use a placeholder
                    it.imageUrl = "";
                    result.add(it);
                }
            }
        } catch (Exception e) {
            AppLog.w("content: smarttube error: " + e.getMessage());
        }
        return result;
    }

    // MARK: - Images

    /** Lazy-fetch a poster thumbnail from Kodi or use a placeholder. */
    Bitmap fetchPoster(Item it) {
        if (it.imageUrl == null || it.imageUrl.isEmpty()) return null;
        if (images.containsKey(it.title)) return images.get(it.title);
        if (pending.contains(it.title)) return null;
        pending.add(it.title);
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(it.imageUrl).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    File tmp = new File(context.getCacheDir(), "content_" + it.title.hashCode() + ".jpg");
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(tmp.getPath(), opts);
                    opts.inJustDecodeBounds = false;
                    while (opts.outWidth / (opts.inSampleSize * 2) > 320) opts.inSampleSize *= 2;
                    Bitmap bmp = BitmapFactory.decodeFile(tmp.getPath(), opts);
                    if (bmp != null) {
                        images.put(it.title, bmp);
                        main.post(() -> { for (Listener l : listeners) l.onContent(); });
                    }
                    tmp.delete();
                }
            } catch (Exception ignored) {
            } finally {
                pending.remove(it.title);
            }
        });
        return null;
    }

    // MARK: - Item helpers

    /** Open a content item in its app. Returns the Android package to open, or empty to use the app directly. */
    static String open(Context c, Item it) {
        if (it == null || it.appPackage == null || it.appPackage.isEmpty()) return "";
        if (!it.action.startsWith("kodi:")) return it.appPackage;
        // For Kodi items, open the Kodi app directly (handles its own deep-linking internally)
        return it.appPackage;
    }

    private static JSONObject encode(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", it.title);
            o.put("subtitle", it.subtitle);
            o.put("imageUrl", it.imageUrl);
            o.put("appPackage", it.appPackage);
            o.put("action", it.action);
        } catch (Exception ignored) {}
        return o;
    }

    private static List<Item> parseList(JSONArray arr) {
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o;
            try { o = arr.getJSONObject(i); } catch (Exception e) { continue; }
            Item it = new Item();
            it.title = o.optString("title", "");
            it.subtitle = o.optString("subtitle", "");
            it.imageUrl = o.optString("imageUrl", "");
            it.appPackage = o.optString("appPackage", "");
            it.action = o.optString("action", "");
            list.add(it);
        }
        return list;
    }

    private static String read(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }


}