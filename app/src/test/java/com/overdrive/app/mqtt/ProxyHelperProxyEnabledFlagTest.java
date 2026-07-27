package com.overdrive.app.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ProxyHelper#isProxyEnabledValue(String)} — the parse of the persisted
 * {@code .tailscale/proxy_enabled} flag that gates the issue #182 "defer instead of direct dial"
 * behaviour. TailscaleLauncher writes the flag with {@code echo $enabled > file}, so the value
 * carries a trailing newline that the parser must tolerate.
 */
public class ProxyHelperProxyEnabledFlagTest {

    @Test
    public void trueWithTrailingNewlineIsEnabled() {
        assertTrue(ProxyHelper.isProxyEnabledValue("true\n"));
    }

    @Test
    public void trueAnyCaseAndSurroundingWhitespaceIsEnabled() {
        assertTrue(ProxyHelper.isProxyEnabledValue("  TRUE  "));
        assertTrue(ProxyHelper.isProxyEnabledValue("True"));
    }

    @Test
    public void numericOneIsEnabled() {
        assertTrue(ProxyHelper.isProxyEnabledValue("1"));
    }

    @Test
    public void falseIsNotEnabled() {
        assertFalse(ProxyHelper.isProxyEnabledValue("false\n"));
    }

    @Test
    public void nullOrBlankIsNotEnabled() {
        assertFalse(ProxyHelper.isProxyEnabledValue(null));
        assertFalse(ProxyHelper.isProxyEnabledValue(""));
        assertFalse(ProxyHelper.isProxyEnabledValue("   "));
    }

    @Test
    public void unrelatedValuesAreNotEnabled() {
        assertFalse(ProxyHelper.isProxyEnabledValue("enabled"));
        assertFalse(ProxyHelper.isProxyEnabledValue("0"));
        assertFalse(ProxyHelper.isProxyEnabledValue("yes"));
    }
}
