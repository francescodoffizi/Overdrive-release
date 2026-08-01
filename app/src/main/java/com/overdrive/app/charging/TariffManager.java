package com.overdrive.app.charging;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.SafeLocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Location-aware electricity tariffs — CRUD + the match that prices a session.
 *
 * <p><b>Why this exists.</b> Before this, charging cost came from exactly two
 * numbers: a global per-kWh rate and one optional DC rate. That is wrong the
 * moment a driver charges in more than one place — home at an off-peak rate, an
 * office wallbox that's free, a motorway DC stall at 4× home. This class lets a
 * rate be attached to a <i>place</i>, so a charge at a known site is priced with
 * that site's tariff automatically and no one has to re-type a rate per session.
 *
 * <p><b>Resolution order</b> (see {@link #resolve(double, double, int)}):
 * <ol>
 *   <li>the nearest enabled profile whose circle contains the charge location
 *       and which prices this gun type — ties broken by <i>smallest radius</i>,
 *       so a specific "Basement L2" inside a broad "Home campus" wins;</li>
 *   <li>the user-pinned default profile, when one is set (covers charging at an
 *       unmapped site on a utility-wide tariff);</li>
 *   <li>{@code null} ⇒ the caller falls back to the global rate, i.e. exactly
 *       the pre-existing behaviour. Nothing regresses for a single-location user
 *       who never creates a profile.</li>
 * </ol>
 *
 * <p><b>Storage.</b> Profiles live in the {@code chargingAnalytics.tariffs} array
 * of {@code /data/local/tmp/overdrive_config.json} via
 * {@link UnifiedConfigManager}, alongside the other charging keys, so they ride
 * the existing config backup/restore with no new file to manage.
 * {@link UnifiedConfigManager#updateSection} MERGES the keys it is given into the
 * existing section under a cross-process file lock, so each writer only sends the
 * keys it owns and siblings are preserved.
 *
 * <p><b>Thread safety.</b> {@code profiles} is a volatile reference to an
 * IMMUTABLE list, replaced by a single atomic swap on every mutation; all writers
 * are {@code synchronized} and build-then-swap. Readers (one per session edge,
 * plus one per row serialized for the UI) take one volatile read, never block on a
 * UI edit, and can never observe a partially-rebuilt list. Do NOT mutate the
 * published list in place — it is unmodifiable and would throw at runtime.
 */
public class TariffManager {

    private static final String TAG = "TariffManager";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String SECTION = "chargingAnalytics";
    private static final String KEY_TARIFFS = "tariffs";
    private static final String KEY_DEFAULT_ID = "defaultTariffId";

    /** Cap the list so a runaway UI can't bloat the config file. */
    public static final int MAX_PROFILES = 40;

    private static volatile TariffManager instance;

    /**
     * Published profile list, replaced ATOMICALLY on every mutation.
     *
     * <p>Deliberately NOT a CopyOnWriteArrayList mutated in place: {@code clear()}
     * followed by {@code addAll()} publishes a zero-length array in between, and a
     * reader on the SoC sampler thread that lands in that window sees NO tariffs
     * and prices a real charging session at the global rate instead of its own —
     * silently wrong money, with no error. Readers take one volatile read of this
     * reference and always observe either the complete old list or the complete
     * new one. Every writer builds a fresh list and assigns once.
     */
    private volatile List<TariffProfile> profiles = Collections.emptyList();
    /** Profile applied when the charge location matches nothing. Empty = none. */
    private volatile String defaultTariffId = "";
    private volatile boolean loaded = false;
    /** Debounce window for persisting usage counters (see markUsed). */
    private static final long MARK_USED_FLUSH_MS = 5 * 60 * 1000L;
    private long lastUsageFlushMs = 0;
    private boolean usageFlushPending = false;

    private TariffManager() {}

    public static TariffManager getInstance() {
        if (instance == null) {
            synchronized (TariffManager.class) {
                if (instance == null) instance = new TariffManager();
            }
        }
        return instance;
    }

    /**
     * Load profiles from config. Safe to call repeatedly (the charging config
     * POST path calls it after every edit so the daemon-side view can't drift
     * from what the UI just wrote).
     */
    public synchronized void load() {
        try {
            JSONObject cfg = UnifiedConfigManager.loadConfig();
            JSONObject section = cfg != null ? cfg.optJSONObject(SECTION) : null;
            List<TariffProfile> parsed = new ArrayList<>();
            String defId = "";
            if (section != null) {
                defId = section.optString(KEY_DEFAULT_ID, "");
                JSONArray arr = section.optJSONArray(KEY_TARIFFS);
                if (arr != null) {
                    for (int i = 0; i < arr.length() && parsed.size() < MAX_PROFILES; i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) parsed.add(new TariffProfile(o));
                    }
                }
            }
            // Single atomic publish — never an empty intermediate state (see the
            // `profiles` field doc).
            profiles = Collections.unmodifiableList(parsed);
            // Drop a dangling default (profile deleted out from under it) so
            // resolve() can't keep pointing at a ghost.
            defaultTariffId = findById(defId) != null ? defId : "";
            loaded = true;
            logger.info("Loaded " + parsed.size() + " tariff profiles, default="
                    + (defaultTariffId.isEmpty() ? "none" : defaultTariffId));
        } catch (Exception e) {
            logger.error("Tariff load error: " + e.getMessage());
            loaded = true;   // don't retry-storm on a malformed file
        }
    }

    private void ensureLoaded() {
        // Double-checked: load() is synchronized, so without the inner re-test two
        // threads racing the first call would each run a full reload.
        if (!loaded) {
            synchronized (this) {
                if (!loaded) load();
            }
        }
    }

    /**
     * Persist profiles + default id into {@code chargingAnalytics}, preserving
     * every other key in that section (read-modify-write, since
     * {@link UnifiedConfigManager#updateSection} replaces the whole object).
     */
    private synchronized boolean save() {
        try {
            // Write a DELTA and let updateSection merge it. loadConfig() returns
            // the cached JSONObject itself, so mutating the section it hands back
            // would race any other writer holding the same nested instance
            // (ChargingConfig.save does the same read-modify-write). updateSection
            // already copies each key of `data` into the existing section under a
            // cross-process file lock, so siblings are preserved either way.
            JSONObject delta = new JSONObject();
            JSONArray arr = new JSONArray();
            for (TariffProfile p : profiles) arr.put(p.toJson());
            delta.put(KEY_TARIFFS, arr);
            delta.put(KEY_DEFAULT_ID, defaultTariffId);
            boolean ok = UnifiedConfigManager.updateSection(SECTION, delta);
            if (!ok) logger.error("Tariff save rejected by UnifiedConfigManager");
            return ok;
        } catch (Exception e) {
            logger.error("Tariff save error: " + e.getMessage());
            return false;
        }
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    /**
     * @return the created profile, or null when the cap is hit OR the write failed.
     *
     * <p>synchronized, and it rolls the in-memory list back if persistence fails:
     * otherwise the daemon would keep pricing charges with a tariff that isn't in
     * the config file, and the API would have reported success for it.
     */
    public synchronized TariffProfile add(String label, double lat, double lng, int radiusM,
                            double acRate, double dcRate, String currency) {
        ensureLoaded();
        List<TariffProfile> cur = profiles;
        if (cur.size() >= MAX_PROFILES) {
            logger.warn("Tariff cap reached (" + MAX_PROFILES + ")");
            return null;
        }
        TariffProfile p = new TariffProfile(label, lat, lng, radiusM, acRate, dcRate, currency);
        List<TariffProfile> next = new ArrayList<>(cur);
        next.add(p);
        String prevDefault = defaultTariffId;
        profiles = Collections.unmodifiableList(next);   // atomic publish
        // NOT auto-promoted to default. Auto-promoting the first profile meant that
        // adding one tariff silently made it the catch-all for every charge that
        // matched no circle — including every pre-v3 history row, whose
        // start_lat/start_lng default to 0. Combined with re-pricing that restated
        // the user's entire cost history from one innocuous action. The default is
        // now only ever set by an explicit tap on the star (setDefault).
        if (!save()) {
            profiles = cur;                 // roll back both, keep file and RAM in step
            defaultTariffId = prevDefault;
            return null;
        }
        logger.info("Added tariff '" + p.getLabel() + "' ac=" + acRate + " dc=" + dcRate
                + " @" + lat + "," + lng + " r=" + p.getRadiusMeters());
        return p;
    }

    /**
     * Partial update — only the keys present in {@code updates} are applied.
     *
     * <p>Applies the deltas to a COPY and swaps it in, rather than mutating the
     * published profile field-by-field: a reader on the SoC thread matching a
     * charge could otherwise observe a half-moved circle (new latitude with the
     * old longitude) and miss the charge it was meant to price.
     */
    public synchronized boolean update(String id, JSONObject updates) {
        ensureLoaded();
        List<TariffProfile> cur = profiles;
        int idx = -1;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).getId().equals(id)) { idx = i; break; }
        }
        if (idx < 0 || updates == null) return false;

        TariffProfile p = new TariffProfile(cur.get(idx).toJson());   // detached copy
        if (updates.has("label")) p.setLabel(updates.optString("label"));
        if (updates.has("lat")) p.setLatitude(updates.optDouble("lat"));
        if (updates.has("lng")) p.setLongitude(updates.optDouble("lng"));
        if (updates.has("radiusM")) p.setRadiusMeters(updates.optInt("radiusM"));
        if (updates.has("acRate")) p.setAcRate(updates.optDouble("acRate"));
        if (updates.has("dcRate")) p.setDcRate(updates.optDouble("dcRate"));
        if (updates.has("currency")) p.setCurrency(updates.optString("currency"));
        if (updates.has("enabled")) p.setEnabled(updates.optBoolean("enabled"));

        List<TariffProfile> next = new ArrayList<>(cur);
        next.set(idx, p);
        profiles = Collections.unmodifiableList(next);
        if (!save()) { profiles = cur; return false; }
        logger.info("Updated tariff '" + p.getLabel() + "'");
        return true;
    }

    public synchronized boolean remove(String id) {
        ensureLoaded();
        List<TariffProfile> cur = profiles;
        TariffProfile p = null;
        List<TariffProfile> next = new ArrayList<>(cur.size());
        for (TariffProfile c : cur) {
            if (c.getId().equals(id)) p = c; else next.add(c);
        }
        if (p == null) return false;

        String prevDefault = defaultTariffId;
        profiles = Collections.unmodifiableList(next);
        if (id.equals(defaultTariffId)) {
            // Clear the pin — do NOT promote a survivor. Auto-promotion silently
            // made an unrelated place the catch-all, and because the re-price that
            // follows a delete resolves the orphaned rows leniently, every session
            // the deleted tariff owned was restated at that other place's rate
            // (e.g. 40 home charges re-priced at a motorway DC rate). The pin is
            // only ever set by an explicit setDefault(), same as add().
            defaultTariffId = "";
        }
        if (!save()) { profiles = cur; defaultTariffId = prevDefault; return false; }
        logger.info("Removed tariff '" + p.getLabel() + "'");
        return true;
    }

    /** Pin the fallback profile. Pass "" to clear. */
    public synchronized boolean setDefault(String id) {
        ensureLoaded();
        String prev = defaultTariffId;
        if (id == null || id.isEmpty()) {
            defaultTariffId = "";
            if (!save()) { defaultTariffId = prev; return false; }
            return true;
        }
        if (findById(id) == null) return false;
        defaultTariffId = id;
        if (!save()) { defaultTariffId = prev; return false; }
        return true;
    }

    public String getDefaultId() {
        ensureLoaded();
        return defaultTariffId;
    }

    public List<TariffProfile> getProfiles() {
        ensureLoaded();
        return new ArrayList<>(profiles);
    }

    public TariffProfile findById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (TariffProfile p : profiles) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    public boolean isEmpty() {
        ensureLoaded();
        return profiles.isEmpty();
    }

    // ========================================================================
    // MATCHING
    // ========================================================================

    /**
     * The profile that should price a session charged at {@code (lat,lng)} with
     * the given tri-state DC verdict, or {@code null} to use the global rate.
     *
     * <p>Only profiles that actually price THIS gun type are considered: a
     * DC-only profile must not swallow an AC session and leave it at 0. Among
     * candidates containing the point, the SMALLEST radius wins — a specific
     * sub-site beats the broad campus circle it sits inside — with distance as
     * the tiebreak for equal-radius overlaps.
     *
     * <p>A {@code (0,0)} location means "no GPS fix at the charge edge"; we
     * cannot claim a match there, so it goes straight to the default. Callers
     * are expected to pass the session's own stored coordinates.
     */
    public TariffProfile resolve(double lat, double lng, int isDc) {
        TariffProfile inCircle = resolveInCircle(lat, lng, isDc);
        if (inCircle != null) return inCircle;

        // A rate the user SET is honoured for both AC and DC. rateFor() applies the
        // separate dcRate only when they bothered to enter one; otherwise the single
        // rate they typed covers every gun at that place. Entering one number must
        // not leave half their charges priced by something else.
        //
        // This is only reached when no circle matched, i.e. the user explicitly
        // PINNED this profile as the catch-all. A tariff is never auto-promoted to
        // default (see add()), so the global rate stays the fallback until they ask
        // otherwise.
        TariffProfile def = findById(defaultTariffId);
        if (def != null && def.isEnabled() && def.rateFor(isDc) > 0) return def;
        return null;
    }

    /**
     * Strict geofence match: the tariff whose circle actually CONTAINS
     * {@code (lat,lng)} and which prices this gun type, or {@code null}. Unlike
     * {@link #resolve} this never falls back to the pinned default.
     *
     * <p>Used where a match must be positively earned by location rather than
     * inherited — specifically when deciding whether a historical session that
     * was priced by the global rate may be ADOPTED by a tariff. Pre-v3 session
     * rows have {@code start_lat/start_lng} defaulted to 0, so the lenient
     * {@link #resolve} would hand every one of them to the default profile and
     * silently restate the whole cost history (see
     * {@code SocHistoryDatabase.repriceSessionsForTariff}).
     */
    public TariffProfile resolveInCircle(double lat, double lng, int isDc) {
        ensureLoaded();
        List<TariffProfile> snapshot = profiles;   // volatile read: never empty mid-reload
        if (snapshot.isEmpty()) return null;
        // (0,0) is the "no GPS fix at the charge edge" sentinel, not the Atlantic.
        if (lat == 0 && lng == 0) return null;

        TariffProfile best = null;
        double bestDist = Double.MAX_VALUE;
        for (TariffProfile p : snapshot) {
            if (!p.isEnabled()) continue;
            if (p.rateFor(isDc) <= 0) continue;   // doesn't price this gun
            double d = SafeLocationManager.haversine(lat, lng, p.getLatitude(), p.getLongitude());
            // Written as !(d <= r) rather than (d > r) so a NaN distance — from a
            // hand-edited/garbage coordinate in the world-writable config file —
            // fails CLOSED. With (d > r), NaN compares false and the broken
            // profile would "contain" every location on earth.
            if (!(d <= p.getRadiusMeters())) continue;
            if (best == null
                    || p.getRadiusMeters() < best.getRadiusMeters()
                    || (p.getRadiusMeters() == best.getRadiusMeters() && d < bestDist)) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * Note that {@code id} priced a session and persist the counter. Called from
     * the session-end path; failure to persist is non-fatal (the rate itself is
     * already snapshotted onto the row).
     */
    /**
     * Note that a tariff priced a session.
     *
     * <p>Persistence is DEBOUNCED: this is called at every session close, and
     * finalizeStaleOpenSessions() can close a backlog of them serially at boot, so
     * writing the whole config file per call meant N cross-process-locked file
     * writes in a burst on the SoC thread. The counters are display-only
     * provenance, so an in-memory update with a coalesced write is the right
     * trade; the priced rate itself is already persisted on the session row.
     */
    public synchronized void markUsed(String id, long whenMs) {
        List<TariffProfile> cur = profiles;
        int idx = -1;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).getId().equals(id)) { idx = i; break; }
        }
        if (idx < 0) return;
        // Mutate a copy and swap, so a concurrent toJson()/resolve() on the
        // published profile can't observe a torn lastUsedAt/useCount, and the
        // non-atomic useCount++ can't lose an increment.
        TariffProfile copy = new TariffProfile(cur.get(idx).toJson());
        copy.markUsed(whenMs);
        List<TariffProfile> next = new ArrayList<>(cur);
        next.set(idx, copy);
        profiles = Collections.unmodifiableList(next);
        // Coalesce: at most one usage-counter flush per MARK_USED_FLUSH_MS.
        //
        // Clock note: whenMs is a session END TIME supplied by the caller and can be
        // 0/absent, so it cannot drive the debounce window on its own — deriving
        // `now` from it made the first-ever call compare 0-0 and never flush, and a
        // backdated session could push lastUsageFlushMs into the past. Use the wall
        // clock for the window, and treat lastUsageFlushMs == 0 as "never flushed"
        // so the first call always persists.
        long nowMs = System.currentTimeMillis();
        boolean due = (lastUsageFlushMs == 0) || (nowMs - lastUsageFlushMs >= MARK_USED_FLUSH_MS);
        if (due) {
            if (save()) {
                lastUsageFlushMs = nowMs;
                usageFlushPending = false;
            } else {
                profiles = cur;   // roll back the counter bump
            }
        } else {
            usageFlushPending = true;
        }
    }

    /**
     * Flush a debounced usage-counter update, if one is pending. Called from the
     * config-change path and from load(), so counters reach disk without a write
     * per session close. Losing a pending counter on an abrupt power cut only
     * costs display provenance, never a price.
     */
    public synchronized void flushPendingUsage() {
        if (!usageFlushPending) return;
        if (save()) {
            usageFlushPending = false;
            lastUsageFlushMs = System.currentTimeMillis();
        }
        // On failure keep the flag set so a later flush retries.
    }

    /**
     * Profiles ordered for display: most-recently-used first, then most-used,
     * then newest. Keeps the tariff the driver actually uses at the top of the
     * list instead of whatever order they happened to create things in.
     */
    public List<TariffProfile> getProfilesForDisplay() {
        List<TariffProfile> list = getProfiles();
        Collections.sort(list, new Comparator<TariffProfile>() {
            @Override
            public int compare(TariffProfile a, TariffProfile b) {
                if (a.getLastUsedAt() != b.getLastUsedAt()) {
                    return Long.compare(b.getLastUsedAt(), a.getLastUsedAt());
                }
                if (a.getUseCount() != b.getUseCount()) {
                    return Integer.compare(b.getUseCount(), a.getUseCount());
                }
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        return list;
    }

    /**
     * API payload: the ordered profile list plus the default id and, when a
     * location is known, which profile currently matches — that's what lets the
     * UI show "auto-applies here" against the live position.
     */
    public JSONObject toStatusJson(double lat, double lng) {
        JSONObject out = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (TariffProfile p : getProfilesForDisplay()) arr.put(p.toJson());
            out.put("tariffs", arr);
            out.put("defaultTariffId", getDefaultId());
            out.put("maxTariffs", MAX_PROFILES);
            if (!(lat == 0 && lng == 0)) {
                out.put("lat", lat);
                out.put("lng", lng);
                // Report the AC match — the gun is unknown until a cable is in,
                // and AC is the base case the label describes.
                TariffProfile m = resolve(lat, lng, 0);
                out.put("matchedTariffId", m != null ? m.getId() : JSONObject.NULL);
            } else {
                out.put("matchedTariffId", JSONObject.NULL);
            }
        } catch (Exception e) {
            logger.error("toStatusJson error: " + e.getMessage());
        }
        return out;
    }
}
