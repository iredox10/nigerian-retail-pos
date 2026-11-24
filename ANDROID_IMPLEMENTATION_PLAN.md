# Android delivery plan

Requested 23 November 2026. Cloud synchronization remains excluded. Android is an independent offline device; installing it does not connect desktop records. No provider confirmation or cross-device consistency is implied.

## Architecture and reference lock

Retain Kotlin 2.2.20 / Compose 1.9.1 and shared domain/design components. Add an Android application and Android shared target using AGP 8.11, compatible with the existing Gradle 8.14.3/JDK17 toolchain. Android API30+ initially (Android 11): the native SQLite version must support repository upserts; verify on emulator rather than assume compatibility. Compile/target API35 initially with edge-to-edge/IME handling. Use app-private files and native SQLite. Reuse business repositories and security source with explicit platform connection/media adapters, never duplicate financial rules. Android resources/components use the existing IBM Plex Sans, teal, semantic money roles and labeled navigation. Shared JVM code stays tested for desktop.

Phone reference: project DESIGN_BRIEF and DESIGN_GUIDE hierarchy, Android adaptive layout/navigation and system Print/Storage Access Framework contracts. Phone flows retain Choose items → This sale → Take payment in one persistent sale. Tablets expose a larger work area. No icon-only destinations. Platform screen decisions follow existing reference lock and Refero bundled typography/component/copy craft; no live Refero tools available. Merchant research remains pending.

## Four subagent assignments (scheduled in available slots)

1. Android SQLite connection adapter and real platform transaction tests.
2. Phone/tablet checkout, exact quantities, cash/explicit credit and persistent drafts.
3. Stock, customers, money and sales Android task screens.
4. Android document/media/backup adapters and remaining-feature parity review (second wave).

Root integrates build, onboarding/owner/staff sessions, lifecycle permissions, navigation, platform source boundary, app packaging and evidence.

## Delivery checklist

- [x] Android Gradle target, manifest and debug APK, app-private records, no Internet permission or implicit Android cloud backup; built and installed on API 37.
- [x] Native SQLite adapter: exact integer binds/results, null/blob/metadata, atomic commit/rollback, constraints, persistent reopen and missing-parameter rejection; actual API 37 instrumentation passes.
- [ ] Shop creation, owner/staff sign-in, role guards, lock/background/session timeout and back navigation.
- [ ] Sell: search/keyboard barcode, quantities/packs, review, cash tender/change, explicit customer credit, notes, retry/draft recovery and saved receipt actions.
- [ ] Sales history/details, receipt export/Android print/share and explicit returns.
- [ ] Stock creation/search, actual receiving/counting, customers/debt/repayments and drawer/expenses/closing.
- [ ] Android native image import and managed historical image bytes.
- [ ] Merchant-controlled encrypted backup create/restore via system document picker; process/session interruptions must not report success.
- [ ] More/contextual advanced workflows: supplier accounts/credits/purchases, customer advances/disputes, quotations/orders/dispatch, variants/bundles/promotions/replenishment, FIFO/reports, staff/recovery/approvals/handover and shop settings.
- [x] Map desktop and planned feature groups into ANDROID_FEATURE_PARITY.md; shared repository availability does not mean Android UI completion.
- [ ] Assemble installable debug APK and meaningful native instrumentation tests, verify offline emulator journeys and screenshots in phone/tablet sizes.
- [x] Desktop regression tests remain green (680 shared/desktop); platform/device/merchant/security/release limits recorded in BUILD_STATUS.md.

## Beyond initial Android build

Camera scanning, Bluetooth/USB thermal printers, scales/cash drawers, keystore-backed working encryption, biometric policy, accessibility/large-font/talkback, low-memory/process-death stress, background backup scheduling, device migration, Play signing/release and real hardware acceptance need their own verified increments. All unfinished full-product features in IMPLEMENTATION_TODO.md remain unfinished on Android unless explicitly shipped. No fake success buttons for unavailable modules.

## Evidence

Verified on 23 November 2026: 680 shared/desktop tests, 6 Android JVM tests and 16 native API 37 instrumentation tests pass, zero failures/errors/skips. Debug APK and Linux application image built with installed Gradle 8.13/JDK17; wrapper Gradle 8.14.3 was not used. Actual isolated emulator shop creation, counted stock, drawer opening, two-piece cash checkout/change, receipt view and unfinished-basket restart recovery were exercised while disconnected. Final sale/stock/drawer persistence was checked after restart. Screenshots and detailed limits are in BUILD_STATUS.md. Installing a debug APK is not production release acceptance.

The unchecked journey rows above remain broader parity/acceptance work, even where parts of their source are present. In particular, native backup picker cancellation/overwrite, staff lifecycle UI, credit/external/return journeys, adaptive tablet/large-font/TalkBack, API 30–36 and physical hardware need independent verification.

## 24 November continuation

Continue from the verified 680 desktop/shared + 6 Android JVM + 16 native Android checkpoint. Separate pre-existing in-progress cloud modules are preserved and are not counted as Android feature completion in this wave.

- [ ] Catalogue edit/archive/restore, metadata and selling/buying pack editors.
- [ ] Owner recovery enrollment/reset, staff PIN setup/sign-in and authenticated counted drawer handover.
- [ ] Editable quotations, orders/deposits/reservations/collection and dispatch proof.
- [ ] Merchant-controlled Android CSV export with exact money and spreadsheet-safe text.
- [ ] Integrate routes, rerun desktop/Android tests, install APK and record actual phone evidence.

Four subagent assignments run in dependency waves; root integrates and verifies. These are additional Android workflows, not a claim that the complete parity matrix is finished.
