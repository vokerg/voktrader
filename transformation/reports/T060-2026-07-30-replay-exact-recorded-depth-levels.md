# Implementation Report - T060

## Summary

Implementation is in progress. This task replaces synthetic replay order books with exact persisted depth levels, validates snapshot integrity, and reports incomplete or corrupt replay coverage explicitly.

## Task

- Task ID: T060
- Task section: `transformation/tasks/PHASE-5-simulation-honesty.md#t060`
- Branch: `task/T060-replay-exact-depth-levels`
- PR: pending
- Status at completion: IN_PROGRESS

## Files changed

- Pending inventory and implementation.

## Design decisions

- Persisted level rows are authoritative for replay depth.
- Replay must preserve side ordering and `levelIndex`; it must not synthesize missing levels.
- Summary prices and depth must be derived from and checked against exact levels.
- Missing, duplicate, discontinuous, or invalid levels are replay blockers rather than recoverable defaults.
- T053 historical tick coverage will be enforced when exact depth snapshots are replayed.

## Tests run

Pending implementation.

## Safety impact

This task changes historical replay construction and validation only. It does not enable live capital or change live strategy thresholds, risk, routing, cancellation, reconciliation, settlement, tick, or fee semantics.

## Backward compatibility

Pending inventory.

## Remaining risks

Pending inventory of replay/backtest entry points and feature construction.

## Follow-up tasks

None identified yet.

## Completion checklist

- [ ] Acceptance criteria met
- [ ] Tests added/updated
- [ ] Tests run and results recorded
- [ ] Task section status updated
- [ ] Task index updated
- [x] No unrelated strategy tuning included
