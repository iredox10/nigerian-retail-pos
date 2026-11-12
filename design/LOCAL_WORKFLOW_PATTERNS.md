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
