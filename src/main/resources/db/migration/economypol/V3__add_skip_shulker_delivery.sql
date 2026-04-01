ALTER TABLE economy_players
    ADD COLUMN skip_shulker_delivery BOOLEAN NOT NULL DEFAULT FALSE
    AFTER incoming_payment_delivery_preference;
