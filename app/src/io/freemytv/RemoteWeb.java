package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * The web remote: a tiny HTTP server (no dependencies) on the local network. A phone opens the address and gets a
 * remote with the D-pad, the text keyboard, the app launcher and an APK installer (the apps update from the phone).
 * Runs inside the remote-keys service's process, which the system keeps alive; the on/off switch and the pin live in
 * a small file (the keys' pattern: processes don't share preferences) that is re-read before every connection.
 */
final class RemoteWeb {
    static final int PORT = 8090;
    private static final String STATE_FILE = "remote-web.txt";

    private RemoteWeb() {}

    private static ServerSocket server;
    private static Thread thread;

    /** The switch, as the settings screen writes it: line 1 on/off, line 2 the pin (empty = no pin). */
    static void setEnabled(Context c, boolean on, String pin) {
        try (OutputStream out = new FileOutputStream(new File(c.getFilesDir(), STATE_FILE))) {
            out.write(((on ? "1" : "0") + "\n" + (pin == null ? "" : pin.trim()) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** Reads the switch: [on, pin]. */
    private static String[] state(Context c) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(new File(c.getFilesDir(), STATE_FILE)), StandardCharsets.UTF_8))) {
            String on = r.readLine(), pin = r.readLine();
            return new String[] {on == null ? "0" : on.trim(), pin == null ? "" : pin.trim()};
        } catch (IOException e) {
            return new String[] {"0", ""};
        }
    }

    /** Starts the loop (once): it listens only while the switch is on. */
    static synchronized void start(Context app) {
        if (thread != null && thread.isAlive()) return;
        final Context c = app.getApplicationContext();
        thread = new Thread(() -> {
            while (true) {
                try {
                    if (!"1".equals(state(c)[0])) {
                        if (server != null) {
                            try {
                                server.close();
                            } catch (IOException ignored) {
                            }
                            server = null;
                        }
                        Thread.sleep(1000);
                        continue;
                    }
                    if (server == null) {
                        server = new ServerSocket(PORT);
                        server.setSoTimeout(1000);
                    }
                    try (Socket s = server.accept()) {
                        serve(c, s);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        AppLog.w("remote web: " + e.getMessage());
                    }
                } catch (IOException | RuntimeException e) {
                    AppLog.w("remote web: " + e.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "remote-web");
        thread.setDaemon(true);
        thread.start();
    }

    /** The address to show in the settings (the first site-local IPv4). */
    static String address() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs = ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isSiteLocalAddress() && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {   // SocketException from getNetworkInterfaces()
        }
        return "0.0.0.0";
    }

    // MARK: - One connection

    private static void serve(Context c, Socket s) throws IOException {
        s.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null || !line.startsWith("GET ") && !line.startsWith("POST ")) return;
        String[] parts = line.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        int length = 0;
        String header;
        while ((header = in.readLine()) != null && !header.isEmpty()) {
            String h = header.toLowerCase(java.util.Locale.ROOT);
            if (h.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        byte[] body = new byte[0];
        if (length > 0 && length < 128 << 20) {
            body = new byte[length];
            int at = 0;
            while (at < length) {
                int n = s.getInputStream().read(body, at, length - at);
                if (n < 0) break;
                at += n;
            }
        }
        String pin = state(c)[1];
        if (!pin.isEmpty() && !pin.equals(queryValue(query, "pin"))) {
            respond(s, "403 Forbidden", "text/plain", "pin errato");
            return;
        }
        switch (path) {
            case "/":
                respond(s, "200 OK", "text/html; charset=utf-8", page(c));
                break;
            case "/api/apps":
                respond(s, "200 OK", "application/json", appsJson(c));
                break;
            case "/api/key": {
                String code = queryValue(query, "code");
                if (code == null || !code.matches("[0-9]+")) {
                    respond(s, "400 Bad Request", "text/plain", "?");
                    return;
                }
                adb(c, "input keyevent " + code);
                respond(s, "200 OK", "text/plain", "ok");
                break;
            }
            case "/api/text": {
                String t = queryValue(query, "t");
                if (t == null) {
                    respond(s, "400 Bad Request", "text/plain", "?");
                    return;
                }
                t = URLDecoder.decode(t, "UTF-8").replaceAll("['\n\r\\\\]", "");   // shell-safe
                if (!t.isEmpty()) adb(c, "input text '" + t + "'");
                respond(s, "200 OK", "text/plain", "ok");
                break;
            }
            case "/api/launch": {
                String pkg = queryValue(query, "pkg");
                if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) {
                    respond(s, "400 Bad Request", "text/plain", "?");
                    return;
                }
                PackageManager pm = c.getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                respond(s, "200 OK", "text/plain", i == null ? "non installata" : "ok");
                break;
            }
            case "/api/install": {
                File apk = new File(c.getExternalFilesDir(null), "upload.apk");
                try (OutputStream out = new FileOutputStream(apk)) {
                    out.write(body);
                }
                String out = adb(c, "pm install -r " + apk.getAbsolutePath());
                respond(s, "200 OK", "text/plain",
                        out.contains("Success") ? "installata" : out.trim().isEmpty() ? "?" : out.trim());
                break;
            }
            default:
                respond(s, "404 Not Found", "text/plain", "?");
        }
    }

    private static String queryValue(String query, String name) {
        for (String p : query.split("&")) {
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    private static void respond(Socket s, String status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.0 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /** Runs a shell command through the app's own adb connection (the same one the settings use). */
    private static String adb(Context c, String command) {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            return adb.shell(command);
        } catch (IOException e) {
            return "adb: " + e.getMessage();
        }
    }

    private static String appsJson(Context c) {
        StringBuilder b = new StringBuilder("[");
        List<String> done = new ArrayList<>();
        for (String[] app : HomeApps.launchable(c)) {
            if (done.contains(app[0])) continue;
            done.add(app[0]);
            if (b.length() > 1) b.append(',');
            b.append("{\"pkg\":\"").append(app[0].replace("\"", ""))
                    .append("\",\"label\":\"").append(app[1].replace("\"", "")).append("\"}");
        }
        return b.append(']').toString();
    }

    // MARK: - The page

    private static String page(Context c) {
        return "<!DOCTYPE html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Free My TV</title><style>"
                + "body{background:#111;color:#eee;font-family:sans-serif;text-align:center;margin:0;padding:12px}"
                + "button{background:#333;color:#eee;border:0;border-radius:14px;padding:16px;font-size:20px;"
                + "min-width:64px;margin:4px}button:active{background:#666}"
                + "input{font-size:20px;padding:10px;border-radius:10px;border:0;width:70%}"
                + ".row{margin:6px 0}.app{font-size:15px;padding:10px;min-width:40%}"
                + "</style></head><body>"
                + "<h3>Free My TV</h3>"
                + "<div class=row><input id=t placeholder='testo (cerca, url…)'>"
                + "<button onclick=\"txt()\">↵</button></div>"
                + "<div class=row><button onclick=\"k(24)\">Vol+</button>"
                + "<button onclick=\"k(25)\">Vol−</button><button onclick=\"k(164)\">Mute</button></div>"
                + "<div class=row><button onclick=\"k(4)\">←</button>"
                + "<button onclick=\"k(3)\">⌂</button></div>"
                + "<div class=row><button onclick=\"k(19)\">▲</button></div>"
                + "<div class=row><button onclick=\"k(21)\">◀</button>"
                + "<button onclick=\"k(23)\">OK</button><button onclick=\"k(22)\">▶</button></div>"
                + "<div class=row><button onclick=\"k(20)\">▼</button></div>"
                + "<h4>App</h4><div id=apps></div>"
                + "<script>var Q=location.search?('&'+location.search.slice(1)):'';"
                + "function k(c){fetch('/api/key?code='+c+Q,{method:'POST'})}"
                + "function txt(){var t=document.getElementById('t').value;"
                + "fetch('/api/text?t='+encodeURIComponent(t)+Q,{method:'POST'})}"
                + "function go(p){fetch('/api/launch?pkg='+p+Q,{method:'POST'})}"
                + "fetch('/api/apps?'+Q.slice(1)).then(function(r){return r.json()}).then(function(l){"
                + "var d=document.getElementById('apps');"
                + "l.forEach(function(a){var b=document.createElement('button');b.className='app';"
                + "b.textContent=a.label;b.onclick=function(){go(a.pkg)};d.appendChild(b)})});"
                + "</script></body></html>";
    }
}
