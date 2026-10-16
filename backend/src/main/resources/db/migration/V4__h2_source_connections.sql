-- Legacy H2 profiles without a mode remain hidden; new explicit H2 sources are supported.
ALTER TABLE connection_profile ADD CONSTRAINT valid_h2_source CHECK (
 database_type <> 'H2' OR connection_type IS NULL OR (
 connection_type IN ('FILE','MEMORY','TCP') AND database_name IS NOT NULL AND password_ciphertext IS NOT NULL
 )
);
