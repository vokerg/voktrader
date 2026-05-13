from __future__ import annotations

import json
import logging
import time
from decimal import Decimal
from datetime import datetime
from typing import Any
from uuid import uuid4

from .config import Settings
from .log_colors import event_label
from .models import (
    CancelOrderResponse,
    ExecutorError,
    FillResponse,
    FillsResponse,
    OpenOrdersResponse,
    OrderCommand,
    OrderResponse,
    OrderStatusResponse,
    TradeSide,
)

logger = logging.getLogger("uvicorn.error")


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

    def cancel_order(self, order_id: str) -> CancelOrderResponse:
        if self.settings.executor_dry_run:
            return CancelOrderResponse(
                success=True,
                remoteOrderId=order_id,
                status="DRY_RUN_CANCELLED",
                rawResponse=json.dumps({"dry_run": True, "order_id": order_id, "cancelled": True}, sort_keys=True),
            )

        client = self._get_client()
        raw_response = _cancel_order(client, order_id)
        return _normalize_cancel_response(order_id, raw_response)

    def get_order_status(self, order_id: str) -> OrderStatusResponse:
        if self.settings.executor_dry_run:
            return OrderStatusResponse(
                success=True,
                remoteOrderId=order_id,
                status="DRY_RUN_UNKNOWN",
                rawResponse=json.dumps({"dry_run": True, "order_id": order_id}, sort_keys=True),
            )

        client = self._get_client()
        raw_response = _call_client_method(
            client,
            ("get_order", "get_order_status"),
            order_id=order_id,
            id=order_id,
        )
        response = _normalize_order_status(raw_response, fallback_order_id=order_id)
        logger.info(
            "%s: orderId=%s raw=%s normalized=%s",
            event_label("EXECUTOR ORDER STATUS RAW"),
            order_id,
            _json_for_log(raw_response),
            response.model_dump_json(),
        )
        return response

    def list_open_orders(self, market_id: str | None = None, token_id: str | None = None) -> OpenOrdersResponse:
        if self.settings.executor_dry_run:
            return OpenOrdersResponse(
                success=True,
                orders=[],
                rawResponse=json.dumps(
                    {"dry_run": True, "market_id": market_id, "token_id": token_id, "orders": []},
                    sort_keys=True,
                ),
            )

        client = self._get_client()
        raw_response = _call_client_method(
            client,
            ("get_orders", "get_open_orders", "list_open_orders"),
            market=market_id,
            market_id=market_id,
            token_id=token_id,
            asset_id=token_id,
        )
        items = _extract_list(raw_response, "orders", "data", "results")
        return OpenOrdersResponse(
            success=True,
            orders=[_normalize_order_status(item) for item in items],
            rawResponse=json.dumps(raw_response, default=str, sort_keys=True),
        )

    def list_fills(
            self,
            order_id: str | None = None,
            market_id: str | None = None,
            token_id: str | None = None,
            side: str | None = None,
            price: str | None = None,
            shares: str | None = None,
            since: str | None = None,
    ) -> FillsResponse:
        if self.settings.executor_dry_run:
            return FillsResponse(
                success=True,
                fills=[],
                rawResponse=json.dumps(
                    {
                        "dry_run": True,
                        "order_id": order_id,
                        "market_id": market_id,
                        "token_id": token_id,
                        "side": side,
                        "price": price,
                        "shares": shares,
                        "since": since,
                        "fills": [],
                    },
                    sort_keys=True,
                ),
            )

        client = self._get_client()
        raw_response = _list_trade_history(client, order_id, market_id, token_id, since)
        items = _extract_list(raw_response, "fills", "trades", "data", "results")
        extracted_count = len(items)
        if order_id is not None:
            order_id_matches = [item for item in items if _matches_order_id(item, order_id)]
            if order_id_matches:
                items = order_id_matches
            else:
                items = [item for item in items if _matches_order_profile(item, token_id, side, price, shares)]
        response = FillsResponse(
            success=True,
            fills=[_normalize_fill(item) for item in items],
            rawResponse=json.dumps(raw_response, default=str, sort_keys=True),
        )
        logger.info(
            "%s: orderId=%s marketId=%s tokenId=%s side=%s price=%s shares=%s since=%s extractedCount=%s matchedCount=%s raw=%s normalized=%s",
            event_label("EXECUTOR FILLS RAW"),
            order_id,
            market_id,
            token_id,
            side,
            price,
            shares,
            since,
            extracted_count,
            len(items),
            _json_for_log(raw_response),
            response.model_dump_json(),
        )
        return response

    def _validate_guardrails(self, command: OrderCommand) -> None:
        if command.amountUsd is not None and command.amountUsd > Decimal(str(self.settings.max_order_amount_usd)):
            raise ValueError(
                f"amountUsd {command.amountUsd} exceeds MAX_ORDER_AMOUNT_USD {self.settings.max_order_amount_usd}"
            )
        if self.settings.require_fok and command.timeInForce.upper() != "FOK":
            raise ValueError("Only FOK orders are allowed while REQUIRE_FOK=true")
        if command.postOnly and command.timeInForce.upper() in {"FOK", "FAK"}:
            raise ValueError("postOnly is only supported for GTC/GTD limit orders")
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

        kwargs: dict[str, Any] = {
            "token_id": command.tokenId,
            "side": side,
            "price": float(command.limitPrice),
            "size": float(size),
        }
        if command.timeInForce.upper() == "GTD":
            kwargs["expiration"] = self._gtd_expiration()

        args = OrderArgs(**kwargs)
        return client.create_and_post_order(args, order_type=order_type, post_only=command.postOnly)

    def _gtd_expiration(self) -> int:
        # Polymarket requires GTD expiration to be in the future with an extra one-minute security buffer.
        return int(time.time()) + 60 + max(1, int(self.settings.gtd_expiration_seconds))

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


def _call_client_method(client: Any, method_names: tuple[str, ...], **kwargs: Any) -> Any:
    filtered_kwargs = {key: value for key, value in kwargs.items() if value is not None}
    for method_name in method_names:
        method = getattr(client, method_name, None)
        if method is None:
            continue
        try:
            return method(**filtered_kwargs)
        except TypeError:
            positional = filtered_kwargs.get("order_id") or filtered_kwargs.get("id")
            if positional is not None:
                try:
                    return method(positional)
                except TypeError:
                    pass
            return method()
    raise UnsupportedOperationError(f"Polymarket SDK client has none of: {', '.join(method_names)}")


def _cancel_order(client: Any, order_id: str) -> Any:
    cancel_order = getattr(client, "cancel_order", None)
    if cancel_order is not None:
        from py_clob_client_v2.clob_types import OrderPayload

        return cancel_order(OrderPayload(orderID=order_id))
    return _call_client_method(
        client,
        ("cancel", "delete_order"),
        order_id=order_id,
        id=order_id,
    )


def _list_trade_history(
        client: Any,
        order_id: str | None,
        market_id: str | None,
        token_id: str | None,
        since: str | None,
) -> Any:
    get_trades = getattr(client, "get_trades", None)
    if get_trades is not None:
        from py_clob_client_v2.clob_types import TradeParams

        params = TradeParams(
            asset_id=token_id,
            after=_epoch_seconds_or_none(since),
        )
        logger.info(
            "%s: method=get_trades orderId=%s gammaMarketId=%s assetId=%s since=%s after=%s",
            event_label("EXECUTOR FILLS SDK REQUEST"),
            order_id,
            market_id,
            token_id,
            since,
            params.after,
        )
        return get_trades(params=params)

    return _call_client_method(
        client,
        ("get_fills", "get_trade_history"),
        order_id=order_id,
        market=market_id,
        market_id=market_id,
        token_id=token_id,
        asset_id=token_id,
        since=since,
    )


def _json_for_log(value: Any) -> str:
    return json.dumps(value, default=str, sort_keys=True)


def _matches_order_id(item: Any, order_id: str) -> bool:
    if not isinstance(item, dict):
        return False
    if _fill_order_id(item) == order_id:
        return True
    return False


def _matches_order_profile(
        item: Any,
        token_id: str | None,
        side: str | None,
        price: str | None,
        shares: str | None,
) -> bool:
    if not isinstance(item, dict):
        return False
    normalized = _normalize_fill(item)
    if token_id is not None and normalized.tokenId != token_id:
        return False
    if side is not None and (normalized.side or "").upper() != side.upper():
        return False
    if price is not None and normalized.price != Decimal(str(price)):
        return False
    if shares is not None and normalized.shares != Decimal(str(shares)):
        return False
    return normalized.price is not None and normalized.shares is not None


def _fill_order_id(item: dict[str, Any]) -> str | None:
    order_id = _first_present(item, *FILL_ORDER_ID_KEYS)
    if order_id is not None:
        return str(order_id)
    maker_orders = item.get("maker_orders") or item.get("makerOrders")
    if isinstance(maker_orders, list):
        for order in maker_orders:
            if isinstance(order, dict):
                nested_order_id = _first_present(order, *FILL_ORDER_ID_KEYS)
                if nested_order_id is not None:
                    return str(nested_order_id)
    return None


def _epoch_seconds_or_none(value: str | None) -> int | None:
    if value is None or value == "":
        return None
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    if "." in normalized:
        prefix, suffix = normalized.split(".", 1)
        fraction = suffix
        timezone = ""
        for marker in ("+", "-"):
            if marker in suffix:
                fraction, timezone = suffix.split(marker, 1)
                timezone = marker + timezone
                break
        normalized = prefix + "." + fraction[:6] + timezone
    try:
        return int(datetime.fromisoformat(normalized).timestamp())
    except ValueError:
        return None


class UnsupportedOperationError(RuntimeError):
    pass


ORDER_ID_KEYS = (
    "orderID",
    "orderId",
    "order_id",
    "id",
)

FILL_ORDER_ID_KEYS = (
    "orderID",
    "orderId",
    "order_id",
    "maker_order_id",
    "makerOrderId",
    "taker_order_id",
    "takerOrderId",
)


def _normalize_cancel_response(order_id: str, raw_response: Any) -> CancelOrderResponse:
    data = raw_response if isinstance(raw_response, dict) else {}
    success = bool(_first_present(data, "success", "cancelled", "canceled") if data else True)
    status = str(_first_present(data, "status", "state") or ("CANCELLED" if success else "FAILED")).upper()
    return CancelOrderResponse(
        success=success,
        remoteOrderId=str(_first_present(data, "orderID", "orderId", "id", "order_id") or order_id),
        status=status,
        rawResponse=json.dumps(raw_response, default=str, sort_keys=True),
        error=None if success else ExecutorError(type="EXCHANGE_REJECTION", message=str(_first_present(data, "error", "message") or status)),
    )


def _normalize_order_status(raw_response: Any, fallback_order_id: str | None = None) -> OrderStatusResponse:
    data = raw_response if isinstance(raw_response, dict) else {}
    success = bool(data) or raw_response is not None
    return OrderStatusResponse(
        success=success,
        remoteOrderId=_string_or_none(_first_present(data, "orderID", "orderId", "id", "order_id") or fallback_order_id),
        status=str(_first_present(data, "status", "state") or "UNKNOWN").upper(),
        marketId=_string_or_none(_first_present(data, "market", "marketId", "market_id")),
        tokenId=_string_or_none(_first_present(data, "tokenId", "token_id", "asset_id")),
        side=_string_or_none(_first_present(data, "side")),
        price=_decimal_or_none(_first_present(data, "price", "limitPrice", "limit_price")),
        originalSize=_decimal_or_none(_first_present(data, "originalSize", "original_size", "size", "amount")),
        filledSize=_decimal_or_none(_first_present(data, "filledSize", "filled_size", "size_matched", "matchedSize")),
        remainingSize=_decimal_or_none(_first_present(data, "remainingSize", "remaining_size", "remaining")),
        avgFillPrice=_decimal_or_none(_first_present(data, "avgFillPrice", "averagePrice", "avg_price", "matchedPrice")),
        createdAt=_datetime_or_none(_first_present(data, "createdAt", "created_at")),
        updatedAt=_datetime_or_none(_first_present(data, "updatedAt", "updated_at")),
        expiresAt=_datetime_or_none(_first_present(data, "expiresAt", "expires_at", "expiration")),
        rawResponse=json.dumps(raw_response, default=str, sort_keys=True),
    )


def _normalize_fill(raw_response: Any) -> FillResponse:
    data = raw_response if isinstance(raw_response, dict) else {}
    role = str(_first_present(data, "role", "liquidityRole", "liquidity_role") or "UNKNOWN").upper()
    if role not in {"MAKER", "TAKER"}:
        role = "UNKNOWN"
    return FillResponse(
        remoteOrderId=_fill_order_id(data),
        tradeId=_string_or_none(_first_present(data, "tradeID", "tradeId", "trade_id", "transactionHash", "transaction_hash")),
        fillId=_string_or_none(_first_present(data, "fillID", "fillId", "fill_id", "id", "transactionHash", "transaction_hash")),
        tokenId=_string_or_none(_first_present(data, "tokenId", "token_id", "asset_id")),
        marketId=_string_or_none(_first_present(data, "market", "marketId", "market_id")),
        side=_string_or_none(_first_present(data, "side")),
        price=_decimal_or_none(_first_present(data, "price", "matchedPrice")),
        shares=_decimal_or_none(_first_present(data, "size", "shares", "amount", "matchedSize")),
        fee=_decimal_or_none(_first_present(data, "fee", "feeUsd", "fee_usd")),
        role=role,
        timestamp=_datetime_or_none(_first_present(data, "timestamp", "createdAt", "created_at", "filledAt", "filled_at", "match_time")),
        rawResponse=json.dumps(raw_response, default=str, sort_keys=True),
    )


def _extract_list(raw_response: Any, *keys: str) -> list[Any]:
    if isinstance(raw_response, list):
        return raw_response
    if isinstance(raw_response, dict):
        for key in keys:
            value = raw_response.get(key)
            if isinstance(value, list):
                return value
        return [raw_response] if raw_response else []
    return []


def _string_or_none(value: Any) -> str | None:
    return str(value) if value is not None else None


def _datetime_or_none(value: Any) -> Any:
    if value is None or value == "":
        return None
    return value


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
