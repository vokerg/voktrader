# Voktrader 2.0 Task Index

This index is the ordered source of truth for transformation tasks. Task PRs target `transformation/2.0`.

## Selection rule

Pick the lowest-numbered `READY` task whose dependencies are `DONE`, unless the task is marked `Parallelizable: yes` and no lower task blocks its specific workstream. During the 2026-08-03 checkpoint recovery, the explicit sequence below overrides stale branch-local task claims.

## Status vocabulary

- `READY` - eligible when dependencies are complete.
- `BLOCKED` - waiting for dependencies or explicit operator decision.
- `IN_PROGRESS` - claimed by an open task PR and actively eligible to proceed.
- `DONE` - acceptance criteria met and report committed.
- `PARTIAL` - useful progress merged but follow-up is required.

## Current checkpoint sequence

1. `T012` - centralize entry risk policy. **DONE** in merged PR #26; the integrated exact Java failure baseline shrank from 25 to 24 identities.
2. `T055` - enforce CI no-regression policy. **PARTIAL** in merged PR #25; repository-controlled enforcement is implemented, but required branch rules still need administrator application and verification.
3. `T016` - integrated execution-boundary checkpoint. **DONE** in PR #27; all 24 remaining failure identities are assigned to T020-T025.
4. `T013` - portfolio exposure invariants. **READY** and the next sequential task under the lowest-ID rule.
5. `T015` and `T020` are also **READY**, but wait behind lower-numbered T013 unless the index explicitly authorizes parallel work. `T014` follows T013.
6. `T042` - unified fee model. Claimed by draft PR #12 and promoted to P0, but remains paused pending current-head reconciliation; do not absorb it into another task.
7. `T020` through `T025` execute as the durable lifecycle chain, followed by mandatory phase-exit gate `T026`.
8. `T030` and all exchange-truth work remain blocked until T026.
9. After T055 is DONE, `T054` retained-database rehearsal and `T056` control-plane default-auth proof may proceed in parallel.
10. `T045` live preflight additionally requires T054 and T056.

Unrelated feature work is frozen while the integrated Java failure baseline is unchanged or worsening. See [`CHECKPOINT-2026-08-03.md`](./CHECKPOINT-2026-08-03.md).

## P0 - Transformation foundation
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T000](./PHASE-0-foundation.md#t000) | DONE | P0 | none | no | Create transformation baseline |

## P1 - Stop the bleeding
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T010](./PHASE-1-stop-the-bleeding.md#t010) | DONE | P0 | T000 | no | Make live profile capability-only |
| [T011](./PHASE-1-stop-the-bleeding.md#t011) | DONE | P0 | T010 | no | Introduce typed entry and exit intent boundary |
| [T012](./PHASE-1-stop-the-bleeding.md#t012) | DONE | P0 | T011 | no | Centralize entry risk policy |
| [T016](./CHECKPOINT-2026-08-03.md#t016---stabilize-integrated-execution-boundary-after-central-risk-merge) | DONE | P0 | T012 | no | Stabilize integrated execution boundary after central-risk merge |
| [T013](./PHASE-1-stop-the-bleeding.md#t013) | READY | P0 | T016 | no | Define portfolio exposure invariants |
| [T014](./PHASE-1-stop-the-bleeding.md#t014) | BLOCKED | P0 | T016, T013 | no | Prove kill switch covers every live entry route |
| [T015](./PHASE-1-stop-the-bleeding.md#t015) | READY | P0 | T016 | no | Expose effective risk gate chain |

## P2 - Durable order lifecycle
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T020](./PHASE-2-durable-order-lifecycle.md#t020) | READY | P0 | T016 | no | Create transactional order outbox schema |
| [T021](./PHASE-2-durable-order-lifecycle.md#t021) | BLOCKED | P0 | T020 | no | Implement outbox worker and claim lease |
| [T022](./PHASE-2-durable-order-lifecycle.md#t022) | BLOCKED | P0 | T021 | no | Make executor idempotency durable |
| [T023](./PHASE-2-durable-order-lifecycle.md#t023) | BLOCKED | P0 | T021, T022 | no | Handle unknown submission outcomes |
| [T024](./PHASE-2-durable-order-lifecycle.md#t024) | BLOCKED | P0 | T021 | no | Remove executor calls from DB transactions |
| [T025](./PHASE-2-durable-order-lifecycle.md#t025) | BLOCKED | P1 | T021, T024 | no | Rebuild cancellation lifecycle |
| [T026](./CHECKPOINT-2026-08-03.md#t026---close-durable-order-lifecycle-integration-failures) | BLOCKED | P0 | T020, T021, T022, T023, T024, T025 | no | Close durable order-lifecycle integration failures |

## P3 - Exchange truth and settlement ledger
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T030](./PHASE-3-exchange-truth.md#t030) | BLOCKED | P0 | T026 | no | Add authenticated user WebSocket consumer |
| [T031](./PHASE-3-exchange-truth.md#t031) | BLOCKED | P0 | T030 | no | Implement provisional settlement state machine |
| [T032](./PHASE-3-exchange-truth.md#t032) | BLOCKED | P0 | T031 | no | Split settled and provisional ledger queries |
| [T033](./PHASE-3-exchange-truth.md#t033) | BLOCKED | P1 | T030, T031 | no | Remove ambiguous fill association |
| [T034](./PHASE-3-exchange-truth.md#t034) | BLOCKED | P0 | T030, T031, T023 | no | Build startup reconciliation preflight |
| [T035](./PHASE-3-exchange-truth.md#t035) | BLOCKED | P1 | T030, T025 | no | Implement exchange dead-man heartbeat |

## P4 - Protocol currency and control-plane hardening
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T040](./PHASE-4-protocol-and-control-plane.md#t040) | DONE | P1 | T000 | yes | Add market WebSocket heartbeat and gap supervision |
| [T041](./PHASE-4-protocol-and-control-plane.md#t041) | DONE | P1 | T040 | no | Support dynamic tick metadata |
| [T042](./PHASE-4-protocol-and-control-plane.md#t042) | BLOCKED | P0 | T016 | no | Replace hard-coded fee assumptions |
| [T043](./PHASE-4-protocol-and-control-plane.md#t043) | BLOCKED | P1 | T021 | no | Map matching-engine restart modes |
| [T044](./PHASE-4-protocol-and-control-plane.md#t044) | DONE | P1 | T000 | yes | Harden live control plane security |
| [T045](./PHASE-4-protocol-and-control-plane.md#t045) | BLOCKED | P1 | T010, T034, T040, T041, T042, T044, T054, T056 | no | Add live preflight endpoint |
| [T050](./PHASE-4-protocol-and-control-plane.md#t050) | DONE | P1 | T000 | yes | Add CI pipeline and smoke compose |
| [T051](./PHASE-4-protocol-and-control-plane.md#t051) | DONE | P2 | T050 | no | Enforce Flyway schema authority |
| [T052](./PHASE-4-protocol-and-control-plane.md#t052) | DONE | P2 | T050 | no | Lock dependency and SDK contracts |
| [T053](./PHASE-4-protocol-and-control-plane.md#t053) | DONE | P2 | T041 | yes | Backfill historical tick provenance |
| [T054](./CHECKPOINT-2026-08-03.md#t054---rehearse-retained-database-flyway-upgrade) | BLOCKED | P0 | T051, T055 | yes | Rehearse retained-database Flyway upgrade |
| [T055](./CHECKPOINT-2026-08-03.md#t055---enforce-transformation-ci-no-regression-policy) | PARTIAL | P0 | T050 | yes | Enforce transformation CI no-regression policy |
| [T056](./CHECKPOINT-2026-08-03.md#t056---prove-generated-spring-credentials-cannot-access-live-control-plane) | BLOCKED | P1 | T044, T055 | yes | Prove generated Spring credentials cannot access live control plane |

## P5 - Simulation honesty
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T060](./PHASE-5-simulation-honesty.md#t060) | DONE | P0 | T050 | no | Replay exact recorded depth levels |
| [T061](./PHASE-5-simulation-honesty.md#t061) | BLOCKED | P1 | T040, T030 | no | Persist normalized event log |
| [T062](./PHASE-5-simulation-honesty.md#t062) | BLOCKED | P1 | T060, T061, T016, T042, T041 | no | Build live/replay parity tests and reset comparable baselines |
| [T063](./PHASE-5-simulation-honesty.md#t063) | BLOCKED | P1 | T060, T031 | no | Implement calibrated execution simulator |
| [T064](./PHASE-5-simulation-honesty.md#t064) | BLOCKED | P2 | T051, T061 | no | Separate operational and analytical stores |

## P6 - Fair-value layer and strategy simplification
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T070](./PHASE-6-fair-value-and-strategy.md#t070) | BLOCKED | P1 | T061 | no | Capture underlying venue composite inputs |
| [T071](./PHASE-6-fair-value-and-strategy.md#t071) | BLOCKED | P1 | T061 | no | Persist contract reference and settlement metadata |
| [T072](./PHASE-6-fair-value-and-strategy.md#t072) | BLOCKED | P1 | T070, T071 | no | Implement baseline q probability model |
| [T073](./PHASE-6-fair-value-and-strategy.md#t073) | BLOCKED | P1 | T072 | no | Add calibration and forecast diagnostics |
| [T074](./PHASE-6-fair-value-and-strategy.md#t074) | BLOCKED | P1 | T072, T063, T042 | no | Implement executable EV gate |
| [T080](./PHASE-6-fair-value-and-strategy.md#t080) | BLOCKED | P2 | T074 | no | Simplify Strategy V2 active set |
| [T081](./PHASE-6-fair-value-and-strategy.md#t081) | BLOCKED | P2 | T080, T062 | no | Retire legacy hard-coded strategies |
| [T082](./PHASE-6-fair-value-and-strategy.md#t082) | BLOCKED | P2 | T064, T073 | no | Create experiment registry |

## P7 - Robustness, shadow, and paper
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T090](./PHASE-7-robustness-shadow-paper.md#t090) | BLOCKED | P1 | T074, T082 | no | Implement shadow execution mode |
| [T091](./PHASE-7-robustness-shadow-paper.md#t091) | BLOCKED | P1 | T023, T031, T034, T062 | no | Add chaos and restart suite |
| [T092](./PHASE-7-robustness-shadow-paper.md#t092) | BLOCKED | P1 | T090, T091 | no | Run paper promotion trial |
| [T093](./PHASE-7-robustness-shadow-paper.md#t093) | BLOCKED | P2 | T015, T045, T082 | no | Build operator evidence dashboards |

## P8 - Tiny live promotion
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T100](./PHASE-8-tiny-live-promotion.md#t100) | BLOCKED | P0 | T092, T093 | no | Prepare tiny live runbook |
| [T101](./PHASE-8-tiny-live-promotion.md#t101) | BLOCKED | P0 | T100 | no | Execute tiny live promotion |
| [T102](./PHASE-8-tiny-live-promotion.md#t102) | BLOCKED | P1 | T101 | no | Define expansion criteria |

## Reordering protocol

A task PR may reorder tasks only if its implementation report explains why. When reordering:

1. Update this index.
2. Update affected task `Depends On` fields.
3. Add new task sections to the correct phase ledger or checkpoint ledger for newly discovered prerequisites.
4. Do not silently skip P0 safety tasks.
