# Remaining product execution plan

Started 13 November 2026. Authority: FEATURE_SPECIFICATION.md; detailed requirements remain in IMPLEMENTATION_TODO.md. This plan groups work by dependency, not by a promise that all planned modules are finished in one batch.

## Working rules

- Four implementation subagents, scheduled within the session's concurrency limit. Root integrates, reviews, runs serialized Gradle checks and records evidence.
- Commit coherent small changes using explicit file paths. Never commit failed builds as completed features.
- Preserve local/offline writes, exact money, tenant boundaries, explicit units/payment certainty, immutable corrections and retry safety.
- Only mark an item complete after implementation and relevant tests. Foundations are identified separately from complete merchant workflows.
- Real external access, hardware and merchant research are dependencies; do not substitute fake success.

## Current batch

- [ ] Agent 1: exact scaled-quantity foundation and boundary tests. Fractional stock/checkout migration remains a separate dependent integration.
- [ ] Agent 2: catalogue SKU/category/brand/aliases, favourites, search/filter, history, migration and backup compatibility.
- [ ] Agent 3: percentage item discounts, explicit rounding/reasons, checkout UI and regression tests.
- [ ] Agent 4: receipt-based supplier statements and dated, reconciled exports; scheduled as a concurrency slot becomes available.
- [ ] Root: integrate CSV/schema/UI changes, review permissions/retries/financial invariants, run integrated tests/package, inspect native renders where possible and record unperformed checks.
- [x] Root: recorded-date historical customer debt and configurable report ageing bands, including correction chronology and date validation.
- [x] Root: product sales/refunds and recorded-staff activity exports.

## Following dependency batches

1. **Quantity migration and stock:** integrate exact quantities across checkout, receiving, returns, drafts, stock counts, imports, exports and old backups; nested conversions and repacking. Then variants, attachments, barcode labels, bulk edits and opening-balance imports.
2. **Pricing and trade:** basket discounts with deterministic allocations, price lists/promotions/bundles, margin/approval controls, repricing, open-price goods, quotations, notes and backdating.
3. **Payments and credit:** explicit unverified transfer/card records, split payments, advances and reversals; credit returns/exchanges/store credit; historical ageing, adjustments/write-offs, customer merging and statements.
4. **Purchasing and inventory:** supplier masters, purchase orders/partial deliveries/invoices, landed costs/returns, count sessions, bins/reservations, lots/expiry/serial numbers, loss categories, replenishment and valuation.
5. **Cash and accounting:** drawers/register custody, denomination/closing/handover, expenses/contributions/drawings, historical COGS, journal/period controls and financial statements.
6. **Documents and orders:** shop branding/settings, PDF and thermal documents, orders/deposits/layaway, fulfilment and dispatch. Add specialist modules per merchant type with domain validation.
7. **Online foundation:** backend identity/membership/authorization, Heroku-compatible Postgres backend, device enrollment, authenticated sync and deterministic conflict policy, then branches and transfers.
8. **Connected services:** backend-only Paystack verification/webhooks/settlement and Telegram opt-in queues. Require actual test credentials, secure configuration and sandbox evidence; hosting requires actual account access.
9. **Platforms and product operations:** Android/iOS adapters and React/TanStack web; reports, tax, loyalty, communications, privacy, languages, help, updater/subscriptions.
10. **Security and release:** PINs/approvals/recovery, full audit review, working-data encryption, automatic backups, fault recovery, performance, Windows/signing/upgrades, accessibility/peripherals, merchant research and production readiness.

## Acceptance evidence

Each implementation batch updates BUILD_STATUS.md and IMPLEMENTATION_STATUS.md with actual commands/results, screenshots, and limitations. Physical hardware, merchant testing, platform-specific checks and external services remain unchecked until performed.
