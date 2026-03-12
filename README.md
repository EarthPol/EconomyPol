# EconomyPol

EconomyPol is a Paper economy plugin for EarthPol built around a discrete, item-based commodity currency.

It is designed to let players hold and move real money items while still providing:

- VaultUnlocked v2 compatibility for modern plugins and Towny
- Legacy Vault compatibility for older plugins
- Database-backed custodial storage and shared accounts
- A managed offline ender-wallet snapshot for offline spending support
- Audit logging, health checks, and a native API for EconomyPol-specific behavior

This README describes the plugin as it is currently implemented.

## Status

EconomyPol is already usable as a database-backed commodity economy core, but it is still early-stage.

Implemented today:

- Discrete denomination-based currency
- Player accounts and shared accounts
- Custodial balances and reservations
- VaultUnlocked v2 provider
- Legacy Vault provider
- Managed offline ender-wallet snapshots
- Persistent offline player notifications
- Database health checks
- Native `EconomyPolAPI`

Not implemented yet:

- Towny-specific shared-account binding/sync layer
- PlaceholderAPI support
- GUI banking/teller interfaces
- Admin repair commands for malformed data
- Rich transaction history commands

## Core Model

EconomyPol separates money into three states.

### 1. Live Physical Money

This is real item money held by a player in top-level inventory slots.

Current implementation counts:

- player inventory contents
- offhand
- ender chest contents

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

- players can explicitly deposit physical money into custodial if self-deposit is enabled
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

For shared accounts:

- debits custodial balance

#### `withdrawCustodialAsPhysicalMoney(...)`

This is the explicit player action that converts custodial money into physical money items.

It:

- debits the player’s custodial balance
- routes the payout through configured destinations
- returns any undeliverable remainder back into custodial

This is what `/economypol withdraw <amount>` does.

### Depositing To a Player

When a player account is credited:

- if the player is online, money is materialized and routed through the configured order
- any remainder is credited to custodial
- if the player is offline and has a frozen offline ender-wallet snapshot, the snapshot is credited
- if the player is offline and has no usable snapshot, the amount is credited to custodial

### Self Deposit

`/economypol deposit <amount|all>` removes physical money from the player’s live sources and stores it in custodial.

This is controlled by:

- `players.allow-self-deposit`

### Shared Account Behavior

Shared accounts are ledger-only.

They support:

- direct custodial deposits
- direct custodial withdrawals
- owner/member relationships
- reservations

This is the correct model for towns, nations, server treasuries, and other non-player entities.

## Routing

Online payouts use a configurable routing order:

- `INVENTORY`
- `ENDER_CHEST`
- `CUSTODIAL_ACCOUNT`

Current default:

```yaml
routing:
  order:
    - INVENTORY
    - ENDER_CHEST
    - CUSTODIAL_ACCOUNT
```

Validation rules:

- routing order must not be empty
- targets must not repeat
- `CUSTODIAL_ACCOUNT` must be last

Routing applies to:

- online player credits
- explicit custodial withdrawal as physical money

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
- the default policy is `REJECT`, but operators can switch to `ROUND` or `TRUNCATE`

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
- `ROUND`
  - non-whole values are rounded using the configured Java `RoundingMode`
  - example with `HALF_UP`: `5.5 -> 6`
  - example with `FLOOR`: `5.9 -> 5`, `-5.1 -> -6`
- `TRUNCATE`
  - non-whole values are truncated toward zero
  - example: `5.9 -> 5`, `-5.9 -> -5`

The default config is:

```yaml
numeric:
  decimal-handling: REJECT
  rounding-mode: HALF_UP
```

Notes:

- `rounding-mode` is only used when `decimal-handling` is `ROUND`
- `TRUNCATE` always truncates toward zero
- even when decimals are rounded or truncated at the API boundary, the stored economy remains integer-only

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

Offline notifications are persisted in the database and delivered on next login.

Current queue table:

- `economy_player_notifications`

## Commands

### Player Commands

- `/economypol balance`
- `/economypol balancetop`
- `/economypol baltop`
- `/economypol deposit <amount|all>`
- `/economypol withdraw <amount>`
- `/economypol normalizewallet`
- `/baltop`

Notes:

- `/economypol withdraw` means “withdraw custodial as physical money”
- `/economypol deposit` means “store physical money into custodial”
- `/economypol balancetop` shows the cached top player balances from online live money plus offline frozen ender-wallet snapshots
- `/economypol baltop` and `/baltop` are aliases for the same cached leaderboard
- `/economypol normalizewallet` normalizes the current ender chest money layout

### Admin Commands

- `/economypol admin balance <player>`
- `/economypol admin check <report>`

Available database check reports:

- `accounts`
- `balances`
- `snapshots`
- `unclean-snapshots`
- `reservations`
- `notifications`
- `stats`

`/economypol admin check` prints a chat-friendly report and also writes the full report to `healthcheck.log`.

## Permissions

Current permissions in `plugin.yml`:

- `economypol.admin` - admin commands, default `op`

Player commands are public by design.

## Configuration

Current default config:

```yaml
database:
  host: 127.0.0.1
  port: 3306
  name: economypol
  username: root
  password: changeme
  disable-plugin-on-failure: true

currency:
  singular-name: Gold Coin
  plural-name: Gold Coins
  denominations:
    - material: GOLD_NUGGET
      base-units: 1
    - material: GOLD_INGOT
      base-units: 9
    - material: GOLD_BLOCK
      base-units: 81

numeric:
  decimal-handling: REJECT
  rounding-mode: HALF_UP

players:
  allow-self-deposit: false
  allow-external-credit: true
  allow-self-withdraw: true

routing:
  order:
    - INVENTORY
    - ENDER_CHEST
    - CUSTODIAL_ACCOUNT

wallet:
  managed-ender-wallet-enabled: true
  include-live-player-inventory: true
  include-live-ender-chest: true

cache:
  balancetop-ttl-seconds: 60

logging:
  debug: false
  audit-log-name: audit
  operations-log-name: operations
```

### Important Settings

#### `players.allow-self-deposit`

- if `false`, players cannot manually store their own physical money in custodial
- external credits can still place money into custodial when required

#### `players.allow-self-withdraw`

- controls explicit custodial-to-physical withdrawal

#### `wallet.managed-ender-wallet-enabled`

- enables the quit snapshot / offline wallet / join restore system

#### `wallet.include-live-player-inventory`

- includes live player inventory and offhand in spendable live money

#### `wallet.include-live-ender-chest`

- includes top-level ender chest contents in spendable live money

#### `numeric.decimal-handling`

- controls how Vault and VaultUnlocked decimal inputs are converted into integer base units
- `REJECT` fails on non-whole values
- `ROUND` rounds using `numeric.rounding-mode`
- `TRUNCATE` truncates toward zero

#### `numeric.rounding-mode`

- the Java `RoundingMode` used when `numeric.decimal-handling` is `ROUND`
- default is `HALF_UP`

#### `cache.balancetop-ttl-seconds`

- controls how long the cached `/economypol balancetop` leaderboard stays fresh
- when stale, the next request rebuilds the cache by scanning all online players plus frozen offline ender-wallet snapshots
- player custodial balances are not part of this leaderboard

## Database Schema

Current migrations:

- `V1__init.sql`

Main tables:

- `economy_accounts`
  - player and shared accounts
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

### Legacy Vault

EconomyPol also registers:

- `net.milkbowl.vault.economy.Economy`

This exists for older plugins that still depend on the original Vault API.

Legacy Vault now uses the same numeric policy as VaultUnlocked through `NumericalConsistencyService`, so decimal behavior is consistent across both provider surfaces.

### EconomyPolAPI

EconomyPol exposes a native Bukkit service for direct integration with the plugin. If possible,
external plugins should integrate directly with EconomyPol for the best experience, however VaultUnlocked
and Vault will still remain supported.

Capabilities include:

- detailed player balance view
- custodial access
- shared account management
- reservation lifecycle
- managed ender-wallet access
- denomination helpers

Example:

```java
EconomyPolAPI api = EconomyPolAPI.resolve()
        .orElseThrow(() -> new IllegalStateException("EconomyPolAPI not available"));

long custodial = api.getCustodialAvailable(playerUuid);
PlayerBalanceView view = api.getPlayerBalanceView(playerUuid);
```

## Towny Compatibility

Current Towny compatibility is through VaultUnlocked and Vault service registration.

That means:

- Towny can use EconomyPol as an economy provider
- shared accounts already exist as a plugin concept
- VaultUnlocked UUID/shared-account support is available

What is **not** implemented yet:

- a Towny-specific binding/sync layer for town, nation, and server accounts
- persistent metadata that says a shared account is specifically a Towny town or nation
- Towny rename/create/delete synchronization

So the current state is:

- Towny compatibility path exists
- Towny-specific account lifecycle integration is still a future addition

## Build and Runtime Requirements

- Java 21
- Paper `1.21.x`
- VaultUnlocked available at runtime for the standard EarthPol deployment
- MySQL/MariaDB-compatible database access through the configured JDBC layer

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
