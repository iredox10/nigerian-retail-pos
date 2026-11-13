# Online integration decisions

Recorded 13 November 2026. Planning only; these are not connected services.

## User selections

- Payments: Paystack.
- Messaging: Telegram first. Evaluate other mainstream Nigerian channels where automated integration can meet the user's preference for free messaging.
- Hosting: no existing Heroku app. Create/configure the deployment after backend and staging checks; do not report an app or deployment as existing.

## Paystack implementation requirements

Use a backend for provider credentials and payment verification; never put secret keys in desktop/mobile/web clients. Reconcile provider reference, merchant tenant, currency and expected amount before a payment changes a sale balance. Treat successful API transport separately from successful transaction status. Persist incoming events and process idempotently.

Current webhook documentation describes a timestamped Paystack-Signature header with HMAC-SHA512, a signing secret, key rotation and a five-minute timestamp tolerance. It also documents legacy x-paystack-signature verification using the API secret for integrations without the new header. Implement the configured scheme from current documentation; do not assume only the older scheme exists. Verify raw bytes before parsing and use constant-time comparisons.

Keep unverified cashier records separate from provider-confirmed payments. Offline operation cannot invent a confirmed Paystack payment.

Primary sources checked:
- [Verify payments](https://paystack.com/docs/payments/verify-payments/)
- [Webhooks](https://paystack.com/docs/payments/webhooks/)

## Telegram messaging

Telegram's bot platform supports free ordinary bot messaging with rate limits. Avoid paid broadcasts; queue and throttle deliveries. A bot must have a permitted destination/chat, with explicit account linking and consent; do not treat a Nigerian phone number as a Telegram chat ID. Keep bot tokens on the backend. Local offline queues are not delivered messages.

Track pending, attempted, delivered-to-provider, retryable failure, blocked and cancelled states truthfully. A provider response does not establish that a human read a message. Build opt-out and auditable merchant authorization.

Primary sources checked:
- [Bot platform](https://core.telegram.org/bots)
- [Bot FAQ and messaging limits](https://core.telegram.org/bots/faq)
- [Bot API](https://core.telegram.org/bots/api)

## Other channels and costs

Do not promise free automated WhatsApp or SMS. Meta documents per-delivered-message pricing by market and category for its business platform; the free consumer/business app is a different product. Manual sharing can be offered separately from automated delivery. Evaluate each additional channel's real API access, recipient requirements and charges before implementing it as a free automated channel.

Primary source checked:
- [WhatsApp Business Platform pricing](https://business.whatsapp.com/products/platform-pricing)

## Required external setup

- Paystack account/test credentials and webhook signing configuration through secure local/server configuration.
- Telegram bot token and opted-in test chats through secure configuration.
- Heroku account/project access and chosen staging resources.
- Actual sandbox integration checks, webhook reachability, retries and end-to-end payment/message evidence.

Do not paste secrets into these documents or source control. No credentials or live payment/message requests were used in this research.
