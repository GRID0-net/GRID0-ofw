package com.grid0.ofw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class V3UtilTest {

    @Test
    public void newerPatchIsNewer() {
        assertTrue(V3Util.isNewerVersion("3.0.1", "3.0.0"));
        assertTrue(V3Util.isNewerVersion("3.0.13", "3.0.9"));
    }

    @Test
    public void equalAndOlderAreNotNewer() {
        assertFalse(V3Util.isNewerVersion("3.0.0", "3.0.0"));
        assertFalse(V3Util.isNewerVersion("2.9.9", "3.0.0"));
        assertFalse(V3Util.isNewerVersion("3.0.0", "3.0.1"));
    }

    @Test
    public void majorAndMinorCompare() {
        assertTrue(V3Util.isNewerVersion("4.0.0", "3.99.99"));
        assertTrue(V3Util.isNewerVersion("3.1", "3.0.9"));
        assertFalse(V3Util.isNewerVersion("3.0.9", "3.1"));
    }

    @Test
    public void missingSegmentsCountAsZero() {
        assertFalse(V3Util.isNewerVersion("3.0", "3.0.0"));
        assertFalse(V3Util.isNewerVersion("3.0.0", "3.0"));
        assertTrue(V3Util.isNewerVersion("3.0.1", "3.0"));
    }

    @Test
    public void nonDigitNoiseIsStripped() {
        assertTrue(V3Util.isNewerVersion("v3.0.1", "3.0.0"));
        assertTrue(V3Util.isNewerVersion("3.0.1", "v3.0.0"));
    }

    @Test
    public void garbageNeverCountsAsNewer() {
        assertFalse(V3Util.isNewerVersion("", "3.0.0"));
        assertFalse(V3Util.isNewerVersion("abc", "3.0.0"));
        assertFalse(V3Util.isNewerVersion(null, "3.0.0"));
        assertFalse(V3Util.isNewerVersion("3.0.1", null));
    }

    private static String event(String dir, String src) {
        return "{\"ev\": \"rx\", \"dir\": \"" + dir + "\", \"kind\": \"browse_req\", \"src\": \""
                + src + "\", \"len\": 325, \"t\": 1.5}";
    }

    @Test
    public void lastS2hSourceWins() {
        String text = event("h2s", "10.147.17.99") + "\n"
                + event("s2h", "192.168.1.150") + "\n"
                + event("s2h", "192.168.1.151") + "\n"
                + event("h2s", "10.147.17.98") + "\n";
        assertEquals("192.168.1.151", V3Util.lastSwitchIpFromEvents(text));
    }

    @Test
    public void noS2hEventsGivesNull() {
        assertNull(V3Util.lastSwitchIpFromEvents(event("h2s", "10.147.17.99") + "\n"));
        assertNull(V3Util.lastSwitchIpFromEvents(""));
        assertNull(V3Util.lastSwitchIpFromEvents("random log noise\n"));
    }

    @Test
    public void s2hLineWithoutSourceIsSkipped() {
        String text = "{\"ev\": \"note\", \"dir\": \"s2h\", \"kind\": \"browse_req\"}\n"
                + event("s2h", "192.168.1.150");
        assertEquals("192.168.1.150", V3Util.lastSwitchIpFromEvents(text));
    }

    @Test
    public void lastLineNeedsNoTrailingNewline() {
        String text = event("h2s", "10.147.17.99") + "\n" + event("s2h", "192.168.5.150");
        assertEquals("192.168.5.150", V3Util.lastSwitchIpFromEvents(text));
    }

    @Test
    public void versionLeadingZerosCompareEqual() {
        assertFalse(V3Util.isNewerVersion("3.00.1", "3.0.1"));
        assertFalse(V3Util.isNewerVersion("3.0.01", "3.0.1"));
        assertTrue(V3Util.isNewerVersion("3.00.2", "3.0.1"));
    }

    @Test
    public void versionBigSegments() {
        assertTrue(V3Util.isNewerVersion("3.0.1000000", "3.0.999999"));
        assertFalse(V3Util.isNewerVersion("3.0.999999", "3.0.1000000"));
    }

    @Test
    public void eventSourceBeforeDirStillParses() {
        String text = "{\"src\": \"192.168.1.150\", \"dir\": \"s2h\", \"ev\": \"rx\"}\n";
        assertEquals("192.168.1.150", V3Util.lastSwitchIpFromEvents(text));
    }

    @Test
    public void twoEventsOnOneLineReturnsFirst() {
        String text = event("s2h", "192.168.1.150") + event("s2h", "192.168.1.151");
        assertEquals("192.168.1.150", V3Util.lastSwitchIpFromEvents(text));
    }

    @Test
    public void dhcpNetmaskNonContiguousIsCounted() {
        assertEquals(16, V3Util.prefixFromDhcpNetmask(16711935));
    }

    @Test
    public void dhcpNetmaskPrefixes() {
        assertEquals(24, V3Util.prefixFromDhcpNetmask(16777215));
        assertEquals(22, V3Util.prefixFromDhcpNetmask(16580607));
        assertEquals(16, V3Util.prefixFromDhcpNetmask(65535));
        assertEquals(25, V3Util.prefixFromDhcpNetmask(-2130706433));
    }

    @Test
    public void dhcpNetmaskUnusableValues() {
        assertEquals(-1, V3Util.prefixFromDhcpNetmask(0));
        assertEquals(-1, V3Util.prefixFromDhcpNetmask(-1));
    }

    @Test
    public void sameSubnetPrefixBasics() {
        assertTrue(V3Util.sameSubnetPrefix("192.168.1.150", "192.168.1.50"));
        assertTrue(V3Util.sameSubnetPrefix("10.147.17.99", "10.147.17.50"));
        assertFalse(V3Util.sameSubnetPrefix("192.168.2.150", "192.168.1.50"));
    }

    @Test
    public void sameSubnetPrefixWideNetworkBehaviour() {
        assertFalse(V3Util.sameSubnetPrefix("192.168.4.30", "192.168.6.207"));
        assertTrue(V3Util.sameSubnetPrefix("192.168.4.30", "192.168.4.207"));
    }

    @Test
    public void sameSubnetPrefixBadInput() {
        assertFalse(V3Util.sameSubnetPrefix(null, "192.168.1.50"));
        assertFalse(V3Util.sameSubnetPrefix("192.168.1.150", null));
        assertFalse(V3Util.sameSubnetPrefix("no-dots", "192.168.1.50"));
    }
}
