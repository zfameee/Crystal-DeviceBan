package ru.crystaldeviceban;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CrystalDeviceBanPlugin extends JavaPlugin implements Listener {

    private final Map<String, BanRecord> bans = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBans();

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("crystaldeviceban"))
                .setExecutor(new BanCommand());

        Objects.requireNonNull(getCommand("deviceunban"))
                .setExecutor(new UnbanCommand());

        Objects.requireNonNull(getCommand("deviceinfo"))
                .setExecutor(new InfoCommand());

        getLogger().info("Crystal-DeviceBan enabled!");
    }

    @Override
    public void onDisable() {
        saveBans();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String fingerprint = fingerprint(
                event.getRawAddress(),
                event.getName(),
                event.getUniqueId()
        );

        BanRecord ban = bans.get(fingerprint);

        if (ban != null) {
            String message = color(
                    getConfig().getString(
                            "messages.banned",
                            "&cДоступ запрещён."
                    )
            );

            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text(
                            message +
                            "\n&cПричина: &f" +
                            ban.reason()
                    )
            );
        }
    }

    private String fingerprint(
            InetAddress address,
            String name,
            UUID uuid
    ) {
        List<String> parts = new ArrayList<>();

        if (getConfig().getBoolean("fingerprint.include-ip", true)) {
            parts.add(
                    address == null
                            ? "unknown"
                            : address.getHostAddress()
            );
        }

        /*
         * Vanilla Minecraft не передаёт серверу настоящий HWID устройства.
         *
         * Поэтому этот fingerprint основан на IP.
         * Имя и UUID специально не используются.
         */

        String raw = String.join("|", parts);

        return sha256(raw);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder(64);

            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void loadBans() {
        bans.clear();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File file = new File(getDataFolder(), "bans.yml");

        org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();

        try {
            config.load(file);
        } catch (Exception exception) {
            getLogger().warning(
                    "Cannot load bans.yml: " +
                    exception.getMessage()
            );
            return;
        }

        var section = config.getConfigurationSection("bans");

        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            String reason = section.getString(
                    id + ".reason",
                    "Без причины"
            );

            String player = section.getString(
                    id + ".player",
                    "unknown"
            );

            long created = section.getLong(
                    id + ".created",
                    0
            );

            bans.put(
                    id,
                    new BanRecord(
                            reason,
                            player,
                            created
                    )
            );
        }
    }

    private synchronized void saveBans() {
        org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();

        for (var entry : bans.entrySet()) {
            String id = entry.getKey();
            BanRecord ban = entry.getValue();

            config.set(
                    "bans." + id + ".reason",
                    ban.reason()
            );

            config.set(
                    "bans." + id + ".player",
                    ban.player()
            );

            config.set(
                    "bans." + id + ".created",
                    ban.created()
            );
        }

        try {
            config.save(
                    new File(
                            getDataFolder(),
                            "bans.yml"
                    )
            );
        } catch (Exception exception) {
            getLogger().warning(
                    "Cannot save bans.yml: " +
                    exception.getMessage()
            );
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes(
                '&',
                text
        );
    }

    private record BanRecord(
            String reason,
            String player,
            long created
    ) {
    }

    private final class BanCommand implements CommandExecutor {

        @Override
        public boolean onCommand(
                CommandSender sender,
                Command command,
                String label,
                String[] args
        ) {

            if (args.length < 1) {
                sender.sendMessage(
                        "§c/crystaldeviceban <player> [reason]"
                );
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);

            if (target == null) {
                sender.sendMessage(
                        "§cИгрок должен быть онлайн."
                );
                return true;
            }

            if (target.getAddress() == null) {
                sender.sendMessage(
                        "§cНе удалось получить адрес подключения."
                );
                return true;
            }

            String id = fingerprint(
                    target.getAddress().getAddress(),
                    target.getName(),
                    target.getUniqueId()
            );

            String reason = args.length >= 2
                    ? String.join(
                            " ",
                            Arrays.copyOfRange(
                                    args,
                                    1,
                                    args.length
                            )
                    )
                    : "Без причины";

            bans.put(
                    id,
                    new BanRecord(
                            reason,
                            target.getName(),
                            System.currentTimeMillis()
                    )
            );

            saveBans();

            target.kick(
                    Component.text(
                            color(
                                    getConfig().getString(
                                            "messages.banned",
                                            "&cДоступ запрещён."
                                    )
                            )
                            + "\nПричина: "
                            + reason
                    )
            );

            sender.sendMessage(
                    color(
                            getConfig().getString(
                                    "messages.ban-created",
                                    "&aОтпечаток заблокирован: &f{id}"
                            )
                    ).replace("{id}", id)
            );

            return true;
        }
    }

    private final class UnbanCommand implements CommandExecutor {

        @Override
        public boolean onCommand(
                CommandSender sender,
                Command command,
                String label,
                String[] args
        ) {

            if (args.length != 1) {
                sender.sendMessage(
                        "§c/deviceunban <fingerprint>"
                );
                return true;
            }

            if (bans.remove(args[0]) == null) {
                sender.sendMessage(
                        "§cТакого отпечатка нет."
                );
                return true;
            }

            saveBans();

            sender.sendMessage(
                    color(
                            getConfig().getString(
                                    "messages.unbanned",
                                    "&aОтпечаток разблокирован: &f{id}"
                            )
                    ).replace("{id}", args[0])
            );

            return true;
        }
    }

    private final class InfoCommand implements CommandExecutor {

        @Override
        public boolean onCommand(
                CommandSender sender,
                Command command,
                String label,
                String[] args
        ) {

            Player player;

            if (args.length == 0 && sender instanceof Player) {
                player = (Player) sender;
            } else if (args.length > 0) {
                player = Bukkit.getPlayerExact(args[0]);
            } else {
                sender.sendMessage(
                        "§c/deviceinfo <player>"
                );
                return true;
            }

            if (player == null || player.getAddress() == null) {
                sender.sendMessage(
                        "§cИгрок не найден/не подключён."
                );
                return true;
            }

            String id = fingerprint(
                    player.getAddress().getAddress(),
                    player.getName(),
                    player.getUniqueId()
            );

            sender.sendMessage(
                    "§7Fingerprint: §f" + id
            );

            sender.sendMessage(
                    "§7Status: " +
                    (
                            bans.containsKey(id)
                                    ? "§cBANNED"
                                    : "§aOK"
                    )
            );

            return true;
        }
    }
}
