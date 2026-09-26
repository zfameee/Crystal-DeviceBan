package ru.crystaldeviceban;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class Crystal-DeviceBanPlugin extends JavaPlugin implements Listener {
    private final Map<String, BanRecord> bans = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadBans();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("crystaldeviceban")).setExecutor(new BanCommand());
        Objects.requireNonNull(getCommand("deviceunban")).setExecutor(new UnbanCommand());
        Objects.requireNonNull(getCommand("deviceinfo")).setExecutor(new InfoCommand());
    }

    @Override public void onDisable() { saveBans(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String fingerprint = fingerprint(e.getRawAddress(), e.getPlayerProfile().getName(), e.getPlayerProfile().getUniqueId());
        BanRecord ban = bans.get(fingerprint);
        if (ban != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text(color(getConfig().getString("messages.banned", "&cДоступ запрещён.")) + "\nПричина: " + ban.reason));
        }
    }

    private String fingerprint(InetAddress address, String name, UUID uuid) {
        List<String> parts = new ArrayList<>();
        if (getConfig().getBoolean("fingerprint.include-ip", true)) parts.add(address == null ? "unknown" : address.getHostAddress());
        // Client brand/locale are not available reliably at pre-login on vanilla clients.
        // Name/UUID are intentionally NOT used by default because that would make this an account ban.
        String raw = String.join("|", parts);
        return sha256(raw);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : d) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void loadBans() {
        bans.clear();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        try { c.load(new java.io.File(getDataFolder(), "bans.yml")); } catch (Exception ex) { getLogger().warning("Cannot load bans.yml: " + ex.getMessage()); return; }
        var section = c.getConfigurationSection("bans");
        if (section == null) return;
        for (String id : section.getKeys(false)) bans.put(id, new BanRecord(section.getString(id + ".reason", "Без причины"), section.getString(id + ".player", "unknown"), section.getLong(id + ".created", 0)));
    }

    private synchronized void saveBans() {
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e : bans.entrySet()) { c.set("bans." + e.getKey() + ".reason", e.getValue().reason); c.set("bans." + e.getKey() + ".player", e.getValue().player); c.set("bans." + e.getKey() + ".created", e.getValue().created); }
        try { c.save(new java.io.File(getDataFolder(), "bans.yml")); } catch (Exception ex) { getLogger().warning("Cannot save bans.yml: " + ex.getMessage()); }
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private record BanRecord(String reason, String player, long created) {}

    private final class BanCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length < 1) { sender.sendMessage("§c/crystaldeviceban <player> [reason]"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { sender.sendMessage("§cИгрок должен быть онлайн."); return true; }
            if (target.getAddress() == null) { sender.sendMessage("§cНе удалось получить адрес подключения."); return true; }
            String id = fingerprint(target.getAddress().getAddress(), target.getName(), target.getUniqueId());
            String reason = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Без причины";
            bans.put(id, new BanRecord(reason, target.getName(), System.currentTimeMillis()));
            saveBans();
            target.kick(Component.text(color(getConfig().getString("messages.banned", "&cДоступ запрещён.")) + "\nПричина: " + reason));
            sender.sendMessage(color(getConfig().getString("messages.ban-created", "&aОтпечаток заблокирован: &f{id}")).replace("{id}", id));
            return true;
        }
    }

    private final class UnbanCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length != 1) { sender.sendMessage("§c/deviceunban <fingerprint>"); return true; }
            if (bans.remove(args[0]) == null) { sender.sendMessage("§cТакого отпечатка нет."); return true; }
            saveBans();
            sender.sendMessage(color(getConfig().getString("messages.unbanned", "&aОтпечаток разблокирован: &f{id}")).replace("{id}", args[0]));
            return true;
        }
    }

    private final class InfoCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            Player p = args.length == 0 && sender instanceof Player ? (Player)sender : Bukkit.getPlayerExact(args[0]);
            if (p == null || p.getAddress() == null) { sender.sendMessage("§cИгрок не найден/не подключён."); return true; }
            String id = fingerprint(p.getAddress().getAddress(), p.getName(), p.getUniqueId());
            sender.sendMessage("§7Fingerprint: §f" + id);
            sender.sendMessage("§7Status: " + (bans.containsKey(id) ? "§cBANNED" : "§aOK"));
            return true;
        }
    }
}
