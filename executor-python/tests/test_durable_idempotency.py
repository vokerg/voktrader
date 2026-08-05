from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from voktrader_executor.idempotency import (
    IdempotencyStatus,
    IdempotencyStore,
    IdempotencyUnknownError,
)
from voktrader_executor.models import OrderResponse


def accepted_response() -> OrderResponse:
    return OrderResponse(
        accepted=True,
        filled=False,
        status="SUBMITTED",
        exchangeOrderId="remote-123",
        message="accepted",
    )


def rejected_response() -> OrderResponse:
    return OrderResponse(
        accepted=False,
        filled=False,
        status="REJECTED",
        message="exchange rejected",
    )


def store(path: Path, *, pending_wait_seconds: float = 1.0) -> IdempotencyStore:
    return IdempotencyStore(
        db_path=path,
        pending_wait_seconds=pending_wait_seconds,
        poll_interval_seconds=0.005,
    )


def test_repeated_result_survives_restart(tmp_path: Path) -> None:
    database = tmp_path / "idempotency.sqlite3"
    first_process = store(database)
    response = accepted_response()

    assert first_process.get("client-order-1") is None
    first_process.put("client-order-1", response)

    restarted_process = store(database)

    assert restarted_process.status("client-order-1") is IdempotencyStatus.SUCCEEDED
    assert restarted_process.get("client-order-1").model_dump() == response.model_dump()


def test_concurrent_duplicate_is_submitted_once(tmp_path: Path) -> None:
    database = tmp_path / "idempotency.sqlite3"
    first_replica = store(database)
    second_replica = store(database)
    response = accepted_response()
    barrier = threading.Barrier(2)
    submit_count = 0
    count_lock = threading.Lock()

    def submit(replica: IdempotencyStore) -> OrderResponse:
        nonlocal submit_count
        barrier.wait()
        cached = replica.get("client-order-concurrent")
        if cached is not None:
            return cached

        with count_lock:
            submit_count += 1
        time.sleep(0.05)
        replica.put("client-order-concurrent", response)
        return response

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(submit, (first_replica, second_replica)))

    assert submit_count == 1
    assert [result.model_dump() for result in results] == [
        response.model_dump(),
        response.model_dump(),
    ]


def test_restart_quarantines_orphaned_pending_submission(tmp_path: Path) -> None:
    database = tmp_path / "idempotency.sqlite3"
    crashed_process = store(database)

    assert crashed_process.get("client-order-unknown") is None
    assert crashed_process.status("client-order-unknown") is IdempotencyStatus.PENDING

    restarted_process = store(database)

    assert restarted_process.status("client-order-unknown") is IdempotencyStatus.UNKNOWN
    with pytest.raises(IdempotencyUnknownError, match="reconcile remote truth"):
        restarted_process.get("client-order-unknown")


def test_rejection_and_unknown_are_distinct_durable_states(tmp_path: Path) -> None:
    database = tmp_path / "idempotency.sqlite3"
    idempotency = store(database)

    rejection = rejected_response()
    assert idempotency.get("client-order-rejected") is None
    idempotency.put("client-order-rejected", rejection)

    assert idempotency.status("client-order-rejected") is IdempotencyStatus.REJECTED
    assert idempotency.get("client-order-rejected").model_dump() == rejection.model_dump()

    unknown = OrderResponse(
        accepted=False,
        filled=False,
        status="FAILED",
        message="network outcome unknown",
    )
    assert idempotency.get("client-order-failed") is None
    idempotency.put("client-order-failed", unknown)

    assert idempotency.status("client-order-failed") is IdempotencyStatus.UNKNOWN
    with pytest.raises(IdempotencyUnknownError):
        idempotency.get("client-order-failed")


def test_legacy_ttl_and_capacity_arguments_do_not_evict_durable_ids(tmp_path: Path) -> None:
    database = tmp_path / "idempotency.sqlite3"
    idempotency = IdempotencyStore(
        ttl_seconds=0,
        max_entries=1,
        db_path=database,
        pending_wait_seconds=1.0,
        poll_interval_seconds=0.005,
    )

    for key in ("client-order-a", "client-order-b"):
        assert idempotency.get(key) is None
        idempotency.put(key, accepted_response())

    assert idempotency.status("client-order-a") is IdempotencyStatus.SUCCEEDED
    assert idempotency.status("client-order-b") is IdempotencyStatus.SUCCEEDED
