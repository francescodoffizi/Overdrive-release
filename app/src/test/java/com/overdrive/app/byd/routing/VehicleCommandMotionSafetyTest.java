package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasEmergencyBrakingCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasEspCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.AdasLaneAssistCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.BrakeFeelCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CarSettingCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateOnCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.byd.routing.VehicleCommandRouter.EnergyFeedbackCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.FindCarCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.FlashLightsCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.FrontDefrostCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.HazardCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.HeadlightModeCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.LightsCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.LockCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.MirrorAutoFollowUpCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.MirrorFoldCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.OperationModeCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Outcome;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Path;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SeatMemoryCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.SteerAssistCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkCloseCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkOpenCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkOpenSdkCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkStopCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.UnlockCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand.MotionSafety;
import com.overdrive.app.byd.routing.VehicleCommandRouter.WindowMoveCommand;

import org.junit.Test;

/**
 * Locks in the motion-safety policy: the gate blocks a small, explicit set of
 * commands or unsafe command directions. Most controls remain UNRESTRICTED and
 * work while the car moves.
 *
 * <p>The default is UNRESTRICTED, so these tests guard two regressions:
 * (1) a gated command silently losing its BLOCK_WHILE_MOVING override (the gate
 * stops protecting), and (2) some unrelated command gaining a block (the
 * over-broad gating we deliberately reverted). The base-default assertion pins
 * that a brand-new command ships UNRESTRICTED.
 *
 * <p>Three controls are gated DIRECTIONALLY: mirror fold and seat-memory recall move a physical
 * part, while headlight OFF removes exterior illumination. Their recovery/no-op directions remain
 * unrestricted, so each polarity is asserted explicitly.
 */
public class VehicleCommandMotionSafetyTest {

    // ── BLOCK_WHILE_MOVING: doors/trunk, plus the two directional body actuators ────────

    @Test public void lockIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new LockCommand().motionSafety()); }
    @Test public void unlockIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new UnlockCommand().motionSafety()); }
    @Test public void trunkOpenIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkOpenCommand().motionSafety()); }
    @Test public void trunkOpenSdkIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkOpenSdkCommand().motionSafety()); }
    @Test public void trunkCloseIsBlocked() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new TrunkCloseCommand().motionSafety()); }

    // ── UNRESTRICTED: everything else. A representative spread, weighted ────
    //    toward commands that were blocked in the earlier, broader design. ──

    // TrunkStop is an abort — must never be blocked, even though it's trunk-related.
    @Test public void trunkStopIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new TrunkStopCommand().motionSafety()); }

    // Horn/lights locate commands — no longer gated.
    @Test public void findCarIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new FindCarCommand().motionSafety()); }
    @Test public void flashLightsIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new FlashLightsCommand().motionSafety()); }

    // Mirror FOLD is gated (it removes rear-quarter vision mid-manoeuvre, with no readback), but
    // UNFOLD stays open — it is the recovery action, so blocking it would strand a moving driver.
    @Test public void mirrorFoldIsBlockedWhileMoving() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new MirrorFoldCommand(true).motionSafety()); }
    @Test public void mirrorUnfoldIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new MirrorFoldCommand(false).motionSafety()); }
    @Test public void mirrorAutoFollowUpIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new MirrorAutoFollowUpCommand(true).motionSafety()); }

    // ADAS toggles — including safety systems — are NOT motion-gated.
    @Test public void adasEspIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new AdasEspCommand(false).motionSafety()); }
    @Test public void adasLaneAssistIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new AdasLaneAssistCommand(0).motionSafety()); }
    @Test public void adasAebIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new AdasEmergencyBrakingCommand(false).motionSafety()); }

    // Exterior DRL — unrestricted.
    @Test public void drlLightsIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new LightsCommand(false).motionSafety()); }

    // The OEM selector blocks only OFF. Auto/parking/low-beam restore or increase visibility.
    @Test public void headlightOffIsBlockedWhileMoving() {
        assertEquals(MotionSafety.BLOCK_WHILE_MOVING,
                new HeadlightModeCommand(1).motionSafety());
    }
    @Test public void headlightAutoIsUnrestricted() {
        assertEquals(MotionSafety.UNRESTRICTED,
                new HeadlightModeCommand(2).motionSafety());
    }
    @Test public void headlightParkingIsUnrestricted() {
        assertEquals(MotionSafety.UNRESTRICTED,
                new HeadlightModeCommand(3).motionSafety());
    }
    @Test public void headlightLowBeamIsUnrestricted() {
        assertEquals(MotionSafety.UNRESTRICTED,
                new HeadlightModeCommand(4).motionSafety());
    }

    // Drive-dynamics / drive-feel — unrestricted.
    @Test public void operationModeIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new OperationModeCommand(1).motionSafety()); }
    @Test public void energyFeedbackIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new EnergyFeedbackCommand(1).motionSafety()); }
    @Test public void steerAssistIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SteerAssistCommand(1).motionSafety()); }
    @Test public void brakeFeelIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new BrakeFeelCommand(1).motionSafety()); }

    // Comfort / safety-visibility.
    @Test public void hazardIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new HazardCommand(true).motionSafety()); }
    @Test public void frontDefrostIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new FrontDefrostCommand(true).motionSafety()); }
    @Test public void climateOnIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new ClimateOnCommand(22).motionSafety()); }
    @Test public void windowMoveIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new WindowMoveCommand(1, 1, null).motionSafety()); }

    // Seat memory RECALL is gated: it drives the driver's seat rails/backrest at speed, moving the
    // person operating the vehicle. SAVE only records the current position and moves nothing.
    @Test public void seatMemoryRecallIsBlockedWhileMoving() { assertEquals(MotionSafety.BLOCK_WHILE_MOVING, new SeatMemoryCommand(1, false).motionSafety()); }
    @Test public void seatMemorySaveIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new SeatMemoryCommand(1, true).motionSafety()); }

    // The generic CarSetting writer is no longer gated for any key — including the
    // former safety-key allowlist (esp/aeb/lane) — matching the doors/trunk-only scope.
    @Test public void carSettingEspAssistIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new CarSettingCommand("esp_assist", 0).motionSafety()); }
    @Test public void carSettingAebIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new CarSettingCommand("aeb", 0).motionSafety()); }
    @Test public void carSettingArbitraryKeyIsUnrestricted() { assertEquals(MotionSafety.UNRESTRICTED, new CarSettingCommand("some_future_key", 0).motionSafety()); }

    @Test public void guardedCommandsExposeTheirUiSetting() {
        assertEquals(DrivingSafetyGuard.GUARD_DOOR_LOCKS,
                new LockCommand().motionSafetyGuardKey());
        assertEquals(DrivingSafetyGuard.GUARD_TRUNK,
                new TrunkOpenCommand().motionSafetyGuardKey());
        assertEquals(DrivingSafetyGuard.GUARD_MIRROR_FOLD,
                new MirrorFoldCommand(true).motionSafetyGuardKey());
        assertEquals(DrivingSafetyGuard.GUARD_POSITIONING,
                new SeatMemoryCommand(1, false).motionSafetyGuardKey());
        assertEquals(DrivingSafetyGuard.GUARD_HEADLIGHT_OFF,
                new HeadlightModeCommand(1).motionSafetyGuardKey());
    }

    // ── Base default: a brand-new command ships UNRESTRICTED ───────────────

    @Test public void unknownCommandDefaultsToUnrestricted() {
        VehicleCommandRouter.VehicleCommand custom = new VehicleCommandRouter.VehicleCommand() {
            public String name() { return "test-custom"; }
        };
        assertEquals(MotionSafety.UNRESTRICTED, custom.motionSafety());
    }

    // ── CommandResult.blocked() factory contract ────────────────────────

    @Test public void blockedResultHasExpectedShape() {
        CommandResult r = CommandResult.blocked("test message");
        assertEquals(Outcome.BLOCKED_DRIVING, r.outcome);
        assertEquals(Path.NONE, r.path);
        assertEquals("test message", r.displayMessage);
    }
}
