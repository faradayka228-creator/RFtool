package com.rftool.app.ble;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/ble/BleManufacturerDecoder.java
 *
 * Разбирает manufacturer-specific data из BLE advertising-пакета и определяет
 * вендора устройства по Company Identifier (официальный реестр Bluetooth SIG),
 * а также распознаёт известные публичные сервисы (Fast Pair, Eddystone, iBeacon).
 * Это стандартный пассивный разбор ОТКРЫТО транслируемых данных — то же самое
 * видит системный Bluetooth-сканер любого телефона при обычном поиске устройств.
 */

import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class BleManufacturerDecoder {

    public static class DecodedInfo {
        public final String vendor;
        public final String deviceTypeHint;
        public final String rawManufacturerHex;

        public DecodedInfo(String vendor, String deviceTypeHint, String rawManufacturerHex) {
            this.vendor = vendor;
            this.deviceTypeHint = deviceTypeHint;
            this.rawManufacturerHex = rawManufacturerHex;
        }
    }

    // Официальные Bluetooth SIG Company Identifiers (наиболее часто встречающиеся)
    private static final Map<Integer, String> COMPANY_IDS = new HashMap<>();
    static {
        COMPANY_IDS.put(0x004C, "Apple, Inc.");
        COMPANY_IDS.put(0x00E0, "Google Inc.");
        COMPANY_IDS.put(0x0006, "Microsoft");
        COMPANY_IDS.put(0x0075, "Samsung Electronics");
        COMPANY_IDS.put(0x0001, "Nokia Mobile Phones");
        COMPANY_IDS.put(0x000F, "Broadcom Corporation");
        COMPANY_IDS.put(0x0059, "Nordic Semiconductor");
        COMPANY_IDS.put(0x0157, "Anhui Huami (Xiaomi/Amazfit)");
        COMPANY_IDS.put(0x038F, "Xiaomi Inc.");
        COMPANY_IDS.put(0x02E1, "Huawei Technologies");
        COMPANY_IDS.put(0x004F, "Sony Corporation");
        COMPANY_IDS.put(0x00D2, "AirTag/Find My compatible"); // Apple FindMy network accessory
        COMPANY_IDS.put(0x0171, "Amazon.com Services LLC");
    }

    // Известные публичные 16-бит сервисные UUID
    private static final Map<String, String> KNOWN_SERVICES = new HashMap<>();
    static {
        KNOWN_SERVICES.put("0000fe2c-0000-1000-8000-00805f9b34fb", "Google Fast Pair");
        KNOWN_SERVICES.put("0000feaa-0000-1000-8000-00805f9b34fb", "Eddystone Beacon");
        KNOWN_SERVICES.put("0000fd6f-0000-1000-8000-00805f9b34fb", "Exposure Notification (COVID)");
        KNOWN_SERVICES.put("0000180f-0000-1000-8000-00805f9b34fb", "Battery Service");
        KNOWN_SERVICES.put("0000180d-0000-1000-8000-00805f9b34fb", "Heart Rate Service");
        KNOWN_SERVICES.put("0000181c-0000-1000-8000-00805f9b34fb", "User Data Service");
        KNOWN_SERVICES.put("0000fef5-0000-1000-8000-00805f9b34fb", "Dialog Semiconductor OTA");
    }

    public static DecodedInfo decode(ScanRecord record) {
        if (record == null) {
            return new DecodedInfo("unknown", "no_scan_record", "");
        }

        SparseArray<byte[]> manufacturerData = record.getManufacturerSpecificData();
        String vendor = "unknown";
        String rawHex = "";

        if (manufacturerData != null && manufacturerData.size() > 0) {
            int companyId = manufacturerData.keyAt(0);
            byte[] data = manufacturerData.valueAt(0);
            vendor = COMPANY_IDS.getOrDefault(companyId, String.format("Unknown (0x%04X)", companyId));
            rawHex = bytesToHex(data);
        }

        String typeHint = guessDeviceType(record, vendor);

        return new DecodedInfo(vendor, typeHint, rawHex);
    }

    private static String guessDeviceType(ScanRecord record, String vendor) {
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                String known = KNOWN_SERVICES.get(uuid.getUuid().toString());
                if (known != null) return known;
            }
        }

        // iBeacon распознаётся по сигнатуре в manufacturer data Apple (0x004C), подтип 0x02 0x15
        SparseArray<byte[]> md = record.getManufacturerSpecificData();
        if (md != null) {
            byte[] appleData = md.get(0x004C);
            if (appleData != null && appleData.length >= 2
                    && (appleData[0] & 0xFF) == 0x02 && (appleData[1] & 0xFF) == 0x15) {
                return "iBeacon";
            }
        }

        if (vendor.contains("Apple")) return "Apple-экосистема (устройство/аксессуар)";
        if (vendor.contains("Xiaomi") || vendor.contains("Huami")) return "Фитнес-трекер/носимое устройство";

        return "неопределён";
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
