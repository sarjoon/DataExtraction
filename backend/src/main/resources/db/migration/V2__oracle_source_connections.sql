-- Existing H2 rows and associated lists are preserved as legacy configuration.
-- Only Oracle profiles are offered as external sources by the application.
ALTER TABLE connection_profile ADD COLUMN database_type VARCHAR(20) DEFAULT 'H2' NOT NULL;
ALTER TABLE connection_profile ADD COLUMN host VARCHAR(253);
ALTER TABLE connection_profile ADD COLUMN port INTEGER;
ALTER TABLE connection_profile ADD COLUMN connection_type VARCHAR(20);
ALTER TABLE connection_profile ADD COLUMN database_name VARCHAR(128);
ALTER TABLE connection_profile ADD COLUMN password_ciphertext VARCHAR(2048);
ALTER TABLE connection_profile ADD CONSTRAINT valid_database_type CHECK (database_type IN ('H2', 'ORACLE'));
ALTER TABLE connection_profile ADD CONSTRAINT valid_oracle_connection CHECK (
    database_type <> 'ORACLE' OR (
        host IS NOT NULL AND port BETWEEN 1 AND 65535
        AND connection_type IN ('SERVICE_NAME', 'SID')
        AND database_name IS NOT NULL AND password_ciphertext IS NOT NULL
    )
);
