package com.vokerg.voktrader.executor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutorApiContractTest {
    private static final Path CONTRACT = Path.of("contracts/executor-api-v1.properties");

    @Test
    void javaResponseRecordsMatchSharedExecutorContract() throws IOException {
        Properties contract = new Properties();
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            contract.load(input);
        }

        assertEquals("executor-api-v1", contract.getProperty("contractVersion"));

        Map<String, Class<?>> records = new LinkedHashMap<>();
        records.put("OrderResponse", ExecutorOrderResponse.class);
        records.put("OrderStatusResponse", ExecutorOrderStatusResponse.class);
        records.put("CancelOrderResponse", ExecutorCancelOrderResponse.class);
        records.put("OpenOrdersResponse", ExecutorOpenOrdersResponse.class);
        records.put("FillResponse", ExecutorFillResponse.class);
        records.put("FillsResponse", ExecutorFillsResponse.class);
        records.put("ExecutorError", ExecutorErrorResponse.class);

        records.forEach((contractName, recordType) -> assertEquals(
                csvSet(contract.getProperty(contractName + ".fields")),
                Arrays.stream(recordType.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toSet()),
                contractName
        ));
    }

    private Set<String> csvSet(String value) {
        return Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }
}
