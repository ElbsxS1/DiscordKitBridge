package br.asthen.kitbridge.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Chat messages with a common prefix and colour. */
public final class Msg {
    public static final String PREFIX = "[DiscordKitBridge] ";

    private Msg() {
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.GREEN));
    }

    public static void error(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.RED));
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(Component.text(PREFIX + text, NamedTextColor.YELLOW));
    }

    public static void plain(CommandSender to, String text) {
        to.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }
}
