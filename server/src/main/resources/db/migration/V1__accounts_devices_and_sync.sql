CREATE TABLE cloud_accounts (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_salt VARCHAR(128) NOT NULL, password_hash VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, disabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE cloud_account_sessions (
 token_hash CHAR(64) PRIMARY KEY, account_id UUID NOT NULL REFERENCES cloud_accounts(id),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL CHECK(expires_at>created_at)
);
CREATE INDEX cloud_account_sessions_expiry ON cloud_account_sessions(expires_at);
CREATE TABLE cloud_auth_attempts (
 attempt_key CHAR(64) PRIMARY KEY, failures INTEGER NOT NULL DEFAULT 0 CHECK(failures>=0),
 cooldown_until TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE cloud_businesses (
 id UUID PRIMARY KEY, name VARCHAR(150) NOT NULL, schema_hash CHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), snapshot JSONB NOT NULL,
 product_versions JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL,
 creator_account_id UUID NOT NULL REFERENCES cloud_accounts(id), creation_key VARCHAR(200) NOT NULL,
 creation_hash CHAR(64) NOT NULL, UNIQUE(creator_account_id,creation_key)
);
CREATE TABLE cloud_memberships (
 business_id UUID NOT NULL REFERENCES cloud_businesses(id), account_id UUID NOT NULL REFERENCES cloud_accounts(id),
 role VARCHAR(20) NOT NULL CHECK(role IN('OWNER')), active BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(business_id,account_id)
);
CREATE TABLE cloud_devices (
 id UUID PRIMARY KEY, business_id UUID NOT NULL REFERENCES cloud_businesses(id), account_id UUID NOT NULL REFERENCES cloud_accounts(id),
 installation_id UUID NOT NULL, name VARCHAR(100) NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL, enrolled_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 UNIQUE(business_id,account_id,installation_id), CHECK(expires_at>enrolled_at)
);
CREATE TABLE cloud_operations (
 business_id UUID NOT NULL REFERENCES cloud_businesses(id), operation_id VARCHAR(200) NOT NULL,
 device_id UUID NOT NULL REFERENCES cloud_devices(id), request_hash CHAR(64) NOT NULL, request_payload JSONB NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN('accepted','conflict')), revision BIGINT,
 last_assessed_revision BIGINT NOT NULL, conflicts JSONB NOT NULL DEFAULT '[]',
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(business_id,operation_id)
);
CREATE TABLE cloud_operation_assessments (
 id BIGSERIAL PRIMARY KEY, business_id UUID NOT NULL, operation_id VARCHAR(200) NOT NULL,
 assessed_revision BIGINT NOT NULL, status VARCHAR(20) NOT NULL, conflicts JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(business_id,operation_id) REFERENCES cloud_operations(business_id,operation_id)
);
CREATE TABLE cloud_changes (
 business_id UUID NOT NULL REFERENCES cloud_businesses(id), revision BIGINT NOT NULL CHECK(revision>0),
 operation_id VARCHAR(200) NOT NULL, device_id UUID NOT NULL REFERENCES cloud_devices(id), delta JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(business_id,revision), UNIQUE(business_id,operation_id),
 FOREIGN KEY(business_id,operation_id) REFERENCES cloud_operations(business_id,operation_id)
);
CREATE INDEX cloud_operations_conflicts ON cloud_operations(business_id,status);
