package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.VoktraderApplication;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.support.ExecutorTestConfig;
import com.vokerg.voktrader.support.ScriptedExecutorClient;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = VoktraderApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:order-lifecycle-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.task.scheduling.enabled=false",
                "voktrader.bots.enabled=false",
                "voktrader.order-layer.reconciliation.enabled=false",
                "voktrader.trading.mode=LIVE",
                "voktrader.trading.live-enabled=true",
                "voktrader.trading.kill-switch-enabled=false",
                "voktrader.trading.allowed-strategy-ids=it-strategy"
        }
)
@Import(ExecutorTestConfig.class)
class OrderLifecycleIntegrationTest {
    private static final String STRATEGY_ID = "it-strategy";
    private static final String RULE_ID = "it-rule";
    private static final String MARKET_ID = "market-it";
    private static final String TOKEN_ID = "token-down";
    private static final String OUTCOME = "Down";

    @Autowired
    private OrderManager orderManager;

    @Autowired
    private DbTradeStateProvider dbTradeStateProvider;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private TradeOrderRepository tradeOrderRepository;

    @Autowired
    private TradeFillRepository tradeFillRepository;

    @Autowired
    private TradeEventRepository tradeEventRepository;

    @Autowired
    private TradeRiskCheckRepository tradeRiskCheckRepository;

    @Autowired
    private ScriptedExecutorClient executor;

    @BeforeEach
    void setUp() {
        tradeEventRepository.deleteAll();
        tradeFillRepository.deleteAll();
        tradeOrderRepository.deleteAll();
        tradeRiskCheckRepository.deleteAll();
        tradeRepository.deleteAll();
        executor.reset();
    }

    @Test
    void fokEntryFullFillThenFakExitFullFill() {
        executor.onSubmit(filledSubmit("remote-entry-1", "0.50", "5", "2.50"));

        OrderLifecycleResult entry = orderManager.submitOrder(buyIntent(TradeOrderType.FOK, "5", "0.50"), ExecutionMode.LIVE);

        assertThat(entry.success()).isTrue();
        TradeEntity trade = trade(entry.tradeId());
        TradeOrderEntity entryOrder = latestOrder(trade.getId(), TradeOrderPhase.ENTRY);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(entryOrder.getStatus()).isEqualTo(TradeOrderStatus.FILLED);

        executor.onSubmit(filledSubmit("remote-exit-1", "0.55", "5", "2.75"));

        OrderLifecycleResult exit = orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "5", "0.55"), ExecutionMode.LIVE);

        assertThat(exit.success()).isTrue();
        assertThat(exit.tradeId()).isEqualTo(entry.tradeId());
        assertThat(tradeRepository.count()).isEqualTo(1);

        TradeEntity closed = trade(entry.tradeId());
        TradeOrderEntity exitOrder = latestOrder(closed.getId(), TradeOrderPhase.EXIT);
        assertThat(closed.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(exitOrder.getTradeId()).isEqualTo(closed.getId());
        assertThat(exitOrder.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
    }

    @Test
    void fokEntryRejectedNoFill() {
        executor.onSubmit(ExecutorOrderResponse.rejected("exchange rejected"));

        OrderLifecycleResult result = orderManager.submitOrder(buyIntent(TradeOrderType.FOK, "5", "0.50"), ExecutionMode.LIVE);

        assertThat(result.success()).isFalse();
        TradeEntity trade = trade(result.tradeId());
        TradeOrderEntity entryOrder = latestOrder(trade.getId(), TradeOrderPhase.ENTRY);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.FAILED);
        assertThat(entryOrder.getStatus()).isEqualTo(TradeOrderStatus.REJECTED);
        assertThat(runtimeState().hasPosition()).isFalse();
    }

    @Test
    void gtcEntryRestsThenFullFillViaReconciliation() {
        executor.onSubmit(restingSubmit("remote-entry-gtc-1"));
        executor.onGetOrderStatus(
                "remote-entry-gtc-1",
                orderStatus("remote-entry-gtc-1", "OPEN", "0.50", "5", null, null, null),
                orderStatus("remote-entry-gtc-1", "FILLED", "0.50", "5", "5", "0", "0.50")
        );
        executor.onListFills(
                "remote-entry-gtc-1",
                noFills(),
                fills(fill("remote-entry-gtc-1", "fill-entry-gtc-1", "5", "0.50", "0.01"))
        );

        OrderLifecycleResult submitted = orderManager.submitOrder(buyIntent(TradeOrderType.GTC, "5", "0.50"), ExecutionMode.LIVE);

        TradeEntity pending = trade(submitted.tradeId());
        TradeOrderEntity entryOrder = latestOrder(pending.getId(), TradeOrderPhase.ENTRY);
        assertThat(pending.getStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(entryOrder.getStatus()).isIn(TradeOrderStatus.SUBMITTED, TradeOrderStatus.RESTING);

        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);

        TradeEntity open = trade(submitted.tradeId());
        TradeOrderEntity filledOrder = latestOrder(open.getId(), TradeOrderPhase.ENTRY);
        StrategyRuntimeState state = runtimeState();
        assertThat(open.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(filledOrder.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(state.hasPosition()).isTrue();
        assertThat(state.filledShares()).isEqualByComparingTo("5");
        assertThat(state.activeEntryOrder()).isNull();
    }

    @Test
    void gtcEntryPartialFillStillLive() {
        executor.onSubmit(restingSubmit("remote-entry-gtc-2"));
        executor.onGetOrderStatus(
                "remote-entry-gtc-2",
                orderStatus("remote-entry-gtc-2", "OPEN", "0.50", "5", null, null, null),
                orderStatus("remote-entry-gtc-2", "OPEN", "0.50", "5", "1", "4", "0.50")
        );
        executor.onListFills(
                "remote-entry-gtc-2",
                noFills(),
                fills(fill("remote-entry-gtc-2", "fill-entry-gtc-2", "1", "0.50", "0.01"))
        );

        OrderLifecycleResult submitted = orderManager.submitOrder(buyIntent(TradeOrderType.GTC, "5", "0.50"), ExecutionMode.LIVE);

        TradeOrderEntity entryOrder = latestOrder(submitted.tradeId(), TradeOrderPhase.ENTRY);
        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);

        TradeEntity trade = trade(submitted.tradeId());
        StrategyRuntimeState state = runtimeState();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(latestOrder(trade.getId(), TradeOrderPhase.ENTRY).getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(state.activeEntryOrder()).isNotNull();
        assertThat(state.filledShares()).isEqualByComparingTo("1");
    }

    @Test
    void gtdPartialFillThenCancelledRemainder() {
        OrderLifecycleResult submitted = createPartialDonePosition("remote-entry-gtd-1", "4.5", TradeOrderType.GTD, "CANCELED");

        TradeEntity trade = trade(submitted.tradeId());
        TradeOrderEntity entryOrder = latestOrder(trade.getId(), TradeOrderPhase.ENTRY);
        StrategyRuntimeState state = runtimeState();

        assertThat(entryOrder.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED_DONE);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(state.hasPosition()).isTrue();
        assertThat(state.activeEntryOrder()).isNull();
        assertThat(state.filledShares()).isEqualByComparingTo("4.5");
        assertThat(state.avgEntryPrice()).isEqualByComparingTo("0.50");
        assertThat(orderManager.reconcileOpenOrders()).isZero();
    }

    @Test
    void gtdZeroFillExpiry() {
        executor.onSubmit(restingSubmit("remote-entry-gtd-expired"));
        executor.onGetOrderStatus(
                "remote-entry-gtd-expired",
                orderStatus("remote-entry-gtd-expired", "OPEN", "0.50", "5", null, null, null),
                orderStatus("remote-entry-gtd-expired", "EXPIRED", "0.50", "5", null, null, null)
        );
        executor.onListFills("remote-entry-gtd-expired", noFills(), noFills());

        OrderLifecycleResult submitted = orderManager.submitOrder(buyIntent(TradeOrderType.GTD, "5", "0.50"), ExecutionMode.LIVE);

        TradeOrderEntity entryOrder = latestOrder(submitted.tradeId(), TradeOrderPhase.ENTRY);
        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);

        TradeEntity trade = trade(submitted.tradeId());
        StrategyRuntimeState state = runtimeState();
        assertThat(latestOrder(trade.getId(), TradeOrderPhase.ENTRY).getStatus()).isEqualTo(TradeOrderStatus.EXPIRED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
        assertThat(state.hasPosition()).isFalse();
    }

    @Test
    void exitFromPartiallyOpenFullClose() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-entry-exit-full", "4.545453", TradeOrderType.GTD, "CANCELED");
        executor.onSubmit(filledSubmit("remote-exit-full", "0.54", "4.545453", "2.45454462"));

        OrderLifecycleResult exit = orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "4.545453", "0.54"), ExecutionMode.LIVE);

        TradeEntity trade = trade(partial.tradeId());
        TradeOrderEntity exitOrder = latestOrder(trade.getId(), TradeOrderPhase.EXIT);
        assertThat(exit.tradeId()).isEqualTo(partial.tradeId());
        assertThat(tradeRepository.count()).isEqualTo(1);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(exitOrder.getTradeId()).isEqualTo(trade.getId());
    }

    @Test
    void exitFromPartiallyOpenPartialClose() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-entry-exit-partial", "4.5", TradeOrderType.GTD, "CANCELED");
        executor.onSubmit(filledSubmit("remote-exit-partial", "0.54", "2.0", "1.08"));

        orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "2.0", "0.54"), ExecutionMode.LIVE);

        TradeEntity trade = trade(partial.tradeId());
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_CLOSED);
        assertThat(trade.getExitFilledShares()).isEqualByComparingTo("2.0");
        assertThat(TradePositionSupport.heldShares(trade)).isEqualByComparingTo("2.5");
    }

    @Test
    void exitRejectionPreservesPosition() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-entry-exit-reject", "4.5", TradeOrderType.GTD, "CANCELED");
        executor.onSubmit(ExecutorOrderResponse.rejected("exchange rejected"));

        OrderLifecycleResult exit = orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "4.5", "0.54"), ExecutionMode.LIVE);

        TradeEntity trade = trade(partial.tradeId());
        TradeOrderEntity exitOrder = latestOrder(trade.getId(), TradeOrderPhase.EXIT);
        assertThat(exit.success()).isFalse();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(exitOrder.getStatus()).isEqualTo(TradeOrderStatus.REJECTED);
    }

    @Test
    void overSellClampsToHeldShares() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-entry-oversell", "4.5", TradeOrderType.GTD, "CANCELED");
        executor.onSubmit(filledSubmit("remote-exit-oversell", "0.54", "4.5", "2.43"));

        orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "10", "0.54"), ExecutionMode.LIVE);

        TradeOrderEntity exitOrder = latestOrder(partial.tradeId(), TradeOrderPhase.EXIT);
        assertThat(executor.lastSubmittedCommand()).isNotNull();
        assertThat(executor.lastSubmittedCommand().shares()).isEqualByComparingTo("4.5");
        assertThat(exitOrder.getRequestedShares()).isEqualByComparingTo("4.5");
    }

    private OrderLifecycleResult createPartialDonePosition(String remoteOrderId, String filledShares, TradeOrderType orderType, String terminalStatus) {
        executor.onSubmit(restingSubmit(remoteOrderId));
        executor.onGetOrderStatus(
                remoteOrderId,
                orderStatus(remoteOrderId, "OPEN", "0.50", "5", null, null, null),
                orderStatus(remoteOrderId, terminalStatus, "0.50", "5", filledShares, null, "0.50")
        );
        executor.onListFills(
                remoteOrderId,
                noFills(),
                fills(fill(remoteOrderId, "fill-" + remoteOrderId, filledShares, "0.50", "0.01"))
        );

        OrderLifecycleResult submitted = orderManager.submitOrder(buyIntent(orderType, "5", "0.50"), ExecutionMode.LIVE);
        TradeOrderEntity entryOrder = latestOrder(submitted.tradeId(), TradeOrderPhase.ENTRY);
        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);
        return submitted;
    }

    private StrategyRuntimeState runtimeState() {
        return dbTradeStateProvider.getState(StrategyInstanceKey.of(null, STRATEGY_ID), MARKET_ID);
    }

    private TradeEntity trade(Long tradeId) {
        return tradeRepository.findById(tradeId).orElseThrow();
    }

    private TradeOrderEntity latestOrder(Long tradeId, TradeOrderPhase phase) {
        return tradeOrderRepository.findByTradeId(tradeId).stream()
                .filter(order -> order.getPhase() == phase)
                .max(Comparator.comparing(TradeOrderEntity::getId))
                .orElseThrow();
    }

    private TradeIntent buyIntent(TradeOrderType orderType, String shares, String price) {
        BigDecimal shareValue = new BigDecimal(shares);
        BigDecimal priceValue = new BigDecimal(price);
        return TradeIntent.buy(
                null,
                market(),
                outcomePrice(price, addCents(price, "0.01")),
                shareValue.multiply(priceValue),
                shareValue,
                orderType,
                orderType.prefersMaker(),
                priceValue,
                STRATEGY_ID,
                RULE_ID,
                "integration entry"
        );
    }

    private TradeIntent sellIntent(TradeOrderType orderType, String shares, String price) {
        return TradeIntent.sell(
                null,
                market(),
                outcomePrice(price, addCents(price, "0.01")),
                new BigDecimal(shares),
                orderType,
                orderType.prefersMaker(),
                new BigDecimal(price),
                STRATEGY_ID,
                RULE_ID,
                "integration exit"
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                MARKET_ID,
                "Integration test market",
                "condition-it",
                "integration-market",
                Instant.parse("2026-05-23T12:10:00Z"),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
    }

    private OutcomePrice outcomePrice(String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                TOKEN_ID,
                OUTCOME,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                Instant.parse("2026-05-23T12:00:00Z")
        );
    }

    private String addCents(String base, String delta) {
        return new BigDecimal(base).add(new BigDecimal(delta)).toPlainString();
    }

    private ExecutorOrderResponse filledSubmit(String remoteOrderId, String avgPrice, String shares, String amountUsd) {
        return new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                remoteOrderId,
                new BigDecimal(avgPrice),
                new BigDecimal(shares),
                new BigDecimal(amountUsd),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T12:00:05Z")
        );
    }

    private ExecutorOrderResponse restingSubmit(String remoteOrderId) {
        return new ExecutorOrderResponse(
                true,
                false,
                "LIVE",
                remoteOrderId,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "accepted",
                "{}",
                Instant.parse("2026-05-23T12:00:01Z")
        );
    }

    private ExecutorOrderStatusResponse orderStatus(
            String remoteOrderId,
            String status,
            String price,
            String originalSize,
            String filledSize,
            String remainingSize,
            String avgFillPrice
    ) {
        return new ExecutorOrderStatusResponse(
                true,
                remoteOrderId,
                status,
                MARKET_ID,
                TOKEN_ID,
                TradeSide.BUY,
                price == null ? null : new BigDecimal(price),
                originalSize == null ? null : new BigDecimal(originalSize),
                filledSize == null ? null : new BigDecimal(filledSize),
                remainingSize == null ? null : new BigDecimal(remainingSize),
                avgFillPrice == null ? null : new BigDecimal(avgFillPrice),
                Instant.parse("2026-05-23T12:00:01Z"),
                Instant.parse("2026-05-23T12:00:02Z"),
                Instant.parse("2026-05-23T12:05:00Z"),
                "{}",
                null
        );
    }

    private ExecutorFillsResponse noFills() {
        return new ExecutorFillsResponse(true, List.of(), "[]", null);
    }

    private ExecutorFillsResponse fills(ExecutorFillResponse... fillResponses) {
        return new ExecutorFillsResponse(true, List.of(fillResponses), "[]", null);
    }

    private ExecutorFillResponse fill(String remoteOrderId, String fillId, String shares, String price, String fee) {
        return new ExecutorFillResponse(
                remoteOrderId,
                "trade-" + fillId,
                fillId,
                TOKEN_ID,
                MARKET_ID,
                TradeSide.BUY,
                new BigDecimal(price),
                new BigDecimal(shares),
                new BigDecimal(fee),
                LiquidityRole.MAKER,
                Instant.parse("2026-05-23T12:00:03Z"),
                "{}"
        );
    }
}
