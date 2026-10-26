ALTER TABLE table_registry ADD COLUMN update_frequency VARCHAR(10) DEFAULT 'Daily' NOT NULL;
ALTER TABLE table_registry ADD CONSTRAINT valid_table_update_frequency CHECK (update_frequency IN ('Daily','Weekly'));
