package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the connected DiLink 3 manual mirror Setting contract. */
public class BydMirrorCommandEncodingTest {

    @Test
    public void manualMirrorCommandUsesSettingDeviceType1023() {
        assertEquals(1023, BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE);
        assertEquals(0x4C10A028,
                BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET);
    }

    @Test
    public void foldAndUnfoldUseDistinctOneAndTwoEncoding() {
        assertEquals(1, BydConstants.mirrorFoldCommand(true));
        assertEquals(2, BydConstants.mirrorFoldCommand(false));
    }
}
