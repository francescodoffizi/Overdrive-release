package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level ownership and lifecycle guards for the Communicate feature. */
public class RemoteCommunicationAssetContractTest {

    @Test
    public void microphoneAndRealtimeResourcesArePressScoped() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/communicate.html");
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");

        assertTrue(page.contains("id=\"pttButton\""));
        assertTrue(page.contains("maxlength=\"200\""));
        assertTrue(page.contains("role=\"tablist\""));
        assertTrue(page.contains("id=\"talkStateGuidance\""));
        assertTrue(page.contains("id=\"messageReadiness\""));

        String initialization = between(script, "init: function () {", "bindTalk:");
        assertFalse(initialization.contains("getUserMedia"));
        assertFalse(initialization.contains("new WebSocket"));
        assertFalse(initialization.contains("new AudioContext"));
        assertFalse(initialization.contains("setInterval"));

        String press = between(script, "onPressStart: function", "onPressEnd:");
        assertTrue(press.contains("media.getUserMedia"));
        assertTrue(script.contains("button.addEventListener('pointerdown'"));
        assertTrue(script.contains("window.addEventListener('pointerup'"));
        assertTrue(script.contains("window.addEventListener('pointercancel'"));
    }

    @Test
    public void everyInactivePathTearsDownCommunicationResources() throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");

        assertTrue(script.contains("document.addEventListener('visibilitychange'"));
        assertTrue(script.contains("window.addEventListener('pagehide'"));
        assertTrue(script.contains("window.addEventListener('beforeunload'"));
        assertTrue(script.contains("self.stopTalk('Navigation', true)"));
        assertTrue(script.contains("self.stopTalk('30 second limit reached')"));
        assertTrue(script.contains(
                "self.stopTalk('Microphone stream became inactive')"));
        assertTrue(script.contains("MAX_SESSION_MS: 30000"));
        assertTrue(script.contains("AUDIO_INACTIVITY_MS: 3000"));
        assertTrue(script.contains("now - self.lastMeterAt >= 100"));

        String stop = between(script, "stopTalk: function", "failTalk:");
        assertTrue(stop.contains("this.stopTalkTicker()"));
        assertTrue(stop.contains("socket.close"));
        assertTrue(stop.contains("this.audioContext.close()"));
        assertTrue(stop.contains("this.stopTracks(this.stream)"));
        assertTrue(stop.contains("this.setMeter(0)"));
        assertFalse(script.contains("requestAnimationFrame"));
    }

    @Test
    public void inactiveStatesAvoidHighFrequencyStatusAndPermissionWork()
            throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");

        assertTrue(script.contains("INACTIVE_STATUS_POLL_MS: 30000"));
        assertTrue(script.contains("statusPollDelay: function"));
        assertTrue(script.contains("status.carState === 'off'"));
        assertTrue(script.contains("audioInactive && messagesInactive"));
        assertTrue(api.contains("shouldCheckAnyOverlay("));
        assertTrue(api.contains("shouldCheckMessageOverlay("));
        assertTrue(websocket.contains("shouldCheckVoiceOverlay("));
    }

    @Test
    public void receiverAndSettingsStayInsideTheirDedicatedSurfaces()
            throws IOException {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/RemoteVoiceService.java");
        String overlaySettings = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_overlay.xml");
        String portraitSettings = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings.xml");

        assertTrue(server.contains("\"/ws/communicate\""));
        assertTrue(server.contains("\"local/communicate.html\""));
        assertTrue(receiver.contains("FLAG_NOT_FOCUSABLE"));
        assertTrue(receiver.contains("attachRoadSenseDuckTarget"));
        assertTrue(receiver.contains("updateOverlayVisibility"));
        assertTrue(overlaySettings.contains(
                "@layout/include_remote_communication_settings"));
        assertTrue(portraitSettings.contains(
                "@layout/include_remote_communication_settings"));
    }

    @Test
    public void pttForwardsPcmFramesDirectlyToTheCarAudioTrack()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "RemoteVoiceBridge.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");

        assertTrue(browser.contains("self.socket.send(pcm.buffer)"));
        assertTrue(websocket.contains("bridge.sendPcm(frame.payload)"));
        assertTrue(bridge.contains("RemoteVoiceProtocol.writePcm(audioOutput, pcm)"));
        assertTrue(receiver.contains("writeRemotePcm(packet.payload)"));
        assertTrue(receiver.contains("track.write("));
        assertTrue(websocket.contains("PTT network stats: frames="));
        assertTrue(websocket.contains("NETWORK_GAP_WARN_MS"));
        assertFalse(browser.contains("MediaRecorder"));
        assertFalse(browser.contains("Blob("));
    }

    @Test
    public void liveVoiceRequiresUnlockedCaptureAndStartedCarPlayback()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");

        String press = between(browser, "onPressStart: function", "onPressEnd:");
        assertTrue(press.contains("this.prepareAudioContext()"));
        assertTrue(browser.contains("self.audioContext.state !== 'running'"));
        assertTrue(browser.contains("self.markTalkLive()"));

        int playbackStart = receiver.indexOf("startAudioPlayback();");
        int readyHandshake = receiver.indexOf(
                "sendHandshake(token, failure == null ? \"READY\"");
        assertTrue(playbackStart >= 0);
        assertTrue(readyHandshake > playbackStart);
        assertTrue(receiver.contains(
                "track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING"));
        assertTrue(receiver.contains("primeAudioTrack(track)"));
        assertTrue(receiver.indexOf("primeAudioTrack(track)")
                < receiver.indexOf("track.play()"));
        assertTrue(receiver.contains("AudioTrack.WRITE_NON_BLOCKING"));
        assertTrue(receiver.contains("track.getUnderrunCount()"));
        assertTrue(receiver.contains("writeRemotePcm(packet.payload)"));
        assertTrue(receiver.contains("while (offset < byteCount)"));
        assertTrue(receiver.contains("AudioFormat.CHANNEL_OUT_STEREO"));
        assertTrue(receiver.contains("getNativeOutputSampleRate"));
    }

    @Test
    public void statusDistinguishesCarOffFromUnreachableAndSetupRequired()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String safety = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "VehicleCommunicationSafety.java");

        assertTrue(browser.contains(
                "'Car off \\u2022 Communication unavailable'"));
        assertTrue(browser.contains("'Car unreachable'"));
        assertTrue(browser.contains("' \\u2022 Setup required'"));
        assertTrue(api.contains("response.put(\"carState\""));
        assertTrue(safety.contains("AccMonitor.isAccStateAuthoritative()"));
        assertTrue(safety.contains("!AccMonitor.isAccOn()"));
    }

    @Test
    public void webOutputLevelUsesTheProvenPerSessionVolumePath()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/communicate.html");
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");
        String config = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/"
                        + "UnifiedConfigManager.kt");

        assertTrue(page.contains("id=\"remoteOutputLevel\""));
        assertTrue(page.contains("id=\"remoteOutputOverride\""));
        assertTrue(page.contains("step=\"5\""));
        assertTrue(browser.contains("'/api/communicate/settings'"));
        assertTrue(browser.contains("outputLevelOverrideEnabled"));
        assertTrue(browser.contains("this.outputSaving"));
        assertTrue(api.contains(".put(\"outputLevel\", settings.outputLevel)"));
        assertTrue(api.contains(".put(\"outputLevelOverrideEnabled\""));
        assertTrue(config.contains(
                "remoteCommunication.put(\"outputLevelOverrideEnabled\", false)"));
        assertTrue(receiver.contains("track.setVolume(volume)"));
        assertTrue(receiver.contains("AudioManager.STREAM_MUSIC"));
        assertFalse(page.contains("remoteAudioChannel"));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
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
