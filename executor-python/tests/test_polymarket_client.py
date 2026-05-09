from decimal import Decimal

from voktrader_executor.config import Settings
from voktrader_executor.models import OrderCommand
from voktrader_executor.polymarket_client import PolymarketExecutor


def test_matched_buy_uses_exchange_making_and_taking_amounts():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="cost-aware-momentum-paper",
        marketId="2127144",
        tokenId="token",
        side="BUY",
        amountUsd=Decimal("1.00"),
        limitPrice=Decimal("0.66"),
        timeInForce="FOK",
        dryRun=False,
    )

    response = PolymarketExecutor(Settings())._normalize_response(
        command,
        {
            "errorMsg": "",
            "makingAmount": "1",
            "orderID": "0x58ff",
            "status": "MATCHED",
            "success": True,
            "takingAmount": "1.5625",
        },
    )

    assert response.accepted is True
    assert response.filled is True
    assert response.status == "MATCHED"
    assert response.filledAmountUsd == Decimal("1.00")
    assert response.filledShares == Decimal("1.562500")
    assert response.averagePrice == Decimal("0.64000000")
    assert response.feeUsd is None


def test_missing_fee_stays_none_but_explicit_zero_is_preserved():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="cost-aware-momentum-paper",
        marketId="2127144",
        tokenId="token",
        side="BUY",
        amountUsd=Decimal("1.00"),
        limitPrice=Decimal("0.60"),
        timeInForce="FOK",
        dryRun=False,
    )

    missing = PolymarketExecutor(Settings())._normalize_response(
        command,
        {"status": "MATCHED", "success": True, "makingAmount": "1", "takingAmount": "1.666665"},
    )
    explicit_zero = PolymarketExecutor(Settings())._normalize_response(
        command,
        {"status": "MATCHED", "success": True, "makingAmount": "1", "takingAmount": "1.666665", "fee": "0"},
    )

    assert missing.feeUsd is None
    assert explicit_zero.feeUsd == Decimal("0")


def test_matched_sell_uses_exchange_making_and_taking_amounts():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="cost-aware-momentum-paper",
        marketId="2127144",
        tokenId="token",
        side="SELL",
        shares=Decimal("1.515152"),
        limitPrice=Decimal("0.56"),
        timeInForce="FOK",
        dryRun=False,
    )

    response = PolymarketExecutor(Settings())._normalize_response(
        command,
        {
            "errorMsg": "",
            "makingAmount": "1.51",
            "orderID": "0x58ff",
            "status": "MATCHED",
            "success": True,
            "takingAmount": "0.8456",
        },
    )

    assert response.accepted is True
    assert response.filled is True
    assert response.status == "MATCHED"
    assert response.filledShares == Decimal("1.51")
    assert response.filledAmountUsd == Decimal("0.8456")
    assert response.averagePrice == Decimal("0.56000000")


def test_zero_share_sell_is_rejected_before_exchange_call():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="cost-aware-momentum-paper",
        marketId="2127144",
        tokenId="token",
        side="SELL",
        shares=Decimal("0"),
        limitPrice=Decimal("0.45"),
        timeInForce="FOK",
        dryRun=False,
    )

    try:
        PolymarketExecutor(Settings())._validate_guardrails(command)
    except ValueError as exc:
        assert str(exc) == "SELL requires positive shares"
    else:
        raise AssertionError("Expected zero-share sell to be rejected")


def test_all_supported_time_in_force_values_are_accepted():
    for time_in_force in ("FOK", "FAK", "GTC", "GTD"):
        command = OrderCommand(
            idempotencyKey=f"test-{time_in_force}",
            strategyId="strategy",
            marketId="2127144",
            tokenId="token",
            side="BUY",
            amountUsd=Decimal("1.00"),
            limitPrice=Decimal("0.60"),
            timeInForce=time_in_force.lower(),
            dryRun=True,
        )

        assert command.timeInForce == time_in_force


def test_post_only_is_rejected_for_immediate_fill_orders():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="strategy",
        marketId="2127144",
        tokenId="token",
        side="BUY",
        amountUsd=Decimal("1.00"),
        limitPrice=Decimal("0.60"),
        timeInForce="FAK",
        postOnly=True,
        dryRun=True,
    )

    try:
        PolymarketExecutor(Settings())._validate_guardrails(command)
    except ValueError as exc:
        assert str(exc) == "postOnly is only supported for GTC/GTD limit orders"
    else:
        raise AssertionError("Expected FAK post-only to be rejected")


def test_dry_run_response_keeps_explicit_zero_fee():
    command = OrderCommand(
        idempotencyKey="test",
        strategyId="cost-aware-momentum-paper",
        marketId="2127144",
        tokenId="token",
        side="BUY",
        amountUsd=Decimal("1.00"),
        limitPrice=Decimal("0.60"),
        timeInForce="FOK",
        dryRun=True,
    )

    response = PolymarketExecutor(Settings())._dry_run_response(command)

    assert response.feeUsd == Decimal("0")


def test_dry_run_gtc_post_only_is_submitted_not_filled():
    command = OrderCommand(
        idempotencyKey="maker-test",
        strategyId="maker-resolution-carry",
        marketId="2127144",
        tokenId="token",
        side="BUY",
        amountUsd=Decimal("1.00"),
        limitPrice=Decimal("0.50"),
        timeInForce="GTC",
        postOnly=True,
        dryRun=True,
    )

    response = PolymarketExecutor(Settings())._dry_run_response(command)

    assert response.accepted is True
    assert response.filled is False
    assert response.status == "DRY_RUN_SUBMITTED"
    assert response.filledShares == Decimal("0")
    assert response.filledAmountUsd == Decimal("0")


def test_limit_order_rejects_when_size_cannot_be_derived():
    command = OrderCommand(
        idempotencyKey="maker-test",
        strategyId="maker-resolution-carry",
        marketId="2127144",
        tokenId="token",
        side="SELL",
        limitPrice=Decimal("0.55"),
        timeInForce="GTC",
        postOnly=True,
        dryRun=False,
    )

    try:
        PolymarketExecutor(Settings())._submit_limit_order(object(), command)
    except ValueError as exc:
        assert str(exc) == "Limit orders require positive shares or amountUsd convertible to shares"
    else:
        raise AssertionError("Expected missing size to be rejected")


class FakeOrderManagementClient:
    def cancel_order(self, order_id):
        return {"success": True, "orderID": order_id, "status": "CANCELLED"}

    def get_order(self, order_id):
        return {
            "orderID": order_id,
            "status": "OPEN",
            "market": "market-id",
            "asset_id": "token-id",
            "side": "BUY",
            "price": "0.51",
            "size": "10",
            "filled_size": "2",
            "remaining_size": "8",
        }

    def get_open_orders(self, **_):
        return {"orders": [{"orderID": "order-1", "status": "LIVE", "asset_id": "token-id"}]}

    def get_trades(self, **_):
        return {
            "trades": [
                {
                    "id": "fill-1",
                    "orderID": "order-1",
                    "asset_id": "token-id",
                    "side": "BUY",
                    "price": "0.51",
                    "size": "2",
                    "fee": "0.01",
                    "role": "MAKER",
                    "created_at": "2026-05-09T12:00:00Z",
                }
            ]
        }


def live_executor_with_fake_client():
    executor = PolymarketExecutor(Settings(EXECUTOR_DRY_RUN=False))
    executor._client = FakeOrderManagementClient()
    return executor


def test_cancel_order_success_mapping():
    response = live_executor_with_fake_client().cancel_order("order-1")

    assert response.success is True
    assert response.remoteOrderId == "order-1"
    assert response.status == "CANCELLED"
    assert response.error is None


def test_get_order_status_mapping():
    response = live_executor_with_fake_client().get_order_status("order-1")

    assert response.success is True
    assert response.remoteOrderId == "order-1"
    assert response.status == "OPEN"
    assert response.marketId == "market-id"
    assert response.tokenId == "token-id"
    assert response.filledSize == Decimal("2")
    assert response.remainingSize == Decimal("8")


def test_list_open_orders_mapping():
    response = live_executor_with_fake_client().list_open_orders(token_id="token-id")

    assert response.success is True
    assert len(response.orders) == 1
    assert response.orders[0].remoteOrderId == "order-1"
    assert response.orders[0].status == "LIVE"


def test_list_fills_mapping():
    response = live_executor_with_fake_client().list_fills(order_id="order-1")

    assert response.success is True
    assert len(response.fills) == 1
    assert response.fills[0].fillId == "fill-1"
    assert response.fills[0].remoteOrderId == "order-1"
    assert response.fills[0].role == "MAKER"
    assert response.fills[0].fee == Decimal("0.01")
