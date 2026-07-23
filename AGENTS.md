# AGENTS.md - Voktrader 2.0 operating protocol

This repository is being revived through the **Voktrader 2.0 transformation program**. The program source of truth is under `transformation/` on branch `transformation/2.0`.

## Prime directive

Do not optimize strategies, add trading variants, or enable live capital until the current task's safety, replay, and evidence requirements are satisfied. Trading code changes must preserve these invariants:

1. Every BUY/new-position intent crosses one central entry risk boundary.
2. SELL/exit and CANCEL paths remain available for reducing exposure.
3. No remote exchange side effect happens inside a database transaction.
4. Exchange MATCHED/MINED/CONFIRMED/FAILED lifecycle is represented explicitly.
5. Replay and live use the same feature, risk, fee, tick, order, and ledger semantics.
6. Every implementation PR leaves an auditable report under `transformation/reports/`.

## How an agent picks up work

When the operator says **"pick up next task"**, follow this protocol exactly.

### 1. Sync and inspect

- Check out `main` and pull.
- Check out `transformation/2.0` and pull.
- Read, in order:
  - `transformation/README.md`
  - `transformation/MASTER_PLAN.md`
  - `transformation/tasks/INDEX.md`
  - `transformation/templates/IMPLEMENTATION_REPORT.md`
  - the phase ledger containing the highest-priority unclaimed task section.

### 2. Select the next task

A task is eligible only when:

- `Status: READY`
- `Owner: unclaimed`
- all `Depends On` tasks are marked `DONE`
- there is no open PR title containing the task ID, for example `[T020]`
- no task with a lower numeric ID is READY unless the task index explicitly says parallel work is allowed.

If no task is eligible, write a short analysis in chat explaining the blocker and do not modify code.

### 3. Claim the task

Create a branch from `transformation/2.0`:

```bash
git checkout transformation/2.0
git pull
git checkout -b task/TXXX-short-slug
```

Open a draft PR immediately with a title like:

```text
[TXXX] Short task title
```

This PR is the claim lock. After the PR exists, update the claimed task section in its phase ledger on your task branch:

```text
Status: IN_PROGRESS
Owner: <agent/session identifier>
Branch: task/TXXX-short-slug
PR: <link or number>
Started: <UTC timestamp>
```

Do not claim multiple tasks unless the task explicitly authorizes bundling.

### 4. Implement narrowly

- Stay inside the selected task scope.
- If you discover a required prerequisite, add a new task section to the correct phase ledger instead of expanding scope silently.
- If task ordering should change, edit `transformation/tasks/INDEX.md` and explain why in the PR report.
- Keep production changes small and testable.
- Prefer failing tests before production changes.

### 5. Required implementation report

Every task PR must add one report:

```text
transformation/reports/TXXX-YYYY-MM-DD-short-slug.md
```

Use `transformation/templates/IMPLEMENTATION_REPORT.md`.

The report must include:

- Summary
- Files changed
- Design decisions
- Tests run with exact commands and results
- Safety impact
- Remaining risks
- Follow-up tasks created or reordered
- Whether the task is complete, blocked, or partially complete

### 6. Mark completion

Only mark the task `DONE` in its task section when all acceptance criteria pass. Otherwise use `BLOCKED` or leave `IN_PROGRESS` with a precise reason.

For completed tasks, update:

- the task section status
- `transformation/tasks/INDEX.md`
- the implementation report
- any newly discovered dependencies or follow-up task sections

### 7. PR discipline

A task PR is ready for review only when:

- tests pass locally or failures are explicitly justified
- the implementation report exists
- the task section status is updated correctly
- the PR body links the task and report
- no unrelated formatting or strategy tuning is included

Use clear commit messages. Avoid generic messages such as `fix`, `changes`, or `wip`.

## Branch model

- `main`: stable baseline.
- `transformation/2.0`: transformation program branch; contains the master plan and merged task outputs.
- `task/TXXX-short-slug`: one task implementation branch.

Task PRs should target `transformation/2.0`, not `main`, until the 2.0 transformation is promoted.

## When to stop and ask

Stop and ask the operator when:

- live execution might be enabled;
- a secret, account identity, funder, chain, or private key is involved;
- a task requires destructive data migration;
- the fix requires materially changing task order;
- test evidence contradicts the master plan;
- a local/remote order mismatch is discovered in code or fixtures.

## Definition of Done for Voktrader 2.0

Voktrader 2.0 is not done when it has pretty dashboards or higher backtest PnL. It is done when a tiny live rollout can answer, reproducibly:

- why the strategy wanted the trade;
- what independent probability estimate supported it;
- what price and quantity were executable;
- which risk gates passed;
- what the exchange acknowledged, matched, mined, confirmed, failed, or cancelled;
- whether replay reconstructs the same decision from the same event stream;
- whether pessimistic fee, latency, and fill assumptions still support the promotion.
