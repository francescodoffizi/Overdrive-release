package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.GearMonitor;

/**
 * Decides whether a motion-sensitive vehicle action is safe to run right now.
 *
 * <p>Split into a pure decision function ({@link #isBlocked}) and a live wrapper
 * ({@link #isMovementBlocked()}), mirroring {@code roadsense.source.VehicleStateGate} —
 * keeping the policy pure means it's unit-testable without Android/HAL dependencies,
 * which matters because mis-gating here means either a false sense of safety (allowed
 * when it shouldn't be) or breaking normal parked remote control (blocked when it
 * shouldn't be).
 *
 * <p>Callers ask only the raw fact ({@code isMovementBlocked(): boolean}) — this class
 * knows nothing about {@link VehicleCommandRouter.VehicleCommand} or risk tiers, so it
 * can gate both the router's dispatch path and the unrelated screen/media endpoints in
 * {@code VehicleControlApiHandler} without either needing to know about the other.
 */
public final class DrivingSafetyGuard {

    private DrivingSafetyGuard() {}

    /**
     * Gear reads P but speed is still above this, we still treat it as moving
     * ("rolling in Park"). ~0.5 m/s (TripDetector's GPS threshold) converted to
     * km/h with a small margin.
     */
    private static final double PARKED_SPEED_THRESHOLD_KMH = 2.0;

    /** How stale a GearMonitor snapshot can be before we stop trusting it and
     *  fall back to the BydDataCollector snapshot instead. Matches GearMonitor's
     *  own poll cadence. */
    private static final long GEAR_FRESHNESS_MS = 1000L;

    enum GearReading { PARK, NOT_PARK, UNKNOWN }

    /**
     * Pure policy — no Android, no singletons, no side effects. Package-visible
     * so tests can exercise it directly.
     *
     * <p>Fail-closed by design: every ambiguous case (unauthoritative ACC, unknown
     * gear, missing speed) returns {@code true} (blocked). This is a deliberate
     * deviation from this codebase's usual "degrade gracefully" convention for
     * not-yet-ready subsystems — for a safety gate, defaulting an unknown state to
     * "allow" would defeat the point of the gate.
     */
    static boolean isBlocked(GearReading gear, boolean accOn, boolean accAuthoritative, double speedKmh) {
        if (accAuthoritative && !accOn) return false;  // ACC confidently OFF -> parked -> never blocked
        if (!accAuthoritative) return true;             // genuinely unknown (cold boot) -> fail closed
        if (gear != GearReading.PARK) return true;       // includes NOT_PARK and UNKNOWN -> fail closed
        if (Double.isNaN(speedKmh)) return true;         // can't cross-check "still rolling in P" -> fail closed
        return speedKmh > PARKED_SPEED_THRESHOLD_KMH;     // gear P but still moving -> blocked
    }

    /** Live wrapper — reads AccMonitor / GearMonitor / BydDataCollector singletons. */
    public static boolean isMovementBlocked() {
        return isBlocked(resolveGear(), AccMonitor.isAccOn(), AccMonitor.isAccStateAuthoritative(), resolveSpeedKmh());
    }

    private static GearReading resolveGear() {
        GearMonitor gm = GearMonitor.getInstance();
        if (gm.isRunning()) {
            long age = System.currentTimeMillis() - gm.getLastUpdateTime();
            if (age >= 0 && age < GEAR_FRESHNESS_MS) {
                return gm.getCurrentGear() == GearMonitor.GEAR_P ? GearReading.PARK : GearReading.NOT_PARK;
            }
            // Poller running but the last read is stale — fall through to the
            // collector snapshot rather than trusting a possibly-outdated value.
        }
        // Poller not running (normally ACC-off — already resolved by the
        // accAuthoritative branch in isBlocked() before this is reached in that
        // case) or a cold-boot race where GearMonitor hasn't started yet. Fall
        // back to the last BYD data snapshot instead of assuming PARK.
        BydVehicleData d = BydDataCollector.getInstance().getData();
        if (d != null && d.gearMode != BydVehicleData.UNAVAILABLE) {
            return d.gearMode == GearMonitor.GEAR_P ? GearReading.PARK : GearReading.NOT_PARK;
        }
        return GearReading.UNKNOWN;
    }

    private static double resolveSpeedKmh() {
        BydVehicleData d = BydDataCollector.getInstance().getData();
        return d != null ? d.speedKmh : Double.NaN;
    }
}
