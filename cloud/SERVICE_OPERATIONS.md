# Cloud service operations

## Email verification and recovery

Email is available to the apps only when delivery is configured. Existing local unlock passwords are independent. Verify the account email before relying on password recovery. A completed reset consumes its code, invalidates account sessions, revokes device sharing and closes pending approvals; saved business records remain.

Resend supports the server's SMTP integration. Configure these privately in the hosting dashboard or an authenticated CLI session:

- `CLOUD_MAIL_HOST=smtp.resend.com`
- `CLOUD_MAIL_PORT=587` (STARTTLS is required)
- `CLOUD_MAIL_USERNAME=resend`
- `CLOUD_MAIL_PASSWORD`: Resend sending API key, never committed or pasted into chat
- `CLOUD_MAIL_FROM`: your verified sender address, for example `Retail POS <accounts@your-verified-domain>`

You can also run `python3 cloud/ops/configure_resend.py --sender accounts@your-verified-domain` in your own terminal. It prompts privately for the key and sends the hosting API request without putting secrets in shell arguments.

These are configuration names, not evidence that delivery is working. Test with an address you control before rollout. The apps explicitly say recovery is unavailable when the service lacks mail configuration. The server requires encrypted SMTP, uses bounded timeouts and never logs the challenge code. Verification and recovery codes expire after ten minutes, are single-use, and have bounded attempts. Do not use Resend's test sender for unrestricted production enrollment.

References: [Resend SMTP](https://resend.com/changelog/smtp-service), [Spring mail](https://docs.spring.io/spring-boot/reference/io/email.html).

## Readiness monitoring

`python3 cloud/ops/check_health.py` checks the public HTTPS endpoint and the database readiness assertion behind it. It reads no merchant records. `.github/workflows/cloud-health.yml` schedules it every 15 minutes when pushed to an enabled GitHub repository. Workflow failure notification delivery depends on repository/account notification settings; a committed workflow is not an active monitoring service. Configure an independent uptime monitor against `/api/v1/health` with an owner-controlled alert destination for operational coverage independent of GitHub.

## Backup restore drill

Run `python3 cloud/ops/verify_backup.py --capture --output artifacts/cloud-acceptance/cloud-backup-restore.json` from an authenticated Heroku/Docker environment. It captures/downloads the actual hosted PostgreSQL backup, restores it into a temporary container with networking disabled, validates PostgreSQL constraints, migration presence and snapshot shape, and then destroys the container and private dump. It never restores over production and excludes merchant contents and signed download URLs from its evidence. Do not upload dumps or raw CLI diagnostics.

Backup capture requires an available Heroku backup slot. Configure and verify a retention schedule compatible with the database plan. A successful database restore is separate from testing business-domain recovery on a replacement device; perform that drill before claiming disaster-recovery acceptance. Cloud sharing is not point-in-time backup, and removing a device does not erase previously downloaded records.

## Executed checkpoint — 25 November 2026

A fresh hosted backup was captured and restored into an isolated PostgreSQL 17 container. PostgreSQL constraints, migration presence and snapshot shape passed; production was not restored or modified. Evidence: [restore report](../artifacts/cloud-acceptance/easy-backup-restore.json). The temporary dump/container were removed. This is database-level evidence; replacement-device/domain recovery remains separate.

Daily `DATABASE_URL` backups were scheduled successfully at **02:00 Africa/Lagos** using Heroku CLI. No schedule existed before this change. Heroku does not notify on scheduled-backup failure, so backup freshness must also be monitored. [Heroku backup scheduling/retention](https://devcenter.heroku.com/articles/heroku-postgres-backups).
