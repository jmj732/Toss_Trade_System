# Portfolio Analysis Workflow Delta

## API

- `POST /api/v1/broker-connections/{id}/portfolio-analyses` executes one synchronous analysis.
- `GET /api/v1/broker-connections/{id}/portfolio-analyses/latest` returns the latest successful result.
- Both endpoints derive ownership only from the authenticated principal and connection path.
- Cross-owner and missing connections return the same not-found response.

## Input selection and quality

- Select the latest `SUCCEEDED` account sync for the connection's current credential revision.
- A newer RUNNING/FAILED sync keeps the previous success as fallback and sets `quality.stale=true`.
- Missing account or KRW/USD capacity sets `quality.partial=true`.
- UNKNOWN cash is copied to `quality.unknownFields`; no cash value is invented.
- Positions use stored currency, market value, and profit/loss without FX conversion.

## Execution

- A short transaction validates ownership, reserves one RUNNING analysis, and captures the input snapshot.
- A PostgreSQL partial unique index permits one RUNNING analysis per user and connection.
- The FastAPI call occurs after that transaction commits.
- A second short transaction marks success and inserts a new immutable result row.
- Timeout, transport, HTTP, and response-contract failures mark the run FAILED without replacing older results.
- No automatic retry, scheduler, queue, heartbeat, event bus, or order call is added.

## Storage and reads

- Analysis runs retain one row per attempt; only RUNNING may transition once to SUCCEEDED or FAILED.
- Result rows are append-only and database triggers reject update/delete.
- A result records its analysis run and input sync snapshot IDs.
- Latest lookup orders successful runs by completion time then ID.
- Later failed/running attempts do not hide the previous successful result.

## Contract boundary

- Requests use analysis schema version `1`.
- Responses must match request ID, schema version, timestamp, quality, and input position identity/value.
- Invalid JSON, mismatched metadata, or invalid result ranges are contract errors.
- The Python service remains stateless and receives no broker, database, credential, or order access.
