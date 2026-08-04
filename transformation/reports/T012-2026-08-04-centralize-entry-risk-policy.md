# Implementation Report - T012

## Summary

Reconstruct T012 on the current `transformation/2.0` head, replacing stale PR #9 while preserving the central-entry-risk design and current checkpoint constraints.

## Task

- Task ID: T012
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t012`
- Branch: `task/T012-central-risk-reconstruction`
- PR: pending
- Status at completion: IN_PROGRESS

## Files changed

Pending implementation.

## Design decisions

Pending implementation.

## Tests run

Pending implementation and CI evidence.

## Safety impact

Every new-position BUY must cross the sole typed entry boundary exactly once. SELL and cancellation paths remain outside entry exposure gating.

## Backward compatibility

Pending implementation.

## Remaining risks

Pending implementation and full-CI evidence.

## Follow-up tasks

T016 remains the only task directly unblocked by T012.

## Completion checklist

- [ ] Acceptance criteria met
- [ ] Tests added/updated
- [ ] Tests run and results recorded
- [ ] Task section status updated
- [ ] Task index updated
- [x] No unrelated strategy tuning included
