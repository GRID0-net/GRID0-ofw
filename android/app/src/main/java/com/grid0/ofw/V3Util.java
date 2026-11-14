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
        String found = null;
        int idx = 0;

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
                    found = line.substring(from, to);
                }
            }

            idx = d + 10;
        }

        return found;
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

        String marker = "\"browser_download_url\": \"";
        int idx = 0;

        while (true) {
            int s = json.indexOf(marker, idx);

            if (s < 0) {
                return null;
            }

            int from = s + marker.length();
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

    private static String stringField(String json, String name) {
        String marker = "\"" + name + "\": \"";
        int s = json.indexOf(marker);

        if (s < 0) {
            return null;
        }

        int from = s + marker.length();
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
