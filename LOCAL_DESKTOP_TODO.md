# Local desktop completion backlog

Started 14 November 2026. Scope: the local desktop gaps listed in the conversation. Cloud transport, mobile apps and provider verification are excluded. This list supplements IMPLEMENTATION_TODO.md; checked items require a working journey, durable history, relevant tests and recorded verification. Micro commits separate models, storage, UI and evidence. Hardware/merchant results cannot be supplied by code alone.

## 1. Daily trading and correction foundations

- [x] Manual transfer/card payment portions with references and explicit unverified status; split with cash and named-customer credit; draft recovery and receipt/report/export history.
- [x] Credit-sale returns with explicit debt reduction versus refund, repayment allocation reconciliation and immutable correction history.
- [x] Cash given and change retained on saved receipts, PDFs and CSV. Optional sale notes survive held baskets and are saved to receipt history.
- [x] Frequent items filter in Sell ranked by recent retained sale receipts.
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
- [x] Reviewed purchase returns against unpaid receipt debt, with exact stock removal, recorded credit and reconciled statements.
- [ ] Supplier cash reimbursements, unapplied credits, supplier credit notes, account adjustments and damaged-delivery discrepancies.
- [x] Purchase cost history and latest explicitly linked supplier comparison, normalized by saved buying units with CSV export.
- [ ] Landed costs with explicit allocation.
- [x] Stock reorder suggestions with a configurable coverage period, saved sales, approved incoming quantities and export.
- [x] Reviewed bulk CSV catalogue update/repricing with row errors, stale checks and atomic application.
- [x] Retail/wholesale/customer price lists and quantity breaks with historical sale snapshots.
- [x] Versioned base/selling-pack minimum prices, strict final-line enforcement after discounts and historical sale checks.
- [ ] Separate-identity minimum-price approval/override; promotions/bundles with exact stock/discount arithmetic.
- [x] Shop logo and one product photo with offline database bytes, reasoned replacement/removal history, Stock/Sell thumbnails, captured receipt logos and encrypted backup/restore.
- [x] On-demand PNG/JPEG preview of current/historical supplier invoice evidence.
- [x] Expense receipt PDF/PNG/JPEG attachments, retained historical bytes, preview/export and encrypted recovery.
- [x] Independently stocked product variants, grouped by searchable family and label. Each item retains its own SKU and barcode.
- [ ] Structured size/colour options and matrix editing.
- [x] Additional product photo galleries, crop and rotation controls and automatic JPEG orientation.
- [ ] Bulk image import and full gallery/keyboard acceptance.
- [x] Versioned explicit input/output repacking recipes between two products, reviewed whole-batch execution, atomic paired stock changes and history.
- [ ] Nested package definitions, multi-input assembly, costing and repacking yield/loss accounting.

## 4. Cash, customer accounts and accounting

- [ ] Per-cashier/register drawers, handover/sign-off and custody transfers.
- [x] Owner-reviewed credit receipt write-offs and linked reinstatement, with preserved history and reconciled statements and ageing.
- [ ] Other debt adjustments, disputes and duplicate customer merge.
- [x] Local repayment follow-ups with a due date and reasoned closure history.
- [ ] Consented outbound repayment messages and delivery status.
- [x] Classified owner cash contributions/drawings, reviewed current drawer balance, linked opposite cash corrections and CSV history.
- [ ] Formal owner equity/loan accounts and non-cash/accrued expenses.
- [ ] Document costing/backdating policy; historical cost layers, COGS, stock valuation and reliable profit.
- [ ] Journals, chart of accounts, period controls and accountant exports.

## 5. Documents and optional local trade modules

- [x] Quotations with fixed terms/expiry, immutable revisions, PDF/text and explicitly reviewed full-cash conversion.
- [x] Sales invoice, pre-handover delivery note and credit note PDF copies from saved records.
- [ ] Quotation conversion to credit/electronic payment or orders, and sales document sharing.
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

## 17 November local trading checkpoint

Minimum prices, explicit repacking recipes, managed expense evidence and classified owner cash are implemented and integrated. **555 tests pass** (77 shared + 478 desktop); Linux image rebuild and selected actual disconnected native workflows pass. See LOCAL_EXECUTION_TODO.md for delivery order and BUILD_STATUS.md for exact evidence. Broader native/hardware/Windows/merchant acceptance remains pending. Cloud sync is excluded by the current user instruction.

## 20 November local trading checkpoint

Cash given and change, sale notes, Frequent items, searchable variant families, local repayment follow-ups and debt-only supplier returns are integrated. **570 tests pass** (77 shared and 493 desktop), including encrypted backup recovery into another database. The Linux image and selected disconnected journeys pass. See BUILD_STATUS.md for screenshots and remaining acceptance checks.
