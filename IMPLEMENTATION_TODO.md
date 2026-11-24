# Complete implementation TODO

Authority: FEATURE_SPECIFICATION.md (all 32 sections). Coverage and evidence: IMPLEMENTATION_STATUS.md and BUILD_STATUS.md.
Provider decisions: [PROVIDER_DECISIONS.md](PROVIDER_DECISIONS.md). Paystack selected; Telegram first; Heroku backend provisioned on 24 November 2026; see cloud/CONNECTION_GUIDE.md.

Reconciled against source and recent commits on 14 November 2026. Use five specialist agents in waves within the available concurrency limit, integrate their work, and make small, explicitly scoped commits. An agent task is not a completion claim.

A checked box means that specific item has implementation and recorded verification. It does not mark its whole module production-ready. Keep unfinished subtasks unchecked. External prerequisites are dependencies, not completed features. Do not invent credentials, successful integrations, merchant studies or hardware results.

24 November update: initial desktop/Android cloud connection and Heroku/Postgres deployment are implemented. See the latest BUILD_STATUS.md and cloud/IMPLEMENTATION_PLAN.md for exact verification and outstanding rollout acceptance. The older dated batches below retain their historical scope.

## Current batch and evidence rules

The current integrated batch passes **424 tests** (56 shared + 368 desktop), zero failures, errors or skips. The Linux distributable was rebuilt. Basket discounts, supplier profiles, multi-item counts, Code128 labels, receipt settings/PDFs, statement PDFs, purchase orders/partial deliveries and classified physical stock removals are integrated. Selected native offline and rendered PDF checks are recorded in BUILD_STATUS.md. The coordinating agent performed an isolated Linux desktop check with no external network routes: fractional quantity edits blocked payment until applied, keyboard Tab/Return applied the edit, a discounted 1.375 kg sale completed with correct change, and a separate 0.375 kg cash return restored stock. A denomination closing check saved 9 × ₦1,000 against ₦10,000 expected and retained the ₦1,000 shortage and sheet in Past shifts. Offline Help rendered, and the rebuilt installed binary passed direct practice-launch readiness, duplicate suppression and child termination checks and a subsequent isolated cashier check configured a PIN, unlocked with it, and launched practice through Help. These targeted checks do not establish every keyboard journey, Windows/hardware support or merchant usability. See BUILD_STATUS.md and IMPLEMENTATION_PLAN.md for evidence and limits.

- [x] Finish the current exact fractional-quantity service/persistence journey, including receive/sell/count/return, historical snapshots, drafts, CSV and encrypted backups; integrated tests and targeted actual offline desktop checks pass.
- [x] Integrate and test percentage item discounts, catalogue metadata/filters and dated customer/supplier statement services. Their core implementation is present; broader UI acceptance remains separate.
- [x] Add and test atomic opening/closing denomination sheets, local six-digit staff PIN policy and searchable offline help with a separate sample process.
- [x] Integrate the five local first-wave slices: whole-basket discounts; stable supplier profiles/accounts; multi-item count sessions; Code128 label output; shop/contact settings and branded PDF receipts. The 389-test run covers their integrated local implementation.
- [x] Add dated customer and stable-ID supplier statement PDFs using reconciled existing histories; 12 targeted PDF tests pass, including receipt compatibility.
- [ ] Complete full rendered/keyboard/permission/failure acceptance for recent local journeys, including native PDF dialogs; retain the distinction between targeted checks and full acceptance.
- [x] Integrate purchase orders/partial delivery and classified stock removal; 424-test full suite and selected native offline journeys pass.
- [ ] Preserve and review pre-existing uncommitted test/artifact changes separately; never absorb unrelated changes into a feature commit.

## Dependency-ordered execution plan

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the coordinating agent's current assignments, commit sequence and verification log.

Work is ordered by dependencies, not by hiding any part of the full specification. Each phase uses small working journeys with repository/domain tests, migration and retry checks where relevant, an actual desktop render for changed UI, and truthful evidence in BUILD_STATUS.md. Keep a feature unavailable until its persistence and failure behavior work.

1. **Local feature batch — core integrated; broader acceptance remains.** Exact quantities, historical snapshots, checkout/receiving/counts/returns/drafts/backup/import/export, percentage discounts, catalogue and dated statement services are integrated and tested. Retain pending full UI/failure/keyboard journeys and independent-machine migration/recovery checks; do not reopen completed core work or declare broad acceptance from the targeted renders.
2. **Close daily local retail gaps.** Extend the implemented Code128 labels, basket discounts, stable supplier profiles and count sessions into the remaining catalogue variants/images/bulk updates, price lists/promotions, customer/supplier corrections and inventory dispositions. Each slice must preserve audit and unit semantics and pass tenant/permission/retry tests. Extend settings/document templates beyond the implemented shop branding and PDF receipts, and extend the existing offline help/practice guidance without expanding the primary navigation.
3. **Establish accounting and custody.** Select and document historical costing/backdating policy before valuation/COGS/profit. Extend the existing denomination sheets/count-entry hiding into cashier/register drawers, secure blind accountability, handovers and controlled period corrections, then source-linked journals and accountant reports. Never call purchase subtraction profit. Verify full stock/cash/debt/journal reconciliation with returns and corrections.
4. **Harden local identity, storage and recovery.** Extend the implemented local staff PIN policy with approval workflows, credential recovery, protected storage keys, working-data encryption, migration/interruption recovery, diagnostics and versioned backups. Verify denial leaves records unchanged, session revocation, bad keys/corruption/disk-full behavior and restore on another machine. Broader security assessment remains separate.
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
- [x] Local staff six-digit convenience PIN, bootstrapped with the staff full password, sharing failed-login cooldown and fixed 12-hour authorization expiry; clock/revocation/persistence paths have automated coverage.
- [ ] Broader PIN/device security assessment, biometric adapters and full keyboard/desktop acceptance; PINs do not encrypt working records.
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

- [x] Exact scaled fractional quantities with immutable per-product precision 0–6. Stock uses Long scaled units capped at 9,007,199,254,740,991 with exact projection checks; each transaction quantity is bounded by Int.MAX_VALUE scaled units.
- [x] Fractional checkout, stock receiving, cash returns, counts and historical precision/conversion snapshots with tested exact kobo rounding.
- [x] Legacy quantity migration and backward-compatible drafts/encrypted backups/CSV, including optional quantity_precision import. Legacy fractional precision-zero balances remain visible without silent conversion; only their whole portion is sellable until explicitly reconciled.
- [ ] Independent-machine restore/migration drills and comprehensive quantity UI/failure acceptance.
- [x] Explicit versioned source/output repacking recipes and reviewed whole batches with exact atomic stock movements/history.
- [ ] Nested package definitions, multi-input assembly and repacking costing/yield-loss reconciliation.
- [x] SKU/category/brand/alias editing, immutable metadata history, CSV snapshots and checkout search/category filters; integrated automated coverage passes.
- [ ] Complete rendered keyboard/failure acceptance for catalogue metadata/filter workflows.
- [x] Group separate catalogue items in variant families. Each item has its own stock, price, SKU and barcode. Search by the family or label in Sell and Stock.
- [ ] Structured size/colour options and matrix-based variant creation and editing.
- [x] Product photo and shop logo imports, bounded raster copies in SQLite, replacement/removal history, Stock/Sell/workspace rendering and encrypted backup/restore.
- [x] Historical receipt logo capture and first-page PDF rendering.
- [x] Product photo gallery with reasoned revision history, crop and rotation, and automatic JPEG orientation. Main photos and logos use the same adjustment controls.
- [x] Review and import main product photos in bulk by filename SKU or barcode, with atomic saves and existing image history.
- [ ] Broader media acceptance.
- [x] Checkout Favourites filter.
- [x] Sell filter for frequently sold items ranked by retained sales in the last 30 WAT days.
- [x] Code128 current-product/selling-pack label PNG output and native print submission, with saved barcode/unit/price snapshots and local decoding tests.
- [ ] Additional label layouts/symbologies and real printer/scanner verification; print submission does not prove paper delivery.
- [x] Reviewed bulk CSV update with row errors, stale checks, atomic application, identifier swaps and immutable before/after history.
- [ ] Broader bulk CSV keyboard/failure/large-catalogue acceptance.
- [ ] Opening balance imports for customers/suppliers with source history.

## Pricing and checkout

- [x] Named whole selling packs, independent prices/barcodes, mixed loose/pack baskets and base-stock deduction.
- [x] Reasoned lower unit prices, original/net price snapshots, exact discounted cash returns and recovered baskets.
- [x] Per-selling-unit percentage discounts with explicit kobo rounding, recovered drafts and net-price returns; integrated tests and a targeted actual offline fractional-discount sale pass.
- [ ] Broader percentage-discount UI/failure/keyboard acceptance, including partial-return journeys.
- [x] Reasoned fixed/percentage whole-basket discounts with deterministic exact line allocation, saved basket recovery and cash-return conservation.
- [ ] Full basket-discount UI/keyboard/failure acceptance and approval-policy extensions.
- [x] Versioned retail/wholesale/customer quantity price lists, explicit checkout review, provenance checks and historical sale snapshots; reduction-only.
- [ ] Automatic pricing/markups and broader price-list UI/keyboard acceptance.
- [x] Explicitly reviewed fixed or percentage line promotions with WAT validity dates, qualifying units and quantities, optional usage caps and revision history. Promotion use is checked again when the sale saves.
- [ ] Automatic and basket-wide promotions, stacking policies and broader offline clock acceptance.
- [x] Versioned component-stock bundles with reviewed whole-bundle cash checkout, exact discounts, stock movements, tender/change and receipt printing.
- [ ] Mixed-basket bundles, non-cash bundle checkout and additional bundle pricing policies.
- [x] Versioned base/selling-pack minimum prices and final-line enforcement after every discount, including measured whole-line ceilings; preserved historical checks.
- [x] Separately authenticated manager price approvals bound to one cart, operation and minimum-price policy, with expiry, revocation and immutable signed sale evidence.
- [ ] Cost/margin warnings and broader approval policies.
- [x] Bulk catalogue repricing via reviewed CSV with stale checks and change history.
- [ ] Described unlisted/open-price sale items with permissions.
- [x] Separate quotations with frozen agreed goods/units/prices, notes, expiry, revisions, acceptance/cancellation and reviewed full-cash sale conversion.
- [x] Sale notes retained in current and held baskets, completed receipts and CSV.
- [x] Reviewed accepted quotation conversion to an order preserving agreed goods/prices, an existing customer and an optional actual cash deposit.
- [ ] Quotation conversion to credit or electronic payment.
- [ ] Sale backdating with effective and recorded timestamps and permissions.
- [x] Historical cash given and change kept on saved receipt text, PDF and CSV.
- [ ] Receipt-number policy beyond the existing receipt identifier.

## Payments and returns

- [x] Manual transfer/card portions with explicit unverified status, reference/account, immutable storage and receipt/CSV history.
- [x] Checkout split of one external portion, cash and optional explicit named-customer credit; draft recovery and reference search.
- [ ] Multiple external portions in checkout UI and allocation across invoices.
- [x] Reviewed cash advances, unused-advance refunds and explicit application to existing credit receipts.
- [ ] Wider underpayment/overpayment settlement and change-owed liabilities.
- [ ] Payment reversal and dispute workflows preserving the ledger.
- [ ] Provider adapter, authenticated webhook verification and idempotent processing.
- [ ] Recheck/retry state machine and verified payment UI.
- [ ] Provider settlement batches, fees and bank reconciliation.
- [x] Credit-sale returns reduce unpaid receipt debt first, refund paid value in cash, require reconciled repayment assignments and retain immutable source history.
- [ ] Broader credit-return keyboard/failure acceptance and original-method external refunds.
- [x] Cash-funded exchanges with linked return and replacement receipts and an explicit cash difference.
- [ ] Exchanges against credit or external payment receipts, and store-credit differences.
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
- [x] Cash customer advance liability accounts, reviewed cash refunds and explicit application to existing credit receipts; statements distinguish advance funding from cash repayments.
- [ ] Store credit, advance-funded new checkout and change-owed liabilities.
- [x] Owner-authorized partial and full receipt-linked write-offs and linked reinstatement, with reconciled dated statements and credit ageing.
- [x] Receipt-linked customer debt disputes with current disputed/undisputed balance and immutable resolution, without changing money or debt.
- [ ] Additional authorized account adjustments and payment-provider disputes.
- [ ] Duplicate customer merge with review and immutable source links.
- [x] Dated customer CSV statements with reconciled opening/closing debt and WAT cutoffs; integrated automated coverage passes.
- [ ] Complete dated customer statement desktop/keyboard/failure acceptance.
- [x] Offline dated customer PDF statements with current-contact labels, WAT opening/activity/closing debt, embedded naira font and paginated identity; targeted tests pass.
- [ ] Full native PDF export/overwrite/cancel acceptance, sharing and server document delivery.
- [x] Local repayment follow-ups with due dates, current debt and reasoned completion or cancellation history.
- [ ] Consented repayment message delivery and delivery status.

## Purchasing and suppliers

- [x] Local stable-ID supplier profiles, current contacts/terms, archive/edit history, explicit purchase links and reconciled dated account CSV/PDF statements.
- [ ] Full supplier-account UI/keyboard/failure acceptance and historical contact snapshots.
- [x] Local purchase-order drafts, approval/cancellation and atomic multi-line purchasing with immutable history.
- [x] Partial deliveries and explicit outstanding purchase quantities.
- [x] Record and resolve shortage, damaged and unexpected delivery discrepancies with reasons and an optional link to a later stock receipt.
- [ ] Quarantine, supplier claims and inspection workflows for damaged deliveries.
- [x] Supplier invoice metadata, normalized duplicate review, immutable reasoned corrections and managed local evidence history.
- [x] Managed supplier PDF/PNG/JPEG attachment bytes and historical versions in encrypted backups, with current/historical image preview and file export.
- [ ] Broader invoice native/keyboard acceptance.
- [x] Purchase-cost history and linked supplier comparisons using saved buying-unit conversions, with CSV export.
- [x] Allocate existing paid cash expenses to purchase receipts by value or equally, with exact kobo splits, revisions and removals.
- [ ] Feed landed-cost allocations into historical stock valuation and accounting once costing policy is implemented.
- [x] Reviewed purchase returns with exact removal from stock and receipt-linked credit up to the amount owed, with no drawer movement.
- [x] Explicit cash reimbursements on supplier returns with receipt debt reduction and drawer reconciliation.
- [x] Merchant-recorded supplier credit notes, unapplied balances, reviewed receipt allocation/reversal and dated CSV/PDF statement reconciliation.
- [ ] Additional supplier account adjustments and supplier-issued document integrations.
- [x] Dated receipt-name supplier statements and CSV, including paid receipts, opening/closing debt and current debt; integrated automated coverage passes.
- [ ] Complete supplier statement desktop/keyboard/failure acceptance.
- [x] Extend dated statements to explicit stable supplier accounts, preserving historical supplier names on linked receipt activities.
- [x] Include purchase return credits in name-based and linked-account statements and exports.
- [ ] Extend statements to additional credits, adjustments and attachments as their source workflows are implemented. Broader PDF native-dialog acceptance remains pending.
- [x] Local reorder suggestions from recent retained sales, physical stock and outstanding approved purchases, with export.
- [x] Saved per-item supplier lead times, safety stock and buying multiples; suggestions deduct reservations and approved incoming goods with exact rounding.
- [ ] Additional replenishment policies and stockout analysis.

## Inventory

- [x] Source movement history and configurable low-stock threshold/filter.
- [x] Durable multi-item stock-count drafts, saved baseline/movement cutoffs, stale-count rejection, atomic posting/cancellation and backup history.
- [ ] Separate variance approvals, multi-user count coordination and full count UI/failure acceptance.
- [x] Local customer-order stock reservations with WAT expiry, release history and available quantities in Sell and Stock.
- [ ] Locations, bins and in-transit stock.
- [ ] Lots, expiry dates, serial numbers and stock ageing.
- [x] Damage/loss/theft/owner-use physical stock removals with reason categories and immutable quantity/history snapshots; no financial valuation.
- [x] Lead-time and safety-stock replenishment with reserved-stock availability and buying multiples.
- [ ] Stockout analysis and additional forecast methods.
- [x] FIFO purchase-basis historical layers and stock valuation, owner-declared opening cost, exact original-cost returns and repacking material transfer, with explicit coverage gaps.
- [ ] Broader costing policies, capitalized landed-cost treatment and unknown stock-source declarations.

## Cash and accounting

- [ ] Per-register/per-cashier drawers.
- [x] Optional denomination sheets persisted atomically with opening/closing cash counts, with typed-total agreement and retry/backup coverage.
- [x] Hide expected cash inside the closing count form. This is count-entry hiding only: the preceding dashboard can show expected cash.
- [x] Targeted actual desktop denomination close retained the count sheet and shortage in Past shifts.
- [x] Authenticated recipient sign-off and atomic counted close/open handover for the existing shared drawer, preserving shortages and signed history.
- [ ] Secure blind-accountability policy, per-register custody and full denomination UI/keyboard acceptance.
- [ ] Cash custody transfers and controlled period corrections.
- [x] Managed expense PDF/PNG/JPEG receipt files with reasoned replace/remove history, preview/export and encrypted backup/restore.
- [ ] Accrued/recurring expenses and non-cash funding sources.
- [x] Classified owner drawer contributions/drawings, stale-balance review, append-only opposite cash corrections and history CSV.
- [ ] Formal owner equity/loan accounts and accounting postings.
- [x] FIFO original-sale and net COGS, restored return cost and non-restocked return losses where source cost coverage is complete.
- [ ] Reliable gross/net profit and complete cost/expense recognition.
- [ ] Chart of accounts, double-entry journals and period controls.
- [ ] Trial balance, balance sheet and accounting exports.

## Documents, orders and trade modules

- [x] Versioned shop/contact/footer settings with actor history, optimistic edit checks and backup inclusion; new completed sales capture immutable receipt identity. Legacy receipts explicitly lack captured contacts.
- [x] Shop logo editor and immutable new-sale logo snapshots for PDF receipts.
- [x] Merchant receipt appearance with styles, frames, colour, additional artwork, actual PDF preview and immutable sale snapshots.
- [ ] Branded quotation/statement templates and historical tax/contact requirements beyond captured receipt identity.
- [x] Branded offline PDF sales receipts and dated customer/supplier statements with embedded fonts, wrapping, pagination and guarded file replacement.
- [x] Frozen quotation PDF/text including historical revision output.
- [x] PDF copies of saved sales invoices, pre-handover delivery notes and credit notes for recorded sale returns.
- [x] Local dispatch of already-sold order goods with exact quantities, recipient/address/courier/tracking, merchant-recorded delivery proof and failed/retry/return history.
- [ ] Proof image attachments, tax-invoice requirements, and document sharing/delivery.
- [x] A4/58/80 mm PDF receipt layouts with automated extraction, font and pagination checks.
- [ ] Real thermal-printer delivery/size/scanner compatibility, Windows native dialogs and comprehensive native export acceptance.
- [x] Local customer orders with agreed quantities and prices, cash deposits, reservation expiry and cancellation with an explicit cash refund.
- [x] Partial and final counter collection with linked sale receipts and tracked quantities, deposits applied and cash due.
- [ ] Broader layaway payment and forfeiture terms, backorders and additional delivery-proof attachments.
- [ ] Connected order channels, storefront/payment links and stock buffers.
- [ ] Beverage container/deposit ledger.
- [x] One/two-axis structured size/colour matrices with editable SKU/barcode/price/counted opening quantity per independently stocked combination and immutable creation history.
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
- [x] Android debug application foundation with shared rules/native SQLite, app-private credentials, basic checkout/operations and native document/image/backup adapters; 6 JVM + 16 API 37 native tests and selected actual disconnected phone workflows pass.
- [ ] Remaining Android editing/platform/API/device/accessibility/hardware/release parity: see ANDROID_FEATURE_PARITY.md; working database encryption and full desktop parity remain unfinished.
- [ ] iOS app and platform adapters.
- [ ] React/TypeScript/TanStack web app with explicit offline capabilities.
- [ ] Windows installer, upgrades and packaging checks.
- Dependencies: hosting account/credentials, platform signing where required and device access.

## Reporting, communication, tax and product operations

- [x] Product sales/refunds and recorded-staff activity CSV reports with source-date/actor semantics.
- [x] Current-category sales/return comparison with the preceding equal-length WAT period, amount bars and CSV.
- [ ] Custom reports, historical category classification and additional charts.
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
- [x] Searchable in-app offline help and a separately launched, isolated sample practice process.
- [ ] Full help/practice UI/keyboard acceptance, guided tutorials and expanded practice workflows.
- [x] Merchant-controlled local diagnostics with exact redacted text/JSON preview and export; SQLite structure, actual storage probe and installed print-service counts.
- [ ] Broader support/peripheral troubleshooting, backup health and fault recovery.
- [ ] Application updater, subscriptions and grace policies.
- [ ] Integration authentication, outbound/webhook event logs and retries.
- Dependencies: actual messaging/fiscal/service access and reviewed applicable rules.

## Security, recovery and release acceptance

- [x] Owner-enrolled local recovery code with single-use password reset, persistent throttling, session/PIN revocation and audit; secrets excluded from business backups.
- [ ] Encrypted working database/drafts with protected device keys.
- [x] Opt-in scheduled versioned encrypted local backups with verified encryption/password checks, guarded retention and bounded session-key lifecycle.
- [ ] Cloud backups, app-closed scheduling, independent-machine restore drill and broad native backup acceptance.
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
