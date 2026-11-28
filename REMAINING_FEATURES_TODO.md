# Remaining product delivery checklist

Requested 27 November 2026. This is the active full-scope checklist, superseding stale unchecked entries in older dated plans. The complete requirements remain FEATURE_SPECIFICATION.md. A checked code task requires a usable persisted workflow, failure/permission behavior, relevant tests and BUILD_STATUS.md evidence. Provider/hardware/research acceptance stays separate. Do not claim that a stub or mocked response is a live integration.

## 1. Android everyday workflow parity

- [ ] Reviewed CSV product import, template, bounded UTF-8 reading, row errors, duplicate checks and atomic application.
- [ ] Reviewed existing-catalogue CSV update/repricing with version checks and preserved history.
- [ ] Supplier edit/archive/restore and contact history.
- [ ] Stable supplier and buying-pack selection during receiving.
- [ ] Supplier statements, payments/returns history and exports.
- [ ] Supplier credit-note creation, allocation/reversal/void and evidence.
- [ ] Purchase-order drafts, approval, partial receiving and discrepancies.
- [ ] Supplier invoice metadata and retained attachments.
- [ ] Multi-item saved stock-count sessions, approval/history/export.
- [ ] Repacking recipe editing/posting, source cost and loss review.
- [ ] Price-list, minimum-price and promotion editing/application.
- [ ] Variant-matrix creation/editing and bundle-definition editing.
- [ ] Replenishment policy editing and stock analysis/export.
- [ ] Product galleries, crop/rotate controls and bulk photos.
- [ ] Customer profile editing/history and dated statements/CSV.
- [ ] Repayment assignments/corrections and debt write-offs/reinstatement on Android.
- [ ] Cash owner contributions/drawings/corrections, denomination sheets and expense evidence.
- [ ] Date-range/product/staff/category reports and CSV output.
- [ ] Native PDF report output.
- [ ] Exchange, credit-return and external-return workflows corresponding to supported core rules.
- [ ] Full receipt style/frame/accent/layout/artwork preview and rendering parity.
- [ ] Quotation and other trade document exports, dispatch images and held-sale removal.
- [ ] Staff role/active changes, security audit and API30–32 approval/handover parity.
- [ ] Offline help/practice, diagnostics and complete touch/accessibility/adaptive navigation.

## 2. Payment and external services

Live payment-provider work is deferred by the user on 27 November. Do not implement or activate payment initiation, bank/card verification, provider refunds, settlement or provider webhooks in this batch. Existing local/manual money records retain their truthful status. Other non-payment tasks continue.

- [ ] Select actual payment provider and configure private sandbox credentials.
- [ ] Provider initiation/status adapter, authenticated signed webhooks, exact amount/currency/reference checks and idempotent event history.
- [ ] Verified-payment UI and safe retry/recheck; offline manual records remain unconfirmed.
- [ ] Multiple external portions per checkout and allocation across invoices.
- [ ] Provider reversal, original-method refund and dispute states with immutable history.
- [ ] Settlement batches, fees and bank reconciliation.
- [ ] Consented email/SMS/WhatsApp/Telegram messaging, opt-out, durable queues, retries/cancellation and truthful provider delivery.
- [ ] Connected courier integration with authenticated proof/status updates.
- [ ] Activate Resend verification/recovery using the user's verified sender and private key; verify actual inbox delivery.

## 3. Advanced trading and customer accounts

- [ ] Store-credit issuance, ledger, checkout application and reversals.
- [ ] Change-owed liabilities and explicit authorized settlement.
- [ ] Credit/external/store-credit exchanges with original-price and return-limit conservation.
- [ ] Automatic promotions, stacking policy and mixed-basket/non-cash bundles.
- [ ] Quotation conversion to credit/electronic sale; controlled backdating and receipt-number policies.
- [ ] Duplicate customer merge with retained identities/history and additional approval policies.
- [ ] Supplier adjustments/claims/quarantine and variant matrix revisions.
- [ ] Nested packages, multi-input assembly and yield/loss costing.

## 4. Branches, registers and accountability

- [ ] Explicit branches and registers with scoped staff permissions.
- [ ] Independent per-register/per-cashier drawers and controlled custody transfers.
- [ ] Branch stock transfers with send/receive acknowledgement and discrepancy history.
- [ ] Branch-aware cloud schema, migration, permissions and consolidation; sharing a shop is not branch isolation.
- [ ] Blind cash-accountability policies, commission rules and staff performance reports.

## 5. Accounting, tax and privacy

- [ ] Full chart of accounts, balanced double-entry journals, source mappings and corrections.
- [ ] Accounting periods, locks and controlled prior-period corrections.
- [ ] Accrued/recurring/non-cash expenses and owner equity/loan accounts.
- [ ] Complete cost recognition, capitalized landed costs and reliable gross/net profit.
- [ ] Trial balance, balance sheet, profit/loss and accountant exports.
- [ ] Versioned inclusive/exclusive tax settings and exact calculations.
- [ ] Tax invoices/summaries and actual fiscal connector where applicable.
- [ ] Privacy notices, authorized access/export/deletion requests and lawful retention rules.

## 6. Security and distribution

- [ ] Working database/draft encryption, protected device keys, safe existing-data migration and recovery.
- [ ] MFA enrollment/challenge/recovery/revocation and broader session/account closure controls.
- [ ] Production Android signing and secure key provisioning; signed release artifacts and upgrade acceptance.
- [ ] Authenticated signed app-update manifest, download/verification/rollback and user-visible update flow.
- [ ] Threat review, disk-full/interrupted-write/corruption tests and repair/recovery workflow.
- [ ] Low-spec/large-data performance, Windows installation/upgrades, real peripherals and full accessibility.

## 7. Optional business modules and platforms

- [ ] Loyalty points/stamps/redemption with offline conflict rules.
- [ ] Lots, expiry dates and traceable batch movements.
- [ ] Serial numbers, warranties and repair-job history.
- [ ] Returnable-container deposits and settlement ledger.
- [ ] Restaurant tables/tickets/kitchen/recipe consumption.
- [ ] Pharmacy-specific workflows with reviewed requirements.
- [ ] React/TypeScript/TanStack web app with explicit offline behavior and secure sign-in.
- [ ] Native iOS app/platform adapters with shared financial behavior and Apple signing/device acceptance.
- [ ] Regional language support and guided tutorials.

## 8. Release and acceptance evidence

- [x] Existing Windows packaging CI passed, including MSI/EXE generation and native private-file permission checks (25 November run 45624687804).
- [x] Existing cloud release v9, 15-minute GitHub readiness workflow and database backup schedule are deployed/configured.
- [ ] Run the newest native Android suite and install without clearing existing phone data.
- [ ] Actual two-device QR/camera pairing and account recovery journeys.
- [ ] Verify scheduled monitoring/backup execution and alert delivery.
- [ ] Replacement-machine/domain restore and power-loss drills.
- [ ] Physical printer/scanner/drawer/scale/display matrix and actual Windows installation/upgrade.
- [ ] Merchant first-glance/task testing, TalkBack/screen reader, large fonts and keyboard-only journeys.

## Current implementation batch

Start with Android CSV migration, then supplier/purchasing and stock workflows. Reuse the desktop's tested repositories and existing light/teal visual roles. For every new workflow: load → explicit edit → review → authorized durable commit → saved result/history. Source reference: design/DESIGN_BRIEF.md, DESIGN_GUIDE.md, design/tokens.json and design/copy.json; form/copy decisions follow Refero's bundled craft guidance. Preserve existing local/cloud authorization boundaries.

27 November checkpoint: the first implementation wave, customer editing/statements, supplier statements, buying rules and invoice evidence compile. Native execution and visual acceptance are still pending, so the corresponding tasks are intentionally unchecked. See BUILD_STATUS.md for the failed CI setup run and corrected runner configuration.

28 November checkpoint: the 45-case API 35 native suite passes, including reviewed staff role/active changes, audit history, receipt PDF centring and transaction rollback. Offline screenshots confirm five new tool entry screens; the receipt save/restart walkthrough still awaits completion. Selected held-draft removal is implemented with a new native regression queued in the 46-case suite. Combined checklist items remain open where their other parts or acceptance checks are unfinished.
