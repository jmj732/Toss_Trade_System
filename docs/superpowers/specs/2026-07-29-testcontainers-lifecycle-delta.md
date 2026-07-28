# Testcontainers Lifecycle Delta

## Background

`design/modular-monolith-architecture`'s CI (Release Gates → Spring backend) failed 5/5
consecutive times after the risk-policy-management delta merged, always identically:
`ScheduledPortfolioRefreshIntegrationTest` (a pre-existing test, untouched by that delta)
failed its own `@SpringBootTest` context bootstrap with `FATAL: sorry, too many clients
already` from Postgres, while trying to open its Flyway/JPA connection pool.

## Root cause (verified empirically, revised once during review)

Initial hypothesis was "container accumulation" — disproved by running three different
`@SpringBootTest` classes together and watching `docker ps`: exactly **one** Postgres
container. `PostgresIntegrationTest.POSTGRES` is a static field on the shared abstract base
class; per the JLS a class initializes at most once per classloader, so every subclass in
the same Surefire fork already reuses the one running container. That part was never broken.

The actual cause, found by measuring rather than assuming: this suite has 30 files using
`@SpringBootTest`, collapsing to **~18 distinct `ApplicationContext` cache keys** (11 files
happen to share one identical key). **25 of the 30 set no Hikari pool-size override**, so
they get Hikari's own default — `maximum-pool-size=10`, `minimum-idle` defaulting to the
same — against the one shared Postgres container. Measured peak: **~150 simultaneous
connections**, well past Postgres's own default `max_connections=100`. Five classes already
cap this per-class (`spring.datasource.hikari.maximum-pool-size=4`, grep for that string
under `src/test`) — the pattern existed, it just wasn't the default.

(An earlier version of this fix also shrank Spring's test context-cache size, on the theory
that fewer simultaneously-cached contexts would bound peak connections. Measurement during
review showed this was true but not load-bearing at any value that wouldn't also cost
context-rebuild time: 18 keys never filled the stock 32-slot cache in the first place, so
raising or lowering it doesn't change how many contexts are live at once for *this* suite.
Dropped in favor of the fix below, which addresses the actual per-context pool size instead.)

Confirmed directly: reverting the `max_connections` fix and running the reproduction test
(`PostgresContainerCapacityTest`) fails with the exact same `FATAL: sorry, too many clients
already` error seen in CI; restoring it makes it pass.

## Fix

- **`pom.xml`**: `maven-surefire-plugin` sets
  `spring.datasource.hikari.maximum-pool-size=2` / `minimum-idle=0` as system properties —
  a suite-wide default every `@SpringBootTest` picks up unless it already overrides the
  property itself (a `@SpringBootTest(properties = ...)` override always wins over a system
  property, so the five existing per-class overrides are unaffected). This generalizes the
  pattern those five classes already used, rather than leaving everyone else on Hikari's
  unconstrained default. Baseline peak drops from ~150 to roughly 18 × 2 ≈ 36.
- One test (`NotificationOutboxProcessorIntegrationTest`) runs 4 concurrent threads against
  its own context and had no prior pool-size override; gave it an explicit
  `maximum-pool-size=4` so its own concurrency isn't constrained by the new suite-wide
  default of 2. Audited every other concurrent (`ExecutorService`/`CountDownLatch`) test in
  the suite — all use 2 threads (fits the new default exactly) or don't touch Postgres at
  all (e.g. the Redis-only token-manager test).
- **`PostgresIntegrationTest`**: the shared container starts with `-c max_connections=300` —
  headroom on top of the ~36 baseline for whatever else is transiently open (verified via
  `docker run ... postgres -c max_connections=300` that the flag is honored by the
  `postgres:17-alpine` image before relying on it). Not load-bearing for the baseline itself,
  which now sits well under even Postgres's stock 100.
- **`PostgresContainerCapacityTest`** (new): opens 120 raw JDBC connections directly against
  the shared container (comfortably over Postgres's default 100) and confirms via
  `pg_stat_activity` that they're all real, server-visible backends — plus a smaller
  assertion that `max_connections` reports > 100. Doesn't depend on how many other Spring
  contexts happen to be cached at the moment it runs, so it fails deterministically without
  the fix and passes deterministically with it (verified both directions locally). Connection
  cleanup in `finally` is per-connection best-effort (`catch SQLException` around each
  `close()`) so a single failing close can't abort the loop and leak the rest.

## What this delta does not touch

- No change to any `risk-policy-management` code — that delta's commit stands as merged.
- No change to per-class test isolation: each test class still gets its own
  `@BeforeEach`/`TRUNCATE`-based data cleanup exactly as before; only the shared container's
  connection ceiling and the suite-wide default pool size changed, not what each test
  asserts or how it seeds data.
- No new Testcontainers dependency or version bump.

## Plan

- [x] Root-cause investigation (empirical, not assumed) — confirmed via local `docker ps`
      during a multi-class run, and via `docker run` verifying the `max_connections` flag.
- [x] Fix: suite-wide default Hikari pool cap (the load-bearing change) + raised
      `max_connections` (headroom) + one per-class override for the suite's only >2-thread
      concurrency test.
- [x] Reproduction test (`PostgresContainerCapacityTest`), verified to fail without the fix
      and pass with it, with proper per-connection cleanup.
- [x] One code review pass; findings applied (connection-leak-on-close fix, replaced the
      inert context-cache-size change with the measured root-cause fix, corrected the
      cache-key count in this doc, fixed a tautological assertion, named magic numbers).
- [x] Full local suite green (`mvn clean verify`, ~370 tests, real Postgres).
- [ ] Run full verification (dashboard, analysis — unaffected, backend-only delta —
      local-stack scripts, existing drills).
- [ ] One feature commit, squash merge into base branch, push, confirm CI green — watched
      across more than one run given the prior 5/5 failure streak.
