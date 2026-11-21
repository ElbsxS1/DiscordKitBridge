package br.asthen.kitbridge.commands;

import br.asthen.kitbridge.DiscordKitBridge;
import br.asthen.kitbridge.bukkit.KitSerializer;
import br.asthen.kitbridge.bukkit.Msg;
import br.asthen.kitbridge.bukkit.PreviewHolder;
import br.asthen.kitbridge.core.PlayerRef;
import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager.KitException;
import br.asthen.kitbridge.security.Validators;
import br.asthen.kitbridge.util.Prices;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** /dk create | delete | list | info | preview | toggle | claim | reload */
public final class KitCommand implements TabExecutor {

    private static final long CONFIRM_MS = 30_000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> SUBS = List.of("create", "delete", "list", "info", "preview", "give", "starter", "price", "enable", "disable", "toggle", "claim", "pending", "reload", "help");

    /** Permission required by each subcommand; used by help and tab completion so players only see what they can run. */
    private static final Map<String, String> PERMS = Map.ofEntries(
            Map.entry("create", "discordkitbridge.create"),
            Map.entry("delete", "discordkitbridge.delete"),
            Map.entry("list", "discordkitbridge.list"),
            Map.entry("info", "discordkitbridge.list"),
            Map.entry("preview", "discordkitbridge.preview"),
            Map.entry("give", "discordkitbridge.give"),
            Map.entry("starter", "discordkitbridge.give"),
            Map.entry("price", "discordkitbridge.price"),
            Map.entry("enable", "discordkitbridge.toggle"),
            Map.entry("disable", "discordkitbridge.toggle"),
            Map.entry("toggle", "discordkitbridge.toggle"),
            Map.entry("claim", "discordkitbridge.claim"),
            Map.entry("reload", "discordkitbridge.reload"),
            Map.entry("pending", "discordkitbridge.pending"));

    private record Confirmation(String kit, long expires) {
    }

    private final DiscordKitBridge plugin;
    private final Map<String, Confirmation> confirmations = new HashMap<>();

    public KitCommand(DiscordKitBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(s, a);
            case "delete" -> delete(s, a);
            case "list" -> list(s);
            case "info" -> info(s, a);
            case "preview" -> preview(s, a);
            case "give" -> give(s, a);
            case "starter" -> starter(s, a);
            case "price" -> price(s, a);
            case "enable" -> setEnabled(s, a, true);
            case "disable" -> setEnabled(s, a, false);
            case "toggle" -> toggle(s, a);
            case "claim" -> claim(s);
            case "pending" -> pending(s, a);
            case "reload" -> reload(s);
            default -> help(s);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void create(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.create")) return;
        if (!(s instanceof Player p)) {
            Msg.error(s, "Este comando só pode ser usado dentro do jogo.");
            return;
        }
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk create <nome>  (segurando uma Shulker Box)");
            return;
        }
        String name = a[1];
        if (!Validators.isValidKitName(name)) {
            Msg.error(s, "Nome inválido. Use de 1 a 32 caracteres: letras, números, _ e -.");
            return;
        }
        try {
            KitData data = KitSerializer.fromShulker(p.getInventory().getItemInMainHand(), name, p.getName());
            plugin.kits().create(data);
            plugin.logger().log("ADMIN", "action", "create", "by", p.getName(), "kit", name, "items", data.itemCount());
            Msg.ok(s, "Kit " + name + " cadastrado com sucesso!");
        } catch (KitException e) {
            Msg.error(s, e.getMessage());
        } catch (IOException e) {
            Msg.error(s, "Não foi possível salvar o kit. Veja o console.");
            plugin.getLogger().severe("Falha ao salvar o kit " + name + ": " + e.getMessage());
        }
    }

    private void delete(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.delete")) return;
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk delete <nome>");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        String name = kit.get().name();
        String who = s.getName();
        Confirmation c = confirmations.get(who);
        boolean confirming = a.length >= 3 && a[2].equalsIgnoreCase("confirm")
                && c != null && c.kit().equalsIgnoreCase(name) && c.expires() > System.currentTimeMillis();
        if (!confirming) {
            confirmations.put(who, new Confirmation(name, System.currentTimeMillis() + CONFIRM_MS));
            Msg.info(s, "Isto apagará o kit " + name + " (" + kit.get().itemCount() + " itens) para sempre. "
                    + "Para confirmar em 30 segundos, use: /dk delete " + name + " confirm");
            return;
        }
        confirmations.remove(who);
        try {
            plugin.kits().delete(name);
            plugin.logger().log("ADMIN", "action", "delete", "by", who, "kit", name);
            Msg.ok(s, "Kit " + name + " removido.");
        } catch (IOException e) {
            Msg.error(s, "Não foi possível remover o kit. Veja o console.");
            plugin.getLogger().severe("Falha ao remover o kit " + name + ": " + e.getMessage());
        }
    }

    private void list(CommandSender s) {
        if (!allowed(s, "discordkitbridge.list")) return;
        List<KitData> kits = plugin.kits().list();
        if (kits.isEmpty()) {
            Msg.info(s, "Nenhum kit cadastrado. Segure uma Shulker Box e use /dk create <nome>.");
            return;
        }
        Msg.ok(s, kits.size() + " kit(s) cadastrado(s):");
        for (KitData k : kits) {
            Msg.plain(s, " - " + k.name() + " (" + k.itemCount() + " itens) "
                    + (k.hasPrice() ? Prices.format(k.priceCents()) : "sem preço") + (k.enabled() ? "" : " [desativado]")
                    + (br.asthen.kitbridge.core.StarterKitPolicy.isStarter(k.name(), plugin.starterSettings().name())
                    ? " [Kit Inicial: automático, sem Shulker Box, invisível ao bot]" : ""));
        }
    }

    private void info(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.list")) return;
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk info <nome>");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        KitData k = kit.get();
        Msg.ok(s, "Kit " + k.name());
        Msg.plain(s, " Itens: " + k.itemCount());
        Msg.plain(s, " Cadastrado em: " + DATE.format(Instant.ofEpochMilli(k.createdAt())) + " por " + k.createdBy());
        Msg.plain(s, " Preço: " + (k.hasPrice() ? Prices.format(k.priceCents()) : "não definido (fora da loja)"));
        Msg.plain(s, " Status: " + (k.enabled() ? "ativo" : "desativado"));
    }

    private void preview(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.preview")) return;
        if (!(s instanceof Player p)) {
            Msg.error(s, "Este comando só pode ser usado dentro do jogo.");
            return;
        }
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk preview <nome>");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        List<ItemStack> items;
        try {
            items = KitSerializer.toItems(kit.get());
        } catch (KitException e) {
            Msg.error(s, "Este kit está corrompido: " + e.getMessage());
            return;
        }
        PreviewHolder holder = new PreviewHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Kit: " + kit.get().name()));
        holder.setInventory(inv);
        List<KitData.Slot> slots = kit.get().slots();
        for (int i = 0; i < slots.size() && i < items.size(); i++) {
            int slot = slots.get(i).slot();
            if (slot >= 0 && slot < 27) inv.setItem(slot, items.get(i));
        }
        p.openInventory(inv);
    }

    private void give(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.give")) return;
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk give <nome> [jogador]");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        Player target;
        if (a.length >= 3) {
            target = Bukkit.getPlayerExact(a[2]);
            if (target == null) {
                Msg.error(s, "Jogador não está online: " + a[2]);
                return;
            }
        } else if (s instanceof Player self) {
            target = self;
        } else {
            Msg.info(s, "No console informe o jogador: /dk give <nome> <jogador>");
            return;
        }
        PlayerRef ref = new PlayerRef(target.getUniqueId(), target.getName());
        switch (plugin.service().giveDirect(ref, kit.get(), s.getName())) {
            case DELIVERED -> Msg.ok(s, "Kit " + kit.get().name() + " entregue a " + target.getName() + ".");
            case PENDING -> Msg.info(s, "O inventário de " + target.getName() + " está cheio: o kit ficou pendente (/dk claim).");
            case FULL -> Msg.error(s, "O inventário de " + target.getName() + " está cheio. Nada foi entregue.");
            case CORRUPT -> Msg.error(s, "Este kit está corrompido e não pôde ser entregue. Veja o console.");
            case OFFLINE -> Msg.error(s, "Jogador não está online: " + target.getName());
            case ERROR -> Msg.error(s, "Não foi possível guardar o kit pendente. Veja o console.");
        }
    }

    /** /dk starter [jogador]: entrega o Kit Inicial na hora (teste), sem Shulker Box e sem passar pelo bot. */
    private void starter(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.give")) return;
        Player target;
        if (a.length >= 2) {
            target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                Msg.error(s, "Jogador não está online: " + a[1]);
                return;
            }
        } else if (s instanceof Player self) {
            target = self;
        } else {
            Msg.info(s, "No console informe o jogador: /dk starter <jogador>");
            return;
        }
        String name = plugin.starterSettings().name();
        switch (plugin.starterKit().give(target, "command", false)) {
            case DELIVERED -> Msg.ok(s, "Kit Inicial entregue a " + target.getName() + " (sem Shulker Box).");
            case NO_KIT -> Msg.error(s, "O kit " + name + " não existe. Segure uma Shulker Box e use /dk create " + name);
            case CORRUPT -> Msg.error(s, "O kit " + name + " está corrompido e não pôde ser entregue. Veja o console.");
            case DISABLED -> Msg.error(s, "O Kit Inicial está desativado (starter-kit.enabled: false).");
            case COOLDOWN, ALREADY_RECEIVED -> Msg.info(s, "Aguarde um instante antes de pedir de novo.");
        }
    }

    private void price(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.price")) return;
        if (a.length < 3) {
            Msg.info(s, "Uso: /dk price <nome> <valor>   (ex.: /dk price Kit_Dima_Evo 29,90)  ou  /dk price <nome> remover");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        String name = kit.get().name();
        long cents;
        if (a[2].equalsIgnoreCase("remover") || a[2].equalsIgnoreCase("remove") || a[2].equalsIgnoreCase("none")) {
            cents = KitData.NO_PRICE;
        } else {
            var parsed = Prices.parse(a[2]);
            if (parsed.isEmpty()) {
                Msg.error(s, "Preço inválido. Use um valor maior que zero, como 30 ou 29,90 (no máximo 999999,99).");
                return;
            }
            cents = parsed.getAsLong();
        }
        try {
            plugin.kits().setPrice(name, cents);
            plugin.logger().log("ADMIN", "action", "price", "by", s.getName(), "kit", name,
                    "price", cents > 0 ? Prices.format(cents) : "none");
            if (cents > 0) Msg.ok(s, "Preço do kit " + name + ": " + Prices.format(cents) + ".");
            else Msg.ok(s, "Kit " + name + " sem preço: não será vendido na loja.");
        } catch (IOException e) {
            Msg.error(s, "Não foi possível salvar. Veja o console.");
            plugin.getLogger().severe("Falha ao salvar o preço do kit: " + e.getMessage());
        }
    }

    private void setEnabled(CommandSender s, String[] a, boolean enable) {
        if (!allowed(s, "discordkitbridge.toggle")) return;
        String cmd = enable ? "enable" : "disable";
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk " + cmd + " <nome>");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        String name = kit.get().name();
        if (kit.get().enabled() == enable) {
            Msg.info(s, "O kit " + name + " já está " + (enable ? "ativo." : "desativado."));
            return;
        }
        try {
            plugin.kits().setEnabled(name, enable);
            plugin.logger().log("ADMIN", "action", cmd, "by", s.getName(), "kit", name);
            Msg.ok(s, "Kit " + name + (enable ? " ativado." : " desativado (aparece como indisponível na loja)."));
        } catch (IOException e) {
            Msg.error(s, "Não foi possível salvar. Veja o console.");
            plugin.getLogger().severe("Falha ao atualizar o kit: " + e.getMessage());
        }
    }

    private void toggle(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.toggle")) return;
        if (a.length < 2) {
            Msg.info(s, "Uso: /dk toggle <nome>");
            return;
        }
        Optional<KitData> kit = plugin.kits().get(a[1]);
        if (kit.isEmpty()) {
            Msg.error(s, "Kit não encontrado: " + a[1]);
            return;
        }
        try {
            boolean enable = !kit.get().enabled();
            plugin.kits().setEnabled(kit.get().name(), enable);
            plugin.logger().log("ADMIN", "action", enable ? "enable" : "disable", "by", s.getName(), "kit", kit.get().name());
            Msg.ok(s, "Kit " + kit.get().name() + (enable ? " ativado." : " desativado."));
        } catch (IOException e) {
            Msg.error(s, "Não foi possível salvar. Veja o console.");
            plugin.getLogger().severe("Falha ao atualizar o kit: " + e.getMessage());
        }
    }

    private void claim(CommandSender s) {
        if (!allowed(s, "discordkitbridge.claim")) return;
        if (!(s instanceof Player p)) {
            Msg.error(s, "Este comando só pode ser usado dentro do jogo.");
            return;
        }
        PlayerRef ref = new PlayerRef(p.getUniqueId(), p.getName());
        var platform = plugin.game().platformOf(p);
        if (plugin.service().pendingCount(ref, platform) == 0) {
            Msg.info(s, "Você não tem kits pendentes.");
            return;
        }
        int n = plugin.service().claim(ref, platform);
        if (n == 0) return; // the service already told the player why (inventory still full)
        plugin.logger().log("CLAIM", "player", p.getName(), "kits", n);
    }

    /** /dk pending [resolve <request_id> <delivered|retry>] */
    private void pending(CommandSender s, String[] a) {
        if (!allowed(s, "discordkitbridge.pending")) return;
        if (a.length >= 2 && a[1].equalsIgnoreCase("resolve")) {
            if (a.length < 4 || !(a[3].equalsIgnoreCase("delivered") || a[3].equalsIgnoreCase("retry"))) {
                Msg.info(s, "Uso: /dk pending resolve <request_id> <delivered|retry>");
                return;
            }
            try {
                boolean delivered = a[3].equalsIgnoreCase("delivered");
                if (plugin.service().resolve(a[2], delivered)) {
                    plugin.logger().log("ADMIN", "action", "pending-resolve", "by", s.getName(), "request_id", a[2], "result", a[3].toLowerCase(Locale.ROOT));
                    Msg.ok(s, delivered ? "Marcada como entregue." : "Será tentada de novo na próxima entrega (/dk claim do jogador).");
                } else {
                    Msg.error(s, "Nenhuma pendência com esse request_id.");
                }
            } catch (java.io.IOException e) {
                Msg.error(s, "Não foi possível gravar a decisão: " + e.getMessage());
            }
            return;
        }
        var list = plugin.service().needsAttention();
        var issues = plugin.pendingStore().issues();
        var orphans = plugin.service().orphanedPending();
        if (list.isEmpty() && issues.isEmpty() && orphans.isEmpty()) {
            Msg.ok(s, "Nenhuma pendência precisa de atenção.");
        }
        for (var e : list) {
            Msg.plain(s, " " + e.requestId() + "  " + e.kitName() + "  [" + e.state() + "]" + (e.note() != null ? "  " + e.note() : ""));
        }
        for (String i : issues) Msg.plain(s, " ! " + i);
        for (var r : orphans) {
            Msg.plain(s, " ? " + r.requestId() + "  " + r.kit() + " para " + r.player()
                    + ": o bot foi avisado de KIT_PENDING mas não há pendência guardada. Confira e use /dk give se preciso.");
        }
        if (!list.isEmpty()) Msg.info(s, "Confira o inventário do jogador e use /dk pending resolve <request_id> delivered|retry.");
        Msg.plain(s, " Outbox: " + plugin.outbox().size() + " item(ns) aguardando o bot, " + plugin.outbox().deadCount() + " recusado(s).");
    }

    private void reload(CommandSender s) {
        if (!allowed(s, "discordkitbridge.reload")) return;
        try {
            int n = plugin.reloadAll();
            plugin.logger().log("ADMIN", "action", "reload", "by", s.getName());
            Msg.ok(s, "Configuração recarregada. " + n + " kit(s) carregado(s).");
        } catch (Exception e) {
            Msg.error(s, "Falha ao recarregar: " + e.getMessage());
        }
    }

    private void help(CommandSender s) {
        String[][] lines = {
                {"create", " /dk create <nome>  - salva a Shulker Box da mão como kit"},
                {"delete", " /dk delete <nome>  - remove um kit (pede confirmação)"},
                {"list", " /dk list           - lista os kits"},
                {"info", " /dk info <nome>    - detalhes de um kit"},
                {"preview", " /dk preview <nome> - visualiza os itens"},
                {"give", " /dk give <nome> [jogador] - entrega o kit (a você, se omitir o jogador)"},
                {"starter", " /dk starter [jogador] - entrega o Kit Inicial (sem Shulker Box)"},
                {"price", " /dk price <nome> <valor> - define o preço (ex.: 29,90)"},
                {"enable", " /dk enable <nome>  - ativa um kit"},
                {"disable", " /dk disable <nome> - desativa um kit (fica indisponível na loja)"},
                {"toggle", " /dk toggle <nome>  - alterna ativo/desativado"},
                {"claim", " /dk claim          - recebe kits pendentes"},
                {"pending", " /dk pending       - entregas que precisam de atenção (resolve <id> delivered|retry)"},
                {"reload", " /dk reload         - recarrega a configuração"},
        };
        Msg.ok(s, "Comandos:");
        for (String[] l : lines) {
            if (s.hasPermission(PERMS.get(l[0]))) Msg.plain(s, l[1]);
        }
    }

    private boolean allowed(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        Msg.error(s, "Você não tem permissão para isso.");
        return false;
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String sub : SUBS) {
                String perm = PERMS.get(sub);
                if (sub.startsWith(a[0].toLowerCase(Locale.ROOT)) && (perm == null || s.hasPermission(perm))) out.add(sub);
            }
        } else if (a.length == 2 && List.of("delete", "info", "preview", "give", "price", "enable", "disable", "toggle").contains(a[0].toLowerCase(Locale.ROOT))
                && s.hasPermission(PERMS.get(a[0].toLowerCase(Locale.ROOT)))) {
            for (KitData k : plugin.kits().list()) if (k.name().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(k.name());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("pending") && s.hasPermission("discordkitbridge.pending")) {
            if ("resolve".startsWith(a[1].toLowerCase(Locale.ROOT))) out.add("resolve");
        } else if (a.length == 4 && a[0].equalsIgnoreCase("pending") && a[1].equalsIgnoreCase("resolve")
                && s.hasPermission("discordkitbridge.pending")) {
            for (String o : List.of("delivered", "retry")) if (o.startsWith(a[3].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("delete") && s.hasPermission("discordkitbridge.delete")) {
            out.add("confirm");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("starter") && s.hasPermission("discordkitbridge.give")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give") && s.hasPermission("discordkitbridge.give")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
        }
        return out;
    }
}
