# Phase 0 Foundation

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T000 - Create transformation baseline

Status: READY
Priority: P0
Phase: P0 - Transformation foundation
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: none
Parallelizable: no

## Objective

Establish the transformation material, preserve the original audit, and make the repository self-describing for follow-on agents.

## Context

This task is part of the Voktrader 2.0 transformation. Read `transformation/MASTER_PLAN.md` and `AGENTS.md` before implementation. Keep scope narrow and update this task section plus `transformation/tasks/INDEX.md` in the task PR.

## Implementation steps

1. Add the transformation directory and source-preserved original audit.
2. Add master plan, task index, phase task ledgers, report template, and root AGENTS protocol.
3. Open the transformation PR against main.

## Acceptance criteria

- [ ] A fresh agent can read AGENTS.md and identify the next task.
- [ ] The original audit content and source checksum are present under transformation/original.
- [ ] The PR explains that no production code was changed.

## Required tests

- Validate all task links and task IDs.
- Confirm branch and PR base/head are correct.
- Record exact checks and results in `transformation/reports/T000-YYYY-MM-DD-create-transformation-baseline.md`.

## Safety notes

- Do not enable real live trading while completing this task.
- Do not tune strategy thresholds.
- Do not modify production code in the baseline PR.

## Completion update protocol

When the PR is complete:

1. Change `Status` to `DONE`, `BLOCKED`, or `PARTIAL`.
2. Fill `Branch`, `PR`, `Started`, and `Completed`.
3. Update `transformation/tasks/INDEX.md`.
4. Add the required implementation report.
5. Add or reorder follow-up tasks if new information changes the plan.
