package com.overdrive.app.charging;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guards for dashboard/session behavior exercised in the embedded WebView. */
public class ChargingFrontendContractTest {

    @Test
    public void dashboardClearsMissingStateAndPrioritizesActiveTaper() throws IOException {
        String dashboard = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(dashboard.contains("c.charging ? 'charge.state_charging'"));
        assertTrue(dashboard.contains("c.full ? 'charge.state_full'"));
        assertTrue(dashboard.indexOf("c.charging ? 'charge.state_charging'")
                < dashboard.indexOf("c.full ? 'charge.state_full'"));
        assertTrue(dashboard.contains("idleDot.className = 'ic_status_dot dot-idle'"));
        assertTrue(dashboard.contains("setText('dashChargePower', '--')"));
        assertTrue(dashboard.contains(
                "c.powerSource !== 'nominalPlaceholder'"));
        assertTrue(dashboard.contains(
                "(c.isEstimated ? '~' : '') + dashboardPower.toFixed(1) + ' kW'"));
        assertTrue(dashboard.contains("setText('dashChargeKwh', '--')"));
        assertTrue(dashboard.contains("setText('dashChargeTtf', '--')"));
    }

    @Test
    public void activeSessionCannotOfferDeleteAndFailedClearIsNotCelebrated() throws IOException {
        String charging = readRepositoryFile("app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(charging.contains("(inProgress ? '' :"));
        assertTrue(html.contains("id=\"detailDeleteBtn\""));
        assertTrue(charging.contains("this._detailInProgress = true"));
        assertTrue(charging.contains("deleteBtn.style.display = 'none'"));
        assertTrue(charging.contains("deleteBtn.disabled = inProgress"));
        assertTrue(charging.contains("deleteBtn.style.display = inProgress ? 'none' : ''"));
        assertTrue(charging.contains("!this._detailInProgress"));
        assertTrue(charging.contains("body.success !== true"));
        assertTrue(charging.contains("'charge.clear_failed'"));
    }

    @Test
    public void failedBootstrapAndDirectLoadsPreserveRenderedState()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "if (!response.ok) throw new Error"));
        assertTrue(charging.contains(
                "if (!data || data.error || data.success === false) return null"));
        assertTrue(charging.contains(
                "var sessions = self._payload(b.sessions, 'sessions', true, false)"));
        assertTrue(charging.contains(
                "var sessions = self._payload(d, 'sessions', true, true)"));
        assertTrue(charging.contains(
                "if (sessions === null) throw new Error('invalid sessions payload')"));
        assertTrue(charging.contains(
                "self._hideSkeleton();\n"
                        + "                    return false;"));
        assertTrue(charging.contains("self._loadCurrentLivePair()"));
        assertTrue(charging.contains(
                "var summary = self._payload(d, 'summary', false, true)"));
        assertTrue(charging.contains(
                "var soc = self._payload(d, 'soc', true, true)"));
        assertTrue(charging.contains(
                "var config = self._payload(d, 'config', false, true)"));
        assertTrue(!charging.contains(
                "self._applySessions(d.sessions || [], offset)"));
    }

    @Test
    public void asynchronousViewsUseGenerationAndPeriodGuards()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "var generation = ++this._detailGeneration"));
        assertTrue(charging.contains(
                "self._isCurrentDetail(id, generation)"));
        assertTrue(charging.contains(
                "String(this.currentSessionId)\n"
                        + "                === String(this._detailSessionId)"));
        assertTrue(charging.contains(
                "periodKey !== self._periodKey()"));
        assertTrue(charging.contains(
                "generation !== self._sessionsGeneration"));
        assertTrue(charging.contains(
                "this._sessionsLoadMorePending"));
        assertTrue(charging.contains(
                "generation !== self._socGeneration"));
        assertTrue(charging.contains(
                "generation !== self._tariffsGeneration"));
        assertTrue(charging.contains(
                "tariffsGeneration === self._tariffsGeneration"));
    }

    @Test
    public void visibleRefreshIsSingleFlightAndOpenRowsUseLiveVerdict()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");

        assertTrue(charging.contains("_liveRefreshInFlight"));
        assertTrue(charging.contains(
                "if (this._liveRefreshInFlight) return this._liveRefreshInFlight"));
        assertTrue(charging.contains(
                "this._summaryGeneration++"));
        assertTrue(charging.contains(
                "this._sessionsGeneration++"));
        assertTrue(charging.contains(
                "'/api/charging/overview?' + periodKey"));
        assertTrue(charging.contains(
                "var summary = self._payload(\n"
                        + "                d, 'summary', false, true)"));
        assertTrue(charging.contains(
                "var sessions = self._payload(\n"
                        + "                d, 'sessions', true, true)"));
        assertTrue(charging.contains(
                "var chargingNow = inProgress && s.chargingNow !== false"));
        assertTrue(charging.contains(
                "this._setText('detailTimeToFull', (chargingNow"));
        assertTrue(charging.contains(
                "String(detailRow.id)\n"
                        + "                            === String(this.currentSessionId)"));
        assertTrue(charging.contains(
                "this._fillDetailHeader(detailRow, detailRow.id)"));
        assertTrue(core.contains("if (!isCharging) powerKW = 0"));
        assertTrue(core.contains(
                "powerSource !== 'nominalPlaceholder'"));
        assertTrue(core.contains(
                "(isEstimated ? '~' : '')"));
        assertTrue(core.contains("evPower.textContent = '-- kW'"));
    }

    @Test
    public void tariffMutationWarnsWhenHistoryCommitIsNotConfirmed()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "d.repricingStatus !== 'complete'"));
        assertTrue(charging.contains(
                "'Tariff saved. Past charges will be re-priced automatically when storage is ready.'"));
        assertTrue(charging.contains(
                "this._toast(warning, warningType)"));
        assertTrue(charging.contains(
                "d.success || d.tariffSaved"));
    }

    @Test
    public void configRefreshAndRejectedSavePreserveDirtyFields()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "var body = this._dirtyConfigBody()"));
        assertTrue(charging.contains(
                "if (this._configDirty[key]) body[key] = current[key]"));
        assertTrue(charging.contains(
                "if (!dirtyBeforeResponse[key])"));
        assertTrue(charging.contains(
                "if (!r.ok || !d || d.success !== true)"));
        assertTrue(charging.contains(
                "Keep the rejected values and original durable baseline"));
        assertTrue(!charging.contains(
                "self.resetApplyButton();\n"
                        + "              self.loadConfig();"));
    }

    @Test
    public void bootstrapMatchesInitiallyActiveSevenDayControls()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/charging.html");

        assertTrue(charging.contains("currentDays: 7"));
        assertTrue(charging.contains("socHours: 168"));
        assertTrue(charging.contains(
                "'&hours=' + hours"));
        assertTrue(html.contains(
                "class=\"filter-tab active\" data-days=\"7\""));
        assertTrue(html.contains(
                "class=\"filter-tab active\" data-hours=\"168\""));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
