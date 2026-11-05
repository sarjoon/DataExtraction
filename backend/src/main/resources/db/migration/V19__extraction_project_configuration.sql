CREATE TABLE extraction_project_configuration (
    connection_id BIGINT PRIMARY KEY REFERENCES connection_profile(id) ON DELETE CASCADE,
    settings_json CLOB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
