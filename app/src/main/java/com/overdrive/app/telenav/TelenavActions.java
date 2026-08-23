package com.overdrive.app.telenav;

import android.content.Context;
import android.content.Intent;

import com.telenav.app.external.constants.FavoriteType;
import com.telenav.app.external.model.search.Address;
import com.telenav.app.external.model.search.Place;

/**
 * High-level Telenav actions shared by both clients: the on-car RoadSense map
 * (calls these directly, in-process) and the phone via {@link TelenavIpcServer}.
 * One place that maps a plain (name, lat, lng) into a Telenav {@link Place} and
 * invokes the bridge.
 */
public final class TelenavActions {

    private static final long TIMEOUT_MS = 20_000L;

    private static final String TELENAV_PKG = "com.telenav.app.arp";
    private static final String TELENAV_MAP_ACTIVITY = "com.telenav.arp.module.map.MainActivity";
    /** Let Telenav come to the front and its nav service settle before we start guidance. */
    private static final long FOREGROUND_SETTLE_MS = 1200L;

    private TelenavActions() {}

    /**
     * Bring Telenav's map to the foreground. Verified live 2026-08-23: Telenav only
     * engages turn-by-turn guidance while it is the foreground app — {@code startNavigation}
     * is accepted and returns success even when Telenav is backgrounded, but nothing
     * is shown. Idempotent (a running task is brought to front, not restarted).
     *
     * <p>This uses {@code startActivity}, so the CALLER MUST be in the foreground (the
     * on-car map is). A backgrounded process is subject to Android's background-activity
     * -launch limits and this silently no-ops; the phone/endpoint path foregrounds from
     * the daemon (UID 2000, {@code am start}) instead — see {@code TelenavDebugApiHandler}.
     */
    public static void foregroundTelenav(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(TELENAV_PKG, TELENAV_MAP_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
            Thread.sleep(FOREGROUND_SETTLE_MS);
        } catch (Exception ignore) {
            // Best effort: if we can't foreground, still attempt the nav command below.
        }
    }

    /** Telenav rejects a null placeId; we store by coordinates, so a synthetic id is fine. */
    public static Place buildPlace(
            String name, double lat, double lng, String favoriteType,
            String placeId, String formattedAddress) {
        String pid = (placeId == null || placeId.trim().isEmpty())
                ? "OD-" + lat + "_" + lng : placeId.trim();
        String addr = (formattedAddress == null || formattedAddress.isEmpty()) ? name : formattedAddress;

        Place p = new Place();
        p.setPlaceId(pid);
        p.setSearchSourceType("ON_BOARD");
        p.setPlaceName(name);
        p.setPlaceDisplayLabel(name);
        p.setPlaceType("ADDRESS");
        p.setFavoriteType(favoriteType);
        p.setGeoLatitude(lat);
        p.setGeoLongitude(lng);
        p.setNavLatitude(lat);
        p.setNavLongitude(lng);
        Address a = new Address();
        a.setFormattedAddress(addr);
        a.setFullAddress(addr);
        p.setAddress(a);
        return p;
    }

    /** Save a place to the favourites (default type Normal = the heart list). Blocking. */
    public static void addFavorite(Context ctx, String name, double lat, double lng, String favoriteType)
            throws Exception {
        String type = (favoriteType == null || favoriteType.isEmpty()) ? FavoriteType.Normal : favoriteType;
        TelenavClient.addFavorite(ctx, TIMEOUT_MS, type, buildPlace(name, lat, lng, type, null, null));
    }

    /**
     * Start turn-by-turn navigation to a place. Foregrounds Telenav first (guidance
     * only engages while Telenav is on screen). Blocking; returns Telenav's result.
     */
    public static boolean navigate(Context ctx, String name, double lat, double lng) throws Exception {
        foregroundTelenav(ctx);
        return TelenavClient.startNavigation(
                ctx, TIMEOUT_MS, buildPlace(name, lat, lng, FavoriteType.Normal, null, null));
    }
}
