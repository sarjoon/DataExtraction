CREATE TABLE snapshot_table_registry (
 connection_id BIGINT NOT NULL REFERENCES connection_profile(id),
 table_name VARCHAR(128) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
 PRIMARY KEY(connection_id,table_name)
);
-- Preserve all existing Snapshot selections and exclusions when upgrading.
INSERT INTO snapshot_table_registry(connection_id,table_name)
 SELECT connection_id,table_name FROM snapshot_table;
ALTER TABLE snapshot_table ADD CONSTRAINT fk_snapshot_registered_table
 FOREIGN KEY(connection_id,table_name) REFERENCES snapshot_table_registry(connection_id,table_name) ON DELETE CASCADE;
