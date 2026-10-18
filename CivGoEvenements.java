package fr.civgo.evenements;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;

public class CivGoEvenements extends JavaPlugin implements TabExecutor {

    private static final List<String> TYPES = List.of("pulsation", "nuit", "pluie", "lune");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("civevent").setExecutor(this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /** Le Cratère est lu dans CivGoNoirs (/noir setcrater) s'il est présent, sinon dans ce config. */
    private org.bukkit.configuration.file.FileConfiguration src() {
        org.bukkit.plugin.Plugin n = Bukkit.getPluginManager().getPlugin("CivGoNoirs");
        return (n != null && n.isEnabled()) ? n.getConfig() : getConfig();
    }

    private World world() {
        return Bukkit.getWorld(src().getString("world", "world"));
    }

    private Location crater(World w) {
        return new Location(w,
                src().getDouble("crater.x", 0),
                src().getDouble("crater.y", 64),
                src().getDouble("crater.z", 0));
    }

    private void console(String cmd) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    // ------------------------------------------------------------ événements

    /** Renvoie false si le type est inconnu. */
    private boolean run(String type) {
        switch (type) {
            case "pulsation" -> pulsation();
            case "nuit" -> console("noir start " + getConfig().getInt("nuit-exceptionnelle.minutes", 15));
            case "pluie" -> console("eclats admin pluie " + getConfig().getInt("pluie.eclats", 60));
            case "lune" -> console(getConfig().getString("commands.lune", "cinematique"));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void pulsation() {
        World w = world();
        if (w == null) return;
        Location c = crater(w);
        double radius = getConfig().getDouble("pulsation.radius", 250);
        int totalTicks = Math.max(2, getConfig().getInt("pulsation.duration-seconds", 12)) * 20;

        for (Player p : w.getPlayers()) {
            p.sendTitle(color(getConfig().getString("pulsation.title")),
                    color(getConfig().getString("pulsation.subtitle")), 10, 70, 20);
        }

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= totalTicks) {
                    cancel();
                    return;
                }
                w.spawnParticle(Particle.PORTAL, c, 120, 4, 6, 4, 0.4);
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 60, 3, 5, 3, 0.1);

                boolean beat = (t % 20) == 0; // un battement par seconde
                for (Player p : w.getPlayers()) {
                    double d = p.getLocation().distance(c);
                    if (d > radius) continue;
                    if (beat) {
                        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 0.8f);
                    }
                    if (t % 40 == 0) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, false));
                        if (d < radius / 3) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, true, false, false));
                        }
                    }
                }
                t += 10;
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    // ------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(ChatColor.GRAY + "/civevent <pulsation|nuit|pluie|lune> | dans <minutes> <type>");
            return true;
        }
        String first = a[0].toLowerCase(Locale.ROOT);

        if (first.equals("dans")) {
            if (a.length < 3 || !TYPES.contains(a[2].toLowerCase(Locale.ROOT))) {
                s.sendMessage(ChatColor.RED + "Usage : /civevent dans <minutes> <pulsation|nuit|pluie|lune>");
                return true;
            }
            int min;
            try {
                min = Math.max(1, Integer.parseInt(a[1]));
            } catch (NumberFormatException ex) {
                s.sendMessage(ChatColor.RED + "Minutes invalides.");
                return true;
            }
            String type = a[2].toLowerCase(Locale.ROOT);
            Bukkit.getScheduler().runTaskLater(this, () -> run(type), min * 1200L);
            s.sendMessage(ChatColor.GRAY + "Événement « " + type + " » programmé dans " + min + " min.");
            return true;
        }

        if (!run(first)) {
            s.sendMessage(ChatColor.RED + "Événement inconnu. Choix : " + String.join(", ", TYPES));
        } else {
            s.sendMessage(ChatColor.GRAY + "Événement « " + first + " » lancé.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) {
            return List.of("pulsation", "nuit", "pluie", "lune", "dans");
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("dans")) {
            return TYPES;
        }
        return List.of();
    }
}
