package fr.civgo.cicatrice;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class CivGoCicatrice extends JavaPlugin implements Listener, TabExecutor {

    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L} '\\-]{2,23}$");

    private static final String[] SHAPES = {
            "une fissure violette", "une marque en étoile brisée", "un croissant de lumière pâle",
            "une spirale d'éclats", "une entaille irrégulière aux reflets d'améthyste",
            "des veines sombres en forme de racine"
    };
    private static final String[] PARTS = {
            "sur la joue gauche", "sur la joue droite", "en travers du front", "le long du cou",
            "sur l'épaule gauche", "sur l'épaule droite", "sur l'avant-bras gauche",
            "sur l'avant-bras droit", "sur le torse", "au dos de la main gauche", "au dos de la main droite"
    };

    private File file;
    private YamlConfiguration data;
    private Team team;
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> glowing = new HashSet<>();
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "identities.yml");
        data = YamlConfiguration.loadConfiguration(file);

        setupTeam();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("identite").setExecutor(this);
        getCommand("cicatrice").setExecutor(this);

        // Timer espacé (5 s) : la Cicatrice brille tant qu'un Noir est actif
        Bukkit.getScheduler().runTaskTimer(this, this::glowTick, 100L, 100L);

        // Au cas où le plugin est rechargé alors que des joueurs sont connectés
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (has(p.getUniqueId())) apply(p);
            else pending.add(p.getUniqueId());
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : glowing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.removePotionEffect(PotionEffectType.GLOWING);
        }
        glowing.clear();
    }

    // ------------------------------------------------------------ utilitaires

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    private void setupTeam() {
        if (Bukkit.getScoreboardManager() == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        team = sb.getTeam("civgo_ident");
        if (team == null) team = sb.registerNewTeam("civgo_ident");
        team.setOption(Team.Option.NAME_TAG_VISIBILITY,
                getConfig().getBoolean("hide-nametags", true) ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
        team.setColor(ChatColor.DARK_PURPLE); // couleur du contour lumineux
    }

    private boolean has(UUID id) {
        return data.contains("players." + id + ".name");
    }

    private String name(UUID id) {
        return data.getString("players." + id + ".name");
    }

    private String scar(UUID id) {
        return data.getString("players." + id + ".scar");
    }

    private void save() {
        try {
            getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde impossible : " + ex.getMessage());
        }
    }

    private boolean nameTaken(String n) {
        ConfigurationSection s = data.getConfigurationSection("players");
        if (s == null) return false;
        for (String k : s.getKeys(false)) {
            if (n.equalsIgnoreCase(s.getString(k + ".name"))) return true;
        }
        return false;
    }

    private String randomScar() {
        return SHAPES[rnd.nextInt(SHAPES.length)] + " " + PARTS[rnd.nextInt(PARTS.length)];
    }

    private static boolean isNoir(World w) {
        for (MetadataValue v : w.getMetadata("civgo_noir")) {
            if (v.asBoolean()) return true;
        }
        return false;
    }

    /** Applique le nom RP : chat, liste des joueurs, pseudo masqué. */
    private void apply(Player p) {
        String n = name(p.getUniqueId());
        if (n == null) return;
        p.setDisplayName(n);
        p.setPlayerListName(n);
        if (team != null) team.addEntry(p.getName());
        data.set("players." + p.getUniqueId() + ".mc", p.getName());
    }

    private void askName(Player p) {
        if (!p.isOnline() || !pending.contains(p.getUniqueId())) return;
        p.sendTitle(msg("ask-title"), msg("ask-subtitle"), 10, 100, 20);
        p.sendMessage(msg("ask-chat"));
    }

    private void register(Player p, String raw) {
        UUID id = p.getUniqueId();
        if (!pending.contains(id)) return;
        String n = raw.trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(n).matches()) {
            p.sendMessage(msg("invalid"));
            return;
        }
        if (nameTaken(n)) {
            p.sendMessage(msg("taken"));
            return;
        }
        String scar = randomScar();
        data.set("players." + id + ".name", n);
        data.set("players." + id + ".scar", scar);
        save();
        pending.remove(id);
        apply(p);
        p.sendTitle("§5" + n, "§7Ta Cicatrice : §d" + scar, 10, 120, 30);
        p.sendMessage(ChatColor.GRAY + "Tu es désormais " + ChatColor.WHITE + n + ChatColor.GRAY
                + ". Tu portes " + ChatColor.LIGHT_PURPLE + scar + ChatColor.GRAY
                + ". Elle brillera pendant les Noirs.");
    }

    // ------------------------------------------------------------ événements

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("hide-join-messages", true)) e.setJoinMessage(null);
        if (has(p.getUniqueId())) {
            apply(p);
            save();
        } else {
            pending.add(p.getUniqueId());
            if (team != null) team.addEntry(p.getName());
            Bukkit.getScheduler().runTaskLater(this, () -> askName(p), 40L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (getConfig().getBoolean("hide-join-messages", true)) e.setQuitMessage(null);
        pending.remove(e.getPlayer().getUniqueId());
        glowing.remove(e.getPlayer().getUniqueId());
    }

    /** Un joueur sans identité ne peut pas bouger tant qu'il n'a pas choisi son nom. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!pending.contains(e.getPlayer().getUniqueId()) || e.getTo() == null) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (pending.contains(p.getUniqueId())) {
            e.setCancelled(true);
            String raw = e.getMessage();
            Bukkit.getScheduler().runTask(this, () -> register(p, raw));
            return;
        }
        if (has(p.getUniqueId())) {
            e.setFormat(color(getConfig().getString("chat-format", "&f%1$s&7 » &f%2$s")));
        }
    }

    /** Clic droit sur un joueur proche : on retient son nom RP (accroupi : on voit aussi sa Cicatrice). */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player t)) return;
        Player p = e.getPlayer();
        String n = name(t.getUniqueId());
        if (n == null || !p.getWorld().equals(t.getWorld())) return;
        if (p.getLocation().distance(t.getLocation()) > getConfig().getDouble("recognize-distance", 6)) return;

        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§7Tu reconnais §f" + n));
        if (p.isSneaking() && scar(t.getUniqueId()) != null) {
            p.sendMessage(ChatColor.GRAY + "Tu remarques chez " + ChatColor.WHITE + n + ChatColor.GRAY
                    + " : " + ChatColor.LIGHT_PURPLE + scar(t.getUniqueId()) + ChatColor.GRAY + ".");
        }
    }

    private void glowTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (has(id) && isNoir(p.getWorld())) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 160, 0, true, false, false));
                glowing.add(id);
            } else if (glowing.remove(id)) {
                p.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
    }

    // ------------------------------------------------------------ commandes

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("cicatrice")) return cicatriceCommand(s, a);

        boolean admin = s.hasPermission("civgo.cicatrice.admin");
        if (a.length == 0) {
            if (s instanceof Player p && name(p.getUniqueId()) != null) {
                s.sendMessage(ChatColor.GRAY + "Tu es " + ChatColor.WHITE + name(p.getUniqueId()) + ChatColor.GRAY + ".");
            } else {
                s.sendMessage(ChatColor.GRAY + "Tu n'as pas encore d'identité.");
            }
            return true;
        }
        if (!admin) {
            s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "whois" -> whois(s, a);
            case "reset" -> reset(s, a);
            case "setnom" -> setNom(s, a);
            default -> s.sendMessage(ChatColor.GRAY + "/identite whois <texte> | reset <pseudo> | setnom <pseudo> <nom>");
        }
        return true;
    }

    private boolean cicatriceCommand(CommandSender s, String[] a) {
        UUID id;
        String owner;
        if (a.length > 0 && s.hasPermission("civgo.cicatrice.admin")) {
            OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
            id = t.getUniqueId();
            owner = a[0];
        } else if (s instanceof Player p) {
            id = p.getUniqueId();
            owner = "Tu";
        } else {
            s.sendMessage("Usage : /cicatrice <pseudo>");
            return true;
        }
        if (scar(id) == null) {
            s.sendMessage(ChatColor.GRAY + "Aucune Cicatrice enregistrée.");
        } else {
            s.sendMessage(ChatColor.GRAY + owner + " porte " + ChatColor.LIGHT_PURPLE + scar(id) + ChatColor.GRAY + ".");
        }
        return true;
    }

    private void whois(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage : /identite whois <texte>");
            return;
        }
        String q = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).toLowerCase(Locale.ROOT);
        ConfigurationSection sec = data.getConfigurationSection("players");
        int found = 0;
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                String rp = sec.getString(k + ".name", "");
                String mc = sec.getString(k + ".mc", "?");
                if (rp.toLowerCase(Locale.ROOT).contains(q) || mc.toLowerCase(Locale.ROOT).contains(q)) {
                    s.sendMessage(ChatColor.LIGHT_PURPLE + rp + ChatColor.GRAY + " = " + ChatColor.WHITE + mc);
                    if (++found >= 10) break;
                }
            }
        }
        if (found == 0) s.sendMessage(ChatColor.GRAY + "Aucun résultat.");
    }

    private void reset(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage : /identite reset <pseudo>");
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
        UUID id = t.getUniqueId();
        if (!has(id)) {
            s.sendMessage(ChatColor.RED + "Ce joueur n'a pas d'identité.");
            return;
        }
        data.set("players." + id, null);
        save();
        glowing.remove(id);
        Player online = t.getPlayer();
        if (online != null) {
            online.setDisplayName(online.getName());
            online.setPlayerListName(online.getName());
            online.removePotionEffect(PotionEffectType.GLOWING);
            pending.add(id);
            askName(online);
        }
        s.sendMessage(ChatColor.GRAY + "Identité de " + a[1] + " réinitialisée.");
    }

    private void setNom(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(ChatColor.RED + "Usage : /identite setnom <pseudo> <nom>");
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
        UUID id = t.getUniqueId();
        if (!has(id)) {
            s.sendMessage(ChatColor.RED + "Ce joueur n'a pas d'identité (utilise reset ou attends qu'il en choisisse une).");
            return;
        }
        String n = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).trim();
        if (!NAME.matcher(n).matches()) {
            s.sendMessage(msg("invalid"));
            return;
        }
        if (nameTaken(n) && !n.equalsIgnoreCase(name(id))) {
            s.sendMessage(msg("taken"));
            return;
        }
        data.set("players." + id + ".name", n);
        save();
        if (t.getPlayer() != null) apply(t.getPlayer());
        s.sendMessage(ChatColor.GRAY + a[1] + " s'appelle maintenant " + ChatColor.WHITE + n + ChatColor.GRAY + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (c.getName().equalsIgnoreCase("identite") && a.length == 1 && s.hasPermission("civgo.cicatrice.admin")) {
            return new ArrayList<>(List.of("whois", "reset", "setnom"));
        }
        if (c.getName().equalsIgnoreCase("identite") && a.length > 1 && a[0].equalsIgnoreCase("whois")) {
            return List.of();
        }
        return null;
    }
}
