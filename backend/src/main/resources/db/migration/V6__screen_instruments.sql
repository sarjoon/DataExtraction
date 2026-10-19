ALTER TABLE screen_registry ADD COLUMN instrument VARCHAR(256) DEFAULT 'Unassigned' NOT NULL;
ALTER TABLE screen_registry DROP CONSTRAINT uq_registered_screen;
ALTER TABLE screen_registry ADD CONSTRAINT uq_registered_instrument_screen UNIQUE(connection_id, instrument, screen_name);
