package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the web dashboard's stale-data and source-freshness contracts. */
public class DashboardFreshnessAssetTest {

    @Test
    public void vehicleStatePollingIsSingleFlightAndClearsAfterFailures()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("vehicleStateRequest: null"));
        assertTrue(page.contains("vehicleStateRefreshQueued: false"));
        assertTrue(page.contains(
                "if (this.vehicleStateRequest)"));
        assertTrue(page.contains(
                "fetch('/api/vehicle/state', { cache: 'no-store' })"));
        assertTrue(page.contains(
                "self.vehicleStateFailureCount++"));
        assertTrue(page.contains(
                "self.markVehicleStateUnavailable()"));
        assertTrue(page.contains(
                "this.updateVehicleState({"));
    }

    @Test
    public void vehicleStatePollingStopsWhileDashboardIsHidden()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("vehicleStatePollTimer: null"));
        assertTrue(page.contains("startVehicleStatePolling: function()"));
        assertTrue(page.contains("stopVehicleStatePolling: function()"));
        assertTrue(page.contains("initVehicleStatePolling: function()"));
        assertTrue(page.contains(
                "document.addEventListener('visibilitychange'"));
        assertTrue(page.contains("if (document.hidden)"));
        assertTrue(page.contains(
                "BYD.dashboard.initVehicleStatePolling()"));
        assertFalse(page.contains(
                "setInterval(function() { BYD.dashboard.refreshVehicleState(); }, 6000)"));
    }

    @Test
    public void speedRequiresPowerAndARecentProviderFix()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/index.html");
        String gps = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/GpsMonitor.java");

        assertTrue(page.contains("var gpsFresh = powerOn"));
        assertTrue(page.contains("gps.isFixStale !== true"));
        assertTrue(gps.contains("fixAgeMs"));
        assertTrue(gps.contains("isFixStale"));
        assertTrue(gps.contains("LIVE_SPEED_FIX_MAX_AGE_MS = 5_000L"));
        assertTrue(gps.contains("SystemClock.elapsedRealtime() - fix.fixElapsedMs"));
    }

    @Test
    public void unknownBatteryClimateAndStatusAreNotPresentedAsLive()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/index.html");
        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");

        assertTrue(page.contains("status.battery.isStale !== true"));
        assertTrue(page.contains(
                "setText('dash12v', batteryFresh"));
        assertTrue(core.contains("status.battery.isStale !== true"));
        assertTrue(core.contains("? voltage.toFixed(1) + 'V' : '--'"));
        assertTrue(page.contains("var climateKnown = remoteKnown || localKnown"));
        assertTrue(page.contains(": '--';"));
        assertTrue(page.contains("setStatusHealth: function(state)"));
        assertTrue(page.contains("BYD.core.pollFailureCount"));
        assertTrue(page.contains(
                ">= BYD.core.POLL_STALE_AFTER_FAILURES"));
    }

    @Test
    public void batteryFreshnessUsesVehicleObservationTime()
            throws IOException {
        String batteryMonitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/BatteryMonitor.java");
        String vehicleData = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/VehicleDataMonitor.java");

        assertTrue(vehicleData.contains(
                "bpJson.put(\"observedAtMs\", vd.voltage12vAtMs)"));
        assertTrue(batteryMonitor.contains(
                "batteryPower.optLong(\"observedAtMs\", 0L)"));
        assertTrue(batteryMonitor.contains(
                "lastBatteryUpdate = observedAtMs"));
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
