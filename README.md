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
- Single-item stock counts with the item's configured precision, previous/count/difference history, reason and stale-count rejection.
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
- Receipt text/PDF export (A4, 58 mm, 80 mm) and operating-system print dialog with pagination. Versioned shop/contact/footer settings are captured on each new sale; later edits preserve saved receipt identity.
- Supplier profiles with contact/terms/change history, explicit stable-account purchase links, receipt balances, later cash payments and dated CSV/PDF statements. Legacy name-only receipts stay unlinked until explicitly selected.
- Supplier purchase orders with editable drafts, approval, cancellation and atomic multi-line partial receiving. Approval creates no stock, payment or debt; actual deliveries retain receipt costs and remaining quantities.
- Durable multi-item stock-count sessions with draft recovery, conservative movement cutoffs and atomic posting.
- Reasoned immutable stock removals for damage, spoilage/expiry, loss, samples, donations and owner use; no sale, cash or expense is fabricated.
- Code128 barcode/price label previews, PNG export and native print submission for saved base/pack barcodes (80 × 50 mm, 300 DPI). Hardware output remains unverified.
- WAT date-range reports and spreadsheet-safe CSV exports.
- Product CSV template, preview and atomic add-only import (up to 10,000 products, 5 MB). An optional `quantity_precision` column explicitly enables measured opening quantities.
- Password-encrypted, tenant-only backup files; restore into a new password-gated shop with validated history.
- Stock receiving with buying cost, cash paid, and the unpaid amount at the time of receipt.
- Configurable selling packs: independent prices/barcodes, mixed carton/loose baskets, shared stock deduction and historical-unit cash returns. Define these under Stock → Manage → Selling units.
- Configurable buying packages: receive 2 cartons of 24 bottles as 48 bottles of stock; preserve the buying cost and conversion on each receipt. Buying definitions can be archived/restored; factors remain immutable.
- Cash expenses, reasoned cash additions/withdrawals, opening counts, and immutable closing counts with shortage/excess.
- Automatic drawer activity for new cash sales and repayments while a shift is open; older cash remains explicitly unassigned.
- Retry protection for checkout, repayments, stock receiving, expenses, and manual cash movements.
- Shared exact-naira input parsing across checkout, stock, repayments, and cash management.
- Per-product quantity precision from zero to six decimal places, chosen when creating an item. Exact scaled stock supports measured checkout, receiving, counts and cash returns; whole selling/buying packs retain explicit conversions. Enter a quantity and choose Apply quantity before payment.
- Physical note/coin count sheets saved with opening/closing cash, retained in encrypted backups. Closing can hide expected cash in the form; the dashboard may already have shown it. Handover and per-register drawers remain pending.
- SKU, category, brand, alternative search names, favourites and category filters with preserved metadata history.
- Percentage item discounts and fixed/percentage whole-basket discounts with exact allocation, explicit re-review after basket changes, saved drafts and conserved partial refunds.
- Dated customer and supplier CSV/PDF statements with reconciled opening/closing debt.
- Searchable bundled help under More → Help & practice. Practice opens a separate process with sample goods, an in-memory database and temporary drafts, without opening shop records.
- Shared semantic theme, offline IBM Plex Sans fonts, and reusable Compose components.

## Important boundaries

- Local tenant isolation, owner/staff passwords and local role permissions are implemented. Online identity/memberships, server authorization, remote tenant administration, and encrypted local storage are not yet implemented. This is not ready for untrusted shared-computer or multi-user SaaS production deployment.
- No cloud sync transport, Heroku backend, bank/card verification, or external messages are connected. Native print submission is implemented; real printer output remains unverified. Manual encrypted backups are local files, not cloud backups. The status bar explicitly says so.
- Stock uses one fixed base unit and precision per product. Measured transaction quantities use at most 2,147,483,647 scaled increments (for example, 2,147.483647 at six decimal places); stored stock also enforces exact supported bounds. Buying/selling pack counts remain whole. Historical transactions preserve their precision and conversion. Nested conversions/repacking, credit-sale returns/exchanges and full supplier accounting remain pending. Stable supplier accounts include only explicitly linked purchases; legacy receipt-name statements remain separate. Supplier credits, landed costs and complete supplier accounting remain pending.
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

Negotiated unit prices are available through Sell → Change price. Enter the lower price and reason; receipts retain the original price and cash returns refund the net amount. Staff approval limits and percentage/basket discounts remain pending.

Credit review now shows current unpaid balances by days late, including a separate undated group, and exports all customer ageing to CSV. Exact bands remain unavailable while repayments are unassigned; assign payments to receipts under Customers first.

Payment assignments can be corrected under Customers → View history → Assign payments to receipts → Undo assignment. Enter a reason; the original assignment stays in history. Its amount becomes available to assign again. This changes receipt coverage only; customer debt, cash and stock stay unchanged.

The remaining whole-product work is tracked in [IMPLEMENTATION_TODO.md](IMPLEMENTATION_TODO.md). Checkboxes represent verified subtasks, and the full feature specification remains authoritative.

Local staff access uses owner, manager and cashier roles. Owners manage staff under More → Staff & permissions. Staff can set or remove a six-digit convenience PIN from sign-in using their full password. PIN access lasts up to 12 hours after full-password sign-in; PIN use does not renew it. PIN/password attempts share a persistent cooldown, and account changes/password resets remove PIN access. The owner continues using the owner password. Remote accounts remain pending. Cashiers can make cash sales, view receipts and read help; managers can perform operational work; owner-only staff management and backup actions are enforced through the active application session.

Local sign-in pauses for 60 seconds after five unsuccessful attempts per shop/account. Sessions lock after 15 minutes of inactivity; ordinary app input keeps a valid session active. Restarting the app does not clear failed-login cooldowns.

Activity history is available under More. New business actions retain the signed-in person's name and role at the time of saving; changing an account later does not rewrite history. Reports → Activity history exports business events for the selected dates. Business history travels with encrypted shop backups. Owner-only Staff access history records account creation, changes and password resets separately on this computer, without passwords or session tokens; it is excluded from business backups. Earlier records may be absent or explicitly unattributed. This local history does not protect against someone with filesystem access.

Review customer credit supports an earlier recorded date and configurable late-day bands for that report. Historical debt uses the payments, assignments and corrections saved by that WAT date; customer names/contact details remain current. Reports also exports product sales/refunds in base units and staff recording activity. Refunds use their own dates and recorder; these reports do not calculate profit or commissions.

The latest local batch adds explicitly unverified transfer/card portions with cash/credit splits, reconciled credit-sale returns, frozen quotations with reviewed full-cash conversion, reviewed bulk catalogue CSV updates, reduction-only quantity price lists, supplier invoice metadata/history and opt-in versioned encrypted local backups. **508 tests pass**, and the Linux image builds. Actual disconnected sample checks and precise limits are in [BUILD_STATUS.md](BUILD_STATUS.md); remaining local work is tracked in [LOCAL_DESKTOP_TODO.md](LOCAL_DESKTOP_TODO.md). Provider verification, external refunds, exchanges, cloud services and the complete feature specification remain unfinished.

Images: add a product photo under **Stock → Manage → Photo** and a shop logo under **More → Shop & receipt details → Shop logo**. Choose a PNG/JPEG, preview it, enter a reason and save. Product thumbnails appear in Stock/Sell; the logo appears in the workspace and new receipt PDFs. Saved images are local database copies included in encrypted backups. Replacement/removal retains history; earlier receipts keep their captured logo. Supplier invoice PNG/JPEG evidence also has an on-demand preview. Source imports support up to 8 MB/12 million pixels, normalized to at most 768 px/512 KB; retained image history is capped at 16 MB per shop. Crop/rotation, galleries and remote image sync remain pending.

Local controls (17 November): **Stock → Stock tools → Minimum selling prices / Repack goods** and **Money → Expenses → Receipt file & history / Owner money** are available. Minimums are checked after all discounts; repacking uses your explicitly declared quantities and changes both stocks together. Owner cash entries remain separate from sales/expenses. Expense receipt files are copied locally and retained in encrypted backups. **555 tests pass** with a rebuilt Linux image and selected actual disconnected desktop checks. Cloud sync stays outside the current implementation scope; remaining local work is ordered in [LOCAL_EXECUTION_TODO.md](LOCAL_EXECUTION_TODO.md).
