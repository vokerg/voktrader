package com.vokerg.voktrader.economy;

import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class EconomyService implements TradeEconomy {
    private static final int MONEY_SCALE = 8;

    private final TradingProperties tradingProperties;
    private final PolymarketFeeCalculator feeCalculator;

    @Override
    public FeeEstimate estimateFee(BigDecimal shares, BigDecimal price, LiquidityRole liquidityRole) {
        LiquidityRole role = liquidityRole == null ? LiquidityRole.UNKNOWN : liquidityRole;
        BigDecimal feeRate = feeRateFor(role);
        BigDecimal feeUsd = feeCalculator.estimateFeeUsd(shares, price, feeRate);
        return new FeeEstimate(role, feeRate, feeUsd);
    }

    @Override
    public ExitEconomy estimateExit(
            TradeEntity trade,
            BigDecimal exitPrice,
            BigDecimal minimumProfitUsd,
            LiquidityRole liquidityRole
    ) {
        BigDecimal shares = trade.getEntryFilledShares() == null ? zero() : trade.getEntryFilledShares();
        BigDecimal entryFee = trade.getEntryFeeUsd() == null ? zero() : trade.getEntryFeeUsd();
        BigDecimal entryCost = trade.getEntryFilledUsd() == null ? zero() : trade.getEntryFilledUsd();
        BigDecimal grossExitValue = shares.multiply(exitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        FeeEstimate exitFee = estimateFee(shares, exitPrice, liquidityRole);
        BigDecimal totalFee = entryFee.add(exitFee.feeUsd()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal netPnl = grossExitValue
                .subtract(exitFee.feeUsd())
                .subtract(entryCost)
                .subtract(entryFee)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal threshold = minimumProfitUsd == null ? zero() : minimumProfitUsd;

        return new ExitEconomy(
                exitFee.liquidityRole(),
                grossExitValue,
                exitFee.feeUsd(),
                totalFee,
                netPnl,
                threshold,
                netPnl.compareTo(threshold) >= 0
        );
    }

    public BigDecimal feeRateFor(LiquidityRole liquidityRole) {
        return switch (liquidityRole == null ? LiquidityRole.UNKNOWN : liquidityRole) {
            case MAKER -> tradingProperties.getMakerFeeRate();
            case TAKER, UNKNOWN -> tradingProperties.getTakerFeeRate();
            case SIMULATED -> tradingProperties.getPaperFeeRate();
        };
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
