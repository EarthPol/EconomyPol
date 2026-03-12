CREATE TABLE IF NOT EXISTS economy_accounts (
    account_id UUID NOT NULL PRIMARY KEY,
    account_type VARCHAR(32) NOT NULL,
    owner_uuid UUID NULL,
    account_name VARCHAR(191) NOT NULL,
    allow_self_deposit BOOLEAN NOT NULL,
    allow_external_credit BOOLEAN NOT NULL,
    allow_self_withdraw BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uq_economy_accounts_owner (account_type, owner_uuid),
    UNIQUE KEY uq_economy_accounts_name (account_name)
);

CREATE TABLE IF NOT EXISTS economy_account_members (
    account_id UUID NOT NULL,
    member_uuid UUID NOT NULL,
    membership_role VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (account_id, member_uuid)
);

CREATE TABLE IF NOT EXISTS economy_balances (
    account_id UUID NOT NULL PRIMARY KEY,
    available_balance BIGINT NOT NULL,
    reserved_balance BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS economy_ender_wallet_snapshots (
    player_uuid UUID NOT NULL PRIMARY KEY,
    base_units BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    last_clean_sync_at BIGINT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS economy_reservations (
    reservation_id UUID NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(191) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NULL
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
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS economy_player_notifications (
    notification_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid UUID NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    primary_amount BIGINT NULL,
    secondary_amount BIGINT NULL,
    detail_text VARCHAR(255) NULL,
    flag_value BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    KEY idx_economy_player_notifications_player (player_uuid, created_at, notification_id)
);
