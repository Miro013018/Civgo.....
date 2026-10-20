package fr.civgo.boutique;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * CivGo Boutique : un coffre spécial ouvre une interface où l'on achète tes objets personnalisés avec des Éclats.
 * Le paiement prend d'abord les Éclats que le joueur a en poche, puis son solde (Vault / CivGoEclats).
 */
public class CivGoBoutique extends JavaPlugin implements Listener, TabExecutor {

    private static class ShopItem {
        final ItemStack item;
        long price;            // prix de base
        int max = -1;          // stock maximum (-1 = illimité)
        int stock = -1;        // stock actuel
        int regen = -1;        // exemplaires rendus à chaque réassort (-1 = valeur du config)
        double demand = 1.0;   // monte quand on achète, retombe avec le temps
        double fluct = 1.0;    // variation du marché

        ShopItem(ItemStack item, long price) {
            this.item = item;
            this.price = price;
        }
    }

    private static class ShopHolder implements InventoryHolder {
        int page;
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final List<ShopItem> items = new ArrayList<>();
    private final Set<String> chests = new HashSet<>();
    private File file;
    private NamespacedKey eclatKey;
    private final Random rnd = new Random();
    private int restockLeft = 30, fluctLeft = 30, minutes = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "shop.yml");
        load();
        Plugin ec = Bukkit.getPluginManager().getPlugin("CivGoEclats");
        if (ec != null) eclatKey = new NamespacedKey(ec, "eclat");
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("boutique").setExecutor(this);
        restockLeft = Math.max(1, getConfig().getInt("restock.interval-minutes", 30));
        fluctLeft = Math.max(1, getConfig().getInt("price.fluctuation-interval-minutes", 30));
        Bukkit.getScheduler().runTaskTimer(this, this::minuteTick, 1200L, 1200L);
        if (econ() == null) getLogger().warning("Aucune économie Vault trouvée : installe CivGoEclats pour payer en Éclats.");
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------ données

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        chests.addAll(y.getStringList("chests"));
        ConfigurationSection sec = y.getConfigurationSection("items");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                ItemStack it = sec.getItemStack(k + ".item");
                long price = sec.getLong(k + ".price", 1);
                if (it == null) continue;
                ShopItem si = new ShopItem(it, price);
                si.max = sec.getInt(k + ".max", -1);
                si.stock = sec.getInt(k + ".stock", si.max);
                si.regen = sec.getInt(k + ".regen", -1);
                si.demand = sec.getDouble(k + ".demand", 1.0);
                si.fluct = sec.getDouble(k + ".fluct", 1.0);
                items.add(si);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("chests", new ArrayList<>(chests));
        for (int i = 0; i < items.size(); i++) {
            String k = String.format("items.%03d", i + 1);
            y.set(k + ".item", items.get(i).item);
            y.set(k + ".price", items.get(i).price);
            y.set(k + ".max", items.get(i).max);
            y.set(k + ".stock", items.get(i).stock);
            y.set(k + ".regen", items.get(i).regen);
            y.set(k + ".demand", items.get(i).demand);
            y.set(k + ".fluct", items.get(i).fluct);
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde impossible : " + ex.getMessage());
        }
    }

    private String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** Le bloc, plus sa moitié s'il s'agit d'un grand coffre. */
    private List<Block> related(Block b) {
        List<Block> out = new ArrayList<>();
        out.add(b);
        if (b.getState() instanceof Chest ch && ch.getInventory().getHolder() instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest l) out.add(l.getBlock());
            if (dc.getRightSide() instanceof Chest r) out.add(r.getBlock());
        }
        return out;
    }

    // ------------------------------------------------------------ économie

    private Economy econ() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private boolean isEclat(ItemStack it) {
        if (eclatKey == null || it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(eclatKey, PersistentDataType.BYTE);
    }

    private int pocket(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isEclat(it)) n += it.getAmount();
        }
        return n;
    }

    private double bank(Player p) {
        Economy e = econ();
        return e == null ? 0 : e.getBalance(p);
    }

    private void removePocket(Player p, int n) {
        int left = n;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            if (!isEclat(c[i])) continue;
            int take = Math.min(left, c[i].getAmount());
            left -= take;
            if (take == c[i].getAmount()) c[i] = null;
            else c[i].setAmount(c[i].getAmount() - take);
        }
        p.getInventory().setStorageContents(c);
    }

    private String eclats(long n) {
        return n + (n == 1 ? " Éclat" : " Éclats");
    }

    private boolean noirActive() {
        for (World w : Bukkit.getWorlds()) {
            for (MetadataValue v : w.getMetadata("civgo_noir")) {
                if (v.asBoolean()) return true;
            }
        }
        return false;
    }

    /** Prix actuel = base x demande x marché x rareté x Noir. */
    private long currentPrice(ShopItem si) {
        double p = si.price * si.demand * si.fluct;
        if (si.max > 0) {
            double ratio = 1.0 - (double) Math.max(0, si.stock) / si.max;
            p *= 1.0 + getConfig().getDouble("price.scarcity-strength", 0.5) * ratio;
        }
        if (noirActive()) p *= getConfig().getDouble("price.noir-multiplier", 1.25);
        return Math.max(1, Math.round(p));
    }

    private void minuteTick() {
        minutes++;
        double decay = getConfig().getDouble("price.demand-decay-per-minute", 0.97);
        for (ShopItem si : items) {
            si.demand = 1.0 + (si.demand - 1.0) * decay;
        }
        if (--restockLeft <= 0) {
            restockLeft = Math.max(1, getConfig().getInt("restock.interval-minutes", 30));
            int def = getConfig().getInt("restock.default-amount", 2);
            for (ShopItem si : items) {
                if (si.max >= 0 && si.stock < si.max) {
                    si.stock = Math.min(si.max, si.stock + (si.regen >= 0 ? si.regen : def));
                }
            }
        }
        if (--fluctLeft <= 0) {
            fluctLeft = Math.max(1, getConfig().getInt("price.fluctuation-interval-minutes", 30));
            double f = getConfig().getDouble("price.fluctuation", 0.08);
            for (ShopItem si : items) {
                double nf = si.fluct + (1.0 - si.fluct) * 0.15 + (rnd.nextDouble() * 2 - 1) * f;
                si.fluct = Math.max(0.7, Math.min(1.6, nf));
            }
        }
        if (minutes % 5 == 0) save();
    }

    private void buy(Player p, ShopItem si, int wanted) {
        if (si.max >= 0 && si.stock <= 0) {
            p.sendMessage(ChatColor.RED + "Cet objet est épuisé. Il reviendra petit à petit.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        int times = si.max >= 0 ? Math.min(wanted, si.stock) : wanted;
        long unit = currentPrice(si);
        long total = unit * times;
        int pocket = pocket(p);
        double bank = bank(p);
        if (pocket + bank < total) {
            p.sendMessage(ChatColor.RED + "Il te faut " + eclats(total) + " (tu as " + pocket + " en poche et "
                    + Math.round(bank) + " sur ton solde).");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        int fromPocket = (int) Math.min(pocket, total);
        long rest = total - fromPocket;
        if (rest > 0) {
            Economy e = econ();
            if (e == null) {
                p.sendMessage(ChatColor.RED + "L'économie est indisponible. Préviens un admin.");
                return;
            }
            EconomyResponse r = e.withdrawPlayer(p, rest);
            if (!r.transactionSuccess()) {
                p.sendMessage(ChatColor.RED + "Paiement refusé : " + r.errorMessage);
                return;
            }
        }
        if (fromPocket > 0) removePocket(p, fromPocket);

        for (int i = 0; i < times; i++) {
            for (ItemStack over : p.getInventory().addItem(si.item.clone()).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), over);
            }
        }
        if (si.max >= 0) si.stock -= times;
        si.demand = Math.min(getConfig().getDouble("price.demand-max", 2.0),
                si.demand + getConfig().getDouble("price.demand-step", 0.04) * times);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "Achat : " + name(si.item) + ChatColor.LIGHT_PURPLE + " x"
                + (si.item.getAmount() * times) + " pour " + eclats(total) + ".");
    }

    // ------------------------------------------------------------ interface

    private String name(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return m.getDisplayName();
        return ChatColor.WHITE + it.getType().name().toLowerCase().replace('_', ' ');
    }

    private ItemStack display(ShopItem si) {
        ItemStack it = si.item.clone();
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            lore.add("");
            long cur = currentPrice(si);
            long pct = Math.round((cur / (double) si.price - 1.0) * 100.0);
            String trend = pct > 0 ? ChatColor.RED + " ▲ +" + pct + " %" : pct < 0 ? ChatColor.GREEN + " ▼ " + pct + " %" : ChatColor.GRAY + " =";
            lore.add(ChatColor.LIGHT_PURPLE + "Prix : " + ChatColor.WHITE + eclats(cur) + trend);
            if (si.max < 0) {
                lore.add(ChatColor.GRAY + "Stock : illimité");
            } else if (si.stock <= 0) {
                lore.add(ChatColor.RED + "ÉPUISÉ " + ChatColor.GRAY + "(réassort dans ~" + restockLeft + " min)");
            } else {
                lore.add(ChatColor.GRAY + "Stock : " + ChatColor.WHITE + si.stock + ChatColor.GRAY + " / " + si.max
                        + (si.stock < si.max ? " (réassort dans ~" + restockLeft + " min)" : ""));
            }
            lore.add(ChatColor.GRAY + "Clic gauche : acheter");
            lore.add(ChatColor.GRAY + "Shift + clic : acheter x" + getConfig().getInt("shift-multiplier", 5));
            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null) m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    private void fillInfo(Player p, Inventory inv) {
        inv.setItem(49, icon(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "Tes Éclats",
                List.of(ChatColor.GRAY + "En poche : " + ChatColor.WHITE + pocket(p),
                        ChatColor.GRAY + "Sur ton solde : " + ChatColor.WHITE + Math.round(bank(p)),
                        ChatColor.DARK_GRAY + "Les Éclats en poche sont dépensés d'abord.")));
    }

    private void fillPage(Inventory inv, int page) {
        for (int s = 0; s < 45; s++) {
            int idx = page * 45 + s;
            inv.setItem(s, idx < items.size() ? display(items.get(idx)) : null);
        }
    }

    private void open(Player p, int page) {
        int pages = Math.max(1, (items.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        ShopHolder h = new ShopHolder();
        h.page = page;
        Inventory inv = Bukkit.createInventory(h, 54, "§5✦ §dBoutique des Éclats §8(" + (page + 1) + "/" + pages + ")");
        h.inv = inv;
        fillPage(inv, page);
        ItemStack glass = icon(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int s = 45; s < 54; s++) inv.setItem(s, glass);
        if (page > 0) inv.setItem(45, icon(Material.ARROW, ChatColor.GRAY + "Page précédente", null));
        if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, ChatColor.GRAY + "Page suivante", null));
        fillInfo(p, inv);
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!top.equals(e.getClickedInventory())) return;
        int slot = e.getRawSlot();
        if (slot == 45 && h.page > 0) {
            Bukkit.getScheduler().runTask(this, () -> open(p, h.page - 1));
            return;
        }
        if (slot == 53 && slot < 54 && top.getItem(53) != null && top.getItem(53).getType() == Material.ARROW) {
            Bukkit.getScheduler().runTask(this, () -> open(p, h.page + 1));
            return;
        }
        if (slot < 0 || slot >= 45) return;
        int idx = h.page * 45 + slot;
        if (idx >= items.size()) return;
        int times = e.isShiftClick() ? Math.max(1, getConfig().getInt("shift-multiplier", 5)) : 1;
        buy(p, items.get(idx), times);
        fillPage(top, h.page);
        fillInfo(p, top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ coffre spécial

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !chests.contains(key(b))) return;
        e.setCancelled(true);
        open(e.getPlayer(), 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!chests.contains(key(b))) return;
        if (!e.getPlayer().hasPermission("civgo.boutique.admin")) {
            e.setCancelled(true);
            return;
        }
        for (Block r : related(b)) chests.remove(key(r));
        save();
        e.getPlayer().sendMessage(ChatColor.GRAY + "Coffre boutique supprimé.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> chests.contains(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> chests.contains(key(b)));
    }

    // ------------------------------------------------------------ commandes

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("civgo.boutique.admin")) {
            s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        String sub = a.length == 0 ? "aide" : a[0].toLowerCase();
        switch (sub) {
            case "coffre" -> {
                if (!(s instanceof Player p)) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    s.sendMessage(ChatColor.RED + "Regarde le bloc (coffre, tonneau...) à transformer, à moins de 6 blocs.");
                    return true;
                }
                if (chests.contains(key(b))) {
                    for (Block r : related(b)) chests.remove(key(r));
                    s.sendMessage(ChatColor.GRAY + "Ce bloc n'est plus un coffre boutique.");
                } else {
                    for (Block r : related(b)) chests.add(key(r));
                    s.sendMessage(ChatColor.LIGHT_PURPLE + "Coffre boutique créé : un clic droit dessus ouvre la boutique.");
                }
                save();
            }
            case "ajouter" -> {
                if (!(s instanceof Player p)) return true;
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    s.sendMessage(ChatColor.RED + "Tiens dans ta main l'objet à vendre (la quantité en main = la quantité vendue par achat).");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage : /boutique ajouter <prix en Éclats> [stock]");
                    return true;
                }
                long price = parse(s, a[1]);
                if (price < 1) return true;
                ShopItem added = new ShopItem(hand.clone(), price);
                if (a.length > 2) {
                    long st = parse(s, a[2]);
                    if (st < 1) return true;
                    added.max = (int) st;
                    added.stock = (int) st;
                }
                items.add(added);
                save();
                s.sendMessage(ChatColor.LIGHT_PURPLE + "Ajouté (n°" + items.size() + ") : " + name(hand) + ChatColor.LIGHT_PURPLE
                        + " x" + hand.getAmount() + " pour " + eclats(price) + (added.max >= 0 ? ", stock " + added.max : ", stock illimité") + ".");
            }
            case "prix" -> {
                if (a.length < 3) {
                    s.sendMessage(ChatColor.RED + "Usage : /boutique prix <n°> <prix>");
                    return true;
                }
                int idx = (int) parse(s, a[1]) - 1;
                long price = parse(s, a[2]);
                if (price < 1) return true;
                if (idx < 0 || idx >= items.size()) {
                    s.sendMessage(ChatColor.RED + "Numéro inconnu (voir /boutique liste).");
                    return true;
                }
                items.get(idx).price = price;
                save();
                s.sendMessage(ChatColor.LIGHT_PURPLE + "Nouveau prix : " + eclats(price) + ".");
            }
            case "retirer" -> {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage : /boutique retirer <n°>");
                    return true;
                }
                int idx = (int) parse(s, a[1]) - 1;
                if (idx < 0 || idx >= items.size()) {
                    s.sendMessage(ChatColor.RED + "Numéro inconnu (voir /boutique liste).");
                    return true;
                }
                ShopItem removed = items.remove(idx);
                save();
                s.sendMessage(ChatColor.GRAY + "Retiré : " + name(removed.item));
            }
            case "liste" -> {
                if (items.isEmpty()) {
                    s.sendMessage(ChatColor.GRAY + "La boutique est vide. Tiens un objet et tape /boutique ajouter <prix>.");
                    return true;
                }
                for (int i = 0; i < items.size(); i++) {
                    ShopItem si = items.get(i);
                    s.sendMessage(ChatColor.LIGHT_PURPLE + "" + (i + 1) + ". " + name(si.item) + ChatColor.GRAY + " x" + si.item.getAmount()
                            + " - base " + ChatColor.WHITE + eclats(si.price) + ChatColor.GRAY + ", actuel " + ChatColor.WHITE + eclats(currentPrice(si))
                            + ChatColor.GRAY + ", stock " + (si.max < 0 ? "illimité" : si.stock + "/" + si.max
                            + (si.regen >= 0 ? " (+" + si.regen + ")" : "")));
                }
            }
            case "stock" -> {
                if (a.length < 3) {
                    s.sendMessage(ChatColor.RED + "Usage : /boutique stock <n°> <max|illimite> [exemplaires rendus à chaque réassort]");
                    return true;
                }
                int idx = (int) parse(s, a[1]) - 1;
                if (idx < 0 || idx >= items.size()) {
                    s.sendMessage(ChatColor.RED + "Numéro inconnu (voir /boutique liste).");
                    return true;
                }
                ShopItem si = items.get(idx);
                if (a[2].toLowerCase().startsWith("illimit")) {
                    si.max = -1;
                    si.stock = -1;
                } else {
                    long mx = parse(s, a[2]);
                    if (mx < 1) return true;
                    si.max = (int) mx;
                    si.stock = (int) mx;
                }
                if (a.length > 3) {
                    long rg = parse(s, a[3]);
                    if (rg < 1) return true;
                    si.regen = (int) rg;
                }
                save();
                s.sendMessage(ChatColor.LIGHT_PURPLE + "Stock de " + name(si.item) + ChatColor.LIGHT_PURPLE + " : "
                        + (si.max < 0 ? "illimité" : si.max + (si.regen >= 0 ? " (+" + si.regen + " par réassort)" : "")) + ".");
            }
            case "marche" -> {
                if (a.length < 3) {
                    s.sendMessage(ChatColor.RED + "Usage : /boutique marche <n°|tous> <multiplicateur>  (ex : 1.5 = +50 %, 0.7 = -30 %)");
                    return true;
                }
                double mult;
                try {
                    mult = Math.max(0.3, Math.min(3.0, Double.parseDouble(a[2].replace(',', '.'))));
                } catch (NumberFormatException ex) {
                    s.sendMessage(ChatColor.RED + "Multiplicateur invalide.");
                    return true;
                }
                if (a[1].equalsIgnoreCase("tous")) {
                    for (ShopItem si : items) si.fluct = mult;
                } else {
                    int idx = (int) parse(s, a[1]) - 1;
                    if (idx < 0 || idx >= items.size()) {
                        s.sendMessage(ChatColor.RED + "Numéro inconnu (voir /boutique liste).");
                        return true;
                    }
                    items.get(idx).fluct = mult;
                }
                save();
                s.sendMessage(ChatColor.LIGHT_PURPLE + "Le marché est à x" + mult + " (il revient ensuite doucement à la normale).");
            }
            case "ouvrir" -> {
                if (s instanceof Player p) open(p, 0);
            }
            default -> {
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique coffre" + ChatColor.GRAY + " : (dé)marque le bloc regardé comme coffre boutique");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique ajouter <prix> [stock]" + ChatColor.GRAY + " : vend l'objet que tu tiens");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique prix <n°> <prix>" + ChatColor.GRAY + " : change un prix");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique retirer <n°>" + ChatColor.GRAY + " : retire un objet");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique stock <n°> <max|illimite> [réassort]" + ChatColor.GRAY + " : stock limité");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique marche <n°|tous> <x>" + ChatColor.GRAY + " : choc de marché (prix)");
                s.sendMessage(ChatColor.LIGHT_PURPLE + "/boutique liste" + ChatColor.GRAY + " | " + ChatColor.LIGHT_PURPLE + "/boutique ouvrir");
            }
        }
        return true;
    }

    private long parse(CommandSender s, String str) {
        try {
            long n = Long.parseLong(str);
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            s.sendMessage(ChatColor.RED + "Nombre invalide (entier positif attendu).");
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1 && s.hasPermission("civgo.boutique.admin")) {
            return List.of("coffre", "ajouter", "prix", "retirer", "liste", "ouvrir", "stock", "marche");
        }
        return List.of();
    }
}
