# Windows build and acceptance

The native desktop packaging configuration produces per-user MSI and EXE installers on Windows. The upgrade UUID stays fixed across releases. These are unsigned development installers, not a claim of SmartScreen/code-signing acceptance.

The `Windows desktop acceptance` GitHub Actions workflow builds on Windows Server 2022 using Java 17 and the committed Gradle wrapper. It runs shared domain, protocol, repository recovery and actual Windows ACL/secret round-trip tests, then packages both installers and uploads them with reports. A workflow definition is not evidence of a successful run; check the run result and downloaded reports before distributing a Windows build.

On a Windows workstation with JDK 17:

```powershell
./gradlew.bat :shared:jvmTest :cloudProtocol:test :businessCore:test :desktopApp:jvmTest --tests pos.cloud.desktop.DesktopCloudFilesTest
./gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe
```

Compose/jpackage requires WiX tooling for Windows installer generation. The Windows runner/build tools must be able to fetch its installer dependencies. Linux cannot produce or validate a native Windows installer merely by changing a file extension.

After obtaining a successful build, use a separate Windows account and disposable shop to verify installation, launch, local owner unlock, sale/receipt, cloud enrollment, lock/resume, device revocation, private-file permissions, upgrade retaining records and uninstall/reinstall recovery. Keep the original backup until independent-machine recovery is demonstrated. Do not run an acceptance uninstall against a merchant workstation.

Full Windows merchant workflows, real peripherals, signed-release policy and independent-machine/power-failure acceptance remain separate from the automated build.
