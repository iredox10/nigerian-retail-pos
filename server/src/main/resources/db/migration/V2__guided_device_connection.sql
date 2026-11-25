ALTER TABLE cloud_devices ADD COLUMN last_seen_at TIMESTAMPTZ;
ALTER TABLE cloud_accounts ADD COLUMN email_verified_at TIMESTAMPTZ;
CREATE TABLE cloud_pairings (
 id UUID PRIMARY KEY, code_hash CHAR(64) NOT NULL UNIQUE, secret_hash CHAR(64) NOT NULL,
 installation_id UUID NOT NULL, name VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, account_id UUID REFERENCES cloud_accounts(id),
 business_id UUID REFERENCES cloud_businesses(id), device_id UUID REFERENCES cloud_devices(id),
 approved_at TIMESTAMPTZ, finished_at TIMESTAMPTZ
);
CREATE TABLE cloud_action_limits (
 action_key CHAR(64) PRIMARY KEY, window_start TIMESTAMPTZ NOT NULL, attempts INTEGER NOT NULL
);
CREATE TABLE cloud_email_challenges (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES cloud_accounts(id),
 purpose VARCHAR(16) NOT NULL CHECK(purpose IN ('verify','reset')),
 code_hash CHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 consumed_at TIMESTAMPTZ
);
CREATE INDEX cloud_email_challenges_account ON cloud_email_challenges(account_id,purpose,created_at);
