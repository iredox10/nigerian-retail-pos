# Retail POS implementation status

Source audit: 13 November 2026.

This document compares the **entire** [feature specification](FEATURE_SPECIFICATION.md) with the source currently present. It is not a claim that the desktop application, long-term specification, or multi-tenant SaaS is complete.

The current product is a local Kotlin/Compose desktop foundation. Tenant-scoped shop records, owner password gates, basic cash/credit sales, stock receiving, cash shifts, and reusable common code exist. A newer batch adds returns, supplier payments, date-filtered reports/CSV, receipt output, and encrypted backup/recovery. The integrated build passes 204 automated tests, including catalogue changes, held-sale recovery, customer statements, buying-package conversions and customer credit controls. Rendered desktop checks and packaging evidence are recorded in BUILD_STATUS.md; hardware and merchant checks remain outstanding. Product CSV import now includes a parsed preview and an atomic add-only save.

## Status definitions

- **Complete:** the stated scope is implemented and verified. No functional specification section below is marked complete as a whole.
- **Partial:** concrete implementation exists, but material requirements in that section remain.
- **Not implemented:** no working implementation was identified for the requirement; design text or an unavailable button does not count.
- **External integration pending:** external services and their integration are absent. Local code near the workflow must not be described as the integration.
- **Documentation only:** the section defines scope, scenarios, or research rather than an executable feature.

Written code, passing unit tests, a rendered screenshot, hardware testing, merchant usability testing, and production authorization are separate evidence levels. For executed checks, consult [BUILD_STATUS.md](BUILD_STATUS.md); that file records the integrated 204-test checkpoint and the limits of desktop visual verification.

## Full specification coverage

| Specification section | Status | Concrete source present | Material remaining scope |
|---|---|---|---|
| **1. Product definition** | Partial | Offline desktop app, SQLite adapter, common domain/theme/components | Full business-management scope, live synchronization, production readiness |
| **2. Businesses and users** | Partial | Multiple local business workspaces with tenant IDs and separate owner passwords | Staff identities, memberships, role hierarchy, delegated permissions, validated support for each trade |
| **3. Onboarding and configuration** | Partial | Create shop/name/password; unlock/switch; add first items; isolated preview; newer restore entry | Guided import/migration, business/contact/tax settings, branches/register setup, configurable modules/payment methods, validated opening balances |
| **4. Product catalogue** | Partial | Product name, one unit, price, stock, item creation, name/unit/barcode search; atomic add-only CSV import with preview; barcode assignment, edit/archive/restore and price history | SKU/categories/brands, aliases, variants, images, label printing, bulk edits/update imports and hardware scanner validation |
| **5. Units, packaging, conversions** | Partial | One base stock unit; immutable named buying/selling packages, per-pack prices/barcodes, mixed-unit checkout and sale/return conversion snapshots | Fractional sales/receiving, repacking and explicit conversion reconciliation |
| **6. Pricing, negotiation, promotions** | Partial | Stored base/pack prices; reasoned lower unit prices with immutable catalogue/discount snapshots, net-price cash refunds and draft recovery; price edits preserve change history | Retail/wholesale/customer price lists, unit pricing tiers, staff discount permissions, percentage/basket discounts, promotions, bundles, floor/margin rules and bulk repricing |
| **7. Checkout and sales** | Partial | Search/select goods, integer quantity controls, durable active and named held baskets with tender/customer recovery, barcode + Enter lookup, cash and named-customer part-credit, exact money/change, operation retry protection | Manual unlisted items, frozen price snapshots for held sales, variants, basket/percentage discounts and tax, split electronic methods, reservations/fulfilment, backdating |
| **8. Payments, verification, settlement** | Partial; external integration pending | Cash recording, applied-cash versus change separation, explicit outstanding credit | Transfer/card recording flows, payment provider API/webhooks, status rechecks, partial allocations across invoices, overpayments/change owed, reversals, disputes, settlement/fee matching, statement imports |
| **9. Returns, exchanges, corrections** | Partial | Linked cash-sale item returns, remaining returnable quantities, reason, optional restock, cash refund/drawer entries, preserved original sale | Credit-sale returns, exchanges, no-receipt policy/approvals, refund to electronic method/store credit, quarantine/condition categories, cross-device return risk and granular permissions |
| **10. Customers and credit** | Partial | Customer names/debt totals, explicit credit sale, cash repayment, duplicate-retry protection, searchable customer history and reconciled CSV statements; contact editing, enforced credit limits, agreed due dates, explicit repayment allocation with reasoned append-only undo and explicit reassignment, seven current-balance ageing groups and CSV, and exact recorded-date overdue totals when all payments are assigned | Staff correction approvals, historical as-of ageing, advances, disputes, write-offs, merge, dated/PDF statements, reminders, merchant-owed balances |
| **11. Suppliers and purchasing** | Partial | Buying-cost receipt, named buying packages with preserved base-stock conversion, supplier name, cash paid, unpaid receipt balance; outstanding-by-receipt and cash supplier payments | Supplier master profiles/contact/terms, purchase orders/approvals, multiple-line/partial deliveries, landed-cost allocation, returns/credits, full statements and supplier-account adjustments |
| **12. Inventory operations** | Partial | Opening quantity, transactional sale deduction, stock receiving/history; newer optional return restock; reasoned single-item stock counts with previous/count/difference history and stale-count rejection; source-linked movement history and configurable low-stock alerts | Legacy missing opening history, reservations/in-transit, multi-item stock counts/cutoffs/variance approvals, locations/warehouses, lots/expiry/serials, costing/valuation, stock ageing and advanced reorder rules |
| **13. Cash drawer and shifts** | Partial | One active cash shift per shop, opening count, linked cash activity, expenses/manual movements, expected/count/difference and immutable close | Per-register/per-cashier drawers, denomination sheets, blind count, handover/sign-off, custody transfers, reopen/correct periods, late-sync effects and permissions |
| **14. Expenses and bookkeeping** | Partial | Cash expenses/category/description, reasoned cash in/out, separation from stock receipt amounts; newer period totals | Accrued expenses, recurrence, attachments, bank/owner-funded payment sources, explicit contributions/drawings accounts, consistent COGS, chart of accounts, trial balance, balance sheet/profit and period controls |
| **15. Receipts and documents** | Partial | Original sale/return text receipt; saved receipt detail; UTF-8 text export; OS printer dialog/submission service | Verified thermal layouts/58–80 mm hardware, branding/contact/tax fields, PDF/share/email, quotations/invoices/delivery notes/credit notes, historical tender/change persistence, receipt numbering policy, verification links |
| **16. Orders, delivery, channels** | Not implemented; integrations pending | Current unfinished basket is a local draft, not an order-management system | Orders/deposits/layaway, reservation expiry, fulfilment/backorders, delivery/dispatch/proof, source channels, storefront/payment links, channel stock buffers and integrations |
| **17. Staff, approvals, audit** | Partial | Owner workspace password gate; stable IDs, timestamps, linked financial events | Staff accounts/PINs, memberships/roles, permission enforcement, local/remote approval, actor attribution for all changes, authorization expiry, commission rules, audit UI/security review |
| **18. Registers, branches, warehouses** | Partial | Independent local businesses; tenant-scoped reads/writes and isolated draft files | Multiple locations under one tenant, enrolled devices/registers, branch pricing/stock/permissions, transfer custody, consolidated reporting, LAN/cloud device exchange, disconnected credit/refund rules |
| **19. Reports and analytics** | Partial | WAT-inclusive date-range summaries for sales/cash/credit/repayment/refund/purchases/expenses; current debt explicitly labeled; selected CSV exports | Historical COGS/profit, product/category/staff performance, ageing/shrinkage, valuation, supplier statement exports, settlement reports, comparisons/charts/custom reports, consolidated completeness/freshness views |
| **20. Retention and communications** | Not implemented; external integration pending | No loyalty or message transport | Points/stamps, redemption conflict controls, segments, consent/opt-out, reminders/campaigns, queue/delivery/retry status, SMS/email/WhatsApp APIs |
| **21. Nigerian localization/usability** | Partial | NGN/kobo formatting and input, Nigerian-format phone normalization, WAT date display, plain trading labels, bundled fonts, reusable readable components | Broader address/phone support, regional language translations, measured low-spec performance, full accessibility/keyboard/touch/scanner workflows, configurable density, practice help/tutorial experience, merchant validation |
| **22. Sector modules** | Not implemented | Generic items can represent goods but do not implement specialist workflows | Beverage returnable-container/deposit ledger, weight/custom measure conversions, fashion variants, serial/warranty/repair, contractor dispatch, restaurant recipe/kitchen/table, specialist pharmacy workflows |
| **23. Tax/privacy/compliance** | Not implemented; fiscal integration pending | General local validation/password controls are not a tax/privacy compliance system | Tax treatment/rate history, inclusive/exclusive calculation, tax invoices/summaries, applicable fiscal connector, privacy notices/rights/retention, approved payment integration and sector review |
| **24. Devices and hardware** | Partial | JVM desktop/Compose source, fullscreen/window controls; newer OS print-service path | Windows build/install/upgrade verification, hardware compatibility list, scanners/cash drawers/scales/customer displays, terminal adapters, Android/iOS apps and tested adapters |
| **25. Sync/conflicts/integrity** | Partial; online transport pending | Durable SQLite transactions/WAL, tenant outbox entries, active/held-draft persistence and atomic transitions, operation IDs/deduplication for several mutations | Upload/download/acknowledgement, authenticated sync API, conflict resolution, retry scheduler, attachment transfer, per-device cursors/clocks/schema migration policy, stale authorization/device alerts, LAN sync and convergence |
| **26. Backup/security/recovery** | Partial | Owner password-derived credentials, tenant-aware draft storage; newer password-encrypted selected-shop backup and restore-as-new-workspace | Encrypted working DB/drafts, automatic/versioned/online backups, MFA/remote revoke/session security, robust failure recovery and security review, OS key protection, credentials lifecycle and device authorization |
| **27. Support/product operations** | Partial | Local errors and status language; preview/run/build instructions | In-app offline help, support tickets/consented diagnostics, peripheral diagnostics, application updater, subscriptions/grace policy, data-rights/account-closure workflow |
| **28. Integrations/extensibility** | External integration pending | JVM local adapters and common domain boundaries, not connected external services | Payment, SMS/email/WhatsApp, fiscal, accountant connectors, e-commerce/logistics, authenticated integration APIs, webhook/outbound event logs and retry policy |
| **29. Offline availability matrix** | Partial | Local goods, cash/credit sales, debt repayment, stock receipts, cash shifts and expenses; implemented output/backup subsets | Every matrix row must be checked against actual code; remote records, approvals, provider verification, messaging, fiscal work and cloud backup are unavailable |
| **30. Acceptance scenarios** | Partial | Domain/repository/security/draft tests; newer output/backup/after-sales tests written | Full scenario coverage, real end-to-end UI automation, multi-device conflicts, fractional and advanced scenarios, Windows hardware/accessibility, merchant study |
| **31. Boundaries/open decisions** | Documentation only | Scope boundaries and open architecture choices are recorded | Keep decisions current; settle sync, mobile adapters, costing, actual hardware and rollout. Recorded intent is not implemented capability |
| **32. Research references** | Documentation only | Sources linked in specification and design guide | Revalidate APIs/legal applicability and integration access when implementing those features; no claim of fresh regulatory audit here |

## Evidence map

These links point to implementation, not proof that every path has passed a test or been visually reviewed.

| Evidence | Source |
|---|---|
| Common tenant/product/customer/sale models, checked totals, checkout validation, money formatting | [Models.kt](shared/src/commonMain/kotlin/pos/domain/Models.kt) |
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

Business queries/mutations carry a tenant ID and composite relationships. The UI owner password gate chooses which local workspace to enter. That does not establish authenticated server-side tenant memberships, staff authorization, or isolation from someone who can read/edit the workstation's SQLite files.

The working database is not encrypted. The repository is a local adapter, not a network authorization service. No online identity provider, Heroku backend, deployment, sync transport, or remote staff access was identified. The app must not be advertised as a production multi-user online service yet.

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

1. **Extend verification of integrated local features:** complete receipt/return/supplier/report/backup UI journeys, small-window behavior, real printer output, recovery on another machine and failure-state interaction checks. The current integrated code passes 204 automated tests.
2. **Close daily retail gaps:** fractional quantity representation; staff pricing permissions, percentage/basket discounts and promotions; categories/variants and bulk catalogue edits; staff correction approvals and historical ageing; full stock-count sessions, advanced replenishment and valuation.
3. **Finish local identity and responsibility:** staff accounts/roles, actor audit, per-register cash custody, access revocation/expiry, local data encryption, reviewed recovery/credential lifecycle.
4. **Design and build online multi-tenancy:** backend authentication/memberships/authorization, Postgres schema, authenticated idempotent command/sync API, per-device reconciliation/conflict rules, background transport and a verified Heroku deployment.
5. **Add external payments deliberately:** licensed partner access, manual versus verified states, provider attempts/webhooks/rechecks, settlement/refund/dispute controls; then messages/fiscal connectors as applicable.
6. **Complete business management and modules:** costing/profit, purchasing/supplier accounts, orders/fulfilment, branches/warehouses, sector extensions, advanced reports/loyalty.
7. **Validate production experience:** Windows packaging and peripherals, keyboard/scanner/touch/screen readers, low-spec performance, backup restore drills, error-state visual QA, merchant first-glance and task studies.

This ordering is a practical dependency proposal, not an agreement to remove or postpone the rest of the feature specification indefinitely. Update this audit alongside implementation evidence; do not mark a whole section complete when only its happy path exists.
