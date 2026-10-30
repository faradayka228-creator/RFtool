package com.rftool.app.wifi;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/wifi/WifiSecurityAuditor.java
 *
 * Классифицирует строку capabilities, которую Android возвращает для каждой
 * видимой точки доступа (ScanResult.capabilities), по типу шифрования и
 * присваивает риск-метку. Чисто пассивный разбор публично транслируемых
 * (broadcast) данных — то же самое видит любой телефон при обычном поиске сетей.
 */

public class WifiSecurityAuditor {

    public enum RiskLevel { CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN }

    public static class AuditResult {
        public final String encryptionSummary;
        public final RiskLevel risk;
        public final String note;

        public AuditResult(String encryptionSummary, RiskLevel risk, String note) {
            this.encryptionSummary = encryptionSummary;
            this.risk = risk;
            this.note = note;
        }
    }

    public static AuditResult audit(String capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return new AuditResult("UNKNOWN", RiskLevel.UNKNOWN, "capabilities не получены");
        }

        boolean open = !capabilities.contains("WEP") && !capabilities.contains("PSK")
                && !capabilities.contains("EAP") && !capabilities.contains("SAE")
                && !capabilities.contains("OWE");

        if (open) {
            return new AuditResult("OPEN", RiskLevel.CRITICAL,
                    "Сеть без шифрования — весь трафик виден любому в радиусе действия");
        }
        if (capabilities.contains("WEP")) {
            return new AuditResult("WEP", RiskLevel.CRITICAL,
                    "WEP взламывается за минуты стандартными средствами — эквивалент открытой сети");
        }
        if (capabilities.contains("SAE") || capabilities.contains("WPA3")) {
            return new AuditResult("WPA3", RiskLevel.LOW,
                    "Современный протокол, устойчив к офлайн-перебору пароля");
        }
        if (capabilities.contains("OWE")) {
            return new AuditResult("OWE (Enhanced Open)", RiskLevel.MEDIUM,
                    "Шифрование без аутентификации — защита от пассивного перехвата, но не от MITM");
        }
        if (capabilities.contains("EAP")) {
            return new AuditResult("WPA-Enterprise (802.1X)", RiskLevel.LOW,
                    "Корпоративная аутентификация через RADIUS-сервер");
        }
        if (capabilities.contains("WPA2") || capabilities.contains("RSN")) {
            boolean tkip = capabilities.contains("TKIP");
            if (tkip) {
                return new AuditResult("WPA2-TKIP", RiskLevel.MEDIUM,
                        "TKIP устарел, рекомендуется миграция на CCMP/AES");
            }
            return new AuditResult("WPA2-PSK (AES)", RiskLevel.LOW,
                    "Стандартная надёжная защита для домашних/офисных сетей");
        }
        if (capabilities.contains("WPA")) {
            return new AuditResult("WPA-PSK (legacy)", RiskLevel.HIGH,
                    "Первая версия WPA содержит известные уязвимости, рекомендуется обновление роутера");
        }

        return new AuditResult(capabilities, RiskLevel.UNKNOWN, "Не удалось однозначно классифицировать");
    }

    public static String riskLabel(RiskLevel level) {
        switch (level) {
            case CRITICAL: return "КРИТИЧНО";
            case HIGH: return "ВЫСОКИЙ";
            case MEDIUM: return "СРЕДНИЙ";
            case LOW: return "НИЗКИЙ";
            default: return "Н/Д";
        }
    }
}
