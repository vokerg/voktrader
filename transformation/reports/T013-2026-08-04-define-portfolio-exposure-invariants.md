# Implementation Report - T013

## Summary

Define portfolio exposure independently of Strategy V2 inner-strategy ownership and enforce the configured duplicate-position and active-exposure semantics at the central entry-risk boundary.

## Task

- Task ID: T013
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t013`
- Branch: `task/T013-portfolio-exposure-invariants`
- PR: pending
- Status at completion: IN_PROGRESS

## Files changed

Pending implementation.

## Design decisions

Pending implementation.

## Tests run

Pending implementation and CI evidence.

## Safety impact

New-position exposure must be evaluated at portfolio scope rather than only by Strategy V2 inner-strategy ownership. Exit and cancellation behavior remain unchanged.

## Backward compatibility

Pending implementation.

## Remaining risks

Pending implementation and full-CI evidence.

## Follow-up tasks

T014 remains blocked until this task is complete.

## Completion checklist

- [ ] Acceptance criteria met
- [ ] Tests added/updated
- [ ] Tests run and results recorded
- [ ] Task section status updated
- [ ] Task index updated
- [x] No unrelated strategy tuning included
