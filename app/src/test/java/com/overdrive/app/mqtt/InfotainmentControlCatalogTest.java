package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.routing.VehicleCommandRouter;

import org.junit.Test;

import java.util.Arrays;

/** Contracts for centre-screen rotation and the OEM panorama camera view selector. */
public class InfotainmentControlCatalogTest {

    @Test
    public void rotationUsesTheConnectedUnitSdkEnum() {
        VehicleControlCatalog.ControlEntity entity =
                VehicleControlCatalog.get("infotainment_rotation");

        assertNotNull(entity);
        assertEquals(Arrays.asList("horizontal", "vertical"), entity.options);
        assertRotation(entity, "horizontal", BydDataCollector.PAD_ROTATION_HORIZONTAL);
        assertRotation(entity, "vertical", BydDataCollector.PAD_ROTATION_VERTICAL);
        assertNotNull(entity.toAction(null, "toggle", null));
        assertNull(entity.toAction(null, "portrait", null));
    }

    @Test
    public void nativeCameraViewsUseTheOemBroadcastCodes() {
        VehicleControlCatalog.ControlEntity entity =
                VehicleControlCatalog.get("native_camera_view");

        assertNotNull(entity);
        assertEquals(Arrays.asList(
                "front", "front_wide", "rear", "rear_wide", "left", "right", "left_right"),
                entity.options);
        assertCamera(entity, "front", 3001);
        assertCamera(entity, "rear", 3002);
        assertCamera(entity, "left", 3003);
        assertCamera(entity, "right", 3004);
        assertCamera(entity, "front_wide", 3006);
        assertCamera(entity, "rear_wide", 3007);
        assertCamera(entity, "left_right", 3008);
        assertNull(entity.toAction(null, "all", null));
    }

    private static void assertRotation(
            VehicleControlCatalog.ControlEntity entity, String payload, int expected) {
        VehicleControlCatalog.ControlAction action =
                entity.toAction(null, payload, null);
        assertNotNull(action);
        assertTrue(action.command instanceof VehicleCommandRouter.InfotainmentRotationCommand);
        assertEquals(expected,
                ((VehicleCommandRouter.InfotainmentRotationCommand) action.command).rotation);
    }

    private static void assertCamera(
            VehicleControlCatalog.ControlEntity entity, String payload, int expected) {
        assertEquals(expected, VehicleControlCatalog.nativeCameraViewCode(payload));
        VehicleControlCatalog.ControlAction action =
                entity.toAction(null, payload, null);
        assertNotNull(action);
        assertTrue(action.command instanceof VehicleCommandRouter.NativeCameraViewCommand);
        assertEquals(expected,
                ((VehicleCommandRouter.NativeCameraViewCommand) action.command).viewCode);
    }
}
