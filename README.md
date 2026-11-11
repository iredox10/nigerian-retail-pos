# Nigerian Retail POS

An offline-first Kotlin desktop retail POS for Nigerian merchants, with reusable shared domain and Compose design-system modules. This is an initial working desktop foundation, not the complete feature specification or a production SaaS deployment.

## Run

Requires a JDK 17 installation (set `JAVA_HOME`), network access for the first dependency download, and a graphical desktop. Subsequent local business operations do not require the network.

```sh
./gradlew :desktopApp:run
```

Create a shop workspace and owner password, add goods under Stock, add a customer if credit is needed, then make a sale. Each shop has isolated records and a separate owner password. Local data is stored under `~/.nigerian-retail-pos` and is not encrypted; use a trusted workstation. Keep this directory for persistence. Do not treat pending outbox entries as online backups.

For an explicitly isolated sample workspace with an in-memory database and temporary draft directory:

```sh
./gradlew :desktopApp:run -Ppreview
```

The preview bypasses workspace onboarding only for its own sample database. It never opens normal shop records or calls a payment service. Preview data is discarded when the process ends.

```sh
./gradlew :shared:jvmTest :desktopApp:jvmTest
./gradlew :desktopApp:createDistributable
```

Desktop distributions are built for the host operating system. Build and test the Windows distribution on Windows; the current environment validates Linux/JVM desktop execution.

## Implemented

- Local shop creation and password-gated workspace selection.
- Tenant-scoped SQLite products, customers, sales, repayments, and outbox entries.
- Product creation/search, stock availability and integer selling quantities.
- Cash checkout, exact amount/change, and explicit named-customer partial credit.
- Atomic stock/debt/sale writes and retry deduplication with stable operation identifiers.
- Tenant-scoped basket recovery after restart, and screen-state retention across navigation.
- Saved receipts, customer repayments, and clearly scoped money summaries.
- Shared semantic theme, offline IBM Plex Sans fonts, and reusable Compose components.

## Important boundaries

- Local tenant isolation and owner passwords are implemented. Online identity, staff roles/memberships, server authorization, remote tenant administration, and encrypted local storage are not yet implemented. This is not ready for untrusted shared-computer or multi-user SaaS production deployment.
- No cloud sync transport, Heroku backend, bank/card verification, printing, backup service, or external messages are connected. The status bar explicitly says so.
- Current stock uses one configured selling unit per product and integer checkout quantities. Packaging conversions, fractional checkout, returns, receiving, cash closing, supplier accounting, and other advanced modules remain future work.
- Draft recovery saves item IDs and quantities; it does not yet retain tendered cash, customer selection, or a frozen price snapshot across restart. Checkout revalidates current price and stock. Review restored goods before payment.
- UI currently performs short local repository operations synchronously; large-catalogue profiling, background database execution, complete keyboard shortcuts, screen-reader review, and merchant usability testing remain outstanding.

## Project structure

```text
shared/src/commonMain/kotlin/pos/domain/       Tenant-aware models and exact money logic
shared/src/commonMain/kotlin/pos/ui/           Reusable theme and components
shared/src/commonMain/composeResources/       Bundled fonts and license
desktopApp/src/jvmMain/kotlin/pos/data/        SQLite persistence and durable drafts
desktopApp/src/jvmMain/kotlin/pos/security/    Local workspace password gate
desktopApp/src/jvmMain/kotlin/pos/screens/     Desktop checkout
desktopApp/src/jvmMain/kotlin/pos/Main.kt      Desktop shell and shop tools
```

The shared module currently declares a JVM target while keeping domain/UI code in common source sets. Add and test Android/iOS targets and platform persistence adapters in their implementation phases; mobile apps have not been built yet. SQLite JDBC is the first desktop persistence adapter; Room Multiplatform migration/evaluation remains open.

## Design and planning

- [Complete feature specification](FEATURE_SPECIFICATION.md): the full planned product scope, including advanced features and sector modules—not just the first release.
- [Desktop design and interaction guide](DESIGN_GUIDE.md): reference direction, visual system, screen layouts, journeys, offline states, accessibility, and implementation quality rules.
- [Design tokens](design/tokens.json): shared machine-readable colors, typography, spacing, dimensions, and motion values.
- [One-page design brief](design/DESIGN_BRIEF.md): the everyday screen model and visual direction, read before implementation.
- [Interface wording](design/copy.json): canonical labels for selling, payments, money owed, and saved work.
- [Design review](design/DESIGN_REVIEW.md): problems found in version 1.0 and the changes made in version 1.1.
- [Merchant usability test plan](design/USABILITY_TEST_PLAN.md): first-glance and practical task checks; no tests have been run yet.
- [Contributor instructions](AGENTS.md): entry-point rules for developers and AI contributors.

The chosen stack direction is Kotlin Multiplatform/Compose for installed apps, React/TypeScript with TanStack for web, and Heroku for online services. Backend synchronization architecture remains open. Product name, pricing, and delivery schedule are not yet decided. See [build verification](BUILD_STATUS.md) for current evidence and limitations.
