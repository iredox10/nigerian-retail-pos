# Complete implementation TODO

Authority: FEATURE_SPECIFICATION.md (all 32 sections). Coverage and evidence: IMPLEMENTATION_STATUS.md and BUILD_STATUS.md.
Provider decisions: [PROVIDER_DECISIONS.md](PROVIDER_DECISIONS.md). Paystack selected; Telegram first; no existing Heroku app.

Reconciled against source and recent commits on 13 November 2026. Use five specialist agents in waves within the available concurrency limit, integrate their work, and make small, explicitly scoped commits. An agent task is not a completion claim.

A checked box means that specific item has implementation and recorded verification. It does not mark its whole module production-ready. Keep unfinished subtasks unchecked. External prerequisites are dependencies, not completed features. Do not invent credentials, successful integrations, merchant studies or hardware results.

## Current batch and evidence rules

The recent source includes percentage item discounts, catalogue metadata and checkout filters, dated customer CSV statements, and receipt-name supplier statements. Their old unchecked entries meant acceptance was unfinished; they must not be read as absence of code. BUILD_STATUS.md previously recorded 258 tests for historical credit/performance reports. During this implementation batch the coordinating agent verified a fresh 287-test baseline (30 shared and 257 desktop). This documentation task itself ran no tests or UI sessions; fractional changes and further integrations require their own subsequent run. Desktop/keyboard/offline acceptance for recent UI additions remains separate.

- [ ] Finish the current fractional-quantity journey: the existing `Quantity` arithmetic/parser is a foundation, not yet proof of fractional checkout, receiving, counts or returns. **In progress.**
- [ ] Verify the recent percentage-discount, metadata/filter and dated-statement journeys against real rendered desktop screens, keyboard input, denied permissions and local/offline failure states; record actual results before checking their acceptance items below.
- [ ] Preserve and review pre-existing uncommitted test/artifact changes separately; never absorb unrelated changes into a feature commit.

## Dependency-ordered execution plan

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the coordinating agent's current assignments, commit sequence and verification log.

Work is ordered by dependencies, not by hiding any part of the full specification. Each phase uses small working journeys with repository/domain tests, migration and retry checks where relevant, an actual desktop render for changed UI, and truthful evidence in BUILD_STATUS.md. Keep a feature unavailable until its persistence and failure behavior work.

1. **Finish and commit the local feature batch.** Complete exact quantities with immutable per-product precision and historical snapshots; integrate checkout/receiving/counts/returns/drafts/backup/import/export; exercise legacy migrations, partial-return rounding, over-return/over-sell rejection, restart recovery and cash/debt reconciliation. Verify recent discounts/catalogue/statements and reconcile this checklist afterward.
2. **Close daily local retail gaps.** Add catalogue variants/images/labels and bulk updates; basket discounts/price lists/promotions; customer/supplier profiles and corrections; count sessions and inventory dispositions. Each slice must preserve audit and unit semantics and pass tenant/permission/retry tests. Add settings/document branding and offline help without expanding the primary navigation.
3. **Establish accounting and custody.** Select and document historical costing/backdating policy before valuation/COGS/profit. Add cashier/register drawers, denomination/blind counts, handovers and controlled period corrections, then source-linked journals and accountant reports. Never call purchase subtraction profit. Verify full stock/cash/debt/journal reconciliation with returns and corrections.
4. **Harden local identity, storage and recovery.** Implement PIN/approval policy, credential recovery, protected storage keys, working-data encryption, migration/interruption recovery, diagnostics and versioned backups. Verify denial leaves records unchanged, session revocation, bad keys/corruption/disk-full behavior and restore on another machine. Broader security assessment remains separate.
5. **Build the online foundation.** Settle device identity, offline authorization duration, command/event schema, movement-based stock and conflict policies. Implement backend accounts/memberships, Postgres migrations, authenticated idempotent API, cursors/acknowledgements/retries and attachment transfer. Test two disconnected devices, duplicate delivery, stale permissions and late closed-period events before showing sync completion.
6. **Integrate payment and communication services.** Add explicit manual/unverified tender states and split payments; implement Paystack backend verification/webhooks/rechecks/refunds/settlement, then consent-linked Telegram queues and other approved channels. Automated fake-provider tests may verify adapters but do not prove live integration. Credentials, reachable staging/webhooks and real sandbox checks are external prerequisites.
7. **Extend business and platform workflows.** Implement orders/deposits/layaway/fulfilment, branches/transfer custody, loyalty and selected trade modules; add Android/iOS adapters and React/TanStack web with explicitly tested offline capability. Tax/fiscal and specialist pharmacy features require applicable requirements review before enabling them.
8. **Release acceptance.** Verify Windows installation/upgrades, supported real peripherals and scaling, keyboard/touch/screen readers, low-spec/large-catalogue performance, merchant first-glance/task studies and the complete offline matrix/acceptance scenarios. Deployment, subscription/updater and support operations require their own recovery checks. Do not substitute unit tests or screenshots for merchant/hardware evidence.

### Integration and research prerequisites

- **Paystack:** merchant test account/credentials, approved flows, secure backend configuration, current official API/signature scheme and reachable webhook endpoint; actual sandbox evidence before marking connected.
- **Telegram:** backend-held bot token, opted-in linked test chats, consent/opt-out, throttled idempotent delivery and actual provider checks. Other channels require available API access and cost review.
- **Hosting:** Heroku account/project access and staging Postgres resources; deployment/restore checks before production. No existing deployed app is claimed.
- **Platforms/hardware:** Windows host, Android/iOS devices/toolchains/signing as required, explicitly supported printers/scanners/scales/drawers/displays and replacement-machine restore access.
- **Product/research:** merchant participants, supported-device baseline, costing/conflict/authorization policies, tax/privacy applicability and pharmacist-led specialist validation. These cannot be fabricated by implementation agents.

## Staff identities and permissions — local password/role batch verified

- [x] Local staff identities and password credentials scoped to a shop.
- [x] Owner, manager and cashier permission policies.
- [x] Owner staff creation, role changes, disable/enable and password reset.
- [x] Staff sign-in, session validation and revocation.
- [x] Enforce mutation permissions at the repository boundary and restrict visible routes/actions.
- [x] Protect owner backup and staff management operations.
- [x] Verify migration, tenant isolation, revocation, denied actions, actual desktop login and packaging.
- [ ] Short staff PIN unlock with attempt limits and secure device policy.
- [x] Attribute new repository financial/catalogue/correction events to the signed-in actor; preserve snapshots, retries and backup history.
- [x] Searchable business activity history, date-filtered CSV export and owner-only staff-change history.
- [ ] Broader audit of platform/admin actions and security review; historical actors cannot be reconstructed.
- [ ] Owner/manager approval requests and auditable overrides.
- [x] Persistent failed-login throttling.
- [x] Session expiry and inactivity lock.
- [ ] Owner credential recovery and staff security review.
- [x] Recorded-staff sales/refund activity CSV with separate recording actors and explicit unattributed history.
- [ ] Commission rules, targets and broader staff/cash-accountability performance reports.

## Quantities and catalogue — current implementation batch

- [ ] Exact scaled fractional quantities and explicit precision per product. Arithmetic/parser and domain tests exist; end-to-end integration is in progress.
- [ ] Fractional checkout, stock receiving, returns, counts and conversion snapshots.
- [ ] Safe migration of existing quantities and backward-compatible backup/import handling.
- [ ] Nested package conversion definitions and explicit repacking/break-bulk reconciliation.
- [ ] Accept SKU/category/brand/alias editing, immutable metadata history, CSV snapshots and checkout search/category filters. Code and targeted tests exist; integrated and rendered acceptance evidence must be recorded.
- [ ] Product variants and unique variant barcodes.
- [ ] Product images and offline attachment storage.
- [ ] Accept the implemented checkout Favourites filter; automatic frequently-sold selection remains unimplemented.
- [ ] Barcode label generation and printing.
- [ ] Bulk edit/update CSV preview, validation, conflict handling and audit.
- [ ] Opening balance imports for customers/suppliers with source history.

## Pricing and checkout

- [x] Named whole selling packs, independent prices/barcodes, mixed loose/pack baskets and base-stock deduction.
- [x] Reasoned lower unit prices, original/net price snapshots, exact discounted cash returns and recovered baskets.
- [ ] Accept implemented per-selling-unit percentage discounts with explicit kobo rounding, recovered drafts and net-price returns. Code and targeted tests exist; full batch verification remains pending.
- [ ] Whole-basket discounts with deterministic line allocation and return handling.
- [ ] Retail/wholesale/customer price lists and quantity breaks.
- [ ] Promotions, validity periods, usage rules and explicit offline time policy.
- [ ] Bundles with component stock movements.
- [ ] Minimum selling prices, cost/margin warnings and approval controls.
- [ ] Bulk repricing with preview and change history.
- [ ] Described unlisted/open-price sale items with permissions.
- [ ] Basket/sale notes and frozen agreed prices for held quotations.
- [ ] Sale backdating with effective and recorded timestamps and permissions.
- [ ] Historical tender/change snapshots and receipt-number policy.

## Payments and returns

- [ ] Manual transfer/card recording with explicit unverified status and references.
- [ ] Split cash/transfer/card payments and payment allocation across invoices.
- [ ] Underpayment/overpayment, customer advances and change-owed liabilities.
- [ ] Payment reversal and dispute workflows preserving the ledger.
- [ ] Provider adapter, authenticated webhook verification and idempotent processing.
- [ ] Recheck/retry state machine and verified payment UI.
- [ ] Provider settlement batches, fees and bank reconciliation.
- [ ] Credit-sale returns with repayment/debt reconciliation.
- [ ] Exchanges and price differences.
- [ ] Store-credit and original-method refunds.
- [ ] No-receipt returns policy, approval and damaged/quarantined goods.
- [ ] Cross-device refund/credit risk controls.
- Dependency: actual payment-provider access, approved credentials and test accounts.

## Customers and credit

- [x] Contact details, enforced credit limits and agreed due dates.
- [x] Explicit repayment assignments and current-debt ageing bands/CSV.
- [x] Reasoned assignment undo and explicit reassignment with original history retained.
- [x] Recorded-date historical debt/assignment reconstruction and configurable report ageing bands.
- [ ] Persist shop-wide default ageing preferences and historical customer profile snapshots.
- [ ] Customer advance/unapplied-credit accounts and merchant-owed balances.
- [ ] Debt adjustments, write-offs, reinstatement and disputes with authorization.
- [ ] Duplicate customer merge with review and immutable source links.
- [ ] Accept implemented dated customer CSV statements with reconciled opening/closing debt and WAT cutoffs. Code and targeted tests exist; current integrated/desktop evidence remains pending.
- [ ] PDF statements, sharing and server document delivery.
- [ ] Consented repayment reminders and delivery status.

## Purchasing and suppliers

- [ ] Supplier master profiles, contacts and terms.
- [ ] Purchase orders, approvals and multi-line purchasing.
- [ ] Partial/short/damaged deliveries and outstanding purchase quantities.
- [ ] Supplier invoice duplicate detection and attachments.
- [ ] Landed-cost allocation and purchase-cost history/comparisons.
- [ ] Purchase returns, supplier credit notes and account adjustments.
- [ ] Accept implemented dated receipt-name supplier statements and CSV, including paid receipts, opening/closing debt and current debt. Code and targeted tests exist; full acceptance evidence remains pending.
- [ ] Extend statements to supplier master accounts, credits, adjustments and attachments after those source workflows exist.
- [ ] Reorder suggestions and supplier order export.

## Inventory

- [x] Source movement history and configurable low-stock threshold/filter.
- [ ] Multi-item stock-count sessions, cutoffs and variance approvals.
- [ ] Locations/bins, reservations and available/in-transit stock.
- [ ] Lots, expiry dates, serial numbers and stock ageing.
- [ ] Damage/loss/theft/owner-use adjustments with reason categories.
- [ ] Advanced replenishment, safety stock and stockout analysis.
- [ ] Costing method, historical cost layers and stock valuation.

## Cash and accounting

- [ ] Per-register/per-cashier drawers.
- [ ] Denomination counts, blind closing and handover/sign-off.
- [ ] Cash custody transfers and controlled period corrections.
- [ ] Accrued/recurring expenses, attachments and non-cash funding sources.
- [ ] Explicit contributions/drawings accounts.
- [ ] Historical COGS and reliable gross/net profit.
- [ ] Chart of accounts, double-entry journals and period controls.
- [ ] Trial balance, balance sheet and accounting exports.

## Documents, orders and trade modules

- [ ] Shop/contact/branding settings and receipt templates.
- [ ] PDF receipts, invoices, quotations, delivery notes and credit notes.
- [ ] Thermal 58/80 mm layouts and hardware verification.
- [ ] Orders, deposits, layaway and reservation expiry.
- [ ] Fulfilment, backorders, delivery/dispatch and proof of delivery.
- [ ] Source channels, storefront/payment links and stock buffers.
- [ ] Beverage container/deposit ledger.
- [ ] Fashion variants/size-colour workflows.
- [ ] Serial/warranty/repair jobs.
- [ ] Contractor/custom-measure dispatch.
- [ ] Restaurant recipes, kitchen tickets and tables.
- [ ] Specialist pharmacy workflows following applicable requirements.

## Branches, online services and platforms

- [ ] Multiple branches/warehouses under one business tenant.
- [ ] Device/register enrolment and branch memberships/permissions.
- [ ] Branch pricing, stock transfer custody and consolidated reports.
- [ ] Backend accounts, authentication, tenant memberships and authorization.
- [ ] Heroku-compatible backend and Postgres migrations.
- [ ] Idempotent authenticated command/sync API.
- [ ] Upload/download acknowledgements and retry scheduler.
- [ ] Per-device cursors, schema versions and conflict/convergence policy.
- [ ] Offline stock/credit/refund conflict resolution with visible freshness.
- [ ] Attachments, stale device/authorization alerts and optional LAN sync.
- [ ] Heroku staging deployment, restore drills and production deployment.
- [ ] Android app and local persistence/secure storage/hardware adapters.
- [ ] iOS app and platform adapters.
- [ ] React/TypeScript/TanStack web app with explicit offline capabilities.
- [ ] Windows installer, upgrades and packaging checks.
- Dependencies: hosting account/credentials, platform signing where required and device access.

## Reporting, communication, tax and product operations

- [x] Product sales/refunds and recorded-staff activity CSV reports with source-date/actor semantics.
- [ ] Category performance, comparisons, charts and custom reports.
- [ ] Shrinkage, stock valuation, supplier performance and settlement reports; receipt-name statement code exists as described above.
- [ ] Consolidated completeness/freshness indicators.
- [ ] Loyalty points/stamps, redemption and offline conflicts.
- [ ] Customer segments and messaging consent/opt-out.
- [ ] Telegram-first consent-linked queue, retries, cancellation and truthful delivery status.
- [ ] SMS/email/WhatsApp adapters and campaigns after actual access/cost/consent review.
- [ ] Tax settings/history and inclusive/exclusive calculations.
- [ ] Tax invoices/summaries and applicable fiscal connector.
- [ ] Privacy notices, access/export/deletion requests and retention rules.
- [ ] Regional language translations and broader address/phone support.
- [ ] In-app offline help, tutorials and practice workflows.
- [ ] Consented diagnostics, support and peripheral troubleshooting.
- [ ] Application updater, subscriptions and grace policies.
- [ ] Integration authentication, outbound/webhook event logs and retries.
- Dependencies: actual messaging/fiscal/service access and reviewed applicable rules.

## Security, recovery and release acceptance

- [ ] Encrypted working database/drafts with protected device keys.
- [ ] Automatic/versioned/cloud backups and recovery on another machine.
- [ ] MFA, remote device/session revocation and account closure.
- [ ] Fail-safe disk-full/interruption/corruption recovery.
- [ ] Threat review and security assessment.
- [ ] Real low-spec performance measurement and large-catalogue tests.
- [ ] Complete keyboard, touch, scanner and screen-reader workflows.
- [ ] Real printer/cash-drawer/scale/customer-display compatibility.
- [ ] Windows installation/upgrade tests and platform accessibility.
- [ ] Merchant first-glance and end-to-end task studies.
- [ ] Full offline availability matrix and all acceptance scenarios verified.
- [ ] Production operational readiness review with truthful evidence.
