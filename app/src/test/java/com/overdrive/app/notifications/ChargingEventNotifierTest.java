package com.overdrive.app.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ChargingEventNotifierTest {

    @Test
    public void stoppedAndNewerSessionGenerationsRejectOldFullChecks() {
        ChargingEventNotifier.FullSessionState state =
                new ChargingEventNotifier.FullSessionState();
        AtomicInteger publications = new AtomicInteger();

        ChargingEventNotifier.SessionEdge first =
                state.onEdge(true, 40.0);
        assertTrue(first.changed);
        state.onEdge(false, Double.NaN);
        state.checkAndPublish(
                first.generation, 99.0, 1000L,
                ignored -> publications.incrementAndGet());
        assertEquals(0, publications.get());

        ChargingEventNotifier.SessionEdge second =
                state.onEdge(true, 60.0);
        state.checkAndPublish(
                first.generation, 99.0, 2000L,
                ignored -> publications.incrementAndGet());
        assertEquals(0, publications.get());

        state.checkAndPublish(
                second.generation, 99.0, 3000L,
                ignored -> publications.incrementAndGet());
        assertEquals(1, publications.get());
        state.checkAndPublish(
                second.generation, 100.0, 4000L,
                ignored -> publications.incrementAndGet());
        assertEquals(1, publications.get());
    }

    @Test
    public void duplicateEdgeAdvancesGenerationWithoutRepublishingStart() {
        ChargingEventNotifier.FullSessionState state =
                new ChargingEventNotifier.FullSessionState();
        ChargingEventNotifier.SessionEdge first =
                state.onEdge(true, 50.0);
        ChargingEventNotifier.SessionEdge duplicate =
                state.onEdge(true, 50.0);

        assertTrue(first.changed);
        assertFalse(duplicate.changed);
        assertTrue(duplicate.generation > first.generation);
    }

    @Test
    public void delayedStartSocCannotInitializeANewerSession() {
        ChargingEventNotifier.FullSessionState state =
                new ChargingEventNotifier.FullSessionState();
        AtomicInteger publications = new AtomicInteger();

        ChargingEventNotifier.SessionEdge first =
                state.onEdge(true, Double.NaN);
        state.onEdge(false, Double.NaN);
        ChargingEventNotifier.SessionEdge second =
                state.onEdge(true, Double.NaN);

        state.initializeStartSoc(first.generation, 99.0);
        state.initializeStartSoc(second.generation, 40.0);
        state.checkAndPublish(
                second.generation, 99.0, 1000L,
                ignored -> publications.incrementAndGet());

        assertEquals(1, publications.get());
    }
}
