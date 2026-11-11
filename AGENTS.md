# Project instructions

## Product and design authority

- This project is an offline-first Nigerian retail/shop POS, not an agent-banking app.
- Read `FEATURE_SPECIFICATION.md` for full functional scope and `DESIGN_GUIDE.md` before implementing or changing UI.
- Use `design/tokens.json` for the shared visual roles; keep it consistent with the design guide.
- Read `design/DESIGN_BRIEF.md` first for the non-negotiable screen hierarchy. Use `design/copy.json` for canonical front-facing labels; internal accounting/domain names can remain technical.
- The agreed stack direction is Kotlin Multiplatform + Compose for installed desktop/mobile apps, SQLite locally, React/TypeScript + TanStack for web, and a Heroku-compatible online backend. The current backend recommendation is Kotlin/Spring Boot with Heroku Postgres; exact versions and sync architecture are not yet locked.

## Implementation behavior

- Follow the guide's reference lock, layouts, terminology, component roles, state coverage, and quality gate.
- Default to the five everyday destinations (Sell, Sales, Stock, Customers, Money) plus labeled More; retain advanced functionality in contextual/subsection views rather than filling the primary sidebar.
- Do not auto-collapse navigation into unlabeled icons or shrink checkout into expert reporting density.
- Never silently convert a short payment into customer debt, reinterpret a selling unit, or describe an unconfirmed transfer as paid.
- Treat first-glance comprehension and merchant task evidence as acceptance requirements; use `design/USABILITY_TEST_PLAN.md` and report unperformed research honestly.
- Preserve the separation between local persistence, server sync, external payment verification, and settlement.
- Ordinary supported offline operations must not require a successful server request.
- Never fabricate financial data, live integration results, sync completion, payment confirmation, or test evidence.
- Record corrections with history; do not silently delete completed financial events.
- Keep incomplete features clearly unavailable instead of wiring fake success actions.
- Use actual rendered desktop screenshots and keyboard/offline checks when validating implemented UI; report any unperformed checks honestly.
- Update the guide and token source together if the user changes the visual direction. Do not require extra approval for routine work already authorized by the user.

## Current repository state

This folder contains a working Kotlin desktop build with shared domain/UI modules and local SQLite persistence. Read README and BUILD_STATUS.md for the implemented scope and evidence; do not mistake the complete feature specification for completed code. Merchant testing and Windows packaging remain outstanding.
