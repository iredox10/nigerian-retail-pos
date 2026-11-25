# Easy shop connection — 25 November 2026

## Reference lock

Keep DESIGN_GUIDE.md and design/tokens.json's light trading-counter roles. Use the existing shared Compose components, 48dp minimum controls, a single-column setup journey and explicit action labels. Refero's bundled copywriting/forms guidance informs task-first labels, progressive disclosure, password-manager/paste support and actionable errors. Pairing uses expiring one-time approval codes, a separate high-entropy polling secret and explicit authenticated owner consent (RFC 8628 pattern; not a claim of OAuth interoperability).

## Tasks

- [ ] Add durable, expiring device pairing with owner review, bounded attempts and idempotent claim/retry; never transfer account or local passwords.
- [ ] Add client pairing and safe direct import into an empty installation, preserving existing-shop owner authorization.
- [ ] Replace technical setup with guided Connect this shop / Use my existing shop flows, default server, advanced settings, pairing code/QR and owner device controls.
- [ ] Add verified-email/password recovery with single-use codes, rate limits, session invalidation and honest unavailable state without delivery configuration.
- [ ] Add plain-language synchronization/conflict state, last connection details and privacy-safe diagnostics.
- [ ] Add operational health/backup/restore tooling and document external monitoring/delivery configuration.
- [ ] Run meaningful protocol/client/server/native tests and builds; inspect rendered desktop setup and keyboard/offline behavior.
- [ ] Deploy compatible backend changes, verify live isolated QA flows, update apps and commit evidence in small slices.

## Acceptance limits

No fake email delivery, backup restoration, sync completion, merchant research or Windows execution. Email provider configuration and merchant testing need actual external evidence. Financial corrections remain explicit history; pairing cannot overwrite an existing local shop. Cloud sharing is separate from point-in-time backup and payment verification.
