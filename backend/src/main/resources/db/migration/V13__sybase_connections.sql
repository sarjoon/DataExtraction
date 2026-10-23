ALTER TABLE connection_profile DROP CONSTRAINT valid_database_type;
ALTER TABLE connection_profile ADD CONSTRAINT valid_database_type CHECK (database_type IN ('H2','ORACLE','SYBASE'));
ALTER TABLE connection_profile ADD CONSTRAINT valid_sybase_source CHECK (
 database_type <> 'SYBASE' OR (host IS NOT NULL AND port BETWEEN 1 AND 65535
 AND connection_type='ASE' AND database_name IS NOT NULL AND password_ciphertext IS NOT NULL)
);
