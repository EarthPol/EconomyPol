CREATE TABLE IF NOT EXISTS economy_players (
    player_uuid UUID NOT NULL PRIMARY KEY,
    username VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS economy_accounts (
    account_id UUID NOT NULL PRIMARY KEY,
    account_type VARCHAR(32) NOT NULL,
    owner_uuid UUID NULL,
    account_name VARCHAR(191) NOT NULL,
    allow_self_deposit BOOLEAN NOT NULL,
    allow_external_credit BOOLEAN NOT NULL,
    allow_self_withdraw BOOLEAN NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uq_economy_accounts_owner (account_type, owner_uuid),
    UNIQUE KEY uq_economy_accounts_name (account_name)
);

CREATE TABLE IF NOT EXISTS economy_towny_governments (
    government_uuid UUID NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    government_type ENUM('TOWN', 'NATION') NOT NULL,
    bank_account_uuid UUID NOT NULL,
    government_name VARCHAR(191) NOT NULL,
    bank_account_name VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uq_economy_towny_governments_account (account_id),
    UNIQUE KEY uq_economy_towny_governments_bank_uuid (bank_account_uuid),
    CONSTRAINT fk_economy_towny_governments_account
        FOREIGN KEY (account_id) REFERENCES economy_accounts(account_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS economy_account_members (
    account_id UUID NOT NULL,
    member_uuid UUID NOT NULL,
    membership_role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (account_id, member_uuid),
    CONSTRAINT fk_economy_account_members_member
        FOREIGN KEY (member_uuid) REFERENCES economy_players(player_uuid)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS economy_balances (
    account_id UUID NOT NULL PRIMARY KEY,
    available_balance BIGINT NOT NULL,
    reserved_balance BIGINT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS economy_ender_wallet_snapshots (
    player_uuid UUID NOT NULL PRIMARY KEY,
    base_units BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    last_clean_sync_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_economy_ender_wallet_snapshots_player
        FOREIGN KEY (player_uuid) REFERENCES economy_players(player_uuid)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS economy_reservations (
    reservation_id UUID NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NULL
);

CREATE TABLE IF NOT EXISTS economy_ledger_entries (
    entry_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id UUID NULL,
    related_account_id UUID NULL,
    player_uuid UUID NULL,
    delta BIGINT NOT NULL,
    available_balance BIGINT NULL,
    reserved_balance BIGINT NULL,
    entry_type VARCHAR(64) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_economy_ledger_entries_player
        FOREIGN KEY (player_uuid) REFERENCES economy_players(player_uuid)
        ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS economy_player_notifications (
    notification_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid UUID NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    primary_amount BIGINT NULL,
    secondary_amount BIGINT NULL,
    detail_text VARCHAR(255) NULL,
    flag_value BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL,
    KEY idx_economy_player_notifications_player (player_uuid, created_at, notification_id),
    CONSTRAINT fk_economy_player_notifications_player
        FOREIGN KEY (player_uuid) REFERENCES economy_players(player_uuid)
        ON DELETE CASCADE
);
