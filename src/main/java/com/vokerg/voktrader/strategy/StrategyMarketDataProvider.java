package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.time.TimeMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class StrategyMarketDataProvider {
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int SCALE = 8;

    private final TrackedMarketState trackedMarketState;
    private final LatestPriceState latestPriceState;
    private final TradeEconomy tradeEconomy;
    private final OrderBookState fallbackOrderBookState = new OrderBookState();
    private final Clock clock = Clock.systemUTC();

    public StrategyMarketDataProvider(
            TrackedMarketState trackedMarketState,
            LatestPriceState latestPriceState,
            TradeEconomy tradeEconomy
    ) {
        this.trackedMarketState = trackedMarketState;
        this.latestPriceState = latestPriceState;
        this.tradeEconomy = tradeEconomy;
    }

    public Optional<StrategyMarketView> currentUpDownMarket() {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        OutcomePrice up = latestPriceState.byOutcome("Up").orElse(null);
        OutcomePrice down = latestPriceState.byOutcome("Down").orElse(null);
        if (market == null || up == null || down == null) {
            return Optional.empty();
        }
        Instant now = TimeMachine.now(clock);
        OrderBookState orderBookState = currentOrderBookState();
        StrategyOutcomeView upView = new DefaultStrategyOutcomeView(up, orderBookState.byTokenId(up.tokenId()).orElse(null), tradeEconomy, now);
        StrategyOutcomeView downView = new DefaultStrategyOutcomeView(down, orderBookState.byTokenId(down.tokenId()).orElse(null), tradeEconomy, now);
        return Optional.of(new DefaultStrategyMarketView(market, now, upView, downView));
    }

    private OrderBookState currentOrderBookState() {
        return OrderBookState.current().orElse(fallbackOrderBookState);
    }

    private record DefaultStrategyMarketView(
            GammaMarketDto market,
            Instant now,
            StrategyOutcomeView up,
            StrategyOutcomeView down
    ) implements StrategyMarketView {
        @Override
        public List<StrategyOutcomeView> outcomes() {
            return List.of(up, down);
        }

        @Override
        public Optional<StrategyOutcomeView> outcome(String outcome) {
            if (outcome == null || outcome.isBlank()) {
                return Optional.empty();
            }
            return outcomes().stream()
                    .filter(view -> outcome.equalsIgnoreCase(view.outcome()))
                    .findFirst();
        }

        @Override
        public Optional<StrategyOutcomeView> token(String tokenId) {
            if (tokenId == null || tokenId.isBlank()) {
                return Optional.empty();
            }
            return outcomes().stream()
                    .filter(view -> tokenId.equals(view.tokenId()))
                    .findFirst();
        }

        @Override
        public Optional<Long> secondsToExpiry() {
            if (market.endDate() == null) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(now, market.endDate()).toSeconds());
        }
    }

    private record DefaultStrategyOutcomeView(
            OutcomePrice price,
            OutcomeOrderBook book,
            TradeEconomy tradeEconomy,
            Instant now
    ) implements StrategyOutcomeView {
        @Override
        public String outcome() {
            return price.outcome();
        }

        @Override
        public String tokenId() {
            return price.tokenId();
        }

        @Override
        public Optional<OutcomeOrderBook> orderBook() {
            return Optional.ofNullable(book);
        }

        @Override
        public BigDecimal mid() {
            if (price.bid() == null || price.ask() == null) {
                return null;
            }
            return price.bid().add(price.ask()).divide(TWO, SCALE, RoundingMode.HALF_UP);
        }

        @Override
        public BigDecimal spread() {
            return price.spread();
        }

        @Override
        public Optional<Long> priceAgeMs() {
            if (price.updatedAt() == null) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(price.updatedAt(), now).toMillis());
        }

        @Override
        public Optional<Long> bookAgeMs() {
            return orderBook()
                    .map(OutcomeOrderBook::updatedAt)
                    .map(updatedAt -> Duration.between(updatedAt, now).toMillis());
        }

        @Override
        public BigDecimal bidDepth() {
            return orderBook().map(OutcomeOrderBook::bidDepth).orElse(BigDecimal.ZERO);
        }

        @Override
        public BigDecimal askDepth() {
            return orderBook().map(OutcomeOrderBook::askDepth).orElse(BigDecimal.ZERO);
        }

        @Override
        public BigDecimal bidDepthWithin(BigDecimal priceRange) {
            return orderBook().map(book -> book.bidDepthWithin(priceRange)).orElse(BigDecimal.ZERO);
        }

        @Override
        public BigDecimal askDepthWithin(BigDecimal priceRange) {
            return orderBook().map(book -> book.askDepthWithin(priceRange)).orElse(BigDecimal.ZERO);
        }

        @Override
        public Optional<OrderBookLevel> bestBidLevel() {
            return orderBook().flatMap(OutcomeOrderBook::bestBid);
        }

        @Override
        public Optional<OrderBookLevel> bestAskLevel() {
            return orderBook().flatMap(OutcomeOrderBook::bestAsk);
        }

        @Override
        public Optional<FillEstimate> estimateTakerBuy(BigDecimal amountUsd) {
            return orderBook().map(book -> book.estimateBuyUsd(amountUsd));
        }

        @Override
        public Optional<FillEstimate> estimateTakerSell(BigDecimal shares) {
            return orderBook().map(book -> book.estimateSellShares(shares));
        }

        @Override
        public Optional<FeeEstimate> estimateTakerFee(FillEstimate estimate) {
            if (estimate == null || estimate.averagePrice() == null || estimate.filledShares() == null) {
                return Optional.empty();
            }
            return Optional.of(tradeEconomy.estimateFee(estimate.filledShares(), estimate.averagePrice(), LiquidityRole.TAKER));
        }

        @Override
        public Optional<FeeEstimate> estimateMakerBuyFee(BigDecimal amountUsd) {
            return bestBidLevel().map(level -> {
                BigDecimal shares = amountUsd.divide(level.price(), SCALE, RoundingMode.HALF_UP);
                return tradeEconomy.estimateFee(shares, level.price(), LiquidityRole.MAKER);
            });
        }
    }
}
