# Voktrader 2.0 transformation baseline

## Summary

Creates the Voktrader 2.0 transformation program branch content:

- Adds `transformation/MASTER_PLAN.md` with the phase-by-phase revival plan.
- Preserves the original audit content, source metadata, and PDF checksum under `transformation/original/`.
- Adds a detailed task registry under `transformation/tasks/`.
- Adds implementation report templates under `transformation/templates/`.
- Updates root `AGENTS.md` with the agent protocol for `pick up next task` workflows.

## Production code impact

None. This PR intentionally adds planning/governance artifacts only.

## Intended workflow after merge

Future task PRs should target `transformation/2.0`. Agents should read `AGENTS.md`, select the next eligible task, claim it by opening a draft PR, implement narrowly, add a report, and update the task status.

## First tasks after this PR

- T010 - Make live profile capability-only
- T040 - Add market WebSocket heartbeat and gap supervision
- T042 - Replace hard-coded fee assumptions
- T044 - Harden live control-plane security
- T050 - Add CI pipeline and smoke compose
