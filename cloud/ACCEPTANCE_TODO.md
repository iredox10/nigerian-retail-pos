# Cloud acceptance continuation — 25 November 2026

Existing shop records and ordinary offline operation stay independent of the server. Source changes are committed in small slices; execution evidence is in BUILD_STATUS.md and artifacts/cloud-acceptance/test-summary.json.

- [x] Isolated `Retail POS QA` Android acceptance package installed on the physical itel phone without modifying the regular POS installation.
- [x] Physical Android 13 local storage, checkout, credentials, receipt/image and repository checks: 28 local cases passed in the latest full 30-case run.
- [x] Physical genuine owner/session import check passed separately, including separate local owner unlock, denied reads and duplicate prevention.
- [x] Correct the shared business-read session gap and prevent Android automatic refresh from renewing idle sessions.
- [x] Real HTTP/Postgres stale-count, concurrent over-return, database/checkpoint crash-gap and compressed-bootstrap checks pass.
- [x] Configure Windows MSI/EXE packaging, private-file ACL tests and a Windows CI job.
- [x] Build the final QA/regular Android APKs and Linux application; 738 JVM tests pass.
- [x] Reinstall final QA build and complete its 31-case physical run with a saved final report: all 29 local cases pass, including exact native image bytes and guarded owner import.
- [x] Rerun both live cases once phone DNS recovers: both passed in 74.909 seconds, verifying HTTPS publication/download, exact image import, guarded owner unlock, sync, revocation and reconnect. Preserve the prior two DNS failures; this is a targeted retry, not a second full-suite run.
- [ ] Complete manual idle-timer and rendered UI acceptance.
- [x] Update the regular phone app with data-preserving `adb install --user 0 -r`; all 10 saved data files have identical hashes before/after. Launch and resumed Activity verified; screenshot/interactive acceptance remains unperformed because the phone returned to its lock screen.
- [ ] Execute the Windows workflow on a Windows runner. GitHub API/CLI timed out here; configuration is not a passing Windows run.
- [ ] Complete interactive two-app enrollment, keyboard/accessibility acceptance, large-data/performance, power-loss/independent-machine recovery and merchant research.
