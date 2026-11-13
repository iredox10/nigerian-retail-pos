# Desktop build status

13 November 2026 · Stock history and reorder alerts update

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- **204 automated tests pass**, with zero failures or errors. The negotiated-price checkpoint added 13 tests to the previous 170-test checkpoint; six ageing-band tests brought the total to 189. Fifteen correction/domain/export tests bring the total to 204.
- Coverage includes cross-tenant rejection, exact kobo amounts, retry deduplication, return limits/restocking, supplier overpayment, atomic import rejection, WAT date boundaries, spreadsheet formula protection, encrypted tenant-only backup, wrong passwords, corrupted input, repeated restore and cash/debt-history consistency.
- Linux desktop distribution rebuilt successfully using `gradle :desktopApp:createDistributable` at `desktopApp/build/compose/binaries/main/app/RetailPOS`.
- Native isolated previews rendered for Reports, Sales, Suppliers, Backup and Import. Reports filled the 1920 × 1080 display; Sales and Suppliers were also inspected in the compositor-provided narrower window. See [Reports](artifacts/desktop-reports.png), [Sales](artifacts/desktop-sales.png), and [Suppliers](artifacts/desktop-suppliers.png), [Backup](artifacts/desktop-backup.png), and [Import](artifacts/desktop-import.png).
- Previous fullscreen verification remains recorded in [the fullscreen render](artifacts/desktop-fullscreen.png). Startup requests true fullscreen with F11 and visible window controls. Window-manager behavior still needs Windows and multi-monitor verification.

## Correcting payment assignments — 13 November 2026

- Customers → View history → Assign payments to receipts now retains each original assignment and offers Undo assignment with a required reason. Undone history shows its correction time and reason.
- Undo releases the assigned amount on both the payment and receipt. Reassign it explicitly through the existing form. It records no new payment, refund, stock movement or customer-debt change.
- Ageing uses active assignments only; released money remains unassigned and exact ageing is unavailable until reassigned. Retrying an old assignment cannot reactivate an undone record.
- Reports → Payment assignments exports original assignment dates with current Active/Undone status and correction details. Assignment corrections exports events by correction date, even when the original assignment is older.
- Backups preserve original assignments and undo events, and older backups without corrections remain supported. Staff correction permissions, cash repayment reversals and historical as-of reporting remain separate work.
- Fifteen additional tests cover correction reason validation, effective caps, reassignment, replay safety, competing undo requests, tenant boundaries, ageing changes, corrupt backup rollback, actual old-database upgrade/reopen and formula-safe correction CSV.
- Native isolated preview: undid the ₦250 sample assignment, confirmed debt stayed ₦750 and unassigned funds became ₦250, inspected correction history, then explicitly assigned that payment again. Original Undone and new Active rows remained together; available payment returned to ₦0. Evidence: [confirmation](artifacts/desktop-assignment-undo-confirm.png), [released amount](artifacts/desktop-assignment-undone.png), [history](artifacts/desktop-assignment-correction-history.png), [reassigned](artifacts/desktop-assignment-reassigned.png).
- Linux distributable rebuilt with the integrated checks. CSV formatters are tested; their native save dialogs were not exercised in this correction pass. No merchant or physical peripheral study was performed.

## Debt-ageing bands — 13 November 2026

- Credit review now groups current receipt balances into due today/later, 1–7, 8–30, 31–60, 61–90 and over 90 days late, with a separate no-agreed-date group.
- Groups use explicitly assigned repayments. Exact amounts remain unavailable while payments are unassigned; no FIFO or inferred due dates are introduced.
- Export all customer ageing creates spreadsheet-safe CSV with classification date, current-balance basis, unassigned amounts and all bands. Unknown amounts are blank, not zero. Export includes all reviewed customers regardless of the on-screen filter.
- Six tests verify every boundary, leap-day calendar arithmetic, partial/fully settled receipt bands, reconciliation, unknown values, tenant restrictions, duplicate rows and relabeled export dates.
- Native isolated preview inspected the rendered rows: Amina's ₦750 current debt appeared entirely under Due today or later, with zero unassigned payments and zero overdue. [Screenshot](artifacts/desktop-debt-ageing.png). The export formatter is tested; the OS save dialog was not exercised in this pass.
- Current balances are classified using the review date; this is not historical accounting as of that date. Reasoned allocation undo and reassignment are implemented in the correction section below; staff approval limits, write-offs and reminders remain pending.

## Negotiated unit prices — 13 November 2026

- Each basket line offers Change price with the original unit price, agreed lower price and required reason. Discounts use integer kobo per sold unit; quantities/pack factors stay unchanged. Full discounts are explicit and reasoned; markups, basket/percentage discounts, minimum margins and staff approval limits remain pending.
- Receipts retain catalogue price, reduction, reason and price paid. Cash returns use the net unit price, including partial pack/loose returns; original transactions stay intact. Sales CSV includes original subtotal and discount totals.
- Drafts preserve discount values and reasons through restart/hold/resume. If the current catalogue price differs from the saved reference price, payment is blocked until the discount is reviewed or removed. Old backups/drafts default to no discount.
- Automated checks cover net credit limits, overflow, unchanged catalogue/stock conversion, partial refunds, tenant isolation, duplicate retries, malformed backups/drafts and older backup defaults.
- Native isolated preview: changed 3 Malt bottles from ₦500 to ₦450 each, entered a reason, observed ₦150 discount and ₦13,850 basket total, paid exact cash and reopened the saved receipt. Evidence: [price entry](artifacts/desktop-discount-price.png), [basket](artifacts/desktop-discount-basket.png), [saved sale](artifacts/desktop-discount-saved.png), [receipt](artifacts/desktop-discount-receipt.png).
- Linux distributable rebuilt successfully. Merchant research, physical printer/scanner checks and staff authorization remain outstanding.

## Selling packs and loose units — 13 November 2026

- Stock → Manage → Selling units defines immutable pack names, base conversion, whole-pack prices and optional barcodes. Archive/create replaces a definition; archived definitions remain available to historical records.
- Sell offers a labeled selling-unit choice. Mixed carton/loose lines share one base stock pool; quantity controls and repository checks aggregate their stock requirement. Pack barcodes use the existing barcode + Enter flow.
- Held/active drafts keep distinct pack keys; unavailable archived units block payment until removed/restored. Resumed drafts use current catalogue prices.
- Historical receipts, customer history, cash-sale refunds and optional restock preserve sold units/prices/conversions. Backups include definitions and snapshots; older backups/migrations default to base units.
- Automated checks verify 1 carton × 24 plus 3 bottles deducts 27 bottles; original pack refunds, mixed overselling rollback, tenant/barcode isolation, replay safety, migration/restore and overflow.
- Linux distributable rebuilt successfully. Native checkout render (unit-choice layout inspected; the full checkout interaction was not completed in this visual pass): [selling-unit choice](artifacts/desktop-pack-checkout.png). Physical scanner/printer and merchant usability remain unverified.

## Latest inventory additions

- Stock → Manage → History shows opening counts where recorded, purchases in converted base units, sales, restocked returns and count corrections, with source references and timestamps. Damaged/non-restocked returns add no stock movement.
- New items preserve their opening quantity and recorded time. Older products and old backup rows keep these fields unset; the UI does not invent an opening count or running balance.
- Optional reorder levels live under item Details. Blank disables the reminder; zero means out-of-stock only. Active goods at or below their threshold appear in Low stock; archived goods are excluded.
- Threshold changes preserve setting history and retry protection without changing stock or cash. Backups preserve settings and opening metadata and reject invalid ranges or incomplete opening records.
- Reports exports current low-stock snapshots and dated source movements. Product exports distinguish unknown opening quantities from zero and include reorder levels.
- History renders the newest 100 movements first, with a labeled option to show more. Large-history database profiling remains outstanding.
- Tests include receiving 2 cartons as 48 bottles, sale/restock/damaged return/count corrections, duplicate retries, tenant boundaries, actual old-database migration and backup round trips.
- Native isolated Stock screen and History tab opened and inspected: [stock alerts](artifacts/desktop-stock-alerts.png), [low-stock filter](artifacts/desktop-low-stock-filter.png), [source movements](artifacts/desktop-inventory-movements.png). Sample history shows 240 opening bottles, a source-linked sale of 3 and current stock 237. Full threshold-edit interaction, keyboard-only journeys and merchant testing remain outstanding.
- This is source history, not costing, inventory valuation or guaranteed running-balance reconstruction of legacy/fractional records.

## Previous repayment-allocation additions

- Assign already-recorded customer repayments to specific credit receipts. One payment may cover several receipts and a receipt may receive several payments, within both source limits.
- Immutable links preserve the customer, source payment, source receipt, amount and timestamp. Retried assignments do not duplicate coverage.
- Assignment never changes total customer debt, cash, stock or the original sale. Older payments stay unassigned until explicitly selected; no FIFO allocation is guessed.
- Credit review shows remaining receipt balances and exact recorded-date overdue debt only when the customer's repayments are fully assigned. With unassigned funds it explains why an exact overdue total is unavailable; undated credit is excluded from dated overdue totals.
- Reports exports assignment history as a spreadsheet-safe CSV with both source references and exact amounts. Backups preserve assignments and reject invalid cross-customer references or over-assignment on restore.
- The allocation-aware credit review was rendered and inspected using isolated sample data: [assigned credit review](artifacts/desktop-credit-assigned-review.png). The contextual [assignment screen](artifacts/desktop-payment-assignments.png) was also opened and inspected with the sample payment fully assigned. Creating an assignment through the form and merchant testing remain unverified.
- The later correction workflow now appends reasoned undo events and permits explicit reassignment; original saved links stay immutable. Staff correction approvals remain pending.

## Previous customer-credit additions

- Customer phone/address editing with Nigerian-format phone normalization, clear unlimited/zero credit-limit states and immutable before/after edit history.
- New customer debt is checked against the current stored limit inside the sale transaction. Lowering a limit does not write off existing debt; cash sales and repayments remain available.
- Optional agreed due dates on new credit sales use the WAT calendar; malformed or past dates reject the new transaction. Existing/historical dates remain valid records on restore.
- Active and held baskets retain due-date text, including unfinished input. Receipts, sale/customer CSVs and statements preserve the saved due date.
- More → Review customer credit separates past-due original sale dates, today/future/undated sales, current all-date customer debt and total repayments. It does not invent payment allocations or an exact overdue invoice balance.
- Legacy database and backup migrations retain old balances and leave absent contact/limit/date fields unset. Profile credentials and online membership remain outside this workflow.
- Native isolated preview renders inspected for the [customer credit editor](artifacts/desktop-customer-credit-editor.png) and [credit review](artifacts/desktop-credit-review.png). No complete end-to-end interaction or merchant study is claimed.

## Previous buying-package additions

- Define merchant-specific buying units under Stock → Manage → Buying units, for example 1 carton = 24 bottles. Names/factors are immutable; archive and create a new definition when package sizes change.
- Receive stock in a chosen buying unit, with quantity and cost labeled per carton/pack and an explicit base-stock preview before saving.
- Record both purchased quantity and base quantity added on historical receipts, supplier views and CSV exports. The base selling unit and ordinary checkout remain unchanged.
- Stock precision, stale conversion, tenant/product ownership, retry and archival checks run in one transaction. A failure leaves stock, cash, supplier receipt and outbox unchanged.
- Additive database migrations and legacy-backup defaults preserve old one-to-one receiving records. Encrypted backups now include buying definitions and receipt conversion snapshots.
- The independent integration test receives 2 cartons × 24 bottles, sells 3 bottles and verifies 45 remain, together with exact drawer cash and supplier debt.
- Product CSV exports now include archived goods and their archive flag; this is labeled on the Reports screen.
- Native preview check: opened Buying units, selected carton, entered 2 cartons at ₦10,000 each with ₦0 paid, and saved the receipt. Malt stock increased from 237 to 285 bottles, with a ₦20,000 purchase/credit preview. See [buying units](artifacts/desktop-buying-units.png), [conversion](artifacts/desktop-carton-receiving.png), [cost preview](artifacts/desktop-carton-cost-preview.png), and [saved stock](artifacts/desktop-carton-stock-saved.png).
- Whole-package selling with independent prices/barcodes is now implemented below. Fractional checkout and repacking remain pending.

## Previous daily-shop additions

- Barcode assignment and exact barcode + Enter lookup at checkout; barcodes remain unique within each shop, including archived items.
- Product name/price/barcode editing, archive/restore and immutable price history. Completed receipts retain their original name, price and unit.
- Reasoned single-item stock counts with previous/count/difference history, retry deduplication and rejection of stale count forms.
- Searchable customer history, original sale/credit and later repayment separation, plus reconciled spreadsheet-safe CSV statements.
- Multiple named held sales, atomic active/held transitions, restart recovery of tender/customer/method, tenant isolation and preservation of other baskets after checkout.
- Legacy database and draft migration coverage; incomplete or corrupt draft files are preserved rather than reset. Backups retain the new catalogue and stock-correction histories and accept older product rows.
- Rendered catalogue, item details and count views inspected: [catalogue](artifacts/desktop-catalogue.png), [details](artifacts/desktop-product-details.png), [count](artifacts/desktop-stock-count.png). Customer history was opened in the native preview and inspected: [history](artifacts/desktop-customer-history.png).
- The native isolated Sell preview was used to put a named basket aside and open Saved sales. See [held baskets](artifacts/desktop-held-sales.png). Hold/resume durability is covered by automated tests; the native resume outcome was not conclusively captured. Keyboard entry of the sample barcode plus Enter added exactly one bottle, changing the basket from 3 to 4 bottles and the total from ₦14,000 to ₦14,500: [barcode check](artifacts/desktop-barcode-checkout.png). These are targeted manual checks, not a complete end-to-end or merchant study.
- Material overlay container roles now map to the existing neutral surface tokens, preventing default purple dialogs.

## Previously added local workflows

- Searchable saved sales, receipt detail with WAT dates, text receipt export, and OS print submission with pagination.
- Original cash-sale item returns, remaining returnable quantities, reason and explicit sellable-restock choice; linked cash refunds preserve the original sale.
- Supplier balances by stock receipt, later cash payments and payment history.
- WAT date-range sales/cash/expense/purchase/refund reports, with current customer debt labeled separately. Profit is unavailable until historical COGS exists.
- Spreadsheet-safe CSV exports for sales, products, customers, expenses, cash activity and stock receipts.
- CSV product template, parsed preview and all-or-nothing add-only import. Duplicate items reject the complete batch, including repeated imports.
- Password-encrypted selected-shop backup files and restore into a new shop with a new owner password. Original shops remain intact; credentials and unfinished baskets are excluded.
- Restore validation checks integer quantity bounds, customer debt against saved history and source-backed cash entries before creating the recovered workspace.

## Checks still required

- Full UI interaction journeys, error-state checks, keyboard-only and accessibility testing. Rendered inspections do not establish these.
- Real thermal printing, scanner/peripheral use, Windows installers/display scaling and independent-machine recovery drills.
- Merchant first-glance and practical task studies; none have been conducted.
- Large-catalogue performance and background execution for longer imports/backups; local operations currently run synchronously.
- External services: no bank/card verification, remote authentication, background cloud sync or Heroku deployment exists.

## Multi-tenant boundary and remaining scope

Local business queries and mutations are tenant-scoped. Composite relationships reject cross-shop financial references, each owner gate has shop-specific credentials, and restore creates another tenant rather than overwriting a shop.

This remains a trusted-device owner application. The working SQLite database is unencrypted. Local owner gates are not a network authorization boundary. Production online operation requires authenticated identities, tenant memberships, server-side authorization, roles, device enrollment and a reviewed synchronization protocol.

See [the full implementation audit](IMPLEMENTATION_STATUS.md) for coverage of every specification section. Staff roles, fractional sales, catalogue categories/variants and bulk edits, credit returns/exchanges, advanced stock/accounting, online services and mobile apps remain among the material outstanding work. The long-term feature specification is not complete.
