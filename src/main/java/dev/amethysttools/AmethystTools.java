package dev.amethysttools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class AmethystTools extends JavaPlugin implements Listener, TabExecutor {

    private static final List<String> TOOLS = List.of("pickaxe", "shovel", "axe", "hoe", "sellaxe");

    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART);

    private NamespacedKey toolKey;
    private NamespacedKey expireKey;
    private final Set<UUID> breaking = new HashSet<>();
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        toolKey = new NamespacedKey(this, "amethyst_tool");
        expireKey = new NamespacedKey(this, "amethyst_expire");
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::checkExpiry, 600L, 600L);
        SellAxe sellAxe = new SellAxe(this);
        Bukkit.getPluginManager().registerEvents(sellAxe, this);
        sellAxe.startHaste();
        var cmd = getCommand("amethyst");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    private void loadSettings() {
        reloadConfig();
        blacklist.clear();
        for (String s : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
            else getLogger().warning("Unknown blacklist material: " + s);
        }
    }

    // ------------------------------------------------------------------ items

    private ItemStack createTool(String type) {
        Material mat = switch (type) {
            case "pickaxe" -> Material.NETHERITE_PICKAXE;
            case "shovel" -> Material.NETHERITE_SHOVEL;
            case "hoe" -> Material.NETHERITE_HOE;
            default -> Material.NETHERITE_AXE;
        };
        String name = type.equals("sellaxe") ? "Sell Axe" : Character.toUpperCase(type.charAt(0)) + type.substring(1);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Amethyst " + name, TextColor.color(0xB026FF))
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Mythic Tool", 0xB026FF));
        lore.add(Component.empty());
        switch (type) {
            case "pickaxe" -> lore.add(line("Mines a 3x3 area", 0xD9A6FF));
            case "shovel" -> lore.add(line("Digs a 3x3 area", 0xD9A6FF));
            case "hoe" -> lore.add(line("Harvests & replants 3x3 crops", 0xD9A6FF));
            case "sellaxe" -> {
                lore.add(line("Right-click a chest to sell everything", 0xD9A6FF));
                lore.add(line("Right-click air to open the sell menu", 0xD9A6FF));
            }
            default -> lore.add(line("Chops down entire trees", 0xD9A6FF));
        }
        if (getConfig().getBoolean("timer.enabled", true)) {
            long minutes = getConfig().getLong("timer.minutes", 4319);
            meta.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG,
                    System.currentTimeMillis() + minutes * 60_000L);
            lore.add(Component.empty());
            lore.add(line("Self-destructs in " + formatDuration(minutes), 0xFF5555));
        }
        meta.lore(lore);

        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text, int rgb) {
        return Component.text(text, TextColor.color(rgb)).decoration(TextDecoration.ITALIC, false);
    }

    String getToolType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId())) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        String type = getToolType(tool);
        if (type == null) return;
        if (!p.hasPermission("amethysttools.use")) return;

        if (isExpired(tool)) {
            p.getInventory().setItemInMainHand(null);
            send(p, msg("expired"));
            e.setCancelled(true);
            return;
        }

        Block origin = e.getBlock();

        // Blacklisted blocks can never be broken with an amethyst tool
        if (blacklist.contains(origin.getType())) {
            e.setCancelled(true);
            return;
        }

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        if (type.equals("sellaxe")) return; // behaves like a normal axe when breaking

        if (type.equals("hoe")) {
            handleHoe(p, origin, tool, e);
            return;
        }

        List<Block> targets = type.equals("axe") ? collectTree(origin) : collectArea(p, origin, tool, type);
        if (targets.isEmpty()) return;

        breaking.add(p.getUniqueId());
        try {
            for (Block b : targets) {
                if (b.equals(origin)) continue;
                if (blacklist.contains(b.getType())) continue;

                // Fire a real break event so WorldGuard / claims plugins can veto each block
                BlockBreakEvent sub = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(sub);
                if (sub.isCancelled()) continue;

                spawnParticles(b);
                if (sub.isDropItems()) {
                    b.breakNaturally(tool);
                } else {
                    b.setType(Material.AIR);
                }
            }
        } finally {
            breaking.remove(p.getUniqueId());
        }
        spawnParticles(origin);
    }

    private void handleHoe(Player p, Block origin, ItemStack tool, BlockBreakEvent e) {
        if (!CROPS.contains(origin.getType())) return;
        e.setCancelled(true); // we handle the origin ourselves (harvest + replant)

        int harvested = 0;
        breaking.add(p.getUniqueId());
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = origin.getRelative(dx, 0, dz);
                    if (!CROPS.contains(b.getType())) continue;
                    if (!(b.getBlockData() instanceof Ageable age)) continue;
                    if (age.getAge() < age.getMaximumAge()) continue;

                    if (!b.equals(origin)) {
                        BlockBreakEvent sub = new BlockBreakEvent(b, p);
                        Bukkit.getPluginManager().callEvent(sub);
                        if (sub.isCancelled()) continue;
                    }
                    Location c = b.getLocation().add(0.5, 0.5, 0.5);
                    for (ItemStack drop : b.getDrops(tool)) {
                        b.getWorld().dropItemNaturally(c, drop);
                    }
                    age.setAge(0);
                    b.setBlockData(age);
                    spawnParticles(b);
                    harvested++;
                }
            }
        } finally {
            breaking.remove(p.getUniqueId());
        }
        if (harvested > 0) tool.damage(1, p);
    }

    // ------------------------------------------------------------------ self-destruct timer

    boolean isExpired(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Long t = item.getItemMeta().getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
        return t != null && System.currentTimeMillis() > t;
    }

    private void checkExpiry() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (getToolType(it) != null && isExpired(it)) {
                    p.getInventory().setItem(i, null);
                    send(p, msg("expired"));
                }
            }
        }
    }

    private String formatDuration(long minutes) {
        long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || sb.length() == 0) sb.append(m).append("m");
        return sb.toString().trim();
    }

    private List<Block> collectArea(Player p, Block origin, ItemStack tool, String type) {
        BlockFace face = p.getTargetBlockFace(6);
        if (face == null) return Collections.emptyList();

        boolean onlyPreferred = getConfig().getBoolean("only-preferred-tool", true);
        List<Block> out = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                Block target = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a); // EAST / WEST
                };
                if (target.getType().isAir() || target.isLiquid()) continue;
                if (target.getType().getHardness() < 0) continue; // unbreakable
                if (onlyPreferred && !target.equals(origin) && !target.isPreferredTool(tool)) continue;
                out.add(target);
            }
        }
        return out;
    }

    private List<Block> collectTree(Block origin) {
        if (!Tag.LOGS.isTagged(origin.getType())) return Collections.emptyList();

        int max = getConfig().getInt("axe.max-blocks", 256);
        boolean leaves = getConfig().getBoolean("axe.break-leaves", true);

        List<Block> result = new ArrayList<>();
        Map<Block, Integer> leafDist = new HashMap<>(); // distance in leaf steps from a log
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        queue.add(origin);
        seen.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block cur = queue.poll();
            result.add(cur);
            boolean curIsLog = Tag.LOGS.isTagged(cur.getType());
            int curLeaf = curIsLog ? 0 : leafDist.getOrDefault(cur, 0);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block n = cur.getRelative(x, y, z);
                        if (seen.contains(n)) continue;
                        Material m = n.getType();

                        if (Tag.LOGS.isTagged(m)) {
                            if (!curIsLog && curLeaf > 0) continue; // don't hop leaf -> log chains
                            seen.add(n);
                            queue.add(n);
                        } else if (leaves && Tag.LEAVES.isTagged(m)) {
                            if (n.getBlockData() instanceof Leaves ld && ld.isPersistent()) continue; // player-placed
                            int d = curLeaf + 1;
                            if (d > 4) continue;
                            seen.add(n);
                            leafDist.put(n, d);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return result;
    }

    private void spawnParticles(Block b) {
        if (!getConfig().getBoolean("particles.enabled", true)) return;
        List<Integer> rgb = getConfig().getIntegerList("particles.color");
        Color color = rgb.size() == 3 ? Color.fromRGB(clamp(rgb.get(0)), clamp(rgb.get(1)), clamp(rgb.get(2)))
                : Color.fromRGB(160, 32, 240);
        int amount = getConfig().getInt("particles.amount", 12);

        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.DUST, c, amount, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(color, 1.4f));
        w.spawnParticle(Particle.WITCH, c, Math.max(2, amount / 3), 0.3, 0.3, 0.3, 0);
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------------------------ commands

    String msg(String path) {
        return LegacyComponentSerializer.legacyAmpersand().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(getConfig().getString("messages." + path, "")));
    }

    void send(CommandSender s, String text) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            send(sender, msg("usage"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("amethysttools.reload")) {
                send(sender, msg("no-permission"));
                return true;
            }
            loadSettings();
            send(sender, msg("reloaded"));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("amethysttools.give")) {
                send(sender, msg("no-permission"));
                return true;
            }
            if (args.length < 3) {
                send(sender, msg("usage"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            String type = args[2].toLowerCase();
            if (target == null || !TOOLS.contains(type)) {
                send(sender, msg("usage"));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(36, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            for (int i = 0; i < amount; i++) {
                target.getInventory().addItem(createTool(type)).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            }
            send(target, msg("given").replace("%tool%", type));
            return true;
        }
        send(sender, msg("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("give", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(TOOLS);
        }
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
                 }
