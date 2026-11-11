# Nigerian Retail POS — Complete Feature Specification

Date: 11 November 2026

Status: Proposed full product scope based on the product discussion. These features are planned requirements, not claims of implementation or a delivery commitment. Sector modules are enabled according to the merchant's business type.

## 1. Product definition

An offline-first retail POS and business-management application for Nigerian shops, market traders, wholesalers, and growing multi-location retailers.

The product helps merchants record sales, manage inventory, collect and reconcile payments, track customer and supplier debt, control staff access, and understand business performance.

It is not an agent-banking product. Customer cash withdrawals, bank cash deposits, agent float, and agent-network operations are outside this specification.

### Offline-first product contract

- Everyday business operations save to the device immediately and remain usable without internet.
- Internet connectivity triggers automatic background synchronization; users do not need to upload their work manually.
- Local persistence succeeds before the app reports a completed business action.
- Sales, stock movements, payments, expenses, and corrections have permanent unique identifiers.
- Retrying a request or synchronizing the same action more than once must not duplicate its business effect.
- Corrections preserve the original record and create linked adjustments, reversals, or replacement records.
- The interface distinguishes local records, synchronized records, and externally verified information.
- External payment verification, remote communications, government integrations, and access to another disconnected device's new data require the appropriate connection.
- Offline operation must not imply that bank transfers, card authorizations, cloud backups, or remote approvals have completed.

## 2. Businesses and users

### Supported business profiles

- Provision shops and minimarts.
- Supermarkets and convenience stores.
- Foodstuff and household-goods traders.
- Beverage retailers and distributors.
- Clothing, footwear, fabric, and cosmetics shops.
- Electronics, accessories, hardware, and building-material shops.
- Wholesalers and mixed wholesale/retail businesses.
- Restaurants and food vendors through an optional hospitality module.
- Pharmacies through an optional specialist module, subject to separate domain and regulatory validation.

### User roles

- Business owner: overall configuration, oversight, and financial access.
- Administrator: delegated business administration.
- Branch manager: branch operations and approvals.
- Cashier/sales assistant: checkout and permitted customer operations.
- Stockkeeper: receiving, counting, and inventory movements.
- Purchasing officer: suppliers, purchases, and purchase approvals.
- Accountant/bookkeeper: financial reports, reconciliation, and exports.
- Auditor/read-only user: approved records without mutation access.
- Custom roles assembled from granular permissions.

## 3. Business onboarding and configuration

- Create a business with name, business type, contact details, address, and optional registration/tax details.
- Configure naira as the default currency and Africa/Lagos as the default business timezone.
- Support Nigerian phone numbers in local and international formats.
- Set up the first branch, store/warehouse locations, register, and owner account.
- Guided setup with sample data kept separate from real business records.
- Start selling without completing the entire product catalogue.
- Choose modules according to business type and hide irrelevant tools.
- Configure receipt branding, business hours, trading-day cutoff, and numbering preferences.
- Configure units, price lists, tax treatments, expense categories, and payment methods.
- Import products, opening stock, customers, suppliers, and opening balances from supported CSV templates.
- Validate imports, preview changes, report row errors, and prevent accidental duplicate imports.
- Record the effective date and provenance of opening balances.
- Provide assisted migration from paper records or another system through reviewed import files.

## 4. Product catalogue

- Create products with name, category, brand, SKU, barcode, description, and optional photo.
- Add products without barcodes and create internal product codes.
- Add new items during a sale with the appropriate permission.
- Support stock-tracked goods, non-stock items, and service line items.
- Product variants such as size, colour, material, flavour, and specification.
- Multiple barcodes for different packaging sizes of the same product.
- Product aliases and familiar/local names for search.
- Categories, subcategories, favourites, and frequently sold product shortcuts.
- Activate, archive, and restore products without deleting transaction history.
- Bulk edit selected products and preview changes before applying them.
- Print product labels, price labels, and supported barcode labels.
- Maintain a history of price, unit-conversion, and relevant catalogue changes.
- Restrict access to cost prices and margin information by role.

## 5. Units, packaging, and conversions

- Buy and sell in different units: carton, crate, pack, dozen, piece, bag, kilogram, gram, litre, metre, and yard.
- Define merchant-specific units and conversion factors per product.
- Support nested conversions such as carton → pack → piece.
- Support fractional quantities with appropriate decimal precision.
- Display stock in a base unit and convenient packaging equivalents.
- Assign separate barcodes and prices to packaging units.
- Automatically deduct the correct base quantity when selling another unit.
- Record repacking and breaking bulk where explicit tracking is needed.
- Preserve the unit and conversion used on each historical transaction.
- Make conversion changes prospective and reconcile stock explicitly when needed.
- Do not assume local containers or measures have universal sizes.

Example: one carton contains 24 bottles; selling three bottles leaves 21 bottles.

## 6. Pricing, negotiation, and promotions

- Retail, wholesale, and customer-specific price lists.
- Prices by product variant and selling unit.
- Quantity-based price breaks and bulk discounts.
- Negotiated line prices with permissions, reason capture, and minimum-price rules.
- Percentage and fixed-amount discounts per line or basket.
- Promotion start/end dates, qualifying products, and usage rules.
- Bundles and combination offers with correct component stock deductions.
- Configurable discount stacking and clear display of the final calculation.
- Below-cost and low-margin warnings with controlled overrides.
- Bulk repricing with previews and audit history.
- Price changes preserve completed sales and existing agreed quotation terms.
- Offline promotion evaluation using downloaded rules and an explicit device-time policy.
- Explain when a promotion or price depends on information that is not current locally.

## 7. Checkout and sales

- Fast checkout using search, categories, favourites, camera barcode scanning, or external scanners.
- Add products without leaving the sale workflow.
- Quick-sale/open-price items with descriptions and staff permissions.
- Quantity changes, unit selection, discounts, notes, and negotiated prices.
- Hold, name, retrieve, and resume baskets.
- Optional customer association; walk-in sales do not require customer registration.
- Show item quantities, unit prices, discounts, taxes, payments, and balance due clearly.
- Support immediate payment, credit sale, deposit, and partial payment.
- Split a sale across payment methods.
- Separate sale, payment, stock reservation, and fulfilment states.
- Configure whether stock is reserved or deducted at confirmation, dispatch, or completion.
- Warn or block negative stock according to business policy and local data freshness.
- Produce durable receipts and recover interrupted checkout safely.
- Search sales by receipt number, date, amount, customer, product, cashier, and payment reference.
- Record backdated sales only with permission, effective date, and actual entry timestamp.
- Distinguish draft baskets, completed sales, quotations, and cancelled transactions.

## 8. Payment recording, verification, and settlement

- Record cash, bank transfer, external card-terminal, and other configured payment methods.
- Record the receiving account or terminal when relevant.
- Multiple payments per sale and one payment allocated across several invoices.
- Track underpayments, overpayments, customer advances, and unapplied payments.
- Calculate cash tendered and change due.
- Record change owed as a customer liability and clear it when paid or applied with authorization.
- Record external payment references, amounts, payment times, and recording staff.
- Separate cashier-recorded payments from provider-verified payments.
- Integrate supported payment providers for bank-transfer confirmation and supported terminal flows.
- Show receiving account name, account number, expected amount, and sale reference.
- Use transaction-specific accounts or references where the partner supports them.
- Process authenticated provider notifications and transaction rechecks.
- Handle delayed, duplicate, unmatched, reversed, and disputed payments.
- Maintain an unmatched-payment inbox and controlled allocation/correction workflow.
- Display verification delays without implying payment success.
- Reconcile gross payments, provider fees, refunds, settlement batches, and net bank credits.
- Track expected and received settlements and investigate discrepancies.
- Allow statement imports and manual reconciliation for supported external accounts.
- Support approved refund initiation through partners when available, with distinct requested, pending, completed, and failed states.
- A screenshot, customer SMS, or cashier action cannot produce a provider-verified status.
- Never automatically retry an uncertain debit as a new payment without resolving its original status.
- Availability of integrated methods depends on provider contracts, APIs, and hardware access.

## 9. Returns, exchanges, and corrections

- Full and partial returns linked to the original sale.
- Exchanges with price differences and additional payment or credit due.
- Record return reasons and the responsible staff member.
- Inspect returned goods and classify as sellable, damaged, or quarantined.
- Restore stock only to the appropriate location and condition.
- Handle refunds to cash, original payment method where supported, or agreed store credit.
- Support controlled no-receipt returns with stronger permissions and audit records.
- Prevent refund quantities and values from exceeding the permitted remaining balance.
- Offline cash refunds and locally authorized returns, subject to known transaction data and limits.
- Flag cross-device return risk when another device's latest activity is unavailable.
- Cancel or reverse transactions without removing the original evidence.

## 10. Customer management and credit sales

- Customer profiles with name, optional phone/contact details, address, and notes.
- Purchase history, credit balance, advances, and statements.
- Credit sales, deposits, instalments, due dates, and repayment schedules.
- Customer-specific credit limits and rules for overdue accounts.
- Allocate repayments to selected invoices or through a documented allocation rule.
- Prevent repayments from being counted as new sales revenue.
- Receivable ageing by configurable periods.
- Credit notes, debt adjustments, write-offs, and reinstatements with reasons and authorization.
- Record disputed balances separately from accepted debt.
- Print, export, and share customer statements.
- Queue merchant-authorized reminder messages for delivery when connected.
- Merge duplicate profiles with review and history preservation.
- Anonymous walk-in credit is prohibited unless the merchant explicitly chooses a traceable alternative identifier.
- Offline credit decisions use local balances and configurable risk limits; they cannot guarantee a current business-wide credit limit across disconnected devices.

## 11. Suppliers and purchasing

- Supplier directory, contact information, payment terms, and notes.
- Purchase orders and purchase approvals.
- Receive stock with or without a prior purchase order.
- Partial deliveries, shortages, extra quantities, damaged deliveries, and outstanding orders.
- Record supplier invoices, cash purchases, credit purchases, and deposits.
- Supplier payments, partial repayments, credits, outstanding balances, and statements.
- Purchase returns and supplier credit notes.
- Record freight, loading, transport, and other landed costs using an explicit allocation method.
- Maintain purchase-cost history and supplier price comparisons.
- Attach optional supplier-invoice photos or documents, queued for upload later.
- Prevent duplicate supplier invoice entry where detected; review suspected duplicates.
- Reorder suggestions using sales and stock data, with freshness warnings.
- Export or share purchase orders with suppliers when connectivity permits.

## 12. Inventory operations

- Stock ledger showing every receipt, sale, return, transfer, adjustment, and loss.
- Opening stock with quantity, cost, date, and source.
- Stock on hand, reserved stock, available stock, and in-transit stock.
- Stock counts, cycle counts, variance review, and approved adjustments.
- Damage, spoilage, expiry, theft, loss, samples, donations, and owner consumption reasons.
- Reorder levels and low-stock alerts calculated from locally available records.
- Negative-stock rules and discrepancy resolution.
- Multiple stock locations within a branch, including shelves, store rooms, and warehouses.
- Batch/lot tracking, expiry dates, and expiry alerts where enabled.
- Serial-number tracking for relevant products.
- Bundles, kits, and assembly/repacking movements.
- Stock valuation using a documented costing method; preserve historical transaction costs.
- Inventory ageing, slow-moving stock, stockout history, and write-off reports.
- Export stock records for verification and migration.

## 13. Cash drawer, shifts, and daily closing

- Open a register/shift with an opening cash count.
- Individual or explicitly shared cash-drawer accountability.
- Cash sales, customer repayments, cash refunds, expenses, and supplier payments.
- Cash added, removed, deposited into a bank, or transferred between authorized custodians.
- Owner withdrawals recorded separately from operating expenses.
- Cash denominations and count sheets.
- Expected closing cash versus actual cash, with shortage/excess explanations.
- Shift handover and cashier sign-off.
- Blind cash count option before showing expected cash.
- Daily and shift closing reports, printable offline.
- Preserve late-synced activity and flag any effect on a previously closed period.
- Reopening or correcting closed periods requires permission and leaves an audit trail.

Expected cash = opening cash + cash received + cash added − cash refunds − cash expenses − cash supplier payments − cash removed.

## 14. Expenses, bookkeeping, and business funds

- Expense categories including fuel, electricity, transport, rent, wages, packaging, repairs, and levies.
- Expenses paid immediately or recorded as payable.
- Recurring expense templates and reminders.
- Receipt attachments and notes.
- Payment source: cash drawer, business bank account, or owner-funded purchase.
- Owner contributions, drawings, and reimbursements kept separate from sales and expenses.
- Business bank/account balances as recorded, distinguished from provider-confirmed balances.
- Linked financial entries for sales, cost of goods, debt, payments, refunds, fees, and inventory losses.
- Cash-basis collection reports distinguished from accrual-based sales/profit reports.
- Configurable chart of accounts and accountant-friendly exports.
- Period controls, reconciliations, trial balance, profit and loss, and balance sheet for businesses with complete records.
- Clearly label reports as estimates when opening balances, costs, or other necessary records are incomplete.
- Preserve a consistent costing policy and explain the effect of corrections or late entries.

## 15. Receipts, invoices, quotations, and documents

- Offline thermal printing on explicitly supported hardware.
- Configurable 58 mm and 80 mm receipt layouts where supported.
- Receipt reprints marked as copies where appropriate.
- Business logo, contact information, branch/register, cashier, time, and unique receipt identifier.
- Item quantities, selling units, prices, discounts, taxes, payment methods, and balance due.
- Distinct labels for quotations, invoices, unpaid sales, partial payments, and verified receipts.
- Generate printable/shareable files offline.
- Share through the device's share sheet, including WhatsApp where installed.
- Queue server-delivered email/SMS messages until connectivity returns.
- Quotations with expiry dates and conversion into orders or sales.
- Delivery notes, purchase orders, statements, and credit notes.
- Optional receipt verification links/QR codes that clearly require connectivity to check.
- Collision-safe offline document identifiers across devices; additional central references may be assigned after sync without losing the original identifier.

## 16. Orders, reservations, delivery, and sales channels

- Customer orders, deposits, reservations, and layaway balances.
- Stock reservation expiry and release.
- Partial fulfilment, backorders, and collection scheduling.
- Pickup, local delivery, and merchant-arranged dispatch.
- Delivery fees, recipient details, landmarks, and delivery instructions.
- Dispatch records and proof of delivery/collection.
- Track payment due on delivery and reconcile collected money.
- Manual entry of phone, WhatsApp, social, and walk-in orders with source labels.
- Optional online catalogue/storefront and payment links in a later product phase.
- Synchronize online orders and stock through connected services with explicit conflict handling.
- Online availability cannot be guaranteed to reflect sales on disconnected shop devices; support stock buffers, channel allocations, and confirmation rules.
- External delivery integrations where commercially available.

## 17. Staff, permissions, approvals, and audit

- Individual staff identities and fast PIN switching on shared devices.
- Role and permission assignment by business, branch, location, and register.
- Hide costs, profits, customer contact details, or financial reports from unauthorized users.
- Limits for discounts, negotiated prices, refunds, credit sales, and cash movements.
- Local manager approval using a separate identity and credential.
- Remote approvals queued until an authorized connected manager responds.
- Offline permissions based on a previously authorized, time-bounded device policy.
- Revoked access takes effect on synchronization or offline authorization expiry; it cannot be instant on a disconnected device.
- Activity history showing actor, device, branch, effective date, entry time, and reason.
- Tamper-evident records of sensitive actions and corrections.
- Staff deactivation without removing their transaction history.
- Sales attribution and optional commission rules with adjustments for returns.
- Staff performance reports that distinguish sales activity from cash accountability.

## 18. Multiple registers, branches, and warehouses

- Multiple devices and registers per branch.
- Multiple branches and warehouses under one business.
- Branch-specific stock, prices, staff, cash drawers, and settings.
- Central catalogue with controlled branch overrides.
- Transfer requests, approvals, dispatch, partial receipt, discrepancies, and completion.
- In-transit stock and named sending/receiving custodians.
- Consolidated and branch-specific reporting.
- Owner access from authorized devices, with last-sync information per branch/device.
- Restrict access by assigned branch or warehouse.
- Safe device enrollment, replacement, and retirement.
- Same-site local-network synchronization without internet as an advanced capability.
- Local-network discovery and pairing require authentication and explicit device authorization.
- Devices without any connection retain independent records until they exchange updates.
- Support configurable stock allocation or oversell policies for disconnected registers.
- Support conservative customer credit and refund limits where business-wide activity is unavailable.

## 19. Reports and analytics

- Sales by day, week, month, branch, register, cashier, category, product, and unit.
- Gross sales, discounts, returns, net sales, collections, and credit sales.
- Payment-method totals and manual versus provider-verified payments.
- Customer receivables, overdue debt, advances, and supplier payables.
- Opening/closing cash, drawer discrepancies, and cash movement reports.
- Cost of goods sold, gross margin, operating expenses, and estimated net profit.
- Replacement-cost warnings shown separately from historical accounting profit.
- Stock on hand, valuation, low stock, stock ageing, expiry, and shrinkage.
- Purchase history, supplier performance, and outstanding orders.
- Settlement, fees, refund, and unmatched-payment reports.
- Best sellers, slow sellers, peak sales times, and repeat-customer patterns.
- Daily owner summary and exception alerts, delivered remotely when connected.
- Custom date ranges, comparison periods, filters, and exports.
- Each report identifies its data scope, last sync time, unsynced record count, and completeness limitations.
- Run core reports locally; consolidated reports include only data received from the relevant devices.

## 20. Customer retention and communications

- Optional loyalty points or stamps with configurable earning and redemption rules.
- Reversal of loyalty benefits after returns.
- Offline redemption limits and controls against spending the same balance on disconnected devices.
- Customer groups and purchase-based segments.
- Opt-in promotional messages and consent records.
- Merchant-reviewed debt reminders, order updates, and receipt messages.
- Queue messages offline and show queued, sent, failed, and cancelled states.
- Avoid duplicate messages after retries.
- Unsubscribe/opt-out management for promotional messaging.
- Distinguish service communications from marketing and apply the appropriate privacy rules.
- WhatsApp Business, SMS, or email integrations subject to provider availability and messaging rules.

## 21. Nigerian localization and usability

- Naira amounts, consistent rounding, and appropriate decimal precision.
- Nigerian phone-number formats and address fields including state, LGA, market, line, stall/shop number, and landmark where useful.
- Merchant-configured local units and familiar product names.
- Clear English as the initial language.
- Optional Pidgin, Hausa, Yoruba, and Igbo localization according to validated regional demand.
- Large touch targets, readable figures, strong contrast, and accessible text scaling.
- Minimal steps for common sales and numeric entry.
- Fast search on inexpensive Android devices.
- Low data consumption, battery-conscious operation, and optional image downloads.
- Keyboard shortcuts and scanner-friendly workflows on supported desktop/tablet interfaces.
- In-app tutorials and a practice environment with no effect on actual stock or money.
- Explain business concepts plainly: money collected, sales on credit, profit, and cash expected.

## 22. Sector modules

### Provision shops, minimarts, and supermarkets

- Fast barcode checkout, carton/pack/piece conversions, expiry, supplier credit, and stock counts.
- Perishable-goods loss recording and configurable weighing-scale integration.

### Foodstuff and bulk goods

- Weight, fractional quantities, bags, and merchant-defined measures.
- Variable prices, repacking, spoilage, and supplier cost changes.
- Record the agreed measured quantity and price without assuming universal local conversions.

### Beverages and distribution

- Crates, packs, bottles, wholesale pricing, delivery orders, and customer credit.
- Returnable bottles/crates and deposits tracked separately from goods revenue.
- Container balances owed by customers and owed to suppliers.
- Full/empty container movements, damage, returns, and deposit refunds.

### Fashion, footwear, and fabrics

- Size/colour variants, metres/yards, customer deposits, reservations, and exchanges.
- Optional alteration/service charges and collection dates.

### Electronics and accessories

- Serial numbers/IMEI where relevant, warranty periods, and proof of purchase.
- Supplier traceability, exchanges, warranty claims, and repair/service intake.
- Service status, parts consumed, deposits, final balance, and collection confirmation.

### Hardware and building materials

- Length, weight, volume, pieces, and product-specific conversions.
- Contractor price lists, quotations, bulk orders, partial dispatch, delivery, and credit.

### Cosmetics and personal care

- Variants, batches/expiry where relevant, bundles, and customer purchase history.

### Restaurants and food vendors

- Open bills, tables where needed, dine-in/takeaway/delivery, and order modifiers.
- Kitchen tickets and local-network kitchen displays/printers.
- Split bills, service charges, recipes, ingredient consumption, and food wastage.
- Kitchen status propagation requires local connectivity between devices.

### Pharmacy specialist extension

- Batch/expiry tracking, supplier traceability, stock quarantine, and recall lookup.
- Controlled access to sensitive records and appropriately restricted dispensing workflows.
- Prescription-related features require separate pharmacist-led requirements and regulatory review before implementation.
- Inventory functionality does not replace clinical judgment or authorize medicine dispensing.

## 23. Tax, invoices, privacy, and compliance support

- Merchant-specific tax registration and invoice details.
- Configurable effective-dated tax rates and inclusive/exclusive pricing.
- Separate taxable, exempt, zero-rated, and other applicable treatments.
- Do not apply one VAT rule to every merchant or product.
- Preserve tax calculations and rules used on historical transactions.
- Tax summaries, accountant exports, and supporting transaction records.
- Optional fiscal/e-invoicing integration where required for the merchant and supported by an authorized integration route.
- Distinguish a locally issued document from a government-validated fiscal document.
- Queue eligible submissions when permitted by the applicable regime; do not assume every fiscal workflow permits offline issuance.
- Keep external identifiers, submission status, validation errors, and correction history.
- Privacy notices, purpose-based collection, consent where appropriate, and user access controls.
- Customer data access, correction, export, and deletion/anonymization workflows, subject to applicable retention obligations.
- Configurable retention and controlled access to retained financial records.
- Payment and terminal functions use the appropriate licensed partners and approved integration paths.
- Confirm applicable tax, privacy, payment, and sector requirements before enabling dependent features; configuration must support later rule changes.

## 24. Devices and hardware

- Android checkout application designed for inexpensive phones and tablets.
- Owner/back-office web interface; any offline web capability must be explicitly defined and tested.
- Optional desktop checkout interface if validated by target merchants.
- Camera barcode scanning and supported external scanners.
- Supported Bluetooth, USB, and network thermal printers.
- Cash-drawer opening on supported printer/hardware combinations.
- Optional customer-facing display.
- Optional weighing-scale integration for explicitly supported models.
- Approved smart-terminal deployment where the provider permits third-party applications.
- Hardware compatibility list, pairing tools, test prints, and troubleshooting guidance.
- Checkout remains usable if optional peripherals fail; failed printing does not cancel or duplicate a saved sale.

## 25. Synchronization, conflicts, and data integrity

- Durable local database for business records and pending changes.
- Automatic sync on connection recovery, app resume, and suitable background opportunities.
- Manual sync/retry as a troubleshooting option rather than the normal workflow.
- Incremental transfers, batching, compression where useful, and resumable attachment uploads.
- Visible offline/online state, last successful sync, pending changes, and actionable errors.
- Idempotent processing and deduplication for business actions and provider events.
- Ordered dependencies so products, sales, payments, and stock movements reconcile correctly.
- Retain device identity, user identity, event time, server receipt time, and business effective date.
- Flag clock anomalies; do not trust device time alone for authoritative cross-device sequencing.
- Inventory synchronizes as movements, not by overwriting a shared stock number with the latest uploaded value.
- Payments and debt adjustments preserve allocation history; balances derive from valid entries.
- Define deterministic merge rules for safe changes and review queues for unsafe conflicts.
- Expose conflicts involving unit conversions, customer merges, stock counts, permissions, or financial adjustments.
- Do not silently discard a sale to resolve a stock conflict.
- Stock counts have an effective cutoff and reconcile intervening movements.
- Preserve closed-period reporting while identifying late-arriving transactions and adjustments.
- Detect long-unsynced devices and warn about stale catalogue, permissions, stock, and balances.
- Prevent logout, reset, or destructive cleanup from silently deleting pending records.
- Updates and schema migrations preserve local records and recover safely after interruption.
- Financial and inventory consistency checks identify discrepancies and support controlled repair.
- Document conflict policies for disconnected stock sales, credit limits, returns, loyalty, and remote permissions.

## 26. Backup, restore, security, and recovery

- Encrypted local storage and encrypted network communication.
- Secure storage of credentials and device keys.
- Owner/admin multifactor authentication for connected administrative access.
- Local PIN/biometric convenience unlock according to supported device security.
- Idle locking and sensitive-action reauthentication.
- Device registration, session management, and revocation.
- Explicit offline authorization duration and recovery policies.
- Automatic cloud backup when connected, with visible backup status.
- Encrypted local export/backup for long-offline businesses.
- Tested restore to a replacement device with duplicate-import protection.
- Recover unsynced data from an accessible device through controlled export.
- State clearly that data never synced or backed up cannot be recovered from a lost or destroyed device.
- Audited support access and restricted administrative tools.
- Do not store card PINs, CVVs, or unnecessary sensitive payment data.
- Security event logs, suspicious activity alerts, and incident response tools.
- Account recovery that protects ownership without making ordinary offline trade depend on frequent online OTP delivery.

## 27. Support and product operations

- Offline help articles, onboarding guides, and hardware troubleshooting.
- Create support requests offline and send them after reconnection.
- Attach a relevant sale/payment identifier and merchant-approved diagnostic data.
- Show ticket status and support contact options when available.
- Network, printer, storage, backup, and sync health diagnostics.
- Merchant-controlled diagnostic export with redaction of sensitive data.
- Application version and update status.
- Safe updates with explicit protection of unsynced work.
- Subscription/plan administration if a paid model is adopted.
- Cached entitlements and a documented offline grace policy; never silently erase records or block access to historical data because billing cannot be checked.
- Merchant data export and account closure workflow.

## 28. Integrations and extensibility

- Payment providers, supported terminals, transfer verification, and settlement feeds.
- SMS, email, and permitted WhatsApp integrations.
- Accountant exports and optional accounting-package connectors.
- Optional online storefront and order-channel integrations.
- Optional logistics/delivery integrations.
- Optional fiscal/e-invoicing connectors according to merchant applicability.
- Authenticated integration access with least-privilege permissions.
- Retryable outbound events, delivery logs, and duplicate protection.
- Document external dependencies and how each feature behaves while disconnected.
- Third-party integration failure must not prevent unrelated local sales recording.

## 29. Offline availability matrix

| Capability | Without internet | On reconnection |
|---|---|---|
| Product search and catalogue changes | Available within permissions and downloaded/local data | Merge and resolve conflicts |
| Cash checkout and receipts | Available on the device | Sync sale, payment, and stock effects |
| Recorded external transfer/card payment | Available with an explicit unverified/manual status | Verify where integrated and reconcile |
| Bank payment authorization/verification | No independent confirmation | Submit/check through the provider |
| Credit sales and repayments | Available with local balance/risk controls | Consolidate allocations and flag exceptions |
| Purchasing and supplier payments | Local recording available | Sync stock and supplier ledger |
| Stock counts and adjustments | Available using local data | Reconcile cutoff and intervening movements |
| Expenses and cash closing | Available locally | Update consolidated reports and exceptions |
| Local printing/file generation | Available with supported hardware | No internet needed for printing |
| Email/SMS/server messaging | Compose and queue | Send once, with delivery status |
| Owner remote oversight | Last received data only | Refresh from synced branches/devices |
| Remote approvals | Request can be queued | Approval requires a connected approver |
| Local-network device exchange | Available if paired devices share a working local network | Also synchronize through central services |
| Consolidated reports | Only records already present locally | Include newly received device records |
| Cloud backup and lost-device revocation | Cannot finish remotely while disconnected | Execute and show completion status |
| Fiscal submission and online ordering | Governed by external service and applicable rules | Submit/sync and handle conflicts |

## 30. Product acceptance scenarios

These scenarios guide implementation and verification across the full product.

1. A shop completes a day of cash sales without internet, restarts the device, then reconnects. Every sale, payment, and stock movement remains present exactly once.
2. A carton contains 24 bottles. Selling two cartons and seven bottles from ten cartons leaves 185 bottles.
3. A ₦35,000 sale receives ₦20,000 immediately and ₦5,000 later. The customer owes ₦10,000; total sales remain ₦35,000.
4. A cashier records a transfer while disconnected. The app labels it unverified until the integrated provider confirms it.
5. A duplicate payment notification arrives. The receipt and balance reflect the payment once.
6. Two disconnected registers sell the same remaining stock. Neither sale disappears; the configured policy flags or resolves the oversell.
7. A customer requests a return already processed on another disconnected device. The app applies its conservative offline policy instead of promising globally current return eligibility.
8. A cashier closes a drawer with a shortage. The shortage is recorded without changing sales to make the figures match.
9. A purchase cost changes. Historical profit remains tied to the documented costing policy; replacement-cost warnings update separately.
10. A manager revokes a cashier while that device is offline. The system explains the revocation delay and applies the configured authorization expiry.
11. A local printer fails. The sale remains saved and can be reprinted without creating a second sale.
12. A device is replaced using a backup. Restore is verified and repeated import cannot duplicate the ledger.
13. A branch sends stock and another receives only part. Remaining goods stay identifiable as in transit or disputed.
14. A merchant owes a customer cash change. The app records a liability and later settlement correctly.
15. A beverage customer returns empty crates. Container balances and deposits change independently from goods revenue.
16. A device syncs after a period was closed. The app flags the affected reports and preserves an explanation of the adjustment.

## 31. Scope boundaries and decisions still required

The complete scope above includes both core and advanced modules. It does not require every merchant to see every feature.

### Outside the planned retail scope

- Agent banking cash withdrawals, cash deposits, and agent float management.
- Operating a bank, issuing an independent stored-value wallet, or holding customer funds without the appropriate arrangement.
- Direct lending, credit underwriting, investment products, or foreign-exchange trading.
- Full payroll, general HR management, and a supplier marketplace.
- Clinical decision support or autonomous medicine-dispensing advice.

Customer advances, change owed, and store credit are merchant accounting balances; their implementation must not be presented as an independently transferable financial wallet.

### Decisions to settle during product design

- Initial merchant segment, launch region, and field-validation findings.
- Whether the first supported branch uses one register or multiple disconnected registers.
- Device/platform support and minimum hardware requirements.
- Local-network synchronization architecture and conflict policies.
- Payment partners, available integrations, and supported terminals.
- Inventory costing method and treatment of backdated entries.
- Tax applicability, fiscal integrations, and sector-specific requirements.
- Language priorities, pricing, subscription policy, and support model.
- Performance targets and tested offline storage capacity.
- Delivery phases and implementation estimates.

## 32. Research references

These references informed the earlier research and product discussion. They are not evidence that every proposed feature is validated with merchants. Payment APIs, regulations, product capabilities, and integration access must be rechecked when the corresponding work starts.

- EFInA, Access to Financial Services 2026: https://a2f.ng/a2f-2026-report/
- Moniebook product capabilities: https://moniepoint.com/moniebook
- Bumpa product capabilities: https://www.getbumpa.com/
- Loyverse offline behavior: https://help.loyverse.com/help/offline-work-of-pos
- Paystack payment verification: https://paystack.com/docs/payments/verify-payments/
- Paystack dedicated virtual accounts: https://paystack.com/docs/payments/dedicated-virtual-accounts/
- Miva MSME survey summary: https://miva.edu.ng/miva-open-university-releases-msmes-year-end-report-2025/
- Moniepoint Onitsha market case study: https://casestudies.moniepoint.com/documents/driving-trade-in-west-africas-largest-market-by-moniepoint.pdf
- Nigeria Revenue Service e-invoicing portal: https://einvoice.nrs.gov.ng/
- Nigeria Data Protection Commission: https://ndpc.gov.ng/
