package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The home screen's random photos, downloaded without any key (Settings > Home Settings > Random backgrounds): Lorem
 * Picsum's curated photos (Unsplash editorial collection, safe for work), requested at the TV's UI size, one every
 * ROTATE_MS while the home screen is showing. The last download stays on disk: with the TV offline the last photo
 * stays the background and a new one is tried every RETRY_MS. Nothing runs in the draw path: like the recommendation
 * photos, each one is decoded and blurred (Backdrop.make) on a background thread and shown with the usual cross-fade.
 */
final class Wallpapers {
    /** No key: only the size and ?random=N (any other key-less source fits in this one line). */
    private static final String SOURCE = "https://picsum.photos/{w}/{h}?random={n}";
    private static final long ROTATE_MS = 15 * 60_000L;   // a new photo every 15 minutes
    private static final long RETRY_MS = 5 * 60_000L;     // offline: try again five minutes later

    private final Context app;
    private final Handler main;
    private final Consumer<Backdrop> onReady;
    private final Runnable onOff;

    private volatile Backdrop ready;      // the photo to show (null until the first download finishes)
    private volatile boolean building;
    private volatile long nextAt;         // uptime of the next attempt (0 = due now)
    private boolean wasOn;
    private int seq = new Random().nextInt(1_000_000);

    Wallpapers(Context app, Handler main, Consumer<Backdrop> onReady, Runnable onOff) {
        this.app = app;
        this.main = main;
        this.onReady = onReady;
        this.onOff = onOff;
    }

    /** The background photo (null with the setting off or before the first one arrives). */
    Backdrop current() {
        return Prefs.wallpapers() ? ready : null;
    }

    /** Called from the home screen's tick (every 15 s) and on resume; nothing runs while the setting is off. */
    void tick() {
        boolean on = Prefs.wallpapers();
        if (on != wasOn) {
            wasOn = on;
            if (!on) onOff.run();                       // back to the picks' photo or the gradient
            else if (ready != null) {                   // back on: the last photo shows at once
                final Backdrop b = ready;
                main.post(() -> onReady.accept(b));
            } else nextAt = 0;
        }
        if (!on || building || SystemClock.uptimeMillis() < nextAt) return;
        building = true;
        new Thread(this::buildNext, "wallpaper").start();
    }

    /** Downloads the photo, then decodes and blurs it (background thread; posted to the main one when ready). */
    private void buildNext() {
        File f = new File(app.getCacheDir(), "wallpaper.jpg");
        try {
            try {
                download(f);
            } catch (IOException e) {
                if (ready != null) {                    // offline: the last photo stays on screen
                    AppLog.w("wallpaper: offline (" + e.getMessage() + "), keeping the last one");
                    nextAt = SystemClock.uptimeMillis() + RETRY_MS;
                    return;
                }
                if (!f.exists()) {                      // offline and never downloaded: nothing to show yet
                    AppLog.w("wallpaper: offline with no photo yet: " + e.getMessage());
                    nextAt = SystemClock.uptimeMillis() + RETRY_MS;
                    return;
                }
                AppLog.w("wallpaper: offline, using the saved photo");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
            if (bmp == null) throw new IOException("unreadable photo");
            Backdrop b = Backdrop.make(bmp, true);
            ready = b;
            nextAt = SystemClock.uptimeMillis() + ROTATE_MS;
            AppLog.w("wallpaper: " + bounds.outWidth + "×" + bounds.outHeight + " downloaded");
            main.post(() -> onReady.accept(b));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            nextAt = SystemClock.uptimeMillis() + RETRY_MS;
            AppLog.w("wallpaper: " + e.getMessage() + " (the photo stays)");
        } finally {
            building = false;
        }
    }

    /** The photo to a temp file, then over the old one (the same pattern as Recs' downloads). */
    private void download(File f) throws IOException {
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the photo");
    }

    /** A different photo every time (the counter also keeps the TV's own cache from serving the same one). */
    private String url() {
        return SOURCE.replace("{w}", String.valueOf(Backdrop.W)).replace("{h}", String.valueOf(Backdrop.H))
                .replace("{n}", String.valueOf(seq++));
    }
}
