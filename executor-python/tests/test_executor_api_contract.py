from pathlib import Path

from voktrader_executor.models import (
    CancelOrderResponse,
    ExecutorCapabilities,
    ExecutorError,
    FillResponse,
    FillsResponse,
    OpenOrdersResponse,
    OrderResponse,
    OrderStatusResponse,
    OrderVariation,
)

CONTRACT_PATH = Path(__file__).resolve().parents[2] / "contracts" / "executor-api-v1.properties"

MODELS = {
    "OrderResponse": OrderResponse,
    "OrderStatusResponse": OrderStatusResponse,
    "CancelOrderResponse": CancelOrderResponse,
    "OpenOrdersResponse": OpenOrdersResponse,
    "FillResponse": FillResponse,
    "FillsResponse": FillsResponse,
    "ExecutorError": ExecutorError,
    "OrderVariation": OrderVariation,
    "ExecutorCapabilities": ExecutorCapabilities,
}


def load_contract() -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in CONTRACT_PATH.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def csv_set(value: str) -> set[str]:
    return {item for item in value.split(",") if item}


def test_pydantic_response_shapes_match_shared_executor_contract() -> None:
    contract = load_contract()
    assert contract["contractVersion"] == "executor-api-v1"

    for model_name, model in MODELS.items():
        schema = model.model_json_schema()
        actual_fields = set(schema.get("properties", {}))
        actual_required = set(schema.get("required", []))

        assert actual_fields == csv_set(contract[f"{model_name}.fields"]), model_name
        assert actual_required == csv_set(contract[f"{model_name}.required"]), model_name
