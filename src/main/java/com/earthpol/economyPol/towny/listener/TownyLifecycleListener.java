package com.earthpol.economyPol.towny.listener;

import com.earthpol.economyPol.towny.TownyService;
import com.palmergames.bukkit.towny.event.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.NewNationEvent;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.RenameNationEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TownyLifecycleListener implements Listener {

    private final TownyService townyService;

    public TownyLifecycleListener(TownyService townyService) {
        this.townyService = townyService;
    }

    @EventHandler
    public void onNewTown(NewTownEvent event) {
        townyService.refreshTown(event.getTown().getUUID());
    }

    @EventHandler
    public void onRenameTown(RenameTownEvent event) {
        townyService.refreshTown(event.getTown().getUUID());
    }

    @EventHandler
    public void onDeleteTown(DeleteTownEvent event) {
        townyService.deleteTown(event.getTownUUID(), event.getTownName());
    }

    @EventHandler
    public void onNewNation(NewNationEvent event) {
        townyService.refreshNation(event.getNation().getUUID());
    }

    @EventHandler
    public void onRenameNation(RenameNationEvent event) {
        townyService.refreshNation(event.getNation().getUUID());
    }

    @EventHandler
    public void onDeleteNation(DeleteNationEvent event) {
        townyService.deleteNation(event.getNationUUID(), event.getNationName());
    }
}
