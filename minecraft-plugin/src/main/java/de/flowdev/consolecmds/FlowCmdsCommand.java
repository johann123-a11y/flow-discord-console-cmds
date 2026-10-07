package de.flowdev.consolecmds;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class FlowCmdsCommand implements CommandExecutor {

    private final FlowDiscordConsoleCmds plugin;

    public FlowCmdsCommand(FlowDiscordConsoleCmds plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "status": {
                BotWebSocketClient ws = plugin.getWsClient();
                boolean connected = ws != null && ws.isOpen();
                sender.sendMessage(ChatColor.GOLD + "[FlowConsoleCmds] Status: "
                        + (connected ? ChatColor.GREEN + "Connected" : ChatColor.RED + "Disconnected"));
                sender.sendMessage(ChatColor.GOLD + "  Link code: " + ChatColor.WHITE + plugin.getLinkCode());
                sender.sendMessage(ChatColor.GOLD + "  Bot URL:   " + ChatColor.WHITE
                        + plugin.getConfig().getString("connection.bot-url", "?"));
                break;
            }

            case "code":
                sender.sendMessage(ChatColor.GOLD + "[FlowConsoleCmds] Link code: "
                        + ChatColor.WHITE + plugin.getLinkCode());
                sender.sendMessage(ChatColor.GRAY + "Run /link " + plugin.getLinkCode() + " in Discord");
                break;

            case "reconnect":
                sender.sendMessage(ChatColor.GOLD + "[FlowConsoleCmds] Reconnecting...");
                plugin.reconnect();
                break;

            case "reload":
                plugin.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "[FlowConsoleCmds] Config reloaded.");
                break;

            case "reset":
                if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                    plugin.resetCode();
                    sender.sendMessage(ChatColor.GREEN + "[FlowConsoleCmds] New link code: "
                            + ChatColor.WHITE + plugin.getLinkCode());
                    sender.sendMessage(ChatColor.GRAY + "The old code no longer works.");
                } else {
                    sender.sendMessage(ChatColor.RED + "This will invalidate your current link code.");
                    sender.sendMessage(ChatColor.RED + "Type " + ChatColor.WHITE
                            + "/flowcmds reset confirm" + ChatColor.RED + " to proceed.");
                }
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- FlowDiscordConsoleCmds ---");
        sender.sendMessage(ChatColor.YELLOW + "/flowcmds status"         + ChatColor.GRAY + " – connection status");
        sender.sendMessage(ChatColor.YELLOW + "/flowcmds code"           + ChatColor.GRAY + " – show link code");
        sender.sendMessage(ChatColor.YELLOW + "/flowcmds reconnect"      + ChatColor.GRAY + " – reconnect to bot");
        sender.sendMessage(ChatColor.YELLOW + "/flowcmds reload"         + ChatColor.GRAY + " – reload config.yml");
        sender.sendMessage(ChatColor.YELLOW + "/flowcmds reset confirm"  + ChatColor.GRAY + " – generate new link code");
    }
}
