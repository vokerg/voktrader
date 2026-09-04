from fastapi.testclient import TestClient

from voktrader_executor.capability_identity import CAPABILITY_IDENTITY
from voktrader_executor.main import app, settings


def test_capabilities_report_installed_contract_executor_and_sdk_versions() -> None:
    response = TestClient(app).get(
        "/v1/capabilities",
        headers={"Authorization": f"Bearer {settings.executor_api_token}"},
    )

    assert response.status_code == 200
    body = response.json()
    assert CAPABILITY_IDENTITY.available is True
    assert body["success"] is True
    assert body["protocolVersion"] == "executor-api-v1"
    assert body["executorVersion"] == "0.3.0"
    assert body["sdkPackage"] == "py-clob-client-v2"
    assert body["sdkVersion"] == "1.1.0"
    assert body["supportedTimeInForce"] == ["FOK", "FAK", "GTC", "GTD"]
    assert body["error"] is None
