package de.nucleus.module.economy;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * VaultUnlocked-Schnittstelle ("modern", net.milkbowl.vault2). Nucleus hat eine Währung und keine
 * gemeinsamen Konten; Welt- und Währungsparameter werden deshalb ignoriert bzw. geprüft.
 * Der erste String-Parameter vieler Methoden ist der Name des aufrufenden Plugins.
 */
// Einige Pflichtmethoden (getBalance, createAccount ...) sind in VaultUnlocked als veraltet markiert,
// aber weiterhin abstrakt und werden von Plugins benutzt; sie müssen also umgesetzt werden.
@SuppressWarnings("deprecation")
final class ModernVaultEconomy implements Economy {

    private final VaultBridge bridge;

    ModernVaultEconomy(VaultBridge bridge) {
        this.bridge = bridge;
    }

    private static EconomyResponse response(VaultBridge.Result r) {
        return new EconomyResponse(r.amount(), r.balance(),
                r.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, r.error());
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
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return bridge.decimals();
    }

    @Override
    public String format(BigDecimal amount) {
        return bridge.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return bridge.format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return bridge.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return bridge.format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return bridge.isOurCurrency(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return bridge.singular();
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return bridge.plural();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return bridge.singular();
    }

    @Override
    public Collection<String> currencies() {
        return List.of(bridge.singular());
    }

    // --- Konten ---

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return bridge.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        return bridge.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return bridge.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return bridge.createAccount(accountID, name);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return bridge.names();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        return Optional.ofNullable(bridge.names().get(accountID));
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return bridge.hasAccount(accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return bridge.hasAccount(accountID);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return bridge.rename(accountID, name);
    }

    @Override
    public boolean renameAccount(String plugin, UUID accountID, String name) {
        return bridge.rename(accountID, name);
    }

    @Override
    public boolean deleteAccount(String plugin, UUID accountID) {
        return false; // Konten löschen nur über Nucleus selbst
    }

    @Override
    public boolean accountSupportsCurrency(String plugin, UUID accountID, String currency) {
        return bridge.isOurCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String plugin, UUID accountID, String currency, String world) {
        return bridge.isOurCurrency(currency);
    }

    // --- Guthaben ---

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return bridge.balance(accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return bridge.balance(accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return bridge.isOurCurrency(currency) ? bridge.balance(accountID) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return bridge.has(accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return bridge.has(accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return bridge.isOurCurrency(currency) && bridge.has(accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return response(bridge.withdraw(accountID, amount));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return response(bridge.withdraw(accountID, amount));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return bridge.isOurCurrency(currency) ? response(bridge.withdraw(accountID, amount)) : wrongCurrency(accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return response(bridge.deposit(accountID, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return response(bridge.deposit(accountID, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return bridge.isOurCurrency(currency) ? response(bridge.deposit(accountID, amount)) : wrongCurrency(accountID, amount);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
        return response(bridge.set(accountID, amount));
    }

    private EconomyResponse wrongCurrency(UUID accountID, BigDecimal amount) {
        return new EconomyResponse(amount, bridge.balance(accountID), EconomyResponse.ResponseType.FAILURE, "Unbekannte Währung");
    }

    // --- Gemeinsame Konten: nicht unterstützt ---

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        return false;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return accountID.equals(uuid);
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return accountID.equals(uuid);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
        return accountID.equals(uuid);
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) {
        return false;
    }
}
