package de.nucleus.module.economy;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Klassische Vault-Schnittstelle (net.milkbowl.vault) für ältere Plugins. Diese API arbeitet mit
 * double und Spielernamen; umgerechnet wird nur hier am Rand, intern bleibt alles in Cent.
 * VaultUnlocked führt modern und klassisch getrennt, deshalb meldet Nucleus beide an.
 */
@SuppressWarnings("deprecation") // die Namens-Methoden sind in Vault selbst deprecated, gehören aber zur Schnittstelle
final class LegacyVaultEconomy extends AbstractEconomy {

    private final VaultBridge bridge;

    LegacyVaultEconomy(VaultBridge bridge) {
        this.bridge = bridge;
    }

    private static BigDecimal dec(double amount) {
        return BigDecimal.valueOf(amount);
    }

    private static EconomyResponse response(VaultBridge.Result r) {
        return new EconomyResponse(r.amount().doubleValue(), r.balance().doubleValue(),
                r.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, r.error());
    }

    private static EconomyResponse unknownPlayer(double amount) {
        return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "Unbekannter Spieler");
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Nucleus hat keine Banken");
    }

    /** Name → UUID; unbekannte Namen ergeben einen Fehler statt eines neuen Kontos. */
    private EconomyResponse byName(String name, double amount, Function<UUID, VaultBridge.Result> action) {
        UUID id = VaultBridge.uuidOf(name);
        return id == null ? unknownPlayer(amount) : response(action.apply(id));
    }

    // --- Allgemein ---

    @Override
    public boolean isEnabled() {
        return bridge.enabled();
    }

    @Override
    public String getName() {
        return VaultBridge.NAME;
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return bridge.decimals();
    }

    @Override
    public String format(double amount) {
        return bridge.format(dec(amount));
    }

    @Override
    public String currencyNamePlural() {
        return bridge.plural();
    }

    @Override
    public String currencyNameSingular() {
        return bridge.singular();
    }

    // --- Über OfflinePlayer (bevorzugt, per UUID) ---

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return bridge.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return bridge.balance(player.getUniqueId()).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return bridge.has(player.getUniqueId(), dec(amount));
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return response(bridge.withdraw(player.getUniqueId(), dec(amount)));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return response(bridge.deposit(player.getUniqueId(), dec(amount)));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return bridge.createAccount(player.getUniqueId(), player.getName());
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // --- Über Namen (alt) ---

    @Override
    public boolean hasAccount(String playerName) {
        UUID id = VaultBridge.uuidOf(playerName);
        return id != null && bridge.hasAccount(id);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(String playerName) {
        UUID id = VaultBridge.uuidOf(playerName);
        return id == null ? 0 : bridge.balance(id).doubleValue();
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(String playerName, double amount) {
        UUID id = VaultBridge.uuidOf(playerName);
        return id != null && bridge.has(id, dec(amount));
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return byName(playerName, amount, id -> bridge.withdraw(id, dec(amount)));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return byName(playerName, amount, id -> bridge.deposit(id, dec(amount)));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        UUID id = VaultBridge.uuidOf(playerName);
        return id != null && bridge.createAccount(id, playerName);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    // --- Banken: nicht unterstützt ---

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
