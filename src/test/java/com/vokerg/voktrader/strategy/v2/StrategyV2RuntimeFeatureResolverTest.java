package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyV2RuntimeFeatureResolverTest {
    private final StrategyV2FeatureResolver resolver = new StrategyV2FeatureResolver();

    @Test
    void unknownFeeUsesFallbackEstimateForUnrealizedPnl() {
        StrategyRuntimeState state = new StrategyRuntimeState(
                StrategyInstanceKey.of(null, "strategy-test"),
                "market-id",
                "strategy-test",
                "token-up",
                TradeStatus.OPEN,
                null,
                null,
                new BigDecimal("10"),
                BigDecimal.ZERO,
                new BigDecimal("0.50"),
                null,
                false,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-09T12:00:00Z"),
                null
        );

        StrategyV2FeatureContext context = resolver.contexts(market(), marketView(), new BigDecimal("1.00"), state).get(0);

        assertThat(context.features().get("position.fee_known")).isEqualTo(false);
        assertThat(context.features().get("position.realized_fee_usd")).isNull();
        assertThat((BigDecimal) context.features().get("position.unrealized_pnl_usd")).isEqualByComparingTo("0.6400000000");
        assertThat(context.features().get("trade.estimated_net_pnl_usd")).isEqualTo(context.features().get("position.unrealized_pnl_usd"));
        assertThat(context.features().get("trade.estimated_net_pnl_pct")).isEqualTo(context.features().get("position.unrealized_pnl_pct"));
        assertThat(context.features().get("trade.hold_seconds")).isEqualTo(context.features().get("position.entry_age_seconds"));
        assertThat(context.features().get("trade.filled_shares")).isEqualTo(context.features().get("position.filled_shares"));
        assertThat(context.features().get("trade.avg_entry_price")).isEqualTo(context.features().get("position.avg_entry_price"));
        assertThat(context.features().get("trade.realized_fee_usd")).isEqualTo(context.features().get("position.realized_fee_usd"));
        assertThat(context.features().get("trade.fee_known")).isEqualTo(context.features().get("position.fee_known"));
        assertThat(context.features()).doesNotContainKey("trade.max_adverse_excursion_usd");
    }

    private StrategyMarketView marketView() {
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        StrategyOutcomeView up = outcome("Up", "token-up", "0.62", "0.04");
        StrategyOutcomeView down = outcome("Down", "token-down", "0.38", "0.04");
        when(marketView.outcomes()).thenReturn(List.of(up, down));
        when(marketView.outcome("Up")).thenReturn(Optional.of(up));
        when(marketView.outcome("Down")).thenReturn(Optional.of(down));
        when(marketView.token("token-up")).thenReturn(Optional.of(up));
        when(marketView.token("token-down")).thenReturn(Optional.of(down));
        return marketView;
    }

    private StrategyOutcomeView outcome(String outcome, String tokenId, String mid, String spread) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(outcome);
        when(view.tokenId()).thenReturn(tokenId);
        when(view.mid()).thenReturn(new BigDecimal(mid));
        when(view.spread()).thenReturn(new BigDecimal(spread));
        when(view.priceAgeMs()).thenReturn(Optional.empty());
        when(view.bookAgeMs()).thenReturn(Optional.empty());
        when(view.bidDepth()).thenReturn(BigDecimal.ZERO);
        when(view.askDepth()).thenReturn(BigDecimal.ZERO);
        when(view.bidDepthWithin(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        when(view.askDepthWithin(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        when(view.bestBidLevel()).thenReturn(Optional.empty());
        when(view.bestAskLevel()).thenReturn(Optional.empty());
        when(view.estimateTakerBuy(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(view.estimateMakerBuyFee(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        return view;
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                Instant.parse("2026-05-09T12:05:00Z"),
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
}
