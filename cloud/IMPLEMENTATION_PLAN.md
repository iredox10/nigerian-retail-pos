# Cloud synchronization implementation

Requested 23 November 2026. Supersedes the earlier cloud-excluded scope for this work. Existing local records and ordinary offline operations remain available.

## Architecture lock

- Kotlin/Spring Boot backend, Heroku-compatible configuration, durable Postgres account/device/business/change storage. TLS required outside explicit loopback development.
- Server-owned business IDs; authenticated account/device enrollment and revocation. Local owner/staff credentials never enter business snapshots or the network. Cloud tokens are device-local secrets, not business backups.
- A versioned schema covers every table in the existing tenant business snapshot. Generated local integer row IDs are omitted and immutable child rows use deterministic content identity. Unsupported schema versions block synchronization without discarding records.
- Upload structural deltas relative to a durable acknowledged baseline. One operation ID and payload fingerprint identify a retry. Server commits accepted records, revision and acknowledgment atomically.
- Three-way field merging permits independent edits. Only declared derived stock/customer-balance columns use exact additive deltas. Financial source rows remain immutable. Conflicts retain the complete pending change and explain why it needs review.
- Validate merged snapshots through the existing business recovery rules, plus source-linked inventory reconciliation and count-cutoff protection. Never synchronize mutable stock by last-writer-wins.
- Download ordered changes with a committed cursor. Apply remote work atomically while preserving newer local transactions; advance the local checkpoint only after durable application. Device credentials, local drafts and device preferences remain local.
- Foreground automatic synchronization and resume/retry on both installed apps. Connection, local save, synchronization and external payment verification remain separate statuses. No cloud/payment success without a real acknowledgment.

## Delivery tasks — 24 November checkpoint

- [x] Versioned protocol, trusted schema, exact deltas/merges and conflict tests.
- [x] Transactional local export/import bridge, history retention, tenant isolation and idempotent checkpoints/imports.
- [x] Durable PostgreSQL accounts/devices, migrations, enrollment/revocation and idempotent push/paginated pull.
- [x] HTTPS transport, durable retries, foreground lifecycle, owner-only desktop files and Android Keystore secrets.
- [x] Owner setup and truthful status/retry/conflict screens in both apps under More.
- [x] Real two-SQLite/HTTP/Postgres checks for disconnected sales, duplicate requests, expired/revoked access and restart/lost-reply recovery.
- [x] Deployed HTTPS two-repository convergence plus native Android HTTP publication/retry/download checks.
- [x] Shared/desktop/Android JVM regressions, 29 native Android tests, a separate airplane-mode check, native builds and actual rendered desktop inspection.
- [x] Heroku deployment: app `nigerian-retail-pos-cloud`, Basic dyno, Essential-0 Postgres, release v6; actual public health and staging checks pass.
- [ ] Full manual desktop/Android setup, publication, import and revocation journeys; keyboard-only and accessibility acceptance.
- [ ] Broader concurrent returns/counts over HTTP, large photos/data and slower-device performance; power-loss and independent-machine recovery drills.
- [ ] Windows packaging/ACL execution, physical Android hardware and merchant usability research.

See BUILD_STATUS.md and artifacts/cloud-verification/test-summary.json for exact evidence. Source implementation and selective execution are not complete production rollout acceptance. Conflicts retain records and require underlying business review; no force-overwrite action is provided. Hosting is now actually provisioned (currently $12/month combined); the previous planning text did not authorize or imply a deployment by itself.

## Protocol contract for parallel implementation

Package `pos.cloud.protocol` in JVM library `cloudProtocol` (Android-compatible JVM APIs).

- `typealias CloudRow = Map<String,String?>`
- `CloudSnapshot(tenantId:String,tenantName:String,tables:Map<String,List<CloudRow>>)`
- `CloudColumn(name:String,type:String,primaryKeyPosition:Int,generated:Boolean=false)`
- `CloudTable(name:String,columns:List<CloudColumn>,identityColumns:List<String>,mutableColumns:Set<String>=emptySet(),additiveColumns:Set<String>=emptySet())`
- `CloudSchema(version:Int=1,tables:List<CloudTable>)` with deterministic `fingerprint` property.
- `CloudRowChange(table:String,key:String,before:CloudRow?,after:CloudRow?)`; `CloudDelta(changes:List<CloudRowChange>,beforeName:String?=null,afterName:String?=null)`
- `CloudConflict(table:String,key:String,fields:List<String>,message:String)`
- `CloudPushRequest(operationId:String,baseRevision:Long,schemaHash:String,delta:CloudDelta)`
- `CloudPushReply(operationId:String,status:String,revision:Long,conflicts:List<CloudConflict>=emptyList())` (`accepted` / `conflict`).
- `CloudChangeEnvelope(revision:Long,operationId:String,deviceId:String,delta:CloudDelta)`
- `CloudPullReply(afterRevision:Long,nextRevision:Long,headRevision:Long,changes:List<CloudChangeEnvelope>)`
- `CloudBootstrapReply(businessId:String,revision:Long,schema:CloudSchema,snapshot:CloudSnapshot)`
- JSON via kotlinx.serialization, strict limits; money/quantities remain decimal strings. `CloudCodec.json`, encode/decode helpers if useful.
- `CloudMerge.diff(before,after,schema):CloudDelta`; `CloudMerge.merge(current,delta,schema):CloudMergeResult(snapshot:CloudSnapshot?,conflicts:List<CloudConflict>)`; `CloudMerge.applyExact(before,delta,schema):CloudSnapshot` for ordered server changes.
- Authentication DTOs may be added by backend under protocol package after coordination. `/api/v1/accounts/register`, `/accounts/login`, `/businesses`, `/businesses/{id}/devices`, `/businesses/{id}/bootstrap`, `/businesses/{id}/sync/push`, `/businesses/{id}/sync/pull?after=&limit=`, device list/revoke.

Backend injected ports: `CloudBusinessRules.schema():CloudSchema`, `validateInitial(snapshot)`, `validateMerge(before,after,delta,baseRevision,productVersions):Set<String>` returns products with new stock movements for cutoff versioning. Root supplies business-core adapter; backend must not accept a client-defined schema as authority.
