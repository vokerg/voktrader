from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field, field_validator


SUPPORTED_TIME_IN_FORCE = ("FOK", "FAK", "GTC", "GTD")


class TradeSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


class OrderCommand(BaseModel):
    idempotencyKey: str = Field(
        description="Stable unique key from the JVM. Reusing the same key returns the cached executor response.",
        examples=["LIVE_TINY:default:maker-resolution-carry:market-id:token-id:BUY:1770000000000"],
    )
    strategyId: str = Field(description="JVM strategy id that created the order intent.", examples=["maker-resolution-carry"])
    ruleId: str | None = Field(default=None, description="Optional strategy rule id for tracing and telemetry.", examples=["maker-resolution-carry"])
    marketId: str = Field(description="Polymarket/Gamma market id used by voktrader.", examples=["2127144"])
    marketSlug: str | None = Field(default=None, description="Optional market slug for logs and troubleshooting.", examples=["bitcoin-up-or-down-may-7-5pm-et"])
    question: str | None = Field(default=None, description="Optional market question text for logs and troubleshooting.")
    conditionId: str | None = Field(default=None, description="Optional Polymarket condition id when available.")
    tokenId: str = Field(description="CLOB token id for the outcome being traded.", examples=["1234567890"])
    outcome: str | None = Field(default=None, description="Human-readable outcome name.", examples=["Up"])
    side: TradeSide = Field(description="BUY spends amountUsd. SELL sells shares.")
    amountUsd: Decimal | None = Field(
        default=None,
        description="USD amount for BUY orders. Also used to derive limit-order size when shares is omitted.",
        examples=["1.00"],
    )
    shares: Decimal | None = Field(
        default=None,
        description="Share size for SELL orders. Optional for BUY limit orders when amountUsd can be converted to size.",
        examples=["1.234567"],
    )
    limitPrice: Decimal = Field(
        gt=Decimal("0"),
        lt=Decimal("1"),
        description="Limit/protection price between 0 and 1. BUY FOK/FAK will not pay above this; SELL FOK/FAK will not sell below this.",
        examples=["0.51"],
    )
    timeInForce: str = Field(
        default="FOK",
        description=(
            "Order time-in-force. Supported values: FOK and FAK route through the SDK market-order path; "
            "GTC and GTD route through the SDK limit-order path."
        ),
        json_schema_extra={"enum": list(SUPPORTED_TIME_IN_FORCE)},
        examples=["FOK", "FAK", "GTC", "GTD"],
    )
    postOnly: bool = Field(
        default=False,
        description="Only valid with GTC/GTD limit orders. Rejects instead of taking liquidity when supported by the exchange SDK.",
        examples=[False, True],
    )
    dryRun: bool = Field(
        default=True,
        description="When true, validates and returns a simulated response without touching the exchange.",
    )
    decisionAt: datetime | None = Field(default=None, description="Timestamp of the JVM strategy decision.")

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "idempotencyKey": "smoke:fok:1",
                    "strategyId": "resolution-pressure-fok",
                    "ruleId": "resolution-pressure-fok",
                    "marketId": "2127144",
                    "tokenId": "1234567890",
                    "outcome": "Up",
                    "side": "BUY",
                    "amountUsd": "1.00",
                    "limitPrice": "0.60",
                    "timeInForce": "FOK",
                    "postOnly": False,
                    "dryRun": True,
                },
                {
                    "idempotencyKey": "smoke:maker:1",
                    "strategyId": "maker-resolution-carry",
                    "ruleId": "maker-resolution-carry",
                    "marketId": "2127144",
                    "tokenId": "1234567890",
                    "outcome": "Up",
                    "side": "BUY",
                    "amountUsd": "1.00",
                    "limitPrice": "0.51",
                    "timeInForce": "GTC",
                    "postOnly": True,
                    "dryRun": True,
                },
            ]
        }
    }

    @field_validator("amountUsd", "shares", mode="after")
    @classmethod
    def non_negative(cls, value: Decimal | None) -> Decimal | None:
        if value is not None and value < 0:
            raise ValueError("must be non-negative")
        return value

    @field_validator("timeInForce", mode="after")
    @classmethod
    def supported_time_in_force(cls, value: str) -> str:
        normalized = value.upper()
        if normalized not in SUPPORTED_TIME_IN_FORCE:
            raise ValueError(f"timeInForce must be one of {', '.join(SUPPORTED_TIME_IN_FORCE)}")
        return normalized


class OrderResponse(BaseModel):
    accepted: bool = Field(description="True when the executor accepted or submitted the order request.")
    filled: bool = Field(description="True when the response represents an immediate fill/match.")
    status: str = Field(description="Normalized executor/exchange status.", examples=["MATCHED", "SUBMITTED", "REJECTED"])
    exchangeOrderId: str | None = Field(default=None, description="Exchange order id when returned by Polymarket.")
    averagePrice: Decimal | None = Field(default=None, description="Average matched price, or command limit price when no better value is available.")
    filledShares: Decimal = Field(default=Decimal("0"), description="Filled share quantity. Zero for submitted resting orders.")
    filledAmountUsd: Decimal = Field(default=Decimal("0"), description="Filled USD amount. Zero for submitted resting orders.")
    feeUsd: Decimal | None = Field(default=None, description="Exchange fee in USD. Null means the exchange did not provide a fee.")
    message: str | None = Field(default=None, description="Human-readable executor/exchange message.")
    rawResponse: str | None = Field(default=None, description="Raw exchange response JSON string or structured error JSON string.")
    exchangeTimestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc),
        description="Sidecar timestamp for the normalized response.",
    )


class ExecutorError(BaseModel):
    type: str = Field(
        description="Normalized error class.",
        examples=["EXCHANGE_REJECTION", "NETWORK_FAILURE", "UNKNOWN_RESPONSE", "UNSUPPORTED_OPERATION"],
    )
    message: str = Field(description="Human-readable error message.")


class OrderStatusResponse(BaseModel):
    success: bool = Field(description="True when the sidecar received a usable exchange response.")
    remoteOrderId: str | None = Field(default=None, description="Exchange order id.")
    status: str = Field(default="UNKNOWN", description="Raw or normalized exchange order status.")
    marketId: str | None = None
    tokenId: str | None = None
    side: str | None = None
    price: Decimal | None = None
    originalSize: Decimal | None = None
    filledSize: Decimal | None = None
    remainingSize: Decimal | None = None
    avgFillPrice: Decimal | None = None
    createdAt: datetime | None = None
    updatedAt: datetime | None = None
    expiresAt: datetime | None = None
    rawResponse: str | None = None
    error: ExecutorError | None = None


class CancelOrderResponse(BaseModel):
    success: bool = Field(description="True when the exchange accepted the cancel request.")
    remoteOrderId: str | None = Field(default=None, description="Exchange order id.")
    status: str = Field(default="UNKNOWN", description="Cancel/order status when available.")
    rawResponse: str | None = None
    error: ExecutorError | None = None


class OpenOrdersResponse(BaseModel):
    success: bool = Field(description="True when open orders were listed successfully.")
    orders: list[OrderStatusResponse] = Field(default_factory=list)
    rawResponse: str | None = None
    error: ExecutorError | None = None


class FillResponse(BaseModel):
    remoteOrderId: str | None = None
    tradeId: str | None = None
    fillId: str | None = None
    tokenId: str | None = None
    marketId: str | None = None
    side: str | None = None
    price: Decimal | None = None
    shares: Decimal | None = None
    fee: Decimal | None = None
    role: str = Field(default="UNKNOWN", description="MAKER, TAKER, or UNKNOWN.")
    timestamp: datetime | None = None
    rawResponse: str | None = None


class FillsResponse(BaseModel):
    success: bool = Field(description="True when fills/trades were listed successfully.")
    fills: list[FillResponse] = Field(default_factory=list)
    rawResponse: str | None = None
    error: ExecutorError | None = None


class OrderVariation(BaseModel):
    timeInForce: str = Field(description="One of FOK, FAK, GTC, GTD.", json_schema_extra={"enum": list(SUPPORTED_TIME_IN_FORCE)})
    postOnly: bool = Field(description="Whether the variation requests post-only behavior.")
    route: str = Field(description="Sidecar SDK route: market or limit.", json_schema_extra={"enum": ["market", "limit"]})
    immediateFillExpected: bool = Field(description="Whether Java should expect the order to fill immediately.")
    canRestOnBook: bool = Field(description="Whether this variation can remain open on the order book.")


class ExecutorCapabilities(BaseModel):
    success: bool = Field(description="True only when executor and SDK identity evidence is available.")
    protocolVersion: str = Field(description="Versioned JVM/sidecar response contract.")
    executorVersion: str = Field(description="Installed voktrader executor package version.")
    sdkPackage: str = Field(description="Installed exchange SDK distribution name.")
    sdkVersion: str = Field(description="Installed exchange SDK version.")
    supportedTimeInForce: tuple[str, ...] = Field(
        description="All time-in-force values accepted by the sidecar request model.",
    )
    supportedVariations: tuple[OrderVariation, ...] = Field(description="Supported timeInForce/postOnly combinations.")
    unsupportedVariations: tuple[OrderVariation, ...] = Field(description="Known invalid combinations rejected by guardrails.")
    dryRun: bool = Field(description="Current sidecar-level EXECUTOR_DRY_RUN setting.")
    requireFok: bool = Field(description="Current sidecar-level REQUIRE_FOK guardrail setting.")
    maxOrderAmountUsd: Decimal = Field(description="Current sidecar-level MAX_ORDER_AMOUNT_USD guardrail.")
    error: ExecutorError | None = Field(default=None, description="Evidence failure when package identity cannot be resolved.")
