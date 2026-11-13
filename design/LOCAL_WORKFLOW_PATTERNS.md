# Local shop workflow additions

12 November 2026. Extends the existing reference lock in DESIGN_GUIDE.md; no new palette, typography or token roles.

## Decisions and references

| Decision | Existing authority | Intended behavior |
|---|---|---|
| Keep catalogue actions under Stock, with a labeled Manage action | Five-destination model and Carbon table reference | Find an item first; edit, count or inspect its history in context |
| Separate item details, stock counts and history | Progressive detail rule in DESIGN_GUIDE.md | Price edits cannot silently change quantities; count corrections cannot masquerade as purchases |
| Display counted quantity and difference before saving, require a reason | Feature scope section 12; durable financial/history rules | Preserve the previous quantity and correction; reject a stale count after another stock change |
| Keep selling unit fixed in an edit | Offline product contract; unit visibility rule | Avoid reinterpreting past and future quantities; conversions remain a separate future workflow |
| Explicit archive/restore confirmation | Refero bundled craft-details.md destructive-action guidance | Keep completed records and recorded stock, while controlling availability for new transactions |
| Barcode search accepts scanner text plus Enter | Scanner/keyboard requirement and canonical search wording | Exact barcode adds one unit; unknown codes and insufficient stock explain the failure |
| Put sale aside and Saved sales stay in the basket panel | Persistent sale workspace; canonical labels | Save a named unfinished basket, start another, and resume only when the current basket is empty |
| Restore held items using current prices and availability with a review notice | Price/stock integrity requirements | A held basket reserves no stock and freezes no selling price |
| Save active and held baskets together | Offline-first product contract | Moving a basket between active and held states is one atomic file replacement; a failure preserves the previous state |
| Separate original credit, repayments and current debt in customer history | Money certainty rule; customer credit scope | A later repayment is not another sale, and current debt is not a historical date-range balance |

## Scope and failure states

- Active/held baskets are tenant-specific local drafts. They are excluded from encrypted business-record backups and never described as cloud-saved orders or reservations.
- Draft tender text, selected customer and method survive restart and hold/resume. Payment is still unfinished; completing the sale revalidates current stock, price and customer.
- Unavailable/archived items block payment until restored or explicitly removed from the draft.
- Held sales are limited to 100 per shop. Invalid or unsupported draft data is left untouched and reported; it is never silently reset.
- Product barcode uniqueness includes archived products in the same shop. Another shop may use the same barcode for its own goods.
- A stock count is a single-item local correction, not a warehouse count session, a valuation record or evidence of an employee approval.
- Product price changes preserve old sale/return snapshots. Name/archive edits also retain local change records; a complete actor-attributed audit interface remains pending.
- Customer statement exports include complete locally saved history. Repayments are not allocated to individual invoices, and no invoice balance or running balance is guessed.

Merchant testing, hardware scanner verification and full accessibility checks remain required. Use BUILD_STATUS.md for executed build/test/render evidence.

## Buying packaging conversions

The next local workflow adds buying units while leaving the base selling unit unchanged. Its authority is specification section 5 (merchant-defined measures and preserved conversion history), the existing Stock contextual-detail pattern, and the design guide's rule against silently reinterpreting quantities.

- Define a named buying unit under Stock → Manage → Buying units: for example, 1 carton contains 24 bottles. The app does not assign a universal size to cartons, bags, paint buckets or other local measures.
- Definitions preserve name and factor. Archive a definition and add another when packaging changes; old receipts keep their own conversion snapshots.
- Receiving asks for the buying unit before quantity and buying price. A price per carton must never be labeled as a price per bottle.
- Before saving, show purchased quantity × conversion = base stock added, alongside total buying cost, cash paid and supplier balance.
- Completed purchase history, supplier receipt details and CSV output show both buying quantity and base stock added. Opening/counting stock still uses the base unit.
- Archive hides a buying unit from new receipts without deleting history. A retry of an already-saved receipt remains a retry, even if the buying unit has since been archived.
- Stock remains a shared base-unit pool for ordinary sales. Pack sales, separate packaging barcodes/prices, fractional checkout and repacking movements are not implemented by this receiving workflow.

## Customer credit controls

This extends the existing Customer contextual-detail pattern and the checkout guide's question-led payment form. The Refero reference lock remains unchanged: readable neutral surfaces, labeled fields, teal commit action and explicit financial consequences.

- Edit contact details and the owner-set limit inside the customer profile. A blank limit means unlimited; zero means no new credit. Lowering a limit below existing debt does not write off debt.
- In Pay later, show existing debt, the limit and debt after the sale before saving. The repository rechecks current debt and limit in the same transaction as the sale.
- Ask for an optional agreed repayment due date. Leaving it blank does not imply today's date. Preserve it in active/held drafts and completed receipts.
- New due dates use the business's WAT calendar and must be today or later. Historical due dates remain historical on restore; migrations do not manufacture dates for old sales.
- Due-date review shows original dated credit sales, later repayments and current total customer debt separately. Repayment allocation to invoices is still unavailable; no exact unpaid overdue invoice balance is inferred.
- Phone normalization accepts local Nigerian format and +234 format; it establishes formatting only, not ownership or reachability.
- Contact/profile changes keep local history and enqueue local events. They do not send messages or create online accounts.

## Assigning repayments to credit receipts

This extends the customer detail pattern from the locked Refero/Carbon direction: choose a customer first, then use a labeled, contextual payment → receipt → amount flow. No new primary navigation or tokens.

- Recording cash received remains the financial action that reduces customer debt. Assignment links that existing payment to credit receipts and never records cash again.
- A payment may cover several receipts and a receipt may receive several payments, within the exact remaining amounts of both sources.
- Existing payments remain unassigned until explicitly selected. No historical FIFO allocation is inferred.
- Before saving, show payment funds available, original receipt credit, already assigned amount, the proposed assignment and what remains.
- Assignment history is immutable in this release. Reassignment/reversal is not offered until a linked correction workflow exists.
- With unassigned payments, label receipt amounts as balances before those payments. Exact recorded-date overdue totals become available only once that customer's payments are fully assigned. Undated receipts remain separate.
- Archived products and changed customer credit limits do not prevent assignment to completed receipts; those original sales are historical records.
- Backup recovery validates both source records, same-customer links and cumulative limits before creating a recovered shop. Older backups carry no invented assignments.

## Stock history and reorder levels

The existing Stock contextual-detail pattern and Carbon table reference own this workflow. The feature specification's inventory-history and reorder requirements supply its behavior; existing surface/teal/warning tokens retain their roles.

- Show source-linked stock movements under each item's History: known opening quantity, purchases in base units, sales, sellable returns and counted corrections. Damaged/non-restocked returns do not add goods.
- Completed source records supply the movement history. A display query cannot create another purchase, sale or correction.
- Older items with no historical opening quantity are marked unknown. Do not infer a starting count, claim a complete running balance or label unknown as zero.
- Set an optional whole-unit reorder level under item Details. Blank disables the alert; zero alerts only when the item has no stock. Alert when recorded stock is at or below the level.
- Low stock filters active items only. Archiving goods keeps their historical movements but removes them from reorder alerts.
- Movement exports retain signed base quantities, timestamps, source IDs and opening-history certainty. Low-stock/product exports are current snapshots rather than historical stock balances.
- Changing a reorder level keeps setting history but never changes stock or cash. This is an owner workspace tool; delegated staff approval remains a separate unfinished feature.

## Selling units and shared stock — 13 November 2026

Keep packaging setup inside item management. Show a labeled “Sell as” control only when alternate selling definitions exist, with each whole-unit price and base equivalent. Selecting another unit never reinterprets a basket line; carton and loose quantities remain distinct. Show base-stock deduction beneath pack lines. Both controls and the repository aggregate mixed-line stock requirements. Archived definitions preserve historical receipts/refunds but cannot create new sales. Persist opaque line keys in drafts; absent units must block payment with an explicit recovery action. Preserve the existing reference lock and tokens.

## Negotiated prices — 13 November 2026

Use the existing labeled contextual action on each basket line. Adapt the locked guide's recognition principle: show the catalogue price, new price per named unit, required reason and resulting total. Keep the fixed payment action and the original receipt-like total panel. No new tokens or navigation. Persist discount and price reference with the draft; a changed catalogue price requires explicit review. Receipts explain original price, reduction and net price so partial returns can refund an exact amount. Only the current owner workspace exists; do not imply staff approval enforcement.

## Debt-ageing bands — 13 November 2026

Extend the existing Credit review customer surface with labeled amount rows, following the locked Carbon comparison structure and familiar-wording rule. Keep the current debt and unassigned-payment context above the bands. Use calendar days after an agreed WAT due date: due today is not late; no-date debt has its own row. Suppress every exact band while repayments remain unassigned. Export all reviewed customers, independent of the visible search/filter, with an explicit current-balance basis and blank unknown amounts. Keep refresh and export labeled; no new navigation or tokens. Native preview inspected the seven rows and current-debt reconciliation.
