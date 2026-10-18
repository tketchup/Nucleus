package de.nucleus.module.economy;

import de.nucleus.NucleusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * Meldet Nucleus bei VaultUnlocked als Economy an (moderne und klassische Schnittstelle).
 * Nur diese Klasse und die beiden Adapter kennen Vault-Klassen; sie werden erst geladen, wenn
 * VaultUnlocked tatsächlich installiert ist. Ohne VaultUnlocked läuft Nucleus ganz normal.
 */
// Die klassische Vault-Schnittstelle ist in VaultUnlocked als veraltet markiert, wird aber von vielen
// älteren Plugins genutzt; deshalb wird sie bewusst zusätzlich angemeldet.
@SuppressWarnings("deprecation")
final class VaultHook {

    private final ModernVaultEconomy modern;
    private final LegacyVaultEconomy legacy;

    private VaultHook(VaultBridge bridge) {
        this.modern = new ModernVaultEconomy(bridge);
        this.legacy = new LegacyVaultEconomy(bridge);
    }

    /** @return der Hook, oder null wenn VaultUnlocked fehlt */
    static VaultHook registerIfPresent(NucleusPlugin plugin, EconomyModule module) {
        // Das Plugin heißt auch in der VaultUnlocked-Variante "Vault"
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }
        VaultHook hook = new VaultHook(new VaultBridge(module));
        ServicesManager sm = Bukkit.getServicesManager();
        sm.register(net.milkbowl.vault2.economy.Economy.class, hook.modern, plugin, ServicePriority.Normal);
        sm.register(net.milkbowl.vault.economy.Economy.class, hook.legacy, plugin, ServicePriority.Normal);
        plugin.getLogger().info("Bei VaultUnlocked als Economy angemeldet (modern und klassisch).");
        return hook;
    }

    void unregister() {
        ServicesManager sm = Bukkit.getServicesManager();
        sm.unregister(net.milkbowl.vault2.economy.Economy.class, modern);
        sm.unregister(net.milkbowl.vault.economy.Economy.class, legacy);
    }
}
