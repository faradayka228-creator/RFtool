package com.rftool.app.nfc;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/nfc/CustomHceService.java
 *
 * Host Card Emulation сервис для ISO 7816-4 совместимой кастомной виртуальной карты.
 * Используется для анализа взаимодействия NFC-терминалов/ридеров с СОБСТВЕННЫМ AID
 * (Application Identifier), который объявлен в res/xml/apduservice.xml.
 *
 * ВАЖНО: это НЕ эмуляция банковской/платёжной карты. AID здесь — кастомный,
 * зарегистрированный самим приложением (см. apduservice.xml), что является
 * штатным, документированным Android API сценарием (пропуска, бейджи,
 * лабораторные RFID-метки и т.п.).
 */

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.util.Locale;

public class CustomHceService extends HostApduService {

    // Статус-коды ISO 7816-4
    private static final byte[] SW_SUCCESS = {(byte) 0x90, 0x00};
    private static final byte[] SW_INSTRUCTION_NOT_SUPPORTED = {0x6D, 0x00};
    private static final byte[] SW_UNKNOWN_AID = {0x6A, (byte) 0x82};

    // Наш кастомный AID (должен совпадать с apduservice.xml), пример: F0010203040506
    private static final String CUSTOM_AID = "F0010203040506";

    public interface ApduLogListener {
        void onApduExchanged(String direction, String hex);
    }

    private static ApduLogListener staticLogListener;

    public static void setLogListener(ApduLogListener listener) {
        staticLogListener = listener;
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        String hexCommand = bytesToHex(commandApdu);
        log("RX", hexCommand);

        if (commandApdu.length < 4) {
            return SW_INSTRUCTION_NOT_SUPPORTED;
        }

        // Разбор CLA/INS/P1/P2 согласно ISO 7816-4
        byte cla = commandApdu[0];
        byte ins = commandApdu[1];

        // SELECT AID: CLA=0x00, INS=0xA4
        if (ins == (byte) 0xA4) {
            byte[] response = handleSelectAid(commandApdu);
            log("TX", bytesToHex(response));
            return response;
        }

        // Пример кастомной инструкции: INS=0xB0 — "чтение данных" нашей виртуальной карты
        if (ins == (byte) 0xB0) {
            byte[] payload = buildCustomDataResponse();
            log("TX", bytesToHex(payload));
            return payload;
        }

        log("TX", bytesToHex(SW_INSTRUCTION_NOT_SUPPORTED));
        return SW_INSTRUCTION_NOT_SUPPORTED;
    }

    private byte[] handleSelectAid(byte[] commandApdu) {
        // Структура SELECT: 00 A4 04 00 [Lc] [AID bytes] 00
        if (commandApdu.length < 5) return SW_UNKNOWN_AID;
        int lc = commandApdu[4] & 0xFF;
        if (commandApdu.length < 5 + lc) return SW_UNKNOWN_AID;

        byte[] aidBytes = new byte[lc];
        System.arraycopy(commandApdu, 5, aidBytes, 0, lc);
        String selectedAid = bytesToHex(aidBytes);

        if (selectedAid.equalsIgnoreCase(CUSTOM_AID)) {
            return SW_SUCCESS;
        }
        return SW_UNKNOWN_AID;
    }

    private byte[] buildCustomDataResponse() {
        // Пример: возвращаем произвольную диагностическую строку + статус успеха
        String payload = "RFTOOL-HCE-DEMO";
        byte[] payloadBytes = payload.getBytes();
        byte[] response = new byte[payloadBytes.length + 2];
        System.arraycopy(payloadBytes, 0, response, 0, payloadBytes.length);
        response[response.length - 2] = SW_SUCCESS[0];
        response[response.length - 1] = SW_SUCCESS[1];
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        log("EVENT", "Деактивация HCE, причина=" + reason + " (0=LINK_LOSS, 1=DESELECTED)");
    }

    private void log(String direction, String hex) {
        if (staticLogListener != null) {
            staticLogListener.onApduExchanged(direction, hex);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }
}
