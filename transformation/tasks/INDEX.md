# Voktrader 2.0 Task Index

This index is the ordered source of truth for transformation tasks. Task PRs target `transformation/2.0`.

## Selection rule

Pick the lowest-numbered `READY` task whose dependencies are `DONE`, unless the task is marked `Parallelizable: yes` and no lower task blocks its specific workstream.

## Status vocabulary

- `READY` - eligible when dependencies are complete.
- `BLOCKED` - waiting for dependencies or explicit operator decision.
- `IN_PROGRESS` - claimed by an open task PR.
- `DONE` - acceptance criteria met and report committed.
- `PARTIAL` - useful progress merged but follow-up is required.

## Current next tasks

The next independent tasks are:

1. `T012` - centralize entry risk policy.
2. `T040` - market WebSocket heartbeat and gap supervision. Parallel infrastructure work.
3. `T042` - unified fee model. Parallel infrastructure work.
4. `T044` - control-plane security. Parallel infrastructure work.
5. `T050` - CI pipeline. Parallel infrastructure work.

Central risk work continues with `T012`.

## P0 - Transformation foundation
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T000](./PHASE-0-foundation.md#t000) | DONE | P0 | none | no | Create transformation baseline |

## P1 - Stop the bleeding
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T010](./PHASE-1-stop-the-bleeding.md#t010) | DONE | P0 | T000 | no | Make live profile capability-only |
| [T011](./PHASE-1-stop-the-bleeding.md#t011) | DONE | P0 | T010 | no | Introduce typed entry and exit intent boundary |
| [T012](./PHASE-1-stop-the-bleeding.md#t012) | IN_PROGRESS | P0 | T011 | no | Centralize entry risk policy |
| [T013](./PHASE-1-stop-the-bleeding.md#t013) | BLOCKED | P0 | T012 | no | Define portfolio exposure invariants |
| [T014](./PHASE-1-stop-the-bleeding.md#t014) | BLOCKED | P0 | T012, T013 | no | Prove kill switch covers every live entry route |
| [T015](./PHASE-1-stop-the-bleeding.md#t015) | BLOCKED | P0 | T012 | no | Expose effective risk gate chain |

## P2 - Durable order lifecycle
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T020](./PHASE-2-durable-order-lifecycle.md#t020) | BLOCKED | P0 | T012 | no | Create transactional order outbox schema |
| [T021](./PHASE-2-durable-order-lifecycle.md#t021) | BLOCKED | P0 | T020 | no | Implement outbox worker and claim lease |
| [T022](./PHASE-2-durable-order-lifecycle.md#t022) | BLOCKED | P0 | T021 | no | Make executor idempotency durable |
| [T023](./PHASE-2-durable-order-lifecycle.md#t023) | BLOCKED | P0 | T021, T022 | no | Handle unknown submission outcomes |
| [T024](./PHASE-2-durable-order-lifecycle.md#t024) | BLOCKED | P0 | T021 | no | Remove executor calls from DB transactions |
| [T025](./PHASE-2-durable-order-lifecycle.md#t025) | BLOCKED | P1 | T021, T024 | no | Rebuild cancellation lifecycle |

## P3 - Exchange truth and settlement ledger
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T030](./PHASE-3-exchange-truth.md#t030) | BLOCKED | P0 | T021 | no | Add authenticated user WebSocket consumer |
| [T031](./PHASE-3-exchange-truth.md#t031) | BLOCKED | P0 | T030 | no | Implement provisional settlement state machine |
| [T032](./PHASE-3-exchange-truth.md#t032) | BLOCKED | P0 | T031 | no | Split settled and provisional ledger queries |
| [T033](./PHASE-3-exchange-truth.md#t033) | BLOCKED | P1 | T030, T031 | no | Remove ambiguous fill association |
| [T034](./PHASE-3-exchange-truth.md#t034) | BLOCKED | P0 | T030, T031, T023 | no | Build startup reconciliation preflight |
| [T035](./PHASE-3-exchange-truth.md#t035) | BLOCKED | P1 | T030, T025 | no | Implement exchange dead-man heartbeat |

## P4 - Protocol currency and control-plane hardening
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T040](./PHASE-4-protocol-and-control-plane.md#t040) | READY | P1 | T000 | yes | Add market WebSocket heartbeat and gap supervision |
| [T041](./PHASE-4-protocol-and-control-plane.md#t041) | BLOCKED | P1 | T040 | no | Support dynamic tick metadata |
| [T042](./PHASE-4-protocol-and-control-plane.md#t042) | READY | P1 | T000 | yes | Replace hard-coded fee assumptions |
| [T043](./PHASE-4-protocol-and-control-plane.md#t043) | BLOCKED | P1 | T021 | no | Map matching-engine restart modes |
| [T044](./PHASE-4-protocol-and-control-plane.md#t044) | READY | P1 | T000 | yes | Harden live control plane security |
| [T045](./PHASE-4-protocol-and-control-plane.md#t045) | BLOCKED | P1 | T010, T034, T040, T041, T042, T044 | no | Add live preflight endpoint |
| [T050](./PHASE-4-protocol-and-control-plane.md#t050) | READY | P1 | T000 | yes | Add CI pipeline and smoke compose |
| [T051](./PHASE-4-protocol-and-control-plane.md#t051) | BLOCKED | P2 | T050 | no | Enforce Flyway schema authority |
| [T052](./PHASE-4-protocol-and-control-plane.md#t052) | BLOCKED | P2 | T050 | no | Lock dependency and SDK contracts |

## P5 - Simulation honesty
| Task | Status | Priority | Depends On | Parallelizable | Title |
| --- | --- | --- | --- | --- | --- |
| [T060](./PHASE-5-simulation-honesty.md#t060) | BLOCKED | P0 | T050 | no | Replay exact recorded depth levels |
| [T061](./PHASE-5-simulation-honesty.md#t061) | BLOCKED | P1 | T040, T030 | no | Persist normalized event log |
| [T062](./PHASE-5-simulation-honesty.md#t062) | BLOCKED | P1 | T060, T061, T012, T042, T041 | no | Build live/replay parity tests |
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
3. Add new task sections to the correct phase ledger for newly discovered prerequisites.
4. Do not silently skip P0 safety tasks.
