package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the protected BYD base-setter path used by the manual mirror Setting command. */
public class BydDeviceHelperProtectedSetTest {

    public static class BaseSettingDevice {
        int seenDeviceType;
        int seenFeatureId;
        int seenValue;

        protected int set(int deviceType, int featureId, int value) {
            seenDeviceType = deviceType;
            seenFeatureId = featureId;
            seenValue = value;
            return 0;
        }
    }

    public static final class ConnectedSettingDevice extends BaseSettingDevice {
        public int getType() {
            return BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE;
        }
    }

    @Test
    public void callSetSingleFindsProtectedSetterOnSuperclass() {
        ConnectedSettingDevice device = new ConnectedSettingDevice();

        int result = BydDeviceHelper.callSetSingle(
                device,
                BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                BydConstants.MIRROR_FOLD_COMMAND);

        assertEquals(0, result);
        assertEquals(BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE, device.seenDeviceType);
        assertEquals(
                BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                device.seenFeatureId);
        assertEquals(BydConstants.MIRROR_FOLD_COMMAND, device.seenValue);
    }

    @Test
    public void callSetSinglePassesUnfoldValueUnchanged() {
        ConnectedSettingDevice device = new ConnectedSettingDevice();

        assertEquals(0, BydDeviceHelper.callSetSingle(
                device,
                BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                BydConstants.MIRROR_UNFOLD_COMMAND));
        assertEquals(BydConstants.MIRROR_UNFOLD_COMMAND, device.seenValue);
    }
}
