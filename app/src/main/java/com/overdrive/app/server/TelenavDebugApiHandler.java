package com.overdrive.app.server;

import com.overdrive.app.telenav.TelenavIpcServer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * READ-ONLY spike endpoint for Telenav's exported user-data AIDL. The daemon
 * (uid-2000, synthetic ActivityThread) cannot {@code bindService}, so this handler
 * forwards to {@link TelenavIpcServer} in the app process over
 * {@code 127.0.0.1:19878}, which does the bind and returns the JSON.
 *
 * <p>{@code GET /api/debug/telenav/favorites} — read all favourite buckets + recent.
 */
public final class TelenavDebugApiHandler {

    private TelenavDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path;
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) pathOnly = path.substring(0, qIdx);

        // Accept the stable /api/telenav/* path and the older /api/debug/telenav/*.
        String p = pathOnly;
        if (p.startsWith("/api/debug/telenav/")) p = "/api/telenav/" + p.substring("/api/debug/telenav/".length());

        final JSONObject req;
        if (p.equals("/api/telenav/favorites")) {
            if (!"GET".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            req = new JSONObject().put("op", "getFavorites");
        } else if (p.equals("/api/telenav/addFavorite") || p.equals("/api/telenav/navigate")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            JSONObject in = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String op = p.endsWith("/navigate") ? "navigate" : "addFavorite";
            req = new JSONObject()
                    .put("op", op)
                    .put("favoriteType", in.optString("favoriteType", "Normal"))
                    .put("name", in.optString("name", ""))
                    .put("lat", in.getDouble("lat"))
                    .put("lng", in.getDouble("lng"))
                    .put("formattedAddress", in.optString("formattedAddress", in.optString("name", "")));
        } else {
            HttpResponse.sendError(out, 404, "Unknown telenav endpoint");
            return true;
        }

        String resp = forwardToApp(req.toString(), 25_000);
        if (resp == null) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", "app IPC unreachable on 127.0.0.1:" + TelenavIpcServer.PORT
                    + " (is the OverDrive app process running?)");
            HttpResponse.sendJson(out, 200, err.toString());
            return true;
        }
        HttpResponse.sendJson(out, 200, resp);
        return true;
    }

    /** One line of JSON to the app-process listener, one line back. */
    private static String forwardToApp(String requestLine, int readTimeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", TelenavIpcServer.PORT), 2000);
            socket.setSoTimeout(readTimeoutMs);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(requestLine);
            return reader.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignore) {}
        }
    }
}
