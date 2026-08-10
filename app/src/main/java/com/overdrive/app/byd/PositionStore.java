package com.overdrive.app.byd;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Named seat/mirror geometry store — the OverDrive-native "seat positions" a user
 * builds up beyond BYD's fixed 3 slots. Persisted as JSON at
 * {@code /data/local/tmp/seat_positions.json} so BOTH the uid-2000 daemon (which
 * writes it, from the capture endpoint) and the app UI process (which will read it
 * for the management screen + automation picker) can see it — neither filesDir nor
 * SharedPreferences is shared across those uids. Same storage discipline as
 * {@link com.overdrive.app.surveillance.SafeLocationManager}: atomic tmp+rename,
 * then world readable/writable.
 *
 * <p>Schema:
 * <pre>{ "version":1, "positions":[ {
 *     "id":       "&lt;profile&gt;-slot-1" (captured) | "user-&lt;slug&gt;" (user-created),
 *     "name":     "Posisjon 1",
 *     "profile":  "paa*****@gmail.com",   // captured only
 *     "slot":     1,            // native DiLink slot for captured entries; absent for user-added
 *     "source":   "captured" | "user",
 *     "createdAt": &lt;epoch ms&gt;,
 *     "updatedAt": &lt;epoch ms&gt;,   // user entries, set when the geometry is re-saved
 *     "axes":     { "HORIZONTAL":52, "BACKREST":56, ..., "LEFT_H":31, ... }
 * } ] }</pre>
 *
 * <p>Ids are stable for the life of the entry. Automations reference a position by id, so a
 * rename must not change it, and re-capturing a native slot must land on the same id.
 *
 * <p>Captured entries are keyed by native slot (1..3) and UPSERTED, so re-saving a
 * native position updates OverDrive's mirror of it rather than piling up duplicates.
 * Unlimited arbitrary named positions (source "user") come from the management UI later.
 */
public final class PositionStore {

    private static final String TAG = "PositionStore";
    public static final String STORE_FILE = "/data/local/tmp/seat_positions.json";
    private static final int VERSION = 1;

    private static final Object LOCK = new Object();
    private static volatile PositionStore instance;

    private PositionStore() {}

    public static PositionStore getInstance() {
        if (instance == null) {
            synchronized (PositionStore.class) {
                if (instance == null) instance = new PositionStore();
            }
        }
        return instance;
    }

    /** Load the store, tolerating a missing/corrupt file (returns an empty {version, positions:[]}). */
    private JSONObject load() {
        try {
            File f = new File(STORE_FILE);
            if (f.exists()) {
                String txt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                JSONObject root = new JSONObject(txt);
                if (!root.has("positions")) root.put("positions", new JSONArray());
                return root;
            }
        } catch (Throwable t) {
            log("load failed (starting empty): " + t);
        }
        JSONObject root = new JSONObject();
        try { root.put("version", VERSION); root.put("positions", new JSONArray()); } catch (Throwable ignored) {}
        return root;
    }

    /** Atomic write (tmp+rename) + world readable/writable, matching SafeLocationManager. */
    private void save(JSONObject root) {
        try {
            root.put("version", VERSION);
            File tmp = new File(STORE_FILE + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) { w.write(root.toString(2)); }
            File target = new File(STORE_FILE);
            if (!tmp.renameTo(target)) {
                try (FileWriter w = new FileWriter(target)) { w.write(root.toString(2)); }
                tmp.delete();
            }
            target.setReadable(true, false);
            target.setWritable(true, false);
        } catch (Throwable t) {
            log("save failed: " + t);
        }
    }

    /** All positions, as a JSON array (never null). */
    public JSONArray list() {
        synchronized (LOCK) {
            return load().optJSONArray("positions");
        }
    }

    /** Look up a position by id, or null. */
    public JSONObject getById(String id) {
        if (id == null) return null;
        JSONArray arr = list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    /**
     * Upsert a captured position keyed by (profile, native slot 1..3). BYD's Pos 1/2/3 are
     * per-logged-in-profile, so the same slot number means different geometry for different
     * accounts — the key MUST include the profile. Overwrites the existing captured entry for
     * that (profile, slot). Returns the stored entry.
     *
     * @param profile the DiLink account nickName (from content://com.byd.accountProvider), or
     *                "default" when unknown; identifies which profile these slots belong to.
     * @param name    display name, e.g. "&lt;profile&gt; - Posisjon 1".
     */
    public JSONObject upsertCaptured(String profile, int slot, String name, JSONObject axes, long nowMs) {
        synchronized (LOCK) {
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("positions");
            String prof = (profile == null || profile.trim().isEmpty()) ? "default" : profile.trim();
            String id = sanitize(prof) + "-slot-" + slot;
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", id);
                entry.put("name", name != null ? name : (prof + " - Posisjon " + slot));
                entry.put("profile", prof);
                entry.put("slot", slot);
                entry.put("source", "captured");
                entry.put("createdAt", nowMs);
                entry.put("axes", axes != null ? axes : new JSONObject());
                // Replace any existing captured entry for this slot.
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null && !id.equals(p.optString("id"))) next.put(p);
                }
                next.put(entry);
                root.put("positions", next);
                save(root);
            } catch (Throwable t) {
                log("upsertCaptured failed: " + t);
            }
            return entry;
        }
    }

    /**
     * Create a user-owned position from the geometry passed in. Unlike captured entries there
     * is no natural key, so the id is derived from the name and then made unique by suffix.
     * It is deliberately NOT re-derived on rename: automations reference positions by id, so
     * the id has to outlive whatever the entry is called (mirrors how AppType stores a package
     * name rather than an app label). Charset is the sanitize() output, [a-z0-9_-], which is
     * what the automation-side value validator accepts.
     *
     * @return the stored entry, or null if the name is empty or over-long.
     */
    public JSONObject createUser(String name, JSONObject axes, long nowMs) {
        String clean = (name == null) ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 60) return null;
        synchronized (LOCK) {
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("positions");
            String base = "user-" + sanitize(clean);
            String id = base;
            for (int n = 2; indexOf(arr, id) >= 0; n++) id = base + "-" + n;
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", id);
                entry.put("name", clean);
                entry.put("source", "user");
                entry.put("createdAt", nowMs);
                entry.put("axes", axes != null ? axes : new JSONObject());
                arr.put(entry);
                save(root);
            } catch (Throwable t) {
                log("createUser failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * Replace the geometry of an existing USER entry, keeping its id and name. Captured entries
     * mirror the car and are rejected — their geometry only ever comes from a capture.
     *
     * @return the updated entry, or null if absent or captured.
     */
    public JSONObject updateAxes(String id, JSONObject axes, long nowMs) {
        return mutate(id, axes, null, nowMs);
    }

    /**
     * Rename a USER entry. The id is untouched, so automations pointing at it keep working.
     *
     * @return the updated entry, or null if absent, captured, or the name is empty/over-long.
     */
    public JSONObject rename(String id, String name) {
        String clean = (name == null) ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 60) return null;
        return mutate(id, null, clean, 0L);
    }

    /** Shared body of updateAxes/rename: find a user entry by id, apply what was passed, save. */
    private JSONObject mutate(String id, JSONObject axes, String name, long nowMs) {
        if (id == null) return null;
        synchronized (LOCK) {
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("positions");
            int i = indexOf(arr, id);
            if (i < 0) return null;
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null || !"user".equals(entry.optString("source"))) return null;
            try {
                if (axes != null) { entry.put("axes", axes); entry.put("updatedAt", nowMs); }
                if (name != null) entry.put("name", name);
                arr.put(i, entry);
                save(root);
            } catch (Throwable t) {
                log("mutate failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /** Index of the entry with this id, or -1. */
    private static int indexOf(JSONArray arr, String id) {
        if (arr == null || id == null) return -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return i;
        }
        return -1;
    }

    /** Remove a position by id. Returns true if something was removed. */
    public boolean remove(String id) {
        if (id == null) return false;
        synchronized (LOCK) {
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("positions");
            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) { removed = true; continue; }
                next.put(p);
            }
            if (removed) { try { root.put("positions", next); } catch (Throwable ignored) {} save(root); }
            return removed;
        }
    }

    /** Sanitize a profile string into an id-safe token (keep alphanumerics, collapse the rest to '_'). */
    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append((Character.isLetterOrDigit(c)) ? Character.toLowerCase(c) : '_');
        }
        return b.toString();
    }

    private void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
