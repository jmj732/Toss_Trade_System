# PostgreSQL Backup and Restore

## Scope

- Covers the `postgres` service only (database `trade`): order/risk/audit ledgers, broker
  connections, account snapshots, analysis results, intelligence events.
- **Redis is out of scope.** It holds only cache and session/OAuth-token-cache state. After a
  restore, Redis starts fresh and empty — this is expected and requires no action. Users are
  simply signed out and re-authenticate.
- A backup contains **no secrets**. It is a `pg_dump` of application data only — never `.env`,
  `POSTGRES_PASSWORD`, or `CREDENTIAL_KEY_BASE64`. Broker credentials
  (`broker_connections.credential_ciphertext`) are already application-layer encrypted before
  they are ever written to the database, so the dump only ever contains ciphertext. The
  decryption key lives outside Postgres entirely and must never be backed up alongside the
  dump.

## Taking a backup

```sh
./scripts/backup-postgres.sh backups/trade-$(date -u +%Y%m%dT%H%M%SZ).dump
```

Requires the `postgres` service to already be running (`docker compose up -d postgres` or the
full stack). Targets the default `trade-local` compose project; to target a different project
(e.g. a differently-named deployment), set `COMPOSE_PROJECT` first:

```sh
COMPOSE_PROJECT=my-project ./scripts/backup-postgres.sh backups/trade.dump
```

Store the resulting `.dump` file somewhere access-controlled and durable — this repo's
`backups/` directory is gitignored and is not a storage location, only a local scratch default.

## Restoring a backup

1. Stop anything writing to the target database (stop the `backend` service, or point it at a
   different database first).
2. Ensure the target `postgres` service is running and reachable.
3. Restore:

   ```sh
   ./scripts/restore-postgres.sh backups/trade-20260728T000000Z.dump
   ```

   `pg_restore --clean --if-exists` drops existing objects before restoring, so this is safe to
   run against a database that already has data in it — the restore fully replaces it.

4. Start (or restart) `backend`. Flyway runs automatically at Spring Boot startup and is the
   re-validation step: if the restored `flyway_schema_history` table's checksums don't match
   the migrations shipped in the running JAR, the application **fails to start** rather than
   serving traffic against a schema it can't trust. A clean start with no new migrations
   applied (only `"Schema is up to date, no migration necessary"` in the logs) is the signal
   that the restore is trustworthy.
5. Confirm `GET /actuator/health/readiness` reports `UP`.
6. Spot-check recent data (e.g. a known account's latest portfolio sync) against what you
   expect to have survived, given the backup's timestamp.

## Restore drill (automated)

`./scripts/backup-restore-drill.sh` proves the full cycle automatically against an isolated,
throwaway compose project (random ports, random local secrets, no interaction with any other
running stack): seeds one canary row, backs up, destroys both the Postgres and Redis volumes,
restores Postgres only, lets the real backend boot against the restored database (the Flyway
re-validation gate described above), and confirms the canary row survived. It also asserts the
backup file does not contain the drill's own generated Postgres password or credential key, as
a concrete check that no secret leaked into the dump.

Run it periodically (e.g. before a release, or on a schedule) as the operational restore drill,
not just as a one-off verification:

```sh
./scripts/backup-restore-drill.sh
```

Exits `0` and prints `backup restore drill: PASS` on success; on failure it dumps the stack's
container logs and exits non-zero.

## What this does not cover

- No backup encryption at rest, no offsite/S3 storage, no retention policy, no automated
  scheduling of real backups. `DESIGN.md` §15.2 tracks encrypted backups as a later
  production-hardening item.
- No point-in-time recovery (WAL archiving). This is a full-snapshot backup/restore only.
- Crypto-erasure of old ciphertext from prior backups or WAL is not addressed here (see
  `docs/superpowers/specs/2026-07-27-broker-connection-credential-vault-design.md`).
