# Backup Restore Drill Delta

## Scope

- PostgreSQL only. `redis-data` is never backed up or restored — Redis holds only
  cache/session/OAuth-token-cache state (per system-architecture-overview), acceptable to lose
  entirely on restore.
- Backup contains only `pg_dump` of the `trade` database. It never includes `.env`,
  `CREDENTIAL_KEY_BASE64`, `POSTGRES_PASSWORD`, or any other secret — those live outside
  Postgres by design already (`broker_connections.credential_ciphertext` is application-layer
  encrypted before insertion, so the dump only ever contains ciphertext, never the key that
  decrypts it).
- Restore re-validates Flyway by letting the real Spring Boot backend boot against the restored
  database: Flyway's own startup validate-then-noop path is the re-validation, not a
  hand-rolled checksum check.
- One automated drill script proves the whole cycle end-to-end (backup → wipe → restore →
  Flyway re-validate → data round-trip → no-secret-leak check), matching the existing
  `scripts/test-local-stack.sh` / `scripts/smoke-local-stack.sh` conventions (isolated project
  name, random local secrets, trap-based cleanup and failure log dump).
- One ops runbook documents the manual procedure for a real incident.
- No backup encryption, no S3/offsite storage, no retention policy, no scheduler — out of
  scope; `DESIGN.md` §15.2 already tracks "backups should also be encrypted" as a later
  production-hardening item, not this delta.

## Minimal design

- `scripts/backup-postgres.sh <output-file>`: `docker compose exec -T postgres pg_dump -U
  trade -Fc -d trade` to the given path. Reads `COMPOSE_PROJECT` env var (optional) to target
  an isolated stack; otherwise uses the default `trade-local` project.
- `scripts/restore-postgres.sh <input-file>`: `docker compose exec -T postgres pg_restore -U
  trade -d trade --clean --if-exists` from the given path. Same `COMPOSE_PROJECT` convention.
- `scripts/backup-restore-drill.sh`: end-to-end drill.
  1. Isolated project + random `POSTGRES_PASSWORD` (matching `smoke-local-stack.sh`).
  2. Start `postgres redis analysis backend` from base `compose.yaml` only (no mock/dev
     overlay — `BROKER_CREDENTIALS_ENABLED=false` by default, so OIDC/Toss are irrelevant to
     this drill).
  3. Insert one canary row into `users`.
  4. Back up, then assert the dump file does not contain the literal `POSTGRES_PASSWORD` or
     `CREDENTIAL_KEY_BASE64` values (defense-in-depth proof, not just an assumption).
  5. `docker compose down -v` (destroys both volumes — Postgres and Redis).
  6. Start Postgres alone (fresh, empty), restore into it.
  7. Start `redis analysis backend` — backend's own Flyway-on-boot is the re-validation gate;
     `--wait` fails the script if Spring Boot can't reach readiness.
  8. Confirm the canary row survived the cycle.
  9. `docker compose down -v` cleanup via trap, log dump on failure (same pattern as existing
     scripts).
- `docs/ops/postgres-backup-restore.md`: the manual runbook.
- `backups/` added to `.gitignore` (dump files must never be committed).

## Plan

- [ ] Write `backup-postgres.sh` / `restore-postgres.sh`.
- [ ] Write `backup-restore-drill.sh` using them.
- [ ] Run the drill locally, confirm PASS.
- [ ] Write the ops runbook.
- [ ] Perform exactly one review pass and fix findings.
- [ ] One feature commit, squash merge into base branch, push.
