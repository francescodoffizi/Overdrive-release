package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CameraProfilesTest {

    @Test
    public void atto3SelectedModelUsesFieldVerifiedCameraZero() {
        CameraProfile profile = CameraProfiles.infer("atto3");

        assertEquals(CameraProfiles.PROFILE_ATTO_3, profile.getId());
        assertEquals(0, profile.getPanoCameraId());
        assertEquals(5120, profile.getPanoWidth());
        assertEquals(960, profile.getPanoHeight());
        assertEquals(0, profile.getPanoSurfaceMode());
    }

    @Test
    public void atto3AliasesResolveToSameProfile() {
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("BYD Atto 3").getId());
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("atto-3").getId());
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("Yuan Plus").getId());
    }

    @Test
    public void unknownSystemModelKeepsConservativeLegacyDefault() {
        CameraProfile profile = CameraProfiles.infer("BYD AUTO");

        assertEquals(CameraProfiles.PROFILE_LEGACY_SEAL_ATTO, profile.getId());
        assertEquals(1, profile.getPanoCameraId());
    }

    @Test
    public void selectedVehicleModelWinsOverGenericSystemModel() {
        assertEquals("atto3",
                CameraConfigResolver.preferSelectedVehicleModel("atto3", "BYD AUTO"));
        assertEquals("BYD AUTO",
                CameraConfigResolver.preferSelectedVehicleModel("", "BYD AUTO"));
        assertEquals("unknown",
                CameraConfigResolver.preferSelectedVehicleModel(null, null));
    }
}
