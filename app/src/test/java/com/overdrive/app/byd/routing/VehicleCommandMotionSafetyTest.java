package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasEspCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasItacCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasLaneAssistCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasSpeedLimitWarningCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AmbientColourCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.BatteryHeatCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.BrakeFeelCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CarSettingCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ChargeCapPercentCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ChargeCapToggleCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ChargeScheduleCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ChildLockCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateOffCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateOnCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateSetFanCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateSetTempCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CloseAllWindowsCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.byd.routing.VehicleCommandRouter.EnergyFeedbackCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.EnergyModeCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.FindCarCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.FlashLightsCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.LightsCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.LockCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.OperationModeCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Outcome;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Path;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SeatHeatCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SeatMemoryCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SeatVentCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SettingChildPresenceDetectionCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SmartChargingToggleCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SteerAssistCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SunroofCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SunshadeCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkCloseCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkOpenCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkOpenSdkCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkStopCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.UnlockCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand.MotionSafety;
import com.overdrive.app.byd.routing.VehicleCommandRouter.WindowMoveCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.WirelessChargingCommand;

import org.junit.Test;

/**
 * Regression guard against tier misclassification: every VehicleCommand subclass
 * must report the risk tier decided in the driving-safety-gate plan. A command
 * silently landing in the wrong tier (e.g. AdasEspCommand becoming UNRESTRICTED)
 * would defeat the whole gate without any other test catching it.
 */
public class VehicleCommandMotionSafetyTest {

    // ── BLOCK_WHILE_MOVING: safety/physical-actuation/ADAS/drive-feel ──────

    @Test public void lockIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new LockCommand().motionSafety()); }
    @Test public void unlockIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new UnlockCommand().motionSafety()); }
    @Test public void findCarIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new FindCarCommand().motionSafety()); }
    @Test public void flashLightsIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new FlashLightsCommand().motionSafety()); }
    @Test public void trunkOpenIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkOpenCommand().motionSafety()); }
    @Test public void trunkOpenSdkIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkOpenSdkCommand().motionSafety()); }
    @Test public void trunkCloseIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkCloseCommand().motionSafety()); }
    @Test public void adasEspIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new AdasEspCommand(false).motionSafety()); }
    @Test public void adasItacIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new AdasItacCommand(false).motionSafety()); }
    @Test public void adasLaneAssistIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new AdasLaneAssistCommand(0).motionSafety()); }
    @Test public void adasSpeedLimitWarningIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new AdasSpeedLimitWarningCommand(false).motionSafety()); }
    @Test public void drlLightsIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new LightsCommand(false).motionSafety()); }
    @Test public void energyFeedbackIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new EnergyFeedbackCommand(1).motionSafety()); }
    @Test public void brakeFeelIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new BrakeFeelCommand(1).motionSafety()); }
    @Test public void operationModeIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new OperationModeCommand(1).motionSafety()); }
    @Test public void energyModeIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new EnergyModeCommand(1).motionSafety()); }
    @Test public void steerAssistIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new SteerAssistCommand(1).motionSafety()); }

    @Test public void seatMemoryRecallIsBlocked() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new SeatMemoryCommand(1, false).motionSafety());
    }

    @Test public void carSettingEspAssistIsBlocked() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new CarSettingCommand("esp_assist", 0).motionSafety());
    }
    @Test public void carSettingAebIsBlocked() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new CarSettingCommand("aeb", 0).motionSafety());
    }
    @Test public void carSettingLaneKeepingIsBlocked() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new CarSettingCommand("lane_keeping", 0).motionSafety());
    }
    @Test public void carSettingUnrecognizedKeyDefaultsToBlocked() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new CarSettingCommand("some_future_key", 0).motionSafety());
    }

    // ── UNRESTRICTED: comfort/preference, no acute driving-safety impact ───

    @Test public void climateOnIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ClimateOnCommand(22).motionSafety()); }
    @Test public void climateOffIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ClimateOffCommand().motionSafety()); }
    @Test public void climateSetTempIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ClimateSetTempCommand(1, 22).motionSafety()); }
    @Test public void climateSetFanIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ClimateSetFanCommand(3).motionSafety()); }
    @Test public void seatHeatIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SeatHeatCommand(1, 1, 0, 0, 0, 0).motionSafety()); }
    @Test public void seatVentIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SeatVentCommand(1, 1, 0, 0, 0, 0).motionSafety()); }
    @Test public void ambientColourIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new AmbientColourCommand(1).motionSafety()); }
    @Test public void wirelessChargingIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new WirelessChargingCommand(true).motionSafety()); }
    @Test public void batteryHeatIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new BatteryHeatCommand(true).motionSafety()); }
    @Test public void chargeScheduleIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ChargeScheduleCommand("00:00", "06:00", "s", true).motionSafety()); }
    @Test public void chargeCapPercentIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ChargeCapPercentCommand(80).motionSafety()); }
    @Test public void chargeCapToggleIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ChargeCapToggleCommand(true).motionSafety()); }
    @Test public void smartChargingToggleIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SmartChargingToggleCommand(true).motionSafety()); }
    @Test public void childPresenceDetectionIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SettingChildPresenceDetectionCommand(1).motionSafety()); }
    @Test public void childLockIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ChildLockCommand(true).motionSafety()); }
    @Test public void windowMoveIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new WindowMoveCommand(1, 1, null).motionSafety()); }
    @Test public void closeAllWindowsIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new CloseAllWindowsCommand().motionSafety()); }
    @Test public void sunroofIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SunroofCommand(1).motionSafety()); }
    @Test public void sunshadeIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SunshadeCommand(1).motionSafety()); }

    @Test public void trunkStopIsNeverBlocked() {
        // Abort/stop action — must always be able to halt an in-progress motor movement.
        assertEquals(MotionSafety.UNRESTRICTED, new TrunkStopCommand().motionSafety());
    }

    @Test public void seatMemorySaveIsUnrestricted() {
        assertEquals(MotionSafety.UNRESTRICTED, new SeatMemoryCommand(1, true).motionSafety());
    }

    @Test public void carSettingChargeLimitIsUnrestricted() {
        assertEquals(MotionSafety.UNRESTRICTED, new CarSettingCommand("charge_limit", 80).motionSafety());
    }

    // ── CommandResult.blocked() factory contract ────────────────────────

    @Test public void blockedResultHasExpectedShape() {
        CommandResult r = CommandResult.blocked("test message");
        assertEquals(Outcome.BLOCKED_DRIVING, r.outcome);
        assertEquals(Path.NONE, r.path);
        assertEquals("test message", r.displayMessage);
    }
}
