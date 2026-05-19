package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.bot.BotRuntimeContext;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotEntity;
import com.vokerg.voktrader.marketdata.model.PriceSnapshotEntity;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotRepository;
import com.vokerg.voktrader.marketdata.persistence.PriceSnapshotRepository;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.strategy.v2.StrategyV2Engine;
import com.vokerg.voktrader.strategy.v2.StrategyV2FeatureSampleContext;
import com.vokerg.voktrader.strategy.v2.StrategyV2OverrideContext;
import com.vokerg.voktrader.strategy.v2.StrategyV2OverrideParser;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.ExecutionOverrideContext;
import com.vokerg.voktrader.trade.BacktestTradeStateContext;
import com.vokerg.voktrader.trade.OrderGatewayContext;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.TradeHistoryScopeContext;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.simulation.BookOrderFillSimulator;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestReplayService {
    private final BacktestStrategyResolver strategyResolver;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final MarketDepthSnapshotRepository depthSnapshotRepository;
    private final MarketRepository marketRepository;
    private final BacktestRunRepository backtestRunRepository;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingProperties tradingProperties;
    private final BacktestExecutionProperties backtestExecutionProperties;
    private final BookOrderFillSimulator bookOrderFillSimulator;
    private final StrategyV2OverrideParser strategyV2OverrideParser;

    @Transactional
    public BacktestResponse run(BacktestRequest request) {
        Instant wallClockStartedAt = Instant.now();
        long wallClockStartedNs = System.nanoTime();
        String runId = "bt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        TradingStrategy strategy = strategyResolver.resolve(request.strategyId());
        List<Long> numericMarketIds = request.marketIds().stream().map(this::parseMarketId).toList();
        StrategyV2Properties override = strategyOverride(request, strategy);

        BacktestRunEntity run = backtestRunRepository.save(new BacktestRunEntity(
                runId,
                strategy.id(),
                String.join(",", request.marketIds())
        ));

        BacktestExecutionService executor = new BacktestExecutionService(
                runId,
                tradeRepository,
                tradeOrderRepository,
                tradeFillRepository,
                feeCalculator,
                tradingProperties
        );
        BacktestOrderGateway orderGateway = new BacktestOrderGateway(
                runId,
                tradeRepository,
                tradeOrderRepository,
                tradeFillRepository,
                feeCalculator,
                tradingProperties,
                backtestExecutionProperties,
                bookOrderFillSimulator
        );
        BacktestDiagnostics diagnostics = new BacktestDiagnostics();

        long botId = request.botId() == null ? -Math.abs(System.nanoTime()) : request.botId();
        long snapshotsSeen = 0;
        Instant replayStartedAt = null;
        Instant replayEndedAt = null;
        long priceLoadStartedNs = System.nanoTime();
        List<PriceSnapshotEntity> priceSnapshots =
                priceSnapshotRepository.findByMarketIdInOrderByMarketIdAscCapturedAtAsc(numericMarketIds);
        long priceLoadEndedNs = System.nanoTime();
        long priceGroupStartedNs = System.nanoTime();
        Map<Long, List<PriceSnapshotEntity>> snapshotsByMarket = groupByMarket(priceSnapshots);
        long priceGroupEndedNs = System.nanoTime();
        long depthLoadStartedNs = System.nanoTime();
        List<MarketDepthSnapshotEntity> depthSnapshots =
                depthSnapshotRepository.findByMarketIdInOrderByMarketIdAscCapturedAtAsc(numericMarketIds);
        long depthLoadEndedNs = System.nanoTime();
        long depthGroupStartedNs = System.nanoTime();
        Map<DepthKey, List<MarketDepthSnapshotEntity>> depthByTick = groupDepthByTick(depthSnapshots);
        long depthGroupEndedNs = System.nanoTime();

        log.info(
                "TIME MACHINE preload: runId={} strategy={} markets={} priceRows={} depthRows={} loadPricesMs={} groupPricesMs={} loadDepthMs={} groupDepthMs={}",
                runId,
                strategy.id(),
                numericMarketIds.size(),
                priceSnapshots.size(),
                depthSnapshots.size(),
                elapsedMs(priceLoadStartedNs, priceLoadEndedNs),
                elapsedMs(priceGroupStartedNs, priceGroupEndedNs),
                elapsedMs(depthLoadStartedNs, depthLoadEndedNs),
                elapsedMs(depthGroupStartedNs, depthGroupEndedNs)
        );

        final long resolvedBotId = botId;
        final long[] snapshotsSeenRef = {snapshotsSeen};
        final Instant[] replayStartedRef = {replayStartedAt};
        final Instant[] replayEndedRef = {replayEndedAt};

        Runnable replay = () -> {
            for (Long marketId : numericMarketIds) {
                long marketStartedNs = System.nanoTime();
                List<PriceSnapshotEntity> snapshots = snapshotsByMarket.getOrDefault(marketId, List.of());
                long marketLookupEndedNs = System.nanoTime();
                MarketEntity marketEntity = marketRepository.findByPolymarketMarketId(marketId.toString()).orElse(null);
                long marketEntityEndedNs = System.nanoTime();
                log.info(
                        "TIME MACHINE market picked up: runId={} strategy={} marketId={} slug={} snapshots={} firstCapturedAt={} lastCapturedAt={} replaySpan={} endDate={}",
                        runId,
                        strategy.id(),
                        marketId,
                        marketEntity == null ? null : marketEntity.getSlug(),
                        snapshots.size(),
                        snapshots.isEmpty() ? null : snapshots.getFirst().getCapturedAt(),
                        snapshots.isEmpty() ? null : snapshots.getLast().getCapturedAt(),
                        replaySpan(snapshots),
                        marketEntity == null ? null : marketEntity.getEndDate()
                );
                long marketSnapshotsSeen = 0;
                long skippedTicks = 0;
                long tickLoopStartedNs = System.nanoTime();
                for (PriceSnapshotEntity snapshot : snapshots) {
                    List<MarketDepthSnapshotEntity> depthRows = depthByTick.getOrDefault(
                            new DepthKey(marketId, snapshot.getCapturedAt()),
                            List.of()
                    );
                    ReplayTick tick = tick(marketId, marketEntity, snapshot, depthRows);
                    if (tick == null) {
                        skippedTicks++;
                        continue;
                    }
                    replayStartedRef[0] = earlier(replayStartedRef[0], tick.capturedAt());
                    replayEndedRef[0] = later(replayEndedRef[0], tick.capturedAt());
                    snapshotsSeenRef[0]++;
                    marketSnapshotsSeen++;
                    runTick(resolvedBotId, strategy, executor, orderGateway, diagnostics, tick);
                }
                long tickLoopEndedNs = System.nanoTime();
                long resolveStartedNs = System.nanoTime();
                resolveRemainingOpenTrades(runId, marketId, marketEntity);
                long resolveEndedNs = System.nanoTime();
                long countStartedNs = System.nanoTime();
                MarketTradeCounts tradeCounts = countMarketTrades(runId, marketId);
                long countEndedNs = System.nanoTime();
                log.info(
                        "TIME MACHINE market finished: runId={} strategy={} marketId={} replayedSnapshots={} skippedTicks={} trades={} closedTrades={} openTrades={} totalReplayedSnapshots={} lookupSnapshotsMs={} loadMarketEntityMs={} tickLoopMs={} resolveOpenMs={} countTradesMs={} totalMarketMs={}",
                        runId,
                        strategy.id(),
                        marketId,
                        marketSnapshotsSeen,
                        skippedTicks,
                        tradeCounts.total(),
                        tradeCounts.closed(),
                        tradeCounts.open(),
                        snapshotsSeenRef[0],
                        elapsedMs(marketStartedNs, marketLookupEndedNs),
                        elapsedMs(marketLookupEndedNs, marketEntityEndedNs),
                        elapsedMs(tickLoopStartedNs, tickLoopEndedNs),
                        elapsedMs(resolveStartedNs, resolveEndedNs),
                        elapsedMs(countStartedNs, countEndedNs),
                        elapsedMs(marketStartedNs, countEndedNs)
                );
            }
        };

        long replayStartedNs = System.nanoTime();
        if (override != null) {
            runStrategyV2Backtest(override, replay);
        } else if (StrategyV2Engine.ID.equals(strategy.id())) {
            StrategyV2FeatureSampleContext.runIsolated(replay);
        } else {
            replay.run();
        }
        long replayEndedNs = System.nanoTime();

        long summaryStartedNs = System.nanoTime();
        BacktestSummary summary = summarize(runId, orderGateway.metrics(), wallClockStartedAt, replayStartedRef[0], replayEndedRef[0]);
        long summaryEndedNs = System.nanoTime();
        long runPersistStartedNs = System.nanoTime();
        run.complete(snapshotsSeenRef[0], summary);
        backtestRunRepository.save(run);
        long runPersistEndedNs = System.nanoTime();

        log.info(
                "TIME MACHINE summary: runId={} strategy={} snapshots={} trades={} closedTrades={} openTrades={} summaryMs={} persistRunMs={} replayMs={} totalMs={}",
                runId,
                strategy.id(),
                snapshotsSeenRef[0],
                summary.tradeCount(),
                summary.closedTradeCount(),
                summary.openTradeCount(),
                elapsedMs(summaryStartedNs, summaryEndedNs),
                elapsedMs(runPersistStartedNs, runPersistEndedNs),
                elapsedMs(replayStartedNs, replayEndedNs),
                elapsedMs(wallClockStartedNs, runPersistEndedNs)
        );

        return new BacktestResponse(
                runId,
                strategy.id(),
                request.marketIds(),
                snapshotsSeenRef[0],
                summary.tradeCount(),
                summary.closedTradeCount(),
                summary.openTradeCount(),
                summary.resolvedWinningTradeCount(),
                summary.resolvedLosingTradeCount(),
                summary.totalEntryUsd(),
                summary.totalExitUsd(),
                summary.totalFeeUsd(),
                summary.grossPnlUsd(),
                summary.finalPnlUsd(),
                summary.netRoiPct(),
                summary.replayStartedAt(),
                summary.replayEndedAt(),
                summary.replayDurationSeconds(),
                summary.wallClockDurationMs(),
                summary.orderMetrics(),
                diagnostics.eventCounts(),
                diagnostics.entryRejectReasons(),
                diagnostics.executionRejectReasons(),
                diagnostics.strategySkipReasons()
        );
    }

    private void runStrategyV2Backtest(StrategyV2Properties override, Runnable replay) {
        StrategyV2OverrideContext.runWith(
                override,
                () -> StrategyV2FeatureSampleContext.runIsolated(replay)
        );
    }

    private StrategyV2Properties strategyOverride(BacktestRequest request, TradingStrategy strategy) {
        String yamlOverride = request.strategyYamlOverride();
        if (yamlOverride == null || yamlOverride.isBlank()) {
            return null;
        }
        if (!StrategyV2Engine.ID.equals(strategy.id())) {
            throw new IllegalArgumentException("strategyYamlOverride is supported only for strategy-v2 backtests");
        }
        return strategyV2OverrideParser.parse(yamlOverride);
    }

    private void runTick(
            long botId,
            TradingStrategy strategy,
            BacktestExecutionService executor,
            BacktestOrderGateway orderGateway,
            BacktestDiagnostics diagnostics,
            ReplayTick tick
    ) {
        TimeMachine.runAt(tick.capturedAt(), () -> {
            TrackedMarketState trackedMarketState = new TrackedMarketState();
            trackedMarketState.startTracking(tick.market());

            LatestPriceState latestPriceState = new LatestPriceState();
            latestPriceState.update(tick.upTokenId(), "Up", tick.snapshot().getUpBid(), tick.snapshot().getUpAsk(), tick.capturedAt());
            latestPriceState.update(tick.downTokenId(), "Down", tick.snapshot().getDownBid(), tick.snapshot().getDownAsk(), tick.capturedAt());

            OrderBookState orderBookState = new OrderBookState();
            tick.depthRows().forEach(row -> orderBookState.update(
                    row.getTokenId(),
                    row.getOutcome(),
                    levels(row.getBestBid(), row.getBidDepth()),
                    levels(row.getBestAsk(), row.getAskDepth()),
                    row.getBookUpdatedAt() == null ? tick.capturedAt() : row.getBookUpdatedAt()
            ));

            BotRuntimeContext context = new BotRuntimeContext(
                    botId,
                    null,
                    strategy.id(),
                    null,
                    null,
                    trackedMarketState,
                    latestPriceState,
                    orderBookState
            );
            BotRuntimeContextHolder.runWith(
                    context,
                    () -> TradeHistoryScopeContext.runWithBacktestRunId(
                            executor.runId(),
                            () -> BacktestDiagnosticsContext.runWith(
                                    diagnostics,
                                    () -> {
                                        Runnable tickRunnable = () -> ExecutionOverrideContext.runWith(executor::execute, strategy::tick);
                                        OrderGatewayContext.runWith(
                                                orderGateway,
                                                () -> BacktestTradeStateContext.runWith(
                                                        orderGateway,
                                                        () -> {
                                                            orderGateway.advanceOpenOrders();
                                                            tickRunnable.run();
                                                        }
                                                )
                                        );
                                    }
                            )
                    )
            );
        });
    }

    private ReplayTick tick(
            Long marketId,
            MarketEntity entity,
            PriceSnapshotEntity snapshot,
            List<MarketDepthSnapshotEntity> depthRows
    ) {
        String upTokenId = tokenId(depthRows, "Up");
        String downTokenId = tokenId(depthRows, "Down");
        if (upTokenId == null || downTokenId == null) {
            return null;
        }

        Instant endDate = snapshot.getRemainingSeconds() == null
                ? entity == null ? null : entity.getEndDate()
                : snapshot.getCapturedAt().plusSeconds(snapshot.getRemainingSeconds());
        GammaMarketDto market = new GammaMarketDto(
                marketId.toString(),
                entity == null ? "Backtest market " + marketId : entity.getQuestion(),
                entity == null ? null : entity.getConditionId(),
                entity == null ? null : entity.getSlug(),
                endDate,
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
        return new ReplayTick(market, marketId, snapshot, depthRows, upTokenId, downTokenId, snapshot.getCapturedAt());
    }

    private List<PriceLevelDto> levels(BigDecimal price, BigDecimal size) {
        if (price == null || size == null || size.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        return List.of(new PriceLevelDto(price.toPlainString(), size.toPlainString()));
    }

    private String tokenId(List<MarketDepthSnapshotEntity> rows, String outcome) {
        return rows.stream()
                .filter(row -> outcome.equalsIgnoreCase(row.getOutcome()))
                .map(MarketDepthSnapshotEntity::getTokenId)
                .filter(tokenId -> tokenId != null && !tokenId.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void resolveRemainingOpenTrades(String runId, Long marketId, MarketEntity market) {
        if (market == null || market.getWinningOutcome() == null || market.getWinningOutcome().isBlank()) {
            return;
        }
        tradeRepository.findByBacktestRunId(runId).stream()
                .filter(trade -> marketId.toString().equals(trade.getMarketId()))
                .filter(trade -> trade.getStatus() == TradeStatus.OPEN)
                .forEach(trade -> {
                    trade.resolve(market.getWinningOutcome(), market.getResolvedAt());
                    tradeRepository.save(trade);
                });
    }

    private BacktestSummary summarize(
            String runId,
            BacktestOrderMetrics orderMetrics,
            Instant wallClockStartedAt,
            Instant replayStartedAt,
            Instant replayEndedAt
    ) {
        List<TradeEntity> trades = tradeRepository.findByBacktestRunId(runId);
        BigDecimal entryUsd = trades.stream()
                .map(TradeEntity::getEntryFilledUsd)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exitUsd = trades.stream()
                .map(TradeEntity::getExitFilledUsd)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = trades.stream()
                .map(TradeEntity::getTotalFeeUsd)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pnl = trades.stream()
                .map(TradeEntity::getFinalPnlUsd)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossPnl = pnl.add(fees);
        BigDecimal roi = entryUsd.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : pnl.divide(entryUsd, 8, RoundingMode.HALF_UP);
        Long wallClockDurationMs = Duration.between(wallClockStartedAt, Instant.now()).toMillis();
        long closed = trades.stream().filter(trade -> trade.getStatus() == TradeStatus.CLOSED || trade.getStatus() == TradeStatus.RESOLVED).count();
        long open = trades.stream().filter(trade -> trade.getStatus() == TradeStatus.OPEN).count();
        long resolvedWins = trades.stream()
                .filter(trade -> trade.getStatus() == TradeStatus.RESOLVED)
                .filter(this::resolvedWinner)
                .count();
        long resolvedLosses = trades.stream()
                .filter(trade -> trade.getStatus() == TradeStatus.RESOLVED)
                .filter(trade -> !resolvedWinner(trade))
                .count();
        return new BacktestSummary(
                trades.size(),
                closed,
                open,
                resolvedWins,
                resolvedLosses,
                entryUsd,
                exitUsd,
                fees,
                grossPnl,
                pnl,
                roi,
                replayStartedAt,
                replayEndedAt,
                BacktestSummary.durationSeconds(replayStartedAt, replayEndedAt),
                wallClockDurationMs,
                orderMetrics
        );
    }

    private boolean resolvedWinner(TradeEntity trade) {
        return trade.getOutcome() != null
                && trade.getWinningOutcome() != null
                && trade.getOutcome().equalsIgnoreCase(trade.getWinningOutcome());
    }

    private MarketTradeCounts countMarketTrades(String runId, Long marketId) {
        String marketIdText = marketId.toString();
        List<TradeEntity> trades = tradeRepository.findByBacktestRunId(runId).stream()
                .filter(trade -> marketIdText.equals(trade.getMarketId()))
                .toList();
        long closed = trades.stream()
                .filter(trade -> trade.getStatus() == TradeStatus.CLOSED || trade.getStatus() == TradeStatus.RESOLVED)
                .count();
        long open = trades.stream()
                .filter(trade -> trade.getStatus() == TradeStatus.OPEN)
                .count();
        return new MarketTradeCounts(trades.size(), closed, open);
    }

    private Map<Long, List<PriceSnapshotEntity>> groupByMarket(List<PriceSnapshotEntity> snapshots) {
        Map<Long, List<PriceSnapshotEntity>> grouped = new LinkedHashMap<>();
        snapshots.stream()
                .sorted(Comparator.comparing(PriceSnapshotEntity::getMarketId).thenComparing(PriceSnapshotEntity::getCapturedAt))
                .forEach(snapshot -> grouped.computeIfAbsent(snapshot.getMarketId(), ignored -> new ArrayList<>()).add(snapshot));
        return grouped;
    }

    private Map<DepthKey, List<MarketDepthSnapshotEntity>> groupDepthByTick(List<MarketDepthSnapshotEntity> depthSnapshots) {
        return depthSnapshots.stream()
                .collect(Collectors.groupingBy(
                        snapshot -> new DepthKey(snapshot.getMarketId(), snapshot.getCapturedAt()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private String replaySpan(List<PriceSnapshotEntity> snapshots) {
        if (snapshots == null || snapshots.size() < 2) {
            return "PT0S";
        }
        return Duration.between(snapshots.getFirst().getCapturedAt(), snapshots.getLast().getCapturedAt()).toString();
    }

    private Instant earlier(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private Long parseMarketId(String marketId) {
        try {
            return Long.parseLong(marketId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Backtest currently requires numeric Polymarket market ids: " + marketId);
        }
    }

    private long elapsedMs(long startedNs, long endedNs) {
        return Duration.ofNanos(Math.max(0L, endedNs - startedNs)).toMillis();
    }

    private record ReplayTick(
            GammaMarketDto market,
            Long numericMarketId,
            PriceSnapshotEntity snapshot,
            List<MarketDepthSnapshotEntity> depthRows,
            String upTokenId,
            String downTokenId,
            Instant capturedAt
    ) {
    }

    private record DepthKey(Long marketId, Instant capturedAt) {
    }

    private record MarketTradeCounts(long total, long closed, long open) {
    }
}
