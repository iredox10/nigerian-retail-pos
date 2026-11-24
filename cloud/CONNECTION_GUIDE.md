# Desktop and Android cloud connection

Local trading remains available without a server. Synchronization shares saved business records, including managed images; it never confirms a bank transfer, card payment or settlement.

The initial backend address is `https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com`. Heroku is configured for one Basic dyno and Essential-0 Postgres (currently $7 + $5 per month).

## Connect the first device

1. Open the shop as its local owner, then **More → Cloud connection**.
2. Enter the HTTPS root address of your deployed POS backend. Create a cloud account with a separate password of at least 12 characters, or sign in.
3. Name the device and review **Connect this shop**. This publishes its business records and images. Local staff/owner passwords, recovery codes and drafts are excluded.
4. Wait for **Records synchronized**, or review the displayed connection error. An acknowledgment is required; opening the screen does not mean work was uploaded.

## Connect the second device

1. Open or create a local workspace as its owner. This grants authority to download another workspace without replacing existing shops.
2. Open **More → Cloud connection**, using the same backend and cloud account.
3. Choose the existing cloud shop, give it a local name and a new local owner password, then **Download as a new local shop**.
4. The app returns to the shop picker. Open the downloaded shop using that new local password. Set up local staff access on this device separately.

Keep the selected shop open and unlocked for automatic sync, which checks about every 15 seconds. Android pauses in the background. Offline devices cannot see each other's new work until each reconnects. **Sync now** retries immediately; normal connection failures back off automatically. Expired or revoked device credentials require owner cloud sign-in and **Renew this device's access**.

## Review and recovery

Conflicting business changes stay in the local database and durable pending checkpoint. The screen shows the affected table and reason. Completed financial history cannot be replaced or deleted to make a sync pass. A stock count made against stock that moved elsewhere may need a separate business reconciliation; retries alone do not resolve it. Keep encrypted backups from both devices and review the underlying records before making a correction. There is no force-overwrite conflict action.

After owner cloud sign-in, **Show connected devices → Revoke access** stops future server access. Previously downloaded records remain on that device; revocation does not erase them. Device credentials expire after 30 days. Account setup sessions last 30 minutes and stay only in memory.

Cloud checkpoints and device tokens are installation metadata and are intentionally excluded from business backups. Restoring a backup is a new local workspace, requiring explicit cloud setup; it is not a credential restore. Desktop tokens use owner-only files; Android tokens use AES-GCM with an Android Keystore key. Keep separate encrypted shop backups: synchronization is not a replacement-machine restore drill or point-in-time cloud backup.

## Backend deployment

`app.json`, `Procfile` and `system.properties` describe a Java 17 Spring Boot service and PostgreSQL database. Heroku builds only the backend with `GRADLE_TASK=-PcloudOnly=true :server:bootJar`; no Android SDK is required. `ORG_GRADLE_PROJECT_cloudOnly=true` also applies to the buildpack’s initialization tasks. Flyway applies the schema at startup. `DATABASE_URL` is supplied privately by Heroku Postgres, never committed to the repository.

For local PostgreSQL verification, set `CLOUD_TEST_JDBC_URL`, `CLOUD_TEST_DB_USER` and `CLOUD_TEST_DB_PASSWORD`, then run `gradle -PcloudOnly=true :cloudProtocol:test :businessCore:test :cloudClient:test :server:test`. Backend HTTP is allowed only from loopback development or Heroku's HTTPS termination. Installed app setup accepts HTTPS addresses only. Do not expose the local development database to the internet.

Capacity/performance, merchant research, Windows credential storage, independent hardware, large-photo synchronization and operational disaster recovery require separate acceptance. This is an initial synchronization implementation, not evidence that all production rollout requirements have passed.
