# Easy shop connection — 25 November 2026

## Reference lock

Keep DESIGN_GUIDE.md and design/tokens.json's light trading-counter roles. Use the existing shared Compose components, 48dp minimum controls, a single-column setup journey and explicit action labels. Refero's bundled copywriting/forms guidance informs task-first labels, progressive disclosure, password-manager/paste support and actionable errors. Pairing uses expiring one-time approval codes, a separate high-entropy polling secret and explicit authenticated owner consent (RFC 8628 pattern; not a claim of OAuth interoperability).

## Tasks

- [x] Add durable, expiring device pairing with owner review, bounded attempts and idempotent claim/retry; never transfer account or local passwords.
- [x] Add client pairing and safe direct import into an empty installation, preserving existing-shop owner authorization.
- [x] Replace technical setup with guided Connect this shop / Use my existing shop flows, default server, advanced settings, pairing code/QR and owner device controls.
- [x] Add verified-email/password recovery with single-use codes, rate limits, session invalidation and honest unavailable state without delivery configuration.
- [x] Add plain-language synchronization/conflict state, last connection details and privacy-safe diagnostics.
- [x] Add operational health/backup/restore tooling and document external monitoring/delivery configuration.
- [x] Run client/server/desktop/Android JVM regressions and builds; inspect actual rendered desktop setup, F11 and network-isolated behavior.
- [ ] Execute the new 32-case native suite and camera/manual two-device journeys on a connected phone or working isolated emulator.
- [x] Deploy compatible backend release v9 and verify real HTTPS health and pairing lifecycle endpoints; commit in small slices.
- [ ] Install the guided-setup build on the reconnected phone, preserving saved data.
- [ ] Configure the user’s verified Resend sender and private API key; verify actual inbox delivery.
- [x] Publish to the configured GitHub repository, activate the 15-minute readiness workflow and verify its first manual run.
- [ ] Verify scheduled-run/alert delivery and perform merchant usability research.

## Acceptance limits

No fake email delivery, backup restoration, sync completion, merchant research or Windows execution. Email provider configuration and merchant testing need actual external evidence. Financial corrections remain explicit history; pairing cannot overwrite an existing local shop. Cloud sharing is separate from point-in-time backup and payment verification.

## Execution notes

- 17 local server tests passed (14 actual HTTP/Postgres + 3 security). The hosted HTTPS convergence test initially failed on connection, then passed on retry. Earlier failures are not counted as passes.
- Fresh hosted backup capture and isolated PostgreSQL restore completed. Integrity checks passed. Daily backups are scheduled for 02:00 Africa/Lagos; first scheduled execution remains unverified.
- Resend selected by the user. No API key or sender is configured locally/on Heroku; sender address/domain and private credential setup remain external dependencies. No real verification/reset email has been sent.
- 605 desktop, 9 Android JVM, 4 client and 17 local server tests pass. Both Android variants/native test APK and Linux distribution build. Actual desktop setup render and F11 worked in a network namespace. Release v8 endpoint lifecycle/convergence checks passed, followed by release v9 with verified health. The first GitHub readiness job passed. Physical native execution/update remain pending after USB disconnected; isolated emulator startup failed twice with exit 139.
