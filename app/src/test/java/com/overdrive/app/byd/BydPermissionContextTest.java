package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Keeps the app-process BYD permission wrapper limited to the OEM permission namespace. */
public class BydPermissionContextTest {

    @Test
    public void recognizesBodyworkPermissions() {
        assertTrue(BydDeviceHelper.isBydPermissionName(
                "android.permission.BYDAUTO_BODYWORK_COMMON"));
        assertTrue(BydDeviceHelper.isBydPermissionName(
                "android.permission.BYDAUTO_BODYWORK_GET"));
        assertTrue(BydDeviceHelper.isBydPermissionName(
                "android.permission.BYDAUTO_BODYWORK_SET"));
    }

    @Test
    public void doesNotGrantUnrelatedPermissions() {
        assertFalse(BydDeviceHelper.isBydPermissionName(
                "android.permission.CAMERA"));
        assertFalse(BydDeviceHelper.isBydPermissionName(
                "com.byd.car.server.PROVIDER"));
        assertFalse(BydDeviceHelper.isBydPermissionName(null));
    }
}
