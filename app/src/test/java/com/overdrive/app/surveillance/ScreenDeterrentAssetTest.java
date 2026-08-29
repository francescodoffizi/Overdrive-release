package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenDeterrentAssetTest {

    @Test
    public void onlyFinalAssetsInsideTheManagedDirectoryAreAccepted() {
        assertTrue(ScreenDeterrentAsset.isAllowedPath(
                "/data/local/tmp/.overdrive/screen_deterrent_asset.123.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/local/tmp/.overdrive/screen_deterrent_asset.upload.tmp"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/local/tmp/.overdrive/SCREEN_DETERRENT_ASSET.123.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                "/data/local/tmp/.overdrive/../secret.mp4"));
        assertFalse(ScreenDeterrentAsset.isAllowedPath("/etc/passwd"));
    }
}
