package fr.civgo.eclats;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fournisseur Vault : EssentialsX (/pay, /balance...) et co utiliseront les Éclats. */
@SuppressWarnings("deprecation")
public class EclatsEconomy implements Economy {

    private final CivGoEclats pl;

    public EclatsEconomy(CivGoEclats pl) {
        this.pl = pl;
    }

    private OfflinePlayer off(String name) {
        return Bukkit.getOfflinePlayer(name);
    }

    private static final String NO_BANK = "CivGo Éclats n'a pas de banques.";

    @Override public boolean isEnabled() { return pl.isEnabled(); }
    @Override public String getName() { return "CivGoEclats"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 0; }

    @Override
    public String format(double amount) {
        long v = Math.round(amount);
        return v + " " + (v == 1 ? currencyNameSingular() : currencyNamePlural());
    }

    @Override public String currencyNamePlural() { return pl.getConfig().getString("currency-plural", "Éclats"); }
    @Override public String currencyNameSingular() { return pl.getConfig().getString("currency-singular", "Éclat"); }

    // --- comptes
    @Override public boolean hasAccount(String name) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p) { return true; }
    @Override public boolean hasAccount(String name, String world) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return true; }

    @Override public boolean createPlayerAccount(String name) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return true; }

    // --- soldes
    @Override public double getBalance(String name) { return getBalance(off(name)); }
    @Override public double getBalance(OfflinePlayer p) { return pl.getBalance(p.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return getBalance(off(name)); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) >= amount; }
    @Override public boolean has(String name, String world, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return getBalance(p) >= amount; }

    // --- retraits
    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdrawPlayer(off(name), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(off(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) {
        UUID id = p.getUniqueId();
        double bal = pl.getBalance(id);
        if (amount < 0) return new EconomyResponse(0, bal, ResponseType.FAILURE, "Montant négatif.");
        if (bal < amount) return new EconomyResponse(0, bal, ResponseType.FAILURE, "Fonds insuffisants.");
        pl.setBalance(id, bal - amount);
        return new EconomyResponse(amount, bal - amount, ResponseType.SUCCESS, null);
    }

    // --- dépôts
    @Override public EconomyResponse depositPlayer(String name, double amount) { return depositPlayer(off(name), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(off(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer p, double amount) {
        UUID id = p.getUniqueId();
        double bal = pl.getBalance(id);
        if (amount < 0) return new EconomyResponse(0, bal, ResponseType.FAILURE, "Montant négatif.");
        pl.setBalance(id, bal + amount);
        return new EconomyResponse(amount, bal + amount, ResponseType.SUCCESS, null);
    }

    // --- banques (non supportées)
    private EconomyResponse noBank() { return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NO_BANK); }

    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return new ArrayList<>(); }
}
