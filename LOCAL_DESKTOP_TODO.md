# Local desktop completion backlog

Started 14 November 2026. Scope: the local desktop gaps listed in the conversation. Cloud transport, mobile apps and provider verification are excluded. This list supplements IMPLEMENTATION_TODO.md; checked items require a working journey, durable history, relevant tests and recorded verification. Micro commits separate models, storage, UI and evidence. Hardware/merchant results cannot be supplied by code alone.

## 1. Daily trading and correction foundations

- [x] Manual transfer/card payment portions with references and explicit unverified status; split with cash and named-customer credit; draft recovery and receipt/report/export history.
- [x] Credit-sale returns with explicit debt reduction versus refund, repayment allocation reconciliation and immutable correction history.
- [ ] Exchanges linked to original return and replacement sale, explicit price difference.
- [ ] Customer advances, unapplied credit, change-owed liabilities and authorized settlement.
- [ ] Payment reversals/disputes that preserve original entries.
- [ ] Manager approvals/overrides with separate identity, reason and bounded authorization.

## 2. Recovery and desktop readiness

- [x] Scheduled versioned encrypted local backups, truthful status, guarded retention and session-key lifecycle.
- [ ] Owner credential recovery with ownership proof and recovery audit.
- [ ] Disk-full/interrupted-write/corruption diagnostics and controlled recovery.
- [ ] Replacement-machine restore drill (requires another machine), duplicate-import protection.
- [ ] Windows installer/upgrade checks (requires Windows); keyboard/focus and accessibility checks.
- [ ] Physical printer/scanner/drawer compatibility (requires supported hardware); no fake success.

## 3. Purchasing and catalogue

- [x] Supplier invoice metadata, duplicate detection/review and preserved source history.
- [ ] Purchase returns, supplier credit notes, account adjustments and damaged-delivery discrepancies.
- [ ] Landed costs with explicit allocation and historical purchase cost comparison.
- [x] Reviewed bulk CSV catalogue update/repricing with row errors, stale checks and atomic application.
- [x] Retail/wholesale/customer price lists and quantity breaks with historical sale snapshots.
- [ ] Minimum-price policy and approval; promotions/bundles with exact stock/discount arithmetic.
- [x] Shop logo and one product photo with offline database bytes, reasoned replacement/removal history, Stock/Sell thumbnails, captured receipt logos and encrypted backup/restore.
- [x] On-demand PNG/JPEG preview of current/historical supplier invoice evidence.
- [ ] Product variants/variant barcodes, image galleries/cropping/EXIF orientation and expense receipt attachments.
- [ ] Explicit repacking/nested conversions with conserved inventory and history.

## 4. Cash, customer accounts and accounting

- [ ] Per-cashier/register drawers, handover/sign-off and custody transfers.
- [ ] Authorized customer debt adjustments/write-offs/reinstatement/disputes; duplicate customer merge.
- [ ] Owner contributions/drawings and non-cash/accrued expenses.
- [ ] Document costing/backdating policy; historical cost layers, COGS, stock valuation and reliable profit.
- [ ] Journals, chart of accounts, period controls and accountant exports.

## 5. Documents and optional local trade modules

- [x] Quotations with fixed terms/expiry, immutable revisions, PDF/text and explicitly reviewed full-cash conversion.
- [ ] Invoice, delivery-note and credit-note documents; quotation conversion to credit/electronic payment/orders.
- [ ] Orders, deposits/layaway, reservations/expiry, partial fulfilment and collection/dispatch.
- [ ] Optional lots/expiry, serials/warranty/repair, container deposits and loyalty according to enabled business modules.
- [ ] Optional restaurant recipes/tickets/tables; specialist features remain gated by requirements validation.

## Evidence and integration

- [x] Reconcile broader TODO/status/docs after each verified batch.
- [x] Run integrated shared/desktop tests and Linux packaging after integration.
- [x] Render actual changed desktop journeys, exercise selected keyboard input while disconnected, and record unperformed Windows/hardware/merchant checks.

## 14 November continuation checkpoint

Four specialists reviewed quotations, external payments, credit returns and bulk CSV updates in waves. Root integrated storage, routes, draft recovery and report semantics. The final serialized run passes **508 tests** (72 shared + 436 desktop); Linux packaging passes. See BUILD_STATUS.md for actual disconnected sample workflows and screenshots.

Scope limits: checkout currently exposes one external portion plus cash and optional named credit; no provider verification, settlement or external refund. Credit returns require repayment assignment before debt/cash allocation. Price lists are explicitly selected reductions, not automatic pricing or markups. Invoice evidence stores actual bounded PDF/PNG/JPEG bytes and revision history in encrypted backups; the earlier source-path-only description was incorrect. Scheduled backups require the running app, authorized session and supplied password; they are local, not cloud backup or a replacement-machine restore drill. Wider keyboard/accessibility, hardware, Windows and merchant checks remain pending.
