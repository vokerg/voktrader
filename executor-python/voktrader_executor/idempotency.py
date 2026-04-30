from __future__ import annotations

from collections import OrderedDict
from threading import Lock
from time import monotonic

from .models import OrderResponse


class IdempotencyStore:
    """Small in-memory idempotency cache.

    This is intentionally simple. If you run multiple sidecar replicas or need restarts to
    preserve idempotency, back this with SQLite/Redis before enabling live size.
    """

    def __init__(self, ttl_seconds: int = 60 * 60, max_entries: int = 10_000) -> None:
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._lock = Lock()
        self._data: OrderedDict[str, tuple[float, OrderResponse]] = OrderedDict()

    def get(self, key: str) -> OrderResponse | None:
        with self._lock:
            self._prune()
            item = self._data.get(key)
            if item is None:
                return None
            created, response = item
            self._data.move_to_end(key)
            if monotonic() - created > self.ttl_seconds:
                self._data.pop(key, None)
                return None
            return response

    def put(self, key: str, response: OrderResponse) -> None:
        with self._lock:
            self._data[key] = (monotonic(), response)
            self._data.move_to_end(key)
            self._prune()

    def _prune(self) -> None:
        now = monotonic()
        expired = [key for key, (created, _) in self._data.items() if now - created > self.ttl_seconds]
        for key in expired:
            self._data.pop(key, None)
        while len(self._data) > self.max_entries:
            self._data.popitem(last=False)
