# Desktop and Android cloud connection

Local trading remains available without a server. Synchronization shares saved business records, including managed images; it never confirms a bank transfer, card payment or settlement.

The initial backend address is `https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com`. Heroku is configured for one Basic dyno and Essential-0 Postgres (currently $7 + $5 per month).

## Connect the first device

1. Open the shop as its local owner, then **More → Connected devices → Connect this shop**.
2. Create a shop account, or choose **I already have an account**. The hosted service address is configured for you; custom hosting stays under **Advanced settings**.
3. Name this device, for example **Shop computer**, then **Review and connect**. The review explains which saved records and images will be shared. Local unlock passwords, recovery proof and drafts stay on this device.
4. Wait for a real completed update. **Shop connected** alone does not acknowledge every later local change.
5. Under **Account and recovery**, verify the account email when service email delivery is configured. Without configured delivery the app explicitly marks verification/recovery unavailable; it does not pretend to send messages.

## Add a phone or computer

1. On a new installation, choose **Use my existing shop** directly on the welcome screen. No empty placeholder shop is needed. On an installation with an existing shop, open it as its owner and choose **Use my existing shop** in Connected devices; the imported shop remains separate.
2. Name the new device and select **Get connection code**. It shows a ten-character code and QR code valid for ten minutes.
3. On the device already connected to the shop, select **More → Connected devices → Add or manage devices** and sign in as the account owner.
4. Enter the new device's code or, on Android, choose **Scan QR code** and take a clear photo of the code. Review the named device and shop before selecting **Allow this device**. Only approve a code from a device you recognize in front of you.
5. Back on the new device, confirm the approved shop name. Choose and confirm its **device unlock password**, then select **Download my shop**. This password stays local and does not change passwords elsewhere.
6. Open the downloaded shop from the picker using that password. Set up local staff access separately.

If the original device is unavailable, **Sign in instead** lets the owner sign into the same account, choose a connected shop and download it. An installation with existing local shops requires its local owner's authorization. Pairing/import never overwrites those shops. If the new installation already belongs to the same cloud shop, sign in to restore access rather than using a second pairing code.

## Everyday use

Keep the selected shop open and unlocked for automatic sharing. The app checks about every fifteen seconds and retries connection failures with backoff. Android pauses sharing in the background. Every supported offline operation saves locally without a server request. Offline devices cannot see each other's new work until each reconnects.

**Saved on this device** refers to local persistence. **Changes sent · Last checked …** refers to the latest acknowledged exchange with the server; it does not mean every other device is online or current. **Check for updates** requests a check immediately. A connection failure never requires recording a sale again. Technical state and batch diagnostics are hidden under **Connection details**.

Successful authorized cloud requests maintain an active device's thirty-day access window. This cannot revive expired or removed access. If access expires or is removed, the local owner can sign in to the account and select **Restore this device’s connection**. Account setup sessions last thirty minutes and stay in memory.

## Forgot the account password

Choose **Forgot account password?** on account sign-in. Recovery requires an email previously verified through **Account and recovery**, configured delivery and a one-time code from the inbox. Changing the account password invalidates existing account sessions and device cloud access. Sign in again on each device to restore sharing. Local unlock passwords and saved business records do not change.

Service operators configure Resend/SMTP privately as described in [service operations](SERVICE_OPERATIONS.md). A UI button or a mocked test mailbox is not evidence that real email delivery is configured.

## Review and recovery

Conflicting business changes stay in the local database and durable pending checkpoint. The screen explains the affected business task; table/reason details remain available under Connection details. Completed financial history cannot be replaced or deleted to make a sync pass. A stock count made against stock that moved elsewhere may need a separate business reconciliation; retries alone do not resolve it. Keep encrypted backups from both devices and review the underlying records before making a correction. There is no force-overwrite conflict action.

After owner cloud sign-in, **Show connected devices → Remove access** stops future server access. Previously downloaded records remain on that device; revocation does not erase them. Inactive device credentials expire after 30 days; successful authorized requests maintain access without bypassing revocation. Account setup sessions last 30 minutes and stay only in memory.

Cloud checkpoints and device tokens are installation metadata and are intentionally excluded from business backups. Restoring a backup is a new local workspace, requiring explicit cloud setup; it is not a credential restore. Desktop tokens use owner-only files; Android tokens use AES-GCM with an Android Keystore key. Keep separate encrypted shop backups: synchronization is not a replacement-machine restore drill or point-in-time cloud backup.

## Backend deployment

`app.json`, `Procfile` and `system.properties` describe a Java 17 Spring Boot service and PostgreSQL database. Heroku builds only the backend with `GRADLE_TASK=-PcloudOnly=true :server:bootJar`; no Android SDK is required. `ORG_GRADLE_PROJECT_cloudOnly=true` also applies to the buildpack’s initialization tasks. Flyway applies the schema at startup. `DATABASE_URL` is supplied privately by Heroku Postgres, never committed to the repository.

For local PostgreSQL verification, set `CLOUD_TEST_JDBC_URL`, `CLOUD_TEST_DB_USER` and `CLOUD_TEST_DB_PASSWORD`, then run `gradle -PcloudOnly=true :cloudProtocol:test :businessCore:test :cloudClient:test :server:test`. Backend HTTP is allowed only from loopback development or Heroku's HTTPS termination. Installed app setup accepts HTTPS addresses only. Do not expose the local development database to the internet.

Capacity/performance, merchant research, Windows credential storage, independent hardware, large-photo synchronization and operational disaster recovery require separate acceptance. This is an initial synchronization implementation, not evidence that all production rollout requirements have passed.
