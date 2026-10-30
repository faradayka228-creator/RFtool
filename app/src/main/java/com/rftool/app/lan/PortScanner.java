package com.rftool.app.lan;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/lan/PortScanner.java
 *
 * Многопоточный TCP-сканер портов для аудита правил firewall на хостах
 * СВОЕЙ локальной сети. Использует Socket().connect() с явным таймаутом,
 * без блокировки основного потока — каждая проверка выполняется в пуле потоков.
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PortScanner {

    public static class PortResult {
        public final int port;
        public final boolean open;
        public final String serviceGuess;
        public final long latencyMs;

        public PortResult(int port, boolean open, String serviceGuess, long latencyMs) {
            this.port = port;
            this.open = open;
            this.serviceGuess = serviceGuess;
            this.latencyMs = latencyMs;
        }
    }

    public interface ScanListener {
        void onPortResult(PortResult result);
        void onProgress(int scanned, int total);
        void onComplete(List<PortResult> openPorts);
    }

    private static final java.util.Map<Integer, String> WELL_KNOWN = new java.util.HashMap<>();
    static {
        WELL_KNOWN.put(21, "FTP");
        WELL_KNOWN.put(22, "SSH");
        WELL_KNOWN.put(23, "Telnet");
        WELL_KNOWN.put(25, "SMTP");
        WELL_KNOWN.put(53, "DNS");
        WELL_KNOWN.put(80, "HTTP");
        WELL_KNOWN.put(110, "POP3");
        WELL_KNOWN.put(139, "NetBIOS");
        WELL_KNOWN.put(143, "IMAP");
        WELL_KNOWN.put(443, "HTTPS");
        WELL_KNOWN.put(445, "SMB");
        WELL_KNOWN.put(554, "RTSP");
        WELL_KNOWN.put(631, "IPP/Printer");
        WELL_KNOWN.put(1883, "MQTT");
        WELL_KNOWN.put(3306, "MySQL");
        WELL_KNOWN.put(3389, "RDP");
        WELL_KNOWN.put(5000, "UPnP/Custom");
        WELL_KNOWN.put(5353, "mDNS");
        WELL_KNOWN.put(8008, "HTTP-alt");
        WELL_KNOWN.put(8080, "HTTP-alt");
        WELL_KNOWN.put(8443, "HTTPS-alt");
        WELL_KNOWN.put(9100, "Printer/RAW");
    }

    private ExecutorService executor;
    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
        if (executor != null) executor.shutdownNow();
    }

    /**
     * @param host          целевой IP в своей сети
     * @param startPort     начало диапазона
     * @param endPort       конец диапазона (включительно)
     * @param timeoutMs     таймаут соединения на порт
     * @param threadCount   размер пула потоков
     */
    public void scanRange(String host, int startPort, int endPort, int timeoutMs,
                           int threadCount, ScanListener listener) {
        cancelled = false;
        executor = Executors.newFixedThreadPool(Math.max(1, threadCount));

        int total = endPort - startPort + 1;
        AtomicInteger scanned = new AtomicInteger(0);
        List<PortResult> openResults = new ArrayList<>();

        for (int port = startPort; port <= endPort; port++) {
            final int p = port;
            executor.submit(() -> {
                if (cancelled) return;
                boolean isOpen = false;
                long latency = -1;
                try (Socket socket = new Socket()) {
                    long start = System.currentTimeMillis();
                    socket.connect(new InetSocketAddress(host, p), timeoutMs);
                    latency = System.currentTimeMillis() - start;
                    isOpen = true;
                } catch (IOException ignored) {
                    // ConnectException / SocketTimeoutException = порт закрыт или фильтруется
                }

                if (isOpen) {
                    String guess = WELL_KNOWN.getOrDefault(p, "unknown");
                    PortResult result = new PortResult(p, true, guess, latency);
                    synchronized (openResults) {
                        openResults.add(result);
                    }
                    listener.onPortResult(result);
                }

                int done = scanned.incrementAndGet();
                listener.onProgress(done, total);
                if (done == total) {
                    listener.onComplete(new ArrayList<>(openResults));
                }
            });
        }

        executor.shutdown();
    }

    public void awaitTermination() {
        if (executor == null) return;
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
