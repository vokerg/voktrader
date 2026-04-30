from __future__ import annotations

import json

import uvicorn
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .config import Settings
from .idempotency import IdempotencyStore
from .models import OrderCommand, OrderResponse
from .polymarket_client import PolymarketExecutor

settings = Settings()
app = FastAPI(title="voktrader executor", version="0.3.0")
idempotency_store = IdempotencyStore()
executor = PolymarketExecutor(settings)


async def require_auth(authorization: str | None = Header(default=None)) -> None:
    expected = f"Bearer {settings.executor_api_token}"
    if authorization != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid bearer token")


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "ok": True,
        "dryRun": settings.executor_dry_run,
        "host": settings.polymarket_host,
    }


@app.post("/v1/orders", response_model=OrderResponse)
def create_order(command: OrderCommand, _: None = Depends(require_auth)) -> OrderResponse:
    cached = idempotency_store.get(command.idempotencyKey)
    if cached is not None:
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
    except Exception as exc:  # noqa: BLE001 - return a structured failure to the JVM caller.
        response = OrderResponse(
            accepted=False,
            filled=False,
            status="FAILED",
            message=str(exc),
            rawResponse=json.dumps({"error": str(exc)}, sort_keys=True),
        )

    idempotency_store.put(command.idempotencyKey, response)
    return response


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
