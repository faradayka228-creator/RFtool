package com.rftool.app.lan;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/lan/LanTopologyMapper.java
 *
 * Асинхронный сканер локальной подсети. Определяет собственный IP/маску через
 * WifiManager, вычисляет диапазон хостов подсети и параллельно проверяет
 * каждый адрес через InetAddress.isReachable() (ICMP, либо TCP echo fallback).
 * Для найденных хостов пытается получить MAC через /proc/net/arp (доступно без root
 * на многих прошивках для адресов в той же L2-сети) и резолвит vendor-префикс.
 */

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LanTopologyMapper {

    public static class HostResult {
        public final String ip;
        public final String mac;       // может быть "unknown", если ARP-запись недоступна
        public final String vendor;    // может быть "unknown"
        public final long rttMs;

        public HostResult(String ip, String mac, String vendor, long rttMs) {
            this.ip = ip;
            this.mac = mac;
            this.vendor = vendor;
            this.rttMs = rttMs;
        }
    }

    public interface ScanListener {
        void onHostFound(HostResult result);
        void onProgress(int scanned, int total);
        void onComplete(List<HostResult> allHosts);
        void onLog(String message);
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private volatile boolean cancelled = false;

    /** Небольшая встроенная база OUI-префиксов для демонстрации; расширяется при необходимости. */
    private static final Map<String, String> OUI_VENDORS = new HashMap<>();
    static {
        OUI_VENDORS.put("B8:27:EB", "Raspberry Pi Foundation");
        OUI_VENDORS.put("DC:A6:32", "Raspberry Pi Trading");
        OUI_VENDORS.put("3C:5A:B4", "Google Inc.");
        OUI_VENDORS.put("F4:F5:D8", "Google Inc.");
        OUI_VENDORS.put("A4:77:33", "Apple Inc.");
        OUI_VENDORS.put("AC:DE:48", "Apple Inc.");
        OUI_VENDORS.put("00:1A:11", "Google Inc.");
        OUI_VENDORS.put("18:B4:30", "Nest Labs");
        OUI_VENDORS.put("B0:BE:76", "Espressif (IoT)");
        OUI_VENDORS.put("CC:50:E3", "Espressif (IoT)");
        OUI_VENDORS.put("00:17:88", "Philips Hue");
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Запускает ping sweep по подсети текущего Wi-Fi соединения.
     * @param context       контекст приложения
     * @param timeoutMs     таймаут isReachable() на один хост
     * @param listener      коллбэки прогресса
     */
    public void startSweep(Context context, int timeoutMs, ScanListener listener) {
        cancelled = false;
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            listener.onLog("WifiManager недоступен");
            listener.onComplete(new ArrayList<>());
            return;
        }

        @SuppressWarnings("deprecation")
        int ipInt = wm.getConnectionInfo().getIpAddress();
        if (ipInt == 0) {
            listener.onLog("Нет активного Wi-Fi соединения, подсеть не определена");
            listener.onComplete(new ArrayList<>());
            return;
        }

        String baseIp = intToIp(ipInt);
        String subnetPrefix = baseIp.substring(0, baseIp.lastIndexOf('.') + 1);
        listener.onLog("Подсеть определена: " + subnetPrefix + "0/24");

        List<HostResult> results = new ArrayList<>();
        int total = 254;
        int[] scannedCounter = {0};

        for (int i = 1; i <= 254; i++) {
            if (cancelled) break;
            final String targetIp = subnetPrefix + i;
            executor.submit(() -> {
                if (cancelled) return;
                try {
                    InetAddress addr = InetAddress.getByName(targetIp);
                    long start = System.currentTimeMillis();
                    boolean reachable = addr.isReachable(timeoutMs);
                    long rtt = System.currentTimeMillis() - start;

                    if (reachable) {
                        String mac = lookupMacFromArpCache(targetIp);
                        String vendor = resolveVendor(mac);
                        HostResult result = new HostResult(targetIp, mac, vendor, rtt);
                        synchronized (results) {
                            results.add(result);
                        }
                        listener.onHostFound(result);
                    }
                } catch (IOException ignored) {
                    // хост недостижим или DNS/IO ошибка — пропускаем молча
                } finally {
                    synchronized (scannedCounter) {
                        scannedCounter[0]++;
                        listener.onProgress(scannedCounter[0], total);
                        if (scannedCounter[0] == total) {
                            listener.onComplete(new ArrayList<>(results));
                        }
                    }
                }
            });
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Читает /proc/net/arp — доступно без root, содержит ARP-таблицу ядра для текущего интерфейса. */
    private String lookupMacFromArpCache(String ip) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            reader.readLine(); // пропускаем заголовок таблицы
            while ((line = reader.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length >= 4 && cols[0].equals(ip)) {
                    String mac = cols[3];
                    if (!mac.equals("00:00:00:00:00:00")) {
                        return mac.toUpperCase();
                    }
                }
            }
        } catch (IOException ignored) {
            // /proc/net/arp может быть недоступен на некоторых прошивках — это ожидаемо
        }
        return "unknown";
    }

    private String resolveVendor(String mac) {
        if (mac == null || mac.equals("unknown") || mac.length() < 8) return "unknown";
        String oui = mac.substring(0, 8); // "XX:XX:XX"
        String vendor = OUI_VENDORS.get(oui);
        return vendor != null ? vendor : "unknown";
    }

    private String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
    }
}
