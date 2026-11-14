package com.kartikmodi.halo.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Assistant's way into the TV: a small HTTP server on port 8765 of the TV's Wi-Fi address.
 *
 * The halo_tv integration polls /api/ha/state every few seconds and posts to /api/ha/command and
 * /api/ha/notify. The parent's viewing-time page uses /api/tvtime. Runs in the launcher's process,
 * which HomeKeyService keeps alive, and starts once per process.
 */
final class HaServer {

    static final int PORT = 8765;
    private static final String TAG = "HaServer";
    private static HaServer instance;

    private final Context app;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    static synchronized void ensureStarted(Context context) {
        if (instance != null) return;
        instance = new HaServer(context.getApplicationContext());
        new Thread(instance::serve, "HaServer").start();
    }

    private HaServer(Context app) {
        this.app = app;
    }

    private void serve() {
        // A dropped socket or a port still held by the previous process should not leave the TV
        // unreachable until someone opens Home again, so this retries instead of giving up.
        int failures = 0;
        while (true) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                failures = 0;
                while (true) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                failures++;
                Log.w(TAG, "server stopped, retry " + failures, e);
            }
            if (failures > 20) {
                synchronized (HaServer.class) { instance = null; }
                return;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---- HTTP plumbing: just enough HTTP/1.1 for one request per connection.

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            int limit = limit(target);
            if (contentLength > limit) {
                send(socket.getOutputStream(), 413, "text/plain", "Too large".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
            route(socket.getOutputStream(), method, path, query, new String(body, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) break;
        }
        if (c < 0 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void sendJson(OutputStream out, Object json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Request bodies are tiny except a message, which may carry a camera snapshot inline. */
    private static int limit(String target) {
        return target.startsWith("/api/ha/notify") ? 12 * 1024 * 1024 : 64 * 1024;
    }

    // ---- Routes

    private void route(OutputStream out, String method, String path, Map<String, String> query, String body)
            throws IOException, JSONException {
        if ("OPTIONS".equals(method)) {   // a browser (or curl -X OPTIONS) asking what it may send
            send(out, 200, "text/plain", new byte[0]);
            return;
        }
        switch (path) {
            case "/":
                send(out, 200, "text/plain; charset=utf-8",
                        ("Halo TV\n\n"
                                + "GET  /api/ha/info     what this TV is\n"
                                + "GET  /api/ha/state    what it is doing\n"
                                + "POST /api/ha/notify   {\"message\": \"...\", \"text\": \"...\", \"image\": \"url\"}\n"
                                + "POST /api/ha/command  {\"action\": \"pause|screen_off|volume_set|home|...\"}\n"
                                + "GET  /api/tvtime      today's viewing time\n").getBytes(StandardCharsets.UTF_8));
                return;
            // ---- Home Assistant: what the TV is doing, a message for the screen, and a command.
            case "/api/ha/info":
                sendJson(out, HaBridge.info(app));
                return;
            case "/api/ha/state":
                sendJson(out, HaBridge.state(app));
                return;
            case "/api/ha/notify":
                if (!"POST".equals(method)) break;
                sendJson(out, HaBridge.notify(app, new JSONObject(body.isEmpty() ? "{}" : body)));
                return;
            case "/api/ha/command":
                if (!"POST".equals(method)) break;
                sendJson(out, HaBridge.command(app, new JSONObject(body.isEmpty() ? "{}" : body)));
                return;
            // ---- Viewing time: today's count, the thresholds, and a parent's one-off allowance.
            case "/api/tvtime":
            case "/api/tvtime/status":
                sendJson(out, ViewingTime.status(app));
                return;
            case "/api/tvtime/config":
                if (!"POST".equals(method)) break;
                sendJson(out, ViewingTime.configure(app, new JSONObject(body.isEmpty() ? "{}" : body)));
                return;
            case "/api/tvtime/reset":
                if (!"POST".equals(method)) break;
                sendJson(out, ViewingTime.reset(app));
                return;
            case "/api/tvtime/adjust":
                if (!"POST".equals(method)) break;
                sendJson(out, ViewingTime.adjust(app, new JSONObject(body.isEmpty() ? "{}" : body)));
                return;
            default:
                break;
        }
        send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }
}
