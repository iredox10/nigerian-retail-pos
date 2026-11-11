# Desktop build status

11 November 2026 · Desktop shop operations and fullscreen update

Five subagents contributed domain logic, persistence, reusable design components, checkout/draft recovery, and local workspace security. Integration and micro-commits were performed by the primary agent.

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- 39 automated tests: six checkout-domain, four exact-amount parsing, six original repository, eleven shop-operation, six adversarial, three password-gate, and three durable-draft tests.
- Tests cover cross-tenant data access rejection, wrong passwords, tenant-specific credentials, stock rollback, persistence/reopening, debt repayments, operation retry deduplication, overflow, and independent draft recovery.
- Native desktop application launched on Linux/JVM; onboarding and sample checkout renders were inspected. A typography/content-color issue found during visual review was corrected.
- A separate preview entry point uses an in-memory database and temporary draft directory with explicitly labeled sample data.
- Linux desktop distribution generated under `desktopApp/build/compose/binaries/main/app/RetailPOS`; Windows packaging remains unverified.

## Added in this round

- Stock receiving and purchase-cost/payment snapshots, including an explicitly labeled unpaid-at-receipt amount.
- Cash expenses, cash in/out with reasons, opening/closing shifts, and preserved shortage/excess records.
- Drawer-linked cash sales and repayments, while cash before a shift opens stays unassigned.
- Additive migration of old cash sales/repayments, without duplicate ledger entries or contamination of opening balances.
- Tenant/unit validation on checkout replays, normalized duplicate-line retries, repayment deduplication, and stock precision bounds.
- Shared common-Kotlin exact-naira parsing, screen integration, and an actionable Open today's cash entry from checkout.
- Fullscreen startup instead of fixed-size windows, with F11 and visible minimize/window-mode/close controls.
- Inspected the new Money and Stock renders using isolated sample data. Verified a true fullscreen render filling the 1920 × 1080 XWayland display; see [fullscreen render](artifacts/desktop-fullscreen.png). Full keyboard-only/accessibility testing is still outstanding.

## Not yet verified

- Windows installers, Windows display scaling and printer/scanner integration.
- Full keyboard-only, screen-reader and touch-mode journeys.
- Merchant first-glance tests or a formal usability study.
- Complete end-to-end UI automation; repository/domain tests do not substitute for it.
- Real bank/card operations, remote authentication or cloud synchronization; these are not implemented.

## Multi-tenant boundary

Every local business query and mutation is tenant-scoped. Product/customer ownership and composite database relationships prevent accidental cross-shop financial writes. The UI requires a shop-specific password to enter an existing local workspace.

This is still a local trusted-device owner application. Database files are unencrypted, and the repository itself is not a network authorization boundary. A production online product requires authenticated identities, tenant memberships, server-side authorization on every operation, role enforcement, device enrollment, and a reviewed synchronization protocol. No Heroku deployment has been performed.

## Known implementation gaps

See README for the explicit feature boundaries. This build does not implement the entire long-term feature scope. Upcoming work includes staff roles, multiple selling units/fractional sales, returns, full supplier ledgers, backup, print support, and online identity/sync. UI actions for unavailable external functions do not simulate success.
