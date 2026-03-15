package com.earthpol.economyPol;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.database.flyway.FlywaySupport;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.earthPolLib.translation.TranslationService;
import com.earthpol.economyPol.economy.api.EconomyPolAPI;
import com.earthpol.economyPol.economy.api.EconomyPolApiProvider;
import com.earthpol.economyPol.economy.command.EconomyCommand;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.listener.EnderChestLockListener;
import com.earthpol.economyPol.economy.listener.PlayerLifecycleListener;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.EnderWalletRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.NotificationRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.support.NumericalConsistencyService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import com.earthpol.economyPol.economy.service.support.ReservationService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import com.earthpol.economyPol.towny.TownyService;
import com.earthpol.economyPol.towny.listener.TownyBootstrapListener;
import com.earthpol.economyPol.towny.repository.TownyGovernmentRepository;
import com.earthpol.economyPol.vault.EconomyVaultAdapter;
import com.earthpol.economyPol.vault.VaultUnlockedEconomyAdapter;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

// TODO: Add built-in TNE migrator, or write a custom script for it. In python perhaps?
// todo: Better logging, more logging configuration
// todo: touch up UI and notification delivery
// TODO: More testing.
// TODO: Tidy up database schema, especially the economy_accounts.

public final class EconomyPol extends JavaPlugin {

    private PluginSettings settings;
    private EconomyLoggers loggers;
    private DatabaseService dbService;
    private AccountRepository accountRepository;
    private PlayerRepository playerRepository;
    private FundsRepository fundsRepository;
    private EnderWalletRepository enderWalletRepository;
    private NotificationRepository notificationRepository;
    private TownyGovernmentRepository townyGovernmentRepository;
    private DenominationService denominationService;
    private NumericalConsistencyService numericalConsistencyService;
    private LiveMoneyService liveMoneyService;
    private SchedulerService schedulerService;
    private TranslationService translationService;
    private NotificationService notificationService;
    private PlayerMoneyLockService playerMoneyLockService;
    private EnderWalletService enderWalletService;
    private ReservationService reservationService;
    private DatabaseCheckService databaseCheckService;
    private TownyService townyService;
    private EconomyService economyService;
    private EconomyPolAPI economyPolApi;
    private EconomyVaultAdapter vaultAdapter;
    private VaultUnlockedEconomyAdapter vaultUnlockedAdapter;
    private boolean uncleanBoot;

    public PluginSettings settings() {return settings;}
    public EconomyLoggers loggers() {return loggers;}
    public EnhancedLogger log() {return loggers.operations();}
    public EnhancedLogger audit() {return loggers.audit();}
    public EnhancedLogger healthcheck() {return loggers.healthcheck();}
    public DatabaseService dbService() {return dbService;}
    public EconomyService economyService() {return economyService;}
    public NumericalConsistencyService numericalConsistencyService() {return numericalConsistencyService;}
    public ReservationService reservationService() {return reservationService;}
    public DatabaseCheckService databaseCheckService() {return databaseCheckService;}
    public NotificationService notificationService() {return notificationService;}
    public SchedulerService schedulerService() {return schedulerService;}
    public TranslationService translationService() {return translationService;}
    public EconomyPolAPI api() {return economyPolApi;}
    public boolean isUncleanBoot() {return uncleanBoot;}

    @Override
    public void onEnable() {
        try {
            settings = PluginSettings.load(this);
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Failed to load EconomyPol configuration.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loggers = new EconomyLoggers(
                EnhancedLogger.create(this, "operations", settings.logging().debug()),
                EnhancedLogger.create(this, "audit", false),
                EnhancedLogger.create(this, "healthcheck", false)
        );
        loggers.applyRetentionPolicy(settings.logging().retentionPolicy());

        uncleanBoot = detectUncleanBoot();
        writeRuntimeMarker();

        initializeDatabase();
        if (dbService == null || !dbService.isRunning()) {
            return;
        }

        accountRepository = new AccountRepository(dbService, log(), audit());
        playerRepository = new PlayerRepository(dbService, log(), audit());
        fundsRepository = new FundsRepository(dbService, log(), audit());
        enderWalletRepository = new EnderWalletRepository(dbService, log(), audit());
        notificationRepository = new NotificationRepository(dbService, log(), audit());
        townyGovernmentRepository = new TownyGovernmentRepository(dbService, log(), audit());
        logRecoveredUncleanSnapshots(enderWalletRepository.markStaleSnapshotsDisabled());

        denominationService = new DenominationService(settings.currency(), log());
        numericalConsistencyService = new NumericalConsistencyService(settings, denominationService);
        liveMoneyService = new LiveMoneyService(denominationService, settings);
        schedulerService = new SchedulerService(this, log());
        translationService = new TranslationService(this, EconomyPol.class);
        translationService.load();
        notificationService = new NotificationService(
                denominationService,
                notificationRepository,
                schedulerService,
                translationService,
                log()
        );
        playerMoneyLockService = new PlayerMoneyLockService();
        reservationService = new ReservationService(fundsRepository, audit());
        townyService = new TownyService();
        enderWalletService = new EnderWalletService(
                this,
                enderWalletRepository,
                playerMoneyLockService,
                notificationService,
                schedulerService,
                audit(),
                uncleanBoot
        );
        databaseCheckService = new DatabaseCheckService(dbService, townyService);
        economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                log(),
                audit()
        );
        economyPolApi = new EconomyPolApiProvider(economyService, reservationService, enderWalletService, denominationService);

        vaultUnlockedAdapter = new VaultUnlockedEconomyAdapter(
                this,
                economyService,
                numericalConsistencyService,
                settings.currency(),
                log()
        );
        vaultAdapter = new EconomyVaultAdapter(
                this,
                economyService,
                numericalConsistencyService,
                settings.currency(),
                log()
        );
        getServer().getServicesManager().register(EconomyPolAPI.class, economyPolApi, this, ServicePriority.Highest);
        getServer().getServicesManager().register(net.milkbowl.vault2.economy.Economy.class, vaultUnlockedAdapter, this, ServicePriority.Highest);
        getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, vaultAdapter, this, ServicePriority.Highest);
        log().info("Registered EconomyPolAPI, VaultUnlocked v2, and legacy Vault economy providers.");

        registerListeners();
        registerCommands();
        log().info("EconomyPol enabled.");
    }

    @Override
    public void onDisable() {
        if (enderWalletService != null) {
            getServer().getOnlinePlayers().forEach(enderWalletService::snapshotOnQuit);
        }
        if (dbService != null) {
            try {
                dbService.getDB().shutdown();
            } catch (Exception exception) {
                if (loggers != null) {
                    log().severe("Failed to shutdown database cleanly.", exception);
                }
            }
        }
        deleteRuntimeMarker();
        if (loggers != null) {
            log().info("EconomyPol disabled.");
            loggers.close();
        }
    }

    private void initializeDatabase() {
        PluginSettings.DatabaseSettings database = settings.database();
        dbService = new DatabaseService(
                log(),
                this,
                database.username(),
                database.password(),
                database.name(),
                database.host(),
                database.port(),
                database.disablePluginOnFailure(),
                "EconomyPol-DB",
                "db/migration/economypol"
        );
        dbService.start();
        if (!dbService.isRunning()) {
            log().severe("Database did not start successfully.");
            return;
        }
        FlywaySupport.migrate(dbService.getDB(), this, java.util.List.of("db/migration/economypol"), log());
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(enderWalletService, economyService, notificationService),
                this
        );
        getServer().getPluginManager().registerEvents(new EnderChestLockListener(playerMoneyLockService), this);
        TownyBootstrapListener townyBootstrapListener = new TownyBootstrapListener(
                this,
                townyService,
                economyService,
                townyGovernmentRepository,
                log()
        );
        getServer().getPluginManager().registerEvents(townyBootstrapListener, this);
        townyBootstrapListener.registerIfTownyEnabled();
    }

    private void registerCommands() {
        PluginCommand economyCommand = getCommand("economypol");
        PluginCommand balanceTopCommand = getCommand("baltop");
        if (economyCommand == null) {
            throw new IllegalStateException("economypol command is missing from plugin.yml");
        }
        if (balanceTopCommand == null) {
            throw new IllegalStateException("baltop command is missing from plugin.yml");
        }
        EconomyCommand executor = new EconomyCommand(
                economyService,
                enderWalletService,
                databaseCheckService,
                townyService,
                settings,
                log(),
                healthcheck()
        );
        economyCommand.setExecutor(executor);
        economyCommand.setTabCompleter(executor);
        balanceTopCommand.setExecutor(executor);
        balanceTopCommand.setTabCompleter(executor);
    }

    private Path runtimeMarkerPath() {
        return getDataFolder().toPath().resolve("runtime.lock");
    }

    private boolean detectUncleanBoot() {
        return Files.exists(runtimeMarkerPath());
    }

    private void writeRuntimeMarker() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(runtimeMarkerPath(), Long.toString(System.currentTimeMillis()));
        } catch (IOException exception) {
            log().warn("Failed to write runtime marker: " + exception.getMessage());
        }
    }

    private void deleteRuntimeMarker() {
        try {
            Files.deleteIfExists(runtimeMarkerPath());
        } catch (IOException exception) {
            if (loggers != null) {
                log().warn("Failed to delete runtime marker: " + exception.getMessage());
            }
        }
    }

    private void logRecoveredUncleanSnapshots(List<EnderWalletSnapshot> recoveredSnapshots) {
        if (recoveredSnapshots.isEmpty()) {
            return;
        }
        log().severe("Startup recovery quarantined " + recoveredSnapshots.size() +
                " managed ender-wallet snapshot(s) left in SYNCING from a previous unclean shutdown. " +
                "They were marked DISABLED_UNCLEAN to prevent ambiguous offline wallet use. " +
                "Run '/economypol admin check unclean-snapshots' for details.");
        for (EnderWalletSnapshot snapshot : recoveredSnapshots) {
            log().severe("unclean-snapshot player=" + snapshot.playerUuid() +
                    " base_units=" + snapshot.baseUnits() +
                    " last_clean_sync_at=" + snapshot.lastCleanSyncAt());
        }
    }
}

