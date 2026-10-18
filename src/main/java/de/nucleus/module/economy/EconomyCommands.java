package de.nucleus.module.economy;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.api.EconomyResult;
import de.nucleus.api.EconomyService.BalanceEntry;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.sub;
import static de.nucleus.command.CommandKit.word;

/** /balance, /pay, /paytoggle, /baltop, /eco. */
final class EconomyCommands {

    private final NucleusPlugin plugin;
    private final EconomyModule module;
    private final CommandKit kit;

    EconomyCommands(NucleusPlugin plugin, EconomyModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("balance", "nucleus.balance")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    requireLoaded();
                    plugin.messages().send(me, "economy.balance", "amount", fmt(module.ledger().balance(me.getUniqueId())));
                }))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.balance.others"))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> {
                            requireLoaded();
                            OfflinePlayer target = CommandKit.known(ctx, "player");
                            requireAccount(target.getUniqueId(), name(target, ctx));
                            plugin.messages().send(sender(ctx), "economy.balance-other", "player", name(target, ctx),
                                    "amount", fmt(module.ledger().balance(target.getUniqueId())));
                        }))),
                "Zeigt den Kontostand", "bal", "money");

        // Betrag als greedy, damit "12,50" (mit Komma) funktioniert
        CommandKit.register(r, kit.root("pay", "nucleus.pay")
                .executes(kit.usage("/pay <spieler> <betrag>"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/pay <spieler> <betrag>"))
                        .then(greedy("amount").executes(kit.run(this::pay)))),
                "Überweist Geld an einen Spieler");

        CommandKit.register(r, kit.root("paytoggle", "nucleus.paytoggle")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    requireLoaded();
                    boolean now = !module.ledger().acceptsPayments(me.getUniqueId());
                    module.ledger().acceptsPayments(me.getUniqueId(), now);
                    plugin.messages().send(me, now ? "economy.paytoggle-on" : "economy.paytoggle-off");
                })),
                "Schaltet Zahlungen an dich an oder aus");

        CommandKit.register(r, kit.root("baltop", "nucleus.baltop")
                .executes(kit.run(ctx -> baltop(ctx, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(kit.run(ctx -> baltop(ctx, IntegerArgumentType.getInteger(ctx, "page"))))),
                "Zeigt die reichsten Spieler", "balancetop", "moneytop");

        CommandKit.register(r, kit.root("eco", "nucleus.eco")
                .executes(kit.usage("/eco <give|take|set|reset> <spieler> [betrag]"))
                .then(adminAction("give"))
                .then(adminAction("take"))
                .then(adminAction("set"))
                .then(sub("reset", null).then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> admin(ctx, "reset", module.settings().startBalance()))))),
                "Verwaltet Kontostände", "economy");
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminAction(String action) {
        return sub(action, null)
                .executes(kit.usage("/eco " + action + " <spieler> <betrag>"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/eco " + action + " <spieler> <betrag>"))
                        .then(greedy("amount").executes(kit.run(ctx -> admin(ctx, action, amount(ctx))))));
    }

    private void pay(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        requireLoaded();
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String targetName = name(target, ctx);
        long amount = amount(ctx);
        EconomyModule.Settings s = module.settings();
        if (target.getUniqueId().equals(me.getUniqueId())) {
            throw new CommandFail("economy.pay-self");
        }
        if (amount < s.payMin()) {
            throw new CommandFail("economy.pay-min", "amount", fmt(s.payMin()));
        }
        if (s.payMax() > 0 && amount > s.payMax()) {
            throw new CommandFail("economy.pay-max", "amount", fmt(s.payMax()));
        }
        requireAccount(target.getUniqueId(), targetName);
        if (!module.ledger().acceptsPayments(target.getUniqueId())) {
            throw new CommandFail("economy.pay-disabled", "player", targetName);
        }
        EconomyResult result = module.ledger().transfer(me.getUniqueId(), target.getUniqueId(), amount);
        switch (result.status()) {
            case SUCCESS -> {
                plugin.messages().send(me, "economy.paid", "player", targetName, "amount", fmt(amount),
                        "balance", fmt(result.balance()));
                Player online = target.getPlayer();
                if (online != null) {
                    plugin.messages().send(online, "economy.received", "player", me.getName(), "amount", fmt(amount));
                }
            }
            case INSUFFICIENT_FUNDS -> throw new CommandFail("economy.insufficient", "balance", fmt(result.balance()));
            case LIMIT_EXCEEDED -> throw new CommandFail("economy.target-limit", "player", targetName);
            default -> throw new CommandFail("economy.invalid-amount");
        }
    }

    private void baltop(CommandContext<CommandSourceStack> ctx, int page) {
        requireLoaded();
        CommandSender sender = sender(ctx);
        List<BalanceEntry> all = module.service().sortedCached();
        int size = module.settings().pageSize();
        int pages = Math.max(1, (all.size() + size - 1) / size);
        if (page > pages) {
            throw new CommandFail("economy.baltop-no-page", "pages", pages);
        }
        plugin.messages().send(sender, "economy.baltop-header", "page", page, "pages", pages,
                "total", fmt(module.ledger().total()));
        int from = (page - 1) * size;
        for (int i = from; i < Math.min(all.size(), from + size); i++) {
            BalanceEntry e = all.get(i);
            plugin.messages().send(sender, "economy.baltop-entry", "rank", i + 1, "player", e.name(), "amount", fmt(e.balance()));
        }
    }

    /** /eco give|take|set|reset: auch von der Konsole, für Offline-Spieler, wird protokolliert. */
    private void admin(CommandContext<CommandSourceStack> ctx, String action, long amount) {
        requireLoaded();
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = name(target, ctx);
        UUID id = target.getUniqueId();
        // Bekannte Spieler ohne Konto (z. B. vor der Economy gespielt) bekommen eins
        module.service().ensureAccount(id, name);
        EconomyResult result = switch (action) {
            case "give" -> module.ledger().deposit(id, amount);
            case "take" -> module.ledger().withdraw(id, amount);
            default -> module.ledger().set(id, amount);
        };
        switch (result.status()) {
            case SUCCESS -> {
                module.service().invalidateTop();
                plugin.getLogger().info("[Eco] " + sender.getName() + ": " + action + " " + name + " "
                        + fmt(amount) + " → Kontostand " + fmt(result.balance()));
                plugin.messages().send(sender, "economy.admin-" + action, "player", name, "amount", fmt(amount),
                        "balance", fmt(result.balance()));
            }
            case INSUFFICIENT_FUNDS -> throw new CommandFail("economy.admin-insufficient", "player", name,
                    "balance", fmt(result.balance()));
            case LIMIT_EXCEEDED -> throw new CommandFail("economy.target-limit", "player", name);
            default -> throw new CommandFail("economy.invalid-amount");
        }
    }

    private long amount(CommandContext<CommandSourceStack> ctx) {
        String raw = arg(ctx, "amount");
        return module.service().parse(raw)
                .orElseThrow(() -> new CommandFail("economy.invalid-amount-detail", "input", raw,
                        "decimals", module.decimals()));
    }

    private void requireLoaded() {
        if (!module.loaded()) {
            throw new CommandFail("economy.loading");
        }
    }

    private void requireAccount(UUID id, String name) {
        if (!module.ledger().hasAccount(id)) {
            throw new CommandFail("economy.no-account", "player", name);
        }
    }

    private String fmt(long amount) {
        return module.service().format(amount);
    }

    private static String name(OfflinePlayer p, CommandContext<CommandSourceStack> ctx) {
        return p.getName() != null ? p.getName() : arg(ctx, "player");
    }
}
