# Physical Android acceptance

Use a separate QA installation for fixtures and destructive recovery tests. The normal package is `ng.retailpos.android`; the acceptance package is `ng.retailpos.android.acceptance`, labeled **Retail POS QA**. Their databases, passwords, drafts and Keystore keys are separated by Android app sandboxing. Never clear/uninstall the regular app to make a test pass.

Build and install using the selected device serial (do not use an unspecified first device):

```sh
gradle -PandroidAcceptance=true :androidApp:assembleAcceptance :androidApp:assembleAcceptanceAndroidTest
adb -s DEVICE_SERIAL install --user 0 -r androidApp/build/outputs/apk/acceptance/androidApp-acceptance.apk
adb -s DEVICE_SERIAL install --user 0 -r androidApp/build/outputs/apk/androidTest/acceptance/androidApp-acceptance-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument --user 0 -w -e listener pos.android.qa.PersistentQaResults -e qaRunId UNIQUE_RUN_ID -e cloudUrl https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com ng.retailpos.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

Both USB debugging authorization and working phone internet/DNS are needed for the live cases. USB connection alone does not supply internet. The owner-flow tests use disposable accounts/shops and revoke their cloud device tokens afterward; fixtures live in QA cache directories. Do not toggle a user's mobile/Wi-Fi settings automatically for testing.

The offline owner-import case can run independently of the network:

```sh
adb -s DEVICE_SERIAL shell am instrument --user 0 -w -e class pos.android.AndroidCloudOwnerFlowTest#importedShopRequiresItsOwnOwnerUnlockEvenWithoutInternet ng.retailpos.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

Check the actual runner result, including failed/skipped cases. A compiled APK or an installed app is not a passing physical test. Keep the phone connected and unlocked until results are collected. Update the regular app only with a matching signed, data-preserving APK; never uninstall it to bypass a signing mismatch.

## Interrupted USB output

The optional `PersistentQaResults` listener stores its report in the isolated QA app, so the next USB connection can inspect whether a run finished:

```sh
adb -s DEVICE_SERIAL shell run-as ng.retailpos.android.acceptance cat files/qa-results/last-run.json
```

Require the expected unique `runId`, `status=finished`, zero failures/ignored/assumptions and the expected test count. A `running` report is not a completed run. The report contains test names and counts, never account passwords or merchant records.

Only the acceptance build declares `WAKE_LOCK`. When this listener is selected it holds a screen wake lock during the run, releases it on completion, and has a ten-minute maximum. It does not dismiss a secure lock screen or change the user's screen-timeout settings. This prevents screen sleep from interrupting unattended QA where the device permits it; it cannot fix a physically disconnected USB cable. The regular app has no extra wake-lock permission.

## Guided setup build

The latest guided-setup test APK contains **32 cases**, including a new live owner-approved pairing test that imports into an entirely empty native installation, verifies a separate local unlock password, resumes its Keystore-backed pairing ticket and checks revocation without deleting saved stock. This case is compiled but not yet executed after the physical phone disconnected. The earlier 31-case physical reports apply to the previous build. Use a new unique run ID and require all 32 results before claiming a full run of the new build. Never use the other project's emulator or clear/uninstall the normal phone app to make installation succeed.
