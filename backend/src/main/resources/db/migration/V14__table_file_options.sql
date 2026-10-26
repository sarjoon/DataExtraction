ALTER TABLE table_registry ADD COLUMN file_type VARCHAR(20) DEFAULT '' NOT NULL;
ALTER TABLE table_registry ADD COLUMN load_strategy VARCHAR(10) DEFAULT '' NOT NULL;
ALTER TABLE table_registry ADD CONSTRAINT valid_table_file_type CHECK (file_type IN ('','Master','Parameter','Transaction','Enriched','Tech Recon','Logs'));
ALTER TABLE table_registry ADD CONSTRAINT valid_table_load_strategy CHECK (load_strategy IN ('','Full','Delta'));
