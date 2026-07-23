# Voktrader 2.0 Transformation

This directory contains the transformation plan for reviving Voktrader as a deterministic research and execution platform.

## Contents

- `MASTER_PLAN.md` - phase-by-phase revival plan and promotion gates.
- `original/` - source-preserved text and checksum for the original revival audit that triggered this program.
- `tasks/INDEX.md` - ordered source of truth for implementation work.
- `tasks/PHASE-*.md` - detailed task contracts grouped into phase ledgers.
- `templates/IMPLEMENTATION_REPORT.md` - required report format for each task PR.
- `reports/` - task completion reports added by implementation PRs.

## Operating model

All implementation work should target branch `transformation/2.0` through one task PR at a time unless the task index marks items as safe to parallelize.

The operator should be able to start an agent and say:

```text
pick up next task
```

The agent then uses `AGENTS.md` and the task index to select, claim, implement, test, report, and update the task graph.

## Transformation principles

1. Safety before strategy.
2. Deterministic replay before optimization.
3. Confirmed settlement before PnL truth.
4. Durable order identity before retries.
5. One risk boundary before live capital.
6. Evidence-based promotion only.
