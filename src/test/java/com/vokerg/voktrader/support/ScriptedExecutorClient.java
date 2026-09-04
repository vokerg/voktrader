package com.vokerg.voktrader.support;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOpenOrdersResponse;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.TickSizeService;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;

public class ScriptedExecutorClient extends PythonExecutorClient {
    private final Deque<Object> submitOutcomes = new ArrayDeque<>();
    private final Map<String, Deque<ExecutorOrderStatusResponse>> orderStatuses = new ConcurrentHashMap<>();
    private final Map<String, Deque<ExecutorFillsResponse>> fills = new ConcurrentHashMap<>();
    private final Map<String, Deque<ExecutorCancelOrderResponse>> cancels = new ConcurrentHashMap<>();
    private final List<ExecutorOrderCommand> submittedCommands = new ArrayList<>();

    public ScriptedExecutorClient() {
        super(new ExecutorProperties(), WebClient.builder(), new ObjectMapper(), mock(TickSizeService.class));
    }

    public synchronized void reset() {
        submitOutcomes.clear(); orderStatuses.clear(); fills.clear(); cancels.clear(); submittedCommands.clear();
    }

    public synchronized void resetCommands() { submittedCommands.clear(); }
    public synchronized void onSubmit(ExecutorOrderResponse response) { submitOutcomes.addLast(response); }
    public synchronized void onSubmitFailure(RuntimeException failure) { submitOutcomes.addLast(failure); }
    public synchronized void onGetOrderStatus(String id, ExecutorOrderStatusResponse... responses) { queue(orderStatuses, id, responses); }
    public synchronized void onListFills(String id, ExecutorFillsResponse... responses) { queue(fills, id, responses); }
    public synchronized void onCancel(String id, ExecutorCancelOrderResponse... responses) { queue(cancels, id, responses); }
    public synchronized List<ExecutorOrderCommand> submittedCommands() { return List.copyOf(submittedCommands); }
    public synchronized ExecutorOrderCommand lastSubmittedCommand() { return submittedCommands.isEmpty() ? null : submittedCommands.getLast(); }

    @Override
    public synchronized ExecutorOrderResponse submit(ExecutorOrderCommand command) {
        submittedCommands.add(command);
        if (submitOutcomes.isEmpty()) return ExecutorOrderResponse.rejected("No scripted submit response");
        Object outcome = submitOutcomes.removeFirst();
        if (outcome instanceof RuntimeException failure) throw failure;
        return (ExecutorOrderResponse) outcome;
    }

    @Override
    public synchronized ExecutorCancelOrderResponse cancelOrder(String remoteOrderId) {
        return nextOrDefault(cancels, remoteOrderId,
                ExecutorCancelOrderResponse.failure(remoteOrderId, "UNSCRIPTED", "No scripted cancel response"));
    }

    @Override
    public synchronized ExecutorOrderStatusResponse getOrderStatus(String remoteOrderId) {
        return nextOrDefault(orderStatuses, remoteOrderId,
                ExecutorOrderStatusResponse.failure(remoteOrderId, "UNSCRIPTED", "No scripted order status response"));
    }

    @Override
    public synchronized ExecutorOpenOrdersResponse listOpenOrders(String marketId, String tokenId) {
        return new ExecutorOpenOrdersResponse(true, List.of(), "[]", null);
    }

    @Override
    public synchronized ExecutorFillsResponse listFills(
            String remoteOrderId, String marketId, String tokenId,
            com.vokerg.voktrader.trade.model.TradeSide side,
            java.math.BigDecimal price, java.math.BigDecimal shares, Instant since
    ) {
        return nextOrDefault(fills, remoteOrderId, new ExecutorFillsResponse(true, List.of(), "[]", null));
    }

    private <T> void queue(Map<String, Deque<T>> target, String key, T... values) {
        Deque<T> deque = new ArrayDeque<>();
        if (values != null) for (T value : values) deque.addLast(value);
        target.put(key, deque);
    }

    private <T> T nextOrDefault(Map<String, Deque<T>> target, String key, T fallback) {
        Deque<T> deque = target.get(key);
        if (deque == null || deque.isEmpty()) return fallback;
        if (deque.size() == 1) return deque.peekFirst();
        return deque.removeFirst();
    }
}
