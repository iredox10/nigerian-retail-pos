# Nigerian Retail POS

An offline-first Kotlin desktop retail POS for Nigerian merchants, with reusable shared domain and Compose design-system modules. This is an initial working desktop foundation, not the complete feature specification or a production SaaS deployment.

## Run

Requires a JDK 17 installation (set `JAVA_HOME`), network access for the first dependency download, and a graphical desktop. Subsequent local business operations do not require the network.

```sh
./gradlew :desktopApp:run
```

Create a shop workspace and owner password, add goods under Stock, add a customer if credit is needed, then make a sale. Each shop has isolated records and a separate owner password. Local data is stored under `~/.nigerian-retail-pos` and is not encrypted; use a trusted workstation. Keep this directory for persistence. Do not treat pending outbox entries as online backups.

The app opens in fullscreen. Press **F11** or use **Exit fullscreen** to switch to a window. The title strip provides Minimize and Close, and can be dragged in windowed mode.

For an explicitly isolated sample workspace with an in-memory database and temporary draft directory:

```sh
./gradlew :desktopApp:run -Ppreview
```

The preview bypasses workspace onboarding only for its own sample database. It never opens normal shop records or calls a payment service. Preview data is discarded when the process ends.

To review a particular screen, set `POS_PREVIEW_SCREEN=Stock`, `Money`, `Sales`, `Reports`, `Suppliers`, `Backup`, or `Import`. For explicitly windowed layout checks, also set `POS_PREVIEW_WIDTH` and `POS_PREVIEW_HEIGHT`. The default preview opens fullscreen just like the regular app.

```sh
./gradlew :shared:jvmTest :desktopApp:jvmTest
./gradlew :desktopApp:createDistributable
```

Desktop distributions are built for the host operating system. Build and test the Windows distribution on Windows; the current environment validates Linux/JVM desktop execution.

## Implemented

- Local shop creation and password-gated workspace selection.
- Tenant-scoped SQLite products, customers, sales, repayments, and outbox entries.
- Product creation, name/unit/barcode search, price/name/barcode edits, archive/restore and preserved price history.
- Whole-unit stock counts with previous/count/difference history, reason and stale-count rejection.
- Source-linked stock history for opening counts, purchases in converted base units, sales, restocked returns and count corrections. Older goods without saved opening counts remain explicitly unknown.
- Optional per-item reorder levels and a Low stock filter; archived goods are excluded. Product/low-stock snapshots and dated stock movements can be exported as CSV.
- Cash checkout, exact amount/change, and explicit named-customer partial credit.
- Atomic stock/debt/sale writes and retry deduplication with stable operation identifiers.
- Tenant-scoped active and named held basket recovery after restart, including payment-entry text/customer/method; screen-state retention across navigation.
- Keyboard barcode entry with Enter adds one item; physical scanner testing remains outstanding.
- Searchable sales and receipt details, customer repayments/history, reconciled CSV statements, and clearly scoped money summaries.
- Customer phone/address and credit limits, with live transaction checks against new debt. Blank limit means unlimited; zero prevents new credit. Profile edits preserve change history.
- Explicit repayment assignment to credit receipts, with immutable history, over-assignment protection and CSV export; assignment does not collect cash or reduce debt again.
- Optional agreed repayment due dates on credit sales, retained in active/held baskets, receipts and exports. More → Review customer credit shows receipt balances and recorded-date overdue totals after explicit payment assignments. Unassigned payments keep overdue totals unavailable.
- Linked cash-sale returns with explicit restocking choice and duplicate-refund protection.
- Receipt text export and operating-system print dialog with pagination.
- Supplier receipt balances, later cash payments, and payment history.
- WAT date-range reports and spreadsheet-safe CSV exports.
- Product CSV template, preview and atomic add-only import (up to 10,000 whole-unit products, 5 MB).
- Password-encrypted, tenant-only backup files; restore into a new password-gated shop with validated history.
- Stock receiving with buying cost, cash paid, and the unpaid amount at the time of receipt.
- Configurable buying packages: receive 2 cartons of 24 bottles as 48 bottles of stock; preserve the buying cost and conversion on each receipt. Buying definitions can be archived/restored; factors remain immutable.
- Cash expenses, reasoned cash additions/withdrawals, opening counts, and immutable closing counts with shortage/excess.
- Automatic drawer activity for new cash sales and repayments while a shift is open; older cash remains explicitly unassigned.
- Retry protection for checkout, repayments, stock receiving, expenses, and manual cash movements.
- Shared exact-naira input parsing across checkout, stock, repayments, and cash management.
- Shared semantic theme, offline IBM Plex Sans fonts, and reusable Compose components.

## Important boundaries

- Local tenant isolation and owner passwords are implemented. Online identity, staff roles/memberships, server authorization, remote tenant administration, and encrypted local storage are not yet implemented. This is not ready for untrusted shared-computer or multi-user SaaS production deployment.
- No cloud sync transport, Heroku backend, bank/card verification, or external messages are connected. Native print submission is implemented; real printer output remains unverified. Manual encrypted backups are local files, not cloud backups. The status bar explicitly says so.
- Current stock uses one base selling unit per product and integer checkout quantities. Receiving supports merchant-defined whole-unit cartons/packs with conversion snapshots. Selling by packaging unit, fractional checkout, credit-sale returns/exchanges, full supplier accounting, and other advanced modules remain future work. Later cash supplier payments are allocated to individual stock receipts; supplier master profiles, credits and statements remain pending.
- Backups exclude unfinished baskets, owner credentials and device settings. Restore creates a separate shop and preserves historical cash counts; verify physical cash before trading. Backup encryption does not encrypt the working database.
- Only one cash shift per shop is supported in this build. Open Money → Today's cash and enter the cash physically counted. Cash paid out for expenses or stock requires that open drawer and sufficient recorded cash. Sales/repayments made without an open drawer remain unassigned and are not automatically added to a later shift's opening count.
- Active and held baskets retain item IDs/quantities and payment-entry text/customer/method, without a frozen price snapshot. Holding reserves no stock. Checkout revalidates current price and stock. Review restored goods before payment. A held basket is not an order, a completed sale or a payment record.
- UI currently performs short local repository operations synchronously; large-catalogue profiling, background database execution, complete keyboard shortcuts, screen-reader review, and merchant usability testing remain outstanding.

## Project structure

```text
shared/src/commonMain/kotlin/pos/domain/       Tenant-aware models and exact money logic
shared/src/commonMain/kotlin/pos/ui/           Reusable theme and components
shared/src/commonMain/composeResources/       Bundled fonts and license
desktopApp/src/jvmMain/kotlin/pos/data/        SQLite persistence and durable drafts
desktopApp/src/jvmMain/kotlin/pos/security/    Local workspace password gate
desktopApp/src/jvmMain/kotlin/pos/screens/     Checkout, stock, and cash workspaces
desktopApp/src/jvmMain/kotlin/pos/Main.kt      Desktop shell and shop tools
```

The shared module currently declares a JVM target while keeping domain/UI code in common source sets. Add and test Android/iOS targets and platform persistence adapters in their implementation phases; mobile apps have not been built yet. SQLite JDBC is the first desktop persistence adapter; Room Multiplatform migration/evaluation remains open.

## Design and planning

- [Complete feature specification](FEATURE_SPECIFICATION.md): the full planned product scope, including advanced features and sector modules—not just the first release.
- [Desktop design and interaction guide](DESIGN_GUIDE.md): reference direction, visual system, screen layouts, journeys, offline states, accessibility, and implementation quality rules.
- [Design tokens](design/tokens.json): shared machine-readable colors, typography, spacing, dimensions, and motion values.
- [One-page design brief](design/DESIGN_BRIEF.md): the everyday screen model and visual direction, read before implementation.
- [Interface wording](design/copy.json): canonical labels for selling, payments, money owed, and saved work.
- [Design review](design/DESIGN_REVIEW.md): problems found in version 1.0 and the changes made in version 1.1.
- [Merchant usability test plan](design/USABILITY_TEST_PLAN.md): first-glance and practical task checks; no tests have been run yet.
- [Contributor instructions](AGENTS.md): entry-point rules for developers and AI contributors.

The chosen stack direction is Kotlin Multiplatform/Compose for installed apps, React/TypeScript with TanStack for web, and Heroku for online services. Backend synchronization architecture remains open. Product name, pricing, and delivery schedule are not yet decided. See the [full implementation audit](IMPLEMENTATION_STATUS.md) for remaining requirements and [build verification](BUILD_STATUS.md) for current evidence and limitations.
