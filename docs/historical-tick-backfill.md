# Historical tick provenance backfill

Historical replay must use timestamped exchange tick evidence. It must not infer a tick from displayed price precision, observed price spacing, current market metadata, or a default value.

## Coverage model

The `historical_tick_coverage` table records one of two append-only states for a dataset interval:

- `RESOLVED`: a token, positive tick size, evidence source, and source reference are present.
- `BLOCKED`: authoritative evidence is absent and replay must stop with the recorded reason.

Depth snapshots retain token IDs, so the inventory can split an interval into an unresolved prefix and a resolved suffix beginning at the first persisted `tick_size_metadata` observation.

Legacy price snapshots do not retain outcome token IDs. Their market intervals are therefore blocked until timestamped retained evidence supplies the market-to-token mapping and tick for every token used by the replay.

## Accepted evidence

Only these evidence sources may be imported:

- `DOCUMENTED_REST_BOOK`: a retained REST order-book response containing `tick_size`.
- `DOCUMENTED_WEBSOCKET_EVENT`: a retained `tick_size_change` event containing the applicable tick.

A source reference must identify the retained artifact and field, for example a repository/archive path plus a JSON pointer or line reference. Current exchange metadata is not historical evidence.

## JSON Lines format

Set `voktrader.historical-tick.evidence-file` to a UTF-8 JSON Lines file. Blank lines and lines beginning with `#` are ignored. Each evidence line has this shape:

```json
{"datasetType":"PRICE_SNAPSHOT","datasetMarketId":42,"protocolMarketId":"condition-or-market-id","tokenId":"token-up","intervalStartAt":"2026-07-18T10:00:00Z","intervalEndAt":"2026-07-18T10:30:00Z","tickSize":0.001,"evidenceSource":"DOCUMENTED_REST_BOOK","sourceReference":"archive/rest-book-42-up.json#tick_size","observedAt":"2026-07-18T10:00:01Z"}
```

For a binary price-snapshot replay interval, provide independently sourced entries for every outcome token used by the replay.

## Controlled execution

Run this only against a backed-up maintenance database with trading disabled. The runner performs inventory first, then imports the evidence file so later resolved records supersede earlier blockers without deleting audit history.

```bash
export VOKTRADER_HISTORICAL_TICK_EVIDENCE_FILE=/absolute/path/tick-evidence.jsonl
./mvnw spring-boot:run
```

The application fails startup when the file is absent, a line is invalid, evidence conflicts with an existing resolved interval, or an unsupported source is supplied.

## Replay enforcement

TimeMachine-based tick lookups reject active token blockers before reading historical metadata. Dataset-aware replay code must use:

```java
tickSizeService.runWithHistoricalTicks(
        HistoricalTickDatasetType.PRICE_SNAPSHOT,
        marketId,
        tokenIds,
        capturedAt,
        replayAction
);
```

This overload rejects missing dataset coverage, active blockers, and missing timeline metadata. The older token-only overload remains available for replay paths that do not have a retained dataset identity, but it still rejects active token-level blockers and missing metadata.
