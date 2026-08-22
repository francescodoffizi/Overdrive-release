package com.overdrive.app.telenav;

import android.content.Context;
import android.util.Log;

import com.telenav.app.external.constants.FavoriteType;
import com.telenav.app.external.model.search.Address;
import com.telenav.app.external.model.search.Place;
import com.telenav.app.external.model.userservice.UserDataResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * Localhost IPC listener that runs in the APP process and binds Telenav's OEM
 * AIDL on behalf of the daemon. The daemon's HTTP server ({@code :8080}) cannot
 * {@code bindService} itself — its synthetic {@code ActivityThread} isn't an
 * AMS-registered app process ("Unable to find app for caller … when binding
 * service") — so {@link TelenavDebugApiHandler} forwards Telenav requests here
 * over {@code 127.0.0.1:19878}, mirroring the app→daemon {@code DaemonIpcClient}
 * socket in reverse.
 *
 * <p>Protocol: one line of JSON request in, one line of JSON response out.
 * Started from {@code OverdriveApplication.onCreate} (main app process).
 */
public final class TelenavIpcServer {

    private static final String TAG = "TelenavIpc";
    public static final int PORT = 19878;

    // Query every bucket: null / "" (unfiltered) plus each named FavoriteType.
    private static final String[] TYPES = {
            null, "", "Home", "Work", "Normal", "School", "Gym", "Daycare", "Custom",
    };

    private static volatile boolean started = false;
    private static Context appCtx;

    private TelenavIpcServer() {}

    public static synchronized void start(Context ctx) {
        if (started) return;
        appCtx = ctx.getApplicationContext();
        Thread t = new Thread(TelenavIpcServer::serve, "telenav-ipc");
        t.setDaemon(true);
        t.start();
        started = true;
    }

    private static void serve() {
        ServerSocket server = null;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket client = server.accept();
                handle(client);
            }
        } catch (Exception e) {
            // Most likely another process already bound the port (multi-process); harmless.
            Log.w(TAG, "server not running: " + e.getMessage());
        } finally {
            if (server != null) try { server.close(); } catch (Exception ignore) {}
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(30_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            String line = reader.readLine();
            JSONObject resp;
            try {
                JSONObject req = (line == null || line.isEmpty())
                        ? new JSONObject() : new JSONObject(line);
                resp = dispatch(req);
            } catch (Exception e) {
                resp = new JSONObject();
                try {
                    resp.put("success", false);
                    resp.put("error", String.valueOf(e.getMessage()));
                } catch (JSONException ignore) {}
            }
            writer.println(resp.toString());
        } catch (Exception e) {
            Log.e(TAG, "handle failed: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignore) {}
        }
    }

    private static JSONObject dispatch(JSONObject req) throws Exception {
        String op = req.optString("op", "");
        if ("getFavorites".equals(op)) {
            return getFavorites();
        }
        if ("addFavorite".equals(op)) {
            return addFavorite(req);
        }
        JSONObject o = new JSONObject();
        o.put("success", false);
        o.put("error", "unknown op: " + op);
        return o;
    }

    private static JSONObject addFavorite(JSONObject req) throws Exception {
        JSONObject o = new JSONObject();
        if (appCtx == null) {
            o.put("success", false);
            o.put("error", "no app context");
            return o;
        }
        final String type = req.optString("favoriteType", FavoriteType.Normal);
        final String name = req.optString("name", "");
        final double lat = req.getDouble("lat");
        final double lng = req.getDouble("lng");
        final String formattedAddress = req.optString("formattedAddress", name);

        final Place place = new Place();
        place.setPlaceName(name);
        place.setPlaceDisplayLabel(name);
        place.setPlaceType("ADDRESS");
        place.setFavoriteType(type);
        place.setGeoLatitude(lat);
        place.setGeoLongitude(lng);
        place.setNavLatitude(lat);
        place.setNavLongitude(lng);
        Address addr = new Address();
        addr.setFormattedAddress(formattedAddress);
        addr.setFullAddress(formattedAddress);
        place.setAddress(addr);

        // Read back the bucket after adding so we can confirm it landed + its type.
        JSONObject readback = TelenavClient.withUserData(appCtx, 20_000, svc -> {
            svc.addFavorite(type, place);
            JSONObject rb = new JSONObject();
            try {
                UserDataResult r = svc.getFavorites(type);
                rb.put("bucketType", type);
                rb.put("maxCount", r == null ? -1 : r.getMaxCount());
                rb.put("places", placesToJson(r == null ? null : r.getData()));
            } catch (Exception e) {
                rb.put("readbackError", String.valueOf(e.getMessage()));
            }
            return rb;
        });
        o.put("success", true);
        o.put("wrote", new JSONObject()
                .put("favoriteType", type).put("name", name).put("lat", lat).put("lng", lng));
        o.put("readback", readback);
        return o;
    }

    private static JSONObject getFavorites() throws Exception {
        if (appCtx == null) {
            JSONObject o = new JSONObject();
            o.put("success", false);
            o.put("error", "no app context");
            return o;
        }
        JSONObject result = TelenavClient.withUserData(appCtx, 20_000, svc -> {
            JSONObject o = new JSONObject();
            JSONArray buckets = new JSONArray();
            for (String type : TYPES) {
                JSONObject b = new JSONObject();
                b.put("queryType", type == null ? JSONObject.NULL : type);
                try {
                    UserDataResult r = svc.getFavorites(type);
                    b.put("resultType", r == null ? JSONObject.NULL : r.getType());
                    b.put("maxCount", r == null ? -1 : r.getMaxCount());
                    b.put("places", placesToJson(r == null ? null : r.getData()));
                } catch (Exception e) {
                    b.put("error", String.valueOf(e.getMessage()));
                }
                buckets.put(b);
            }
            o.put("favorites", buckets);
            try {
                UserDataResult recent = svc.getRecent();
                JSONObject rc = new JSONObject();
                rc.put("maxCount", recent == null ? -1 : recent.getMaxCount());
                rc.put("places", placesToJson(recent == null ? null : recent.getData()));
                o.put("recent", rc);
            } catch (Exception e) {
                o.put("recentError", String.valueOf(e.getMessage()));
            }
            return o;
        });
        result.put("success", true);
        return result;
    }

    private static JSONArray placesToJson(List<Place> places) throws JSONException {
        JSONArray arr = new JSONArray();
        if (places == null) return arr;
        for (Place p : places) {
            if (p == null) continue;
            JSONObject j = new JSONObject();
            try {
                j.put("placeName", p.getPlaceName());
                j.put("displayLabel", p.getPlaceDisplayLabel());
                j.put("favoriteType", p.getFavoriteType());
                j.put("placeType", p.getPlaceType());
                j.put("placeId", p.getPlaceId());
                j.put("geoLat", p.getGeoLatitude());
                j.put("geoLng", p.getGeoLongitude());
                j.put("navLat", p.getNavLatitude());
                j.put("navLng", p.getNavLongitude());
                Address a = p.getAddress();
                if (a != null) j.put("formattedAddress", a.getFormattedAddress());
            } catch (Throwable t) {
                j.put("parseError", String.valueOf(t.getMessage()));
            }
            arr.put(j);
        }
        return arr;
    }
}
