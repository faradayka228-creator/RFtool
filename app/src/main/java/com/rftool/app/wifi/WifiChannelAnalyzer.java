package com.rftool.app.wifi;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/wifi/WifiChannelAnalyzer.java
 *
 * Переводит частоту (MHz) из ScanResult в номер Wi-Fi канала (2.4/5 ГГц) и
 * считает загруженность каждого канала по количеству видимых точек доступа.
 * Помогает найти наименее забитый канал для своей точки доступа — классическая
 * пассивная диагностика RF-среды, без какого-либо воздействия на чужие сети.
 */

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class WifiChannelAnalyzer {

    public static int frequencyToChannel(int freqMhz) {
        if (freqMhz >= 2412 && freqMhz <= 2484) {
            if (freqMhz == 2484) return 14;
            return (freqMhz - 2407) / 5;
        }
        if (freqMhz >= 5170 && freqMhz <= 5825) {
            return (freqMhz - 5000) / 5;
        }
        if (freqMhz >= 5955 && freqMhz <= 7115) { // Wi-Fi 6E / 6 ГГц
            return (freqMhz - 5950) / 5;
        }
        return -1;
    }

    public static String band(int freqMhz) {
        if (freqMhz < 2500) return "2.4GHz";
        if (freqMhz < 5955) return "5GHz";
        return "6GHz";
    }

    /** Строит карту "канал -> количество AP" по накопленной телеметрии. */
    public static Map<Integer, Integer> buildCongestionMap(
            Iterable<WifiWardrivingService.WifiTelemetryPoint> points) {
        Map<Integer, Integer> congestion = new TreeMap<>();
        Map<String, Boolean> seenBssid = new HashMap<>(); // не считаем один и тот же AP многократно
        for (WifiWardrivingService.WifiTelemetryPoint p : points) {
            if (seenBssid.containsKey(p.bssid)) continue;
            seenBssid.put(p.bssid, true);
            int channel = frequencyToChannel(p.frequencyMhz);
            if (channel < 0) continue;
            congestion.merge(channel, 1, Integer::sum);
        }
        return congestion;
    }

    public static int findLeastCongestedChannel(Map<Integer, Integer> congestion, int[] candidateChannels) {
        int best = candidateChannels.length > 0 ? candidateChannels[0] : -1;
        int bestScore = Integer.MAX_VALUE;
        for (int ch : candidateChannels) {
            int score = congestion.getOrDefault(ch, 0);
            if (score < bestScore) {
                bestScore = score;
                best = ch;
            }
        }
        return best;
    }

    /** Стандартные непересекающиеся каналы 2.4 ГГц для рекомендации. */
    public static final int[] NON_OVERLAPPING_24GHZ = {1, 6, 11};
}
