CREATE TABLE IF NOT EXISTS economy_towny_governments (
    towny_binding_id UUID NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    government_type ENUM('TOWN', 'NATION') NOT NULL,
    government_uuid UUID NOT NULL,
    bank_account_uuid UUID NOT NULL,
    government_name VARCHAR(191) NOT NULL,
    bank_account_name VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uq_economy_towny_governments_account (account_id),
    UNIQUE KEY uq_economy_towny_governments_government (government_type, government_uuid),
    UNIQUE KEY uq_economy_towny_governments_bank_uuid (bank_account_uuid),
    CONSTRAINT fk_economy_towny_governments_account
        FOREIGN KEY (account_id) REFERENCES economy_accounts(account_id)
        ON DELETE CASCADE
);
