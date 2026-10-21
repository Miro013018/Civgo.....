package fr.civgo.objets;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CivGo Objets : donne un pouvoir à chaque objet du pack de ressources CivGo.
 * Les objets sont reconnus grâce à leur modèle (composant item_model "civgo:<nom>"), donc ceux donnés par /give marchent aussi.
 */
public class CivGoObjets extends JavaPlugin implements Listener, TabExecutor {

    private static final String TAG = "civgo_lantern";

    private record Def(String id, Material base, String color, String name, List<String> lore) {}

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Long> waveCooldown = new HashMap<>();
    private final Set<String> placed = new HashSet<>();
    private final Map<String, UUID[]> lanterns = new HashMap<>();   // "monde,x,y,z" -> {affichage, interaction}
    private NamespacedKey minedKey;
    private File lanternFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        minedKey = new NamespacedKey(this, "mined");
        defs();
        lanternFile = new File(getDataFolder(), "lanternes.yml");
        loadLanterns();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("objets").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::helmetTick, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::lanternTick, 40L, 20L);
    }

    @Override
    public void onDisable() {
        saveLanterns();
    }

    // ------------------------------------------------------------ objets

    private void defs() {
        add("boussole_cratere", Material.STICK, "§6", "Boussole du Cratère",
                "§7Clic droit : direction et distance du Cratère.");
        add("epee_lune", Material.NETHERITE_SWORD, "§d", "Épée de Lune",
                "§7Frappe lunaire : +25 % de dégâts aux monstres, la cible luit 4 s.",
                "§7Pendant un Noir : +25 % de dégâts et vol de vie.",
                "§7Clic droit : Onde lunaire (recharge 20 s).");
        add("pioche_eclat", Material.NETHERITE_PICKAXE, "§d", "Pioche d'Éclat",
                "§7Tous les 150 blocs minés : +1 Éclat sur ton solde.");
        add("arc_noirs", Material.BOW, "§5", "Arc des Noirs",
                "§7Tire des Éclats : +70 % de dégâts, éclats lumineux à l'impact.",
                "§7Révèle la Cicatrice du joueur touché pendant 8 s.");
        add("casque_lunaire", Material.NETHERITE_HELMET, "§f", "Casque Lunaire",
                "§7Annule l'obscurité des Noirs et du Cratère.",
                "§7Pendant un Noir : vision nocturne, -10 % de dégâts des monstres.");
        add("bouclier_chute", Material.SHIELD, "§d", "Bouclier de la Chute",
                "§7Maintiens le clic droit : protège de TOUS les côtés.");
        add("lanterne_lunaire", Material.STICK, "§b", "Lanterne Lunaire",
                "§7Pose-la sur le sol ou au plafond (clic).",
                "§7Elle ne luit que pendant les Noirs, d'une lueur violette.");
        add("amulette_cicatrice", Material.STICK, "§6", "Amulette de Cicatrice",
                "§7Tenue en main secondaire : cache ta Cicatrice pendant les Noirs.",
                "§7L'Arc des Noirs la révèle quand même.");
        add("fiole_lueur", Material.POTION, "§d", "Fiole de Lueur",
                "§7Vision nocturne.");
    }

    private void add(String id, Material base, String color, String name, String... lore) {
        defs.put(id, new Def(id, base, color, name, List.of(lore)));
    }

    private ItemStack make(String id) {
        Def d = defs.get(id);
        ItemStack it = new ItemStack(d.base());
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("civgo:" + id));
        m.setDisplayName(d.color() + d.name());
        m.setLore(d.lore());
        if (m instanceof PotionMeta pm) pm.setBasePotionType(PotionType.NIGHT_VISION);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack lanternItem(boolean lit) {
        ItemStack it = make("lanterne_lunaire");
        if (lit) {
            ItemMeta m = it.getItemMeta();
            m.setItemModel(NamespacedKey.fromString("civgo:lanterne_lunaire_allumee"));
            it.setItemMeta(m);
        }
        return it;
    }

    /** Nom du modèle CivGo d'un objet (ex : "epee_lune"), ou null. */
    private String model(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta m = it.getItemMeta();
        if (!m.hasItemModel()) return null;
        NamespacedKey k = m.getItemModel();
        return k != null && k.getNamespace().equals("civgo") ? k.getKey() : null;
    }

    private boolean noirActive() {
        for (World w : Bukkit.getWorlds()) {
            for (MetadataValue v : w.getMetadata("civgo_noir")) {
                if (v.asBoolean()) return true;
            }
        }
        return false;
    }

    private Economy econ() {
        RegisteredServiceProvider<Economy> r = Bukkit.getServicesManager().getRegistration(Economy.class);
        return r == null ? null : r.getProvider();
    }

    private void bar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private double maxHealth(Player p) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        AttributeInstance inst = a == null ? null : p.getAttribute(a);
        return inst == null ? 20.0 : inst.getValue();
    }

    private static Particle.DustOptions violet(float size) {
        return new Particle.DustOptions(Color.fromRGB(180, 100, 255), size);
    }

    // ------------------------------------------------------------ Épée de Lune, Casque, Bouclier (dégâts)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // Épée de Lune
        if (e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity t
                && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && "epee_lune".equals(model(p.getInventory().getItemInMainHand()))) {
            double mult = 1.0;
            if (t instanceof Monster) mult *= getConfig().getDouble("epee.monster-multiplier", 1.25);
            boolean noir = noirActive();
            if (noir) mult *= getConfig().getDouble("epee.noir-multiplier", 1.25);
            e.setDamage(e.getDamage() * mult);
            t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, false, false));
            t.getWorld().spawnParticle(Particle.END_ROD, t.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
            if (noir) {
                double heal = Math.min(2.0, e.getDamage() * 0.1);
                p.setHealth(Math.min(maxHealth(p), p.getHealth() + heal));
            }
        }

        if (e.getEntity() instanceof Player v) {
            // Casque Lunaire : moins de dégâts des monstres pendant un Noir
            if (e.getDamager() instanceof Monster && noirActive()
                    && "casque_lunaire".equals(model(v.getInventory().getHelmet()))) {
                e.setDamage(e.getDamage() * (1.0 - getConfig().getDouble("casque.damage-reduction-noir", 0.10)));
            }
            shieldAllSides(e, v);
        }
    }

    /** Bouclier de la Chute : bloque aussi les coups venant des côtés et de dos. */
    private void shieldAllSides(EntityDamageByEntityEvent e, Player v) {
        if (!getConfig().getBoolean("bouclier.protect-all-sides", true)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c != EntityDamageEvent.DamageCause.ENTITY_ATTACK && c != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && c != EntityDamageEvent.DamageCause.PROJECTILE && c != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        if (!v.isBlocking()) return;
        ItemStack shield = v.getActiveItem();
        if (!"bouclier_chute".equals(model(shield))) return;

        // le bouclier vanilla bloque déjà tout ce qui vient de face (180° devant)
        Vector toVictim = v.getLocation().toVector().subtract(e.getDamager().getLocation().toVector());
        toVictim.setY(0);
        if (toVictim.lengthSquared() > 1.0E-6) {
            toVictim.normalize();
            if (toVictim.dot(v.getLocation().getDirection().setY(0).normalize()) < 0) return;
        }
        double dmg = e.getFinalDamage();
        e.setCancelled(true);
        v.getWorld().playSound(v.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        v.getWorld().spawnParticle(Particle.DUST, v.getLocation().add(0, 1, 0), 14, 0.5, 0.6, 0.5, 0, violet(1.2f));
        if (shield.getItemMeta() instanceof Damageable dm) {
            dm.setDamage(dm.getDamage() + Math.max(1, (int) Math.round(dmg)));
            shield.setItemMeta(dm);
            if (dm.getDamage() >= shield.getType().getMaxDurability()) {
                shield.setAmount(0);
                v.getWorld().playSound(v.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
            }
        }
    }

    // ------------------------------------------------------------ Épée : Onde lunaire / Boussole (clic droit)

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = model(hand);
        if (id == null) return;

        if (id.equals("lanterne_lunaire") && a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            placeLantern(p, e);
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        if (id.equals("epee_lune")) {
            long now = System.currentTimeMillis();
            long cd = getConfig().getInt("epee.wave-cooldown-seconds", 20) * 1000L;
            Long last = waveCooldown.get(p.getUniqueId());
            if (last != null && now - last < cd) {
                bar(p, "§7Onde lunaire prête dans " + ((cd - (now - last)) / 1000 + 1) + " s");
                return;
            }
            waveCooldown.put(p.getUniqueId(), now);
            lunarWave(p);
        } else if (id.equals("boussole_cratere")) {
            compass(p);
        }
    }

    private void lunarWave(Player p) {
        double r = getConfig().getDouble("epee.wave-radius", 5);
        double dmg = getConfig().getDouble("epee.wave-damage", 8);
        Location c = p.getLocation().add(0, 0.2, 0);
        p.getWorld().playSound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 0.6f);
        p.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
        for (int i = 0; i < 40; i++) {                          // anneau de lumière
            double ang = Math.PI * 2 * i / 40;
            p.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(ang) * r, 0.3, Math.sin(ang) * r), 1, 0, 0, 0, 0);
            p.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * r * 0.6, 0.4, Math.sin(ang) * r * 0.6), 1, 0, 0, 0, 0, violet(1.5f));
        }
        for (Entity en : p.getNearbyEntities(r, 2.5, r)) {
            if (!(en instanceof Monster m)) continue;
            m.damage(dmg, p);
            Vector kb = m.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0.3);
            if (kb.lengthSquared() > 1.0E-6) m.setVelocity(kb.normalize().multiply(0.9).setY(0.35));
            m.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, true, false, false));
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, true));
    }

    private void compass(Player p) {
        Plugin n = Bukkit.getPluginManager().getPlugin("CivGoNoirs");
        if (n == null) {
            bar(p, "§7La boussole ne trouve pas le Cratère.");
            return;
        }
        double cx = n.getConfig().getDouble("crater.x", 0), cz = n.getConfig().getDouble("crater.z", 0);
        double dx = cx - p.getLocation().getX(), dz = cz - p.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = bearing - p.getLocation().getYaw();
        while (rel > 180) rel -= 360;
        while (rel < -180) rel += 360;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int idx = (int) Math.round(rel / 45.0);
        if (idx < 0) idx += 8;
        bar(p, "§6✦ Cratère §f" + arrows[idx % 8] + " §7" + (int) dist + " blocs");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    // ------------------------------------------------------------ Arc des Noirs

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player) || !"arc_noirs".equals(model(e.getBow()))) return;
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;
        arrow.setDamage(arrow.getDamage() * getConfig().getDouble("arc.damage-multiplier", 1.7));
        arrow.setCritical(true);
        arrow.setMetadata("civgo_arc", new FixedMetadataValue(this, e.getEntity().getUniqueId().toString()));
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isInBlock() || ++t > 100) {
                    cancel();
                    return;
                }
                Location l = arrow.getLocation();
                arrow.getWorld().spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0, violet(1.1f));
                arrow.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow) || !arrow.hasMetadata("civgo_arc")) return;
        Entity hit = e.getHitEntity();
        Location l = hit != null ? hit.getLocation().add(0, 1.0, 0) : arrow.getLocation();
        World w = l.getWorld();
        w.spawnParticle(Particle.END_ROD, l, 28, 0.35, 0.35, 0.35, 0.07);
        w.spawnParticle(Particle.DUST, l, 36, 0.4, 0.4, 0.4, 0, violet(1.6f));
        w.spawnParticle(Particle.GLOW, l, 12, 0.4, 0.4, 0.4, 0.02);
        w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f);
        if (hit instanceof Player target && !arrow.getMetadata("civgo_arc").isEmpty()
                && !target.getUniqueId().toString().equals(arrow.getMetadata("civgo_arc").get(0).asString())) {
            int secs = getConfig().getInt("arc.reveal-seconds", 8);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cicatrice reveal " + target.getName() + " " + secs);
            target.sendMessage(ChatColor.LIGHT_PURPLE + "Un éclat t'a touché : ta Cicatrice est révélée !");
            Player shooter = Bukkit.getPlayer(UUID.fromString(arrow.getMetadata("civgo_arc").get(0).asString()));
            if (shooter != null) bar(shooter, "§d✦ Cicatrice révélée : §f" + target.getDisplayName());
        }
    }

    // ------------------------------------------------------------ Pioche d'Éclat

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent e) {
        if (placed.size() > 50000) placed.clear();
        placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!"pioche_eclat".equals(model(tool)) || p.getGameMode() != GameMode.SURVIVAL) return;
        Block b = e.getBlock();
        if (placed.remove(key(b))) return;                  // pas de gain sur un bloc posé par un joueur
        if (b.getType().getHardness() < 0.5f) return;

        int goal = Math.max(1, getConfig().getInt("pioche.blocks-per-eclat", 150));
        ItemMeta m = tool.getItemMeta();
        int n = m.getPersistentDataContainer().getOrDefault(minedKey, PersistentDataType.INTEGER, 0) + 1;
        boolean reward = n >= goal;
        if (reward) n -= goal;
        m.getPersistentDataContainer().set(minedKey, PersistentDataType.INTEGER, n);
        tool.setItemMeta(m);
        p.getInventory().setItemInMainHand(tool);

        if (reward) {
            Economy ec = econ();
            if (ec != null) ec.depositPlayer(p, 1);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ La pioche extrait un Éclat : +1 Éclat sur ton solde.");
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.3f);
            p.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 0.5, 0.5), 16, 0.3, 0.3, 0.3, 0.05);
        } else if (n % 10 == 0) {
            bar(p, "§dPioche d'Éclat §7: " + n + " / " + goal);
        }
    }

    private String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    // ------------------------------------------------------------ Casque Lunaire

    private void helmetTick() {
        boolean noir = noirActive();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!"casque_lunaire".equals(model(p.getInventory().getHelmet()))) continue;
            p.removePotionEffect(PotionEffectType.DARKNESS);
            if (noir) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 15, 0, true, false, false));
        }
    }

    // ------------------------------------------------------------ Lanterne Lunaire (objet posable)

    private void placeLantern(Player p, PlayerInteractEvent e) {
        BlockFace f = e.getBlockFace();
        if (f != BlockFace.UP && f != BlockFace.DOWN) {
            bar(p, "§7Pose la lanterne sur le sol ou contre un plafond.");
            return;
        }
        Block target = e.getClickedBlock().getRelative(f);
        if (!target.getType().isAir() || lanterns.containsKey(key(target))) return;
        World w = target.getWorld();
        Location loc = target.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, dd -> {
            dd.setItemStack(lanternItem(false));
            dd.setBrightness(new Display.Brightness(6, 6));
            dd.addScoreboardTag(TAG);
        });
        Interaction in = w.spawn(target.getLocation().add(0.5, 0.05, 0.5), Interaction.class, ii -> {
            ii.setInteractionWidth(0.5f);
            ii.setInteractionHeight(0.95f);
            ii.addScoreboardTag(TAG);
        });
        lanterns.put(key(target), new UUID[]{d.getUniqueId(), in.getUniqueId()});
        saveLanterns();
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
        w.playSound(loc, Sound.BLOCK_LANTERN_PLACE, 1f, 1f);
    }

    @EventHandler
    public void onLanternHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction in) || !in.getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true);
        if (!(e.getDamager() instanceof Player p)) return;
        for (Map.Entry<String, UUID[]> en : new ArrayList<>(lanterns.entrySet())) {
            if (!en.getValue()[1].equals(in.getUniqueId())) continue;
            removeLantern(en.getKey(), en.getValue(), p.getGameMode() != GameMode.CREATIVE);
            return;
        }
        in.remove();
    }

    private void removeLantern(String k, UUID[] ids, boolean drop) {
        String[] s = k.split(",");
        World w = Bukkit.getWorld(s[0]);
        if (w != null) {
            Block b = w.getBlockAt(Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]));
            if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
            if (drop) w.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), lanternItem(false));
        }
        for (UUID id : ids) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.remove();
        }
        lanterns.remove(k);
        saveLanterns();
    }

    /** Toutes les secondes : la lanterne ne luit que pendant les Noirs (lumière + particules violettes). */
    private void lanternTick() {
        if (lanterns.isEmpty()) return;
        boolean noir = noirActive();
        for (Map.Entry<String, UUID[]> en : new ArrayList<>(lanterns.entrySet())) {
            String[] s = en.getKey().split(",");
            World w = Bukkit.getWorld(s[0]);
            if (w == null) continue;
            int x = Integer.parseInt(s[1]), y = Integer.parseInt(s[2]), z = Integer.parseInt(s[3]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Entity d = Bukkit.getEntity(en.getValue()[0]);
            if (!(d instanceof ItemDisplay disp)) {              // l'objet a disparu (kill, etc.)
                removeLantern(en.getKey(), en.getValue(), false);
                continue;
            }
            Block b = w.getBlockAt(x, y, z);
            if (noir) {
                if (b.getType().isAir() || b.getType() == Material.LIGHT) {
                    Light l = (Light) Material.LIGHT.createBlockData();
                    l.setLevel(15);
                    b.setBlockData(l, false);
                }
                disp.setItemStack(lanternItem(true));
                disp.setBrightness(new Display.Brightness(15, 15));
                Location c = b.getLocation().add(0.5, 0.5, 0.5);
                w.spawnParticle(Particle.DUST, c, 8, 0.35, 0.4, 0.35, 0, violet(1.2f));
                w.spawnParticle(Particle.END_ROD, c, 1, 0.3, 0.4, 0.3, 0.01);
            } else {
                if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
                disp.setItemStack(lanternItem(false));
                disp.setBrightness(new Display.Brightness(6, 6));
            }
        }
    }

    private void loadLanterns() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(lanternFile);
        for (String line : y.getStringList("lanternes")) {
            String[] p = line.split(";");
            if (p.length == 3) lanterns.put(p[0], new UUID[]{UUID.fromString(p[1]), UUID.fromString(p[2])});
        }
    }

    private void saveLanterns() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, UUID[]> en : lanterns.entrySet()) {
            out.add(en.getKey() + ";" + en.getValue()[0] + ";" + en.getValue()[1]);
        }
        y.set("lanternes", out);
        try {
            getDataFolder().mkdirs();
            y.save(lanternFile);
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde des lanternes impossible : " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("civgo.objets.admin")) {
            s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("liste")) {
            s.sendMessage(ChatColor.LIGHT_PURPLE + "Objets : " + ChatColor.WHITE + String.join(", ", defs.keySet()));
            s.sendMessage(ChatColor.GRAY + "/objets donner <objet> [joueur]");
            return true;
        }
        if (a[0].equalsIgnoreCase("donner") && a.length >= 2) {
            if (!defs.containsKey(a[1].toLowerCase())) {
                s.sendMessage(ChatColor.RED + "Objet inconnu. Tape /objets liste.");
                return true;
            }
            Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pl ? pl : null);
            if (t == null) {
                s.sendMessage(ChatColor.RED + "Joueur introuvable.");
                return true;
            }
            for (ItemStack over : t.getInventory().addItem(make(a[1].toLowerCase())).values()) {
                t.getWorld().dropItemNaturally(t.getLocation(), over);
            }
            s.sendMessage(ChatColor.LIGHT_PURPLE + defs.get(a[1].toLowerCase()).name() + " donné à " + t.getName() + ".");
            return true;
        }
        s.sendMessage(ChatColor.GRAY + "/objets liste | /objets donner <objet> [joueur]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) return List.of("liste", "donner");
        if (a.length == 2 && a[0].equalsIgnoreCase("donner")) return new ArrayList<>(defs.keySet());
        return null;
    }
}
