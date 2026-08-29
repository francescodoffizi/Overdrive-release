package android.hardware.bydauto.bodywork;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BYDAutoBodyworkDevice extends AbsBYDAutoDevice {
    public static final int AUTO_MODEL_NEW_QIN_EV = 2;
    public static final int AUTO_MODEL_NEW_QIN_FUEL = 3;
    public static final int AUTO_MODEL_NEW_QIN_HEV = 1;
    public static final int AUTO_MODEL_NEW_TANG_EV = 6;
    public static final int AUTO_MODEL_NEW_TANG_FUEL = 5;
    public static final int AUTO_MODEL_NEW_TANG_HEV = 4;
    public static final int AUTO_MODEL_NULL = 11;
    public static final int AUTO_MODEL_SONG_18_EV = 9;
    public static final int AUTO_MODEL_SONG_18_FUEL = 7;
    public static final int AUTO_MODEL_SONG_18_HEV = 8;
    public static final int AUTO_MODEL_SONG_MAX_HEV = 10;
    public static final int AUTO_TYPE_3A = 20;
    public static final int AUTO_TYPE_3B = 36;
    public static final int AUTO_TYPE_5A = 5;
    public static final int AUTO_TYPE_5AEV = 29;
    public static final int AUTO_TYPE_5B = 22;
    public static final int AUTO_TYPE_5BH = 27;
    public static final int AUTO_TYPE_5BHE = 53;
    public static final int AUTO_TYPE_5BHI = 64;
    public static final int AUTO_TYPE_6A = 7;
    public static final int AUTO_TYPE_6B = 8;
    public static final int AUTO_TYPE_CV_SEDAN_HK = 188;
    public static final int AUTO_TYPE_CV_T35A02 = 40;
    public static final int AUTO_TYPE_CV_TE_HK = 280;
    public static final int AUTO_TYPE_EK = 138;
    public static final int AUTO_TYPE_EL = 96;
    public static final int AUTO_TYPE_EL20 = 117;
    public static final int AUTO_TYPE_ELEBD = 149;
    public static final int AUTO_TYPE_ELEBU = 108;
    public static final int AUTO_TYPE_EM2E = 127;
    public static final int AUTO_TYPE_EM2E_UY = 186;
    public static final int AUTO_TYPE_EMEA = 95;
    public static final int AUTO_TYPE_EQ = 215;
    public static final int AUTO_TYPE_EQE = 251;
    public static final int AUTO_TYPE_ESEA = 146;
    public static final int AUTO_TYPE_EW_UY = 191;
    public static final int AUTO_TYPE_F0 = 0;
    public static final int AUTO_TYPE_F3 = 1;
    public static final int AUTO_TYPE_F3R = 2;
    public static final int AUTO_TYPE_F6 = 6;
    public static final int AUTO_TYPE_G3 = 3;
    public static final int AUTO_TYPE_HA = 13;
    public static final int AUTO_TYPE_HA2EM = 101;
    public static final int AUTO_TYPE_HA2FL = 122;
    public static final int AUTO_TYPE_HA2FM = 122;
    public static final int AUTO_TYPE_HA2H = 141;
    public static final int AUTO_TYPE_HA2HE = 102;
    public static final int AUTO_TYPE_HA6H = 217;
    public static final int AUTO_TYPE_HAC = 46;
    public static final int AUTO_TYPE_HAD = 50;
    public static final int AUTO_TYPE_HADA = 63;
    public static final int AUTO_TYPE_HADE = 51;
    public static final int AUTO_TYPE_HADF = 66;
    public static final int AUTO_TYPE_HADG = 85;
    public static final int AUTO_TYPE_HAEA = 49;
    public static final int AUTO_TYPE_HAEC = 62;
    public static final int AUTO_TYPE_HAEV = 28;
    public static final int AUTO_TYPE_HA_15A = 37;
    public static final int AUTO_TYPE_HB = 14;
    public static final int AUTO_TYPE_HC = 15;
    public static final int AUTO_TYPE_HCE = 92;
    public static final int AUTO_TYPE_HCEF = 135;
    public static final int AUTO_TYPE_HCF = 93;
    public static final int AUTO_TYPE_HCHY = 121;
    public static final int AUTO_TYPE_HD = 31;
    public static final int AUTO_TYPE_HDE = 98;
    public static final int AUTO_TYPE_HDE21 = 124;
    public static final int AUTO_TYPE_HDF = 99;
    public static final int AUTO_TYPE_HXH_ABC_L = 244;
    public static final int AUTO_TYPE_KD = 131;
    public static final int AUTO_TYPE_L3 = 4;
    public static final int AUTO_TYPE_L3G = 33;
    public static final int AUTO_TYPE_M6 = 10;
    public static final int AUTO_TYPE_MEE = 44;
    public static final int AUTO_TYPE_MEEY = 109;
    public static final int AUTO_TYPE_MEEY_KL = 220;
    public static final int AUTO_TYPE_MEF = 42;
    public static final int AUTO_TYPE_MEFD = 81;
    public static final int AUTO_TYPE_MEH = 43;
    public static final int AUTO_TYPE_MEHD = 67;
    public static final int AUTO_TYPE_MEHM = 113;
    public static final int AUTO_TYPE_MRE = 60;
    public static final int AUTO_TYPE_MRH = 57;
    public static final int AUTO_TYPE_PAHAL = 143;
    public static final int AUTO_TYPE_PKEAS_PKEBS = 248;
    public static final int AUTO_TYPE_PKHAS = 249;
    public static final int AUTO_TYPE_RSA = 45;
    public static final int AUTO_TYPE_S6 = 9;
    public static final int AUTO_TYPE_S6DM = 17;
    public static final int AUTO_TYPE_S8 = 16;
    public static final int AUTO_TYPE_SA = 11;
    public static final int AUTO_TYPE_SA2E = 84;
    public static final int AUTO_TYPE_SA2EM = 112;
    public static final int AUTO_TYPE_SA2FC = 82;
    public static final int AUTO_TYPE_SA2FL = 110;
    public static final int AUTO_TYPE_SA2H = 83;
    public static final int AUTO_TYPE_SA2HG = 111;
    public static final int AUTO_TYPE_SA2H_RQ = 90;
    public static final int AUTO_TYPE_SA3 = 162;
    public static final int AUTO_TYPE_SA3E = 72;
    public static final int AUTO_TYPE_SA3EE = 74;
    public static final int AUTO_TYPE_SA3EJ = 147;
    public static final int AUTO_TYPE_SA3E_FG = 173;
    public static final int AUTO_TYPE_SA3F = 68;
    public static final int AUTO_TYPE_SA3FL = 139;
    public static final int AUTO_TYPE_SA3H = 71;
    public static final int AUTO_TYPE_SADM = 12;
    public static final int AUTO_TYPE_SAEA = 40;
    public static final int AUTO_TYPE_SAEC = 70;
    public static final int AUTO_TYPE_SAED = 97;
    public static final int AUTO_TYPE_SAEG = 94;
    public static final int AUTO_TYPE_SAEV = 39;
    public static final int AUTO_TYPE_SAFG = 73;
    public static final int AUTO_TYPE_SAFJ = 73;
    public static final int AUTO_TYPE_SAH = 32;
    public static final int AUTO_TYPE_SAHA = 88;
    public static final int AUTO_TYPE_SAHB = 89;
    public static final int AUTO_TYPE_SAHC = 90;
    public static final int AUTO_TYPE_SAHE = 65;
    public static final int AUTO_TYPE_SAHG = 61;
    public static final int AUTO_TYPE_SAHX = 74;
    public static final int AUTO_TYPE_SC = 30;
    public static final int AUTO_TYPE_SC2E = 128;
    public static final int AUTO_TYPE_SC3E = 160;
    public static final int AUTO_TYPE_SC3EBE = 81;
    public static final int AUTO_TYPE_SC3H = 291;
    public static final int AUTO_TYPE_SCEA = 41;
    public static final int AUTO_TYPE_SCED = 69;
    public static final int AUTO_TYPE_SCH = 35;
    public static final int AUTO_TYPE_SE = 23;
    public static final int AUTO_TYPE_SEF = 24;
    public static final int AUTO_TYPE_SEH = 48;
    public static final int AUTO_TYPE_SF = 153;
    public static final int AUTO_TYPE_SGE = 144;
    public static final int AUTO_TYPE_SGH = 145;
    public static final int AUTO_TYPE_SK2F = 130;
    public static final int AUTO_TYPE_SK2H = 129;
    public static final int AUTO_TYPE_SLHAB = 90;
    public static final int AUTO_TYPE_ST = 47;
    public static final int AUTO_TYPE_STC = 56;
    public static final int AUTO_TYPE_STE = 58;
    public static final int AUTO_TYPE_STEAU = 137;
    public static final int AUTO_TYPE_STEB = 59;
    public static final int AUTO_TYPE_STEL = 114;
    public static final int AUTO_TYPE_STEM = 60;
    public static final int AUTO_TYPE_STF = 54;
    public static final int AUTO_TYPE_STF20 = 53;
    public static final int AUTO_TYPE_STF21 = 116;
    public static final int AUTO_TYPE_STFD = 55;
    public static final int AUTO_TYPE_STH20 = 52;
    public static final int AUTO_TYPE_STH21 = 115;
    public static final int AUTO_TYPE_STHAM = 136;
    public static final int AUTO_TYPE_STHM = 56;
    public static final int AUTO_TYPE_STM = 57;
    public static final int AUTO_TYPE_SUE = 150;
    public static final int AUTO_TYPE_SUH = 151;
    public static final int AUTO_TYPE_UXE = 152;
    public static final int AUTO_TYPE_VA = 18;
    public static final int AUTO_TYPE_VB = 19;
    public static final int AUTO_TYPE_VBEV = 26;
    public static final int AUTO_TYPE_VBH = 25;
    public static final int AUTO_TYPE_VC = 21;
    public static final int AUTO_TYPE_VCE = 174;
    public static final int AUTO_TYPE_VDE_AB = 175;
    public static final int AUTO_TYPE_VDE_AU = 273;
    public static final int AUTO_TYPE_VDE_TUTEFU = 272;
    public static final int AUTO_TYPE_e6B = 38;
    public static final int AUTO_TYPE_e6H = 34;
    public static final int AUTO_TYPE_e6K = 52;
    public static final int BATTERY_POWER_MAX = 255;
    public static final int BATTERY_POWER_MIN = 0;
    public static final int BODYWORK_ALARM_STATE_OFF = 0;
    public static final int BODYWORK_ALARM_STATE_ON = 1;
    public static final int BODYWORK_ALL_WINDOW_ANTI_PINCH = 4;
    public static final int BODYWORK_ANTI_PINCH_INVLAID = 0;
    public static final int BODYWORK_AUTO_SYSTEM_STATE_NORMAL = 0;
    public static final int BODYWORK_AUTO_SYSTEM_STATE_SET_SECURE = 1;
    public static final int BODYWORK_AUTO_SYSTEM_STATE_START_SECURE = 2;
    public static final int BODYWORK_AUTO_SYSTEM_STATE_UNDEFINED = 255;
    public static final int BODYWORK_BATTERY_MODE_DAY = 0;
    public static final int BODYWORK_BATTERY_MODE_INVALID = 255;
    public static final int BODYWORK_BATTERY_MODE_NIGHT = 1;
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_INVALID = 255;
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_LOW = 0;
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_NORMAL = 1;
    public static final int BODYWORK_CMD_DOOR_FUEL_TANK_CAP = 7;
    public static final int BODYWORK_CMD_DOOR_HOOD = 5;
    public static final int BODYWORK_CMD_DOOR_LEFT_FRONT = 1;
    public static final int BODYWORK_CMD_DOOR_LEFT_REAR = 3;
    public static final int BODYWORK_CMD_DOOR_LUGGAGE_DOOR = 6;
    public static final int BODYWORK_CMD_DOOR_RIGHT_FRONT = 2;
    public static final int BODYWORK_CMD_DOOR_RIGHT_REAR = 4;
    public static final int BODYWORK_CMD_MOON_ROOF = 5;
    public static final int BODYWORK_CMD_STEERING_WHEEL_ANGEL = 1;
    public static final int BODYWORK_CMD_STEERING_WHEEL_SPEED = 2;
    public static final int BODYWORK_CMD_SUNSHADE_PANEL = 6;
    public static final int BODYWORK_CMD_WINDOW_LEFT_FRONT = 1;
    public static final int BODYWORK_CMD_WINDOW_LEFT_REAR = 3;
    public static final int BODYWORK_CMD_WINDOW_RIGHT_FRONT = 2;
    public static final int BODYWORK_CMD_WINDOW_RIGHT_REAR = 4;
    public static final int BODYWORK_COMMAND_BUSY = -2147482647;
    public static final int BODYWORK_COMMAND_FAILED = -2147482648;
    public static final int BODYWORK_COMMAND_INVALID_VALUE = -2147482645;
    public static final int BODYWORK_COMMAND_SUCCESS = 0;
    public static final int BODYWORK_COMMAND_TIMEOUT = -2147482646;
    private static final String BODYWORK_COMMON_PERM = "android.permission.BYDAUTO_BODYWORK_COMMON";
    public static final int BODYWORK_FRONT_WINDOW_ANTI_PINCH = 3;
    private static final String BODYWORK_GET_PERM = "android.permission.BYDAUTO_BODYWORK_GET";
    public static final int BODYWORK_LEFT_WINDOW_ANTI_PINCH_CONFIG = 2;
    public static final int BODYWORK_LOW_POWER_BOTH = 3;
    public static final int BODYWORK_LOW_POWER_ELEC = 2;
    public static final int BODYWORK_LOW_POWER_FUEL = 1;
    public static final int BODYWORK_LOW_POWER_NORMAL = 0;
    public static final int BODYWORK_NO_ANTI_PINCH = 1;
    public static final int BODYWORK_POWER_LEVEL_ACC = 1;
    public static final int BODYWORK_POWER_LEVEL_FAKE_OK = 4;
    public static final int BODYWORK_POWER_LEVEL_INVALID = 255;
    public static final int BODYWORK_POWER_LEVEL_OFF = 0;
    public static final int BODYWORK_POWER_LEVEL_OK = 3;
    public static final int BODYWORK_POWER_LEVEL_ON = 2;
    private static final String BODYWORK_SET_PERM = "android.permission.BYDAUTO_BODYWORK_SET";
    public static final int BODYWORK_STATE_CLOSED = 0;
    public static final int BODYWORK_STATE_OPEN = 1;
    public static final int BODYWORK_STATE_UNDEFINED = 255;
    public static final double BODYWORK_STEERING_WHEEL_ANGEL_MAX = 780.0d;
    public static final double BODYWORK_STEERING_WHEEL_ANGEL_MIN = -780.0d;
    public static final double BODYWORK_STEERING_WHEEL_SPEED_MAX = 1016.0d;
    public static final double BODYWORK_STEERING_WHEEL_SPEED_MIN = 0.0d;
    public static final int CLOSE_WINDOW_FOR_RAIN_INVALID = 0;
    public static final int CLOSE_WINDOW_FOR_RAIN_OFF = 2;
    public static final int CLOSE_WINDOW_FOR_RAIN_ON = 1;
    public static final int CONFIG_ANTI_PINCH_MOON_ROOF = 3;
    public static final int CONFIG_MOON_ROOF_SUNSHADE_PANEL = 1;
    public static final int CONFIG_NONE = 0;
    public static final int CONFIG_SUNSHADE_PANEL = 2;
    public static final int DATA_FLAG_INVALID = 0;
    public static final int DATA_FLAG_VALID = 1;
    public static final int DATA_STWHEEL_SENSOR_CALIBRATION_STATE = 1;
    public static final int DATA_STWHEEL_SENSOR_STATE = 0;
    private static final boolean DEBUG = true;
    public static final int DEVICE_HAS_THE_FEATURE = 1;
    public static final int DEVICE_NOT_HAS_THE_FEATURE = 0;
    public static final int DEVICE_THE_FEATURE_LINK_ERROR = 65535;
    public static final int DEVICE_THE_FEATURE_NEVER_GET = 2;
    public static final int ENERGY_TYPE_EV = 1;
    public static final int ENERGY_TYPE_FUEL = 3;
    public static final int ENERGY_TYPE_HEV = 2;
    public static final String FEATURE_MOON_ROOF = "MoonRoof";
    public static final String FEATURE_RAIN_CLOSE_WINDOW = "RainAutoCloseWindow";
    public static final int HAS_0x005500 = 24;
    public static final int HAS_0x00D500 = 33;
    public static final int HAS_0x010D00 = 49;
    public static final int HAS_0x012300 = 18;
    public static final int HAS_0x012F00 = 5;
    public static final int HAS_0x013300 = 42;
    public static final int HAS_0x013B00 = 4;
    public static final int HAS_0x013F00 = 54;
    public static final int HAS_0x014400 = 72;
    public static final int HAS_0x015100 = 69;
    public static final int HAS_0x01C000 = 55;
    public static final int HAS_0x01D100 = 48;
    public static final int HAS_0x021800 = 43;
    public static final int HAS_0x024500 = 28;
    public static final int HAS_0x024A00 = 53;
    public static final int HAS_0x024C00 = 17;
    public static final int HAS_0x025D00 = 73;
    public static final int HAS_0x026F00 = 74;
    public static final int HAS_0x029400 = 56;
    public static final int HAS_0x030D00 = 46;
    public static final int HAS_0x032100 = 19;
    public static final int HAS_0x033400 = 51;
    public static final int HAS_0x034200 = 52;
    public static final int HAS_0x034F00 = 20;
    public static final int HAS_0x038A00 = 57;
    public static final int HAS_0x039400 = 44;
    public static final int HAS_0x039600 = 6;
    public static final int HAS_0x03AC00 = 37;
    public static final int HAS_0x03AD00 = 38;
    public static final int HAS_0x03B300 = 50;
    public static final int HAS_0x03B400 = 29;
    public static final int HAS_0x03C100 = 41;
    public static final int HAS_0x03CD00 = 47;
    public static final int HAS_0x03D901 = 80;
    public static final int HAS_0x03D902 = 81;
    public static final int HAS_0x03D903 = 82;
    public static final int HAS_0x03D904 = 83;
    public static final int HAS_0x03D905 = 84;
    public static final int HAS_0x03D906 = 85;
    public static final int HAS_0x03D911 = 86;
    public static final int HAS_0x03D955 = 34;
    public static final int HAS_0x03D956 = 87;
    public static final int HAS_0x03D958 = 88;
    public static final int HAS_0x03D959 = 89;
    public static final int HAS_0x03D95A = 90;
    public static final int HAS_0x03D95B = 91;
    public static final int HAS_0x03D95C = 92;
    public static final int HAS_0x03D95D = 93;
    public static final int HAS_0x03D9AA = 94;
    public static final int HAS_0x03F100 = 40;
    public static final int HAS_0x03FF00 = 8;
    public static final int HAS_0x040100 = 58;
    public static final int HAS_0x040400 = 59;
    public static final int HAS_0x040700 = 60;
    public static final int HAS_0x040800 = 39;
    public static final int HAS_0x040D00 = 1;
    public static final int HAS_0x041700 = 26;
    public static final int HAS_0x041800 = 11;
    public static final int HAS_0x041A00 = 61;
    public static final int HAS_0x041C00 = 62;
    public static final int HAS_0x041E00 = 30;
    public static final int HAS_0x042900 = 22;
    public static final int HAS_0x042E00 = 63;
    public static final int HAS_0x043200 = 10;
    public static final int HAS_0x043300 = 3;
    public static final int HAS_0x043400 = 64;
    public static final int HAS_0x043800 = 32;
    public static final int HAS_0x044700 = 106;
    public static final int HAS_0x046C00 = 70;
    public static final int HAS_0x047000 = 95;
    public static final int HAS_0x047500 = 15;
    public static final int HAS_0x048004 = 35;
    public static final int HAS_0x048100 = 36;
    public static final int HAS_0x048600 = 65;
    public static final int HAS_0x048E00 = 66;
    public static final int HAS_0x048F00 = 9;
    public static final int HAS_0x049400 = 67;
    public static final int HAS_0x049C00 = 27;
    public static final int HAS_0x04A400 = 68;
    public static final int HAS_0x04A501 = 96;
    public static final int HAS_0x04A502 = 97;
    public static final int HAS_0x04A503 = 98;
    public static final int HAS_0x04A504 = 99;
    public static final int HAS_0x04A505 = 100;
    public static final int HAS_0x04A506 = 71;
    public static final int HAS_0x04A507 = 101;
    public static final int HAS_0x04A508 = 102;
    public static final int HAS_0x04A509 = 103;
    public static final int HAS_0x04A50A = 104;
    public static final int HAS_0x04A50B = 105;
    public static final int HAS_0x04B600 = 7;
    public static final int HAS_0x04B800 = 45;
    public static final int HAS_0x04BF00 = 25;
    public static final int HAS_0x04C201 = 12;
    public static final int HAS_0x04C202 = 13;
    public static final int HAS_0x04C203 = 14;
    public static final int HAS_0x04C800 = 31;
    public static final int HAS_0x04D902 = 21;
    public static final int HAS_0x04F600 = 2;
    public static final int HAS_0x04FA00 = 23;
    public static final int HAS_0x04FB00 = 16;
    public static final double MAX_BATTERY_POWER = 25.5d;
    public static final int MCU_RESTART_INVALID = 0;
    public static final int MCU_RESTART_VALID = 1;
    public static final int MESSAGE_55 = 1;
    public static final int MESSAGE_OFFLINE = 0;
    public static final int MESSAGE_ONLINE = 1;
    public static final double MIN_BATTERY_POWER = 0.0d;
    public static final int MOONROOF_BREATH = 253;
    public static final int MOONROOF_CLOSED = 0;
    public static final int MOONROOF_COMFORTABLE = 252;
    public static final int MOONROOF_INVALID = 255;
    public static final int MOONROOF_MIN = 21;
    public static final int MOONROOF_OPEN = 100;
    public static final int MOONROOF_STOP = 254;
    public static final int SMART_VOICE_LIMIT_INVALID = 0;
    public static final int SMART_VOICE_LIMIT_VALID = 1;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_INVALID = 0;
    public static final int STATE_UNINITIALIZED = 2;
    public static final int SUNROOF_CLOSE = 4;
    public static final int SUNROOF_CLOSE_NOTICE_CLOSE = 1;
    public static final int SUNROOF_CLOSE_NOTICE_INVALID = 0;
    public static final int SUNROOF_INVALID = 0;
    public static final int SUNROOF_OPEN = 3;
    public static final int SUNROOF_POSITION_COMFORTABLE = 6;
    public static final int SUNROOF_POSITION_FULL_CLOSE = 2;
    public static final int SUNROOF_POSITION_FULL_OPEN = 1;
    public static final int SUNROOF_POSITION_HALF_OPEN = 3;
    public static final int SUNROOF_POSITION_INVALID = 0;
    public static final int SUNROOF_POSITION_STOP = 4;
    public static final int SUNROOF_POSITION_UPDIP = 5;
    public static final int SUNROOF_STOP = 1;
    public static final int SUNROOF_TILTUP = 2;
    public static final int SUNSHADE_INVALID = 255;
    public static final int SUNSHADE_STOP = 254;
    protected static final String TAG = "BYDAutoBodyworkDevice";
    public static final int VOICE_CMD_CLOSE = 2;
    public static final int VOICE_CMD_HALF_OPEN = 3;
    public static final int VOICE_CMD_OPEN = 1;
    public static final int VOICE_CMD_STOP = 4;
    public static final int VOICE_CMD_VENTILATE = 5;
    public static final int WINDOW_BREATH = 5;
    public static final int WINDOW_CLOSE = 2;
    public static final int WINDOW_DISABLE = 1;
    public static final int WINDOW_ENABLE = 0;
    public static final int WINDOW_INVALID = 0;
    public static final int WINDOW_OPEN_FULL = 1;
    public static final int WINDOW_OPEN_HALF = 4;
    public static final int WINDOW_OPEN_PERCENT_MAX = 100;
    public static final int WINDOW_OPEN_PERCENT_MIN = 0;
    public static final int WINDOW_STOP = 3;
    private static BYDAutoBodyworkDevice mInstance;
    private int autoSystemState = 1;
    private final String autoVin = UUID.randomUUID().toString();
    private final Context context;
    private final List<AbsBYDAutoBodyworkListener> listeners = new ArrayList();
    private int powerLevel = 2;

    private BYDAutoBodyworkDevice(Context context2) {
        super(context2);
        this.context = context2;
    }

    public static synchronized BYDAutoBodyworkDevice getInstance(Context context2) {
        BYDAutoBodyworkDevice bYDAutoBodyworkDevice;
        synchronized (BYDAutoBodyworkDevice.class) {
            if (mInstance == null) {
                mInstance = new BYDAutoBodyworkDevice(context2);
            }
            bYDAutoBodyworkDevice = mInstance;
        }
        return bYDAutoBodyworkDevice;
    }

    public int getAutoSystemState() {
        return this.autoSystemState;
    }

    public String getAutoVIN() {
        return this.autoVin;
    }

    public int getBatteryCapacity() {
        return 0;
    }

    public double getBatteryPowerHEV() {
        return 0.0d;
    }

    public int getBatteryPowerValue() {
        return 0;
    }

    public int getBatteryVoltageLevel() {
        return 0;
    }

    public int getPowerLevel() {
        return this.powerLevel;
    }

    public double getSteeringWheelValue(int i) {
        return 0.0d;
    }

    public int getType() {
        return 1007; // TYPE_VERTICAL_TEXT constant value
    }

    public void registerListener(AbsBYDAutoBodyworkListener absBYDAutoBodyworkListener) {
        if (absBYDAutoBodyworkListener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(absBYDAutoBodyworkListener)) {
                    this.listeners.add(absBYDAutoBodyworkListener);
                }
            }
        }
    }
    
    public void unregisterListener(AbsBYDAutoBodyworkListener absBYDAutoBodyworkListener) {
        if (absBYDAutoBodyworkListener != null) {
            synchronized (this.listeners) {
                this.listeners.remove(absBYDAutoBodyworkListener);
            }
        }
    }
}
