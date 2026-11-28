# Desktop build status

23 November 2026 · Orders, stock holds, promotions and purchasing

## Android staff and saved-sale follow-up — 28 November 2026

Run [46137740261](https://github.com/iredox10/nigerian-retail-pos/actions/runs/46137740261), source `e589a3f`, completed **45 API 35 native cases with zero failures, ignored tests or assumptions**, in **159.737 seconds**. [Actual native report](artifacts/cloud-acceptance/staff-ui-review/native-results.json). This includes the reviewed staff name/role/active editor, exact-retry/stale-edit guards, PIN/session revocation and retained owner-only security history. Selected desktop output/security tests and the Android JVM checks also passed in that job.

The offline walkthrough opened CSV import, suppliers, purchase orders, stock counts and supplier credit notes. Actual [CSV import](artifacts/cloud-acceptance/staff-ui-review/csv-import.png) and [purchase orders](artifacts/cloud-acceptance/staff-ui-review/purchase-orders.png) screenshots were inspected. It then searched below the retained More-list position for Reports, which was above it. The [failure screen](artifacts/cloud-acceptance/staff-ui-review/failure-screen.png) shows the app still running; no AndroidRuntime exception was captured. Automation now returns the tool list to the top before each search. Receipt save/restart visual acceptance is still pending.

Android Saved sales now offers a confirmed removal of the selected unfinished draft. It uses the existing atomic tenant draft store; it does not refund a payment or reverse a completed sale. A new native regression checks restart, other drafts/shops, the active basket and unchanged completed sales, stock and cash. **The new 46-case suite is running; this draft-removal case has not yet passed native execution.**

## Android receipt design and signing correction — 28 November 2026

The Android receipt editor now has separate shop/design views, Classic/Modern/Compact looks, frames, preset/custom accents, left/centre headings, logo visibility, retained artwork and settings history. Its paged preview is rendered from the actual native PDF and explicitly marks sample items/amounts as **DESIGN PREVIEW — Not a sale**; sample previews create no products or financial events. Settings/artwork save together under the existing version/permission guards. Logo changes retain unsaved receipt-detail entries. Saved receipt wording distinguishes debt at sale from today's debt and prevents a printed copy from implying another payment.

The earlier API 35 run [46009704772](https://github.com/iredox10/nigerian-retail-pos/actions/runs/46009704772) finished **43 native cases: 41 passed, two failed, zero ignored/assumptions**, in 106.677 seconds. Both failures were existing manager-approval/cash-handover signing paths selecting an uninitialized Android keystore generator. [Original failed native report](artifacts/cloud-acceptance/parity-43-native-results.json). The correction uses an explicit Android software Ed25519 provider without changing global TLS/JCA provider order, historical key encoding or proof format. This preserves current app-private signing-key storage; it does not implement hardware key migration or working-database encryption.

**10 QA JVM tests pass**, including Android-provider↔desktop-JDK key/signature interoperability and changed-payload rejection. The provider/designer code and all **44 native test sources compile**. Both QA APKs packaged successfully in **48 seconds** after excluding duplicate OSGi metadata; these remain development builds. [Local build and APK hashes](artifacts/cloud-acceptance/receipt-design-local-build.json). Subsequent canonical wording and an offline UI save/restart check are running in CI. The local desktop security test attempt was interrupted when its daemon disappeared; no passing result is claimed for it. CI includes those desktop security regressions as well as the portable output tests.

The API 35 follow-up [46134837514](https://github.com/iredox10/nigerian-retail-pos/actions/runs/46134837514) completed **44 native cases with zero failures, ignored tests or assumptions**, in **102.997 seconds**. The previously failing genuine manager approval and recipient-password cash handover now pass, along with receipt centring/sample isolation/atomic artwork rollback. [Actual passing report](artifacts/cloud-acceptance/receipt-ui-review/native-results.json). The shared business-core module also compiled locally in 61 seconds.

The offline UI walkthrough stopped before creating its sample shop: its scroll gesture crossed the open keyboard and changed the password instead of revealing Create shop. The retained [actual screen](artifacts/cloud-acceptance/receipt-ui-review/failure-screen.png) and UI tree confirm that state. The automation now dismisses the keyboard before navigation; password fields also request password keyboards with autocorrection disabled. **The corrected walkthrough, actual rendered receipt inspection and offline design save/restart checks remain pending.** Merchant research, full keyboard/TalkBack checks, physical printer/paper negotiation and API 30–32 approval/handover UI acceptance remain open. Payment-provider work stays deferred; Resend delivery still needs an actual verified sender and private credentials.

## Android parity implementation — 27 November 2026

The active delivery checklist is [REMAINING_FEATURES_TODO.md](REMAINING_FEATURES_TODO.md). Live payment-provider integration is deferred by the user. Resend recovery remains unavailable until a verified sender and private credentials are configured; the response “yes” did not supply a sender address.

The feature branch adds reviewed add-only/catalogue-update CSV migration with strict bounded UTF-8 input; supplier edit/archive/restore and contact history; stable supplier/buying-pack receiving; purchase-order drafts, approval, partial delivery and discrepancy history; saved multi-item counts with stale-movement protection; supplier-credit allocation/reversal/void; WAT-dated product/staff/category reports and reviewed CSV export; customer editing/history/dated statements; versioned lead-time/safety/multiple buying rules and reorder CSV; and supplier invoice corrections/void, retained PDF/image evidence and history export. These reuse existing local repository transactions and keep stock, debt and cash corrections separate. CSV encoding and statement calculations are portable, while desktop file pickers and PDFBox remain desktop-only.

The first remote Android acceptance run [46004654246](https://github.com/iredox10/nigerian-retail-pos/actions/runs/46004654246) successfully built both QA APKs and ran the selected desktop output tests and Android JVM checks. It then failed before native execution because `adb` was absent from the runner PATH. That infrastructure problem is corrected; no passing native result or screen inspection is claimed for that run. The customer extension and all native test sources compiled locally with Java 17/Gradle 8.13 in 2m 3s. Supplier statements/buying rules/invoice evidence and all added native cases also compile; the invoice compilation completed in 2m 26s. The QA variant’s 9 JVM tests pass with zero failures/errors/skips. The suite now expects 43 native cases; their execution is pending.

A second [remote run 46006990736](https://github.com/iredox10/nigerian-retail-pos/actions/runs/46006990736) again passed the selected output/JVM checks and built both QA APKs. At last inspection its emulator setup had not reached native execution. The follow-up explicitly installs SDK platform/emulator tools, bounds device discovery, retains QA-only UI evidence on capture failures, and tests the QA variant directly to avoid compiling the same screens twice.

The latest local QA build completed in **3m 7s**, including both APKs and **9 passing QA JVM tests**, zero failures/errors/skips. The current portable-output test run also passed **159 cases**, zero failures/errors/skips, in **3m 2s**. These are scoped checks, not a rerun of every desktop test. [QA build and APK hashes](artifacts/cloud-acceptance/parity-local-build.json), [portable output results](artifacts/cloud-acceptance/parity-output-tests.json).

The downloaded first-run emulator log also revealed `Unknown AVD name`: the creation and launch tools used different default AVD locations. The workflow now creates the real configuration in an explicit shared directory, verifies it exists, and registers that location for the launcher. Native execution and actual screen captures remain pending; successful compilation is not substituted for those checks.

The task's separate API 37 emulator exited 139 during boot; the other project's emulator and the regular phone app were untouched. CI uses a fresh API 35 emulator and a separate QA package. New screen screenshots, picker journeys, keyboard/offline walkthroughs, merchant research and accessibility acceptance remain pending. This checkpoint does not complete the full specification or production distribution.

## Guided shop connection — 25 November 2026

The shared installed-app screen is now **Connected devices**. Setup starts with **Connect this shop** or **Use my existing shop**; the server address stays under Advanced settings. New installations can download a shop directly from welcome without a placeholder workspace. Pairing displays a ten-character code/QR, requires account-owner review of the named device, expires after ten minutes, and separates the public code from a high-entropy claim secret. Retry identity and native/local owner credentials are retained without overwriting existing shops. Android can read a code through its camera app; typed codes remain available everywhere.

Account options include email verification and single-use password-reset codes, bounded attempts and revocation of account sessions, device access and pending approvals after reset. Resend-compatible encrypted SMTP and a private configuration helper are implemented. **Real email delivery is not configured or verified**: no sender/domain or API key is present. The service advertises this honestly and the apps explain that recovery is unavailable until configured. Tests use an explicitly test-only mailbox, not fabricated provider delivery.

**605 desktop tests, 9 Android JVM tests, 4 cloud-client tests and 17 local server tests pass**, with zero failures/errors/skips in those groups. The server group includes 14 real HTTP/Postgres integration cases plus three security cases. New checks cover pairing owner authorization, incorrect/expired/replayed codes, revoked access, email verification/reset invalidation, bounded guesses, QR round-trip and safe first-installation import/password preservation. Normal/QA Android APKs, the 32-case native test APK and the Linux application distribution compile/package successfully. The hosted-service convergence smoke check also passed on retry in 26 seconds, using the production Java HTTPS client and two independent SQLite stores. Earlier attempts failed on connection and are retained as failures, not included in the passing local count. [Hosted convergence evidence](artifacts/cloud-acceptance/easy-remote-convergence.json).

Heroku **release v9**, backend source `0ca7155`, is deployed. Public HTTPS checks verified health/protocol, pairing start/wait/finish and rejection after finishing on v8; v9 health also passed. The final SMTP change requires TLS 1.2/1.3 with server-hostname verification. Email recovery capability is false as expected. Only disposable QA accounts/codes were used. [Deployed service evidence](artifacts/cloud-acceptance/easy-deployed-service.json).

Actual packaged desktop rendering was inspected with sample records in an isolated network namespace. [Setup choices](artifacts/cloud-acceptance/easy-desktop-choices.png) show the two actions and hidden server settings; a targeted F11 key event changed fullscreen state. The preview was closed afterward. Full keyboard account enrollment, accessibility, camera scanning and merchant usability remain unperformed. [Render/keyboard evidence](artifacts/cloud-acceptance/easy-desktop-review.json).

A fresh hosted database backup was captured and restored into a disposable PostgreSQL container with networking disabled. Constraints/migration/snapshot-shape checks passed; production was not restored or modified, and the private dump/container were removed. Daily backups are scheduled at **02:00 Africa/Lagos**; the first scheduled run has not yet occurred. The source is published to the configured GitHub repository. Its 15-minute health workflow is active and the first manually dispatched readiness run passed; actual scheduled execution and external alert delivery remain unverified. Replacement-device/domain recovery remains separate from this database restore drill.

**Latest guided-setup build is not yet installed/verified on the physical phone**, which disconnected after the earlier checkpoint below. Two attempts to start the task's isolated emulator exited with code 139; the other project's emulator was left untouched. The new native pairing test is compiled but unexecuted. The previous successful physical update and 31-case evidence below apply to the previous build, not these new screens. Current normal APK SHA-256: `44061a1aac257207334de608a8161339c186faec585bfe31ce0d3b7bea1ce20b`; QA APK: `17e4243118659e01d69d6cd9c5b20732502989428746134a93bf87eee6a03eb7`.

[Reviewed release summary](artifacts/cloud-acceptance/easy-connection-summary.json), [implementation checklist](cloud/EASY_CONNECTION_PLAN.md), [local test summary](artifacts/cloud-acceptance/easy-local-tests.json), [connection guide](cloud/CONNECTION_GUIDE.md), [service operations](cloud/SERVICE_OPERATIONS.md).

## Cloud acceptance and physical-phone findings — 25 November 2026

The shared repository now requires the active-shop session for ordinary business reads as well as writes. A new physical-phone owner/import check exposed the prior gap; the correction retains workspace-name listing for the locked picker and leaves trusted recovery on a separate connection. Denial/revocation regression tests now inspect records only after restoring an authorized test session. Android automatic reads no longer count as user activity; actual Activity user-interaction events refresh the idle timer. System-bar icons use the app's light canvas rather than becoming white on white.

**738 JVM tests pass**, zero failures/errors/skips: 79 shared + 603 desktop + 25 protocol + 3 client + 4 core + 15 backend + 9 Android JVM. Real HTTP/Postgres coverage now includes stale counts after another device's sale, competing returns that would exceed the original sale, and restart after database commit but before file checkpoint completion. Exact retries preserve accepted history and newer local work. JSON bootstrap downloads are gzip-compressed; the integration test verified a response smaller than half its decoded size and exact decoding by the real client.

Heroku **release v7**, source `f9653b3`, is deployed and its public health endpoint returned `status=ok`, protocol 1. Existing service plans remain one Basic dyno and Essential-0 Postgres. No merchant shop was published by this acceptance work.

The final **Retail POS QA** build completed **31 instrumentation cases** on the user's physical **itel A666L, Android 13/API 33** in 182.944 seconds: **29 passed, two failed, no ignored tests or assumption skips**. The completed QA-only persistent report was retrieved from the phone. All local cases pass, including exact native image-byte preservation, genuine owner/session import, separate owner unlock, retry without duplicate shops, cashier denial, native SQLite, receipt/image and security checks. Both live cases failed at cloud registration; an independent phone ping returned `unknown host` for the backend, while the computer's HTTPS health request returned `status=ok`, protocol 1. After the phone resolved the hostname again, a targeted **two-case live retry passed in 74.909 seconds**, with no failures/ignored tests/assumption skips. This verified real HTTPS publication/download, exact image import, separate owner unlock, concurrent sale convergence, revocation and reconnect through the genuine native application boundary. All **31 cases have passed across the full run plus the live retry**; a single all-green full-suite run is not claimed. The failed full-run report and [passing live retry](artifacts/cloud-acceptance/physical-live-retry-report.json) are both retained.

The regular `ng.retailpos.android` app was then updated successfully using `adb install --user 0 -r`. All **10 saved data files** under files/databases/shared_prefs had identical SHA-256 hashes immediately before/after installation, before launch. No uninstall or data clearing was performed. The regular MainActivity launched and was verified resumed with no AndroidRuntime errors in the inspected log window. The phone returned to its lock screen; a rendered app screen and manual idle-timer acceptance were not verified. Current debug APK SHA-256: `0fd9b51fa0a8ea90b0a4c6b148b2ecd293179bbc625b3e810a0ea87dbc8193e9`; acceptance APK: `c38c7c7ca0869b2846163b1e9f128cedc21bdf532a1d729a871647740e18e7d0`.

The QA-only persistent-result listener and bounded screen wake lock now have actual device execution evidence. The run completed with USB connected, and the report recorded both failures rather than presenting a false pass. APK manifest inspection verified `WAKE_LOCK` only in the acceptance build, with Internet permission retained in both builds. See [physical result](artifacts/cloud-acceptance/physical-final-report.json) and [data-preserving update evidence](artifacts/cloud-acceptance/regular-phone-update.json).

Windows per-user MSI/EXE configuration and a Windows Server 2022 GitHub Actions job are committed, including actual owner-ACL/secret round-trip assertions and installer/report artifacts. **No Windows run or installer is claimed:** this host is Linux and GitHub CLI/API connections timed out. The ACL test passed on Linux; its Windows branch awaits a Windows runner.

[Reviewed execution summary](artifacts/cloud-acceptance/test-summary.json), [physical-phone instructions](cloud/PHYSICAL_ANDROID_ACCEPTANCE.md), [Windows instructions](cloud/WINDOWS_ACCEPTANCE.md), [remaining acceptance checklist](cloud/ACCEPTANCE_TODO.md). Interactive two-app enrollment, full keyboard/accessibility and idle-timer acceptance, large-data/low-spec performance, power-loss/independent-machine recovery and merchant usability remain unperformed. The initial phone screenshot captured the system-icon visibility problem before its fix; no after-fix phone render is claimed.

## Initial desktop–Android cloud connection — 24 November 2026

Cloud exclusion is superseded for this requested connection work. The initial Spring Boot/Postgres backend is deployed on Heroku as `nigerian-retail-pos-cloud`, release **v6**, backend source **a669335**. Its public HTTPS `/api/v1/health` returned `{"status":"ok","protocolVersion":1}` against the provisioned database. Hosting uses **one Basic dyno and Essential-0 Postgres**, currently $7 + $5 per month. Only explicitly disposable QA accounts/businesses were published during verification; existing merchant workspaces were not uploaded or linked automatically.

Both installed apps now expose **More → Cloud connection**. A local owner can create/sign into a separate cloud account, review publication of the first shop, download an existing cloud shop as a new local workspace, renew device access and list/revoke devices. Each device keeps its own local owner/staff passwords, recovery codes and drafts. Android device tokens use AES-GCM and Android Keystore; Linux desktop uses verified owner-only files, with a desktop-only ACL adapter for Windows. Windows execution/ACL behavior remains unverified here.

The protocol preserves exact decimal-string scalars, generated child identities, immutable financial source rows and deterministic conflicts. Upload identities/payloads and pull checkpoints are durable. Accepted server data, revisions and acknowledgments commit together in PostgreSQL. Local application rechecks permissions and current records within a transaction and retains newer local writes. Sync delivery flags stay device-local. Normal trading does not wait for HTTP. Automatic checks run about every 15 seconds while the selected shop is unlocked; Android pauses on backgrounding. Status distinguishes saved local work, retry, expired/revoked access, conflict and a real successful sync. Provider verification and settlement remain separate and unconnected.

### Verification

**731 JVM tests pass**, zero failures/errors/skips: 79 shared + 601 desktop + 9 Android JVM + 25 protocol + 2 client + 4 business-core + 11 backend. The backend count includes seven real HTTP/Postgres tests, three security tests and one opt-in deployed HTTPS smoke test. The latter used two independent SQLite repositories, concurrent offline sales, an upload retry, and verified identical stock (7 units), two sales and drawer cash (1,300 minor units) on both clients. Other tests cover lost acknowledgments plus newer local writes, coordinator restart, consecutive cursors, idempotent import, revocation/expiry, tenant isolation, forbidden stock edits and stale count cutoffs.

**29 Android instrumentation tests pass** on the task-owned API 37 emulator in 85.991 seconds, including Android Keystore ciphertext round-trip and actual native HTTPS publication, upload retry and download against Heroku. A separate repeat of the native checkpoint/sales test passed with airplane mode enabled, proving native checkout and stock/cash merge without a server request. The live tests used disposable records and revoked their device tokens afterward.

Ran installed Gradle 8.13/JDK17 with bounded memory and one worker for the combined regression/build command; it succeeded in 5m 55s. Desktop packaging after the desktop-only ACL adapter also succeeded in 1m 33s. Heroku independently built the backend using the committed Gradle 8.14.3 wrapper and Java 17. The final Android debug APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `774d893b45c2a4c587493d566330319c8e11c47bd73eb127e70b7d2fe00bdc9a`. It now declares Internet permission and keeps cleartext traffic disabled. It remains a development-signed build.

Actual packaged Linux desktop rendering was inspected in an isolated network namespace: [Cloud connection screen](artifacts/cloud-verification/desktop-connection.png). The app opened offline and kept local/remote status separate; limited Tab/F11 interaction was exercised. A full keyboard-only account setup/submission journey was not verified. The final private preview was closed after inspection. [Reviewed test summary](artifacts/cloud-verification/test-summary.json).

Manual Android cloud setup/publish/join/revocation walkthroughs remain unverified: another app session was using the emulator during the subsequent UI inspection. No screenshot of that other app was retained. Native tests above are actual execution evidence, not a manual UI walkthrough. Full desktop interactive two-app enrollment, cross-platform photo/large-data performance, concurrent return/count HTTP acceptance, app termination/power-loss extremes, physical devices, Windows, operational disaster recovery and merchant usability remain outstanding. Conflicting completed history has no force-overwrite action. Cloud sync is not point-in-time cloud backup or payment verification.

Use [cloud/CONNECTION_GUIDE.md](cloud/CONNECTION_GUIDE.md) for connection steps and [cloud/IMPLEMENTATION_PLAN.md](cloud/IMPLEMENTATION_PLAN.md) for remaining acceptance. Earlier dated cloud-excluded checkpoints below describe their historical scope.

## Android catalogue, security, orders and CSV — 24 November 2026

**680 shared/desktop tests pass** (79 shared + 601 desktop), **9 Android JVM tests pass**, and **26 native Android instrumentation tests pass**, with zero reported failures/errors/skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --max-workers=2` successfully in 2m 58s using installed Gradle 8.13/JDK17. Both APKs installed successfully; the native runner completed in 54.754 seconds on the task-owned API 37 emulator. After a phone-width tab adjustment, the APK and all 9 JVM tests rebuilt successfully in 21 seconds and the final APK was installed again.

Four subagents supplied catalogue, security, trade/order and CSV slices; root integrated the routes, sign-in and verification. Micro commits separate the source changes. This extends the prior Android checkpoint; it does not complete the full parity matrix.

- Stock now has active/archive views, reviewed atomic item/metadata editing, immutable catalogue history, and fixed selling/buying package creation/archive/restore. Unit meanings and existing sales stay preserved.
- Locked sign-in supports full-password-authorized temporary staff PINs and single-use owner recovery. Owner recovery enrollment is owner-only and displays the proof once, in memory. Recipient-password cash handover on API 33+ retains shortages and atomically closes/opens the existing shared drawer. It does not implement multiple registers.
- Quotations support reviewed creation/revision, acceptance/cancellation and full-cash/order conversion. Orders support actual deposits, cancellation refunds, dated stock holds, partial/final collection and saved receipts. Dispatch records retain merchant-entered recipient/courier/tracking and delivered/failed/retry/returned proof history; they do not claim external courier confirmation.
- CSV exports cover consistent tenant-scoped sales, current catalogue, current customer accounts and signed cash movements. Preview and save recheck permissions. Exact numeric columns stay distinct from formula-neutralized text. Android's document picker controls the local destination. Import remains unavailable.

### Actual Android phone evidence

Used the existing task-owned 720 × 1280 API 37 emulator (280 dpi), with airplane mode recorded as 1 and Wi-Fi/mobile data disabled. Existing shop records were preserved; a new `Android wave 24 sample` shop supplied these checks.

Created a whole-unit QA product with 10 pieces at ₦550, opened Manage item, reviewed/saved its SKU and retained the same count/price. Force-stopped/reopened the final app, authenticated again and verified the retained product through a CSV snapshot. Native review found the last catalogue tab partially clipped; the final layout uses three equal-width 48-dp controls and the short “Packs” label. [Catalogue review](artifacts/android-catalogue-review-24nov.png), [saved metadata](artifacts/android-catalogue-saved-24nov.png), [visible installed app](artifacts/android-visible-24nov.png), [shop tools](artifacts/android-tools-24nov.png).

Generated the exact one-row Stock CSV preview and saved it through the actual Android Downloads picker. Pulled and parsed the resulting file: one product from the selected shop, price `550.00`, quantity `10` and the saved SKU. [Preview](artifacts/android-csv-preview-24nov.png), [saved](artifacts/android-csv-saved-24nov.png). Keyboard text entry and Tab were used; this is not a complete keyboard-only navigation study.

Native tests additionally cover edit rollback on duplicate SKU, stale/denied changes, measured/packs and historical receipts, PIN expiry/removal/reset, recovery consumption/revocation, genuine signed handover/retry/shortage/recovery, quotations/orders/reservations/dispatch and exact cash/stock histories, and CSV tenant/permission/no-financial-mutation rules. Earlier native receipt/image/storage tests remain in the 26-test suite.

Full manual PIN/recovery/handover and quotation/order/dispatch phone journeys, export cancellation/failure, tablet/large-font/TalkBack, API 30–36, physical peripherals, another-device migration and merchant usability remain unperformed in this continuation. See ANDROID_FEATURE_PARITY.md for remaining editing/reporting modules.

The final debug APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `f8864a02236ced9e756bb2fa5787b9561df57eb63fb01239876819dc14487ea8`. It declares no Internet permission. This is a development build, not production release acceptance. Separate pre-existing cloud work remains uncommitted; one list/array type-compatibility correction in its CloudRepository source was needed for this workspace build. These Android results do not validate or complete cloud synchronization.

## Android product quantity selector — 23 November 2026

A phone user reported “Choose zero to six quantity decimal places” while adding goods. Replaced the free-text precision field with a labeled selector limited to the supported 0–6 choices. Whole quantities (0) remain the explicit default; measured options show examples such as 1.250 for three places. The existing precision/quantity persistence rules remain unchanged, including immutable product precision.

Android debug build and all 6 Android JVM tests pass (`:androidApp:testDebugUnitTest :androidApp:assembleDebug --offline`, installed Gradle 8.13/JDK17, 27 seconds). Actual emulator renders verified the whole-quantity default, all seven menu choices and selected three-place measured option: [default](artifacts/android-product-precision-selector.png), [measured choice](artifacts/android-product-precision-measured.png). The emulator disconnected before the final product-save walkthrough; no completed save is claimed for this UI change. The physical phone was also disconnected, so the rebuilt APK has not yet been installed there. Saved business data was not reset or changed by this fix.

## Android build, native storage and offline phone checks — 23 November 2026

**680 shared/desktop tests pass** (79 shared + 601 desktop), together with **6 Android JVM checkout tests** and **16 native Android instrumentation tests**, all without failures, errors or skips. The final build ran `:shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline` using installed Gradle 8.13 and JDK 17, successful in 61 seconds. The wrapper declares Gradle 8.14.3 but its distribution was not locally cached; it was not used for this checkpoint. Native instrumentation ran separately in 17.306 seconds on the API 37 `sdk_gphone16k_x86_64` emulator.

The debug APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (approximately 31 MB), signed with the development key. It has no Internet permission and disables automatic Android app-data backup. Android records are independent of desktop; no sync, external bank confirmation or production release is claimed. The declared minimum is API 30; API 30–36, physical devices and release signing remain unverified. Manager price approvals are currently exposed only on API 33+.

### Verified changes

- Native SQLite retains exact integer money/quantities, blobs/nulls, foreign keys, rollback and reopen behavior. Missing prepared parameters now fail before queries/writes instead of becoming NULL; explicit NULL and quoted/commented question marks are covered. A first attempt exposed an Android-version exception difference; the corrected implementation passed the full native suite.
- Shared stock movement queries now use exact integer CASE factors for supported precision rather than an optional SQL POWER function. A real native test sells measured and packaged goods together, restores a measured return, and checks stock, movement deltas and cash.
- Native tests exercise real owner/staff credentials, denied cashier writes, revocation, separately authenticated manager signatures, customer advances/disputes/bundles, managed images, encrypted snapshot recovery and receipt PDF pagination/bounds. Restore rechecks the source owner's authority after document decryption and again before provisioning the recovered owner; denied or revoked imports leave the original records and tenant list unchanged.
- Checkout retains a selected customer on a fully paid sale. Adding quantities to a line rejects conflicting agreed prices/reasons rather than silently discarding new terms. Unit tests cover these cases and exact quantity/overflow behavior.
- Actual phone review exposed a scroll-hidden total and a checkbox label that was not selectable. Total and final change/debt/unconfirmed status now remain beside the save action; payment focuses on payment fields, and cash-confirmation rows have one labeled, selectable checkbox target. This is targeted usability correction, not full accessibility certification.

### Actual disconnected phone evidence

Created a separate `Offline QA 23Nov` shop on the emulator with Wi-Fi and mobile data disabled. Used touch and keyboard/Tab entry to add 10 counted soap pieces at ₦1,250 each and open a counted ₦1,000 drawer. Tapping the confirmation label selected its checkbox and saved the count. Added two pieces, restarted the updated application and recovered the unfinished ₦2,500 basket. Entered ₦3,000 cash, reviewed ₦500 change, saved once and opened the captured receipt. The drawer showed ₦3,500 and stock showed 8 pieces. A later force-stop/relaunch and owner sign-in checked the saved sale and these balances again.

[Count review](artifacts/android-offline-drawer-reviewed.png), [basket](artifacts/android-offline-basket.png), [payment review](artifacts/android-offline-cash-review.png), [saved sale](artifacts/android-offline-sale-saved.png), [receipt](artifacts/android-offline-saved-receipt.png), [sales after restart](artifacts/android-offline-sales-after-restart.png), [drawer after restart](artifacts/android-offline-cash-after-sale.png), [stock after restart](artifacts/android-offline-stock-after-sale.png).

Print/share actions were present; this walkthrough did not submit a physical print or send a document. Tablet/foldable/rotation, large fonts/TalkBack, background expiry, broad Android credit/return/external payment/backup picker journeys, minimum-API runtime checks, physical peripherals, another-device restore and merchant research remain pending. Many advanced Android tools currently read shared records rather than edit them. [ANDROID_FEATURE_PARITY.md](ANDROID_FEATURE_PARITY.md) distinguishes source implementation from missing UI and acceptance.

## Local accounts, stock and authorization — 23 November 2026

**680 tests pass** (79 shared and 601 desktop), with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` and rebuilt the Linux application. This checkpoint adds 59 tests over the receipt-printing checkpoint. Five subagents contributed in dependency waves within the available concurrency limit; root integrated permissions, cash, statements, recovery and routes. Source and UI were recorded in separate commits.

### Implemented scope

- Cash customer advances support explicit receipt-debt application and unused cash refunds. Customer and dated CSV/PDF statements distinguish advance applications from cash repayments. Applications reduce debt without posting drawer cash again. Receipt disputes retain disputed/undisputed current balances and reasoned resolution without changing financial events.
- Supplier credit notes support references, unapplied balances, reviewed receipt allocation/reversal and dated statements, including buying returns and cash payments in chronological order. No credit action fabricates cash.
- Structured one/two-axis variant matrices retain separately stocked combinations. Versioned bundles expand real component stock into reviewed whole-bundle cash sales with exact price allocation, tender/change, saved source and receipt printing.
- Saved lead times, safety stock and buying multiples extend reorder suggestions, including reserved stock and approved incoming purchases. Category comparisons use current catalogue classification, return dates and the preceding equal-length WAT period.
- FIFO purchase-cost layers support declared opening cost, original-cost sellable returns, non-restocked return losses, purchase-return depletion and repacking material-cost transfer. Missing/ambiguous cost sources remain explicit gaps; delivery allocations remain separate expenses. Complete profit, alternative costing policies and capitalized landed-cost treatment remain open.
- Full-password manager price approvals bind the current cart, operation and minimum-price versions, with expiry/revocation and signed historical evidence. The cashier can request fresh approval after expiry. Counted shared-drawer handovers require genuine recipient consent and atomically close/open shifts with the difference retained. This does not implement multiple-register custody.
- Locally enrolled owner recovery uses a single-use code, persistent throttling, credential/session/PIN revocation and audit. Credential and signing secrets remain outside business backups.
- Accepted quotations can create customer orders using preserved goods/units/prices and an optional actual cash deposit. Dispatch records reference already-sold order goods and retain recipient/courier/tracking plus merchant-recorded delivered/failed/retry/returned proof history. They do not claim courier-service confirmation.
- Local diagnostics preview exact allowlisted text/JSON and export only on request. SQLite structure, actual storage write/cleanup and installed print-service counts have separate states. Private data, paths and raw errors are excluded. Network, sync, updates and unsupported backup metadata remain unchecked/unavailable.

### Actual Linux and disconnected evidence

Used isolated native Compose displays with loopback-only network namespaces. The existing merchant workspace was not used for test transactions.

- Recorded/resolved a ₦250 dispute against sample credit. Received ₦1,000 advance, applied ₦500 to receipt debt and refunded ₦300; available advance became ₦200, debt became ₦250 and drawer moved from ₦10,000 to ₦10,700, with no application cash row. Keyboard entry, Tab/Return and reviews passed. Native review exposed two misleading cash labels; these were corrected to separate advance funding. [Advance](artifacts/desktop-customer-advance-refunded.png), [drawer](artifacts/desktop-customer-advance-drawer-after.png), [dispute](artifacts/desktop-customer-dispute-resolved.png).
- Recorded a ₦3,000 supplier credit, applied ₦2,000 and reversed it with history. Purchase debt changed ₦18,000→₦16,000 while applied; available credit returned to ₦3,000 after reversal. Drawer remained ₦10,000. FIFO initially showed no cost coverage, then saved a declared ₦28,800 cost for 96 soap pieces; coverage remained correctly partial at 1/6. [Credit](artifacts/desktop-supplier-credits-saved.png), [reversal](artifacts/desktop-supplier-credits-reversal.png), [cost source](artifacts/desktop-fifo-source-ledger.png).
- Created six shirt size/colour combinations with independent SKUs, ₦3,500 prices and explicitly zero opening counts using keyboard review/save. Saved a two-soap bundle, completed its ₦900 sale with ₦1,000 given and ₦100 change, and saw the saved-receipt print action. Buying rules saved 2 lead days, 3 safety pieces and a buying multiple of 12, yielding a 12-piece suggestion. Category comparison rendered ₦2,400 current/₦0 preceding net sales, and remained readable at 1100 × 800. [Matrix](artifacts/desktop-variant-matrix-created.png), [bundle](artifacts/desktop-bundle-sale-saved.png), [rules](artifacts/desktop-replenishment-rules-saved.png), [comparison](artifacts/desktop-category-comparison-compact.png).
- In a real isolated owner workspace, enrolled recovery, reset while locked, rejected the old password and accepted the new password. Enrolled a cashier, then completed recipient-password handover with ₦2 expected/₦1.90 counted. Read-only source checks confirmed the old shift closed with its shortage, a new ₦1.90 opening and one signed handover/audit pair. Temporary auth data was removed. [Recovery](artifacts/desktop-owner-recovery-success.png), [handover](artifacts/desktop-cash-handover-review.png), [saved](artifacts/desktop-cash-handover-saved.png).
- In cashier sample practice, proposed a ₦300 discount, authenticated the separate sample manager and completed the approved ₦13,700 cash sale as the cashier. The fresh-approval action remained visible. [Approval](artifacts/desktop-price-approval-ready.png), [sale](artifacts/desktop-price-approval-saved.png).
- Ran local diagnostics, inspected text/JSON, saved via the native chooser and checked exactly 15 allowlisted fields. The in-memory database correctly reported storage unavailable, printer services 0 and unchecked network/sync. [Preview](artifacts/desktop-local-diagnostics-json-complete.png), [saved](artifacts/desktop-local-diagnostics-saved.png).

Native quotation/order/dispatch acceptance also passed: accepted 13 soap pieces at negotiated ₦400 (catalogue ₦450), converted the ₦5,200 quote with a counted ₦1,000 deposit, then collected all goods with ₦4,200 due. Dispatched the 13 pieces and saved recipient name/text proof with Tab/Return. Final expected cash was ₦15,200 from ₦10,000 opening, with separate ₦1,000 deposit and ₦4,200 collection rows; stock showed 83 pieces from 96. Both dispatch and delivery events remained visible. There was no pre-dispatch cash/stock screenshot; tests separately enforce no additional dispatch movement. [Order review](artifacts/desktop-quotation-order-review.png), [linked order](artifacts/desktop-quotation-order-saved.png), [cash](artifacts/desktop-quotation-order-cash.png), [delivery proof](artifacts/desktop-order-dispatch-delivered.png). Failed/retry/returned dispatch, native stale rejection and native restore were not exercised in this walkthrough.

Automated tests cover preserved quotation pricing, deposits, dispatch quantities, immutable history, permission denial, stale reviews, rollback/retry, tampering and encrypted/snapshot recovery. Physical print delivery, Windows packaging, independent-machine restore, full keyboard-only/accessibility/scanner journeys, low-spec performance and merchant first-glance/task research remain unperformed. Cloud sync remains excluded. IMPLEMENTATION_TODO.md retains incomplete requirements rather than treating this checkpoint as the full product.

## Modern receipt refinement and checkout printing — 23 November 2026

**621 tests pass** (79 shared and 542 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` and rebuilt the Linux image.

Modern receipts now use a larger shop masthead, real divider rules, semibold item names, aligned monetary columns, supporting detail tones and a rounded accented total panel. A realistic three-item sale with discounts, part cash, an unconfirmed transfer, debt and policy fits one A4 page. Narrow receipts paginate with their identity and preserve financial status. Short single-page Modern thermal receipts fit their content with an 80 mm minimum height. Wrapped policy paragraphs stay together when they fit on a fresh page.

The editor has illustrated style choices and Fit/150%/200% PDF preview controls with horizontal panning. Fresh unsaved profiles start with Modern. Existing profile choices remain selected.

Sell now offers **Print receipt** immediately after durable checkout, above New sale. It reads the completed sale and captured design before opening the native printer flow. Print failure or cancellation leaves the saved sale intact. The saved-state details can scroll while both actions remain reachable in smaller windows.

### Rendered and native evidence

Generated actual BrandedReceiptService PDFs for A4, 58 mm and 80 mm with a ₦14,750 sale, ₦150 discount, ₦5,000 cash, ₦5,000 unconfirmed transfer and ₦4,750 original credit. Inspected the rendered A4 page, 80 mm first page and 58 mm continuation. [A4](artifacts/receipt-modern-redesign-a4.png), [80 mm](artifacts/receipt-modern-redesign-80mm.png).

Used isolated practice with loopback-only networking. Reviewed the illustrated selector and Modern PDF preview. Completed a ₦14,000 sample cash sale with ₦15,000 given, then saw the print option and ₦1,000 change. Clicking Print receipt opened the native print workflow, which reported no print service. Dismissal returned to the saved sale with retry available. Also checked the saved-screen actions at 1100 × 800. [Editor](artifacts/desktop-modern-receipt-editor.png), [print option](artifacts/desktop-checkout-print-option.png), [smaller window](artifacts/desktop-checkout-print-compact.png).

Regression tests cover wrapped policy pagination, long merchant/product names, maximum-range money, preserved unconfirmed-payment wording, text bounds, short thermal height and repeated print preparation using captured branding without another sale. Physical printing and merchant usability remain unperformed.

## Receipt customization and money entry — 23 November 2026

**617 tests pass** (79 shared and 538 desktop), with zero failures, errors or skips. The Linux image rebuilt with `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`.

- More → Shop & receipt details → Receipt design offers Classic, Modern and Compact, no/single/double frames, preset or custom accent colour, heading alignment, logo visibility, tagline, policy and an additional PNG/JPEG image. Actual PDF pages provide the preview, including pagination. Rendering runs off the UI thread after a short edit delay.
- New sales capture their design and immutable image references. Saved Sales can preview/export the captured design and submit that PDF through the native printer dialog. Existing version-one settings remain readable with the classic left-aligned default. Encrypted backup includes artwork bytes and import history.
- Shared monetary inputs show a naira prefix and live thousands grouping, including cash, prices, deposits, repayments and credit limits. Displayed amounts use naira, grouping and two decimal places. Input preserves entered decimal digits and integer kobo semantics. Quantity, percentage and denomination-count fields keep numeric entry.

### Actual visual and desktop checks

Used isolated Linux Compose at 1440 × 1000 with sample records and loopback-only networking. Selected Modern, Double line and Blue, entered a tagline and policy, imported a sample PNG through the native chooser, chose A4 and saved with a reason using Tab/Enter. A subsequent ₦14,000 sample sale received ₦15,000 with ₦1,000 change. Its saved receipt preview showed the captured design and artwork. [Editor](artifacts/desktop-receipt-design-modern.png), [artwork](artifacts/desktop-receipt-artwork-preview.png), [saved settings](artifacts/desktop-receipt-design-saved.png), [saved receipt](artifacts/desktop-saved-receipt-design.png), [money entry](artifacts/desktop-money-input-grouping.png).

Generated nine renderer fixtures across three styles and A4/58/80 mm. Inspected rendered first pages for A4 Modern and 58 mm Compact, plus the last page of an 80 mm Modern receipt. Frames, text, continuation identity, policy and artwork stayed within their page bounds. [A4 render](artifacts/receipt-design-a4-render.png), [thermal render](artifacts/receipt-design-thermal-render.png).

Tests cover old settings, immutable sale appearance, recovery, image provenance, tenant permissions, all style/width combinations, preserved unconfirmed-payment/debt text, pagination, failed-export preservation and input cursor mappings. Physical printing, Windows/native printer compatibility and merchant usability testing remain unperformed. Text export retains its plain-text format. Design customization applies to sales receipts. Other document templates retain their existing layouts.

## Desktop visual refinement — 23 November 2026

The merchant requested clearer icons and a more intuitive, attractive interface. The shared controls now use locally bundled Lucide outline icons with persistent text, icon-backed headings and empty states, and semantic notice symbols. Navigation has matching destination icons and a shop mark. Product image placeholders use package symbols. More presents permission-filtered tools as grouped responsive cards. Checkout keeps the goods, sale and payment hierarchy with clearer search and receipt cues. The design guide and token file record the updated corner and icon roles. Lucide attribution is retained in design/licenses/lucide.txt.


All **608 tests pass** (77 shared and 531 desktop), with no failures, errors or skips. Rebuilt the Linux application using `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`.

Checked actual Linux Compose screens in isolated practice with loopback-only networking at 1440 × 1000. Reviewed Sell, Stock, Customers, Sales, Money, shop tools and an empty order list. Tab/Enter opened Customer orders from the tool cards. Also checked Sell and Money in a 1100 × 800 window. Widened the repeated Add stock action after its added icon caused wrapping. These are selected visual and keyboard checks. Full accessibility and merchant comprehension testing remain pending.

[Sell](artifacts/desktop-ui-sell.png), [shop tools](artifacts/desktop-ui-shop-tools.png), [Stock](artifacts/desktop-ui-stock.png), [Customers](artifacts/desktop-ui-customers.png), [Sales](artifacts/desktop-ui-sales.png), [Money](artifacts/desktop-ui-money.png), [smaller Sell window](artifacts/desktop-ui-sell-compact.png), [order empty state](artifacts/desktop-ui-orders-empty.png).

## Current integrated checkpoint

**608 tests pass** (77 shared and 531 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image rebuilt successfully.

- More → Customer orders saves agreed goods, units and prices, cash deposits and explicit cancellation refunds. Partial and final counter collections create linked sale receipts and apply the remaining deposit once. Drawer reports and receipt exports retain the linked amounts.
- Remaining order quantities can be reserved through a selected WAT date. Holds shrink with collection and end on release, cancellation, completion or expiry. Checkout, supplier returns and repacking protect reserved stock. Stock shows on-hand, reserved and available quantities, and Sell uses available quantities. Long-open screens refresh the WAT date every 30 seconds.
- Stock tools → Promotions configures dated fixed or percentage offers, selling-unit and quantity eligibility and optional sale caps. Sell reviews the exact reduction. Saving checks the terms again and preserves offer details in held baskets and receipts.
- Stock tools → Landed costs allocates an existing paid expense equally or by purchase value. Shares reconcile to the kobo, and revision/removal keeps earlier reasons and amounts. Purchase cost history shows the allocation.
- Suppliers → Purchase orders records and resolves shortages, damaged goods and unexpected goods, with an optional link to a matching later receipt. Quarantine and supplier claims remain pending.

### Disconnected desktop checks

Used actual Linux Compose at 1440 × 1000 with isolated sample records and loopback-only networking. Exercised selected keyboard entry, Tab/Enter and pointer actions.

- Recorded a ₦700 deposit and collected two ₦450 soap pieces. The review applied ₦700 and collected ₦200, then showed the remaining quantities and linked receipt. [Deposit](artifacts/desktop-customer-order-deposit.png), [collection review](artifacts/desktop-customer-order-collection-review.png), [partial collection](artifacts/desktop-customer-order-part-collected.png)
- Reserved 13 soap pieces from 96 on hand. Stock displayed 13 reserved and 83 available, and Sell displayed 83 available. [Saved hold](artifacts/desktop-order-stock-held.png), [Stock](artifacts/desktop-order-stock-available.png), [Sell](artifacts/desktop-order-sell-available.png)
- Created a one-day 20% malt promotion, reviewed a ₦300 reduction, put aside and resumed the basket, then saved ₦13,700 cash. The saved receipt retained the offer and usage increased by one. [Review](artifacts/desktop-promotion-review.png), [resumed basket](artifacts/desktop-promotion-resumed.png), [receipt](artifacts/desktop-promotion-receipt.png)
- Allocated a ₦750.01 transport expense across ₦4,000 and ₦20,000 receipts as ₦125.00 and ₦625.01. Revised to an equal split and removed it with a reason. Expected cash stayed ₦8,749.98 through those allocation actions. [Review](artifacts/desktop-landed-cost-review.png), [history](artifacts/desktop-landed-cost-revision-history.png), [drawer](artifacts/desktop-landed-cost-cash.png)
- Recorded one soap piece missing from an approved four-piece purchase order, then resolved it with the supplier acknowledgement reason. [Review](artifacts/desktop-purchase-discrepancy-review.png), [resolution](artifacts/desktop-purchase-discrepancy-resolved.png)

Automated coverage includes deposit/refund reconciliation, partial collection and encrypted recovery, competing stock holds, cashier permission enforcement, WAT midnight expiry, stale changes and backup tampering, promotion validity and usage, exact expense allocation and discrepancy history. Native hold release and midnight expiry were not exercised. Windows, physical peripherals, comprehensive accessibility, replacement-machine recovery and merchant testing remain pending. The remaining local backlog is in LOCAL_DESKTOP_TODO.md. Cloud sync is excluded by the user's instruction.

## Earlier cash exchange checkpoint

**593 tests pass** (77 shared and 516 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Sales supports exchanges from cash receipts and cash-funded replacement receipts. The review uses the original saved return values and current replacement prices. The linked return, replacement sale, stock changes and cash difference save together. Receipts and sales exports show the value applied through the exchange.
- Supplier returns can reduce outstanding receipt debt and record the excess as cash received into an open drawer. The quantity, debt, cash movement and history save together. Supplier statements and reports show the reimbursement alongside the debt reduction.
- Stock tools can match up to 64 PNG or JPEG files to active products by SKU or barcode. Merchants preview the matches, select photos and record a reason. Selected main photos save together using the existing revision history and encrypted backup storage.

### Disconnected desktop checks

Used the Linux app at 1440 × 1000 in an isolated practice workspace with loopback-only networking. Used keyboard entry, Tab/Enter and pointer actions.

- Exchanged a ₦500 sample malt drink for ₦450 soap. The replacement used ₦450 of return value and Money showed a single ₦50 refund. [Review](artifacts/desktop-cash-exchange-review.png), [replacement receipt](artifacts/desktop-cash-exchange-saved.png), [drawer](artifacts/desktop-cash-exchange-drawer.png)
- Returned two sample rice bags. The return reduced supplier debt by ₦18,000 and recorded ₦2,000 received into the drawer. [Review](artifacts/desktop-supplier-reimbursement-review.png), [settled receipt](artifacts/desktop-supplier-reimbursement-saved.png), [drawer](artifacts/desktop-supplier-reimbursement-drawer.png)
- Opened a folder in the native chooser, matched a photo to a sample barcode, skipped an unmatched file and saved the selected photo using the keyboard. Verified the thumbnail in Stock. [Review](artifacts/desktop-bulk-photo-import-review.png), [Stock](artifacts/desktop-bulk-photo-import-stock.png)

Automated tests cover higher, lower and equal exchange prices, rollback, receipt linkage and encrypted restore. Supplier reimbursements have tests for fully and partly paid purchases, permission checks and recovery. Bulk import tests cover duplicate matches, shop isolation, retries and stale-review rollback. Windows, physical peripherals, full accessibility, replacement-machine recovery and merchant testing remain pending.

## 21 November checkpoint — stock tools, documents and debt corrections

**585 tests pass** (77 shared and 508 desktop) with zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline`. The Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Stock tools now shows reorder suggestions using exact stock units, 30 WAT days of sales after returns, the approved quantities still due, and a configurable coverage period. It exports the visible review to CSV.
- Stock tools shows historical buying prices and compares the latest saved receipt for each explicitly linked supplier. It sorts exact price ratios using the original buying conversion and marks rounded stock-unit prices.
- Sales exports A4 invoices, delivery notes for handover and credit notes for saved returns. Invoices retain original amounts and show manually recorded external payments with their unconfirmed status. Delivery notes leave handover confirmation to the recipient.
- The photo editor applies JPEG orientation from the file and supports cropping and rotation. Each product can hold six additional photos with reasoned revision history. Encrypted backups include the revisions, and saved receipts retain the logo they captured.
- Owners can review, write off and restore linked receipt debt. Credit ageing, receipt allocation, statements and exports reconcile both recorded events. A return against an affected receipt requires restoring active write-offs first.

### Disconnected desktop checks

Ran actual Linux Compose at 1440 × 1000 with sample records in a loopback-only network namespace. Used text entry, Tab/Enter and selected pointer actions.

- Updated the reorder review to 14 days of coverage and inspected its saved CSV. The file showed four malt bottles and one rice bag suggested. [Reorder suggestions](artifacts/desktop-reorder-suggestions.png)
- Opened the linked sample supplier comparison and inspected the exported receipt reference, base-unit conversion and ₦10,000 per bag price. Exact comparisons between different buying units have automated coverage. [Supplier price comparison](artifacts/desktop-purchase-cost-comparison.png)
- Opened and exported all three PDFs through the native file chooser and checked the resulting file text. The invoice retained ₦1,500 total and ₦1,000 owed at sale. The delivery note listed three bottles and blank handover fields. The credit note tied ₦1,000 in returned goods to a ₦750 debt reduction and ₦250 cash refund. [Invoice](artifacts/desktop-sales-invoice-preview.png), [delivery note](artifacts/desktop-delivery-note-preview.png), [credit note](artifacts/desktop-credit-note-preview.png)
- Rotated a sample product photo and cropped its left edge by 8 percent. Saved it as the main photo and added the original image as a gallery photo. [Crop and rotation](artifacts/desktop-product-photo-crop-rotate.png), [gallery](artifacts/desktop-product-photo-gallery.png)
- Reviewed and saved a ₦250 write-off against a ₦750 sample balance, then restored it. The saved balance was ₦500 after write-off and ₦750 after reinstatement. The exported customer CSV recorded both signed amounts with the same receipt reference. [Review](artifacts/desktop-debt-writeoff-review.png), [saved write-off](artifacts/desktop-debt-written-off.png), [reinstated history](artifacts/desktop-debt-reinstated.png)

Automated coverage includes encrypted recovery, financial rollbacks, WAT statement cutoffs, purchase-unit conversion, historical logo preservation and JPEG orientation. Windows, physical peripherals, comprehensive keyboard and accessibility acceptance, replacement-machine recovery, and merchant trials remain pending.

## 20 November checkpoint — checkout and local account workflows

**570 tests pass** (77 shared and 493 desktop). Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with Gradle 8.13 and JDK 17. The rebuilt Linux image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`.

- Checkout preserves cash tender and change on completed sales. Receipts and CSV distinguish cash applied to the sale from cash handed over. Optional sale notes survive active and held baskets and appear on completed receipts and exports.
- Sell ranks its Frequent items filter by the number of receipts in the last 30 shop days that still retain each item. Fully returned items do not count toward that receipt's ranking.
- Stock → Manage → Variant groups individual catalogue items with a family and label. Each item keeps its own stock, price, SKU, barcode and photo. Family and label are searchable in Stock and Sell. Reasoned variant changes have revision history.
- Customers → Repayment follow-ups records local contact dates and dated completion or cancellation reasons. It shows the customer’s current debt. Scheduling and closing a follow-up leave money owed and drawer balances intact.
- Suppliers → Receipts & payments reviews returns against the original buying quantity and price. A saved return removes the exact stock quantity and reduces the amount owed on the linked receipt. Dated statements and exports record the credit separately from cash. Reimbursements and excess supplier credits remain pending.

An automated encrypted-file round trip restores the new records to another database and checks the resulting stock, drawer balance, sale receipt, customer debt, supplier debt and Frequent items ranking. Additional tests cover stale reviews, permissions, write rollback, missing history and mixed selling-unit ranking.

### Disconnected desktop checks

Used actual Linux Compose with sample records in an isolated 1440 × 1000 display and a loopback-only network namespace. Exercised typing, Tab/Enter and selected pointer actions.

- Submitted a short cash payment, corrected it to ₦15,000 for a ₦14,000 sale, and reopened the record. The successful checkout showed ₦1,000 change and the receipt retained both amounts. [Sale saved](artifacts/desktop-tender-change-saved.png), [saved receipt](artifacts/desktop-tender-change-receipt.png).
- Added a counter collection note and found it on the reopened receipt. The held-basket note recovery check was automated. [Receipt with note](artifacts/desktop-sale-note-receipt.png).
- Opened Frequent items and saw the malt drink from the sample sales. Assigned the sample soap to a family using the keyboard and found it in Sell using only the family name. [Frequent items](artifacts/desktop-frequent-items.png), [saved variant](artifacts/desktop-product-variant-saved.png), [variant search](artifacts/desktop-product-variant-search.png).
- Scheduled and completed a same-day repayment follow-up for the sample customer. Both the open and completed views showed ₦750 owed. [Due follow-up](artifacts/desktop-repayment-followup-due.png), [completed history](artifacts/desktop-repayment-followup-completed.png).
- Returned one bag of rice. Stock changed from 34 to 33 bags. The ₦10,000 credit reduced the receipt debt from ₦18,000 to ₦8,000. Exported a receipt-name CSV and a linked supplier account PDF through the native chooser and checked the totals in both files. [Review](artifacts/desktop-purchase-return-review.png), [saved return](artifacts/desktop-purchase-return-saved.png), [stock history](artifacts/desktop-purchase-return-stock.png), [statement](artifacts/desktop-purchase-return-statement.png).

Windows, physical peripherals, comprehensive keyboard and accessibility acceptance, replacement-machine recovery, and merchant usability testing remain pending. Cloud synchronization is outside the current scope.

## 17 November checkpoint — local trading controls

**555 tests pass**: 77 shared + 478 desktop, zero failures/errors/skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` using installed Gradle 8.13/JDK 17; successful in 1 minute 14 seconds. Linux image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 519-test checkpoint below. `git diff --check` passes.

- Minimum selling prices have reasoned immutable revisions, optimistic edit checks and tenant/permission boundaries. A base-unit floor also constrains packs; final line values include allocated basket discounts and fractional minimums round upward to kobo. Below-minimum checkout fails atomically. New sales retain policy checks, with a completeness marker for recovery; old sales are not judged by a later policy. Owner/manager settings exist; separate-identity approvals/overrides and margin/cost warnings remain pending.
- Repacking uses explicitly declared source/output quantities between two distinct catalogue products. Saved recipes retain revisions; whole batches require current-stock review and a reason. Both exact stock changes save in one transaction, appear in stock history and invalidate stale physical counts, including net-zero movement cases. There is no inferred equivalence, new selling-unit definition, cost allocation, sale or cash effect. Multi-input assembly, costing/yield-loss accounting and nested package definitions remain pending.
- Expense receipts copy real bounded PDF/PNG/JPEG bytes into local storage. Reasoned replacements/removals retain history; current and earlier files can be previewed/exported. Encrypted backups validate attachment content, ownership, chronology and revision/activity completeness. Files support up to 1 MB each with an 8 MB retained expense-evidence budget per shop. Expense amount/cash remains unchanged. Accrued/non-cash expenses remain pending.
- Owner money records classify actual drawer cash as contribution or drawing, with owner name, reason, current drawer/balance review, immutable cash source and CSV. An explicit equal opposite correction requires actual cash movement and preserves the original. These cash classifications are separate from sales/operating expenses; formal equity/loan accounts, journals and per-cashier custody remain pending.
- Advanced Stock actions now live under a labeled Stock tools menu, keeping Add item/Add stock and the existing five primary destinations. Backup table count bounds increase from 64 to 128 for the added modules; 64 MB file, 2 MB field and 200,000-row limits remain in force. Old supported backups remain recoverable.

### Actual native offline evidence — 17 November

The rebuilt installed Linux app ran in its isolated sample practice mode on Xvfb at 1440 × 1000, with a network namespace containing only loopback and no external routes. Keyboard text entry/Tab and selected pointer actions were performed; complete keyboard-only/accessibility testing and merchant studies were not performed.

- Set sample Malt minimum to ₦450/bottle, then negotiated ₦400 each on three bottles. Checkout rejected the ₦1,200 line against its ₦1,350 minimum instead of saving: [saved policy](artifacts/desktop-minimum-price-saved.png), [blocked payment](artifacts/desktop-minimum-price-blocked.png).
- Entered owner name, ₦2,000 and reason, reviewed the direction against ₦10,000 drawer cash, then saved: [review](artifacts/desktop-owner-contribution-review.png), [saved contribution](artifacts/desktop-owner-contribution-saved.png). Drawings/corrections are covered by automated integrity tests; their full native journey was not performed in this pass.
- Selected a clearly labeled sample receipt PNG through the native chooser, entered a reason, saved a copy and opened the saved revision preview: [saved expense receipt](artifacts/desktop-expense-receipt-saved.png), [preview](artifacts/desktop-expense-receipt-preview.png). The existing expense remained ₦1,250. Native overwrite/cancel/historical-removal journeys remain broader acceptance work.
- Created an explicit one-bag-to-five-kg sample recipe and saved two reviewed batches. Bag stock changed 34→32, loose stock 10→20 kg: [review](artifacts/desktop-repacking-review.png), [completed history](artifacts/desktop-repacking-saved.png), [stock result](artifacts/desktop-repacking-stock.png).

Automated coverage includes source isolation, permission denial, late-actor/injected-write rollback, stale/retry identities, exact measured/basket pricing, immutable histories, encrypted recovery and missing/tampered records. Full compact-layout, Windows/hardware, replacement-machine recovery, performance and merchant acceptance remain pending. Cloud synchronization and live provider/messaging services were excluded and are not implemented by this batch.

## Previous integrated checkpoint — shop and product images

**519 tests pass**: 72 shared + 447 desktop, zero failures/errors/skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with installed Gradle 8.13/JDK 17; successful in 34 seconds. Linux image rebuilt. This supersedes the 508-test checkpoint below.

- Product photo editor under Stock → Manage → Photo; separate shop logo editor under More → Shop & receipt details. PNG/JPEG inputs are decoded, resized with preserved proportions, and re-encoded to PNG, stripping source metadata. Source cap 8 MB/12 million pixels; stored cap 768 pixels/512 KB. Retained image assets have a 16 MB/shop budget, including historical versions. One active image per product and one shop logo are supported.
- Bytes are stored in tenant-scoped SQLite tables, independent of the original file. Reasoned image replacement/removal has immutable history, actor activity, stale-version checks and stable retries. Archived product edits are denied. Encrypted backups contain image bytes and history; legacy backups without images remain supported. Working SQLite remains unencrypted.
- Stock/Sell show fixed-size aspect-fit thumbnails with names and prices always available. Workspace shows the current shop logo. New sales capture their logo independently of later logo changes. A capture marker detects missing receipt-image records during restore. Clock rollback cannot capture a future-dated logo into a sale.
- Receipt PDFs render the captured logo on the first page only, preserving pagination and readable text on A4/58/80 mm. Existing receipts without a logo snapshot never acquire today's logo.
- Existing supplier invoice attachments already contained managed PDF/PNG/JPEG bytes and historical backup support; previous source-path-only documentation was incorrect. Added on-demand current/historical image preview. Increased the bounded backup field allowance to 2 MB so a supported 1 MB attachment fits after base64 encoding; the whole backup stays bounded at 64 MB.

### Image verification evidence

Seven image-integrity tests cover source-file deletion, normalization and invalid input, reopen, encrypted restore, shop isolation, permission denial, late-actor rollback, stale/replayed changes, removal/history, preserved receipt logos, omitted/corrupt snapshots and legacy backups. Three PDF tests verify first-page-only images, text clearance/pagination on all three papers, invalid-logo rejection before replacement and visual fixtures. One invoice regression exercises encrypted backup/restore of a valid image larger than 768 KB.

Actual native Linux Compose ran in the isolated practice workspace, Xvfb 1440 × 1000 and a network namespace with loopback only/no external route. Chose a clearly labeled SAMPLE SHOP test raster through the native picker, typed reasons and used Tab/Enter to save. Inspected uploaded product image in [Stock](artifacts/desktop-product-image-stock.png) and [Sell](artifacts/desktop-product-image-sell.png), and saved shop logo in [settings/sidebar](artifacts/desktop-shop-logo-saved.png). Test raster use proves import/display paths, not realistic catalogue photography or merchant comprehension.

Rendered actual generated receipt PDFs with Poppler and inspected all widths: [A4](artifacts/pdf-receipt-logo-a4.png), [58 mm](artifacts/pdf-receipt-logo-58mm.png), [80 mm](artifacts/pdf-receipt-logo-80mm.png). No physical printer output was tested. Native invoice preview, full save/remove/cancel/failure journeys, Windows, accessibility and merchant research remain unperformed. EXIF orientation is stripped without automatic rotation; cropping/rotation controls, galleries, expense receipt attachments and cloud media transport remain pending. The already-open trading app must be reopened to load the rebuilt version; its data/process was not interrupted for testing.

## Previous 508-test checkpoint

**508 tests pass**: 72 shared and 436 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with installed Gradle 8.13 and JDK 17; successful in 38 seconds. Linux image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 424-test checkpoint below. `git diff --check` passes.

Four specialist agents reviewed quotation persistence/output/UI, external-payment records, credit-return reconciliation and bulk catalogue updates. Root integrated schemas/backups, permission routes, report semantics and recovered drafts. Small commits separate domain, persistence, output, UI and fixes.

### Local scope delivered in this continuation

- Manual transfer/card records retain reference and receiving account/terminal, with explicit **not confirmed** status. Checkout supports one external portion plus cash and optional named-customer credit. External money is excluded from drawer cash. Drafts, receipts, search and CSV preserve it. Provider verification, settlement, reversals and external-method refunds remain unavailable.
- Credit-sale returns reduce unpaid receipt debt first, then refund paid value in cash. Existing repayments must be assigned; reviewed funding changes invalidate the return. Assignment reversal cannot release already-refunded funding. Customer history, dated statements, ageing and product/staff reports distinguish debt reductions, returned goods and actual cash. Original sale/return history remains immutable.
- Quotations capture goods, pack meanings, exact prices, contacts, notes and expiry in immutable revisions. Issue/accept/cancel do not move goods or money. Reviewed full-cash conversion checks current goods, pack identity, stock, expiry and price bounds and records one linked sale atomically. Credit/electronic/order conversion and reservations remain unavailable.
- Bulk CSV updates review existing goods, display row errors and before/after values, reject stale versions and apply the full batch atomically with a reason/history. Reviewed SKU/barcode swaps are supported. Stock and completed receipts are unchanged. The earlier add-only new-product importer remains separate.
- Versioned retail/wholesale/customer quantity price lists require explicit checkout selection; prices may match or reduce normal prices. Supplier invoice metadata includes normalized duplicate checks, reasoned corrections and managed bounded PDF/PNG/JPEG evidence and historical attachment backups (corrected by source audit).
- Opt-in scheduled encrypted local backups retain versions with guarded pruning and bounded session passwords. The app must be running with an authorized session and supplied password. File/password checks do not establish successful recovery on another machine. Working SQLite storage remains unencrypted.

### Actual rendered disconnected checks — continuation

Linux Compose ran with sample data in Xvfb at 1440 × 1000 inside a user/network namespace with loopback only and no external route. Keyboard typing, Tab and selected pointer actions were exercised. These are targeted checks, not a full keyboard-only/accessibility study or merchant research.

- Saved a ₦14,000 sale as a manually recorded ₦10,000 transfer plus ₦4,000 cash; completion explicitly says external payment **not confirmed**: [payment form](artifacts/desktop-manual-transfer-split-preview.png), [saved result](artifacts/desktop-manual-transfer-split-saved.png). This is sample bookkeeping, not a bank integration result.
- Returned two sample bottles worth ₦1,000 from a part-credit receipt. Review split ₦750 debt reduction / ₦250 cash refund; saved result preserved original history: [review](artifacts/desktop-credit-return-split-preview.png), [saved return](artifacts/desktop-credit-return-saved.png).
- Issued two bottles at an agreed ₦450 each, recorded acceptance, reviewed ₦900 full cash, and converted to one linked sale. Stock changed from 237 to 235 bottles: [issued quotation](artifacts/desktop-quotation-issued.png), [conversion review](artifacts/desktop-quotation-cash-review.png), [converted](artifacts/desktop-quotation-converted.png). Native quotation PDF save/cancel/overwrite was not exercised; output is covered by automated PDF tests.
- Exported a real sample catalogue CSV through the native chooser, edited it under `/tmp`, reopened it through the chooser, reviewed one changed item among six, entered a reason, confirmed and saved. Soap changed ₦450→₦475 with SKU/category/favourite while remaining 96 pieces: [review](artifacts/desktop-bulk-catalogue-preview.png), [saved batch](artifacts/desktop-bulk-catalogue-saved.png), [Stock favourite](artifacts/desktop-bulk-catalogue-favourite.png).

New automated checks include tenant isolation, rollback, immutable history, stale/retry identity, backup tampering, funding chronology, shared pack/base stock, recovered external drafts and JVM screen loading. Broader native coverage for backup scheduling, price-list setup, supplier invoice corrections, all changed failure/keyboard paths and compact layouts remains pending. Windows packaging, real printers/scanners/drawers, merchant comprehension, independent-machine restore and online services remain unperformed.

### Retained earlier targeted evidence

The earlier percentage-item and dated-statement checks are also retained: [percentage preview](artifacts/desktop-percentage-preview.png), [saved cash sale](artifacts/desktop-percentage-sale.png), [net refund preview](artifacts/desktop-percentage-refund-preview.png), [saved refund](artifacts/desktop-percentage-refund-saved.png), [dated customer statement](artifacts/desktop-customer-dated-statement.png). These sample checks predate this continuation and do not establish broader acceptance.

## Previous 424-test integrated checkpoint

**424 tests pass**: 56 shared and 368 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` using installed Gradle 8.13 and JDK 17; successful in 27 seconds. The rebuilt Linux application image is at `desktopApp/build/compose/binaries/main/app/RetailPOS`. This supersedes the 389-test first-wave and 324-test earlier checkpoints. Windows packaging and hardware/merchant acceptance remain pending.

## New local functionality — 14 November 2026

- Fixed/percentage basket discounts use integer allocation and preserved net-paid refunds. Restored/changed baskets require explicit review; invalid changes block payment. Supplier profiles retain contact/terms history and explicit stable-account links; legacy name-only receipts remain separate.
- Multi-item counts save unfinished drafts and post all corrections atomically. Any intervening movement, including a net-zero sale/return, requires a new count. This is a conservative cutoff, not continuous count reconciliation.
- Versioned shop receipt settings are captured on new sales. PDF receipts support A4/58/80 mm; customer/supplier statements export reconciled local records to A4 PDF with account/period continuation headers. Native PDF save dialogs and real printers still need broader platform testing.
- Code128 labels use saved base/selling-pack barcodes, integer module widths and quiet zones. PNG export and print submission are implemented; other symbologies, physical scanner readability and printer output remain unverified.
- Purchase orders support editable drafts, approval, cancellation, explicit partial deliveries, immutable history and CSV exports. Only delivery creates a stock receipt and actual cash/debt effects. Estimated ordered cost can differ from the sum of individually rounded deliveries. Landed costs, supplier credits and damaged-delivery handling remain pending.
- Reasoned stock removals preserve exact before/after quantities, category, actor and activity history. They change physical stock only; accounting valuation and expense/COGS recognition remain pending.

### Rendered evidence for this batch

Actual Linux Compose checks used the installed app in an isolated practice process, Xvfb at 1440 × 1000 and a network namespace with loopback only. Keyboard typing and selected pointer actions were exercised; full keyboard-only/accessibility and merchant comprehension studies were not performed.

- Entered a 12.5% basket discount on ₦14,000, showing ₦1,750 reduction and ₦12,250 due. Increasing a quantity then disabled payment and requested another review: [applied discount](artifacts/desktop-basket-percentage-applied.png), [blocked changed basket](artifacts/desktop-basket-review-required.png).
- Saved and posted two physical counts, 94 pieces from 96 and 9.875 kg from 10 kg, with the reason and differences preserved: [posting confirmation](artifacts/desktop-stock-count-review.png), [posted result](artifacts/desktop-stock-count-posted.png).
- Inspected stable supplier details and ₦18,000 owed on its explicitly linked ₦20,000 purchase: [supplier account](artifacts/desktop-supplier-profile-detail.png).
- Edited shop name/footer, entered a reason, reached Save through scrolling and saved the form; inspected the saved fields: [receipt settings](artifacts/desktop-shop-settings-saved.png). Historical branding retention is covered by repository tests, not an additional native end-to-end sale in this pass.
- Inspected the rendered Code128 preview and a generated PNG: [label screen](artifacts/desktop-label-options.png), [label file](artifacts/barcode-label-proof.png). No physical print/scan was performed.
- Created and approved a 10-piece purchase order at ₦300 each, then entered a partial delivery of 4 pieces with explicit ₦0 cash using keyboard Tab between fields. The saved order shows 4 received and 6 remaining: [approved order](artifacts/desktop-purchase-order-approved.png), [delivery preview](artifacts/desktop-purchase-order-partial-preview.png), [saved delivery](artifacts/desktop-purchase-order-partial-saved.png).
- Recorded 2 damaged pieces from 96 with a reason, reviewed the 94-piece remainder and saved immutable history: [review](artifacts/desktop-stock-removal-review.png), [saved removal](artifacts/desktop-stock-removal-saved.png).
- Poppler rendering and text extraction verified the receipt and statement PDFs, readable naira glyphs, wrapping, page identity and reconciliation. Customer debt ₦100−₦32.10=₦67.90; supplier ₦1,481.40−₦281.40−₦32.10=₦1,167.90. [Receipt](artifacts/pdf-branded-receipt.png), [customer statement](artifacts/pdf-customer-statement.png), [supplier first page](artifacts/pdf-supplier-statement.png), [supplier final page](artifacts/pdf-supplier-statement-last-page.png).

## Previous integrated checkpoint

- **324 automated tests pass**: 35 shared and 289 desktop, zero failures, errors or skips. Ran `gradle :shared:jvmTest :desktopApp:jvmTest :desktopApp:createDistributable --offline` with the installed Gradle 8.13 and JDK 17; the final run completed successfully in 24 seconds. The wrapper specifies a different distribution and was not used for this checkpoint.
- Linux application image rebuilt at `desktopApp/build/compose/binaries/main/app/RetailPOS`. Windows packaging and hardware compatibility remain unverified.
- The previous baseline was 287 tests. Added eight fractional-quantity integrity/adversarial tests, eleven cash-count domain/repository tests, seven help/launcher tests, and eleven staff-PIN security tests. Existing financial and migration tests continue to pass.
- Five specialist agents worked in waves: current-feature audit, fractional integration, backlog reconciliation, cash counting, and help/practice. The audit agent subsequently implemented staff PINs. Small commits separate planning, domain, storage, exports, UI, security, help and corrections. The complete remaining scope is still tracked in IMPLEMENTATION_TODO.md and IMPLEMENTATION_PLAN.md.

### Completed local implementation

- Product precision is chosen at creation (0–6 decimal places) and remains fixed. Stock arithmetic uses scaled integers; transactions retain their precision and conversion snapshots. Measured checkout, receiving, single-item counts, cash returns, CSV import/export, reports and backup/restore are connected. Pack counts remain whole. Transaction quantities have a maximum of `Int.MAX_VALUE` scaled increments; stored stock enforces supported exact bounds and round-trip projection limits for legacy display/opening-history fields. Nested conversions/repacking remain pending.
- Refunds use cumulative rounded quantities, so repeated partial returns sum to the original saved line amount. Invalid/pending quantity edits block payment and require Apply quantity; entering an intermediate zero cannot silently remove a line. Older fractional opening balances without a precision policy remain displayed, with only their whole portion available for whole-unit selling.
- Cash opening/closing can store canonical note/coin sheets in the same transaction as the shift. Replays cannot overwrite counts. Backups retain and validate sheets; legacy backups without them remain supported. Closing can hide expected cash in its form, with an explicit explanation that Today's cash may already have displayed it. Register handover and stronger blind-accountability policies remain pending.
- Staff can configure/remove a six-digit local PIN using their full password. Password/PIN attempts share the persisted five-failure/60-second cooldown. PIN authority expires after 12 hours without renewal by PIN; PIN sessions also enforce absolute expiry, account version/generation and clock rollback. Full-password sign-in is the fallback and can renew authority. Staff account changes/password reset remove PIN access. Owner sign-in remains password-based. PIN credentials stay outside business backups.
- More → Help & practice provides bundled searchable task guidance. Practice launches a separate sample process, waits for its first rendered frame, suppresses duplicate launches and closes the child on cleanup. The installed `--practice` entry uses an in-memory business database and temporary draft/security files, without opening the trading database.

### Actual rendered and offline checks

Native Compose screens were reviewed on Linux/Xvfb at 1440 × 1000, using isolated sample records inside a Linux user/network namespace with only loopback and no external routes. These checks used keyboard text entry and selected Tab/Return actions plus pointer navigation; they are not a complete keyboard-only or accessibility assessment.

- Typed `0.375` into a measured basket line: the original line stayed present and payment was disabled until Apply quantity. Tab/Return applied it as 0.375 kg and ₦375.00. See [pending entry](artifacts/desktop-fractional-pending.png) and [applied entry](artifacts/desktop-fractional-applied.png).
- Increased to 1.375 kg, applied 12.5% off a ₦1,000/kg price and saved the cash sale for **₦1,203.13**, giving **₦796.87** change from ₦2,000. See [discount](artifacts/desktop-fractional-discount.png) and [saved sale](artifacts/desktop-fractional-sale-saved.png).
- In a separate fresh fixture, saved a 1.375 kg cash sale, returned/restocked 0.375 kg for ₦375, and inspected the preserved receipt/return and resulting **9 kg** stock from an opening 10 kg. See [return preview](artifacts/desktop-fractional-return-preview.png), [saved return](artifacts/desktop-fractional-return-saved.png), and [stock](artifacts/desktop-fractional-stock-after-return.png).
- Entered nine ₦1,000 notes and saved a closing count of **₦9,000** against expected **₦10,000**. Past shifts retained the count-sheet link, expected-hidden method and **₦1,000 shortage**. See [saved count](artifacts/desktop-cash-denominations-saved.png). Working screenshots exposed misleading empty-row subtotals; these now show zero while the overall blank sheet still requires an explicit count.
- Rendered bundled Help without internet. A separate real-process smoke check invoked the rebuilt Linux installed launcher through PracticeLauncher, received first-frame readiness, confirmed duplicate-launch suppression and verified that closing the launcher left no child process alive. See [help](artifacts/desktop-help-offline.png) and [installed practice](artifacts/desktop-installed-practice.png). This checks the actual launcher, not an end-to-end click from a merchant's production shop.
- In the isolated staff preview, locked the owner workspace, selected Sample cashier, configured a PIN using its full password and unlocked with that PIN. The cashier retained Sell/Sales and More → Help without restricted tools. Then clicked Open practice shop in Help and observed the separate sample window. See [PIN setup](artifacts/desktop-pin-setup.png), [cashier unlocked](artifacts/desktop-pin-cashier-unlocked.png), [cashier tools](artifacts/desktop-cashier-help-tools.png) and [practice from Help](artifacts/desktop-cashier-practice-launch.png). Namespace child JVMs used consistent `user.home`/`user.name` properties for native-library extraction; an initial mismatched test harness correctly surfaced Practice unavailable, then the corrected harness completed the launch. No real shop or provider was used.
- Typed an unmatched Help query and inspected No matching help, then replaced it with `carton` and expanded the stock-unit guidance. See [empty search](artifacts/desktop-help-empty.png) and [filtered guidance](artifacts/desktop-help-search.png). Rerendered the denomination form after the subtotal correction and saved its count again; unused rows now display ₦0.00. See [count entry](artifacts/desktop-cash-denominations-entered.png).

Service tests additionally cover exact decimal subtraction, precision-six sales, over-selling, retry deduplication, corrupted precision/stock/sheet restore rollback, legacy migration, encrypted backup round-trips, fixed PIN expiry, shared cooldowns, cross-instance revocation and secret-free staff history. Merchant studies, Windows, physical peripherals, full screen-reader/touch/keyboard journeys, independent-machine recovery, large-catalogue profiling and external integrations remain unperformed or incomplete.

The dated sections below are historical checkpoints, not claims that their older test totals or then-pending feature lists describe this latest build.

## Verified

- Shared and desktop compilation with Kotlin 2.2.20, Compose 1.9.1 and JDK 17.
- **241 automated tests pass**, with zero failures or errors. The negotiated-price checkpoint added 13 tests to the previous 170-test checkpoint; six ageing-band tests brought the total to 189. Fifteen correction/domain/export tests bring the total to 204.
- Coverage includes cross-tenant rejection, exact kobo amounts, retry deduplication, return limits/restocking, supplier overpayment, atomic import rejection, WAT date boundaries, spreadsheet formula protection, encrypted tenant-only backup, wrong passwords, corrupted input, repeated restore and cash/debt-history consistency.
- Linux desktop distribution rebuilt successfully using `gradle :desktopApp:createDistributable` at `desktopApp/build/compose/binaries/main/app/RetailPOS`.
- Native isolated previews rendered for Reports, Sales, Suppliers, Backup and Import. Reports filled the 1920 × 1080 display; Sales and Suppliers were also inspected in the compositor-provided narrower window. See [Reports](artifacts/desktop-reports.png), [Sales](artifacts/desktop-sales.png), and [Suppliers](artifacts/desktop-suppliers.png), [Backup](artifacts/desktop-backup.png), and [Import](artifacts/desktop-import.png).
- Previous fullscreen verification remains recorded in [the fullscreen render](artifacts/desktop-fullscreen.png). Startup requests true fullscreen with F11 and visible window controls. Window-manager behavior still needs Windows and multi-monitor verification.

## Login throttling and inactivity locking — 13 November 2026

- Five unsuccessful sign-in attempts pause that shop/account for 60 seconds. Correct passwords cannot bypass the pause. Attempts persist across staff-service instances and restarts, and owner/staff identities are counted separately.
- Genuine sessions expire after 15 minutes without authorized activity. Key press, mouse press and wheel activity in this app refreshes the session at most once per second; no entered content is read or retained. The 15-second background check validates without extending the timer.
- Expired/revoked sessions cannot be revived. Idle session checks and activity use the same validated clock reading; backward clock changes fail closed.
- Eleven additional clock-driven tests cover exact boundaries, polling/activity, denied permissions, forged/expired/revoked tokens, cooldown scope/persistence/reopen, clock rollback and timestamp reuse. All 231 integrated tests pass and the Linux package was rebuilt.
- Native staff/access-dialog and compact-form renders were inspected after adding the event listener: [access dialog](artifacts/desktop-staff-access-dialog.png), [compact form](artifacts/desktop-staff-form-compact.png). A controlled native idle-to-lock transition and full minute-long cooldown were not confirmed in this pass; exact service behavior is covered by the automated clock tests. Do not treat a timer screenshot filename as proof of a transition.
- Preview-only POS_PREVIEW_IDLE_MS can shorten the isolated fixture's timeout. The installed app retains the 15-minute default. PINs, owner credential recovery, actor attribution, approval workflows and a broader security review remain unfinished.

## Local staff identities and permissions — 13 November 2026

- A complete remaining-feature checklist is now kept in IMPLEMENTATION_TODO.md. Only verified subtasks are checked; the long-term product remains unfinished.
- Owner-managed local staff accounts use separate salted PBKDF2 password credentials. Owners add cashier/manager accounts, rename/change roles, disable/enable and reset passwords. Staff accounts and credentials are excluded from business backups.
- Cashiers can make cash sales and view/print/export individual receipts. Managers have operational stock/customer/credit/discount/refund/money/report/supplier access. Staff management and encrypted shop backups are owner-only.
- The app installs a genuine-session validator on repository mutations, including successful-operation replays. Credit and discounted checkout require additional permissions. Credential/role/active changes invalidate staff sessions across instances through version checks; the shell rechecks sessions every 15 seconds and on navigation.
- Restore from the locked picker uses an explicit trusted recovery connection and still requires backup decryption plus new-owner credential provisioning. It returns to owner sign-in. Working business DB encryption, staff PINs, actor attribution and approval flows remain pending.
- Sixteen additional tests cover role policy, denied mutations leaving whole snapshots unchanged, genuine credentials, forged sessions, revocation/reset, cross-instance persistence, safe create retries, backup exclusion and JVM class loading. All 220 integrated tests pass; Linux distributable rebuilt.
- Native isolated preview: inspected owner staff management, locked the workspace, selected Sample cashier, entered its password and unlocked the restricted Sell/Sales workspace. Evidence: [management](artifacts/desktop-staff-management.png), [sign-in](artifacts/desktop-staff-sign-in.png), [cashier workspace](artifacts/desktop-cashier-workspace.png).
- A runtime bytecode/class-loading problem in the first screen structure was corrected and is covered by a regression test. Native cashier payment completion, full staff creation/reset dialogs, manager sign-in, restored-backup sign-in and automatic revocation polling were not exercised end-to-end in this visual pass; their service/repository paths have automated coverage.
- Credentials enforce local application access only. They do not establish server-side membership or protect records from OS/filesystem access.

## Correcting payment assignments — 13 November 2026

- Customers → View history → Assign payments to receipts now retains each original assignment and offers Undo assignment with a required reason. Undone history shows its correction time and reason.
- Undo releases the assigned amount on both the payment and receipt. Reassign it explicitly through the existing form. It records no new payment, refund, stock movement or customer-debt change.
- Ageing uses active assignments only; released money remains unassigned and exact ageing is unavailable until reassigned. Retrying an old assignment cannot reactivate an undone record.
- Reports → Payment assignments exports original assignment dates with current Active/Undone status and correction details. Assignment corrections exports events by correction date, even when the original assignment is older.
- Backups preserve original assignments and undo events, and older backups without corrections remain supported. Staff correction permissions, cash repayment reversals and historical as-of reporting remain separate work.
- Fifteen additional tests cover correction reason validation, effective caps, reassignment, replay safety, competing undo requests, tenant boundaries, ageing changes, corrupt backup rollback, actual old-database upgrade/reopen and formula-safe correction CSV.
- Native isolated preview: undid the ₦250 sample assignment, confirmed debt stayed ₦750 and unassigned funds became ₦250, inspected correction history, then explicitly assigned that payment again. Original Undone and new Active rows remained together; available payment returned to ₦0. Evidence: [confirmation](artifacts/desktop-assignment-undo-confirm.png), [released amount](artifacts/desktop-assignment-undone.png), [history](artifacts/desktop-assignment-correction-history.png), [reassigned](artifacts/desktop-assignment-reassigned.png).
- Linux distributable rebuilt with the integrated checks. CSV formatters are tested; their native save dialogs were not exercised in this correction pass. No merchant or physical peripheral study was performed.

## Debt-ageing bands — 13 November 2026

- Credit review now groups current receipt balances into due today/later, 1–7, 8–30, 31–60, 61–90 and over 90 days late, with a separate no-agreed-date group.
- Groups use explicitly assigned repayments. Exact amounts remain unavailable while payments are unassigned; no FIFO or inferred due dates are introduced.
- Export all customer ageing creates spreadsheet-safe CSV with classification date, current-balance basis, unassigned amounts and all bands. Unknown amounts are blank, not zero. Export includes all reviewed customers regardless of the on-screen filter.
- Six tests verify every boundary, leap-day calendar arithmetic, partial/fully settled receipt bands, reconciliation, unknown values, tenant restrictions, duplicate rows and relabeled export dates.
- Native isolated preview inspected the rendered rows: Amina's ₦750 current debt appeared entirely under Due today or later, with zero unassigned payments and zero overdue. [Screenshot](artifacts/desktop-debt-ageing.png). The export formatter is tested; the OS save dialog was not exercised in this pass.
- Current balances are classified using the review date; this is not historical accounting as of that date. Reasoned allocation undo and reassignment are implemented in the correction section below; staff approval limits, write-offs and reminders remain pending.

## Negotiated unit prices — 13 November 2026

- Each basket line offers Change price with the original unit price, agreed lower price and required reason. Discounts use integer kobo per sold unit; quantities/pack factors stay unchanged. Full discounts are explicit and reasoned; markups, basket/percentage discounts, minimum margins and staff approval limits remain pending.
- Receipts retain catalogue price, reduction, reason and price paid. Cash returns use the net unit price, including partial pack/loose returns; original transactions stay intact. Sales CSV includes original subtotal and discount totals.
- Drafts preserve discount values and reasons through restart/hold/resume. If the current catalogue price differs from the saved reference price, payment is blocked until the discount is reviewed or removed. Old backups/drafts default to no discount.
- Automated checks cover net credit limits, overflow, unchanged catalogue/stock conversion, partial refunds, tenant isolation, duplicate retries, malformed backups/drafts and older backup defaults.
- Native isolated preview: changed 3 Malt bottles from ₦500 to ₦450 each, entered a reason, observed ₦150 discount and ₦13,850 basket total, paid exact cash and reopened the saved receipt. Evidence: [price entry](artifacts/desktop-discount-price.png), [basket](artifacts/desktop-discount-basket.png), [saved sale](artifacts/desktop-discount-saved.png), [receipt](artifacts/desktop-discount-receipt.png).
- Linux distributable rebuilt successfully. Merchant research, physical printer/scanner checks and staff authorization remain outstanding.

## Selling packs and loose units — 13 November 2026

- Stock → Manage → Selling units defines immutable pack names, base conversion, whole-pack prices and optional barcodes. Archive/create replaces a definition; archived definitions remain available to historical records.
- Sell offers a labeled selling-unit choice. Mixed carton/loose lines share one base stock pool; quantity controls and repository checks aggregate their stock requirement. Pack barcodes use the existing barcode + Enter flow.
- Held/active drafts keep distinct pack keys; unavailable archived units block payment until removed/restored. Resumed drafts use current catalogue prices.
- Historical receipts, customer history, cash-sale refunds and optional restock preserve sold units/prices/conversions. Backups include definitions and snapshots; older backups/migrations default to base units.
- Automated checks verify 1 carton × 24 plus 3 bottles deducts 27 bottles; original pack refunds, mixed overselling rollback, tenant/barcode isolation, replay safety, migration/restore and overflow.
- Linux distributable rebuilt successfully. Native checkout render (unit-choice layout inspected; the full checkout interaction was not completed in this visual pass): [selling-unit choice](artifacts/desktop-pack-checkout.png). Physical scanner/printer and merchant usability remain unverified.

## Latest inventory additions

- Stock → Manage → History shows opening counts where recorded, purchases in converted base units, sales, restocked returns and count corrections, with source references and timestamps. Damaged/non-restocked returns add no stock movement.
- New items preserve their opening quantity and recorded time. Older products and old backup rows keep these fields unset; the UI does not invent an opening count or running balance.
- Optional reorder levels live under item Details. Blank disables the reminder; zero means out-of-stock only. Active goods at or below their threshold appear in Low stock; archived goods are excluded.
- Threshold changes preserve setting history and retry protection without changing stock or cash. Backups preserve settings and opening metadata and reject invalid ranges or incomplete opening records.
- Reports exports current low-stock snapshots and dated source movements. Product exports distinguish unknown opening quantities from zero and include reorder levels.
- History renders the newest 100 movements first, with a labeled option to show more. Large-history database profiling remains outstanding.
- Tests include receiving 2 cartons as 48 bottles, sale/restock/damaged return/count corrections, duplicate retries, tenant boundaries, actual old-database migration and backup round trips.
- Native isolated Stock screen and History tab opened and inspected: [stock alerts](artifacts/desktop-stock-alerts.png), [low-stock filter](artifacts/desktop-low-stock-filter.png), [source movements](artifacts/desktop-inventory-movements.png). Sample history shows 240 opening bottles, a source-linked sale of 3 and current stock 237. Full threshold-edit interaction, keyboard-only journeys and merchant testing remain outstanding.
- This is source history, not costing, inventory valuation or guaranteed running-balance reconstruction of legacy/fractional records.

## Previous repayment-allocation additions

- Assign already-recorded customer repayments to specific credit receipts. One payment may cover several receipts and a receipt may receive several payments, within both source limits.
- Immutable links preserve the customer, source payment, source receipt, amount and timestamp. Retried assignments do not duplicate coverage.
- Assignment never changes total customer debt, cash, stock or the original sale. Older payments stay unassigned until explicitly selected; no FIFO allocation is guessed.
- Credit review shows remaining receipt balances and exact recorded-date overdue debt only when the customer's repayments are fully assigned. With unassigned funds it explains why an exact overdue total is unavailable; undated credit is excluded from dated overdue totals.
- Reports exports assignment history as a spreadsheet-safe CSV with both source references and exact amounts. Backups preserve assignments and reject invalid cross-customer references or over-assignment on restore.
- The allocation-aware credit review was rendered and inspected using isolated sample data: [assigned credit review](artifacts/desktop-credit-assigned-review.png). The contextual [assignment screen](artifacts/desktop-payment-assignments.png) was also opened and inspected with the sample payment fully assigned. Creating an assignment through the form and merchant testing remain unverified.
- The later correction workflow now appends reasoned undo events and permits explicit reassignment; original saved links stay immutable. Staff correction approvals remain pending.

## Previous customer-credit additions

- Customer phone/address editing with Nigerian-format phone normalization, clear unlimited/zero credit-limit states and immutable before/after edit history.
- New customer debt is checked against the current stored limit inside the sale transaction. Lowering a limit does not write off existing debt; cash sales and repayments remain available.
- Optional agreed due dates on new credit sales use the WAT calendar; malformed or past dates reject the new transaction. Existing/historical dates remain valid records on restore.
- Active and held baskets retain due-date text, including unfinished input. Receipts, sale/customer CSVs and statements preserve the saved due date.
- More → Review customer credit separates past-due original sale dates, today/future/undated sales, current all-date customer debt and total repayments. It does not invent payment allocations or an exact overdue invoice balance.
- Legacy database and backup migrations retain old balances and leave absent contact/limit/date fields unset. Profile credentials and online membership remain outside this workflow.
- Native isolated preview renders inspected for the [customer credit editor](artifacts/desktop-customer-credit-editor.png) and [credit review](artifacts/desktop-credit-review.png). No complete end-to-end interaction or merchant study is claimed.

## Previous buying-package additions

- Define merchant-specific buying units under Stock → Manage → Buying units, for example 1 carton = 24 bottles. Names/factors are immutable; archive and create a new definition when package sizes change.
- Receive stock in a chosen buying unit, with quantity and cost labeled per carton/pack and an explicit base-stock preview before saving.
- Record both purchased quantity and base quantity added on historical receipts, supplier views and CSV exports. The base selling unit and ordinary checkout remain unchanged.
- Stock precision, stale conversion, tenant/product ownership, retry and archival checks run in one transaction. A failure leaves stock, cash, supplier receipt and outbox unchanged.
- Additive database migrations and legacy-backup defaults preserve old one-to-one receiving records. Encrypted backups now include buying definitions and receipt conversion snapshots.
- The independent integration test receives 2 cartons × 24 bottles, sells 3 bottles and verifies 45 remain, together with exact drawer cash and supplier debt.
- Product CSV exports now include archived goods and their archive flag; this is labeled on the Reports screen.
- Native preview check: opened Buying units, selected carton, entered 2 cartons at ₦10,000 each with ₦0 paid, and saved the receipt. Malt stock increased from 237 to 285 bottles, with a ₦20,000 purchase/credit preview. See [buying units](artifacts/desktop-buying-units.png), [conversion](artifacts/desktop-carton-receiving.png), [cost preview](artifacts/desktop-carton-cost-preview.png), and [saved stock](artifacts/desktop-carton-stock-saved.png).
- Whole-package selling with independent prices/barcodes is now implemented below. Fractional checkout and repacking remain pending.

## Previous daily-shop additions

- Barcode assignment and exact barcode + Enter lookup at checkout; barcodes remain unique within each shop, including archived items.
- Product name/price/barcode editing, archive/restore and immutable price history. Completed receipts retain their original name, price and unit.
- Reasoned single-item stock counts with previous/count/difference history, retry deduplication and rejection of stale count forms.
- Searchable customer history, original sale/credit and later repayment separation, plus reconciled spreadsheet-safe CSV statements.
- Multiple named held sales, atomic active/held transitions, restart recovery of tender/customer/method, tenant isolation and preservation of other baskets after checkout.
- Legacy database and draft migration coverage; incomplete or corrupt draft files are preserved rather than reset. Backups retain the new catalogue and stock-correction histories and accept older product rows.
- Rendered catalogue, item details and count views inspected: [catalogue](artifacts/desktop-catalogue.png), [details](artifacts/desktop-product-details.png), [count](artifacts/desktop-stock-count.png). Customer history was opened in the native preview and inspected: [history](artifacts/desktop-customer-history.png).
- The native isolated Sell preview was used to put a named basket aside and open Saved sales. See [held baskets](artifacts/desktop-held-sales.png). Hold/resume durability is covered by automated tests; the native resume outcome was not conclusively captured. Keyboard entry of the sample barcode plus Enter added exactly one bottle, changing the basket from 3 to 4 bottles and the total from ₦14,000 to ₦14,500: [barcode check](artifacts/desktop-barcode-checkout.png). These are targeted manual checks, not a complete end-to-end or merchant study.
- Material overlay container roles now map to the existing neutral surface tokens, preventing default purple dialogs.

## Previously added local workflows

- Searchable saved sales, receipt detail with WAT dates, text receipt export, and OS print submission with pagination.
- Original cash-sale item returns, remaining returnable quantities, reason and explicit sellable-restock choice; linked cash refunds preserve the original sale.
- Supplier balances by stock receipt, later cash payments and payment history.
- WAT date-range sales/cash/expense/purchase/refund reports, with current customer debt labeled separately. Profit is unavailable until historical COGS exists.
- Spreadsheet-safe CSV exports for sales, products, customers, expenses, cash activity and stock receipts.
- CSV product template, parsed preview and all-or-nothing add-only import. Duplicate items reject the complete batch, including repeated imports.
- Password-encrypted selected-shop backup files and restore into a new shop with a new owner password. Original shops remain intact; credentials and unfinished baskets are excluded.
- Restore validation checks integer quantity bounds, customer debt against saved history and source-backed cash entries before creating the recovered workspace.

## Checks still required

- Full UI interaction journeys, error-state checks, keyboard-only and accessibility testing. Rendered inspections do not establish these.
- Real thermal printing, scanner/peripheral use, Windows installers/display scaling and independent-machine recovery drills.
- Merchant first-glance and practical task studies; none have been conducted.
- Large-catalogue performance and background execution for longer imports/backups; local operations currently run synchronously.
- External services: no bank/card verification, remote authentication, background cloud sync or Heroku deployment exists.

## Multi-tenant boundary and remaining scope

Local business queries and mutations are tenant-scoped. Composite relationships reject cross-shop financial references, each owner gate has shop-specific credentials, and restore creates another tenant rather than overwriting a shop.

This remains a trusted-device owner application. The working SQLite database is unencrypted. Local owner gates are not a network authorization boundary. Production online operation requires authenticated identities, tenant memberships, server-side authorization, roles, device enrollment and a reviewed synchronization protocol.

See [the full implementation audit](IMPLEMENTATION_STATUS.md) for coverage of every specification section. Staff roles, fractional sales, catalogue categories/variants and bulk edits, credit returns/exchanges, advanced stock/accounting, online services and mobile apps remain among the material outstanding work. The long-term feature specification is not complete.

## Activity history and actor attribution — 13 November 2026

- New repository business events save a frozen staff ID/name/role with their source/outbox event inside the same transaction. Missing/foreign identities or failed audit writes roll back the business action. Retries retain the original actor and do not append duplicate activity. Trusted tooling/old records remain explicitly unattributed; no history is backfilled.
- Business activity and staff-change tables reject SQL updates/deletes. This is application/database integrity, not protection from workstation administrators or filesystem access.
- More → Activity history shows business events, source IDs and WAT dates, with search, refresh, empty/error states and owner-only Staff access history. Existing guide/reference lock and semantic theme are retained. Merchant-facing action names replace raw domain names.
- Reports → Activity history exports date-filtered, spreadsheet-safe CSV. Encrypted business backups retain business-event identities and accept legacy backups without this table; malformed or mismatched events reject the entire restore. Staff security history remains on the original computer and excludes credentials/tokens.
- Ten additional tests (six business/backup/export tests and four staff-audit tests) bring the integrated total to 241, with zero failures/errors/skips. Includes identity snapshots, retry safety, transactional rollback, cross-shop/read restrictions, reopen, update/delete rejection, encrypted recovery, corrupt restore rejection, legacy backups and CSV formula protection. Desktop class-loading coverage includes the activity screen. Linux distribution rebuilt using offline Gradle dependency resolution.
- Actual native isolated 1920 × 1080 business-history render inspected: [activity history](artifacts/desktop-activity-history.png). Attributed and unattributed events and the owner-only tab were visible. Keyboard-event injection did not produce a conclusive visible transition; keyboard search/tab interaction and the staff-history view were not confirmed in this pass. No network-disconnection drill, merchant study, Windows or physical-peripheral test was performed. Service tests exercise local SQLite without external services; offline Gradle mode is not evidence of disabling the machine's network.

## Historical credit and performance reports — 13 November 2026

- Review customer credit now supports an earlier recorded date, inclusive through the end of that day in Africa/Lagos. Later repayments/assignments/assignment corrections are excluded; current profile names/contacts are labeled as current. Customers settled today can still have debt on an earlier date. Complete current history must reconcile before reconstruction. This is recorded history, not a claim about unrecorded physical payments.
- Ageing day limits can be changed for the current report (for example 15,45). Limits must be positive/increasing; due today/future and undated remain separate. Unassigned repayments keep exact bands unavailable. These report settings are not yet saved as shop defaults.
- Reports → Product performance exports sales after discounts, refund amounts and historical base quantities. Sales and returns use their own dates; older-sale returns can produce negative net sales in a period. This is revenue, not profit or cash received.
- Reports → Staff activity groups each sale/refund by its recorded actor snapshot; refund recorders remain separate from original salespeople. Older events remain explicitly unattributed. These figures do not calculate commissions.
- Seventeen additional tests bring the checkpoint to 258 passing tests, zero failures/errors/skips. Includes WAT cutoffs, correction chronology, settled-customer history, invalid input/history, custom bands, pack/discount/refund reconciliation, actor grouping, foreign/duplicate rejection and spreadsheet-safe exports.
- Native Compose preview rendered on an isolated Xvfb display at 1440 × 1000. Keyboard date entry reached the earlier-date empty state and malformed dates disabled export. The view was adjusted to scroll controls/results on shorter screens; band customization is collapsed by default.
- A further native preview ran inside a Linux user/network namespace with only loopback and no network routes. Changed bands to 15,45 using keyboard input, scrolled to verify all band totals, and saved a CSV using the native dialog. The actual file contained current debt 750.00 NGN, unassigned payments 0.00 and 750.00 in the not-late band. This was isolated sample data, not merchant records. See [custom ageing](artifacts/desktop-custom-ageing.png). No external services were used. This checks the local sample workflow, not every offline operation or independent-machine recovery.
- Merchant research, physical hardware, Windows and screen-reader testing remain outstanding.
