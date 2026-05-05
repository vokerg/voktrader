from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field, field_validator


class TradeSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


class OrderCommand(BaseModel):
    idempotencyKey: str
    strategyId: str
    ruleId: str | None = None
    marketId: str
    marketSlug: str | None = None
    question: str | None = None
    conditionId: str | None = None
    tokenId: str
    outcome: str | None = None
    side: TradeSide
    amountUsd: Decimal | None = None
    shares: Decimal | None = None
    limitPrice: Decimal = Field(gt=Decimal("0"), lt=Decimal("1"))
    timeInForce: str = "FOK"
    dryRun: bool = True
    decisionAt: datetime | None = None

    @field_validator("amountUsd", "shares", mode="after")
    @classmethod
    def non_negative(cls, value: Decimal | None) -> Decimal | None:
        if value is not None and value < 0:
            raise ValueError("must be non-negative")
        return value


class OrderResponse(BaseModel):
    accepted: bool
    filled: bool
    status: str
    exchangeOrderId: str | None = None
    averagePrice: Decimal | None = None
    filledShares: Decimal = Decimal("0")
    filledAmountUsd: Decimal = Decimal("0")
    feeUsd: Decimal | None = None
    message: str | None = None
    rawResponse: str | None = None
    exchangeTimestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
