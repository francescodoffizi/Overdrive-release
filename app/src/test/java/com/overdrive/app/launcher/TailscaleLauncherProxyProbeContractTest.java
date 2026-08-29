package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.mqtt.ProxyHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class TailscaleLauncherProxyProbeContractTest {

    @Test
    public void proxySelectionUsesTheListeningPortInsteadOfProcessMatching() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt");

        assertTrue(source.contains("val useProxy = ProxyHelper.probePort(PROXY_PORT)"));
        assertFalse(source.contains("command = \"pgrep -f sing-box\""));
    }

    @Test
    public void probePortReportsListeningAndClosedPorts() throws Exception {
        int port;
        try (ServerSocket listener = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = listener.getLocalPort();
            assertTrue(ProxyHelper.probePort(port));
        }
        assertFalse(ProxyHelper.probePort(port));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
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
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
