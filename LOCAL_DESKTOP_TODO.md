# Local desktop completion backlog

Started 14 November 2026. Scope: the local desktop gaps listed in the conversation. Cloud transport, mobile apps and provider verification are excluded. This list supplements IMPLEMENTATION_TODO.md; checked items require a working journey, durable history, relevant tests and recorded verification. Micro commits separate models, storage, UI and evidence. Hardware/merchant results cannot be supplied by code alone.

## 1. Daily trading and correction foundations

- [x] Manual transfer/card payment portions with references and explicit unverified status; split with cash and named-customer credit; draft recovery and receipt/report/export history.
- [x] Credit-sale returns with explicit debt reduction versus refund, repayment allocation reconciliation and immutable correction history.
- [x] Cash given and change retained on saved receipts, PDFs and CSV. Optional sale notes survive held baskets and are saved to receipt history.
- [x] Frequent items filter in Sell ranked by recent retained sale receipts.
- [x] Cash exchanges linked to the original return and replacement sale, with an explicit cash difference.
- [ ] Exchanges involving customer credit, external payments or store credit.
- [x] Cash customer advances with preserved held balances, explicit application to reconciled receipt debt and unused-cash refunds.
- [ ] Store-credit balances/application at checkout, change-owed liabilities and their authorized settlement.
- [x] Customer receipt-debt disputes with disputed/undisputed balances and reasoned resolution; original financial entries remain unchanged.
- [ ] Payment reversals and payment-provider disputes preserving original entries.
- [x] Separate full-password manager price approvals bound to the exact sale/operation/minimum-price versions, with reason, expiry, revocation checks and signed evidence.
- [ ] Additional manager approval policies for non-price sensitive actions.

## 2. Recovery and desktop readiness

- [x] Scheduled versioned encrypted local backups, truthful status, guarded retention and session-key lifecycle.
- [x] Locally enrolled, single-use owner recovery code with persistent throttling, recovery audit and credential/session/PIN revocation.
- [ ] Wider recovery/security assessment and replacement-machine recovery acceptance.
- [x] Merchant-reviewed redacted local diagnostics for SQLite structure, storage write/cleanup and installed print-service counts, with allowlisted text/JSON export.
- [ ] Disk-full/interrupted-write/corruption recovery drills, repair and broader diagnostics; unchecked services remain unavailable.
- [ ] Replacement-machine restore drill (requires another machine), duplicate-import protection.
- [ ] Windows installer/upgrade checks (requires Windows); keyboard/focus and accessibility checks.
- [ ] Physical printer/scanner/drawer compatibility (requires supported hardware); no fake success.

## 3. Purchasing and catalogue

- [x] Supplier invoice metadata, duplicate detection/review and preserved source history.
- [x] Reviewed purchase returns against unpaid receipt debt, with exact stock removal, recorded credit and reconciled statements.
- [x] Record supplier cash reimbursements during a reviewed return and reconcile receipt debt and the drawer.
- [x] Record and resolve quantity or condition discrepancies on an approved purchase order with reasoned history.
- [x] Referenced supplier credit notes, unapplied balances, reviewed receipt allocation/reversal and reconciled dated statements.
- [ ] Additional supplier-account adjustments, claims and stock quarantine.
- [x] Purchase cost history and latest explicitly linked supplier comparison, normalized by saved buying units with CSV export.
- [x] Explicit landed-cost allocation from a paid cash expense, with exact splitting and revision history.
- [x] Stock reorder suggestions with a configurable coverage period, saved sales, approved incoming quantities and export.
- [x] Reviewed bulk CSV catalogue update/repricing with row errors, stale checks and atomic application.
- [x] Retail/wholesale/customer price lists and quantity breaks with historical sale snapshots.
- [x] Versioned base/selling-pack minimum prices, strict final-line enforcement after discounts and historical sale checks.
- [x] Scheduled, explicitly selected line promotions with qualifying units and quantities, optional usage caps and historical discount details.
- [x] Separately authenticated minimum-price overrides and versioned whole-bundle cash sales with reviewed component stock, exact price allocation, tender/change and immutable source.
- [ ] Automatic promotions, mixed-basket/non-cash bundles and broader discount stacking.
- [x] Shop logo and one product photo with offline database bytes, reasoned replacement/removal history, Stock/Sell thumbnails, captured receipt logos and encrypted backup/restore.
- [x] On-demand PNG/JPEG preview of current/historical supplier invoice evidence.
- [x] Expense receipt PDF/PNG/JPEG attachments, retained historical bytes, preview/export and encrypted recovery.
- [x] Independently stocked product variants, grouped by searchable family and label. Each item retains its own SKU and barcode.
- [x] Structured one/two-axis option matrices with review/edit of generated combinations before creating independently stocked goods and preserving creation history.
- [ ] Revision of existing matrix definitions/combinations beyond normal per-product editing.
- [x] Additional product photo galleries, crop and rotation controls and automatic JPEG orientation.
- [x] Bulk import main product photos from a reviewed folder using SKU or barcode filenames.
- [ ] Full gallery and keyboard acceptance.
- [x] Versioned explicit input/output repacking recipes between two products, reviewed whole-batch execution, atomic paired stock changes and history.
- [x] Original material-cost transfer through saved repacking movements under the FIFO purchase-basis policy; missing source costs stay unavailable.
- [ ] Nested package definitions, multi-input assembly and complete repacking yield/loss accounting.

## 4. Cash, customer accounts and accounting

- [x] Counted handover of the existing shared drawer with separate recipient authentication/sign-off, retained discrepancy and atomic close/open.
- [ ] Independent per-cashier/register drawers and wider cash custody transfers.
- [x] Owner-reviewed credit receipt write-offs and linked reinstatement, with preserved history and reconciled statements and ageing.
- [x] Receipt-debt claim/resolution history and reconciled disputed/undisputed balances without changing the original debt.
- [ ] Other debt adjustments and reviewed duplicate customer merge with preserved source history.
- [x] Local repayment follow-ups with a due date and reasoned closure history.
- [ ] Consented outbound repayment messages and delivery status.
- [x] Classified owner cash contributions/drawings, reviewed current drawer balance, linked opposite cash corrections and CSV history.
- [ ] Formal owner equity/loan accounts and non-cash/accrued expenses.
- [x] Saved FIFO purchase-cost policy/layers, declared opening costs, historical sale/return costing, purchase-return depletion and stock/net-COGS coverage with explicit missing-cost gaps.
- [ ] Broader backdating/alternative costing policies, capitalized landed costs, complete expense recognition and reliable gross/net profit.
- [ ] Journals, chart of accounts, period controls and accountant exports.

## 5. Documents and optional local trade modules

- [x] Receipt style/frame/colour editor, logo visibility, additional artwork and policy text, actual PDF preview, saved-sale preservation and designed printer submission.

- [x] Quotations with fixed terms/expiry, immutable revisions, PDF/text and explicitly reviewed full-cash conversion.
- [x] Sales invoice, pre-handover delivery note and credit note PDF copies from saved records.
- [x] Accepted quotation-to-order conversion preserving agreed goods/units/prices with an optional actual cash deposit.
- [ ] Quotation conversion directly to credit/electronic sales and broader sales document sharing.
- [x] Orders, cash deposits and refunds, order stock holds with WAT expiry, and partial or final counter collection.
- [x] Dispatch of already-sold order goods with recipient/courier/tracking and merchant-recorded delivered/failed/retry/returned proof history; no additional stock/cash or courier-confirmation claim.
- [ ] Wider layaway terms, backorders, dispatch proof-image attachments and external delivery integration.
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

## 23 November local accounts, stock and authorization checkpoint

The desktop backlog above is reconciled with the **680-test** checkpoint (79 shared + 601 desktop) and its recorded Linux packaging/native evidence in [BUILD_STATUS.md](BUILD_STATUS.md). Customer cash advances and receipt-debt disputes, supplier credit allocation, variant matrix creation, whole-bundle cash sales, FIFO purchase-basis coverage, recipient-authenticated shared-drawer handover, bounded manager price approvals, enrolled owner recovery, quotation-to-order conversion, dispatch proof history and redacted diagnostics are implemented local slices. Each open group is split above where only part has shipped; none of these completions establishes full accounting or release acceptance.

Android now has a separate source/acceptance matrix in [ANDROID_FEATURE_PARITY.md](ANDROID_FEATURE_PARITY.md); its build and selected disconnected phone checks are documented in BUILD_STATUS.md. Mobile work does not change the scope of this desktop checklist or imply shared records/cloud sync. Windows, physical peripherals, independent-machine restore, broad keyboard/accessibility/performance acceptance and merchant research remain pending. Earlier dated test counts remain historical evidence, not the current total.
