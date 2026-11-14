package com.grid0.ofw;

// Pure helpers split out of MainActivity so they can be unit tested.
public final class V3Util {

    private V3Util() {
    }

    public static boolean isNewerVersion(String remote, String local) {
        try {
            String[] r = remote.split("\\.");
            String[] l = local.split("\\.");

            for (int i = 0; i < Math.max(r.length, l.length); i++) {
                int rv = i < r.length ? Integer.parseInt(r[i].replaceAll("[^0-9]", "")) : 0;
                int lv = i < l.length ? Integer.parseInt(l[i].replaceAll("[^0-9]", "")) : 0;

                if (rv != lv) {
                    return rv > lv;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    public static String lastSwitchIpFromEvents(String text) {
        return lastSwitchIpFromEvents(text, null, -1);
    }

    // Like lastSwitchIpFromEvents, but only sources inside the phone's own
    // subnet count, so a peer address seen on the switch side of the relay
    // can never be learned as the Switch.
    public static String lastSwitchIpFromEvents(String text, String phoneIp, int prefix) {
        String found = null;
        int idx = 0;
        boolean filter = phoneIp != null && prefix >= 0;

        while (true) {
            int d = text.indexOf("\"dir\": \"s2h\"", idx);

            if (d < 0) {
                break;
            }

            int lineStart = text.lastIndexOf("\n", d) + 1;
            int lineEnd = text.indexOf("\n", d);
            String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            int s = line.indexOf("\"src\": \"");

            if (s >= 0) {
                int from = s + 8;
                int to = line.indexOf("\"", from);

                if (to > from) {
                    String candidate = line.substring(from, to);

                    if (!filter || ipInSubnet(candidate, phoneIp, prefix)) {
                        found = candidate;
                    }
                }
            }

            idx = d + 10;
        }

        return found;
    }

    public static boolean ipInSubnet(String ip, String base, int prefix) {
        long a = ipToLong(ip);
        long b = ipToLong(base);

        if (a < 0 || b < 0 || prefix < 0 || prefix > 32) {
            return false;
        }

        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;

        return (a & mask) == (b & mask);
    }

    private static long ipToLong(String ip) {
        if (ip == null) {
            return -1;
        }

        String[] parts = ip.split("\\.");

        if (parts.length != 4) {
            return -1;
        }

        long v = 0;

        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);

                if (n < 0 || n > 255) {
                    return -1;
                }

                v = (v << 8) | n;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        return v;
    }

    public static String versionFromReleaseJson(String json) {
        if (json == null) {
            return null;
        }

        String tag = stringField(json, "tag_name");

        if (tag == null) {
            return null;
        }

        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    public static String apkUrlFromReleaseJson(String json) {
        if (json == null) {
            return null;
        }

        String marker = "\"browser_download_url\":";
        int idx = 0;

        while (true) {
            int s = json.indexOf(marker, idx);

            if (s < 0) {
                return null;
            }

            int from = valueStart(json, s + marker.length());

            if (from < 0) {
                idx = s + marker.length();
                continue;
            }

            int to = json.indexOf("\"", from);

            if (to < 0) {
                return null;
            }

            String url = json.substring(from, to);

            if (url.endsWith(".apk")) {
                return url;
            }

            idx = to;
        }
    }

    // Index of the first character inside the quotes of the value at or after
    // from, tolerating whitespace after the colon, or -1 when there is no
    // quoted value there. GitHub serves compact JSON with no space after the
    // colon, so the value cannot be matched with a fixed ": " marker.
    private static int valueStart(String json, int from) {
        int i = from;

        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }

        if (i >= json.length() || json.charAt(i) != '"') {
            return -1;
        }

        return i + 1;
    }

    private static String stringField(String json, String name) {
        String marker = "\"" + name + "\":";
        int s = json.indexOf(marker);

        if (s < 0) {
            return null;
        }

        int from = valueStart(json, s + marker.length());

        if (from < 0) {
            return null;
        }

        int to = json.indexOf("\"", from);

        return to > from ? json.substring(from, to) : null;
    }

    // DhcpInfo.netmask is little-endian. Returns the prefix length, or -1 when unusable.
    public static int prefixFromDhcpNetmask(int dhcpNetmask) {
        int bits = Integer.bitCount(Integer.reverseBytes(dhcpNetmask));

        return bits > 0 && bits < 32 ? bits : -1;
    }

    // The learned Switch IP check compares the first three octets, matching /24 networks.
    public static boolean sameSubnetPrefix(String ip, String other) {
        if (ip == null || other == null) {
            return false;
        }

        int a = ip.lastIndexOf('.');
        int b = other.lastIndexOf('.');

        if (a < 0 || b < 0) {
            return false;
        }

        return ip.substring(0, a).equals(other.substring(0, b));
    }
}
