package dev.amethysttools;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Only loaded when Vault is installed. */
public class EconomyHook {

    private Economy economy;

    public boolean setup() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public void deposit(OfflinePlayer player, double amount) {
        economy.depositPlayer(player, amount);
    }

    public String format(double amount) {
        return economy.format(amount);
    }
}
