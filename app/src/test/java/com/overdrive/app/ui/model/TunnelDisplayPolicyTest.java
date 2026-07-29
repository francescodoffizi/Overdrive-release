package com.overdrive.app.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TunnelDisplayPolicyTest {

    @Test
    public void noConfiguredTunnelIsHiddenRatherThanConnecting() {
        TunnelDisplayPolicy.Result result = resolve(
                null, null, null,
                DaemonStatus.STOPPED, DaemonStatus.STOPPED, DaemonStatus.STOPPED);

        assertEquals(TunnelDisplayPolicy.Kind.HIDDEN, result.getKind());
        assertFalse(result.isVisible());
        assertNull(result.getUrl());
    }

    @Test
    public void persistedUrlDoesNotMasqueradeAsOnline() {
        TunnelDisplayPolicy.Result result = resolve(
                null, "https://stale.example", null,
                DaemonStatus.STOPPED, DaemonStatus.STOPPED, DaemonStatus.STOPPED);

        assertEquals(TunnelDisplayPolicy.Kind.HIDDEN, result.getKind());
        assertFalse(result.isOnline());
    }

    @Test
    public void startingAndWaitingAreExplicitStates() {
        assertEquals(TunnelDisplayPolicy.Kind.STARTING_ZROK,
                resolve(null, null, null,
                        DaemonStatus.STARTING, DaemonStatus.STOPPED,
                        DaemonStatus.STOPPED).getKind());
        assertEquals(TunnelDisplayPolicy.Kind.WAITING_FOR_URL,
                resolve(null, null, null,
                        DaemonStatus.STOPPED, DaemonStatus.RUNNING,
                        DaemonStatus.STOPPED).getKind());
    }

    @Test
    public void onlineRequiresRunningProcessAndUsesExistingPriority() {
        TunnelDisplayPolicy.Result result = resolve(
                "https://zrok.example",
                "https://cloudflare.example",
                "https://tailscale.example",
                DaemonStatus.RUNNING, DaemonStatus.RUNNING, DaemonStatus.RUNNING);

        assertEquals(TunnelDisplayPolicy.Kind.ONLINE, result.getKind());
        assertEquals("https://zrok.example", result.getUrl());
        assertTrue(result.isOnline());
    }

    @Test
    public void staleHigherPriorityUrlDoesNotHideActiveTunnel() {
        TunnelDisplayPolicy.Result result = resolve(
                "https://stale-zrok.example",
                "https://cloudflare.example",
                null,
                DaemonStatus.STOPPED, DaemonStatus.RUNNING, DaemonStatus.STOPPED);

        assertEquals("https://cloudflare.example", result.getUrl());
    }

    private static TunnelDisplayPolicy.Result resolve(
            String zrokUrl,
            String cloudflaredUrl,
            String tailscaleUrl,
            DaemonStatus zrokStatus,
            DaemonStatus cloudflaredStatus,
            DaemonStatus tailscaleStatus) {
        return TunnelDisplayPolicy.resolve(
                zrokUrl, cloudflaredUrl, tailscaleUrl,
                zrokStatus, cloudflaredStatus, tailscaleStatus);
    }
}
