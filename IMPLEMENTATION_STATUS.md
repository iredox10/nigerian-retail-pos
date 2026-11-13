# Retail POS implementation status

Source audit: 13 November 2026.

This document compares the **entire** [feature specification](FEATURE_SPECIFICATION.md) with the source currently present. It is not a claim that the desktop application, long-term specification, or multi-tenant SaaS is complete.

The current product is a local Kotlin/Compose desktop foundation. Tenant-scoped shop records, owner/staff password access, cash/credit sales, buying/selling packs, stock receiving/counts/history, cash shifts, linked cash returns, reports/CSV, receipt output and encrypted manual backup/recovery exist. The last recorded integrated checkpoint in BUILD_STATUS.md is **258 passing tests** for historical credit and performance reports; this is recorded evidence, not a fresh test run during this source reconciliation.

Recent commits add percentage item discounts with saved net-price returns, SKU/category/brand/alias/favourite metadata and checkout filters, dated customer CSV statements and dated receipt-name supplier statement viewing/CSV. Source and targeted tests exist for these additions, but current integrated and desktop acceptance evidence still needs to be recorded. Do not treat their old unchecked TODO entries as absence of code, or their source files as proof of merchant/hardware acceptance.

Exact scaled quantity arithmetic and parsing exist in the shared domain. **Fractional transaction integration is in progress**: arithmetic alone does not make checkout, receiving, returns, counts, historical snapshots or backups fractional-safe. Keep those acceptance items open until the integrated journey and migration checks pass.

Product CSV import remains atomic and add-only. Rendered desktop/isolated-offline checks and their limits are recorded in BUILD_STATUS.md; physical peripherals, Windows packaging and merchant studies remain outstanding. No whole functional specification section is complete.

## Status definitions

- **Complete:** the stated scope is implemented and verified. No functional specification section below is marked complete as a whole.
- **Partial:** concrete implementation exists, but material requirements in that section remain.
- **Not implemented:** no working implementation was identified for the requirement; design text or an unavailable button does not count.
- **External integration pending:** external services and their integration are absent. Local code near the workflow must not be described as the integration.
- **Documentation only:** the section defines scope, scenarios, or research rather than an executable feature.

Written code, passing unit tests, a rendered screenshot, hardware testing, merchant usability testing, and production authorization are separate evidence levels. For executed checks, consult [BUILD_STATUS.md](BUILD_STATUS.md); that file records multiple dated checkpoints, including 258 tests for historical reporting, and the limits of desktop visual verification. Later additions need an updated integrated checkpoint.

## Full specification coverage

| Specification section | Status | Concrete source present | Material remaining scope |
|---|---|---|---|
| **1. Product definition** | Partial | Offline desktop app, SQLite adapter, common domain/theme/components | Full business-management scope, live synchronization, production readiness |
| **2. Businesses and users** | Partial | Multiple local business workspaces with tenant IDs, owner passwords, local staff credentials and owner/manager/cashier roles | Online memberships, fine-grained delegated permissions, validated support for each trade |
| **3. Onboarding and configuration** | Partial | Create shop/name/password; unlock/switch; add first items; isolated preview; newer restore entry | Guided import/migration, business/contact/tax settings, branches/register setup, configurable modules/payment methods, validated opening balances |
| **4. Product catalogue** | Partial | Product name/unit/price/stock, item creation and barcode search; add-only CSV preview/import; edit/archive/restore and price history; recent SKU/category/brand/alias/favourite metadata editor/history, checkout search/category/favourite filters and CSV snapshots | Current metadata/filter acceptance evidence; variants, descriptions/images, non-stock/service items, subcategories/automatic frequent items, label printing, bulk edits/update imports and hardware scanner validation |
| **5. Units, packaging, conversions** | Partial | One base stock unit; immutable named buying/selling packages, per-pack prices/barcodes, mixed-unit checkout and sale/return conversion snapshots; exact scaled quantity arithmetic/parser foundation | In-progress fractional product precision, persistence and checkout/receiving/count/return integration, legacy migration and historical rounding; nested conversions, repacking and explicit conversion reconciliation |
| **6. Pricing, negotiation, promotions** | Partial | Stored base/pack prices; reasoned lower unit prices with immutable snapshots, net-price cash refunds and draft recovery; recent per-selling-unit percentage discount calculation/UI/saved reduction; price edits preserve change history | Current percentage journey acceptance evidence; retail/wholesale/customer price lists, unit pricing tiers, configurable discount approvals, whole-basket discounts, promotions, bundles, floor/margin rules and bulk repricing |
| **7. Checkout and sales** | Partial | Search/select goods, integer quantity controls, durable active and named held baskets with tender/customer recovery, barcode + Enter lookup, cash and named-customer part-credit, exact money/change, operation retry protection | Fractional integration and current percentage/catalogue acceptance; manual unlisted items, frozen price snapshots for held sales, variants, whole-basket discounts and tax, split electronic methods, reservations/fulfilment, backdating |
| **8. Payments, verification, settlement** | Partial; external integration pending | Cash recording, applied-cash versus change separation, explicit outstanding credit | Transfer/card recording flows, payment provider API/webhooks, status rechecks, partial allocations across invoices, overpayments/change owed, reversals, disputes, settlement/fee matching, statement imports |
| **9. Returns, exchanges, corrections** | Partial | Linked cash-sale item returns, remaining returnable quantities, reason, optional restock, cash refund/drawer entries, preserved original sale | Credit-sale returns, exchanges, no-receipt policy/approvals, refund to electronic method/store credit, quarantine/condition categories, cross-device return risk and granular permissions |
| **10. Customers and credit** | Partial | Customer names/debt totals, explicit credit sale, cash repayment, duplicate-retry protection, searchable customer history and reconciled CSV statements; contact editing, enforced credit limits, agreed due dates, explicit repayment allocation with reasoned append-only undo and explicit reassignment, seven current-balance ageing groups and CSV, and recorded-date historical debt/ageing with configurable report bands when assignments are complete; recent dated customer CSV with opening/activity/closing debt | Current dated-statement acceptance evidence, persistent shop ageing defaults/historical profile snapshots, granular correction approvals, advances, disputes, write-offs, merge, PDF statements/delivery, reminders, merchant-owed balances |
| **11. Suppliers and purchasing** | Partial | Buying-cost receipt, named buying packages with preserved base-stock conversion, supplier name, cash paid, unpaid receipt balance; outstanding-by-receipt and cash supplier payments; recent exact-recorded-name dated statement viewing/CSV with opening/closing debt | Current statement acceptance evidence; supplier master profiles/contact/terms, purchase orders/approvals, multiple-line/partial deliveries, landed-cost allocation, returns/credits, full master-account statements and supplier-account adjustments |
| **12. Inventory operations** | Partial | Opening quantity, transactional sale deduction, stock receiving/history; newer optional return restock; reasoned single-item stock counts with previous/count/difference history and stale-count rejection; source-linked movement history and configurable low-stock alerts | Legacy missing opening history, reservations/in-transit, multi-item stock counts/cutoffs/variance approvals, locations/warehouses, lots/expiry/serials, costing/valuation, stock ageing and advanced reorder rules |
| **13. Cash drawer and shifts** | Partial | One active cash shift per shop, opening count, linked cash activity, expenses/manual movements, expected/count/difference and immutable close | Per-register/per-cashier drawers, denomination sheets, blind count, handover/sign-off, custody transfers, reopen/correct periods, late-sync effects and permissions |
| **14. Expenses and bookkeeping** | Partial | Cash expenses/category/description, reasoned cash in/out, separation from stock receipt amounts; newer period totals | Accrued expenses, recurrence, attachments, bank/owner-funded payment sources, explicit contributions/drawings accounts, consistent COGS, chart of accounts, trial balance, balance sheet/profit and period controls |
| **15. Receipts and documents** | Partial | Original sale/return text receipt; saved receipt detail; UTF-8 text export; OS printer dialog/submission service | Verified thermal layouts/58–80 mm hardware, branding/contact/tax fields, PDF/share/email, quotations/invoices/delivery notes/credit notes, historical tender/change persistence, receipt numbering policy, verification links |
| **16. Orders, delivery, channels** | Not implemented; integrations pending | Current unfinished basket is a local draft, not an order-management system | Orders/deposits/layaway, reservation expiry, fulfilment/backorders, delivery/dispatch/proof, source channels, storefront/payment links, channel stock buffers and integrations |
| **17. Staff, approvals, audit** | Partial | Owner/staff password sign-in, local role-restricted navigation, repository mutation checks, persistent failed-login throttling and revocable/idle-expiring staff sessions; stable IDs, timestamps, linked financial events; new business-event actor snapshots, searchable activity screen, dated CSV and owner-only staff-change history | Short PIN unlock, online memberships, granular permissions, local/remote approval, broader platform/admin audit coverage, commission rules, security review |
| **18. Registers, branches, warehouses** | Partial | Independent local businesses; tenant-scoped reads/writes and isolated draft files | Multiple locations under one tenant, enrolled devices/registers, branch pricing/stock/permissions, transfer custody, consolidated reporting, LAN/cloud device exchange, disconnected credit/refund rules |
| **19. Reports and analytics** | Partial | WAT date-range summaries for sales/cash/credit/repayment/refund/purchases/expenses; historical credit/ageing bands, product performance and staff recording-activity CSV; recent dated customer/supplier statements | Current statement acceptance evidence; historical COGS/profit, category and broader staff performance/commissions, shrinkage/valuation, supplier performance, settlements, comparisons/charts/custom reports, consolidated completeness/freshness views |
| **20. Retention and communications** | Not implemented; external integration pending | No loyalty or message transport | Points/stamps, redemption conflict controls, segments, consent/opt-out, reminders/campaigns, queue/delivery/retry status, Telegram-first and other approved messaging APIs |
| **21. Nigerian localization/usability** | Partial | NGN/kobo formatting and input, Nigerian-format phone normalization, WAT date display, plain trading labels, bundled fonts, reusable readable components | Broader address/phone support, regional language translations, measured low-spec performance, full accessibility/keyboard/touch/scanner workflows, configurable density, practice help/tutorial experience, merchant validation |
| **22. Sector modules** | Not implemented | Generic items can represent goods but do not implement specialist workflows | Beverage returnable-container/deposit ledger, weight/custom measure conversions, fashion variants, serial/warranty/repair, contractor dispatch, restaurant recipe/kitchen/table, specialist pharmacy workflows |
| **23. Tax/privacy/compliance** | Not implemented; fiscal integration pending | General local validation/password controls are not a tax/privacy compliance system | Tax treatment/rate history, inclusive/exclusive calculation, tax invoices/summaries, applicable fiscal connector, privacy notices/rights/retention, approved payment integration and sector review |
| **24. Devices and hardware** | Partial | JVM desktop/Compose source, fullscreen/window controls; newer OS print-service path | Windows build/install/upgrade verification, hardware compatibility list, scanners/cash drawers/scales/customer displays, terminal adapters, Android/iOS apps and tested adapters |
| **25. Sync/conflicts/integrity** | Partial; online transport pending | Durable SQLite transactions/WAL, tenant outbox entries, active/held-draft persistence and atomic transitions, operation IDs/deduplication for several mutations | Upload/download/acknowledgement, authenticated sync API, conflict resolution, retry scheduler, attachment transfer, per-device cursors/clocks/schema migration policy, stale authorization/device alerts, LAN sync and convergence |
| **26. Backup/security/recovery** | Partial | Owner password-derived credentials, tenant-aware draft storage; newer password-encrypted selected-shop backup and restore-as-new-workspace | Encrypted working DB/drafts, automatic/versioned/online backups, MFA/remote revoke/session security, robust failure recovery and security review, OS key protection, credentials lifecycle and device authorization |
| **27. Support/product operations** | Partial | Local errors and status language; preview/run/build instructions | In-app offline help, support tickets/consented diagnostics, peripheral diagnostics, application updater, subscriptions/grace policy, data-rights/account-closure workflow |
| **28. Integrations/extensibility** | External integration pending | JVM local adapters and common domain boundaries, not connected external services | Payment, Telegram-first and other approved messaging, fiscal, accountant connectors, e-commerce/logistics, authenticated integration APIs, webhook/outbound event logs and retry policy |
| **29. Offline availability matrix** | Partial | Local goods, cash/credit sales, debt repayment, stock receipts, cash shifts and expenses; implemented output/backup subsets | Every matrix row must be checked against actual code; remote records, approvals, provider verification, messaging, fiscal work and cloud backup are unavailable |
| **30. Acceptance scenarios** | Partial | Domain/repository/security/draft tests; newer output/backup/after-sales tests written | Full scenario coverage, real end-to-end UI automation, multi-device conflicts, fractional and advanced scenarios, Windows hardware/accessibility, merchant study |
| **31. Boundaries/open decisions** | Documentation only | Scope boundaries and open architecture choices are recorded | Keep decisions current; settle sync, mobile adapters, costing, actual hardware and rollout. Recorded intent is not implemented capability |
| **32. Research references** | Documentation only | Sources linked in specification and design guide | Revalidate APIs/legal applicability and integration access when implementing those features; no claim of fresh regulatory audit here |

## Evidence map

These links point to implementation, not proof that every path has passed a test or been visually reviewed.

| Evidence | Source |
|---|---|
| Common tenant/product/customer/sale models, checked totals, checkout validation, money formatting | [Models.kt](shared/src/commonMain/kotlin/pos/domain/Models.kt) |
| Exact quantity arithmetic/parser foundation; transaction integration tracked separately | [Quantity.kt](shared/src/commonMain/kotlin/pos/domain/Quantity.kt) |
| Per-selling-unit percentage discount calculation | [PercentageDiscount.kt](shared/src/commonMain/kotlin/pos/domain/PercentageDiscount.kt), [SellScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/SellScreen.kt) |
| Catalogue metadata validation/search/history | [ProductMetadata.kt](shared/src/commonMain/kotlin/pos/domain/ProductMetadata.kt), [ProductManagementPanel.kt](desktopApp/src/jvmMain/kotlin/pos/screens/ProductManagementPanel.kt) |
| Common exact-naira input parser | [AmountInput.kt](shared/src/commonMain/kotlin/pos/domain/AmountInput.kt) |
| Common stock-receipt, expense, cash-shift/entry models | [ShopOperations.kt](shared/src/commonMain/kotlin/pos/domain/ShopOperations.kt) |
| Immutable buying units and receipt conversion snapshots | [PurchaseUnit.kt](shared/src/commonMain/kotlin/pos/domain/PurchaseUnit.kt), [ShopOperations.kt](shared/src/commonMain/kotlin/pos/domain/ShopOperations.kt) |
| Common return/refund and supplier-payment models | [AfterSales.kt](shared/src/commonMain/kotlin/pos/domain/AfterSales.kt) |
| Local schema, tenant query scope, transactions, retry checks, stock/cash/debt/supplier/return and snapshot persistence | [PosRepository.kt](desktopApp/src/jvmMain/kotlin/pos/data/PosRepository.kt) |
| Tenant-specific active and held basket file | [DraftStore.kt](desktopApp/src/jvmMain/kotlin/pos/data/DraftStore.kt) |
| Local owner credential gate | [WorkspaceAccess.kt](desktopApp/src/jvmMain/kotlin/pos/security/WorkspaceAccess.kt) |
| Workspace picker, tenant switch and routes | [Main.kt](desktopApp/src/jvmMain/kotlin/pos/Main.kt) |
| Checkout and active/held-draft recovery and barcode entry | [SellScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/SellScreen.kt) |
| Item creation and stock receipt workflows | [StockScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/StockScreen.kt) |
| Cash shifts, manual money movement, expenses | [MoneyScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/MoneyScreen.kt) |
| New saved-receipt output and cash return workflow | [SalesScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/SalesScreen.kt) |
| Searchable customer history and repayments | [CustomerScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/CustomerScreen.kt) |
| Customer credit limits, due-date validation and profile history | [CustomerCredit.kt](shared/src/commonMain/kotlin/pos/domain/CustomerCredit.kt) |
| Original due-date review without inferred invoice allocations | [DebtAgeingService.kt](desktopApp/src/jvmMain/kotlin/pos/output/DebtAgeingService.kt), [CreditReviewScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/CreditReviewScreen.kt) |
| Dated customer CSV and receipt-name supplier statements | [DatedCustomerStatementService.kt](desktopApp/src/jvmMain/kotlin/pos/output/DatedCustomerStatementService.kt), [SupplierStatementService.kt](desktopApp/src/jvmMain/kotlin/pos/output/SupplierStatementService.kt) |
| Historical credit/configurable ageing and product/staff exports | [HistoricalCreditService.kt](desktopApp/src/jvmMain/kotlin/pos/output/HistoricalCreditService.kt), [ConfigurableAgeingService.kt](desktopApp/src/jvmMain/kotlin/pos/output/ConfigurableAgeingService.kt), [ProductPerformanceService.kt](desktopApp/src/jvmMain/kotlin/pos/output/ProductPerformanceService.kt), [StaffPerformanceService.kt](desktopApp/src/jvmMain/kotlin/pos/output/StaffPerformanceService.kt) |
| Validated customer CSV statements | [CustomerStatementService.kt](desktopApp/src/jvmMain/kotlin/pos/output/CustomerStatementService.kt) |
| Catalogue edits, barcode/archive controls, counts and history | [ProductManagementPanel.kt](desktopApp/src/jvmMain/kotlin/pos/screens/ProductManagementPanel.kt) |
| New supplier receipt balances and payment workflow | [SuppliersScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/SuppliersScreen.kt) |
| New report date range, summary amounts, export selection | [ReportsScreen.kt](desktopApp/src/jvmMain/kotlin/pos/screens/ReportsScreen.kt) |
| New quoted/formula-neutralized CSV and selected-tenant scope | [CsvService.kt](desktopApp/src/jvmMain/kotlin/pos/output/CsvService.kt) |
| New receipt text, local export, system printing | [ReceiptService.kt](desktopApp/src/jvmMain/kotlin/pos/output/ReceiptService.kt) |
| New authenticated-encryption backup format and decoding bounds | [BackupService.kt](desktopApp/src/jvmMain/kotlin/pos/output/BackupService.kt) |
| New backup/restore UI and new owner credential provisioning | [BackupScreens.kt](desktopApp/src/jvmMain/kotlin/pos/screens/BackupScreens.kt) |
| Shared visual primitives | [PosComponents.kt](shared/src/commonMain/kotlin/pos/ui/components/PosComponents.kt), [PosTheme.kt](shared/src/commonMain/kotlin/pos/ui/theme/PosTheme.kt) |
| Shared target actually configured today | [shared/build.gradle.kts](shared/build.gradle.kts) |

## Important distinctions

### Local tenant separation is not a finished multi-user SaaS

Business queries/mutations carry a tenant ID and composite relationships. The UI owner password gate chooses which local workspace to enter. Local staff sessions enforce role permissions in the running application. That does not establish authenticated server-side tenant memberships or isolation from someone who can read/edit the workstation's SQLite files.

The working database is not encrypted. The repository is a local adapter, not a network authorization service. Local staff credentials live separately from business backups. No online identity provider, Heroku backend, deployment, sync transport, or remote staff access was identified. The app must not be advertised as a production multi-user online service yet.

### An outbox is not synchronization or backup

Pending changes are recorded locally. No transport acknowledges them remotely. The online status must remain explicitly disconnected; a queue count or internet connection does not prove a remote copy exists.

The new encrypted backup is a separate manually saved file. It covers the selected tenant's saved business records; it excludes owner credentials, unfinished basket files and device settings. Restore creates another tenant rather than overwriting an existing shop. Automated tests cover tampering, wrong passwords, malformed input, tenant boundaries, ledger/debt consistency and repeated restore. Recovery on independent machines and broader security review remain outstanding.

### New output has narrow semantics

- OS print submission does not prove paper delivery or support for a particular thermal printer. Windows/system-dialog and real hardware verification remain required.
- A text receipt is not a tax invoice, a PDF/share integration, or bank-payment verification.
- CSV exports are local, selected-tenant datasets. Current product/customer snapshots are explicitly distinct from date-filtered historical transactions.
- Supplier balances/payments are tied to received-stock records and supplier-name strings, not a complete supplier master/credit-note/statement system.
- Returns currently cover original cash-sale items and cash refunds, with an optional sellable restock flag. They do not cover the full exchange, credit-return, payment-provider, quarantine, or approval scope.
- Reports deliberately do not calculate profit because historical COGS is incomplete. Do not present sales minus stock purchases as profit.

### Shared code is prepared, not shipped on every platform

Domain/UI sources live under commonMain, but only a JVM target is currently configured. Android/iOS builds, platform persistence, secure storage, printing, and hardware adapters remain future implementation. SQLite JDBC is desktop-specific; using common source folders does not make it a Room Multiplatform implementation.

## Prioritized remaining implementation work

The dependency-ordered execution plan and comprehensive task checklist are maintained in IMPLEMENTATION_TODO.md. The current order is:

1. **Finish the current local batch:** complete fractional transactions, exact historical rounding, migrations, draft/backup/import/export compatibility; verify recent percentage discounts, catalogue filters and dated statements with an integrated test run and actual desktop/keyboard/offline checks. Preserve pre-existing unrelated working changes.
2. **Close daily retail gaps:** variants/photos/labels/bulk edits, basket pricing/promotions, supplier/customer corrections and profiles, multi-item count sessions and inventory dispositions. Each slice includes permissions, retry safety, audit history and failure behavior.
3. **Establish costing and custody:** choose costing/backdating policy before valuation/COGS/profit; add per-register/cashier accountability, denomination/blind counts and handovers; then journal/accountant reporting with reconciled source events.
4. **Harden identity and recovery:** PINs, granular approvals, credential recovery, working-data encryption/keys, interruption recovery, versioned backups and reviewed diagnostics/security.
5. **Build online multi-tenancy and synchronization:** backend accounts/memberships, Postgres, authenticated idempotent APIs, device enrollment/cursors/retries, movement-based inventory and documented offline conflict rules. Test duplicate delivery and disconnected-device convergence before claiming sync.
6. **Add external payments and communications:** explicit manual/unverified states and split tender; Paystack verification/webhooks/rechecks/settlement; consent-linked Telegram-first messaging. Actual credentials, secure staging and provider checks remain required; adapter tests cannot prove live integration.
7. **Complete orders, branches, platforms and trade modules:** fulfilment/deposits, stock transfer custody, Android/iOS/web, loyalty, fiscal/tax and applicable specialist workflows. Specialist applicability review, signing/device access and field evidence remain dependencies.
8. **Validate release operations:** Windows upgrades/peripherals/scaling, complete keyboard/touch/screen readers, low-spec/large-catalogue performance, independent-machine restore and merchant first-glance/task studies; then deployment/support/updater/subscription recovery checks.

This ordering preserves the full specification. Scope is not complete because a phase has an initial model or a passing unit test. External account access, hosted resources, physical devices, reviewed applicable requirements and merchant participants must be supplied or arranged before their dependent acceptance can finish. Do not invent evidence or expose fake-success controls while waiting.

Persistent remaining-work checklist: [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md). Paystack, Telegram-first messaging and new Heroku setup decisions: [PROVIDER_DECISIONS.md](PROVIDER_DECISIONS.md). Provider connectivity is not implemented.
