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
