package com.tizem.rtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RTPPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private enum Dest { OVERWORLD, NETHER, SPAWN }

    private static class MenuHolder implements InventoryHolder {
        private Inventory inv;
        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE,
            Material.WITHER_ROSE);

    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    private String menuTitle;
    private String overworldName, netherName, spawnWorld;
    private int ovMin, ovMax, neMin, neMax;
    private int countdown, maxAttempts;
    private boolean cancelOnMove;
    private String prefix, arrow;
    private TextColor prefixColor, accentColor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getCommand("rtp").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void load() {
        reloadConfig();
        menuTitle = getConfig().getString("menu-title", "Random Teleport");
        countdown = getConfig().getInt("countdown", 3);
        cancelOnMove = getConfig().getBoolean("cancel-on-move", true);
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 25));
        overworldName = getConfig().getString("overworld.world", "world");
        ovMin = Math.max(0, getConfig().getInt("overworld.min-radius", 500));
        ovMax = Math.max(ovMin + 1, getConfig().getInt("overworld.max-radius", 5000));
        netherName = getConfig().getString("nether.world", "world_nether");
        neMin = Math.max(0, getConfig().getInt("nether.min-radius", 100));
        neMax = Math.max(neMin + 1, getConfig().getInt("nether.max-radius", 1000));
        spawnWorld = getConfig().getString("spawn.world", "world");
        prefix = getConfig().getString("prefix", "RTP");
        arrow = getConfig().getString("arrow", "»");
        prefixColor = parse(getConfig().getString("prefix-color", "#9DFF1F"));
        accentColor = parse(getConfig().getString("accent-color", "#9DFF1F"));
    }

    private TextColor parse(String hex) {
        TextColor c = TextColor.fromHexString(hex);
        return c != null ? c : NamedTextColor.GREEN;
    }

    private Component line(Component body) {
        return Component.text(prefix, prefixColor, TextDecoration.BOLD)
                .append(Component.text(" " + arrow + " ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(body.decoration(TextDecoration.BOLD, false));
    }

    private Component white(String s) {
        return Component.text(s, NamedTextColor.WHITE);
    }

    private Component countdownLine(int n) {
        return line(white("Teleporting in ")
                .append(Component.text(String.valueOf(n), accentColor))
                .append(white(" seconds")));
    }

    // ---------- command + menu ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("tizem.rtp.admin")) {
                sender.sendMessage(line(white("You don't have permission!")));
                return true;
            }
            load();
            sender.sendMessage(line(white("Config reloaded!")));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!p.hasPermission("tizem.rtp.use")) {
            p.sendMessage(line(white("You don't have permission!")));
            return true;
        }
        openMenu(p);
        return true;
    }

    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(menuTitle));
        holder.inv = inv;
        inv.setItem(11, item(Material.MOSS_BLOCK, "Overworld", NamedTextColor.GREEN, "Click to random teleport"));
        inv.setItem(13, item(Material.NETHER_WART_BLOCK, "Nether", NamedTextColor.RED, "Click to random teleport"));
        inv.setItem(15, item(Material.END_STONE_BRICKS, "Spawn", NamedTextColor.YELLOW, "Click to teleport to spawn"));
        p.openInventory(inv);
    }

    private ItemStack item(Material m, String name, TextColor color, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Dest d = switch (e.getSlot()) {
            case 11 -> Dest.OVERWORLD;
            case 13 -> Dest.NETHER;
            case 15 -> Dest.SPAWN;
            default -> null;
        };
        if (d == null) return;
        p.closeInventory();
        startCountdown(p, d);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ---------- countdown ----------

    private void startCountdown(Player p, Dest dest) {
        UUID id = p.getUniqueId();
        if (pending.containsKey(id) || searching.contains(id)) {
            p.sendMessage(line(white("You are already teleporting!")));
            return;
        }
        if (countdown <= 0 || p.hasPermission("tizem.rtp.bypass")) {
            go(p, dest);
            return;
        }
        Location start = p.getLocation();
        int[] left = {countdown};
        p.sendMessage(countdownLine(left[0]));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl == null) {
                cancelPending(id);
                return;
            }
            if (cancelOnMove && moved(start, pl.getLocation())) {
                cancelPending(id);
                pl.sendMessage(line(white("Teleport cancelled because you moved!")));
                return;
            }
            left[0]--;
            if (left[0] > 0) {
                pl.sendMessage(countdownLine(left[0]));
            } else {
                cancelPending(id);
                go(pl, dest);
            }
        }, 20L, 20L);
        pending.put(id, task);
    }

    private boolean moved(Location a, Location b) {
        return a.getWorld() != b.getWorld()
                || a.getBlockX() != b.getBlockX()
                || a.getBlockY() != b.getBlockY()
                || a.getBlockZ() != b.getBlockZ();
    }

    private void cancelPending(UUID id) {
        BukkitTask t = pending.remove(id);
        if (t != null) t.cancel();
    }

    // ---------- teleporting ----------

    private void go(Player p, Dest dest) {
        if (dest == Dest.SPAWN) {
            toSpawn(p);
            return;
        }
        String name = dest == Dest.NETHER ? netherName : overworldName;
        World w = Bukkit.getWorld(name);
        if (w == null) {
            p.sendMessage(line(white("That world isn't available!")));
            return;
        }
        searching.add(p.getUniqueId());
        p.sendMessage(line(white("Searching for a safe location...")));
        attempt(p, w, dest, 0);
    }

    private void toSpawn(Player p) {
        World w = Bukkit.getWorld(spawnWorld);
        if (w == null) w = Bukkit.getWorlds().get(0);
        Location loc = w.getSpawnLocation().add(0.5, 0, 0.5);
        p.teleportAsync(loc).thenAccept(ok ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (ok && p.isOnline()) {
                        p.sendMessage(line(white("You have been teleported to spawn!")));
                    }
                }));
    }

    private void attempt(Player p, World w, Dest dest, int tries) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            searching.remove(id);
            return;
        }
        if (tries >= maxAttempts) {
            searching.remove(id);
            p.sendMessage(line(white("Couldn't find a safe location, try again!")));
            return;
        }

        int cfgMin = dest == Dest.NETHER ? neMin : ovMin;
        int cfgMax = dest == Dest.NETHER ? neMax : ovMax;
        WorldBorder border = w.getWorldBorder();
        double hi = Math.min(cfgMax, border.getSize() / 2.0 - 16);
        double lo = Math.min(cfgMin, Math.max(0, hi - 1));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = lo + r.nextDouble() * Math.max(1, hi - lo);
        int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * dist);

        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!p.isOnline()) {
                        searching.remove(id);
                        return;
                    }
                    int y = findY(w, dest, x, z);
                    if (y == Integer.MIN_VALUE) {
                        attempt(p, w, dest, tries + 1);
                        return;
                    }
                    Location target = new Location(w, x + 0.5, y + 1, z + 0.5,
                            p.getLocation().getYaw(), p.getLocation().getPitch());
                    p.teleportAsync(target).thenAccept(ok ->
                            Bukkit.getScheduler().runTask(this, () -> {
                                searching.remove(id);
                                if (ok && p.isOnline()) {
                                    p.sendMessage(line(white("You have been randomly teleported!")));
                                }
                            }));
                }));
    }

    private int findY(World w, Dest dest, int x, int z) {
        if (dest == Dest.NETHER) {
            for (int y = 120; y >= 32; y--) {
                if (isSafe(w, x, y, z)) return y;
            }
            return Integer.MIN_VALUE;
        }
        int y = w.getHighestBlockYAt(x, z);
        return isSafe(w, x, y, z) ? y : Integer.MIN_VALUE;
    }

    private boolean isSafe(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) return false;
        Block ground = w.getBlockAt(x, y, z);
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        Material gt = ground.getType();
        if (!gt.isSolid() || UNSAFE.contains(gt) || Tag.LEAVES.isTagged(gt)) return false;
        if (!feet.isPassable() || feet.isLiquid() || UNSAFE.contains(feet.getType())) return false;
        if (!head.isPassable() || head.isLiquid() || UNSAFE.contains(head.getType())) return false;
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cancelPending(id);
        searching.remove(id);
    }

    @Override
    public void onDisable() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }
}
