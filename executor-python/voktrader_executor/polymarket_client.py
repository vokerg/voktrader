from __future__ import annotations

import json
from decimal import Decimal
from typing import Any
from uuid import uuid4

from .config import Settings
from .models import OrderCommand, OrderResponse, TradeSide


class PolymarketExecutor:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._client: Any | None = None

    def place_order(self, command: OrderCommand) -> OrderResponse:
        effective_dry_run = command.dryRun or self.settings.executor_dry_run
        self._validate_guardrails(command)

        if effective_dry_run:
            return self._dry_run_response(command)

        client = self._get_client()
        raw_response = self._submit_order(client, command)
        return self._normalize_response(command, raw_response)

    def _validate_guardrails(self, command: OrderCommand) -> None:
        if command.amountUsd is not None and command.amountUsd > Decimal(str(self.settings.max_order_amount_usd)):
            raise ValueError(
                f"amountUsd {command.amountUsd} exceeds MAX_ORDER_AMOUNT_USD {self.settings.max_order_amount_usd}"
            )
        if self.settings.require_fok and command.timeInForce.upper() != "FOK":
            raise ValueError("Only FOK orders are allowed while REQUIRE_FOK=true")
        if command.limitPrice <= 0 or command.limitPrice >= 1:
            raise ValueError("limitPrice must be between 0 and 1")
        if command.side == TradeSide.BUY and (command.amountUsd is None or command.amountUsd <= 0):
            raise ValueError("BUY requires positive amountUsd")
        if command.side == TradeSide.SELL and (command.shares is None or command.shares <= 0):
            raise ValueError("SELL requires positive shares")

    def _get_client(self) -> Any:
        if self._client is not None:
            return self._client

        private_key = _normalize_private_key(self.settings.polymarket_private_key)
        funder = _normalize_funder_address(self.settings.polymarket_funder)

        if not private_key:
            raise RuntimeError("POLYMARKET_PRIVATE_KEY is required when EXECUTOR_DRY_RUN=false")

        # Import lazily so local dry-run smoke tests do not require the CLOB SDK.
        from py_clob_client_v2 import ApiCreds, ClobClient

        client = ClobClient(
            self.settings.polymarket_host,
            key=private_key,
            chain_id=self.settings.polymarket_chain_id,
            signature_type=self.settings.polymarket_signature_type,
            funder=funder,
        )

        if (
            self.settings.polymarket_api_key
            and self.settings.polymarket_api_secret
            and self.settings.polymarket_api_passphrase
        ):
            creds = ApiCreds(
                api_key=self.settings.polymarket_api_key,
                api_secret=self.settings.polymarket_api_secret,
                api_passphrase=self.settings.polymarket_api_passphrase,
            )
            client.set_api_creds(creds)
        else:
            client.set_api_creds(client.create_or_derive_api_key())

        self._client = client
        return client

    def _submit_order(self, client: Any, command: OrderCommand) -> Any:
        if command.timeInForce.upper() in {"GTC", "GTD"} or command.postOnly:
            return self._submit_limit_order(client, command)
        return self._submit_market_order(client, command)

    def _submit_market_order(self, client: Any, command: OrderCommand) -> Any:
        from py_clob_client_v2 import MarketOrderArgs, OrderType, Side

        side = Side.BUY if command.side == TradeSide.BUY else Side.SELL
        order_type = getattr(OrderType, command.timeInForce.upper(), OrderType.FOK)

        # py_clob_client_v2 MarketOrderArgs uses amount for both sides:
        # BUY amount is USD, SELL amount is shares.
        amount = command.amountUsd if command.side == TradeSide.BUY else command.shares
        kwargs: dict[str, Any] = {
            "token_id": command.tokenId,
            "side": side,
            "amount": float(amount),
            "price": float(command.limitPrice),
            "order_type": order_type,
        }

        args = MarketOrderArgs(**kwargs)
        return client.create_and_post_market_order(args, order_type=order_type)

    def _submit_limit_order(self, client: Any, command: OrderCommand) -> Any:
        from py_clob_client_v2 import OrderArgs, OrderType, Side

        side = Side.BUY if command.side == TradeSide.BUY else Side.SELL
        order_type = getattr(OrderType, command.timeInForce.upper(), OrderType.GTC)
        size = command.shares
        if size is None and command.amountUsd is not None:
            size = (command.amountUsd / command.limitPrice).quantize(Decimal("0.000001"))
        if size is None or size <= 0:
            raise ValueError("Limit orders require positive shares or amountUsd convertible to shares")

        args = OrderArgs(
            token_id=command.tokenId,
            side=side,
            price=float(command.limitPrice),
            size=float(size),
        )
        return client.create_and_post_order(args, order_type=order_type, post_only=command.postOnly)

    def _normalize_response(self, command: OrderCommand, raw_response: Any) -> OrderResponse:
        data = raw_response if isinstance(raw_response, dict) else {}
        raw_json = json.dumps(raw_response, default=str, sort_keys=True)

        order_id = _first_present(
            data,
            "orderID",
            "orderId",
            "id",
            "exchangeOrderId",
            "order_id",
        )
        status = str(_first_present(data, "status", "state") or "SUBMITTED").upper()
        success = bool(data.get("success", True))
        filled = status in {"FILLED", "MATCHED"} or bool(data.get("filled", False))

        filled_shares = _decimal_or_zero(
            _first_present(data, "filledSize", "filled_size", "size_matched", "matchedSize")
        )
        filled_amount = _decimal_or_zero(
            _first_present(data, "filledAmount", "filled_amount", "amount_matched", "matchedAmount")
        )
        avg_price = _decimal_or_none(
            _first_present(data, "averagePrice", "avg_price", "price", "matchedPrice")
        ) or command.limitPrice

        making_amount = _decimal_or_none(_first_present(data, "makingAmount", "making_amount"))
        taking_amount = _decimal_or_none(_first_present(data, "takingAmount", "taking_amount"))
        if filled and making_amount is not None and taking_amount is not None:
            if command.side == TradeSide.BUY:
                filled_amount = making_amount
                filled_shares = taking_amount
            else:
                filled_shares = making_amount
                filled_amount = taking_amount
            if filled_shares > 0 and filled_amount > 0:
                avg_price = (filled_amount / filled_shares).quantize(Decimal("0.00000001"))

        if filled and filled_amount == 0 and command.amountUsd is not None:
            filled_amount = command.amountUsd
        if filled and filled_shares == 0 and command.shares is not None:
            filled_shares = command.shares
        if filled and filled_shares == 0 and filled_amount > 0 and avg_price > 0:
            filled_shares = (filled_amount / avg_price).quantize(Decimal("0.000001"))
        if filled and filled_amount == 0 and filled_shares > 0 and avg_price > 0:
            filled_amount = (filled_shares * avg_price).quantize(Decimal("0.000001"))

        return OrderResponse(
            accepted=success,
            filled=filled,
            status=status,
            exchangeOrderId=str(order_id) if order_id is not None else None,
            averagePrice=avg_price,
            filledShares=filled_shares,
            filledAmountUsd=filled_amount,
            feeUsd=_decimal_or_none_for_first_present(
                data,
                "fee",
                "feeUsd",
                "fee_usd",
                "takerFee",
                "taker_fee",
            ),
            message=str(_first_present(data, "message", "error") or status),
            rawResponse=raw_json,
        )

    def _dry_run_response(self, command: OrderCommand) -> OrderResponse:
        filled_shares = command.shares
        filled_amount = command.amountUsd
        if filled_shares is None and filled_amount is not None:
            filled_shares = (filled_amount / command.limitPrice).quantize(Decimal("0.000001"))
        if filled_amount is None and filled_shares is not None:
            filled_amount = (filled_shares * command.limitPrice).quantize(Decimal("0.000001"))

        payload = {
            "dry_run": True,
            "idempotencyKey": command.idempotencyKey,
            "tokenId": command.tokenId,
            "side": command.side.value,
            "limitPrice": str(command.limitPrice),
            "amountUsd": str(command.amountUsd) if command.amountUsd is not None else None,
            "shares": str(command.shares) if command.shares is not None else None,
            "timeInForce": command.timeInForce,
            "postOnly": command.postOnly,
        }
        return OrderResponse(
            accepted=True,
            filled=not command.postOnly and command.timeInForce.upper() in {"FOK", "FAK"},
            status="DRY_RUN_FILLED" if command.timeInForce.upper() in {"FOK", "FAK"} else "DRY_RUN_SUBMITTED",
            exchangeOrderId="dryrun-" + uuid4().hex,
            averagePrice=command.limitPrice,
            filledShares=(filled_shares or Decimal("0")) if command.timeInForce.upper() in {"FOK", "FAK"} else Decimal("0"),
            filledAmountUsd=(filled_amount or Decimal("0")) if command.timeInForce.upper() in {"FOK", "FAK"} else Decimal("0"),
            feeUsd=Decimal("0"),
            message="dry-run fill; no exchange call was made" if command.timeInForce.upper() in {"FOK", "FAK"}
            else "dry-run resting order submitted; no exchange call was made",
            rawResponse=json.dumps(payload, sort_keys=True),
        )


def _first_present(data: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in data and data[key] is not None:
            return data[key]
    return None


def _decimal_or_zero(value: Any) -> Decimal:
    result = _decimal_or_none(value)
    return result if result is not None else Decimal("0")


def _decimal_or_none_for_first_present(data: dict[str, Any], *keys: str) -> Decimal | None:
    return _decimal_or_none(_first_present(data, *keys))


def _decimal_or_none(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    return Decimal(str(value))


def _normalize_private_key(value: str | None) -> str | None:
    if value is None or value.strip() == "":
        return None
    return _validated_hex(
        value,
        name="POLYMARKET_PRIVATE_KEY",
        expected_hex_chars=64,
        description="a 32-byte hex private key",
    )


def _normalize_funder_address(value: str | None) -> str | None:
    if value is None or value.strip() == "":
        return None
    return _validated_hex(
        value,
        name="POLYMARKET_FUNDER",
        expected_hex_chars=40,
        description="a 20-byte hex wallet/proxy address",
    )


def _validated_hex(value: str, *, name: str, expected_hex_chars: int, description: str) -> str:
    stripped = value.strip()
    hex_part = stripped[2:] if stripped.lower().startswith("0x") else stripped
    if len(hex_part) != expected_hex_chars or any(char not in "0123456789abcdefABCDEF" for char in hex_part):
        raise ValueError(
            f"{name} must be {description} ({expected_hex_chars} hex chars, optional 0x prefix) or left blank"
        )
    return stripped
