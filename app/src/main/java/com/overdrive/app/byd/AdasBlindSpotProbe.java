package com.overdrive.app.byd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;

/**
 * Read-only diagnostic for the radar blind-spot ALERT registers, which have never been
 * confirmed on a car. Answers the two questions the daemon log cannot: whether the feature
 * ids are real SDK constants or hardcoded guesses, and what the registers actually return.
 *
 * <p>Deliberately does NOT call {@link BydDataCollector#readBlindSpotNow()}. That advances
 * the counter baselines, so probing through it would consume the very increment being
 * looked for and report "no alert" on the one read that mattered.
 */
public final class AdasBlindSpotProbe {

    /** How the read layer interprets an id — the encoding is itself a suspect. */
    private enum Kind {
        /** Dedicated FL/FR alarms: current code treats {@code >= 1} as alerting. */
        LEVEL,
        /** LCA/RCTA/DOW: alerting means an INCREASE over the last seen value. */
        COUNTER,
        /** Switch-state reads in the SAME 0x4180 family — the differential control. */
        FAMILY_CONTROL,
        /** Proven-working ADAS reads in OTHER id families, to prove the device answers at all. */
        PROVEN_CONTROL
    }

    private static final class Entry {
        final String name;
        final String sdkField;
        final int id;
        final Kind kind;
        final String side;

        Entry(String name, String sdkField, int id, Kind kind, String side) {
            this.name = name;
            this.sdkField = sdkField;
            this.id = id;
            this.kind = kind;
            this.side = side;
        }
    }

    private static final Entry[] ENTRIES = {
        // The suspects: the eight signals BlindSpotEvent depends on.
        new Entry("ADAS_FL_BLIND_SPOT_ALARM", "Adas.ADAS_FL_BLIND_SPOT_ALARM_ACTIVE_SIGNAL_STATUS",
                BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM, Kind.LEVEL, "left"),
        new Entry("ADAS_FR_BLIND_SPOT_ALARM", "Adas.ADAS_FR_BLIND_SPOT_ALARM_ACTIVE_SIGNAL_STATUS",
                BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM, Kind.LEVEL, "right"),
        new Entry("ADAS_LCA_WARNING_LEFT", "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGLEFT",
                BydFeatureIds.ADAS_LCA_WARNING_LEFT, Kind.COUNTER, "left"),
        new Entry("ADAS_LCA_WARNING_RIGHT", "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGRIGHT",
                BydFeatureIds.ADAS_LCA_WARNING_RIGHT, Kind.COUNTER, "right"),
        new Entry("ADAS_RCTA_WARNING_LEFT", "Adas.ADAS_RIGHT_RADAR_RCTA_WARNINGLEFT",
                BydFeatureIds.ADAS_RCTA_WARNING_LEFT, Kind.COUNTER, "left"),
        new Entry("ADAS_RCTA_WARNING_RIGHT", "Adas.ADAS_RIGHT_RADAR_RCTA_WARNINGRIGHT",
                BydFeatureIds.ADAS_RCTA_WARNING_RIGHT, Kind.COUNTER, "right"),
        new Entry("ADAS_DOW_WARN_LEFT", "Adas.ADAS_DOW_WARN_LEFT",
                BydFeatureIds.ADAS_DOW_WARN_LEFT, Kind.COUNTER, "left"),
        new Entry("ADAS_DOW_WARN_RIGHT", "Adas.ADAS_DOW_WARN_RIGHT",
                BydFeatureIds.ADAS_DOW_WARN_RIGHT, Kind.COUNTER, "right"),

        // Same 0x4180 family, but switch-STATE reads rather than alert registers. If these
        // answer sanely while the alert ids read -1, the family is served and the specific
        // alert ids are wrong; if all of them read -1, the whole family is unreachable.
        new Entry("ADAS_RTCA_SWITCH_STATE", "Adas.ADAS_RTCA_SWITCH_STATE",
                BydFeatureIds.ADAS_RTCA_SWITCH_STATE, Kind.FAMILY_CONTROL, null),
        new Entry("ADAS_DOW_SWITCH_STATE", "Adas.ADAS_DOW_SWITCH_STATE",
                BydFeatureIds.ADAS_DOW_SWITCH_STATE, Kind.FAMILY_CONTROL, null),
        new Entry("ADAS_RCW_SWITCH_STATE", "Adas.ADAS_RCW_SWITCH_STATE",
                BydFeatureIds.ADAS_RCW_SWITCH_STATE, Kind.FAMILY_CONTROL, null),
        new Entry("ADAS_HAS_BSD", "Adas.ADAS_HAS_BSD",
                BydFeatureIds.ADAS_HAS_BSD, Kind.FAMILY_CONTROL, null),

        // Different id families that are known to answer on this firmware. These prove the
        // ADAS handle and deviceType are healthy, separating "device dead" from "ids wrong".
        new Entry("ADAS_SLW_FUNC_SWITCH_STATE", "Adas.ADAS_SLW_FUNC_SWITCH_STATE",
                BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE, Kind.PROVEN_CONTROL, null),
        new Entry("ADAS_ISLA_SWITCH_STATUS", "Adas.ADAS_ISLA_SWITCH_STATUS_5R13V",
                BydFeatureIds.ADAS_ISLA_SWITCH_STATUS, Kind.PROVEN_CONTROL, null),
        new Entry("ADAS_ESP_STATE", "Adas.ADAS_ESP_STATE",
                BydFeatureIds.ADAS_ESP_STATE, Kind.PROVEN_CONTROL, null),
    };

    /**
     * Real SDK field names (proven present in framework.jar) that could carry the live
     * blind-spot / cross-traffic ALERT this trim actually publishes. The shipped code touches
     * none of these — it reads six fabricated ids instead. Resolved by NAME at runtime, so an
     * absent one is reported rather than guessed at.
     */
    private static final String[] CANDIDATE_FIELDS = {
        "ADAS_BSD_STATE", "ADAS_BSD_STATE_HAL", "ADAS_BSD_CONFIG",
        "ADAS_BLIND_SPOT_DETECTION_GRAY",
        "ADAS_BSIS_FUNC_STATUS", "ADAS_BSIS_ENABLE_STATUS", "ADAS_BSIS_GRAY_STATUS",
        "ADAS_BSIS_ASSEMBLE_STATUS",
        "ADAS_ILCA_SWITCH_STATE", "ADAS_ILCA_CONFIG",
        "ADAS_RTCA_CONFIG", "ADAS_DOW_CONFIG",
        "ADAS_DOOR_OPEN_WARNING_GRAY",
        "ADAS_ACUTE_WARNING_STATE", "ADAS_VOICE_WARN_STATE",
        "ADAS_SDW_STATE", "ADAS_SDW_STATUS_FEEDBACK",
        "ADAS_PCW_PRE_WARNING", "ADAS_PCW_LATENT_WARNING",
        "ADAS_REAR_COLLISION_WARNING_GRAY", "ADAS_REVERSING_RADAR_SYS_STATE",
    };

    /**
     * Resolve each candidate field by name and read it. This is the search for the register
     * that actually moves when the radar warns — diff two calls across a real alert.
     */
    private static JSONArray readCandidates(Object device) {
        JSONArray arr = new JSONArray();
        Class<?> adas = null;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            for (Class<?> in : cls.getDeclaredClasses()) {
                if (in.getSimpleName().equals("Adas")) { adas = in; break; }
            }
        } catch (Throwable ignored) {}
        for (String name : CANDIDATE_FIELDS) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                if (adas == null) { o.put("error", "no Adas class"); arr.put(o); continue; }
                int id;
                try {
                    java.lang.reflect.Field f = adas.getField(name);
                    f.setAccessible(true);
                    id = f.getInt(null);
                } catch (NoSuchFieldException nsf) {
                    o.put("present", false);
                    arr.put(o);
                    continue;
                }
                o.put("present", true);
                o.put("id", id);
                o.put("hex", String.format("0x%08X", id));
                int raw = BydDeviceHelper.callGetSingle(device, id);
                o.put("raw", raw);
                // -10011 = module asleep/no data, -1 = read failed. Neither is a value.
                o.put("meaningful", raw != -1 && raw != BydFeatureIds.BMS_UNAVAILABLE);
            } catch (Throwable t) {
                try { o.put("error", String.valueOf(t)); } catch (Throwable ignored) {}
            }
            arr.put(o);
        }
        return arr;
    }

    private AdasBlindSpotProbe() {}

    /**
     * Which feature ids came from a real SDK constant and which fell back to a hardcoded
     * literal. No device access, so this works even with the HAL down.
     */
    public static JSONObject resolve() throws Exception {
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("featureIdClass", featureIdClassReport());

        JSONArray ids = new JSONArray();
        for (Entry e : ENTRIES) {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("sdkField", e.sdkField);
            o.put("effectiveId", e.id);
            o.put("hex", String.format("0x%08X", e.id));
            o.put("kind", e.kind.name());
            if (e.side != null) o.put("side", e.side);
            o.put("resolution", resolveSource(e.sdkField, e.id));
            ids.put(o);
        }
        r.put("ids", ids);
        r.put("adasFields", adasFieldInventory());
        r.put("note", "GET /api/debug/adas/read to dump the live register values");
        return r;
    }

    /**
     * Every field the platform's {@code BYDAutoFeatureIds$Adas} actually declares, with its
     * value. The only way to learn the REAL names for the ids we currently guess at — a
     * fabricated name is silently indistinguishable from a correct one otherwise.
     */
    private static JSONObject adasFieldInventory() throws Exception {
        JSONObject o = new JSONObject();
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            Class<?> adas = null;
            for (Class<?> in : cls.getDeclaredClasses()) {
                if (in.getSimpleName().equals("Adas")) { adas = in; break; }
            }
            if (adas == null) {
                o.put("error", "no Adas inner class");
                return o;
            }
            java.lang.reflect.Field[] fields = adas.getDeclaredFields();
            o.put("count", fields.length);
            JSONObject all = new JSONObject();
            JSONObject blindSpotRelated = new JSONObject();
            for (java.lang.reflect.Field f : fields) {
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    String n = f.getName();
                    all.put(n, v);
                    // The names a blind-spot/lane-change/cross-traffic alert could plausibly
                    // hide behind, so the fix doesn't require eyeballing 100+ entries.
                    String u = n.toUpperCase();
                    if (u.contains("BLIND") || u.contains("BSD") || u.contains("LCA")
                            || u.contains("RCTA") || u.contains("DOW") || u.contains("RADAR")
                            || u.contains("WARN") || u.contains("ALARM")) {
                        blindSpotRelated.put(n, v);
                    }
                } catch (Throwable ignored) {}
            }
            o.put("blindSpotRelated", blindSpotRelated);
            o.put("all", all);
        } catch (Throwable t) {
            o.put("error", String.valueOf(t));
        }
        return o;
    }

    /**
     * Raw per-id register dump. Side-effect free: reads each id directly and compares
     * against the live baseline WITHOUT updating it, so repeated calls stay truthful and a
     * real alert is still visible to the production poller.
     */
    public static JSONObject read() throws Exception {
        JSONObject r = new JSONObject();
        r.put("uid", android.os.Process.myUid());
        r.put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime());

        BydDataCollector collector = BydDataCollector.getInstance();
        Object device = collector.adasHandleForProbe();
        r.put("adasDeviceAvailable", device != null);
        if (device == null) {
            r.put("verdict", "ADAS device handle is null — every read returns -1 and "
                    + "readBlindSpotNow() correctly reports unavailable");
            return r;
        }
        r.put("adasDeviceClass", device.getClass().getName());

        int deviceType = BydDeviceHelper.deviceTypeOf(device);
        r.put("deviceType", deviceType);
        boolean typeResolved = deviceType != Integer.MIN_VALUE;
        r.put("deviceTypeResolved", typeResolved);
        if (!typeResolved) {
            r.put("verdict", "deviceType unresolved — callGetSingle short-circuits to -1 for "
                    + "EVERY id, so readBlindSpotNow() reports 0 (\"clear\"), not unavailable");
        }

        Map<Integer, Integer> baselines = collector.bsBaselineView();
        Set<Integer> proven = collector.bsProvenView();

        JSONArray reads = new JSONArray();
        int readable = 0;
        int readableAlertIds = 0;
        for (Entry e : ENTRIES) {
            int raw = BydDeviceHelper.callGetSingle(device, e.id);
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("id", e.id);
            o.put("hex", String.format("0x%08X", e.id));
            o.put("kind", e.kind.name());
            if (e.side != null) o.put("side", e.side);
            o.put("raw", raw);
            o.put("readable", raw != -1);
            // Same id read through the OEM's overload. CarSetting.apk reads these via
            // HalGetter -> BYDAutoADASDevice.get(int[], Class); callGetSingle uses
            // get(int,int), a different overload that answers -10011 here.
            o.put("arrayForm", arrayFormRead(device, e.id));
            if (raw != -1) {
                readable++;
                if (e.kind == Kind.LEVEL || e.kind == Kind.COUNTER) readableAlertIds++;
            }
            if (e.kind == Kind.LEVEL) {
                // What the shipped `>= 1` test would conclude. If this is true with nothing
                // beside the car, the resting value is non-zero and the test is wrong.
                o.put("currentCodeWouldAlert", raw >= 1);
            }
            if (e.kind == Kind.COUNTER) {
                Integer base = baselines.get(e.id);
                o.put("baseline", base == null ? JSONObject.NULL : base);
                o.put("currentCodeWouldAlert", base != null && raw > base);
                o.put("pollSkipsThisId", proven.contains(e.id));
            }
            reads.put(o);
        }
        r.put("reads", reads);
        r.put("candidates", readCandidates(device));
        r.put("readableCount", readable);
        r.put("totalCount", ENTRIES.length);
        r.put("provenEventIds", new JSONArray(proven));
        if (!r.has("verdict")) r.put("verdict", verdict(readable, readableAlertIds));
        r.put("note", "Read once with nothing beside the car, then again during a real alert "
                + "(reverse out of a bay for RCTA) and diff the raw values.");
        return r;
    }

    /**
     * Read one id the way the OEM app does — {@code get(int[], Class)} via
     * {@link BydDeviceHelper#callGet} — and report it alongside the raw object type, so a
     * BYDAutoEventValue that carries no int is distinguishable from a genuine 0.
     */
    private static JSONObject arrayFormRead(Object device, int id) {
        JSONObject o = new JSONObject();
        try {
            Object v = BydDeviceHelper.callGet(device, id, Integer.TYPE);
            o.put("returnedNull", v == null);
            if (v != null) {
                o.put("objectType", v.getClass().getName());
                int iv = BydDeviceHelper.getIntValue(v);
                o.put("intValue", iv);
                // MIN_VALUE = no intValue field at all; -10011/-1 = the sentinels we already
                // know about. Anything else is a real reading from this overload.
                o.put("meaningful", iv != Integer.MIN_VALUE && iv != -1
                        && iv != BydFeatureIds.BMS_UNAVAILABLE);
            }
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
        return o;
    }

    /** Plain-language reading of the dump, so the operator needn't interpret raw ints. */
    private static String verdict(int readable, int readableAlertIds) {
        if (readable == 0) {
            return "NOTHING readable — the ADAS device answers no id at all. Handle/deviceType "
                    + "or permissions, not the blind-spot ids specifically.";
        }
        if (readableAlertIds == 0) {
            return "Control ids answer but NO alert id does — the alert feature ids are wrong "
                    + "for this firmware. Note readBlindSpotNow() still reports 0 (\"clear\"), "
                    + "never unavailable, which is why this failed silently.";
        }
        return "Alert ids ARE readable — compare these raw values against a second read taken "
                + "during a real alert. If they never change, these are not the alert channel; "
                + "if a LEVEL id rests non-zero, the shipped `>= 1` test is wrong.";
    }

    /**
     * Whether {@code BYDAutoFeatureIds} resolves to the platform class or to our bundled
     * compile stub. If it is the stub, EVERY resolveOrFallback silently returns its literal.
     */
    private static JSONObject featureIdClassReport() throws Exception {
        JSONObject o = new JSONObject();
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            o.put("present", true);
            ClassLoader cl = cls.getClassLoader();
            o.put("classLoader", cl == null ? "boot (platform)" : cl.getClass().getName());
            o.put("isPlatformClass", cl == null);
            JSONArray inner = new JSONArray();
            for (Class<?> c : cls.getDeclaredClasses()) inner.put(c.getSimpleName());
            o.put("innerClasses", inner);
            o.put("hasAdasInnerClass", inner.toString().contains("\"Adas\""));
        } catch (Throwable t) {
            o.put("present", false);
            o.put("error", String.valueOf(t));
        }
        return o;
    }

    /**
     * Re-resolve one id independently of {@link BydFeatureIds}, so a hardcoded fallback is
     * distinguishable from a genuine SDK constant — and a WRONG literal is visible as a
     * value mismatch against the SDK.
     */
    private static JSONObject resolveSource(String sdkField, int effective) throws Exception {
        JSONObject o = new JSONObject();
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            String[] parts = sdkField.split("\\.");
            if (parts.length != 2) {
                o.put("source", "unknown");
                o.put("reason", "unexpected field spec");
                return o;
            }
            for (Class<?> in : cls.getDeclaredClasses()) {
                if (!in.getSimpleName().equals(parts[0])) continue;
                try {
                    int sdkValue = in.getField(parts[1]).getInt(null);
                    o.put("source", "sdk-constant");
                    o.put("sdkValue", sdkValue);
                    o.put("matchesHardcodedFallback", sdkValue == effective);
                    return o;
                } catch (NoSuchFieldException nsf) {
                    o.put("source", "hardcoded-fallback");
                    o.put("reason", "field '" + parts[1] + "' absent on " + parts[0]);
                    return o;
                }
            }
            o.put("source", "hardcoded-fallback");
            o.put("reason", "no inner class '" + parts[0] + "'");
        } catch (ClassNotFoundException cnf) {
            o.put("source", "hardcoded-fallback");
            o.put("reason", "BYDAutoFeatureIds absent");
        } catch (Throwable t) {
            o.put("source", "unknown");
            o.put("reason", String.valueOf(t.getMessage()));
        }
        return o;
    }
}
