package com.overdrive.app.telenav;

import android.content.Context;

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

    private TelenavActions() {}

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

    /** Start turn-by-turn navigation to a place. Blocking; returns Telenav's result. */
    public static boolean navigate(Context ctx, String name, double lat, double lng) throws Exception {
        return TelenavClient.startNavigation(
                ctx, TIMEOUT_MS, buildPlace(name, lat, lng, FavoriteType.Normal, null, null));
    }
}
