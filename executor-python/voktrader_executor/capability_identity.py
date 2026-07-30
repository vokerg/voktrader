from __future__ import annotations

from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, version

PROTOCOL_VERSION = "executor-api-v1"
EXECUTOR_DISTRIBUTION = "voktrader-executor"
SDK_DISTRIBUTION = "py-clob-client-v2"


@dataclass(frozen=True)
class CapabilityIdentity:
    available: bool
    protocol_version: str
    executor_version: str
    sdk_package: str
    sdk_version: str
    error_message: str | None


def _installed_version(distribution: str) -> str | None:
    try:
        return version(distribution)
    except PackageNotFoundError:
        return None


def resolve_capability_identity() -> CapabilityIdentity:
    executor_version = _installed_version(EXECUTOR_DISTRIBUTION)
    sdk_version = _installed_version(SDK_DISTRIBUTION)
    missing = [
        distribution
        for distribution, installed in (
            (EXECUTOR_DISTRIBUTION, executor_version),
            (SDK_DISTRIBUTION, sdk_version),
        )
        if installed is None
    ]
    error_message = None
    if missing:
        error_message = "installed package metadata unavailable: " + ", ".join(missing)

    return CapabilityIdentity(
        available=not missing,
        protocol_version=PROTOCOL_VERSION,
        executor_version=executor_version or "UNKNOWN",
        sdk_package=SDK_DISTRIBUTION,
        sdk_version=sdk_version or "UNKNOWN",
        error_message=error_message,
    )


CAPABILITY_IDENTITY = resolve_capability_identity()
