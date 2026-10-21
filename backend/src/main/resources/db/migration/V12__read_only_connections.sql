-- Source protection is opt-out: destinations must explicitly permit writes.
ALTER TABLE connection_profile ADD COLUMN read_only BOOLEAN DEFAULT TRUE NOT NULL;
