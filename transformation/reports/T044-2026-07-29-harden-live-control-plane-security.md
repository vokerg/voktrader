# Implementation Report - T044

## Summary

Hardened the live Spring control plane with fail-closed stateless authentication, method-based role authorization, separate mutation confirmation, append-only audit-attempt persistence, local-only default binding, and explicit shutdown of development/admin surfaces.

## Task

- Task ID: T044
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t044`
- Branch: `task/T044-live-control-plane-security`
- PR: #14
- Status at completion: DONE
- Started: 2026-07-29T12:04:56Z
- Completed: 2026-07-29T12:40:23Z

## Files changed

- `pom.xml`: added Spring Security runtime and test starters.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneProperties.java`: defines required live credentials, constant-time comparison, role mapping, minimum length, and uniqueness validation.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneTokenAuthenticationFilter.java`: authenticates stateless bearer credentials for `/api/**`.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneMutationGuardFilter.java`: audit-writes every unsafe API attempt before dispatch and requires a separate confirmation header for authenticated mutations.
- `src/main/java/com/vokerg/voktrader/security/LiveControlPlaneSecurityConfig.java`: applies live-only route and method authorization, disables browser login/session behavior, and denies development/admin routes.
- `src/main/java/com/vokerg/voktrader/security/NonLiveSecurityConfig.java`: preserves existing permissive paper/test/local behavior outside the live profile.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneAuditEventEntity.java`: immutable application-facing audit event model.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneAuditEventRepository.java`: exposes only the append operation to application callers.
- `src/main/java/com/vokerg/voktrader/security/ControlPlaneAuditService.java`: persists mutation attempts in a separate transaction before controller side effects.
- `src/main/resources/db/migration/V20__control_plane_audit_events.sql`: adds the append-only audit table and time index.
- `src/main/resources/application-live.properties`: binds to `127.0.0.1` by default, disables H2/Swagger/OpenAPI/Admin, and requires four no-default live secrets.
- `src/test/java/com/vokerg/voktrader/security/*Test.java`: covers credential validation, audit contents, anonymous rejection, read-only/operator/admin policy, mutation confirmation, and denied admin surfaces.
- `src/test/java/com/vokerg/voktrader/trade/LiveProfileConfigurationTest.java`: verifies live-local binding and disabled surface properties.
- `docs/runbook.md`: documents role credentials, confirmation, local binding, and safe request examples.

## Design decisions

- Authentication is stateless bearer-token authentication. The API does not use browser cookies, form login, HTTP Basic, or server sessions.
- Read-only, operator, and admin are separate credentials. GET/HEAD permit all three roles; POST/PUT/PATCH require operator or admin; DELETE requires admin.
- Unsafe API methods require `X-Voktrader-Confirmation` in addition to the role credential. The confirmation secret must be distinct from all role tokens.
- Missing, short, or duplicate live control-plane secrets fail live startup closed.
- Mutation attempts are persisted before controller dispatch in a `REQUIRES_NEW` transaction. Audit persistence failure therefore prevents the mutation from reaching the controller.
- Audit records contain method, path, principal, authority, remote address, timestamp, and confirmation presence only. Authorization headers and request bodies are deliberately excluded.
- H2 Console, Swagger/OpenAPI, and Spring Boot Admin are disabled by live properties and denied by the live security chain.
- The live server binds to loopback unless an operator deliberately supplies `VOKTRADER_LIVE_BIND_ADDRESS`.
- Non-live profiles retain the repository's existing permissive behavior to avoid changing paper, optimizer, smoke, and local-development workflows.

## Tests run

Final implementation-head Actions run: https://github.com/vokerg/voktrader/actions/runs/30452442748

- `./mvnw -B test`
  - T044 focused tests: 11 passed, 0 failed, 0 errors.
    - `ControlPlanePropertiesTest`: 3 passed.
    - `ControlPlaneAuditServiceTest`: 2 passed.
    - `LiveControlPlaneSecurityTest`: 6 passed.
  - Full repository result: 279 tests, 7 failures, 18 errors, 2 skipped.
  - Remaining failures are pre-existing architecture, runtime-state, and order-lifecycle debt; no T044 test remains red.
- `python -m pytest`: passed.
- Dashboard tests and production build: passed.
- Static repository/default-token checks: passed.
- Gitleaks current-tree scan: passed.
- Java-plus-executor compose smoke: passed.
- Clean PostgreSQL migration verification: ran and failed on the existing duplicate `V2` migrations (`V2__fake_signal_sells.sql` and `V2__trade_execution_model.sql`). T051 already owns Flyway schema authority.

## Safety impact

- No live-capital capability was enabled.
- No strategy selection, threshold, entry, exit, cancellation, reconciliation, settlement, fee, tick, or order-lifecycle behavior changed.
- Live control mutations now require authentication, an authorized role, a separate confirmation value, and a successful audit write before controller dispatch.
- The live server is less exposed by default because it binds to loopback and development/admin surfaces are disabled.

## Backward compatibility

- Paper, optimizer, test, smoke, and other non-live profiles remain permit-all as before.
- Live operators must now provide four distinct secrets of at least 32 characters and attach the appropriate bearer token to every `/api/**` request.
- Existing live dashboard/API clients must add the role authorization header, and mutation clients must also add the confirmation header.
- Remote live binding is still possible but now requires an explicit bind-address override.

## Remaining risks

- Role credentials are long-lived shared secrets rather than per-user identities. Rotation and distribution remain operational responsibilities.
- Audit rows record attempts before dispatch, not final controller outcomes. They prove a control attempt reached the guarded boundary without storing request secrets.
- The repository's existing full Java suite and clean migration gate remain red on debt outside T044 scope. Their failures are retained rather than suppressed.

## Follow-up tasks

- T051 remains the owner of the duplicate-version/Flyway authority debt.
- Existing T012 and durable order-lifecycle tasks remain the owners of the full Java-suite failures.
- No new task or dependency change was required.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
