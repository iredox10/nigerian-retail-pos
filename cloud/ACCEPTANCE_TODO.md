# Cloud acceptance continuation — 25 November 2026

Existing shop records and ordinary offline operation stay independent of the server. Source changes are committed in small slices; execution evidence is in BUILD_STATUS.md and artifacts/cloud-acceptance/test-summary.json.

- [x] Isolated `Retail POS QA` Android acceptance package installed on the physical itel phone without modifying the regular POS installation.
- [x] Physical Android 13 local storage, checkout, credentials, receipt/image and repository checks: 28 local cases passed in the latest full 30-case run.
- [x] Physical genuine owner/session import check passed separately, including separate local owner unlock, denied reads and duplicate prevention.
- [x] Correct the shared business-read session gap and prevent Android automatic refresh from renewing idle sessions.
- [x] Real HTTP/Postgres stale-count, concurrent over-return, database/checkpoint crash-gap and compressed-bootstrap checks pass.
- [x] Configure Windows MSI/EXE packaging, private-file ACL tests and a Windows CI job.
- [x] Build the final QA/regular Android APKs and Linux application; 738 JVM tests pass.
- [ ] Reinstall final QA build and rerun live owner/image publication/import/revocation after stable USB and working phone DNS are available. The extra native image-byte assertion and latest activity wiring still need that final run.
- [ ] Update the regular phone app with data-preserving `adb install -r` after final phone acceptance; it has not been changed by this continuation.
- [ ] Execute the Windows workflow on a Windows runner. GitHub API/CLI timed out here; configuration is not a passing Windows run.
- [ ] Complete interactive two-app enrollment, keyboard/accessibility acceptance, large-data/performance, power-loss/independent-machine recovery and merchant research.
