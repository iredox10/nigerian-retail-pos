# Android delivery plan

Requested 23 November 2026. Cloud synchronization remains excluded. Android is an independent offline device; installing it does not connect desktop records. No provider confirmation or cross-device consistency is implied.

## Architecture and reference lock

Retain Kotlin 2.2.20 / Compose 1.9.1 and shared domain/design components. Add an Android application and Android shared target using AGP 8.11, compatible with the existing Gradle 8.14.3/JDK17 toolchain. Android API28+ initially: actual SQLite must support repository upserts/window functions; verify on emulator rather than assume compatibility. Compile/target API35 initially with edge-to-edge/IME handling. Use app-private files and native SQLite. Reuse business repositories and security source with explicit platform connection/media adapters, never duplicate financial rules. Android resources/components use the existing IBM Plex Sans, teal, semantic money roles and labeled navigation. Shared JVM code stays tested for desktop.

Phone reference: project DESIGN_BRIEF and DESIGN_GUIDE hierarchy, Android adaptive layout/navigation and system Print/Storage Access Framework contracts. Phone flows retain Choose items → This sale → Take payment in one persistent sale. Tablets expose a larger work area. No icon-only destinations. Platform screen decisions follow existing reference lock and Refero bundled typography/component/copy craft; no live Refero tools available. Merchant research remains pending.

## Four subagent assignments (scheduled in available slots)

1. Android SQLite connection adapter and real platform transaction tests.
2. Phone/tablet checkout, exact quantities, cash/explicit credit and persistent drafts.
3. Stock, customers, money and sales Android task screens.
4. Android document/media/backup adapters and remaining-feature parity review (second wave).

Root integrates build, onboarding/owner/staff sessions, lifecycle permissions, navigation, platform source boundary, app packaging and evidence.

## Delivery checklist

- [ ] Android Gradle target, manifest, app-private records, no Internet dependency or implicit Android cloud backup.
- [ ] Native SQLite adapter: exact integer binds/results, null/blob/metadata, atomic commit/rollback, constraints, persistent reopen.
- [ ] Shop creation, owner/staff sign-in, role guards, lock/background/session timeout and back navigation.
- [ ] Sell: search/keyboard barcode, quantities/packs, review, cash tender/change, explicit customer credit, notes, retry/draft recovery and saved receipt actions.
- [ ] Sales history/details, receipt export/Android print/share and explicit returns.
- [ ] Stock creation/search, actual receiving/counting, customers/debt/repayments and drawer/expenses/closing.
- [ ] Android native image import and managed historical image bytes.
- [ ] Merchant-controlled encrypted backup create/restore via system document picker; process/session interruptions must not report success.
- [ ] More/contextual advanced workflows: supplier accounts/credits/purchases, customer advances/disputes, quotations/orders/dispatch, variants/bundles/promotions/replenishment, FIFO/reports, staff/recovery/approvals/handover and shop settings.
- [ ] Map every existing desktop and still-planned feature into an explicit Android parity matrix; shared repository availability does not mean Android UI completion.
- [ ] Assemble installable debug APK and meaningful native instrumentation tests, verify offline emulator journeys and screenshots in phone/tablet sizes.
- [ ] Desktop regression tests remain green; record platform limitations, pending physical-device/merchant/security/release work honestly.

## Beyond initial Android build

Camera scanning, Bluetooth/USB thermal printers, scales/cash drawers, keystore-backed working encryption, biometric policy, accessibility/large-font/talkback, low-memory/process-death stress, background backup scheduling, device migration, Play signing/release and real hardware acceptance need their own verified increments. All unfinished full-product features in IMPLEMENTATION_TODO.md remain unfinished on Android unless explicitly shipped. No fake success buttons for unavailable modules.

## Evidence

Build/test counts, APK path and actual device screenshots will be recorded after verification. Installing a debug APK is not production release acceptance.
