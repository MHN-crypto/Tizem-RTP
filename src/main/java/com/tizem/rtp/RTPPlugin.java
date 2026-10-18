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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RTPPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE,
            Material.WITHER_ROSE);

    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    private String worldName;
    private int minRadius, maxRadius, countdown, maxAttempts;
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
        worldName = getConfig().getString("world", "world");
        minRadius = Math.max(0, getConfig().getInt("min-radius", 500));
        maxRadius = Math.max(minRadius + 1, getConfig().getInt("max-radius", 5000));
        countdown = getConfig().getInt("countdown", 3);
        cancelOnMove = getConfig().getBoolean("cancel-on-move", true);
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 15));
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
        startCountdown(p);
        return true;
    }

    private void startCountdown(Player p) {
        UUID id = p.getUniqueId();
        if (pending.containsKey(id) || searching.contains(id)) {
            p.sendMessage(line(white("You are already teleporting!")));
            return;
        }
        if (countdown <= 0 || p.hasPermission("tizem.rtp.bypass")) {
            findAndTeleport(p);
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
                findAndTeleport(pl);
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

    private void findAndTeleport(Player p) {
        UUID id = p.getUniqueId();
        World w = Bukkit.getWorld(worldName);
        if (w == null) w = p.getWorld();
        searching.add(id);
        p.sendMessage(line(white("Searching for a safe location...")));
        attempt(p, w, 0);
    }

    private void attempt(Player p, World w, int tries) {
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

        WorldBorder border = w.getWorldBorder();
        double max = Math.min(maxRadius, border.getSize() / 2.0 - 16);
        double min = Math.min(minRadius, Math.max(0, max - 1));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * Math.max(1, max - min);
        int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * dist);

        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!p.isOnline()) {
                        searching.remove(id);
                        return;
                    }
                    int y = w.getHighestBlockYAt(x, z);
                    if (!isSafe(w, x, y, z)) {
                        attempt(p, w, tries + 1);
                        return;
                    }
                    Location dest = new Location(w, x + 0.5, y + 1, z + 0.5,
                            p.getLocation().getYaw(), p.getLocation().getPitch());
                    p.teleportAsync(dest).thenAccept(ok ->
                            Bukkit.getScheduler().runTask(this, () -> {
                                searching.remove(id);
                                if (ok && p.isOnline()) {
                                    p.sendMessage(line(white("You have been randomly teleported!")));
                                }
                            }));
                }));
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
