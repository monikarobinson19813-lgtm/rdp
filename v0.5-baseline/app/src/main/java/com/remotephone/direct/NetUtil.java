package com.remotephone.direct;

import java.net.*;
import java.util.*;

public final class NetUtil {
    private NetUtil() {}
    public static List<String> ipv4Addresses() {
        List<String> tailscale = new ArrayList<>(), privateIps = new ArrayList<>(), others = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("100.")) tailscale.add(ip);
                    else if (ip.startsWith("10.") || ip.startsWith("192.168.") || is172Private(ip)) privateIps.add(ip);
                    else others.add(ip);
                }
            }
        } catch (Exception ignored) {}
        List<String> out = new ArrayList<>(); out.addAll(tailscale); out.addAll(privateIps); out.addAll(others); return out;
    }
    private static boolean is172Private(String ip) {
        try { int n = Integer.parseInt(ip.split("\\.")[1]); return n >= 16 && n <= 31; } catch (Exception e) { return false; }
    }
}
