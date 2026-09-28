# EconomyPol

EconomyPol is a Paper economy plugin for EarthPol built around a discrete, item-based commodity currency.

It is designed to let players hold and move real money items while still providing:

- VaultUnlocked v2 compatibility for modern plugins and Towny
- Legacy Vault compatibility for older plugins
- Database-backed custodial storage and shared accounts
- A managed offline ender-wallet snapshot for offline spending support
- Explicit Towny government bindings for town and nation bank accounts
- Audit logging, health checks, and a native API for EconomyPol-specific behavior

This README describes the plugin as it is currently implemented.

## Status

EconomyPol is already usable as a database-backed commodity economy core, but it is still early-stage.

Implemented today:

- Discrete denomination-based currency
- Player accounts and shared accounts
- Player registration rows with foreign-keyed player-owned economy data
- Join-time player identity refresh for `economy_players.username` and existing player account names
- Custodial balances and reservations
- VaultUnlocked v2 provider
- Legacy Vault provider
- Managed offline ender-wallet snapshots
- Persistent offline player notifications
- Folia-safe scheduler coordination for player-bound inventory work
- Towny government binding and lifecycle synchronization
- Split startup/runtime config with reloadable `config.yml`
- Database health checks
- Native `EconomyPolAPI`

Not implemented yet:

- PlaceholderAPI support
- GUI banking/teller interfaces
- broader admin repair commands for malformed data
- Rich transaction history commands

## Core Model

EconomyPol separates money into three states.

### 1. Live Physical Money

This is real item money held by a player in top-level inventory slots.

By default, current implementation counts:

- player inventory contents
- offhand
- ender chest contents

This can be narrowed through `wallet.include-live-player-inventory` and `wallet.include-live-ender-chest`.

Current implementation does not count:

- nested containers inside inventories or ender chests
- shulkers or other container recursion

Money is defined by configured denominations, for example:

- `GOLD_NUGGET = 1`
- `GOLD_INGOT = 9`
- `GOLD_BLOCK = 81`

All values are integer base units stored as `long`.

### 2. Custodial Balance

Custodial is database-backed stored value.

For players, custodial is primarily an overflow and storage mechanism:

- admins can explicitly deposit physical money into custodial with `/economypol deposit`
- online routed payouts can spill into custodial when no physical storage space remains
- offline credits can land in custodial if the offline ender-wallet is unavailable
- players must explicitly withdraw custodial funds as physical money to carry and use them

Important current rule:

- Player custodial money is **not** part of player spendable balance.
- Player `getBalance` / `has` / normal `withdraw` paths only use physical money or the frozen offline ender-wallet snapshot.

For shared accounts, custodial is the full source of truth:

- shared accounts are pure ledger balances
- shared accounts do not have physical inventory state
- bank/shared withdrawals and deposits use custodial directly

### 3. Managed Offline Ender Wallet

This is not a general offline inventory scan.

It is a managed snapshot of the player’s top-level ender chest money taken on quit and restored on next login.

Purpose:

- allow limited offline player spending/receiving
- avoid reading arbitrary offline NBT as a live source of truth

Rules:

- only top-level ender chest money is snapshotted
- nested containers are ignored
- on login, the snapshot is materialized back into the ender chest
- malformed stacks or delivery overflow are moved to custodial

## How Money Actually Moves

### Spending From a Player Account

There are two distinct withdrawal concepts:

#### `withdrawPlayer(...)`

This is the generic economy spend path used by Vault/VaultUnlocked and account withdrawals.

For players:

- online player: removes live physical money from inventory/offhand/ender chest
- offline player: debits the frozen offline ender-wallet snapshot
- custodial is not auto-spent
- if `routing.change-overflow-policy` is `CUSTODIAL`, unplaceable returned change is credited to custodial instead of canceling the spend

For shared accounts:

- debits custodial balance

#### `withdrawCustodialAsPhysicalMoney(...)`

This is the explicit player action that converts custodial money into physical money items.

It:

- debits the player’s custodial balance
- routes the payout through the supplied routing targets
- returns any undeliverable remainder back into custodial

Admin users can still use this path directly with `/economypol withdraw <amount>`.

For normal players, `/economypol withdraw` now withdraws the maximum exact amount that can fit into the player inventory and leaves the rest in custodial. This is intentionally more restrictive than generic economy behavior so players are encouraged to physicalize and manage carried money.

### Depositing To a Player

When a player account is credited:

- if the player is online, money is materialized and routed through the configured order
- any remainder is credited to custodial
- if the player is offline and has a frozen offline ender-wallet snapshot, the snapshot is credited
- if the player is offline and has no usable snapshot, the amount is credited to custodial

### Self Deposit

`/economypol deposit <amount|all>` removes physical money from the player’s live sources and stores it in custodial.

This command is currently gated behind `economypol.admin`.

### Shared Account Behavior

Shared accounts are ledger-only.

They support:

- direct custodial deposits
- direct custodial withdrawals
- owner/member relationships
- reservations

This is the correct model for towns, nations, server treasuries, and other non-player entities.

## Handling of Non-Player vs Player Accounts

`economy_balances` is EconomyPol's per-account database balance table.

For every `account_id`, it stores:

- `available_balance`
- `reserved_balance`

This table is the source of truth for all database-backed balance movement. Reservations, custodial credits, custodial withdrawals, and shared-account deposits or withdrawals all flow through it.

### Non-Player Accounts

For non-player accounts, meaning shared accounts such as:

- towns
- nations
- server treasuries
- admin-created banks

`economy_balances` is the actual balance.

That means:

- the shared account's money lives entirely in the database
- `available_balance` is the spendable bank balance
- `reserved_balance` is money temporarily locked for an in-flight operation
- shared accounts do not have a physical-inventory money state

So when TNE shared accounts are migrated into EconomyPol, their balances are meant to land in `economy_balances`.

### Player Accounts

For player accounts, `economy_balances` means something different.

It is the player's custodial balance, not the player's normal carried money.

Player money is intentionally split across multiple states:

- live physical money in inventory/offhand/ender chest
- frozen offline ender-wallet snapshot value
- custodial database money in `economy_balances`

Important current rule:

- player `spendable` balance does **not** include custodial

So a player can have money in `economy_balances` and still be unable to spend through normal Vault/player withdraw flows until they explicitly materialize that money back into physical form.

In practice, player custodial is used for cases like:

- explicit self-deposit of physical money into storage
- overflow when incoming money or returned change does not fully fit physically
- offline fallback credits when the managed offline wallet cannot be used
- temporary reservation during custodial-to-physical withdrawal

### Practical Effect

If you populate `economy_balances` for a shared account, you are setting that bank's real balance.

If you populate `economy_balances` for a player account, you are giving that player custodial stored value, not money directly in their inventory.

That distinction is intentional and is central to how EconomyPol separates non-player ledger balances from player-held physical money.

## Routing

EconomyPol has one canonical routing order:

- `INVENTORY`
- `ENDER_CHEST`
- `CUSTODIAL_ACCOUNT`

Current default:

```yaml
routing:
  change-overflow-policy: CUSTODIAL
```

For passive incoming money and returned change, players can suppress the early stages of that order with `/economypol paymentdelivery`:

- `default`
  - `INVENTORY -> ENDER_CHEST -> CUSTODIAL_ACCOUNT`
- `skip_inventory`
  - `ENDER_CHEST -> CUSTODIAL_ACCOUNT`
- `skip_inventory_and_enderchest`
  - `CUSTODIAL_ACCOUNT`

This only affects passive incoming delivery and returned change. It does not affect explicit `/economypol withdraw`, which remains inventory-only by design because the player is actively choosing to physicalize money.

Returned change from live-money spends also has a configurable overflow policy:

- `FAIL`
  - if physical change cannot fully fit, the spend is canceled and the original physical money state is restored
  - stricter and more physical
  - can expose compatibility bugs in third-party plugins that pre-check balance and then ignore failed withdraws
- `CUSTODIAL`
  - if physical change cannot fully fit, only the unplaceable remainder is credited to player custodial
  - more compatible with Towny, shops, and other Vault consumers
  - means some returned change may become custodial money until the player explicitly withdraws it as physical cash

Routing applies to:

- online player credits
- returned change from live-money spends

Explicit `/economypol withdraw` is intentionally separate and inventory-only.

## Denominations

Denominations are exact integer conversions of the base unit.

EconomyPol validates that the denomination ladder is divisible, for example:

- `1`
- `9`
- `81`

Valid:

- `1 -> 9 -> 81`

Invalid:

- `1 -> 10 -> 81`

Materialization is highest-first.

Example:

- `100` base units with the default ladder becomes:
- `1 GOLD_BLOCK`
- `2 GOLD_INGOT`
- `1 GOLD_NUGGET`

Fractional money does not exist.

- all storage uses integers
- Vault/VaultUnlocked fractional digit metadata is hardcoded to `0`
- external decimal requests are normalized through `NumericalConsistencyService`
- the default policy is `TRUNCATE`, and operators can switch to `REJECT`

## Numeric Consistency

EconomyPol centralizes decimal-to-integer behavior in `NumericalConsistencyService`.

This service is responsible for:

- converting `double` and `BigDecimal` API amounts into integer base units
- converting integer balances back into `double` or `BigDecimal`
- formatting non-whole external values consistently
- applying the operator-configured decimal policy

Current supported policies:

- `REJECT`
  - non-whole values fail with an error
  - example: `5.5` is rejected
  - this is the safest mode for a strict discrete item economy
- `TRUNCATE`
  - non-whole values are truncated toward zero
  - example: `5.9 -> 5`, `-5.9 -> -5`

The default config is:

```yaml
numeric:
  decimal-handling: TRUNCATE
```

Notes:

- `TRUNCATE` always truncates toward zero
- even when decimals are truncated at the API boundary, the stored economy remains integer-only

## Player Balance Semantics

The balance shown by EconomyPol is intentionally split.

`PlayerBalanceView` includes:

- spendable
- custodial available
- custodial reserved
- live money
- frozen ender wallet
- locked state

Current `spendable` for players is:

- `live money + frozen ender wallet`

It does **not** include custodial.

That means:

- a player can have money in custodial and still have `0` spendable
- they must withdraw custodial as physical money to use it in player-spend flows

This is deliberate and matches the current EarthPol design choice.

## Offline Ender Wallet In Detail

### On Quit

If managed ender wallet is enabled:

- top-level ender chest money is counted
- a snapshot row is written with state `FROZEN`

### While Offline

If a frozen snapshot exists:

- offline spends draw from that snapshot
- offline credits add to that snapshot

If no usable snapshot exists:

- offline player spending fails
- offline credits fall back to custodial

### On Join

The player is money-locked while sync runs.

During sync:

- snapshot state becomes `SYNCING`
- the current top-level ender chest contents are cloned
- a theoretical final chest layout is computed from that clone
- only the frozen snapshot value is materialized back into top-level ender chest slots
- existing top-level money items are cleared and replaced as part of that planned layout
- overflow or malformed leftovers are moved to custodial
- the snapshot row is deleted after success

If join sync fails after the snapshot was moved to `SYNCING`:

- the original top-level ender chest contents are restored
- the original `FROZEN` snapshot row is restored
- the restored frozen snapshot is safe while the player is still online because online player balance and spending ignore frozen snapshots
- that restored snapshot acts as a dormant recovery record until the player logs out again or a later sync succeeds

If the server had an unclean shutdown:

- stale syncing snapshots are marked `DISABLED_UNCLEAN`
- startup logs emit severe entries describing each quarantined snapshot row
- offline ender-wallet behavior is disabled until the player returns

## Notifications

EconomyPol has a dedicated `NotificationService`.

It sends formatted messages for:

- incoming money routed to custodial
- withdraw overflow retained in custodial
- ender-wallet overflow moved to custodial
- custodial balance reminders
- offline custodial credits
- change routed to custodial because not all physical change fit
- strict spend failures caused by not enough room to return change

Offline notifications are persisted in the database and delivered on next login.

Notification text is now backed by EarthPolLib `TranslationService`.

- bundled locale files live under `src/main/resources/translations`
- on first boot, EarthPolLib exports them into the plugin data folder and creates `translations.yml`
- NotificationService resolves translated notification titles and bodies through that translation layer before sending or replaying queued notifications

Current queue table:

- `economy_player_notifications`

## Commands

### Player Commands

Preferred shortcuts:

- `/bal`
- `/baltop`
- `/claim`
- `/compress`
- `/economypol paymentdelivery <default|skip_inventory|skip_inventory_and_enderchest>`
- `/economypol help`
- `/economypol`

Supported long forms and aliases:

- `/economypol balance`
- `/economypol bal`
- `/economypol balancetop`
- `/economypol baltop`
- `/economypol claim`
- `/economypol withdraw`
- `/economypol normalizewallet`
- `/economypol compress`
- `/ecopol ...`

Notes:

- `/claim` and `/economypol withdraw` are the player-facing overflow claim flow
- for normal players, `/claim` and `/economypol withdraw` mean “withdraw the maximum exact amount that fits in inventory”
- `/bal` and `/economypol balance` show the player-facing balance breakdown: spendable, inventory, ender chest, and overflow account
- `/baltop` and `/economypol balancetop` show the cached leaderboard built from online live money plus frozen offline ender-wallet snapshots
- `/compress` and `/economypol normalizewallet` normalize the current ender chest money layout
- `/economypol paymentdelivery` controls how passive incoming money and returned change are routed for that player
- `/economypol help` and `/economypol` show the stylized command help summary

### Admin-Gated Commands

- `/economypol deposit <amount|all>`
- `/economypol withdraw <amount>`

### Admin Commands

- `/economypol admin balance <player>`
- `/economypol admin check <report>`
- `/economypol admin cleanup towny-orphans`
- `/economypol admin reload`

Available database check reports:

- `accounts`
- `balances`
- `snapshots`
- `unclean-snapshots`
- `reservations`
- `notifications`
- `towny-accounts`
- `stats`

`/economypol admin check` prints a chat-friendly report and also writes the full report to `healthcheck.log`.

`towny-accounts` inspects the explicit `economy_towny_governments` binding table and Towny-managed shared accounts for:

- orphaned binding rows whose Towny government no longer exists
- binding/account UUID mismatches against Towny's current bank account UUID
- owner/name mismatches on the bound shared account row
- missing binding rows for current Towny governments
- legacy Towny-style shared-account rows left behind without a binding

`/economypol admin cleanup towny-orphans` removes only orphaned Towny binding rows and legacy unbound Towny-style shared-account rows. It does not attempt to rewrite UUID-mismatch rows or repair missing canonical rows automatically.

`/economypol admin reload` reloads only `config.yml`.

- `database.yml` and `currency.yml` are startup-only
- `logging.debug` does apply immediately on reload
- `logging.retention-policy` does apply immediately on reload

## Permissions

Current permissions in `plugin.yml`:

- `economypol.admin` - admin commands, default `op`

`economypol.admin` also gates the player self-deposit command and explicit amount withdraws.

## Configuration

EconomyPol now uses three config files.

### `database.yml`

Startup-only. Restart required after changes.

```yaml
host: 127.0.0.1
port: 3306
name: economypol
username: root
password: changeme
disable-plugin-on-failure: true
```

### `currency.yml`

Startup-only. Restart required after changes.

```yaml
singular-name: Gold Coin
plural-name: Gold Coins
denominations:
  - material: GOLD_NUGGET
    base-units: 1
  - material: GOLD_INGOT
    base-units: 9
  - material: GOLD_BLOCK
    base-units: 81
```

### `config.yml`

Generated into the plugin data folder and maintained through EarthPolLib `ReloadableConfigHandler`.

- this is the only EconomyPol config file reloaded by `/economypol admin reload`
- missing keys are repopulated from code defaults
- malformed scalar/list values fall back to defaults and are logged during reload

Default runtime config:

```yaml
numeric:
  decimal-handling: TRUNCATE

routing:
  change-overflow-policy: CUSTODIAL

wallet:
  managed-ender-wallet-enabled: true
  include-live-player-inventory: true
  include-live-ender-chest: true

cache:
  balancetop-ttl-seconds: 60

logging:
  debug: false
  retention-policy: MONTHLY
```

### Reloadability

EconomyPol is suitable for selective runtime config reloads, not full blanket reloadability.

Safe runtime reload targets in `config.yml`:

- `numeric.*`
- `routing.change-overflow-policy`
- `wallet.*`
- `cache.*`
- `logging.debug`
- `logging.retention-policy`

Restart-only settings:

- everything in `database.yml`
- everything in `currency.yml`

Why the limit exists:

- database settings define startup database wiring
- currency settings define denomination materialization and adapter metadata

So the intended model is:

- use `/economypol admin reload` for runtime behavior changes
- restart the server for database or currency changes

### Important Settings

#### `wallet.managed-ender-wallet-enabled`

- enables the quit snapshot / offline wallet / join restore system

#### `wallet.include-live-player-inventory`

- includes live player inventory and offhand in spendable live money

#### `wallet.include-live-ender-chest`

- includes top-level ender chest contents in spendable live money

#### `numeric.decimal-handling`

- controls how Vault and VaultUnlocked decimal inputs are converted into integer base units
- `REJECT` fails on non-whole values
- `TRUNCATE` truncates toward zero
- current default is `TRUNCATE`

#### `routing.change-overflow-policy`

- controls what happens when a live-money spend must make change and the physical change does not fully fit
- `FAIL`
  - restores the original physical money state and cancels the transaction
  - stricter and more physical
  - may expose compatibility bugs in plugins that ignore failed withdraws after a successful balance check
- `CUSTODIAL`
  - credits only the unplaceable part of the returned change to player custodial
  - more compatible with Towny, shops, and other Vault consumers
  - means some returned change becomes non-physical until explicitly withdrawn

#### `cache.balancetop-ttl-seconds`

- controls how long the cached `/economypol balancetop` leaderboard stays fresh
- when stale, the next request rebuilds the cache by scanning all online players plus frozen offline ender-wallet snapshots
- player custodial balances are not part of this leaderboard
- if a rebuild is already running, later requesters are queued and all receive the rebuilt snapshot when it completes

#### `logging.debug`

- applies immediately on `/economypol admin reload`
- toggles debug emission on the operations logger

#### `logging.retention-policy`

- applies immediately on `/economypol admin reload`
- controls log cleanup for the operations, audit, and healthcheck loggers
- supported values:
  - `WEEKLY`
  - `MONTHLY`
  - `NEVER`

## Database Schema

Current migrations:

- `V1__init.sql`

Schema conventions:

- UUID columns use MariaDB native `UUID`
- time columns use `TIMESTAMP(3)`
- the schema is written for MariaDB, which matches EarthPolLib's current database support

Main tables:

- `economy_players`
  - registered player identity rows keyed by `player_uuid`
  - refreshed automatically when a player joins
  - `username` stores the latest known player name
  - rows seeded earlier from UUID-only integrations are corrected on the next real join
  - stores each player's `incoming_payment_delivery_preference`
- `economy_accounts`
  - player and shared accounts
  - stores shared account and player account identity only
  - existing player account names are refreshed from `Player#getName()` on join when they differ
- `economy_towny_governments`
  - explicit Towny government to bank-account bindings
  - `government_uuid` is the raw Towny town or nation UUID and the row primary key
  - `account_id` is the EconomyPol shared account bound to that government
  - `bank_account_uuid` is the Towny bank UUID Towny actually uses when calling EconomyPol
- `economy_account_members`
  - shared account member relationships
- `economy_balances`
  - available and reserved balances
- `economy_ender_wallet_snapshots`
  - managed offline ender-wallet state
- `economy_reservations`
  - holds/escrow state
- `economy_ledger_entries`
  - audit trail of balance changes
- `economy_player_notifications`
  - queued offline player notifications

## Logging

EconomyPol uses multiple logs:

- operations log
- audit log
- healthcheck log

### Operations Log

General operational status and warnings.

Examples:

- startup/shutdown
- runtime warnings
- queued notification delivery info

### Audit Log

Immutable-style action logging.

Examples:

- balance changes
- deposits/withdrawals
- reservation lifecycle
- ender-wallet freeze/debit/credit/sync

### Healthcheck Log

Written by admin database checks.

Every `/economypol admin check <report>` run records:

- the report name
- exact run time
- duration
- string form of the full report

## Database Health Checks

The health-check service is intentionally read-only.

It never repairs rows automatically.

Current checks look for malformed or inconsistent rows in:

- accounts
- balances
- snapshots
- unclean snapshots
- reservations
- notifications

The `stats` report also summarizes:

- account counts
- custodial totals
- snapshot totals
- active reservations
- pending player notifications
- ledger entry count

Important:

- database totals do not include live physical money in player inventories

## Integration Surfaces

### VaultUnlocked v2

This is the primary modern integration surface.

EconomyPol registers:

- `net.milkbowl.vault2.economy.Economy`

It supports:

- UUID-based accounts
- shared accounts
- integer-only money handling with configurable decimal coercion at the API boundary
- Towny-compatible shared-account access when Towny is running in modern mode

### Legacy Vault

EconomyPol also registers:

- `net.milkbowl.vault.economy.Economy`

This exists for older plugins that still depend on the original Vault API.

Legacy Vault now uses the same numeric policy as VaultUnlocked through `NumericalConsistencyService`, so decimal behavior is consistent across both provider surfaces.
At runtime the VaultUnlocked plugin is still discovered by Bukkit as `Vault`, so EconomyPol declares `depend: [Vault]`. VaultUnlocked is required because EconomyPol registers both the v2 and legacy economy APIs; classic Vault alone is insufficient.
EconomyPol also declares `loadbefore: [Towny, QuickShop-Hikari]` so the provider is registered before those plugins initialize.

### EconomyPolAPI

EconomyPol exposes a native Bukkit service for direct integration with the plugin. If possible,
external plugins should integrate directly with EconomyPol for the best experience, however VaultUnlocked
and Vault will still remain supported.

Capabilities include:

- explicit player vs shared-account operations
- detailed player balance view and custodial access
- shared account management
- incoming payment delivery preference access
- reservation lifecycle
- managed ender-wallet access
- denomination helpers
- structured `MoneyOperationResult.failureReason()` values for native integrations

`EconomyPolAPI` is still the single Bukkit service entrypoint, but it is now split internally into focused account, player, reservation, ender-wallet, and denomination sub-interfaces.
API clients are now caller-bound through `EconomyPolAPI.resolve(plugin)`, which lets EconomyPol log which plugin is making each native API call.
Native API calls do not implicitly create missing player or shared-account rows outside the explicit `registerPlayer(...)`, `ensurePlayerAccount(...)`, and `createSharedAccount(...)` paths. Integrations should create or check accounts first and treat missing-account failures as real integration errors.
Convenience overloads also exist for `OfflinePlayer` and `Player` on the player-facing native API methods. UUID-based methods remain the canonical identity surface.

Example:

```java
EconomyPolAPI api = EconomyPolAPI.resolve(this)
        .orElseThrow(() -> new IllegalStateException("EconomyPolAPI not available"));

long spendable = api.getPlayerSpendableBalance(playerUuid);
long custodial = api.getCustodialAvailable(playerUuid);
PlayerBalanceView view = api.getPlayerBalanceView(playerUuid);
long townBalance = api.getSharedAccountBalance(townAccountUuid);

MoneyOperationResult result = api.withdrawFromPlayerAccount(playerUuid, 10L, "CUSTOM_MARKET_BUY");
if (!result.success() && result.failureReason() == MoneyOperationFailureReason.NOT_ENOUGH_ROOM_FOR_CHANGE) {
    // Handle a strict physical-economy change-space failure explicitly.
}
```

## Towny Compatibility

Current Towny compatibility is through VaultUnlocked/Vault service registration plus an explicit Towny binding layer in EconomyPol.

That means:

- Towny can use EconomyPol as an economy provider
- Towny towns and nations are synchronized into EconomyPol shared accounts
- EconomyPol stores explicit Towny government bindings with:
  - raw government UUID
  - current Towny bank account UUID
  - government type
  - cached government and bank names
- Towny create/rename/delete lifecycle events are synchronized into EconomyPol
- VaultUnlocked UUID/shared-account support is available

### How Towny Bindings Work

Towny has two relevant identities for a government-backed bank:

- the raw Towny government UUID for the `Town` or `Nation`
- the Towny bank account UUID that Towny passes to the economy provider

The bank account UUID is the economy-facing identity. The bank account name changes when the town or nation is renamed, but the UUID normally stays stable unless Towny's own UUID policy or repair tooling changes it.

EconomyPol stores both explicitly in `economy_towny_governments`.

Important columns:

- `government_uuid`
  - the real Towny object UUID
  - primary key of the binding row
- `government_type`
  - `TOWN` or `NATION`
- `account_id`
  - the EconomyPol shared account bound to that government
- `bank_account_uuid`
  - the UUID Towny currently uses when calling EconomyPol
- `government_name`
  - cached Towny town or nation name
- `bank_account_name`
  - cached Towny bank account name such as `town-BustunTown`

In the healthy state:

- `account_id == bank_account_uuid`

EconomyPol stores both fields separately so it can detect mismatches instead of silently rewriting the binding.

### Towny Synchronization Flow

When Towny enables:

- EconomyPol's `TownyBootstrapListener` notices the `PluginEnableEvent`
- `TownyService` activates the Towny integration backend
- EconomyPol synchronizes all current towns and nations
- each government is reconciled through `syncGovernment(...)`

For each town or nation, sync:

- reads the raw government UUID, current Towny bank account UUID, government name, and bank account name
- ensures the matching EconomyPol shared account exists for the bank account UUID
- updates the shared account name to match Towny
- sets the shared account owner metadata to the bank account UUID
- upserts the `economy_towny_governments` binding row

Towny lifecycle events then keep that binding current:

- new town or nation -> refresh binding
- rename town or nation -> refresh cached names and shared-account name
- delete town or nation -> delete the shared account and cascade-delete the binding row

### Bank UUID Mismatches

A bank-account UUID mismatch means the binding row says one EconomyPol account is bound to a Towny government, but Towny currently derives or uses a different bank UUID for that same government.

Common causes:

- Towny's NPC/account UUID version policy changed
- a Towny admin UUID repair/reset path changed the bank UUID
- stale legacy Towny-style shared-account rows exist from before explicit bindings were added
- manual database edits or old buggy rows

Current EconomyPol behavior is intentionally conservative:

- `syncGovernment(...)` logs a severe mismatch and refuses to silently rebind the government to a different account
- `/economypol admin check towny-accounts` reports the mismatch
- `/economypol admin cleanup towny-orphans` removes only true orphan rows and legacy unbound rows, not live UUID mismatches

This avoids accidentally pointing a Towny government at the wrong balance history.

What is **not** implemented yet:

- Towny server-account binding
- automatic repair of UUID-mismatch rows caused by historic legacy data or Towny UUID-policy changes

So the current state is:

- Towny town/nation lifecycle integration is implemented
- Towny server-account binding is still future work

## EarthPol Integration

EconomyPol shades `com.earthpol:earthpollib:1.0.1` from the Bitworks Nexus releases repository. EarthPolLib is a library and does not need to be installed as a separate server plugin.

Database startup uses EarthPolLib's `SchemaMigrator` before any economy repositories or providers are initialized. The bundled SQL migration is unchanged; the migrator can import matching successful entries from the legacy per-plugin Flyway history. Keep existing database contents and migration history when upgrading.

EconomyPol loads after VaultUnlocked and before Towny and QuickShop-Hikari. Towny remains optional, with its government bindings initialized when Towny enables.

Kernel currently has its own economy commands and balance store. This import does not migrate Kernel balances or redirect its commands. Complete that integration before using both economies together on the same server.

## Build and Runtime Requirements

- Java 21
- Paper `1.21.11` (the compile target)
- VaultUnlocked required at runtime (Bukkit plugin name `Vault`)
- MariaDB through the configured JDBC layer
- Towny `0.102.x` optional but supported

Build commands used during development:

```powershell
mvn -DskipTests compile
mvn test
mvn -DskipTests package
```

## Design Tradeoffs and Current Caveats

The current implementation intentionally favors explicit, understandable money flows over invisible convenience.

Important caveats:

- Player custodial money is not auto-spendable.
- Only top-level inventories are scanned for money.
- Offline player spending only works through the frozen ender-wallet snapshot.
- Notifications are queued for offline players, but there is not yet a full inbox/history UI.
- Health checks report problems but do not repair them.
- Towny bank UUID mismatches are reported and blocked from silent rebinding instead of being auto-fixed.
