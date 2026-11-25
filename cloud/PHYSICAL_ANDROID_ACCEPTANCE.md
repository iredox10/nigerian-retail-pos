# Physical Android acceptance

Use a separate QA installation for fixtures and destructive recovery tests. The normal package is `ng.retailpos.android`; the acceptance package is `ng.retailpos.android.acceptance`, labeled **Retail POS QA**. Their databases, passwords, drafts and Keystore keys are separated by Android app sandboxing. Never clear/uninstall the regular app to make a test pass.

Build and install using the selected device serial (do not use an unspecified first device):

```sh
gradle -PandroidAcceptance=true :androidApp:assembleAcceptance :androidApp:assembleAcceptanceAndroidTest
adb -s DEVICE_SERIAL install --user 0 -r androidApp/build/outputs/apk/acceptance/androidApp-acceptance.apk
adb -s DEVICE_SERIAL install --user 0 -r androidApp/build/outputs/apk/androidTest/acceptance/androidApp-acceptance-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument --user 0 -w -e cloudUrl https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com ng.retailpos.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

Both USB debugging authorization and working phone internet/DNS are needed for the live cases. USB connection alone does not supply internet. The owner-flow tests use disposable accounts/shops and revoke their cloud device tokens afterward; fixtures live in QA cache directories. Do not toggle a user's mobile/Wi-Fi settings automatically for testing.

The offline owner-import case can run independently of the network:

```sh
adb -s DEVICE_SERIAL shell am instrument --user 0 -w -e class pos.android.AndroidCloudOwnerFlowTest#importedShopRequiresItsOwnOwnerUnlockEvenWithoutInternet ng.retailpos.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

Check the actual runner result, including failed/skipped cases. A compiled APK or an installed app is not a passing physical test. Keep the phone connected and unlocked until results are collected. Update the regular app only with a matching signed, data-preserving APK; never uninstall it to bypass a signing mismatch.
