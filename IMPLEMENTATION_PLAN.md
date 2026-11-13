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
- [ ] Complete exact per-product fractional quantities through stock, checkout, receiving, returns, and counts.
- [ ] Preserve historical precision, retry identity, saved baskets, exports, and encrypted backup compatibility.
- [ ] Verify legacy migrations and cumulative partial-refund rounding.
- [ ] Render and exercise changed desktop journeys; document exact checks and limitations.
- [ ] Commit the finished current batch in coherent micro commits.

## Phase 2: complete independent local journeys

- [ ] Cash denomination sheets: exact totals, atomic opening/closing snapshots, optional blind entry, history and backup.
- [ ] Offline help: contextual task guidance and safe entry into an isolated practice workspace.
- [ ] Staff convenience PINs: full-password bootstrap, bounded authorization, persistent attempt limits, reset/revocation and UI.
- [ ] Reconcile feature status and rerun integrated tests after each batch.

## Phase 3: trading and financial completeness

- [ ] Nested packages/repacking, variants/images, labels, reviewed bulk catalogue updates.
- [ ] Basket discounts, price lists, promotions, bundles, floor-price approvals.
- [ ] Explicit manual electronic-payment records, split tenders, customer advances/change owed and reversals.
- [ ] Credit returns, exchanges, store credit and controlled corrections.
- [ ] Supplier master accounts, purchasing, partial deliveries, landed costs and supplier credits.
- [ ] Multi-item counts, stock locations, lots/expiry/serials and valuation.
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
