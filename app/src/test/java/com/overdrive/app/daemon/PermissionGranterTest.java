package com.overdrive.app.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

/**
 * Parsing the already-granted permission set out of {@code dumpsys package}.
 *
 * <p>The fixture below is trimmed verbatim from a BYD Seal head unit
 * (firmware 13.1.33.2602030.1, Android 10) so the indentation, the
 * {@code flags=[...]} suffix on runtime entries, and the bare-name
 * {@code requested permissions:} block all match what the parser really sees.
 */
public class PermissionGranterTest {

    /**
     * Real {@code dumpsys package com.overdrive.app} excerpt. A
     * {@code granted=false} runtime entry is included — absent on the sampled
     * device, but a permission can be revoked and the parser must not claim it.
     */
    private static final String DUMPSYS = String.join("\n",
            "    requested permissions:",
            "      android.permission.DEVICE_POWER",
            "      com.byd.car.server.PROVIDER",
            "      android.permission.ACCESS_MEDIA_LOCATION",
            "    install permissions:",
            "      android.permission.SYSTEM_ALERT_WINDOW: granted=true",
            "      android.permission.FOREGROUND_SERVICE: granted=true",
            "      com.byd.car.server.PROVIDER: granted=true",
            "      android.permission.WRITE_SECURE_SETTINGS: granted=true",
            "    User 0: ceDataInode=123 installed=true hidden=false",
            "      gids=[3002, 3003, 3001, 1007]",
            "      runtime permissions:",
            "        android.permission.ACCESS_FINE_LOCATION: granted=true, "
                    + "flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]",
            "        android.permission.BYDAUTO_SAFETY_BELT_COMMON: granted=true",
            "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET]",
            "");

    @Test
    public void collectsInstallPermissions() {
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertTrue(granted.contains("android.permission.SYSTEM_ALERT_WINDOW"));
        assertTrue(granted.contains("android.permission.WRITE_SECURE_SETTINGS"));
    }

    @Test
    public void collectsRuntimePermissionsDespiteFlagsSuffix() {
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertTrue(granted.contains("android.permission.ACCESS_FINE_LOCATION"));
        assertTrue(granted.contains("android.permission.BYDAUTO_SAFETY_BELT_COMMON"));
    }

    @Test
    public void collectsNonAndroidPermissionNamespaces() {
        // BYD ships vendor permissions under their own package namespace.
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertTrue(granted.contains("com.byd.car.server.PROVIDER"));
    }

    @Test
    public void excludesRevokedPermissions() {
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertFalse("granted=false must not count as granted",
                granted.contains("android.permission.RECORD_AUDIO"));
    }

    @Test
    public void excludesMerelyRequestedPermissions() {
        // The "requested permissions:" block lists bare names with no
        // granted= marker. Treating those as granted would skip the very
        // grants we need to issue.
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertFalse(granted.contains("android.permission.DEVICE_POWER"));
        assertFalse(granted.contains("android.permission.ACCESS_MEDIA_LOCATION"));
    }

    @Test
    public void countsOnlyGrantedEntries() {
        Set<String> granted = PermissionGranter.parseGrantedPermissions(DUMPSYS);
        assertEquals(6, granted.size());
    }

    @Test
    public void emptyOrUnparseableOutputYieldsNothingRatherThanThrowing() {
        // A dumpsys failure must degrade to "grant everything", never to
        // "skip everything" — losing permissions would break the daemon.
        assertTrue(PermissionGranter.parseGrantedPermissions("").isEmpty());
        assertTrue(PermissionGranter.parseGrantedPermissions(null).isEmpty());
        assertTrue(PermissionGranter.parseGrantedPermissions("garbage output").isEmpty());
    }
}
