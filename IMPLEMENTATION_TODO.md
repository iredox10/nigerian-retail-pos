# Complete implementation TODO

Authority: FEATURE_SPECIFICATION.md (all 32 sections). Coverage and evidence: IMPLEMENTATION_STATUS.md and BUILD_STATUS.md.
Provider decisions: [PROVIDER_DECISIONS.md](PROVIDER_DECISIONS.md). Paystack selected; Telegram first; no existing Heroku app.

Created 13 November 2026. Work through each item with three implementation agents, integrate and verify, then make small commits.

A checked box means that specific item has implementation and recorded verification. It does not mark its whole module production-ready. Keep unfinished subtasks unchecked. External prerequisites are dependencies, not completed features. Do not invent credentials, successful integrations, merchant studies or hardware results.

## Staff identities and permissions — local password/role batch verified

- [x] Local staff identities and password credentials scoped to a shop.
- [x] Owner, manager and cashier permission policies.
- [x] Owner staff creation, role changes, disable/enable and password reset.
- [x] Staff sign-in, session validation and revocation.
- [x] Enforce mutation permissions at the repository boundary and restrict visible routes/actions.
- [x] Protect owner backup and staff management operations.
- [x] Verify migration, tenant isolation, revocation, denied actions, actual desktop login and packaging.
- [ ] Short staff PIN unlock with attempt limits and secure device policy.
- [ ] Attribute every financial/catalogue/correction event to an actor.
- [ ] Owner/manager approval requests and auditable overrides.
- [ ] Session expiry, inactivity lock, credential recovery and staff security review.
- [ ] Staff commissions and performance reports.

## Next implementation: quantities and catalogue

- [ ] Exact scaled fractional quantities and explicit precision per product.
- [ ] Fractional checkout, stock receiving, returns, counts and conversion snapshots.
- [ ] Safe migration of existing quantities and backward-compatible backup/import handling.
- [ ] Nested package conversion definitions and explicit repacking/break-bulk reconciliation.
- [ ] SKU, categories, brands, aliases and product search filters.
- [ ] Product variants and unique variant barcodes.
- [ ] Product images and offline attachment storage.
- [ ] Favourites and frequent-item selection.
- [ ] Barcode label generation and printing.
- [ ] Bulk edit/update CSV preview, validation, conflict handling and audit.
- [ ] Opening balance imports for customers/suppliers with source history.

## Pricing and checkout

- [x] Named whole selling packs, independent prices/barcodes, mixed loose/pack baskets and base-stock deduction.
- [x] Reasoned lower unit prices, original/net price snapshots, exact discounted cash returns and recovered baskets.
- [ ] Percentage discounts with explicit integer-kobo rounding.
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
- [ ] Historical as-of debt and configurable ageing bands.
- [ ] Customer advance/unapplied-credit accounts and merchant-owed balances.
- [ ] Debt adjustments, write-offs, reinstatement and disputes with authorization.
- [ ] Duplicate customer merge with review and immutable source links.
- [ ] PDF/dated statements and document delivery.
- [ ] Consented repayment reminders and delivery status.

## Purchasing and suppliers

- [ ] Supplier master profiles, contacts and terms.
- [ ] Purchase orders, approvals and multi-line purchasing.
- [ ] Partial/short/damaged deliveries and outstanding purchase quantities.
- [ ] Supplier invoice duplicate detection and attachments.
- [ ] Landed-cost allocation and purchase-cost history/comparisons.
- [ ] Purchase returns, supplier credit notes and account adjustments.
- [ ] Full supplier statements and statement exports.
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

- [ ] Product/category/staff performance, comparisons and custom reports.
- [ ] Shrinkage, stock valuation, supplier and settlement reports.
- [ ] Consolidated completeness/freshness indicators.
- [ ] Loyalty points/stamps, redemption and offline conflicts.
- [ ] Customer segments and messaging consent/opt-out.
- [ ] SMS/email/WhatsApp queue, retries, delivery and campaigns.
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
