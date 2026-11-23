# Desktop build status

23 November 2026 · Orders, stock holds, promotions and purchasing

## Receipt customization and money entry — 23 November 2026

**617 tests pass** (79 shared and 538 desktop), with zero failures, errors or skips. The Linux image rebuilt with `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`.

- More → Shop & receipt details → Receipt design offers Classic, Modern and Compact, no/single/double frames, preset or custom accent colour, heading alignment, logo visibility, tagline, policy and an additional PNG/JPEG image. Actual PDF pages provide the preview, including pagination. Rendering runs off the UI thread after a short edit delay.
- New sales capture their design and immutable image references. Saved Sales can preview/export the captured design and submit that PDF through the native printer dialog. Existing version-one settings remain readable with the classic left-aligned default. Encrypted backup includes artwork bytes and import history.
- Shared monetary inputs show a naira prefix and live thousands grouping, including cash, prices, deposits, repayments and credit limits. Displayed amounts use naira, grouping and two decimal places. Input preserves entered decimal digits and integer kobo semantics. Quantity, percentage and denomination-count fields keep numeric entry.

### Actual visual and desktop checks

Used isolated Linux Compose at 1440 × 1000 with sample records and loopback-only networking. Selected Modern, Double line and Blue, entered a tagline and policy, imported a sample PNG through the native chooser, chose A4 and saved with a reason using Tab/Enter. A subsequent ₦14,000 sample sale received ₦15,000 with ₦1,000 change. Its saved receipt preview showed the captured design and artwork. [Editor](artifacts/desktop-receipt-design-modern.png), [artwork](artifacts/desktop-receipt-artwork-preview.png), [saved settings](artifacts/desktop-receipt-design-saved.png), [saved receipt](artifacts/desktop-saved-receipt-design.png), [money entry](artifacts/desktop-money-input-grouping.png).

Generated nine renderer fixtures across three styles and A4/58/80 mm. Inspected rendered first pages for A4 Modern and 58 mm Compact, plus the last page of an 80 mm Modern receipt. Frames, text, continuation identity, policy and artwork stayed within their page bounds. [A4 render](artifacts/receipt-design-a4-render.png), [thermal render](artifacts/receipt-design-thermal-render.png).

Tests cover old settings, immutable sale appearance, recovery, image provenance, tenant permissions, all style/width combinations, preserved unconfirmed-payment/debt text, pagination, failed-export preservation and input cursor mappings. Physical printing, Windows/native printer compatibility and merchant usability testing remain unperformed. Text export retains its plain-text format. Design customization applies to sales receipts. Other document templates retain their existing layouts.

## Desktop visual refinement — 23 November 2026

The merchant requested clearer icons and a more intuitive, attractive interface. The shared controls now use locally bundled Lucide outline icons with persistent text, icon-backed headings and empty states, and semantic notice symbols. Navigation has matching destination icons and a shop mark. Product image placeholders use package symbols. More presents permission-filtered tools as grouped responsive cards. Checkout keeps the goods, sale and payment hierarchy with clearer search and receipt cues. The design guide and token file record the updated corner and icon roles. Lucide attribution is retained in design/licenses/lucide.txt.


All **608 tests pass** (77 shared and 531 desktop), with no failures, errors or skips. Rebuilt the Linux application using `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`.

Checked actual Linux Compose screens in isolated practice with loopback-only networking at 1440 × 1000. Reviewed Sell, Stock, Customers, Sales, Money, shop tools and an empty order list. Tab/Enter opened Customer orders from the tool cards. Also checked Sell and Money in a 1100 × 800 window. Widened the repeated Add stock action after its added icon caused wrapping. These are selected visual and keyboard checks. Full accessibility and merchant comprehension testing remain pending.

[Sell](artifacts/desktop-ui-sell.png), [shop tools](artifacts/desktop-ui-shop-tools.png), [Stock](artifacts/desktop-ui-stock.png), [Customers](artifacts/desktop-ui-customers.png), [Sales](artifacts/desktop-ui-sales.png), [Money](artifacts/desktop-ui-money.png), [smaller Sell window](artifacts/desktop-ui-sell-compact.png), [order empty state](artifacts/desktop-ui-orders-empty.png).

## Current integrated checkpoint

**608 tests pass** (77 shared and 531 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image rebuilt successfully.

- More → Customer orders saves agreed goods, units and prices, cash deposits and explicit cancellation refunds. Partial and final counter collections create linked sale receipts and apply the remaining deposit once. Drawer reports and receipt exports retain the linked amounts.
- Remaining order quantities can be reserved through a selected WAT date. Holds shrink with collection and end on release, cancellation, completion or expiry. Checkout, supplier returns and repacking protect reserved stock. Stock shows on-hand, reserved and available quantities, and Sell uses available quantities. Long-open screens refresh the WAT date every 30 seconds.
- Stock tools → Promotions configures dated fixed or percentage offers, selling-unit and quantity eligibility and optional sale caps. Sell reviews the exact reduction. Saving checks the terms again and preserves offer details in held baskets and receipts.
- Stock tools → Landed costs allocates an existing paid expense equally or by purchase value. Shares reconcile to the kobo, and revision/removal keeps earlier reasons and amounts. Purchase cost history shows the allocation.
- Suppliers → Purchase orders records and resolves shortages, damaged goods and unexpected goods, with an optional link to a matching later receipt. Quarantine and supplier claims remain pending.

### Disconnected desktop checks

Used actual Linux Compose at 1440 × 1000 with isolated sample records and loopback-only networking. Exercised selected keyboard entry, Tab/Enter and pointer actions.

- Recorded a ₦700 deposit and collected two ₦450 soap pieces. The review applied ₦700 and collected ₦200, then showed the remaining quantities and linked receipt. [Deposit](artifacts/desktop-customer-order-deposit.png), [collection review](artifacts/desktop-customer-order-collection-review.png), [partial collection](artifacts/desktop-customer-order-part-collected.png)
- Reserved 13 soap pieces from 96 on hand. Stock displayed 13 reserved and 83 available, and Sell displayed 83 available. [Saved hold](artifacts/desktop-order-stock-held.png), [Stock](artifacts/desktop-order-stock-available.png), [Sell](artifacts/desktop-order-sell-available.png)
- Created a one-day 20% malt promotion, reviewed a ₦300 reduction, put aside and resumed the basket, then saved ₦13,700 cash. The saved receipt retained the offer and usage increased by one. [Review](artifacts/desktop-promotion-review.png), [resumed basket](artifacts/desktop-promotion-resumed.png), [receipt](artifacts/desktop-promotion-receipt.png)
- Allocated a ₦750.01 transport expense across ₦4,000 and ₦20,000 receipts as ₦125.00 and ₦625.01. Revised to an equal split and removed it with a reason. Expected cash stayed ₦8,749.98 through those allocation actions. [Review](artifacts/desktop-landed-cost-review.png), [history](artifacts/desktop-landed-cost-revision-history.png), [drawer](artifacts/desktop-landed-cost-cash.png)
- Recorded one soap piece missing from an approved four-piece purchase order, then resolved it with the supplier acknowledgement reason. [Review](artifacts/desktop-purchase-discrepancy-review.png), [resolution](artifacts/desktop-purchase-discrepancy-resolved.png)

Automated coverage includes deposit/refund reconciliation, partial collection and encrypted recovery, competing stock holds, cashier permission enforcement, WAT midnight expiry, stale changes and backup tampering, promotion validity and usage, exact expense allocation and discrepancy history. Native hold release and midnight expiry were not exercised. Windows, physical peripherals, comprehensive accessibility, replacement-machine recovery and merchant testing remain pending. The remaining local backlog is in LOCAL_DESKTOP_TODO.md. Cloud sync is excluded by the user's instruction.

## Earlier cash exchange checkpoint

**593 tests pass** (77 shared and 516 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Sales supports exchanges from cash receipts and cash-funded replacement receipts. The review uses the original saved return values and current replacement prices. The linked return, replacement sale, stock changes and cash difference save together. Receipts and sales exports show the value applied through the exchange.
- Supplier returns can reduce outstanding receipt debt and record the excess as cash received into an open drawer. The quantity, debt, cash movement and history save together. Supplier statements and reports show the reimbursement alongside the debt reduction.
- Stock tools can match up to 64 PNG or JPEG files to active products by SKU or barcode. Merchants preview the matches, select photos and record a reason. Selected main photos save together using the existing revision history and encrypted backup storage.

### Disconnected desktop checks

Used the Linux app at 1440 × 1000 in an isolated practice workspace with loopback-only networking. Used keyboard entry, Tab/Enter and pointer actions.

- Exchanged a ₦500 sample malt drink for ₦450 soap. The replacement used ₦450 of return value and Money showed a single ₦50 refund. [Review](artifacts/desktop-cash-exchange-review.png), [replacement receipt](artifacts/desktop-cash-exchange-saved.png), [drawer](artifacts/desktop-cash-exchange-drawer.png)
- Returned two sample rice bags. The return reduced supplier debt by ₦18,000 and recorded ₦2,000 received into the drawer. [Review](artifacts/desktop-supplier-reimbursement-review.png), [settled receipt](artifacts/desktop-supplier-reimbursement-saved.png), [drawer](artifacts/desktop-supplier-reimbursement-drawer.png)
- Opened a folder in the native chooser, matched a photo to a sample barcode, skipped an unmatched file and saved the selected photo using the keyboard. Verified the thumbnail in Stock. [Review](artifacts/desktop-bulk-photo-import-review.png), [Stock](artifacts/desktop-bulk-photo-import-stock.png)

Automated tests cover higher, lower and equal exchange prices, rollback, receipt linkage and encrypted restore. Supplier reimbursements have tests for fully and partly paid purchases, permission checks and recovery. Bulk import tests cover duplicate matches, shop isolation, retries and stale-review rollback. Windows, physical peripherals, full accessibility, replacement-machine recovery and merchant testing remain pending.

## 21 November checkpoint — stock tools, documents and debt corrections

**585 tests pass** (77 shared and 508 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Stock tools now shows reorder suggestions using exact stock units, 30 WAT days of sales after returns, the approved quantities still due, and a configurable coverage period. It exports the visible review to CSV.
- Stock tools shows historical buying prices and compares the latest saved receipt for each explicitly linked supplier. It sorts exact price ratios using the original buying conversion and marks rounded stock-unit prices.
- Sales exports A4 invoices, delivery notes for handover and credit notes for saved returns. Invoices retain original amounts and show manually recorded external payments with their unconfirmed status. Delivery notes leave handover confirmation to the recipient.
- The photo editor applies JPEG orientation from the file and supports cropping and rotation. Each product can hold six additional photos with reasoned revision history. Encrypted backups include the revisions, and saved receipts retain the logo they captured.
- Owners can review, write off and restore linked receipt debt. Credit ageing, receipt allocation, statements and exports reconcile both recorded events. A return against an affected receipt requires restoring active write-offs first.

### Disconnected desktop checks

Ran actual Linux Compose at 1440 × 1000 with sample records in a loopback-only network namespace. Used text entry, Tab/Enter and selected pointer actions.

- Updated the reorder review to 14 days of coverage and inspected its saved CSV. The file showed four malt bottles and one rice bag suggested. [Reorder suggestions](artifacts/desktop-reorder-suggestions.png)
- Opened the linked sample supplier comparison and inspected the exported receipt reference, base-unit conversion and ₦10,000 per bag price. Exact comparisons between different buying units have automated coverage. [Supplier price comparison](artifacts/desktop-purchase-cost-comparison.png)
- Opened and exported all three PDFs through the native file chooser and checked the resulting file text. The invoice retained ₦1,500 total and ₦1,000 owed at sale. The delivery note listed three bottles and blank handover fields. The credit note tied ₦1,000 in returned goods to a ₦750 debt reduction and ₦250 cash refund. [Invoice](artifacts/desktop-sales-invoice-preview.png), [delivery note](artifacts/desktop-delivery-note-preview.png), [credit note](artifacts/desktop-credit-note-preview.png)
- Rotated a sample product photo and cropped its left edge by 8 percent. Saved it as the main photo and added the original image as a gallery photo. [Crop and rotation](artifacts/desktop-product-photo-crop-rotate.png), [gallery](artifacts/desktop-product-photo-gallery.png)
- Reviewed and saved a ₦250 write-off against a ₦750 sample balance, then restored it. The saved balance was ₦500 after write-off and ₦750 after reinstatement. The exported customer CSV recorded both signed amounts with the same receipt reference. [Review](artifacts/desktop-debt-writeoff-review.png), [saved write-off](artifacts/desktop-debt-written-off.png), [reinstated history](artifacts/desktop-debt-reinstated.png)

Automated coverage includes encrypted recovery, financial rollbacks, WAT statement cutoffs, purchase-unit conversion, historical logo preservation and JPEG orientation. Windows, physical peripherals, comprehensive keyboard and accessibility acceptance, replacement-machine recovery, and merchant trials remain pending.

## 20 November checkpoint — checkout and local account workflows

**570 tests pass** (77 shared and 493 desktop). Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with Gradle 8.13 and JDK 17. The rebuilt Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Checkout preserves cash tender and change on completed sales. Receipts and CSV distinguish cash applied to the sale from cash handed over. Optional sale notes survive active and held baskets and appear on completed receipts and exports.
- Sell ranks its Frequent items filter by the number of receipts in the last 30 shop days that still retain each item. Fully returned items do not count toward that receipt's ranking.
- Stock → Manage → Variant groups individual catalogue items with a family and label. Each item keeps its own stock, price, SKU, barcode and photo. Family and label are searchable in Stock and Sell. Reasoned variant changes have revision history.
- Customers → Repayment follow-ups records local contact dates and dated completion or cancellation reasons. It shows the customer’s current debt. Scheduling and closing a follow-up leave money owed and drawer balances intact.
- Suppliers → Receipts & payments reviews returns against the original buying quantity and price. A saved return removes the exact stock quantity and reduces the amount owed on the linked receipt. Dated statements and exports record the credit separately from cash. Reimbursements and excess supplier credits remain pending.

An automated encrypted-file round trip restores the new records to another database and checks the resulting stock, drawer balance, sale receipt, customer debt, supplier debt and Frequent items ranking. Additional tests cover stale reviews, permissions, write rollback, missing history and mixed selling-unit ranking.

### Disconnected desktop checks

Used actual Linux Compose with sample records in an isolated 1440 × 1000 display and a loopback-only network namespace. Exercised typing, Tab/Enter and selected pointer actions.

- Submitted a short cash payment, corrected it to ₦15,000 for a ₦14,000 sale, and reopened the record. The successful checkout showed ₦1,000 change and the receipt retained both amounts. [Sale saved](artifacts/desktop-tender-change-saved.png), [saved receipt](artifacts/desktop-tender-change-receipt.png).
- Added a counter collection note and found it on the reopened receipt. The held-basket note recovery check was automated. [Receipt with note](artifacts/desktop-sale-note-receipt.png).
- Opened Frequent items and saw the malt drink from the sample sales. Assigned the sample soap to a family using the keyboard and found it in Sell using only the family name. [Frequent items](artifacts/desktop-frequent-items.png), [saved variant](artifacts/desktop-product-variant-saved.png), [variant search](artifacts/desktop-product-variant-search.png).
- Scheduled and completed a same-day repayment follow-up for the sample customer. Both the open and completed views showed ₦750 owed. [Due follow-up](artifacts/desktop-repayment-followup-due.png), [completed history](artifacts/desktop-repayment-followup-completed.png).
- Returned one bag of rice. Stock changed from 34 to 33 bags. The ₦10,000 credit reduced the receipt debt from ₦18,000 to ₦8,000. Exported a receipt-name CSV and a linked supplier account PDF through the native chooser and checked the totals in both files. [Review](artifacts/desktop-purchase-return-review.png), [saved return](artifacts/desktop-purchase-return-saved.png), [stock history](artifacts/desktop-purchase-return-stock.png), [statement](artifacts/desktop-purchase-return-statement.png).

Windows, physical peripherals, comprehensive keyboard and accessibility acceptance, replacement-machine recovery, and merchant usability testing remain pending. Cloud synchronization is outside the current scope.

## 17 November checkpoint — local trading controls

**555 tests pass**: 77 shared + 478 desktop, zero failures/errors/skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` using installed Gradle 8.13/JDK 17; successful in 1 minute 14 seconds. Linux image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 519-test checkpoint below. `git diff --check` passes.

- Minimum selling prices have reasoned immutable revisions, optimistic edit checks and tenant/permission boundaries. A base-unit floor also constrains packs; final line values include allocated basket discounts and fractional minimums round upward to kobo. Below-minimum checkout fails atomically. New sales retain policy checks, with a completeness marker for recovery; old sales are not judged by a later policy. Owner/manager settings exist; separate-identity approvals/overrides and margin/cost warnings remain pending.
- Repacking uses explicitly declared source/output quantities between two distinct catalogue products. Saved recipes retain revisions; whole batches require current-stock review and a reason. Both exact stock changes save in one transaction, appear in stock history and invalidate stale physical counts, including net-zero movement cases. There is no inferred equivalence, new selling-unit definition, cost allocation, sale or cash effect. Multi-input assembly, costing/yield-loss accounting and nested package definitions remain pending.
- Expense receipts copy real bounded PDF/PNG/JPEG bytes into local storage. Reasoned replacements/removals retain history; current and earlier files can be previewed/exported. Encrypted backups validate attachment content, ownership, chronology and revision/activity completeness. Files support up to 1 MB each with an 8 MB retained expense-evidence budget per shop. Expense amount/cash remains unchanged. Accrued/non-cash expenses remain pending.
- Owner money records classify actual drawer cash as contribution or drawing, with owner name, reason, current drawer/balance review, immutable cash source and CSV. An explicit equal opposite correction requires actual cash movement and preserves the original. These cash classifications are separate from sales/operating expenses; formal equity/loan accounts, journals and per-cashier custody remain pending.
- Advanced Stock actions now live under a labeled Stock tools menu, keeping Add item/Add stock and the existing five primary destinations. Backup table count bounds increase from 64 to 128 for the added modules; 64 MB file, 2 MB field and 200,000-row limits remain in force. Old supported backups remain recoverable.

### Actual native offline evidence — 17 November

The rebuilt installed Linux app ran in its isolated sample practice mode on Xvfb at 1440 × 1000, with a network namespace containing only loopback and no external routes. Keyboard text entry/Tab and selected pointer actions were performed; complete keyboard-only/accessibility testing and merchant studies were not performed.

- Set sample Malt minimum to ₦450/bottle, then negotiated ₦400 each on three bottles. Checkout rejected the ₦1,200 line against its ₦1,350 minimum instead of saving: [saved policy](artifacts/desktop-minimum-price-saved.png), [blocked payment](artifacts/desktop-minimum-price-blocked.png).
- Entered owner name, ₦2,000 and reason, reviewed the direction against ₦10,000 drawer cash, then saved: [review](artifacts/desktop-owner-contribution-review.png), [saved contribution](artifacts/desktop-owner-contribution-saved.png). Drawings/corrections are covered by automated integrity tests; their full native journey was not performed in this pass.
- Selected a clearly labeled sample receipt PNG through the native chooser, entered a reason, saved a copy and opened the saved revision preview: [saved expense receipt](artifacts/desktop-expense-receipt-saved.png), [preview](artifacts/desktop-expense-receipt-preview.png). The existing expense remained ₦1,250. Native overwrite/cancel/historical-removal journeys remain broader acceptance work.
- Created an explicit one-bag-to-five-kg sample recipe and saved two reviewed batches. Bag stock changed 34→32, loose stock 10→20 kg: [review](artifacts/desktop-repacking-review.png), [completed history](artifacts/desktop-repacking-saved.png), [stock result](artifacts/desktop-repacking-stock.png).

Automated coverage includes source isolation, permission denial, late-actor/injected-write rollback, stale/retry identities, exact measured/basket pricing, immutable histories, encrypted recovery and missing/tampered records. Full compact-layout, Windows/hardware, replacement-machine recovery, performance and merchant acceptance remain pending. Cloud synchronization and live provider/messaging services were excluded and are not implemented by this batch.

## Previous integrated checkpoint — shop and product images

**519 tests pass**: 72 shared + 447 desktop, zero failures/errors/skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with installed Gradle 8.13/JDK 17; successful in 34 seconds. Linux image rebuilt. This supersedes the 508-test checkpoint below.

- Product photo editor under Stock → Manage → Photo; separate shop logo editor under More → Shop & receipt details. PNG/JPEG inputs are decoded, resized with preserved proportions, and re-encoded to PNG, stripping source metadata. Source cap 8 MB/12 million pixels; stored cap 768 pixels/512 KB. Retained image assets have a 16 MB/shop budget, including historical versions. One active image per product and one shop logo are supported.
- Bytes are stored in tenant-scoped SQLite tables, independent of the original file. Reasoned image replacement/removal has immutable history, actor activity, stale-version checks and stable retries. Archived product edits are denied. Encrypted backups contain image bytes and history; legacy backups without images remain supported. Working SQLite remains unencrypted.
- Stock/Sell show fixed-size aspect-fit thumbnails with names and prices always available. Workspace shows the current shop logo. New sales capture their logo independently of later logo changes. A capture marker detects missing receipt-image records during restore. Clock rollback cannot capture a future-dated logo into a sale.
- Receipt PDFs render the captured logo on the first page only, preserving pagination and readable text on A4/58/80 mm. Existing receipts without a logo snapshot never acquire today's logo.
- Existing supplier invoice attachments already contained managed PDF/PNG/JPEG bytes and historical backup support; previous source-path-only documentation was incorrect. Added on-demand current/historical image preview. Increased the bounded backup field allowance to 2 MB so a supported 1 MB attachment fits after base64 encoding; the whole backup stays bounded at 64 MB.

### Image verification evidence

Seven image-integrity tests cover source-file deletion, normalization and invalid input, reopen, encrypted restore, shop isolation, permission denial, late-actor rollback, stale/replayed changes, removal/history, preserved receipt logos, omitted/corrupt snapshots and legacy backups. Three PDF tests verify first-page-only images, text clearance/pagination on all three papers, invalid-logo rejection before replacement and visual fixtures. One invoice regression exercises encrypted backup/restore of a valid image larger than 768 KB.

Actual native Linux Compose ran in the isolated practice workspace, Xvfb 1440 × 1000 and a network namespace with loopback only/no external route. Chose a clearly labeled SAMPLE SHOP test raster through the native picker, typed reasons and used Tab/Enter to save. Inspected uploaded product image in [Stock](artifacts/desktop-product-image-stock.png) and [Sell](artifacts/desktop-product-image-sell.png), and saved shop logo in [settings/sidebar](artifacts/desktop-shop-logo-saved.png). Test raster use proves import/display paths, not realistic catalogue photography or merchant comprehension.

Rendered actual generated receipt PDFs with Poppler and inspected all widths: [A4](artifacts/pdf-receipt-logo-a4.png), [58 mm](artifacts/pdf-receipt-logo-58mm.png), [80 mm](artifacts/pdf-receipt-logo-80mm.png). No physical printer output was tested. Native invoice preview, full save/remove/cancel/failure journeys, Windows, accessibility and merchant research remain unperformed. EXIF orientation is stripped without automatic rotation; cropping/rotation controls, galleries, expense receipt attachments and cloud media transport remain pending. The already-open trading app must be reopened to load the rebuilt version; its data/process was not interrupted for testing.

## Previous 508-test checkpoint

**508 tests pass**: 72 shared and 436 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with installed Gradle 8.13 and JDK 17; successful in 38 seconds. Linux image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 424-test checkpoint below. `git diff --check` passes.

Four specialist agents reviewed quotation persistence/output/UI, external-payment records, credit-return reconciliation and bulk catalogue updates. Root integrated schemas/backups, permission routes, report semantics and recovered drafts. Small commits separate domain, persistence, output, UI and fixes.

### Local scope delivered in this continuation

- Manual transfer/card records retain reference and receiving account/terminal, with explicit **not confirmed** status. Checkout supports one external portion plus cash and optional named-customer credit. External money is excluded from drawer cash. Drafts, receipts, search and CSV preserve it. Provider verification, settlement, reversals and external-method refunds remain unavailable.
- Credit-sale returns reduce unpaid receipt debt first, then refund paid value in cash. Existing repayments must be assigned; reviewed funding changes invalidate the return. Assignment reversal cannot release already-refunded funding. Customer history, dated statements, ageing and product/staff reports distinguish debt reductions, returned goods and actual cash. Original sale/return history remains immutable.
- Quotations capture goods, pack meanings, exact prices, contacts, notes and expiry in immutable revisions. Issue/accept/cancel do not move goods or money. Reviewed full-cash conversion checks current goods, pack identity, stock, expiry and price bounds and records one linked sale atomically. Credit/electronic/order conversion and reservations remain unavailable.
- Bulk CSV updates review existing goods, display row errors and before/after values, reject stale versions and apply the full batch atomically with a reason/history. Reviewed SKU/barcode swaps are supported. Stock and completed receipts are unchanged. The earlier add-only new-product importer remains separate.
- Versioned retail/wholesale/customer quantity price lists require explicit checkout selection; prices may match or reduce normal prices. Supplier invoice metadata includes normalized duplicate checks, reasoned corrections and managed bounded PDF/PNG/JPEG evidence and historical attachment backups (corrected by source audit).
- Opt-in scheduled encrypted local backups retain versions with guarded pruning and bounded session passwords. The app must be running with an authorized session and supplied password. File/password checks do not establish successful recovery on another machine. Working SQLite storage remains unencrypted.

### Actual rendered disconnected checks — continuation

Linux Compose ran with sample data in Xvfb at 1440 × 1000 inside a user/network namespace with loopback only and no external route. Keyboard typing, Tab and selected pointer actions were exercised. These are targeted checks, not a full keyboard-only/accessibility study or merchant research.

- Saved a ₦14,000 sale as a manually recorded ₦10,000 transfer plus ₦4,000 cash; completion explicitly says external payment **not confirmed**: [payment form](artifacts/desktop-manual-transfer-split-preview.png), [saved result](artifacts/desktop-manual-transfer-split-saved.png). This is sample bookkeeping, not a bank integration result.
- Returned two sample bottles worth ₦1,000 from a part-credit receipt. Review split ₦750 debt reduction / ₦250 cash refund; saved result preserved original history: [review](artifacts/desktop-credit-return-split-preview.png), [saved return](artifacts/desktop-credit-return-saved.png).
- Issued two bottles at an agreed ₦450 each, recorded acceptance, reviewed ₦900 full cash, and converted to one linked sale. Stock changed from 237 to 235 bottles: [issued quotation](artifacts/desktop-quotation-issued.png), [conversion review](artifacts/desktop-quotation-cash-review.png), [converted](artifacts/desktop-quotation-converted.png). Native quotation PDF save/cancel/overwrite was not exercised; output is covered by automated PDF tests.
- Exported a real sample catalogue CSV through the native chooser, edited it under `/tmp`, reopened it through the chooser, reviewed one changed item among six, entered a reason, confirmed and saved. Soap changed ₦450→₦475 with SKU/category/favourite while remaining 96 pieces: [review](artifacts/desktop-bulk-catalogue-preview.png), [saved batch](artifacts/desktop-bulk-catalogue-saved.png), [Stock favourite](artifacts/desktop-bulk-catalogue-favourite.png).

New automated checks include tenant isolation, rollback, immutable history, stale/retry identity, backup tampering, funding chronology, shared pack/base stock, recovered external drafts and JVM screen loading. Broader native coverage for backup scheduling, price-list setup, supplier invoice corrections, all changed failure/keyboard paths and compact layouts remains pending. Windows packaging, real printers/scanners/drawers, merchant comprehension, independent-machine restore and online services remain unperformed.

### Retained earlier targeted evidence

The earlier percentage-item and dated-statement checks are also retained: [percentage preview](artifacts/desktop-percentage-preview.png), [saved cash sale](artifacts/desktop-percentage-sale.png), [net refund preview](artifacts/desktop-percentage-refund-preview.png), [saved refund](artifacts/desktop-percentage-refund-saved.png), [dated customer statement](artifacts/desktop-customer-dated-statement.png). These sample checks predate this continuation and do not establish broader acceptance.

## Previous 424-test integrated checkpoint

**424 tests pass**: 56 shared and 368 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` using installed Gradle 8.13 and JDK 17; successful in 27 seconds. The rebuilt Linux application image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 389-test first-wave and 324-test earlier checkpoints. Windows packaging and hardware/merchant acceptance remain pending.

## New local functionality — 14 November 2026

- Fixed/percentage basket discounts use integer allocation and preserved net-paid refunds. Restored/changed baskets require explicit review; invalid changes block payment. Supplier profiles retain contact/terms history and explicit stable-account links; legacy name-only receipts remain separate.
- Multi-item counts save unfinished drafts and post all corrections atomically. Any intervening movement, including a net-zero sale/return, requires a new count. This is a conservative cutoff, not continuous count reconciliation.
- Versioned shop receipt settings are captured on new sales. PDF receipts support A4/58/80 mm; customer/supplier statements export reconciled local records to A4 PDF with account/period continuation headers. Native PDF save dialogs and real printers still need broader platform testing.
- Code128 labels use saved base/selling-pack barcodes, integer module widths and quiet zones. PNG export and print submission are implemented; other symbologies, physical scanner readability and printer output remain unverified.
- Purchase orders support editable drafts, approval, cancellation, explicit partial deliveries, immutable history and CSV exports. Only delivery creates a stock receipt and actual cash/debt effects. Estimated ordered cost can differ from the sum of individually rounded deliveries. Landed costs, supplier credits and damaged-delivery handling remain pending.
- Reasoned stock removals preserve exact before/after quantities, category, actor and activity history. They change physical stock only; accounting valuation and expense/COGS recognition remain pending.

### Rendered evidence for this batch

Actual Linux Compose checks used the installed app in an isolated practice process, Xvfb at 1440 × 1000 and a network namespace with loopback only. Keyboard typing and selected pointer actions were exercised; full keyboard-only/accessibility and merchant comprehension studies were not performed.

- Entered a 12.5% basket discount on ₦14,000, showing ₦1,750 reduction and ₦12,250 due. Increasing a quantity then disabled payment and requested another review: [applied discount](artifacts/desktop-basket-percentage-applied.png), [blocked changed basket](artifacts/desktop-basket-review-required.png).
- Saved and posted two physical counts, 94 pieces from 96 and 9.875 kg from 10 kg, with the reason and differences preserved: [posting confirmation](artifacts/desktop-stock-count-review.png), [posted result](artifacts/desktop-stock-count-posted.png).
- Inspected stable supplier details and ₦18,000 owed on its explicitly linked ₦20,000 purchase: [supplier account](artifacts/desktop-supplier-profile-detail.png).
- Edited shop name/footer, entered a reason, reached Save through scrolling and saved the form; inspected the saved fields: [receipt settings](artifacts/desktop-shop-settings-saved.png). Historical branding retention is covered by repository tests, not an additional native end-to-end sale in this pass.
- Inspected the rendered Code128 preview and a generated PNG: [label screen](artifacts/desktop-label-options.png), [label file](artifacts/barcode-label-proof.png). No physical print/scan was performed.
- Created and approved a 10-piece purchase order at ₦300 each, then entered a partial delivery of 4 pieces with explicit ₦0 cash using keyboard Tab between fields. The saved order shows 4 received and 6 remaining: [approved order](artifacts/desktop-purchase-order-approved.png), [delivery preview](artifacts/desktop-purchase-order-partial-preview.png), [saved delivery](artifacts/desktop-purchase-order-partial-saved.png).
- Recorded 2 damaged pieces from 96 with a reason, reviewed the 94-piece remainder and saved immutable history: [review](artifacts/desktop-stock-removal-review.png), [saved removal](artifacts/desktop-stock-removal-saved.png).
- Poppler rendering and text extraction verified the receipt and statement PDFs, readable naira glyphs, wrapping, page identity and reconciliation. Customer debt ₦100−₦32.10=₦67.90; supplier ₦1,481.40−₦281.40−₦32.10=₦1,167.90. [Receipt](artifacts/pdf-branded-receipt.png), [customer statement](artifacts/pdf-customer-statement.png), [supplier first page](artifacts/pdf-supplier-statement.png), [supplier final page](artifacts/pdf-supplier-statement-last-page.png).

## Previous integrated checkpoint

- **324 automated tests pass**: 35 shared and 289 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with the installed Gradle 8.13 and JDK 17; the final run completed successfully in 24 seconds. The wrapper specifies a different distribution and was not used for this checkpoint.
- Linux application image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. Windows packaging and hardware compatibility remain unverified.
- The previous baseline was 287 tests. Added eight fractional-quantity integrity/adversarial tests, eleven cash-count domain/repository tests, seven help/launcher tests, and eleven staff-PIN security tests. Existing financial and migration tests continue to pass.
- Five specialist agents worked in waves: current-feature audit, fractional integration, backlog reconciliation, cash counting, and help/practice. The audit agent subsequently implemented staff PINs. Small commits separate planning, domain, storage, exports, UI, security, help and corrections. The complete remaining scope is still tracked in IMPLEMENTATION_TODO.md and IMPLEMENTATION_PLAN.md.

### Completed local implementation

- Product precision is chosen at creation (0–6 decimal places) and remains fixed. Stock arithmetic uses scaled integers; transactions retain their precision and conversion snapshots. Measured checkout, receiving, single-item counts, cash returns, CSV import/export, reports and backup/restore are connected. Pack counts remain whole. Transaction quantities have a maximum of `Int.MAX_VALUE` scaled increments; stored stock enforces supported exact bounds and round-trip projection limits for legacy display/opening-history fields. Nested conversions/repacking remain pending.
- Refunds use cumulative rounded quantities, so repeated partial returns sum to the original saved line amount. Invalid/pending quantity edits block payment and require Apply quantity; entering an intermediate zero cannot silently remove a line. Older fractional opening balances without a precision policy remain displayed, with only their whole portion available for whole-unit selling.
- Cash opening/closing can store canonical note/coin sheets in the same transaction as the shift. Replays cannot overwrite counts. Backups retain and validate sheets; legacy backups without them remain supported. Closing can hide expected cash in its form, with an explicit explanation that Today's cash may already have displayed it. Register handover and stronger blind-accountability policies remain pending.
- Staff can configure/remove a six-digit local PIN using their full password. Password/PIN attempts share the persisted five-failure/60-second cooldown. PIN authority expires after 12 hours without renewal by PIN; PIN sessions also enforce absolute expiry, account version/generation and clock rollback. Full-password sign-in is the fallback and can renew authority. Staff account changes/password reset remove PIN access. Owner sign-in remains password-based. PIN credentials stay outside business backups.
- More → Help & practice provides bundled searchable task guidance. Practice launches a separate sample process, waits for its first rendered frame, suppresses duplicate launches and closes the child on cleanup. The installed `--practice` entry uses an in-memory business database and temporary draft/security files, without opening the trading database.

### Actual rendered and offline checks

Native Compose screens were reviewed on Linux/Xvfb at 1440 × 1000, using isolated sample records inside a Linux user/network namespace with only loopback and no external routes. These checks used keyboard text entry and selected Tab/Return actions plus pointer navigation; they are not a complete keyboard-only or accessibility assessment.

- Typed `0.375` into a measured basket line: the original line stayed present and payment was disabled until Apply quantity. Tab/Return applied it as 0.375 kg and ₦375.00. See [pending entry](artifacts/desktop-fractional-pending.png) and [applied entry](artifacts/desktop-fractional-applied.png).
- Increased to 1.375 kg, applied 12.5% off a ₦1,000/kg price and saved the cash sale for **₦1,203.13**, giving **₦796.87** change from ₦2,000. See [discount](artifacts/desktop-fractional-discount.png) and [saved sale](artifacts/desktop-fractional-sale-saved.png).
- In a separate fresh fixture, saved a 1.375 kg cash sale, returned/restocked 0.375 kg for ₦375, and inspected the preserved receipt/return and resulting **9 kg** stock from an opening 10 kg. See [return preview](artifacts/desktop-fractional-return-preview.png), [saved return](artifacts/desktop-fractional-return-saved.png), and [stock](artifacts/desktop-fractional-stock-after-return.png).
- Entered nine ₦1,000 notes and saved a closing count of **₦9,000** against expected **₦10,000**. Past shifts retained the count-sheet link, expected-hidden method and **₦1,000 shortage**. See [saved count](artifacts/desktop-cash-denominations-saved.png). Working screenshots exposed misleading empty-row subtotals; these now show zero while the overall blank sheet still requires an explicit count.
- Rendered bundled Help without internet. A separate real-process smoke check invoked the rebuilt Linux installed launcher through PracticeLauncher, received first-frame readiness, confirmed duplicate-launch suppression and verified that closing the launcher left no child process alive. See [help](artifacts/desktop-help-offline.png) and [installed practice](artifacts/desktop-installed-practice.png). This checks the actual launcher, not an end-to-end click from a merchant's production shop.
- In the isolated staff preview, locked the owner workspace, selected Sample cashier, configured a PIN using its full password and unlocked with that PIN. The cashier retained Sell/Sales and More → Help without restricted tools. Then clicked Open practice shop in Help and observed the separate sample window. See [PIN setup](artifacts/desktop-pin-setup.png), [cashier unlocked](artifacts/desktop-pin-cashier-unlocked.png), [cashier tools](artifacts/desktop-cashier-help-tools.png) and [practice from Help](artifacts/desktop-cashier-practice-launch.png). Namespace child JVMs used consistent `user.home`/`user.name` properties for native-library extraction; an initial mismatched test harness correctly surfaced Practice unavailable, then the corrected harness completed the launch. No real shop or provider was used.
- Typed an unmatched Help query and inspected No matching help, then replaced it with `carton` and expanded the stock-unit guidance. See [empty search](artifacts/desktop-help-empty.png) and [filtered guidance](artifacts/desktop-help-search.png). Rerendered the denomination form after the subtotal correction and saved its count again; unused rows now display ₦0.00. See [count entry](artifacts/desktop-cash-denominations-entered.png).

Service tests additionally cover exact decimal subtraction, precision-six sales, over-selling, retry deduplication, corrupted precision/stock/sheet restore rollback, legacy migration, encrypted backup round-trips, fixed PIN expiry, shared cooldowns, cross-instance revocation and secret-free staff history. Merchant studies, Windows, physical peripherals, full screen-reader/touch/keyboard journeys, independent-machine recovery, large-catalogue profiling and external integrations remain unperformed or incomplete.

The dated sections below are historical checkpoints, not claims that their older test totals or then-pending feature lists describe this latest build.

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- **241 automated tests pass**, with zero failures or errors. The negotiated-price checkpoint added 13 tests to the previous 170-test checkpoint; six ageing-band tests brought the total to 189. Fifteen correction/domain/export tests bring the total to 204.
- Coverage includes cross-tenant rejection, exact kobo amounts, retry deduplication, return limits/restocking, supplier overpayment, atomic import rejection, WAT date boundaries, spreadsheet formula protection, encrypted tenant-only backup, wrong passwords, corrupted input, repeated restore and cash/debt-history consistency.
- Linux desktop distribution rebuilt successfully using `gradle :desktopApp:createDistributable` at `desktopApp/build/compose/binaries/main/app/RetailPOS`.
- Native isolated previews rendered for Reports, Sales, Suppliers, Backup and Import. Reports filled the 1920 × 1080 display; Sales and Suppliers were also inspected in the compositor-provided narrower window. See [Reports](artifacts/desktop-reports.png), [Sales](artifacts/desktop-sales.png), and [Suppliers](artifacts/desktop-suppliers.png), [Backup](artifacts/desktop-backup.png), and [Import](artifacts/desktop-import.png).
- Previous fullscreen verification remains recorded in [the fullscreen render](artifacts/desktop-fullscreen.png). Startup requests true fullscreen with F11 and visible window controls. Window-manager behavior still needs Windows and multi-monitor verification.

## Login throttling and inactivity locking — 13 November 2026

- Five unsuccessful sign-in attempts pause that shop/account for 60 seconds. Correct passwords cannot bypass the pause. Attempts persist across staff-service instances and restarts, and owner/staff identities are counted separately.
- Genuine sessions expire after 15 minutes without authorized activity. Key press, mouse press and wheel activity in this app refreshes the session at most once per second; no entered content is read or retained. The 15-second background check validates without extending the timer.
- Expired/revoked sessions cannot be revived. Idle session checks and activity use the same validated clock reading; backward clock changes fail closed.
- Eleven additional clock-driven tests cover exact boundaries, polling/activity, denied permissions, forged/expired/revoked tokens, cooldown scope/persistence/reopen, clock rollback and timestamp reuse. All 231 integrated tests pass and the Linux package was rebuilt.
- Native staff/access-dialog and compact-form renders were inspected after adding the event listener: [access dialog](artifacts/desktop-staff-access-dialog.png), [compact form](artifacts/desktop-staff-form-compact.png). A controlled native idle-to-lock transition and full minute-long cooldown were not confirmed in this pass; exact service behavior is covered by the automated clock tests. Do not treat a timer screenshot filename as proof of a transition.
- Preview-only POS_PREVIEW_IDLE_MS can shorten the isolated fixture's timeout. The installed app retains the 15-minute default. PINs, owner credential recovery, actor attribution, approval workflows and a broader security review remain unfinished.

## Local staff identities and permissions — 13 November 2026

- A complete remaining-feature checklist is now kept in IMPLEMENTATION_TODO.md. Only verified subtasks are checked; the long-term product remains unfinished.
- Owner-managed local staff accounts use separate salted PBKDF2 password credentials. Owners add cashier/manager accounts, rename/change roles, disable/enable and reset passwords. Staff accounts and credentials are excluded from business backups.
- Cashiers can make cash sales and view/print/export individual receipts. Managers have operational stock/customer/credit/discount/refund/money/report/supplier access. Staff management and encrypted shop backups are owner-only.
- The app installs a genuine-session validator on repository mutations, including successful-operation replays. Credit and discounted checkout require additional permissions. Credential/role/active changes invalidate staff sessions across instances through version checks; the shell rechecks sessions every 15 seconds and on navigation.
- Restore from the locked picker uses an explicit trusted recovery connection and still requires backup decryption plus new-owner credential provisioning. It returns to owner sign-in. Working business DB encryption, staff PINs, actor attribution and approval flows remain pending.
- Sixteen additional tests cover role policy, denied mutations leaving whole snapshots unchanged, genuine credentials, forged sessions, revocation/reset, cross-instance persistence, safe create retries, backup exclusion and JVM class loading. All 220 integrated tests pass; Linux distributable rebuilt.
- Native isolated preview: inspected owner staff management, locked the workspace, selected Sample cashier, entered its password and unlocked the restricted Sell/Sales workspace. Evidence: [management](artifacts/desktop-staff-management.png), [sign-in](artifacts/desktop-staff-sign-in.png), [cashier workspace](artifacts/desktop-cashier-workspace.png).
- A runtime bytecode/class-loading problem in the first screen structure was corrected and is covered by a regression test. Native cashier payment completion, full staff creation/reset dialogs, manager sign-in, restored-backup sign-in and automatic revocation polling were not exercised end-to-end in this visual pass; their service/repository paths have automated coverage.
- Credentials enforce local application access only. They do not establish server-side membership or protect records from OS/filesystem access.

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

## Activity history and actor attribution — 13 November 2026

- New repository business events save a frozen staff ID/name/role with their source/outbox event inside the same transaction. Missing/foreign identities or failed audit writes roll back the business action. Retries retain the original actor and do not append duplicate activity. Trusted tooling/old records remain explicitly unattributed; no history is backfilled.
- Business activity and staff-change tables reject SQL updates/deletes. This is application/database integrity, not protection from workstation administrators or filesystem access.
- More → Activity history shows business events, source IDs and WAT dates, with search, refresh, empty/error states and owner-only Staff access history. Existing guide/reference lock and semantic theme are retained. Merchant-facing action names replace raw domain names.
- Reports → Activity history exports date-filtered, spreadsheet-safe CSV. Encrypted business backups retain business-event identities and accept legacy backups without this table; malformed or mismatched events reject the entire restore. Staff security history remains on the original computer and excludes credentials/tokens.
- Ten additional tests (six business/backup/export tests and four staff-audit tests) bring the integrated total to 241, with zero failures/errors/skips. Includes identity snapshots, retry safety, transactional rollback, cross-shop/read restrictions, reopen, update/delete rejection, encrypted recovery, corrupt restore rejection, legacy backups and CSV formula protection. Desktop class-loading coverage includes the activity screen. Linux distribution rebuilt using offline Gradle dependency resolution.
- Actual native isolated 1920 × 1080 business-history render inspected: [activity history](artifacts/desktop-activity-history.png). Attributed and unattributed events and the owner-only tab were visible. Keyboard-event injection did not produce a conclusive visible transition; keyboard search/tab interaction and the staff-history view were not confirmed in this pass. No network-disconnection drill, merchant study, Windows or physical-peripheral test was performed. Service tests exercise local SQLite without external services; offline Gradle mode is not evidence of disabling the machine's network.

## Historical credit and performance reports — 13 November 2026

- Review customer credit now supports an earlier recorded date, inclusive through the end of that day in Africa/Lagos. Later repayments/assignments/assignment corrections are excluded; current profile names/contacts are labeled as current. Customers settled today can still have debt on an earlier date. Complete current history must reconcile before reconstruction. This is recorded history, not a claim about unrecorded physical payments.
- Ageing day limits can be changed for the current report (for example 15,45). Limits must be positive/increasing; due today/future and undated remain separate. Unassigned repayments keep exact bands unavailable. These report settings are not yet saved as shop defaults.
- Reports → Product performance exports sales after discounts, refund amounts and historical base quantities. Sales and returns use their own dates; older-sale returns can produce negative net sales in a period. This is revenue, not profit or cash received.
- Reports → Staff activity groups each sale/refund by its recorded actor snapshot; refund recorders remain separate from original salespeople. Older events remain explicitly unattributed. These figures do not calculate commissions.
- Seventeen additional tests bring the checkpoint to 258 passing tests, zero failures/errors/skips. Includes WAT cutoffs, correction chronology, settled-customer history, invalid input/history, custom bands, pack/discount/refund reconciliation, actor grouping, foreign/duplicate rejection and spreadsheet-safe exports.
- Native Compose preview rendered on an isolated Xvfb display at 1440 × 1000. Keyboard date entry reached the earlier-date empty state and malformed dates disabled export. The view was adjusted to scroll controls/results on shorter screens; band customization is collapsed by default.
- A further native preview ran inside a Linux user/network namespace with only loopback and no network routes. Changed bands to 15,45 using keyboard input, scrolled to verify all band totals, and saved a CSV using the native dialog. The actual file contained current debt 750.00 NGN, unassigned payments 0.00 and 750.00 in the not-late band. This was isolated sample data, not merchant records. See [custom ageing](artifacts/desktop-custom-ageing.png). No external services were used. This checks the local sample workflow, not every offline operation or independent-machine recovery.
- Merchant research, physical hardware, Windows and screen-reader testing remain outstanding.
