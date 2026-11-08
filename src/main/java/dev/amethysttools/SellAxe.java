package dev.amethysttools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Sell Axe (Vault) + Haste while holding an amethyst tool. */
public class SellAxe implements Listener {

    private final AmethystTools plugin;
    private EconomyHook economy;

    public SellAxe(AmethystTools plugin) {
        this.plugin = plugin;
    }

    private static class SellHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ haste

    public void startHaste() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyHaste, 20L, 20L);
    }

    private void applyHaste() {
        if (!plugin.getConfig().getBoolean("haste.enabled", true)) return;
        int level = Math.max(1, plugin.getConfig().getInt("haste.level", 2));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.getToolType(p.getInventory().getItemInMainHand()) == null) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, level - 1, true, false, true));
        }
    }

    // ------------------------------------------------------------------ vault

    private EconomyHook getEconomy() {
        if (economy != null) return economy;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        try {
            EconomyHook hook = new EconomyHook();
            if (hook.setup()) economy = hook;
        } catch (NoClassDefFoundError ignored) {
        }
        return economy;
    }

    private double priceOf(Material m) {
        return plugin.getConfig().getDouble("sell-prices." + m.name(), 0);
    }

    /** Removes sellable items from the inventory and returns the total value. */
    private double sellInventory(Inventory inv, int[] count) {
        double total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            double price = priceOf(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count[0] += it.getAmount();
            inv.setItem(i, null);
        }
        return total;
    }

    // ------------------------------------------------------------------ interact

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!"sellaxe".equals(plugin.getToolType(tool))) return;
        if (!p.hasPermission("amethysttools.use")) return;

        if (plugin.isExpired(tool)) {
            p.getInventory().setItemInMainHand(null);
            plugin.send(p, plugin.msg("expired"));
            e.setCancelled(true);
            return;
        }

        EconomyHook eco = getEconomy();
        if (eco == null) {
            plugin.send(p, plugin.msg("no-economy"));
            e.setCancelled(true);
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block b = e.getClickedBlock();
            if (b == null) return;
            Material t = b.getType();
            if (t != Material.CHEST && t != Material.TRAPPED_CHEST && t != Material.BARREL) return;
            if (!(b.getState() instanceof Container container)) return;

            e.setCancelled(true);
            int[] count = {0};
            double total = sellInventory(container.getInventory(), count);
            if (total <= 0) {
                plugin.send(p, plugin.msg("nothing-to-sell"));
                return;
            }
            eco.deposit(p, total);
            plugin.send(p, plugin.msg("sold")
                    .replace("%items%", String.valueOf(count[0]))
                    .replace("%money%", eco.format(total)));
        } else {
            e.setCancelled(true);
            SellHolder holder = new SellHolder();
            holder.inventory = Bukkit.createInventory(holder, 54,
                    Component.text("Amethyst Sell Axe", TextColor.color(0xB026FF)));
            p.openInventory(holder.inventory);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellHolder)) return;
        Player p = (Player) e.getPlayer();
        Inventory inv = e.getInventory();

        EconomyHook eco = getEconomy();
        double total = 0;
        int[] count = {0};
        if (eco != null) total = sellInventory(inv, count);

        // give back everything that was not sold
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            p.getInventory().addItem(it).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        inv.clear();

        if (eco == null) {
            plugin.send(p, plugin.msg("no-economy"));
        } else if (total > 0) {
            eco.deposit(p, total);
            plugin.send(p, plugin.msg("sold")
                    .replace("%items%", String.valueOf(count[0]))
                    .replace("%money%", eco.format(total)));
        } else {
            plugin.send(p, plugin.msg("nothing-to-sell"));
        }
    }
}
