# Desktop build status

12 November 2026 · Catalogue, stock counts, customer history and held-sales update

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- **95 automated tests pass**, with zero failures or errors: 39 prior tests plus six after-sales, four tenant-snapshot, four receipt, six CSV/report-date, eight encrypted-backup, three restore-regression, four product-import tests, eight catalogue/stock/customer-query tests, eight held-sale/recovery tests, and five customer-statement tests.
- Coverage includes cross-tenant rejection, exact kobo amounts, retry deduplication, return limits/restocking, supplier overpayment, atomic import rejection, WAT date boundaries, spreadsheet formula protection, encrypted tenant-only backup, wrong passwords, corrupted input, repeated restore and cash/debt-history consistency.
- Linux desktop distribution rebuilt successfully using `gradle :desktopApp:createDistributable` at `desktopApp/build/compose/binaries/main/app/RetailPOS`.
- Native isolated previews rendered for Reports, Sales, Suppliers, Backup and Import. Reports filled the 1920 × 1080 display; Sales and Suppliers were also inspected in the compositor-provided narrower window. See [Reports](artifacts/desktop-reports.png), [Sales](artifacts/desktop-sales.png), and [Suppliers](artifacts/desktop-suppliers.png), [Backup](artifacts/desktop-backup.png), and [Import](artifacts/desktop-import.png).
- Previous fullscreen verification remains recorded in [the fullscreen render](artifacts/desktop-fullscreen.png). Startup requests true fullscreen with F11 and visible window controls. Window-manager behavior still needs Windows and multi-monitor verification.

## Latest daily-shop additions

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

See [the full implementation audit](IMPLEMENTATION_STATUS.md) for coverage of every specification section. Staff roles, packaging conversions/fractional sales, catalogue categories/variants and bulk edits, credit returns/exchanges, advanced stock/accounting, online services and mobile apps remain among the material outstanding work. The long-term feature specification is not complete.
