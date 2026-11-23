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

## Delivery tasks

- [ ] Versioned protocol, schema identities, structural deltas, exact deterministic merge and conflict tests.
- [ ] Local repository export/import bridge preserving completed records, local history and tenant isolation.
- [ ] Durable account/device authorization and Postgres migrations; idempotent push/paginated pull.
- [ ] Secure desktop/Android HTTP transport, local checkpoints, secret storage, backoff and lifecycle.
- [ ] Owner cloud setup: create/sign in, publish an existing shop, connect another device to the same shop, and revoke device access.
- [ ] Truthful sync status, retry and conflict review in both apps without expanding primary navigation.
- [ ] Real two-database/two-client HTTP/Postgres checks: disconnected edits, duplicate requests, stale credentials, concurrent sales/returns/counts and restart recovery.
- [ ] Desktop/Android regressions, native builds and rendered checks.
- [ ] Heroku deployment configuration and actual staging checks when the hosting account/app is available. No purchased resources or live deployment is implied by local tests.

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
