package com.vokerg.voktrader.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TelemetryData {
    private TelemetryData() {
    }

    public static Map<String, Object> data(Object... pairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            data.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return data;
    }
}
