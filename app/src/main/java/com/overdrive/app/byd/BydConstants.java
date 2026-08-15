package com.overdrive.app.byd;

/**
 * Common constants for BYD SDK integration
 */
public final class BydConstants {
    
    private BydConstants() {} // Prevent instantiation

    /** Connected DiLink 3 rear-view-mirror state device. */
    public static final String REAR_VIEW_MIRROR_DEVICE_CLASS =
            "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice";
    public static final int REAR_VIEW_MIRROR_DEVICE_TYPE = 1047;

    /**
     * Connected-model manual exterior-mirror command contract.
     *
     * <p>The vehicle's own DiCar write profile maps
     * {@code SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET} to the Setting provider (device 1023).
     * Its command values follow the OEM mirror enum: 1=fold, 2=unfold.
     */
    public static final String MIRROR_FOLD_SETTING_DEVICE_CLASS =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    public static final int MIRROR_FOLD_SETTING_DEVICE_TYPE = 1023;
    public static final int MIRROR_FOLD_COMMAND = 1;
    public static final int MIRROR_UNFOLD_COMMAND = 2;

    public static int mirrorFoldCommand(boolean folded) {
        return folded ? MIRROR_FOLD_COMMAND : MIRROR_UNFOLD_COMMAND;
    }
    
    // Power levels from BYDAutoBodyworkDevice
    public static final int POWER_LEVEL_OFF = 0;
    public static final int POWER_LEVEL_ACC = 1;
    public static final int POWER_LEVEL_ON = 2;
    
    public static String powerLevelToString(int level) {
        switch (level) {
            case POWER_LEVEL_OFF: return "OFF";
            case POWER_LEVEL_ACC: return "ACC";
            case POWER_LEVEL_ON: return "ON";
            default: return "UNKNOWN(" + level + ")";
        }
    }
}
