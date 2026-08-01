package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.routing.VehicleCommandRouter.MirrorFoldCommand;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Outcome;

import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for the BLIND-TOGGLE cache on a set-only switch (mirror fold).
 *
 * <p>Mirror fold has no state getter on this platform, so a {@code "toggle"} payload is
 * resolved by flipping the last value we COMMANDED, cached in
 * {@code VehicleControlCatalog.LAST_SWITCH_PAYLOAD}. The cache used to be written inside
 * {@code toAction(...)} — at command-BUILD time, before the command ran — so even a press the
 * motion-safety gate refused outright still advanced it, silently swapping what the next press
 * would do. It is now deferred onto the {@code ControlAction} and applied via
 * {@code commitIfAttempted(outcome)}.
 *
 * <p>The bar is deliberately "did it reach the vehicle", NOT "did it succeed": a control with
 * no readback that only commits on SUCCESS freezes on one value forever when the HAL cannot
 * confirm, making the opposite action unreachable. These tests pin both halves of that
 * contract — attempted outcomes advance the cache, pre-vehicle refusals do not.
 */
public class MirrorFoldToggleCacheTest {

    /**
     * The blind-toggle cache is a process-wide static in {@link VehicleControlCatalog}, so
     * without this every test would inherit whatever the previously-run test left behind and
     * the suite would pass or fail depending on method order. Clear the one key under test so
     * each case starts from the real cold-boot state ("nothing commanded yet").
     */
    @Before
    public void clearBlindToggleCache() throws Exception {
        java.lang.reflect.Field f =
                VehicleControlCatalog.class.getDeclaredField("LAST_SWITCH_PAYLOAD");
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).remove("mirror_fold");
    }

    private static VehicleControlCatalog.ControlEntity mirrorFold() {
        VehicleControlCatalog.ControlEntity e = VehicleControlCatalog.get("mirror_fold");
        assertNotNull("mirror_fold must be registered in the catalog", e);
        return e;
    }

    /** A press the vehicle layer never saw must not advance the cache. */
    @Test
    public void toggleDoesNotAdvanceCacheUntilCommitted() {
        VehicleControlCatalog.ControlEntity e = mirrorFold();

        // First toggle: nothing cached yet, so it resolves to ON (fold).
        VehicleControlCatalog.ControlAction a1 = e.toAction(null, "toggle", null);
        assertNotNull(a1);
        assertTrue(a1.command instanceof MirrorFoldCommand);
        assertTrue("first blind toggle should command FOLD", ((MirrorFoldCommand) a1.command).fold);

        // Refused BEFORE reaching the car (motion-safety gate): nothing was commanded, so the
        // next press must still be FOLD rather than flipping to UNFOLD.
        a1.commitIfAttempted(Outcome.BLOCKED_DRIVING);
        VehicleControlCatalog.ControlAction a2 = e.toAction(null, "toggle", null);
        assertNotNull(a2);
        assertTrue("a press blocked before the vehicle must not flip the next press",
                ((MirrorFoldCommand) a2.command).fold);

        // Same for every other never-reached-the-car outcome. NOT_SUPPORTED belongs here: the
        // router returns it from its capability guards BEFORE invoking any leg (no SDK path /
        // no cloud path / no legs at all), so it does not imply an attempt.
        //
        // Structure matters: commit FIRST, then assert on the NEXT built action. An
        // assert-then-commit loop never checks the final element — the last commit would be
        // followed by no assertion, so re-adding that outcome to commitIfAttempted would leave
        // the suite green.
        for (Outcome never : new Outcome[]{
                Outcome.RATE_LIMITED, Outcome.AUTH_REQUIRED, Outcome.NOT_SUPPORTED}) {
            VehicleControlCatalog.ControlAction a = e.toAction(null, "toggle", null);
            assertNotNull(a);
            a.commitIfAttempted(never);

            VehicleControlCatalog.ControlAction next = e.toAction(null, "toggle", null);
            assertNotNull(next);
            assertTrue("outcome " + never + " must not advance the blind-toggle cache",
                    ((MirrorFoldCommand) next.command).fold);
        }
    }

    /**
     * A command that reached the car but could not be CONFIRMED must still advance the cache.
     * Committing only on SUCCESS would freeze this control on "fold" forever on any HAL that
     * cannot confirm the write, making unfold unreachable via toggle.
     */
    @Test
    public void attemptedButUnconfirmedWriteStillFlipsNextPress() {
        VehicleControlCatalog.ControlEntity e = mirrorFold();

        VehicleControlCatalog.ControlAction a1 = e.toAction(null, "toggle", null);
        assertNotNull(a1);
        assertTrue(((MirrorFoldCommand) a1.command).fold);
        a1.commitIfAttempted(Outcome.FAILED);   // asked the car; outcome unknown/refused

        VehicleControlCatalog.ControlAction a2 = e.toAction(null, "toggle", null);
        assertNotNull(a2);
        assertEquals("an attempted-but-unconfirmed FOLD must still flip the next press to UNFOLD",
                false, ((MirrorFoldCommand) a2.command).fold);
        a2.commitIfAttempted(Outcome.SUCCESS);

        VehicleControlCatalog.ControlAction a3 = e.toAction(null, "toggle", null);
        assertNotNull(a3);
        assertTrue("and back to FOLD after that", ((MirrorFoldCommand) a3.command).fold);
        a3.commitIfAttempted(Outcome.SUCCESS);
    }

    /** Once the caller commits a successful write, the next toggle flips. */
    @Test
    public void committedToggleFlipsNextPress() {
        VehicleControlCatalog.ControlEntity e = mirrorFold();

        VehicleControlCatalog.ControlAction a1 = e.toAction(null, "toggle", null);
        assertNotNull(a1);
        assertTrue(((MirrorFoldCommand) a1.command).fold);
        a1.commitToggleState();   // command succeeded

        VehicleControlCatalog.ControlAction a2 = e.toAction(null, "toggle", null);
        assertNotNull(a2);
        assertEquals("after a committed FOLD, the next toggle must UNFOLD",
                false, ((MirrorFoldCommand) a2.command).fold);
        a2.commitToggleState();

        VehicleControlCatalog.ControlAction a3 = e.toAction(null, "toggle", null);
        assertNotNull(a3);
        assertTrue("and back to FOLD on the press after that",
                ((MirrorFoldCommand) a3.command).fold);
        a3.commitToggleState();
    }

    /** An explicit on/off payload also only records once committed, and commit is null-safe. */
    @Test
    public void explicitPayloadCommitIsNullSafeAndRecorded() {
        VehicleControlCatalog.ControlEntity e = mirrorFold();

        VehicleControlCatalog.ControlAction on = e.toAction(null, "on", null);
        assertNotNull(on);
        assertTrue(((MirrorFoldCommand) on.command).fold);
        on.commitToggleState();

        // Explicit ON was committed, so a following toggle must flip to OFF.
        VehicleControlCatalog.ControlAction t = e.toAction(null, "toggle", null);
        assertNotNull(t);
        assertEquals(false, ((MirrorFoldCommand) t.command).fold);

        // commitToggleState must be a harmless no-op when called twice, and on an action
        // with no deferred hook at all (ControlAction.of(...) style entities).
        t.commitToggleState();
        t.commitToggleState();
    }
}
