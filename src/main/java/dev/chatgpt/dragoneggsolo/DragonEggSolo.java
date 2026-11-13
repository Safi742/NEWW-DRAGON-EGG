package dev.chatgpt.dragoneggsolo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DragonEggSolo extends JavaPlugin implements Listener {
    private NamespacedKey markerKey;
    private EggEvent active;
    private BukkitTask searchTask;
    private BukkitTask carrierGlowTask;
    private final Set<UUID> glowGranted = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        markerKey = new NamespacedKey(this, "managed_egg_entity");
        cleanupOldDisplays();
        Bukkit.getPluginManager().registerEvents(this, this);
        carrierGlowTask = Bukkit.getScheduler().runTaskTimer(this, this::updateCarrierGlow, 20L, 20L);
        getLogger().info("DragonEggSolo enabled. Waiting for an Ender Dragon kill.");
    }

    @Override public void onDisable() {
        if (searchTask != null) searchTask.cancel();
        if (carrierGlowTask != null) carrierGlowTask.cancel();
        if (active != null) active.removeEntities();
        for (UUID id : glowGranted) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.removePotionEffect(PotionEffectType.GLOWING);
        }
        glowGranted.clear();
    }

    @EventHandler public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || dragon.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (searchTask != null) searchTask.cancel();
        if (active != null) active.removeEntities();
        World world = dragon.getWorld();
        Location origin = dragon.getLocation().clone();
        final int[] elapsed = {0};
        int timeoutTicks = Math.max(1, getConfig().getInt("egg-search-timeout-seconds", 30)) * 20;
        searchTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Block egg = findEggBlock(world, origin, 18, 18);
            if (egg != null) {
                Location at = egg.getLocation().add(0.5, 0.15, 0.5);
                egg.setType(Material.AIR, false);
                active = new EggEvent(world, at);
                active.spawn();
                searchTask.cancel(); searchTask = null;
                getLogger().info("Dragon Egg event spawned at " + at);
            } else {
                elapsed[0] += 10;
                if (elapsed[0] >= timeoutTicks) {
                    searchTask.cancel(); searchTask = null;
                    getLogger().warning("No Dragon Egg block found within the search timeout.");
                }
            }
        }, 1L, 10L);
    }

    private Block findEggBlock(World world, Location origin, int horizontal, int vertical) {
        for (int x=-horizontal; x<=horizontal; x++) for (int y=-vertical; y<=vertical; y++) for (int z=-horizontal; z<=horizontal; z++) {
            Block block = world.getBlockAt(origin.getBlockX()+x, origin.getBlockY()+y, origin.getBlockZ()+z);
            if (block.getType() == Material.DRAGON_EGG) return block;
        }
        return null;
    }

    @EventHandler public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (active == null || !active.isActive() || !active.isInteraction(event.getRightClicked())) return;
        event.setCancelled(true);
        int count = active.countNearbyPlayers();
        if (count != 1) {
            String msg = getConfig().getString("messages.waiting", "§cLocked: %count% players nearby.");
            event.getPlayer().sendMessage(msg.replace("%count%", String.valueOf(count)));
            return;
        }
        Location dropAt = active.location.clone();
        active.removeEntities(); active = null;
        dropAt.getWorld().dropItemNaturally(dropAt, new ItemStack(Material.DRAGON_EGG));
        event.getPlayer().sendMessage(getConfig().getString("messages.claimed", "§aYou claimed the Dragon Egg!"));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        glowGranted.remove(event.getPlayer().getUniqueId());
    }

    private void updateCarrierGlow() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean hasEgg = player.getInventory().contains(Material.DRAGON_EGG);
            UUID id = player.getUniqueId();
            if (hasEgg && !player.hasPotionEffect(PotionEffectType.GLOWING)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 45, 0, false, false, false));
                glowGranted.add(id);
            } else if (!hasEgg && glowGranted.remove(id)) {
                player.removePotionEffect(PotionEffectType.GLOWING);
            } else if (hasEgg && glowGranted.contains(id)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 45, 0, false, false, false));
            }
        }
    }

    private void cleanupOldDisplays() {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities())
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) entity.remove();
    }

    private final class EggEvent {
        private final World world;
        private final Location location;
        private ItemDisplay display;
        private Interaction hitbox;
        private BossBar bossBar;
        private BukkitTask animationTask;
        private float yaw;
        private boolean running = true;
        private Team glowTeam;

        EggEvent(World world, Location location) { this.world = world; this.location = location.clone(); }

        void spawn() {
            display = world.spawn(location.clone().add(0, 0.35, 0), ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.DRAGON_EGG));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(Display.Billboard.FIXED);
                e.setShadowRadius(0.45f); e.setShadowStrength(0.7f);
                e.setGlowing(true); mark(e);
                e.setTransformation(new Transformation(new Vector3f(-0.12f, -0.12f, -0.12f), new AxisAngle4f(0, 0, 1, 0),
                        new Vector3f(1.25f, 1.25f, 1.25f), new AxisAngle4f(0, 0, 1, 0)));
            });
            hitbox = world.spawn(location.clone().add(0, 0.45, 0), Interaction.class, e -> {
                e.setInteractionWidth(1.35f); e.setInteractionHeight(1.45f); e.setResponsive(true); mark(e);
            });
            bossBar = Bukkit.createBossBar("Dragon Egg | 0 nearby", BarColor.RED, BarStyle.SOLID);
            bossBar.setVisible(true);
            setupGlowTeam();
            int interval = Math.max(1, getConfig().getInt("update-interval-ticks", 5));
            animationTask = Bukkit.getScheduler().runTaskTimer(DragonEggSolo.this, () -> {
                if (!running || display == null || !display.isValid()) { removeEntities(); return; }
                yaw = (yaw + 5.0f) % 360.0f;
                double bob = Math.sin(System.currentTimeMillis() / 420.0) * 0.10;
                Location next = location.clone().add(0, 0.35 + bob, 0); next.setYaw(yaw);
                display.teleport(next);
                int count = countNearbyPlayers();
                boolean ready = count == 1;
                bossBar.setTitle("Dragon Egg  |  " + count + (count == 1 ? " player nearby  |  READY" : " players nearby  |  LOCKED"));
                bossBar.setColor(ready ? BarColor.BLUE : BarColor.RED);
                for (Player player : world.getPlayers()) {
                    boolean nearby = player.getLocation().distanceSquared(location) <= getConfig().getDouble("range", 12.0) * getConfig().getDouble("range", 12.0);
                    if (nearby) bossBar.addPlayer(player); else bossBar.removePlayer(player);
                }
                if (glowTeam != null) glowTeam.setColor(ready ? org.bukkit.ChatColor.BLUE : org.bukkit.ChatColor.RED);
            }, 0L, interval);
        }

        private void setupGlowTeam() {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = "des_egg_glow";
            glowTeam = board.getTeam(teamName);
            if (glowTeam == null) glowTeam = board.registerNewTeam(teamName);
            glowTeam.setColor(org.bukkit.ChatColor.RED);
            glowTeam.addEntry(display.getUniqueId().toString());
        }

        private void mark(Entity entity) { entity.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte)1); }
        int countNearbyPlayers() {
            double range = Math.max(1.0, getConfig().getDouble("range", 12.0));
            double r2 = range * range; int count = 0;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(location) <= r2) count++;
            return count;
        }
        boolean isInteraction(Entity entity) { return hitbox != null && hitbox.getUniqueId().equals(entity.getUniqueId()); }
        boolean isActive() { return running && display != null && display.isValid() && hitbox != null && hitbox.isValid(); }
        void removeEntities() {
            running = false;
            if (animationTask != null) animationTask.cancel();
            if (bossBar != null) { bossBar.removeAll(); bossBar.setVisible(false); }
            if (glowTeam != null) { glowTeam.removeEntry(display == null ? "" : display.getUniqueId().toString()); if (glowTeam.getEntries().isEmpty()) glowTeam.unregister(); }
            if (display != null && display.isValid()) display.remove();
            if (hitbox != null && hitbox.isValid()) hitbox.remove();
        }
    }
}
