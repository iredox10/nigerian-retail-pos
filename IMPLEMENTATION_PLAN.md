# Implementation execution plan

Started 13 November 2026. This plan orders execution; the complete scope remains in
`FEATURE_SPECIFICATION.md`, with individual requirements tracked in
`IMPLEMENTATION_TODO.md`. A phase is not complete merely because code has been written.

## Working rules

- Finish the current local feature batch before releasing subsequent batches.
- Make small, explicit-file commits for coherent domain, persistence, UI, and verification changes.
- Preserve existing working-tree changes unless reviewed and intentionally included.
- Run meaningful financial-integrity, migration, tenant-isolation, and recovery checks.
- Review real Compose renders, keyboard input, and disconnected local operation for changed journeys.
- Keep hardware, merchant research, external-provider validation, and production readiness separate.
- Use five specialist subagents in waves; this session supports three concurrent workers alongside the coordinator.

## Phase 1: finish the current batch

- [x] Audit percentage item discounts, catalogue metadata/filtering, and dated statements against source.
- [x] Run the pre-change integrated suite: 287 tests, no failures, errors, or skips.
- [x] Complete exact per-product fractional quantities through stock, checkout, receiving, returns, and counts.
- [x] Preserve historical precision, retry identity, saved baskets, exports, and encrypted backup compatibility.
- [x] Verify legacy migrations and cumulative partial-refund rounding.
- [x] Render and exercise selected changed desktop journeys; document exact checks and limitations in BUILD_STATUS.md. Full merchant, hardware and accessibility acceptance remains pending.
- [x] Commit the current implementation batch in coherent micro commits.

## Phase 2: complete independent local journeys

- [x] Cash denomination sheets: exact totals, atomic opening/closing snapshots, expected-cash hiding within the form, history and backup. This does not conceal previously viewed dashboard amounts.
- [x] Offline help: contextual task guidance and safe entry into an isolated practice workspace.
- [x] Staff convenience PINs: full-password bootstrap, bounded authorization, persistent attempt limits, reset/revocation and UI.
- [x] Reconcile feature status and rerun integrated tests: 324 passing tests and a rebuilt Linux application image.

Five agents completed the assigned audit, quantity, backlog, cash and help tasks in
waves; the audit agent subsequently implemented PINs. Phase 3 now includes the
completed local slices below; its remaining scope and Phases 4–5 are unfinished.
External integrations are unavailable.

## Phase 3: trading and financial completeness

### Completed first-wave five-agent assignments

The five local slices below are integrated and pass a 389-test suite with zero failures,
errors or skips. This supersedes the earlier 324-test checkpoint. The coordinator
integrated migrations, backups and routes. Targeted rendered/offline checks do not
establish complete keyboard, native-dialog, merchant or hardware acceptance.

- [x] Basket discounts: exact fixed/percentage allocation, measured quantities, saved baskets and refund conservation.
- [x] Supplier profiles: contact/terms/history, explicit purchase links and stable-account statements.
- [x] Stock-count sessions: durable multi-item drafts, cutoff/stale-count checks, atomic posting and preserved history.
- [x] Barcode labels: validated supported symbology, file generation and native print submission with hardware limits recorded.
- [x] Shop and receipt settings: durable business/contact settings, history, backups, immutable receipt identity snapshots and branded A4/58/80 mm PDF receipts. Legacy receipts explicitly lack captured contacts.

A subsequent offline statement-PDF slice adds dated customer and stable supplier-account
PDF exports using the existing reconciled financial histories. Its targeted run passes
7 statement tests plus 5 unchanged receipt tests; this is not a new whole-suite count.
Current contacts, historical receipt names, selected WAT dates and page identities remain explicit.
Full native save/overwrite/cancel UI acceptance remains pending.

### Subsequent local wave verified

Final checkpoint: 424 passing tests (56 shared + 368 desktop), zero failures/errors/skips; Linux package rebuilt. See BUILD_STATUS.md for selected native and PDF evidence.

- [x] Purchase-order drafts/approval/cancellation and atomic multi-line partial receiving: integrated tests, backup/history and selected offline native checks pass. Damaged receipts, landed costs and supplier credits remain pending.
- [x] Classified physical stock removals: integrated exact-quantity/backup/history tests and selected offline native review/save pass. Accounting valuation remains pending.

Work runs in waves of up to three workers alongside the coordinator. The full
remaining specification below remains in scope; account/hardware/field research
dependencies must not be marked successful without evidence.

- [ ] Nested packages/repacking, variants/images, additional label layouts/hardware support and reviewed bulk catalogue updates.
- [ ] Price lists, promotions, bundles, floor-price approvals and broader basket-discount acceptance.
- [ ] Explicit manual electronic-payment records, split tenders, customer advances/change owed and reversals.
- [ ] Credit returns, exchanges, store credit and controlled corrections.
- [ ] Purchasing/partial deliveries under implementation, supplier account corrections, landed costs and supplier credits.
- [ ] Count variance approvals and broader count acceptance, stock locations, lots/expiry/serials and valuation.
- [ ] Cash custody, accounting/costing, documents, orders and fulfilment.

Each item must be decomposed into a complete usable journey with persistence and
failure handling before it is checked off; separate arithmetic models are foundations.

## Phase 4: online services and other platforms

- [ ] Backend tenant identity/authorization, Postgres schema, device enrolment and authenticated idempotent APIs.
- [ ] Define sync conflict policies, then transport, acknowledgements, retries, cursors and reconciliation.
- [ ] Paystack adapter/webhooks/rechecks/settlement, with actual sandbox evidence before availability claims.
- [ ] Consented Telegram delivery queue, opt-out and retry handling, then other channels as supported.
- [ ] Heroku staging and restore drills, then deployment checks.
- [ ] Android, iOS and React/TanStack web implementations with platform-specific offline tests.

Provider credentials, account access, signing, service reachability and hardware are
external dependencies. Local work must not claim those dependencies have succeeded.

## Phase 5: remaining scope and release acceptance

- [ ] Loyalty, branch operations, sector modules, configurable tax and privacy workflows.
- [ ] Encrypted working storage, automatic/versioned backups, device recovery and broader security assessment.
- [ ] Low-spec/large-catalogue profiling, Windows packaging, peripherals and accessibility.
- [ ] Merchant first-glance and task studies; address findings before production-readiness claims.

The detailed checklist remains authoritative for unlisted subtasks. No phase removes
the rest of the specification or substitutes automated tests for field validation.

## 14 November continuation — integrated local trade batch

- [x] Four specialist reviews: quotations, manual external payments, credit returns and bulk catalogue updates, in concurrency-limited waves.
- [x] Root storage/backup/permission integration, explicit desktop routes and recovered split-payment drafts.
- [x] Versioned reduction-only price lists, supplier invoice metadata/correction evidence and opt-in encrypted scheduled local backup services/UI.
- [x] 508 integrated tests, Linux packaging and selected disconnected native workflows. Detailed evidence and limits are in BUILD_STATUS.md.
- [ ] Extend these slices into the unchecked LOCAL_DESKTOP_TODO.md scope; no whole specification section is complete.
