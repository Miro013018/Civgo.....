package fr.civgo.cratere;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * CivGo Cratère : creuse un vrai cratère (cuvette + rebord + éjectas) là où se trouve l'admin.
 * La génération est découpée par petits paquets de colonnes à chaque tick pour ne pas faire ramer.
 * Elle règle aussi le centre du Cratère dans CivGoNoirs / CivGoEclats s'ils sont installés.
 */
public class CivGoCratere extends JavaPlugin implements TabExecutor {

    private record Pending(Location loc, int radius, int depth, long time) {}

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Random rnd = new Random();
    private BukkitRunnable job;
    private long seed = 1337;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("cratere").setExecutor(this);
    }

    @Override
    public void onDisable() {
        if (job != null) job.cancel();
    }

    // ------------------------------------------------------------ outils

    private int groundY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        int min = w.getMinHeight();
        while (y > min) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.LOGS.isTagged(m)) break;
            y--;
        }
        return y;
    }

    private double hash(int x, int z) {
        long n = x * 374761393L + z * 668265263L + seed * 1442695040888963407L;
        n = (n ^ (n >>> 13)) * 1274126177L;
        n ^= n >>> 16;
        return (n & 0xffff) / 65535.0;
    }

    private double noise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(xi, zi), b = hash(xi + 1, zi), c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    private Material pick(Material... m) {
        return m[rnd.nextInt(m.length)];
    }

    // ------------------------------------------------------------ génération

    private class Plan {
        final World w;
        final int cx, cz, R, D, H, E, baseY, centerY;
        final double ph1, ph2;
        final Material ore;
        final boolean magma;

        Plan(Location l, int radius, int depth) {
            w = l.getWorld();
            cx = l.getBlockX();
            cz = l.getBlockZ();
            R = radius;
            D = depth;
            H = Math.max(3, Math.min(getConfig().getInt("rim-height", 10), radius / 6));
            E = (int) Math.ceil(radius * 1.75);
            ph1 = rnd.nextDouble() * 6.28;
            ph2 = rnd.nextDouble() * 6.28;
            magma = getConfig().getBoolean("center-magma", true);

            // niveau de référence = hauteur moyenne du terrain sur l'anneau du rebord
            int n = 48, sum = 0;
            for (int k = 0; k < n; k++) {
                double ang = Math.PI * 2 * k / n;
                int x = (int) Math.round(cx + Math.cos(ang) * R * 1.15);
                int z = (int) Math.round(cz + Math.sin(ang) * R * 1.15);
                sum += groundY(w, x, z);
            }
            baseY = Math.round(sum / (float) n);
            centerY = groundY(w, cx, cz);

            String oreName = "BUDDING_AMETHYST";
            Plugin ec = Bukkit.getPluginManager().getPlugin("CivGoEclats");
            if (ec != null) oreName = ec.getConfig().getString("ore-material", oreName);
            Material om = Material.matchMaterial(oreName);
            ore = om == null ? Material.BUDDING_AMETHYST : om;
        }

        void column(int x, int z) {
            double dx = x - cx, dz = z - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > E) return;
            double ang = Math.atan2(dz, dx);
            double rt = R * (1 + 0.07 * Math.sin(3 * ang + ph1) + 0.04 * Math.sin(5 * ang + ph2) + 0.02 * Math.sin(9 * ang + ph1 * 2));
            double t = d / rt;

            // éjectas : quelques blocs sombres projetés autour du rebord
            if (t >= 1.4) {
                double prob = t < 1.75 ? 0.035 * (1.75 - t) / 0.35 : 0;
                if (prob > 0 && rnd.nextDouble() < prob) {
                    int y0 = groundY(w, x, z);
                    w.getBlockAt(x, y0, z).setType(pick(Material.BLACKSTONE, Material.BASALT, Material.TUFF), false);
                    if (rnd.nextDouble() < 0.35) {
                        int h = 1 + rnd.nextInt(2);
                        for (int k = 1; k <= h; k++) {
                            w.getBlockAt(x, y0 + k, z).setType(pick(Material.BLACKSTONE, Material.BASALT), false);
                        }
                    }
                }
                return;
            }

            int topAll = w.getHighestBlockYAt(x, z);
            int y0 = groundY(w, x, z);
            int minY = w.getMinHeight() + 4;

            double target;
            if (t < 1.0) {
                double rough = (noise(x * 0.12, z * 0.12) - 0.5) * 4.0 * (1 - t * 0.6);
                target = baseY + H - D * (1 - t * t) + rough;
            } else {
                double u = (t - 1.0) / 0.4;
                double bump = H * (1 - u) * (1 - u);
                target = y0 + bump + (noise(x * 0.2, z * 0.2) - 0.5) * 1.6 * (1 - u);
            }
            int tgt = Math.max((int) Math.round(target), minY);

            // 1) dégager (arbres compris)
            for (int y = topAll; y > tgt; y--) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
            // 2) remblayer le rebord
            for (int y = y0 + 1; y <= tgt; y++) {
                w.getBlockAt(x, y, z).setType(Material.TUFF, false);
            }
            // 3) surface calcinée
            for (int k = 0; k < 3; k++) {
                int y = tgt - k;
                if (y < minY) break;
                w.getBlockAt(x, y, z).setType(surface(t, k), false);
            }
        }

        Material surface(double t, int k) {
            // Éclats : de plus en plus nombreux vers le centre
            if (t < 1.0) {
                double pOre = (0.012 + 0.16 * Math.pow(1 - t, 2)) * (k == 0 ? 1.0 : 0.5);
                if (rnd.nextDouble() < pOre) return ore;
            }
            double r = rnd.nextDouble();
            if (t < 0.14) {
                if (k == 0 && magma && r < 0.22) return Material.MAGMA_BLOCK;
                if (k == 0 && magma && r < 0.38) return Material.CRYING_OBSIDIAN;
                return pick(Material.BLACKSTONE, Material.BASALT);
            }
            if (t < 0.45) {
                if (r < 0.03) return Material.CRYING_OBSIDIAN;
                if (r < 0.07) return Material.AMETHYST_BLOCK;
                return pick(Material.BLACKSTONE, Material.BASALT, Material.SMOOTH_BASALT, Material.DEEPSLATE);
            }
            if (t < 0.8) {
                if (r < 0.10) return Material.BLACKSTONE;
                if (r < 0.20) return Material.GRAVEL;
                return pick(Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF);
            }
            if (t < 1.0) {
                return pick(Material.COBBLED_DEEPSLATE, Material.TUFF, Material.GRAVEL, Material.COARSE_DIRT, Material.COBBLESTONE);
            }
            if (r < 0.06) return Material.BLACKSTONE;
            return pick(Material.COARSE_DIRT, Material.GRAVEL, Material.TUFF, Material.COBBLESTONE);
        }
    }

    private void launch(CommandSender who, Location l, int radius, int depth) {
        if (job != null) {
            who.sendMessage(ChatColor.RED + "Une génération est déjà en cours (/cratere stop pour l'arrêter).");
            return;
        }
        seed = System.nanoTime();
        Plan p = new Plan(l, radius, depth);

        // règle le centre du Cratère dans les autres plugins CivGo
        Plugin noirs = Bukkit.getPluginManager().getPlugin("CivGoNoirs");
        if (noirs != null) {
            noirs.getConfig().set("world", p.w.getName());
            noirs.getConfig().set("crater.x", p.cx);
            noirs.getConfig().set("crater.y", p.centerY);
            noirs.getConfig().set("crater.z", p.cz);
            noirs.getConfig().set("zone.radius", (int) (radius * 1.3));
            noirs.getConfig().set("danger-radius", radius * 12);
            noirs.saveConfig();
        }
        Plugin ec = Bukkit.getPluginManager().getPlugin("CivGoEclats");
        if (ec != null) {
            ec.getConfig().set("radius", radius);
            ec.saveConfig();
        }

        final int side = 2 * p.E + 1;
        final int total = side * side;
        final int perTick = Math.max(20, getConfig().getInt("columns-per-tick", 120));
        who.sendMessage(ChatColor.DARK_PURPLE + "Génération du Cratère lancée (rayon " + radius + ", profondeur " + depth + ")...");

        job = new BukkitRunnable() {
            int idx = 0;
            long last = System.currentTimeMillis();

            @Override
            public void run() {
                int done = 0;
                while (idx < total && done < perTick) {
                    int x = p.cx - p.E + idx / side;
                    int z = p.cz - p.E + idx % side;
                    idx++;
                    double dx = x - p.cx, dz = z - p.cz;
                    if (dx * dx + dz * dz > (double) p.E * p.E) continue;
                    p.column(x, z);
                    done++;
                }
                if (idx >= total) {
                    cancel();
                    job = null;
                    who.sendMessage(ChatColor.DARK_PURPLE + "Le Cratère est creusé. Centre : " + p.cx + " " + p.centerY + " " + p.cz
                            + ". Les Éclats affleurent vers le centre.");
                    Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "La terre a tremblé : le Cratère s'est ouvert.");
                } else if (System.currentTimeMillis() - last > 5000) {
                    last = System.currentTimeMillis();
                    who.sendMessage(ChatColor.GRAY + "Cratère : " + (int) (100.0 * idx / total) + " %");
                }
            }
        };
        job.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------ commandes

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("civgo.cratere.admin")) {
            s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "creer" -> {
                if (!(s instanceof Player pl)) {
                    s.sendMessage("Commande réservée aux joueurs : place-toi au centre voulu.");
                    return true;
                }
                int max = getConfig().getInt("max-radius", 400);
                int radius = getConfig().getInt("radius", 120);
                int depth = getConfig().getInt("depth", 40);
                try {
                    if (a.length > 1) radius = Integer.parseInt(a[1]);
                    if (a.length > 2) depth = Integer.parseInt(a[2]);
                } catch (NumberFormatException ex) {
                    s.sendMessage(ChatColor.RED + "Nombres invalides. Exemple : /cratere creer 120 40");
                    return true;
                }
                radius = Math.max(20, Math.min(max, radius));
                depth = Math.max(8, Math.min(120, depth));
                pending.put(pl.getUniqueId(), new Pending(pl.getLocation(), radius, depth, System.currentTimeMillis()));
                s.sendMessage(ChatColor.GOLD + "ATTENTION : cela va remodeler et détruire le terrain sur environ "
                        + (int) (radius * 1.75) + " blocs autour de toi (arbres, constructions, tout). Fais une sauvegarde du monde avant.");
                s.sendMessage(ChatColor.GOLD + "Cratère de rayon " + radius + " et profondeur " + depth
                        + " centré ici. Tape /cratere confirmer dans les 30 secondes pour lancer.");
            }
            case "confirmer" -> {
                if (!(s instanceof Player pl)) return true;
                Pending p = pending.remove(pl.getUniqueId());
                if (p == null || System.currentTimeMillis() - p.time() > 30_000L) {
                    s.sendMessage(ChatColor.RED + "Rien à confirmer (ou délai dépassé). Refais /cratere creer.");
                    return true;
                }
                launch(s, p.loc(), p.radius(), p.depth());
            }
            case "stop" -> {
                if (job == null) {
                    s.sendMessage(ChatColor.GRAY + "Aucune génération en cours.");
                } else {
                    job.cancel();
                    job = null;
                    s.sendMessage(ChatColor.GRAY + "Génération arrêtée (le Cratère est resté à moitié creusé).");
                }
            }
            default -> s.sendMessage(ChatColor.GRAY + "/cratere creer [rayon] [profondeur] puis /cratere confirmer | /cratere stop. "
                    + (job != null ? "Génération en cours." : "Aucune génération en cours."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) return List.of("creer", "confirmer", "stop", "info");
        return List.of();
    }
}
