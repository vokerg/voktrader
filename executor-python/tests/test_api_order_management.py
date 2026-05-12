import os

from fastapi.testclient import TestClient

os.environ["EXECUTOR_DRY_RUN"] = "true"

from voktrader_executor.main import app


client = TestClient(app)
headers = {"Authorization": "Bearer change-me"}


def test_cancel_endpoint_returns_structured_dry_run_response():
    response = client.post("/v1/orders/remote-1/cancel", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["remoteOrderId"] == "remote-1"
    assert body["status"] == "DRY_RUN_CANCELLED"
    assert body["error"] is None


def test_get_order_status_endpoint_returns_order_shape():
    response = client.get("/v1/orders/remote-1", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["remoteOrderId"] == "remote-1"
    assert body["status"] == "DRY_RUN_UNKNOWN"
    assert "filledSize" in body
    assert "remainingSize" in body


def test_open_orders_endpoint_is_not_captured_by_order_id_route():
    response = client.get("/v1/orders/open", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["orders"] == []


def test_fills_endpoint_returns_list_shape():
    response = client.get("/v1/fills?order_id=remote-1", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["fills"] == []
