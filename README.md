# Nigerian Retail POS

An offline-first Kotlin desktop retail POS for Nigerian merchants, with an Android app sharing the domain, design system and local transaction repositories. The working local app covers the scope below. The full specification remains tracked in IMPLEMENTATION_TODO.md; wider platform support and production rollout acceptance remain unfinished.

## Cloud connection

Desktop and Android now have **More → Cloud connection** for owner cloud sign-in, connecting the first shop, downloading it on a second device, device revocation and foreground synchronization. The initial Heroku backend is deployed at [the POS cloud service](https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com/api/v1/health). Ordinary local work remains available without internet. Sync does not confirm bank/card payments.

Read [the connection guide](cloud/CONNECTION_GUIDE.md) before linking shop records, and [BUILD_STATUS.md](BUILD_STATUS.md) for tested behavior and outstanding acceptance. Local passwords, staff access and recovery codes remain device-local. Conflicts retain records for review; there is no force-overwrite action.

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

## Android development build

Requires JDK 17 and Android SDK platform 35/build tools, configured through Android Studio or an untracked `local.properties`. The initial minimum is Android 11 (API 30). Manager price-approval signing and authenticated cash handover are currently available on API 33+ only. The APK is signed with the development debug key; it is not a production release or physical-hardware compatibility claim.

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb shell am instrument -w ng.retailpos.android.test/androidx.test.runner.AndroidJUnitRunner
```

Android now also includes catalogue/metadata/package editing, owner recovery and staff PIN access, authenticated shared-drawer handover, editable quotations/orders/dispatch, and guarded CSV previews/exports. Additional native workflows remain tracked in the Android coverage matrix.

The APK is at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Records stay in Android app-private storage; they do not synchronize with desktop. The app declares no internet permission and disables Android automatic app-data backup. Explicit encrypted document export/restore is available to the owner. Working records are not encrypted. See [Android plan](ANDROID_IMPLEMENTATION_PLAN.md), [coverage](ANDROID_FEATURE_PARITY.md) and [build evidence](BUILD_STATUS.md) for implementation and acceptance limits.

## Implemented

- Customers can receive/refund cash advances and explicitly apply them to existing credit receipts. Statements distinguish this funding from new cash repayments. Store-credit checkout and change owed remain unfinished.
- Receipt-linked customer debt disputes and reasoned resolution retain the original debt and cash history.
- Supplier credit notes retain unapplied balances, reviewed receipt allocations/reversals and reconciled dated statements.
- One/two-axis variant matrices create separately stocked options with explicit identifiers, prices and opening counts. Saved bundles support reviewed whole-bundle cash sales and component deductions.
- Buying rules include lead times, safety stock and buying multiples; suggestions account for reserved stock. Reports compare current catalogue categories with the preceding equal-length period.
- FIFO stock valuation and COGS use saved buying costs, declared opening costs and original-cost return/repacking sources. Missing cost coverage is unavailable; the app does not claim complete profit.
- Manager price approvals require a separate full-password identity and bind to the exact cart/operation/policy. Shared-drawer handover requires recipient full-password consent and preserves the counted shortage/excess. Multiple-register custody remains unfinished.
- Owner recovery uses an enrolled single-use code with throttling, audit and session revocation. Business backups do not contain credential/recovery secrets.
- Accepted quotations can create frozen-price customer orders with an optional actual cash deposit. Sold order goods can retain local dispatch and merchant-recorded delivery proof/status.
- More → Local app checks previews and exports allowlisted diagnostics. It reports unavailable/not-checked states accurately and sends nothing.

- Local shop creation and password-gated workspace selection.
- Tenant-scoped SQLite products, customers, sales, repayments, and outbox entries.
- Product creation, name/unit/barcode search, price/name/barcode edits, archive/restore and preserved price history.
- Single-item stock counts with the item's configured precision, previous/count/difference history, reason and stale-count rejection.
- Source-linked stock history for opening counts, purchases in converted base units, sales, restocked returns and count corrections. Older goods without saved opening counts remain explicitly unknown.
- Optional per-item reorder levels and a Low stock filter; archived goods are excluded. Product/low-stock snapshots and dated stock movements can be exported as CSV.
- Cash checkout with cash given and change retained on completed receipts, and explicit named-customer partial credit.
- Atomic stock/debt/sale writes and retry deduplication with stable operation identifiers.
- Tenant-scoped active and named held basket recovery after restart, including payment details and an optional note that appears on the completed receipt and sales export.
- Keyboard barcode entry with Enter adds one item; physical scanner testing remains outstanding.
- Searchable sales and receipt details, customer repayments/history, reconciled CSV statements, and clearly scoped money summaries.
- Customer phone/address and credit limits, with live transaction checks against new debt. Blank limit means unlimited; zero prevents new credit. Profile edits preserve change history.
- Explicit repayment assignment to credit receipts, with immutable history, over-assignment protection and CSV export; assignment does not collect cash or reduce debt again.
- Optional agreed repayment due dates on credit sales, retained in active/held baskets, receipts and exports. More → Review customer credit shows receipt balances and recorded-date overdue totals after explicit payment assignments. Unassigned payments keep overdue totals unavailable.
- Customers → Repayment follow-ups schedules local contact dates and records completed or cancelled actions. Staff contact the customer directly.
- Linked cash-sale returns with explicit restocking choice and duplicate-refund protection.
- Receipt text/PDF export (A4, 58 mm, 80 mm) and operating-system print dialog with pagination. Versioned shop/contact/footer settings are captured on each new sale; later edits preserve saved receipt identity.
- Sales → Sale documents previews and exports A4 invoices, delivery notes for handover, and credit notes for recorded returns.
- Sales → Exchange items links the returned goods and replacement receipt for a cash-funded sale. The review shows the amount to collect or refund in cash.
- Supplier profiles with contact/terms/change history, explicit stable-account purchase links, receipt balances, later cash payments and dated CSV/PDF statements. Legacy name-only receipts stay unlinked until explicitly selected.
- More → Suppliers & payments → Receipts & payments supports reviewed returns against the original purchase. Saving removes the goods and reduces the amount owed on the receipt. Any difference explicitly received in cash goes into the open drawer and appears on supplier statements.
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
- Stock → Manage → Variant groups separately stocked goods under a searchable family and label. Sell also offers a Frequent items filter based on sales retained in the last 30 shop days.
- Stock → Manage → Photo supports crop, rotation and up to six additional gallery photos. The main photo appears in Stock and Sell.
- Stock tools → Import product photos reviews a folder of files named by SKU or barcode and applies the selected main photos as one batch.
- Stock tools includes Reorder suggestions and Purchase cost history. Reorder suggestions use local sales and open approved orders. Purchase comparisons use original receipt prices and saved buying unit conversions.
- An owner can write off debt from a linked credit receipt and restore that write-off under Customers → View history. Both actions keep a reason and appear in statements and credit ageing.
- Percentage item discounts and fixed/percentage whole-basket discounts with exact allocation, explicit re-review after basket changes, saved drafts and conserved partial refunds.
- Dated customer and supplier CSV/PDF statements with reconciled opening/closing debt.
- Searchable bundled help under More → Help & practice. Practice opens a separate process with sample goods, an in-memory database and temporary drafts, without opening shop records.
- Shared semantic theme, offline IBM Plex Sans fonts, and reusable Compose components.

## Important boundaries

- Local tenant isolation, owner/staff passwords and local role permissions are implemented. Online identity/memberships, server authorization, remote tenant administration, and encrypted local storage are not yet implemented. This is not ready for untrusted shared-computer or multi-user SaaS production deployment.
- Cloud synchronization is now configured separately by the owner under More; bank/card verification and external messages remain unconnected. Native print submission is implemented; real printer output remains unverified. Manual encrypted backups are local files, not cloud backups. Cloud status is separate from local saving and payment verification.
- Stock uses one fixed base unit and precision per product. Measured transaction quantities use at most 2,147,483,647 scaled increments (for example, 2,147.483647 at six decimal places). Stored stock also enforces exact supported bounds. Buying and selling pack counts remain whole. Historical transactions preserve their precision and conversion. Nested conversions, credit and external-payment exchanges, unapplied supplier credits, landed costs and complete supplier accounting remain pending. Stable supplier accounts include explicitly linked purchases. Legacy receipt-name statements remain separate.
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

The shared module declares JVM and Android targets. Desktop uses SQLite JDBC; Android uses a native SQLite adapter for the same repository rules. Android editing and document support cover a subset of desktop workflows; see [Android feature coverage](ANDROID_FEATURE_PARITY.md). iOS and web applications remain unfinished.

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

Images: add a product photo under **Stock → Manage → Photo** and a shop logo under **More → Shop & receipt details → Shop logo**. Choose a PNG/JPEG, preview it, enter a reason and save. Product thumbnails appear in Stock/Sell; the logo appears in the workspace and new receipt PDFs. Saved images are local database copies included in encrypted backups. Replacement/removal retains history; earlier receipts keep their captured logo. Supplier invoice PNG/JPEG evidence also has an on-demand preview. Source imports support up to 8 MB/12 million pixels, normalized to at most 768 px/512 KB; retained image history is capped at 16 MB per shop. Crop and rotation are available in the photo editor. Product galleries and bulk main-photo import are available under Stock.

Local controls (17 November): **Stock → Stock tools → Minimum selling prices / Repack goods** and **Money → Expenses → Receipt file & history / Owner money** are available. Minimums are checked after all discounts; repacking uses your explicitly declared quantities and changes both stocks together. Owner cash entries remain separate from sales/expenses. Expense receipt files are copied locally and retained in encrypted backups. **555 tests pass** with a rebuilt Linux image and selected actual disconnected desktop checks. This historical local checkpoint did not include cloud sync; the initial connection is described above; remaining local work is ordered in [LOCAL_EXECUTION_TODO.md](LOCAL_EXECUTION_TODO.md).

**More → Customer orders** records an agreed price and quantity, takes deposits into an open drawer, and links each counter collection to a saved sale. You can reserve the remaining goods through a selected WAT date. Sell shows available stock and Stock shows both on-hand and reserved quantities. A cancellation with an unused cash deposit requires a refund from the open drawer.

**Stock → Stock tools** now includes Promotions and Landed costs. Configure a fixed or percentage offer for a selling unit, then review and apply it in Sell. Use Landed costs to split an existing paid expense among purchase receipts. **Suppliers → Purchase orders** records and resolves discrepancies on approved orders. [Build evidence](BUILD_STATUS.md)

Receipt design is available in **More → Shop & receipt details → Receipt design**. Choose Classic, Modern or Compact, add a single or double frame, set a preset or custom accent colour, align the heading and show or hide your logo. You can add a tagline, returns note and an extra PNG/JPEG artwork image. The preview renders the actual PDF layout for A4, 58 mm or 80 mm paper. Save with a reason to use the design on new sales. Each completed sale retains its design and artwork for preview, PDF export and printer submission in Sales. Check physical paper and printer compatibility on your own printer.

Money amounts display with the naira symbol, thousands separators and two decimal places. Monetary entry fields show the naira symbol and group digits as you type, preserving the entered decimal digits and exact kobo value.

Modern receipts now include a larger branded heading, aligned item and payment amounts and a highlighted total panel. The receipt preview supports Fit, 150% and 200% zoom. After **Finish sale**, **Print receipt** is available immediately beside **New sale**. Printing uses the completed sale's saved design.
