package fr.civgo.noirs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * CivGo Noirs : nuits imprévisibles. Pendant un Noir, la métadonnée "civgo_noir"
 * est posée sur le monde (lue par CivGoCicatrice pour faire briller les joueurs).
 */
public class CivGoNoirs extends JavaPlugin implements Listener, TabExecutor {

    public static final String META = "civgo_noir";

    private boolean active;
    private long startMs, endMs, nextMs;
    private long savedTime;
    private boolean savedStorm, savedThunder;
    private BossBar bar;
    private BukkitTask loop;
    private int cycles;
    private final Set<UUID> inZone = new HashSet<>();
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("noir").setExecutor(this);
        scheduleNext();
        // Un seul timer espacé (toutes les 5 secondes) : léger pour un petit hébergement
        loop = Bukkit.getScheduler().runTaskTimer(this, this::tick, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (active) stop(false);
        if (loop != null) loop.cancel();
    }

    // ------------------------------------------------------------ utilitaires

    private World world() {
        return Bukkit.getWorld(getConfig().getString("world", "world"));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    private long randomMs(String minPath, String maxPath, int defMin, int defMax) {
        int min = Math.max(1, getConfig().getInt(minPath, defMin));
        int max = Math.max(min, getConfig().getInt(maxPath, defMax));
        int minutes = min + rnd.nextInt(max - min + 1);
        return minutes * 60_000L;
    }

    private long randomDurationMs() {
        return randomMs("duration.min-minutes", "duration.max-minutes", 4, 10);
    }

    private void scheduleNext() {
        nextMs = System.currentTimeMillis()
                + randomMs("auto.min-interval-minutes", "auto.max-interval-minutes", 40, 120);
    }

    /** Danger entre 0 et 1 selon la distance au Cratère. */
    public double danger(Location l) {
        double radius = Math.max(1.0, getConfig().getDouble("danger-radius", 1500));
        double dx = l.getX() - getConfig().getDouble("crater.x", 0);
        double dz = l.getZ() - getConfig().getDouble("crater.z", 0);
        double d = 1.0 - Math.sqrt(dx * dx + dz * dz) / radius;
        double min = getConfig().getDouble("min-danger", 0.15);
        return Math.max(min, Math.min(1.0, d));
    }

    /** Distance horizontale au centre du Cratère (blocs). */
    public double craterDistance(Location l) {
        double dx = l.getX() - getConfig().getDouble("crater.x", 0);
        double dz = l.getZ() - getConfig().getDouble("crater.z", 0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private List<PotionEffect> zoneEffects() {
        List<PotionEffect> out = new ArrayList<>();
        for (String s : getConfig().getStringList("zone.effects")) {
            String[] p = s.split(":");
            PotionEffectType t = PotionEffectType.getByKey(NamespacedKey.minecraft(p[0].trim().toLowerCase(Locale.ROOT)));
            if (t == null) continue;
            int lvl = 0;
            if (p.length > 1) {
                try {
                    lvl = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(new PotionEffect(t, 160, lvl, true, false, true));
        }
        return out;
    }

    /** Zone du Cratère : toujours dangereuse (même sans Noir). */
    private void zoneTick(World w) {
        if (!getConfig().getBoolean("zone.enabled", true)) return;
        double radius = getConfig().getDouble("zone.radius", 100);
        List<PotionEffect> eff = zoneEffects();
        for (Player p : w.getPlayers()) {
            UUID id = p.getUniqueId();
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            boolean inside = craterDistance(p.getLocation()) <= radius;
            if (inside) {
                if (inZone.add(id)) {
                    p.sendTitle(color(getConfig().getString("zone.enter-title", "&5Le Cratère")),
                            color(getConfig().getString("zone.enter-subtitle", "&7L'air vibre... tu n'es pas seul.")), 10, 60, 20);
                    p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_NEARBY_CLOSE, 0.7f, 0.7f);
                }
                if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                    for (PotionEffect e : eff) p.addPotionEffect(e);
                    if (getConfig().getBoolean("zone.mobs", true)) {
                        int target = getConfig().getInt("zone.mobs-per-player", 4);
                        int near = 0;
                        for (Entity en : p.getNearbyEntities(32, 20, 32)) {
                            if (en instanceof Monster) near++;
                        }
                        if (near < target) {
                            Location spot = randomSpot(w, p.getLocation());
                            if (spot != null) {
                                EntityType[] pool = {EntityType.SPIDER, EntityType.CREEPER, EntityType.WITCH, EntityType.HUSK, EntityType.VINDICATOR};
                                w.spawnEntity(spot, pool[rnd.nextInt(pool.length)]);
                            }
                        }
                    }
                }
            } else if (inZone.remove(id)) {
                p.sendMessage(color(getConfig().getString("zone.leave-message", "&7Tu quittes le Cratère.")));
            }
        }
        inZone.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    public boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------ cycle de vie d'un Noir

    private void tick() {
        World w = world();
        if (w == null) return;
        long now = System.currentTimeMillis();
        zoneTick(w);

        if (!active) {
            if (getConfig().getBoolean("auto.enabled", true) && now >= nextMs && !w.getPlayers().isEmpty()) {
                start(randomDurationMs());
            }
            return;
        }
        if (now >= endMs) {
            stop(true);
            return;
        }

        double left = 1.0 - (double) (now - startMs) / (double) Math.max(1L, endMs - startMs);
        bar.setProgress(Math.max(0.0, Math.min(1.0, left)));
        w.setTime(18000L);

        boolean dark = getConfig().getBoolean("darkness-effect", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(w)) {
                bar.addPlayer(p);
                if (dark) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 0, true, false, false));
                }
            } else {
                bar.removePlayer(p);
            }
        }

        cycles++;
        int every = Math.max(1, getConfig().getInt("extra-spawns.interval-seconds", 10) / 5);
        if (getConfig().getBoolean("extra-spawns.enabled", true) && cycles % every == 0) {
            extraSpawns(w);
        }
    }

    public boolean start(long durationMs) {
        World w = world();
        if (active || w == null) return false;

        active = true;
        startMs = System.currentTimeMillis();
        endMs = startMs + durationMs;
        cycles = 0;
        savedTime = w.getTime();
        savedStorm = w.hasStorm();
        savedThunder = w.isThundering();

        w.setMetadata(META, new FixedMetadataValue(this, Boolean.TRUE));
        if (getConfig().getBoolean("storm", true)) {
            w.setStorm(true);
            w.setThundering(true);
        }
        w.setTime(18000L);

        bar = Bukkit.createBossBar(msg("bossbar"), BarColor.PURPLE, BarStyle.SOLID);
        for (Player p : w.getPlayers()) {
            bar.addPlayer(p);
            p.sendTitle(msg("title"), msg("subtitle"), 10, 80, 30);
            p.sendMessage(msg("start"));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4f, 0.6f);
        }
        return true;
    }

    public void stop(boolean natural) {
        if (!active) return;
        active = false;
        World w = world();
        if (w != null) {
            w.removeMetadata(META, this);
            long elapsedTicks = (System.currentTimeMillis() - startMs) / 50L;
            w.setTime((savedTime + elapsedTicks) % 24000L);
            w.setStorm(savedStorm);
            w.setThundering(savedThunder);
            for (Player p : w.getPlayers()) {
                p.removePotionEffect(PotionEffectType.DARKNESS);
                if (natural) {
                    p.sendMessage(msg("end"));
                    p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1.0f, 1.0f);
                }
            }
        }
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        scheduleNext();
    }

    // ------------------------------------------------------------ mobs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!getConfig().getBoolean("mob-buffs", true)) return;
        if (!(e.getEntity() instanceof Monster)) return;
        World w = world();
        if (w == null || !e.getLocation().getWorld().equals(w)) return;
        boolean inZ = getConfig().getBoolean("zone.enabled", true)
                && craterDistance(e.getLocation()) <= getConfig().getDouble("zone.radius", 100);
        if (!active && !inZ) return;

        LivingEntity m = e.getEntity();
        double d = inZ ? 1.0 : danger(e.getLocation());
        int dur = 20 * 60 * 20;
        // Tous les monstres sont un peu plus rapides pendant un Noir
        m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dur, d > 0.66 ? 1 : 0, true, false));
        if (d > 0.33) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, dur, d > 0.66 ? 1 : 0, true, false));
        }
        if (d > 0.66) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, dur, 0, true, false));
        }
    }

    private void extraSpawns(World w) {
        int max = Math.max(1, getConfig().getInt("extra-spawns.max-per-player", 6));
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            Location pl = p.getLocation();
            // Pas de renfort pour les joueurs bien enfouis sous terre
            if (pl.getY() < w.getHighestBlockYAt(pl) - 10) continue;

            double d = danger(pl);
            int target = (int) Math.round(1 + d * (max - 1));
            int near = 0;
            for (Entity en : p.getNearbyEntities(32, 20, 32)) {
                if (en instanceof Monster) near++;
            }
            if (near >= target) continue;

            Location spot = randomSpot(w, pl);
            if (spot == null) continue;
            w.spawnEntity(spot, pickType(d));
        }
    }

    private Location randomSpot(World w, Location c) {
        for (int i = 0; i < 4; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = 14 + rnd.nextDouble() * 12;
            int x = (int) Math.floor(c.getX() + Math.cos(a) * r);
            int z = (int) Math.floor(c.getZ() + Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, y, z);
            if (!ground.getType().isSolid()) continue;
            Block b1 = w.getBlockAt(x, y + 1, z);
            Block b2 = w.getBlockAt(x, y + 2, z);
            if (!b1.isPassable() || !b2.isPassable() || b1.isLiquid()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private EntityType pickType(double d) {
        List<EntityType> pool = new ArrayList<>();
        pool.add(EntityType.ZOMBIE);
        pool.add(EntityType.SKELETON);
        if (d > 0.33) {
            pool.add(EntityType.SPIDER);
            pool.add(EntityType.HUSK);
            pool.add(EntityType.CREEPER);
        }
        if (d > 0.66) {
            pool.add(EntityType.WITCH);
            pool.add(EntityType.STRAY);
            pool.add(EntityType.VINDICATOR);
        }
        return pool.get(rnd.nextInt(pool.size()));
    }

    // ------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        switch (sub) {
            case "start" -> {
                if (!s.hasPermission("civgo.noirs.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                long ms = randomDurationMs();
                if (a.length > 1) {
                    try {
                        ms = Math.max(1, Integer.parseInt(a[1])) * 60_000L;
                    } catch (NumberFormatException ex) {
                        s.sendMessage(ChatColor.RED + "Durée invalide (minutes).");
                        return true;
                    }
                }
                if (start(ms)) {
                    s.sendMessage(ChatColor.DARK_PURPLE + "Noir lancé pour " + (ms / 60_000L) + " min.");
                } else {
                    s.sendMessage(ChatColor.RED + "Impossible : un Noir est déjà en cours (ou monde introuvable).");
                }
            }
            case "stop" -> {
                if (!s.hasPermission("civgo.noirs.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                if (active) {
                    stop(true);
                    s.sendMessage(ChatColor.GRAY + "Noir arrêté.");
                } else {
                    s.sendMessage(ChatColor.RED + "Aucun Noir en cours.");
                }
            }
            case "setcrater" -> {
                if (!s.hasPermission("civgo.noirs.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                if (!(s instanceof Player pl)) {
                    s.sendMessage("Commande réservée aux joueurs : place-toi au centre du Cratère.");
                    return true;
                }
                Location l = pl.getLocation();
                getConfig().set("world", l.getWorld().getName());
                getConfig().set("crater.x", l.getBlockX());
                getConfig().set("crater.y", l.getBlockY());
                getConfig().set("crater.z", l.getBlockZ());
                saveConfig();
                s.sendMessage(ChatColor.DARK_PURPLE + "Cratère placé ici : " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                        + " (" + l.getWorld().getName() + "). Éclats et Événements utilisent aussi ce point.");
            }
            case "crater" -> {
                s.sendMessage(ChatColor.DARK_PURPLE + "Cratère : x=" + getConfig().getInt("crater.x") + " y=" + getConfig().getInt("crater.y")
                        + " z=" + getConfig().getInt("crater.z") + " (monde " + getConfig().getString("world", "world")
                        + "), zone dangereuse de rayon " + getConfig().getInt("zone.radius", 100) + ".");
            }
            case "danger" -> {
                if (!(s instanceof Player pl)) {
                    s.sendMessage("Commande réservée aux joueurs.");
                    return true;
                }
                Location l = pl.getLocation();
                double dist = craterDistance(l);
                boolean in = dist <= getConfig().getDouble("zone.radius", 100);
                s.sendMessage(ChatColor.DARK_PURPLE + "Distance au Cratère : " + (int) dist + " blocs. Danger des Noirs ici : "
                        + Math.round(danger(l) * 100) + " %." + (in ? " Tu es DANS la zone du Cratère." : ""));
            }
            default -> {
                if (active) {
                    long left = Math.max(0, (endMs - System.currentTimeMillis()) / 1000L);
                    s.sendMessage(ChatColor.DARK_PURPLE + "Un Noir est en cours (" + left + " s restantes).");
                } else if (s.hasPermission("civgo.noirs.admin")) {
                    long in = Math.max(0, (nextMs - System.currentTimeMillis()) / 60_000L);
                    s.sendMessage(ChatColor.GRAY + "Pas de Noir. Prochain Noir automatique dans ~" + in + " min.");
                } else {
                    s.sendMessage(ChatColor.GRAY + "Le ciel est calme... pour l'instant.");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1 && s.hasPermission("civgo.noirs.admin")) {
            return List.of("start", "stop", "status", "setcrater", "crater", "danger");
        }
        return List.of();
    }
}
