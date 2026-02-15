package me.wooxel.punishPlugin;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class PunishPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private Map<String, Long> punishDurations = new HashMap<>();
    private final Set<String> permanentPunishments = new HashSet<>();
    private File configFile;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        // Ordner und Config erstellen
        File folder = new File(getDataFolder(), "PunishSystem");
        if (!folder.exists()) folder.mkdirs();

        configFile = new File(folder, "config.yml");
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                config = YamlConfiguration.loadConfiguration(configFile);
                // Standardgründe einfügen (jetzt in Sekunden)
                config.set("AUSZEIT", 60);          // 60 Sekunden = 1 Minute
                config.set("BELEIDIGUNG", 21600);   // 21600 Sekunden = 6 Stunden
                config.set("HACKING", -1);          // permanent
                saveConfigFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        loadPunishDurations();

        getCommand("punish").setExecutor(this);
        getCommand("punish").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("PunishSystem aktiviert");
    }

    private void saveConfigFile() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPunishDurations() {
        punishDurations.clear();
        permanentPunishments.clear();
        for (String key : config.getKeys(false)) {
            long value = config.getLong(key);
            if (value <= 0) {
                permanentPunishments.add(key.toUpperCase());
            } else {
                punishDurations.put(key.toUpperCase(), value * 1000L); // Sekunden → Millisekunden
            }
        }
    }

    // -------- BAN SCREEN BEIM REJOIN --------
    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        BanList banList = Bukkit.getBanList(BanList.Type.NAME);
        if (!banList.isBanned(e.getPlayer().getName())) return;

        BanEntry entry = banList.getBanEntry(e.getPlayer().getName());
        if (entry == null) return;

        String reason = entry.getReason();
        Date until = entry.getExpiration();

        String message = buildBanScreen(reason, until);
        e.disallow(PlayerLoginEvent.Result.KICK_BANNED, message);
    }

    // -------- COMMAND --------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        String targetName = args[0];
        String reasonKey = args[1].toUpperCase();

        long now = System.currentTimeMillis();
        Date until = null;
        boolean permanent = false;

        if (punishDurations.containsKey(reasonKey)) {
            until = new Date(now + punishDurations.get(reasonKey));
        } else if (permanentPunishments.contains(reasonKey)) {
            permanent = true;
        } else {
            sender.sendMessage("§cUnbekannter Punish-Grund!");
            sendHelp(sender);
            return true;
        }

        Bukkit.getBanList(BanList.Type.NAME)
                .addBan(targetName, reasonKey, until, sender.getName());

        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            target.kickPlayer(buildBanScreen(reasonKey, until));
        }

        sender.sendMessage("§aSpieler §e" + targetName + " §awurde gebannt: §6" + reasonKey);
        return true;
    }

    // -------- BAN SCREEN BUILDER --------
    private String buildBanScreen(String reason, Date until) {
        StringBuilder sb = new StringBuilder();
        sb.append("§cOPBANDE:\n\n");

        if (until == null) {
            sb.append("§fDu wurdest §cpermanent §fgebannt.\n\n");
        } else {
            long remaining = until.getTime() - System.currentTimeMillis();

            if (remaining <= 0) {
                return "§aDein Bann ist abgelaufen. Bitte reconnecte.";
            }

            sb.append("§fDu bist bis §c")
                    .append(formatDate(until))
                    .append(" §fgebannt.\n");

            sb.append("§fVerbleibend: §c")
                    .append(formatRemaining(remaining))
                    .append("\n\n");
        }

        sb.append("§fGrund:\n§7")
                .append(reason)
                .append("\n\n§bdiscord.gg");

        return sb.toString();
    }

    private String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        return sdf.format(date);
    }

    private String formatRemaining(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;

        return days + "T " + hours + "H " + minutes + "M";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§cBenutzung: /punish <Spieler> <Grund>");
        sender.sendMessage("§7Mögliche Gründe:");
        punishDurations.keySet().forEach(r -> sender.sendMessage(" §e" + r));
        permanentPunishments.forEach(r -> sender.sendMessage(" §c" + r + " §7(permanent)"));
    }

    // -------- TAB COMPLETER --------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) { // Spieler-Vorschläge
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 2) { // Grund-Vorschläge
            String partial = args[1].toUpperCase();
            for (String r : punishDurations.keySet()) {
                if (r.startsWith(partial)) completions.add(r);
            }
            for (String r : permanentPunishments) {
                if (r.startsWith(partial)) completions.add(r);
            }
        }

        return completions;
    }
}
