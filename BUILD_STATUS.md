# Desktop build status

12 November 2026 · Returns, suppliers, reports, import and recovery update

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- **74 automated tests pass**, with zero failures or errors: 39 prior tests plus six after-sales, four tenant-snapshot, four receipt, six CSV/report-date, eight encrypted-backup, three restore-regression, and four product-import tests.
- Coverage includes cross-tenant rejection, exact kobo amounts, retry deduplication, return limits/restocking, supplier overpayment, atomic import rejection, WAT date boundaries, spreadsheet formula protection, encrypted tenant-only backup, wrong passwords, corrupted input, repeated restore and cash/debt-history consistency.
- Linux desktop distribution rebuilt successfully using `gradle :desktopApp:createDistributable` at `desktopApp/build/compose/binaries/main/app/RetailPOS`.
- Native isolated previews rendered for Reports, Sales, Suppliers, Backup and Import. Reports filled the 1920 × 1080 display; Sales and Suppliers were also inspected in the compositor-provided narrower window. See [Reports](artifacts/desktop-reports.png), [Sales](artifacts/desktop-sales.png), and [Suppliers](artifacts/desktop-suppliers.png), [Backup](artifacts/desktop-backup.png), and [Import](artifacts/desktop-import.png).
- Previous fullscreen verification remains recorded in [the fullscreen render](artifacts/desktop-fullscreen.png). Startup requests true fullscreen with F11 and visible window controls. Window-manager behavior still needs Windows and multi-monitor verification.

## Added in this round

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

See [the full implementation audit](IMPLEMENTATION_STATUS.md) for coverage of every specification section. Staff roles, packaging conversions/fractional sales, barcode catalogue tools, multiple held baskets, credit returns/exchanges, advanced stock/accounting, online services and mobile apps remain among the material outstanding work. The long-term feature specification is not complete.
