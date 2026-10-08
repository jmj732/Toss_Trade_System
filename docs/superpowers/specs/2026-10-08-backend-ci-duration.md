# Backend CI duration

## Evidence

PR #80 CI took 11m29s: 1m23s queued, Maven 9m19s, test phase about 9m7s. 175 suites / 1149 tests passed. Logs show 186 migration applications and 32 Spring context starts. SEC mapping tests took 64.603s under the real 500ms provider limiter.

## Change

- Restore Testcontainers PostgreSQL 2.0.5 default fsync=off after withCommand overrode it while raising max_connections. This applies only to disposable test fixtures; production configuration is untouched. Local schema-only timing did not materially improve (45.175s versus 44.684s), so this restoration alone is not claimed as a performance fix.
- CI uses two reusable JVM forks, each with its own existing Postgres singleton, capped at 1024 MiB heap. Tests remain sequential inside each fork because schema tests share/reset their database.
- Preserve all tests and existing Hikari pool cap, migration coverage, SEC pacing/backoff and CI gate. Add a safe suite timing summary from existing Surefire XML reports.

## Validation

A regression first failed on SHOW fsync = on, then passed with off; existing 120-connection capacity checks still pass. Run full clean verify with the exact CI flags and compare suite inventory and results against PR #80. Compare actual GitHub CI wall time before claiming improvement. No dependency, production migration or investment data contract changes.

## Local result

Full verify with exact CI flags passed in 3m00s versus the previous local 5m29s (~45% shorter). The same 175 suite names were present; 1150 tests including the added regression had zero failures/errors/skips. actionlint and embedded summary execution passed. GitHub wall time remains a separate measurement.
