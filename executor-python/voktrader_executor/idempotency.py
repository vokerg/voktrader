from __future__ import annotations

import os
import sqlite3
import time
import uuid
from enum import Enum
from pathlib import Path
from threading import local

from .models import OrderResponse


class IdempotencyStatus(str, Enum):
    PENDING = "PENDING"
    SUCCEEDED = "SUCCEEDED"
    REJECTED = "REJECTED"
    UNKNOWN = "UNKNOWN"


class IdempotencyPendingError(RuntimeError):
    """A matching submission is still owned by another caller."""


class IdempotencyUnknownError(RuntimeError):
    """A prior submission may have reached the exchange and must be reconciled."""


class IdempotencyStore:
    """Durable SQLite authority for executor submission identities.

    ``get`` atomically reserves a new key by inserting ``PENDING``. The caller
    that receives ``None`` owns the reservation and must call ``put`` with the
    normalized executor response. Concurrent callers wait for that durable
    result and never receive permission to submit the same key.

    Existing ``ttl_seconds`` and ``max_entries`` arguments are accepted for
    source compatibility with the former in-memory cache. They are deliberately
    ignored: durable order identities are not safe to evict by age or capacity.
    """

    DEFAULT_DB_PATH = "data/executor-idempotency.sqlite3"
    DEFAULT_PENDING_WAIT_SECONDS = 30.0
    DEFAULT_POLL_INTERVAL_SECONDS = 0.025

    def __init__(
        self,
        ttl_seconds: int | None = None,
        max_entries: int | None = None,
        *,
        db_path: str | os.PathLike[str] | None = None,
        pending_wait_seconds: float | None = None,
        poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS,
    ) -> None:
        del ttl_seconds, max_entries
        configured_path = db_path or os.getenv(
            "EXECUTOR_IDEMPOTENCY_DB_PATH",
            self.DEFAULT_DB_PATH,
        )
        self.db_path = str(configured_path)
        self.pending_wait_seconds = (
            pending_wait_seconds
            if pending_wait_seconds is not None
            else float(
                os.getenv(
                    "EXECUTOR_IDEMPOTENCY_PENDING_WAIT_SECONDS",
                    str(self.DEFAULT_PENDING_WAIT_SECONDS),
                )
            )
        )
        self.poll_interval_seconds = poll_interval_seconds
        if self.pending_wait_seconds < 0:
            raise ValueError("pending_wait_seconds must be non-negative")
        if self.poll_interval_seconds <= 0:
            raise ValueError("poll_interval_seconds must be positive")

        path = Path(self.db_path)
        if path.parent != Path("."):
            path.parent.mkdir(parents=True, exist_ok=True)

        self._local = local()
        self._initialize_schema()
        self._recover_orphaned_pending()

    def get(self, key: str) -> OrderResponse | None:
        """Return a prior terminal response or reserve ``key`` for this caller.

        ``None`` means this caller atomically created the only submission
        reservation. An active duplicate waits for the owner to persist its
        result. Unknown outcomes raise instead of permitting a blind retry.
        """

        if not key or not key.strip():
            raise ValueError("idempotency key is required")

        deadline = time.monotonic() + self.pending_wait_seconds
        while True:
            owner_token = str(uuid.uuid4())
            now = time.time_ns()
            with self._connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                row = connection.execute(
                    """
                    SELECT status, response_json
                    FROM executor_idempotency
                    WHERE client_order_id = ?
                    """,
                    (key,),
                ).fetchone()
                if row is None:
                    connection.execute(
                        """
                        INSERT INTO executor_idempotency (
                            client_order_id,
                            status,
                            owner_token,
                            response_json,
                            created_at_ns,
                            updated_at_ns
                        ) VALUES (?, ?, ?, NULL, ?, ?)
                        """,
                        (
                            key,
                            IdempotencyStatus.PENDING.value,
                            owner_token,
                            now,
                            now,
                        ),
                    )
                    connection.commit()
                    self._owned_reservations()[key] = owner_token
                    return None
                connection.commit()

            current_status = IdempotencyStatus(row["status"])
            if current_status in {
                IdempotencyStatus.SUCCEEDED,
                IdempotencyStatus.REJECTED,
            }:
                return OrderResponse.model_validate_json(row["response_json"])
            if current_status is IdempotencyStatus.UNKNOWN:
                raise IdempotencyUnknownError(
                    f"idempotency key {key!r} has an unknown prior submission outcome; "
                    "reconcile remote truth before retrying"
                )

            if time.monotonic() >= deadline:
                raise IdempotencyPendingError(
                    f"idempotency key {key!r} is still pending in another executor request"
                )
            time.sleep(self.poll_interval_seconds)

    def put(self, key: str, response: OrderResponse) -> None:
        """Persist the owner's normalized response without overwriting truth."""

        owner_token = self._owned_reservations().pop(key, None)
        durable_status = self._classify(response)
        response_json = response.model_dump_json()
        now = time.time_ns()

        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if owner_token is not None:
                updated = connection.execute(
                    """
                    UPDATE executor_idempotency
                    SET status = ?,
                        owner_token = NULL,
                        response_json = ?,
                        updated_at_ns = ?
                    WHERE client_order_id = ?
                      AND status = ?
                      AND owner_token = ?
                    """,
                    (
                        durable_status.value,
                        response_json,
                        now,
                        key,
                        IdempotencyStatus.PENDING.value,
                        owner_token,
                    ),
                ).rowcount
                connection.commit()
                if updated == 0:
                    raise IdempotencyUnknownError(
                        f"idempotency reservation for {key!r} is no longer owned by this request"
                    )
                return

            connection.execute(
                """
                INSERT INTO executor_idempotency (
                    client_order_id,
                    status,
                    owner_token,
                    response_json,
                    created_at_ns,
                    updated_at_ns
                ) VALUES (?, ?, NULL, ?, ?, ?)
                ON CONFLICT(client_order_id) DO NOTHING
                """,
                (
                    key,
                    durable_status.value,
                    response_json,
                    now,
                    now,
                ),
            )
            connection.commit()

    def status(self, key: str) -> IdempotencyStatus | None:
        """Return durable internal state for diagnostics and tests."""

        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT status
                FROM executor_idempotency
                WHERE client_order_id = ?
                """,
                (key,),
            ).fetchone()
        return IdempotencyStatus(row["status"]) if row is not None else None

    def _initialize_schema(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS executor_idempotency (
                    client_order_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL
                        CHECK (status IN ('PENDING', 'SUCCEEDED', 'REJECTED', 'UNKNOWN')),
                    owner_token TEXT,
                    response_json TEXT,
                    created_at_ns INTEGER NOT NULL,
                    updated_at_ns INTEGER NOT NULL,
                    CHECK (
                        (
                            status = 'PENDING'
                            AND owner_token IS NOT NULL
                            AND response_json IS NULL
                        )
                        OR (
                            status IN ('SUCCEEDED', 'REJECTED')
                            AND owner_token IS NULL
                            AND response_json IS NOT NULL
                        )
                        OR (
                            status = 'UNKNOWN'
                            AND owner_token IS NULL
                        )
                    )
                );

                CREATE INDEX IF NOT EXISTS idx_executor_idempotency_status_updated
                    ON executor_idempotency (status, updated_at_ns);
                """
            )

    def _recover_orphaned_pending(self) -> None:
        now = time.time_ns()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                UPDATE executor_idempotency
                SET status = ?,
                    owner_token = NULL,
                    updated_at_ns = ?
                WHERE status = ?
                """,
                (
                    IdempotencyStatus.UNKNOWN.value,
                    now,
                    IdempotencyStatus.PENDING.value,
                ),
            )
            connection.commit()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(
            self.db_path,
            timeout=5.0,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 5000")
        connection.execute("PRAGMA synchronous = FULL")
        return connection

    def _owned_reservations(self) -> dict[str, str]:
        reservations = getattr(self._local, "reservations", None)
        if reservations is None:
            reservations = {}
            self._local.reservations = reservations
        return reservations

    @staticmethod
    def _classify(response: OrderResponse) -> IdempotencyStatus:
        if response.accepted:
            return IdempotencyStatus.SUCCEEDED
        if response.status.upper() == "REJECTED":
            return IdempotencyStatus.REJECTED
        return IdempotencyStatus.UNKNOWN
