from __future__ import annotations

import json
import logging
from decimal import Decimal

import uvicorn
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse
from py_clob_client_v2.exceptions import PolyApiException
from pydantic import ValidationError

from .config import Settings
from .idempotency import IdempotencyStore
from .models import ExecutorCapabilities, OrderCommand, OrderResponse, OrderVariation
from .polymarket_client import PolymarketExecutor

logger = logging.getLogger("uvicorn.error")
logger.setLevel(logging.INFO)
settings = Settings()
app = FastAPI(
    title="voktrader executor",
    version="0.3.0",
    description=(
        "Polymarket CLOB V2 executor sidecar for voktrader. The JVM strategy/risk engine sends order "
        "commands here over HTTP; this sidecar validates guardrails, handles idempotency, and either dry-runs "
        "or submits through the Polymarket SDK. Supported order variations are exposed at /v1/capabilities."
    ),
    openapi_tags=[
        {"name": "health", "description": "Unauthenticated process health and basic runtime state."},
        {"name": "capabilities", "description": "Authenticated executor capability and guardrail metadata."},
        {"name": "orders", "description": "Authenticated order submission endpoint used by the JVM app."},
    ],
)
idempotency_store = IdempotencyStore()
executor = PolymarketExecutor(settings)


@app.on_event("startup")
def log_startup_config() -> None:
    logger.info(
        "EXECUTOR CONFIG: dryRun=%s host=%s maxOrderAmountUsd=%s requireFok=%s",
        settings.executor_dry_run,
        settings.polymarket_host,
        settings.max_order_amount_usd,
        settings.require_fok,
    )


async def require_auth(authorization: str | None = Header(default=None)) -> None:
    expected = f"Bearer {settings.executor_api_token}"
    if authorization != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid bearer token")


@app.get(
    "/health",
    tags=["health"],
    summary="Health check",
    description="Returns basic sidecar health and dry-run state. This endpoint does not require authorization.",
)
def health() -> dict[str, object]:
    return {
        "ok": True,
        "dryRun": settings.executor_dry_run,
        "host": settings.polymarket_host,
    }


@app.get(
    "/v1/capabilities",
    response_model=ExecutorCapabilities,
    tags=["capabilities"],
    summary="Describe supported order variations",
    description=(
        "Returns supported timeInForce/postOnly combinations and current sidecar guardrails. "
        "Use this to see that FOK/FAK are immediate-fill market-order styles, while GTC/GTD are limit-order "
        "styles that may rest on the book. Requires Authorization: Bearer <EXECUTOR_API_TOKEN>."
    ),
)
def capabilities(_: None = Depends(require_auth)) -> ExecutorCapabilities:
    return ExecutorCapabilities(
        supportedVariations=(
            OrderVariation(timeInForce="FOK", postOnly=False, route="market", immediateFillExpected=True, canRestOnBook=False),
            OrderVariation(timeInForce="FAK", postOnly=False, route="market", immediateFillExpected=True, canRestOnBook=False),
            OrderVariation(timeInForce="GTC", postOnly=False, route="limit", immediateFillExpected=False, canRestOnBook=True),
            OrderVariation(timeInForce="GTC", postOnly=True, route="limit", immediateFillExpected=False, canRestOnBook=True),
            OrderVariation(timeInForce="GTD", postOnly=False, route="limit", immediateFillExpected=False, canRestOnBook=True),
            OrderVariation(timeInForce="GTD", postOnly=True, route="limit", immediateFillExpected=False, canRestOnBook=True),
        ),
        unsupportedVariations=(
            OrderVariation(timeInForce="FOK", postOnly=True, route="market", immediateFillExpected=True, canRestOnBook=False),
            OrderVariation(timeInForce="FAK", postOnly=True, route="market", immediateFillExpected=True, canRestOnBook=False),
        ),
        dryRun=settings.executor_dry_run,
        requireFok=settings.require_fok,
        maxOrderAmountUsd=Decimal(str(settings.max_order_amount_usd)),
    )


@app.post(
    "/v1/orders",
    response_model=OrderResponse,
    tags=["orders"],
    summary="Submit an order command",
    description=(
        "Submits a JVM-generated order command. Valid combinations are FOK/FAK with postOnly=false, "
        "and GTC/GTD with either postOnly=false or postOnly=true. BUY requires amountUsd. SELL requires shares. "
        "When command.dryRun or EXECUTOR_DRY_RUN is true, no exchange call is made. Requires Authorization: "
        "Bearer <EXECUTOR_API_TOKEN>."
    ),
)
def create_order(command: OrderCommand, _: None = Depends(require_auth)) -> OrderResponse:
    effective_dry_run = command.dryRun or settings.executor_dry_run
    logger.info(
        "EXECUTOR ORDER RECEIVED: dryRun=%s strategy=%s marketId=%s outcome=%s tokenId=%s side=%s amountUsd=%s shares=%s limitPrice=%s tif=%s postOnly=%s idempotencyKey=%s",
        effective_dry_run,
        command.strategyId,
        command.marketId,
        command.outcome,
        command.tokenId,
        command.side.value,
        command.amountUsd,
        command.shares,
        command.limitPrice,
        command.timeInForce,
        command.postOnly,
        command.idempotencyKey,
    )

    cached = idempotency_store.get(command.idempotencyKey)
    if cached is not None:
        log_response(command, cached, cached=True, dry_run=effective_dry_run)
        return cached

    try:
        response = executor.place_order(command)
    except (ValidationError, ValueError) as exc:
        response = OrderResponse(
            accepted=False,
            filled=False,
            status="REJECTED",
            message=str(exc),
            rawResponse=json.dumps({"error": str(exc)}, sort_keys=True),
        )
    except PolyApiException as exc:
        response = poly_api_error_response(exc)
    except Exception as exc:  # noqa: BLE001 - return a structured failure to the JVM caller.
        response = OrderResponse(
            accepted=False,
            filled=False,
            status="FAILED",
            message=str(exc),
            rawResponse=json.dumps({"error": str(exc)}, sort_keys=True),
        )

    idempotency_store.put(command.idempotencyKey, response)
    log_response(command, response, cached=False, dry_run=effective_dry_run)
    return response


def poly_api_error_response(exc: PolyApiException) -> OrderResponse:
    error = exc.error_msg
    status_code = exc.status_code
    raw_response = json.dumps(
        {"statusCode": status_code, "error": error},
        default=str,
        sort_keys=True,
    )

    if isinstance(error, dict):
        message = str(error.get("error") or error.get("message") or error)
        exchange_order_id = error.get("orderID") or error.get("orderId") or error.get("id")
    else:
        message = str(error)
        exchange_order_id = None

    status_name = "REJECTED" if status_code is not None and 400 <= status_code < 500 else "FAILED"
    return OrderResponse(
        accepted=False,
        filled=False,
        status=status_name,
        exchangeOrderId=str(exchange_order_id) if exchange_order_id is not None else None,
        message=message,
        rawResponse=raw_response,
    )


def log_response(command: OrderCommand, response: OrderResponse, *, cached: bool, dry_run: bool) -> None:
    logger.info(
        "EXECUTOR ORDER RESULT: accepted=%s filled=%s status=%s dryRun=%s cached=%s exchangeOrderId=%s strategy=%s marketId=%s outcome=%s tokenId=%s side=%s avgPrice=%s filledShares=%s filledAmountUsd=%s feeUsd=%s message=%s idempotencyKey=%s",
        response.accepted,
        response.filled,
        response.status,
        dry_run,
        cached,
        response.exchangeOrderId,
        command.strategyId,
        command.marketId,
        command.outcome,
        command.tokenId,
        command.side.value,
        response.averagePrice,
        response.filledShares,
        response.filledAmountUsd,
        response.feeUsd,
        response.message,
        command.idempotencyKey,
    )


@app.exception_handler(Exception)
async def all_exception_handler(_, exc: Exception) -> JSONResponse:
    return JSONResponse(
        status_code=500,
        content=OrderResponse(
            accepted=False,
            filled=False,
            status="FAILED",
            message=str(exc),
            rawResponse=json.dumps({"error": str(exc)}, sort_keys=True),
        ).model_dump(mode="json"),
    )


def run() -> None:
    uvicorn.run("voktrader_executor.main:app", host="127.0.0.1", port=8099, reload=False)


if __name__ == "__main__":
    run()
