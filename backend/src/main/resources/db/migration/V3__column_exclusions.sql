CREATE TABLE column_exclusion (
 table_id BIGINT NOT NULL REFERENCES table_registry(id) ON DELETE CASCADE,
 column_name VARCHAR(128) NOT NULL,
 excluded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (table_id,column_name)
);
