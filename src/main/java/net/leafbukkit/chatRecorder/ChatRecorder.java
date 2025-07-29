package net.leafbukkit.chatRecorder;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import net.sourceforge.pinyin4j.PinyinHelper;
import com.velocitypowered.api.event.command.CommandExecuteEvent;

import java.io.File;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "chatrecorder",
        name = "ChatRecorder",
        version = "0.2.3",
        description = "Recorder",
        authors = {"NetScn"}
)
public class ChatRecorder {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    private Connection connection;
    private final Map<UUID, Boolean> authenticatedAdmins = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingAdminChecks = new ConcurrentHashMap<>();
    private static final String PASSWORD = "password";
    private final Set<String> clickTeleportTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Long> commandUsageLog = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL = 7 * 24 * 60 * 60 * 1000L; // 7天
    private long lastCleanupTime = System.currentTimeMillis();
    private ScheduledTask cleanupTask;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        initializeDatabase();
        server.getCommandManager().register("chatrecorder", new ChatRecorderCommand());
        logger.info("ChatRecorder v0.2.3 - 此服务器已经被正版授权！");
        logger.info("Created By NetScn");
        logger.warn("已经添加 SQLite 的支持。");

        server.getCommandManager().register("chatrecorderclick", new ChatRecorderClickCommand());
        server.getCommandManager().register("chatrecorderteleport", new ChatRecorderTeleportCommand(), "crtp");

        // 启动定期清理任务
        cleanupTask = server.getScheduler().buildTask(this, this::cleanupOldLogs)
                .repeat(1, TimeUnit.HOURS)
                .schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.warn("已关闭数据库连接");
            }
        } catch (SQLException e) {
            logger.error("关闭数据库连接时出错:", e);
        }

        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
    }

    private void cleanupOldLogs() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime > CLEANUP_INTERVAL) {
            commandUsageLog.entrySet().removeIf(entry -> now - entry.getValue() > CLEANUP_INTERVAL);
            lastCleanupTime = now;
            logger.info("已清理过期的命令使用记录");
        }
    }

    private void initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            logger.info("成功加载 SQLite JDBC 驱动 org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("找不到 SQLite JDBC 驱动 org.sqlite.JDBC，请检查是否shade成功！", e);
            return;
        }

        try {
            File dataFolder = new File("plugins/ChatRecorder");
            if (!dataFolder.exists()) {
                boolean created = dataFolder.mkdirs();
                if (created) {
                    logger.info("创建插件数据文件夹: " + dataFolder.getAbsolutePath());
                }
            }
            File dbFile = new File(dataFolder, "chat_recorder.db");

            String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(jdbcUrl);
            logger.info("连接 SQLite 数据库: " + jdbcUrl);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS chat_records (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "player_uuid TEXT NOT NULL," +
                                "player_name TEXT NOT NULL," +
                                "message TEXT NOT NULL," +
                                "server_name TEXT NOT NULL," +
                                "is_command " +
                                "BOOLEAN NOT NULL," +
                                "timestamp BIGINT NOT NULL" +
                                ")"
                );

                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_uuid ON chat_records(player_uuid)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_name ON chat_records(player_name)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_timestamp ON chat_records(timestamp)");

                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS player_info (" +
                                "uuid TEXT PRIMARY KEY," +
                                "name TEXT NOT NULL," +
                                "first_seen BIGINT NOT NULL," +
                                "last_seen BIGINT NOT NULL" +
                                ")"
                );
            }

            logger.info("SQLite 数据库初始化完成");

        } catch (SQLException e) {
            logger.error("初始化 SQLite 数据库时发生错误:", e);
        }
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String serverName = player.getCurrentServer()
                .map(serverConnection -> serverConnection.getServerInfo().getName())
                .orElse("unknown");

        updatePlayerInfo(player.getUniqueId(), player.getUsername());

        if (!message.startsWith("/")) {
            saveChatRecord(player.getUniqueId(), player.getUsername(), message, serverName, false);
        }
    }

    private void updatePlayerInfo(UUID playerUuid, String playerName) {
        long now = System.currentTimeMillis();
        String sql = "INSERT OR REPLACE INTO player_info(uuid, name, first_seen, last_seen) " +
                "VALUES(?, ?, COALESCE((SELECT first_seen FROM player_info WHERE uuid = ?), ?), ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, playerUuid.toString());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.error("更新玩家信息时出错:", e);
        }
    }

    private void saveChatRecord(UUID playerUuid, String playerName, String message, String serverName, boolean isCommand) {
        String sql = "INSERT INTO chat_records(player_uuid, player_name, message, server_name, is_command, timestamp) " +
                "VALUES(?, ?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, message);
            statement.setString(4, serverName);
            statement.setBoolean(5, isCommand);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.error("保存聊天记录时出错:", e);
        }
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getCommandSource();
        String command = event.getCommand();

        if (command.toLowerCase().startsWith("chatrecorder") ||
                command.toLowerCase().startsWith("chatrecorderclick") ||
                command.toLowerCase().startsWith("chatrecorderteleport") ||
                command.toLowerCase().startsWith("crtp")) {
            return;
        }

        String serverName = player.getCurrentServer()
                .map(serverConnection -> serverConnection.getServerInfo().getName())
                .orElse("unknown");

        updatePlayerInfo(player.getUniqueId(), player.getUsername());

        saveChatRecord(player.getUniqueId(), player.getUsername(), "/" + command, serverName, true);
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        authenticatedAdmins.remove(playerId);
        pendingAdminChecks.remove(playerId);
    }
/**
 *
 * 此功能为反馈给一个指定玩家使用违规命令（/chatrecorder）的警报
 * 你可以取消它，也可以重新启用它
 *
 *
 */

  //  @Subscribe
    //public void onPlayerPostLogin(PostLoginEvent event) {
      //  Player player = event.getPlayer();
        //if (player.getUsername().equalsIgnoreCase("Hi_NeteaseMC")) {
         //   logger.info("[Debugger] Preparing to send Notify"); // 调试日志

            // 延迟1秒发送，确保玩家完全进入服务器
          //  server.getScheduler().buildTask(this, () -> {
            //    List<String> recentUsers = getRecentCommandUsers();
              //  if (!recentUsers.isEmpty()) {
                //    TextComponent.Builder message = Component.text()
                  //          .append(Component.text("§e管理系统 §f>> §c你的进入发现了 " + recentUsers.size() + " 个玩家使用CR相关命令\n"))
                    //        .append(Component.text("§7列表如下(最近7天):\n"));

                //    for (String user : recentUsers) {
                  //      message.append(Component.text("§7- " + user + "\n"));
                    //}

                    //player.sendMessage(message.build());
                    //logger.info("success!"); // 调试日志
           //     }
            //}).delay(1, TimeUnit.SECONDS).schedule();
        //}
    //}

//    private void notifyHiNeteaseMCRealTime(Player commandUser) {
  //      server.getPlayer("Hi_NeteaseMC").ifPresent(hiNeteaseMC -> {
    //        if (!commandUser.getUsername().equalsIgnoreCase("Hi_NeteaseMC")) {
      //          Component message = Component.text()
        //                .append(Component.text("§e管理系统 §7· §c§l警告！ §f>> §c玩家 "))
          //              .append(Component.text(commandUser.getUsername(), NamedTextColor.YELLOW))
            //            .append(Component.text(" 刚刚使用了 ChatRecorder 命令"))
              //          .append(Component.text("\n请注意观察当前操作环境！并且对该玩家进行处罚！！！"))
                //        .build();
                //hiNeteaseMC.sendMessage(message);
           // }
        //});
   // }

    private String generateTeleportToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private class ChatRecorderCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("只有玩家可以使用此命令!", NamedTextColor.RED));
                return;
            }

            Player player = (Player) source;

            // 记录命令使用并实时通知
            commandUsageLog.put(player.getUniqueId(), System.currentTimeMillis());
            //notifyHiNeteaseMCRealTime(player);

            String[] args = invocation.arguments();

            if (args.length == 0) {
                if (!authenticatedAdmins.getOrDefault(player.getUniqueId(), false)) {
                    promptForPassword(player);
                } else {
                    player.sendMessage(Component.text("§c请输入玩家名进行查询，格式: /chatrecorder <玩家名> {关键字}\n" +
                            "§a或使用 /chatrecorder all {关键字} 查询所有玩家记录"));
                }
                return;
            }

            if (args[0].equalsIgnoreCase("exitenterpw")) {
                pendingAdminChecks.remove(player.getUniqueId());
                player.sendMessage(Component.text("已退出密码输入界面。", NamedTextColor.GREEN));
                return;
            }

            if (pendingAdminChecks.containsKey(player.getUniqueId())) {
                handlePasswordCheck(player, args[0]);
                return;
            }

            if (!authenticatedAdmins.getOrDefault(player.getUniqueId(), false)) {
                promptForPassword(player);
                return;
            }

            String targetName = args[0];
            String keyword = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;

            if (targetName.equalsIgnoreCase("all")) {
                showAllChatRecords(player, keyword, 1);
                return;
            }

            Optional<Player> targetPlayer = server.getPlayer(targetName);
            if (targetPlayer.isPresent()) {
                showChatRecords(player, targetPlayer.get(), keyword, 1);
            } else {
                Optional<UUID> targetUuid = findPlayerUuid(targetName);
                if (targetUuid.isPresent()) {
                    showChatRecords(player, targetUuid.get(), targetName, keyword, 1);
                } else {
                    player.sendMessage(Component.text("找不到玩家: " + targetName, NamedTextColor.RED));
                }
            }
        }

        private void promptForPassword(Player player) {
            pendingAdminChecks.put(player.getUniqueId(), "");
            player.sendMessage(Component.text()
                    .content("§fChatRecorder v0.2.3 - By Net\n\n§f/chatrecorder §6<password>\n§f/chatrecorder §6exitenterpw")
                    .build());
        }

        private void handlePasswordCheck(Player player, String input) {
            if (input.equals(PASSWORD)) {
                authenticatedAdmins.put(player.getUniqueId(), true);
                pendingAdminChecks.remove(player.getUniqueId());
                player.sendMessage(Component.text("密码验证成功！请输入 /chatrecorder <玩家名> {关键字}（关键字是可选的,不区分字母大小写） 进行查询。\n" +
                        "或使用 /chatrecorder all {关键字} 查询所有玩家记录", NamedTextColor.GREEN));
            } else {
                player.disconnect(Component.text()
                        .content("§c你的账户已被挂起！请重新加入！")
                        .build());
            }
        }

        private Optional<UUID> findPlayerUuid(String username) {
            Optional<Player> onlinePlayer = server.getAllPlayers().stream()
                    .filter(p -> p.getUsername().equalsIgnoreCase(username))
                    .findFirst();

            if (onlinePlayer.isPresent()) {
                return onlinePlayer.map(Player::getUniqueId);
            }

            String sql = "SELECT uuid FROM player_info WHERE LOWER(name) = LOWER(?) LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(UUID.fromString(rs.getString("uuid")));
                    }
                }
            } catch (SQLException e) {
                logger.error("查询玩家UUID时出错:", e);
            }

            return Optional.empty();
        }

        private void showChatRecords(Player admin, Player target, String keyword, int page) {
            showChatRecords(admin, target.getUniqueId(), target.getUsername(), keyword, page);
        }

        private void showChatRecords(Player admin, UUID targetUuid, String targetName, String keyword, int page) {
            List<ChatRecord> records = loadChatRecordsFromDatabase(targetUuid, keyword);

            if (records.isEmpty()) {
                admin.sendMessage(Component.text("没有找到聊天记录" +
                        (keyword != null ? " (关键词: " + keyword + ")" : ""), NamedTextColor.RED));
                return;
            }

            int pageSize = 10;
            int totalPages = (int) Math.ceil((double) records.size() / pageSize);
            page = Math.max(1, Math.min(page, totalPages));

            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, records.size());
            List<ChatRecord> pageRecords = records.subList(start, end);

            TextComponent.Builder messageBuilder = Component.text();

            messageBuilder.append(Component.text("§f----------------------\n"));
            messageBuilder.append(Component.text("玩家名: " + targetName + "\n"));

            if (keyword != null) {
                messageBuilder.append(Component.text("关键字: " + keyword + "\n\n"));
            }

            for (ChatRecord record : pageRecords) {
                String time = formatTime(record.getTimestamp());
                String messagePrefix = record.isCommand() ? "§6[命令] " : "§f";
                String token = generateTeleportToken();
                clickTeleportTokens.add(token);

                TextComponent recordLine = Component.text()
                        .content("§7[" + time + "] " + messagePrefix + record.getMessage())
                        .hoverEvent(HoverEvent.showText(Component.text("§f发表于 " + record.getServerName() + "\n§e点击传送到该文本发送的子服")))
                        .clickEvent(ClickEvent.runCommand("/crtp " + record.getServerName() + " " + token))
                        .build();
                messageBuilder.append(recordLine).append(Component.newline());
            }

            TextComponent prevPage = buildPageNavButton("<<", page > 1, page - 1, targetName, keyword);
            TextComponent nextPage = buildPageNavButton(">>", page < totalPages, page + 1, targetName, keyword);

            messageBuilder.append(Component.text("\n§a" + page + "/" + totalPages + " "));
            messageBuilder.append(prevPage).append(Component.text(" "));
            messageBuilder.append(nextPage);
            messageBuilder.append(Component.text("\n§f----------------------\n"));
            messageBuilder.append(Component.text("§c输入/chatrecorder <玩家名> {关键字} 查询聊天记录"));
            messageBuilder.append(Component.text("\n§f----------------------"));

            admin.sendMessage(messageBuilder.build());
        }

        private void showAllChatRecords(Player admin, String keyword, int page) {
            List<ChatRecord> records = loadAllChatRecordsFromDatabase(keyword);

            if (records.isEmpty()) {
                admin.sendMessage(Component.text("没有找到聊天记录" +
                        (keyword != null ? " (关键词: " + keyword + ")" : ""), NamedTextColor.RED));
                return;
            }

            int pageSize = 10;
            int totalPages = (int) Math.ceil((double) records.size() / pageSize);
            page = Math.max(1, Math.min(page, totalPages));

            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, records.size());
            List<ChatRecord> pageRecords = records.subList(start, end);

            TextComponent.Builder messageBuilder = Component.text();

            messageBuilder.append(Component.text("§f----------------------\n"));
            messageBuilder.append(Component.text("§f在 所有玩家的聊天记录 中查询\n"));

            if (keyword != null) {
                messageBuilder.append(Component.text("关键字: " + keyword + "\n\n"));
            }

            for (ChatRecord record : pageRecords) {
                String time = formatTime(record.getTimestamp());
                String messagePrefix = record.isCommand() ? "§6[命令] " : "§f";
                String token = generateTeleportToken();
                clickTeleportTokens.add(token);

                TextComponent recordLine = Component.text()
                        .content("§7[" + time + "] §e" + record.getPlayerName() + ": " + messagePrefix + record.getMessage())
                        .hoverEvent(HoverEvent.showText(Component.text("§f玩家: " + record.getPlayerName() +
                                "\n发表于 " + record.getServerName() +
                                "\n§e点击传送到该文本发送的子服")))
                        .clickEvent(ClickEvent.runCommand("/crtp " + record.getServerName() + " " + token))
                        .build();
                messageBuilder.append(recordLine).append(Component.newline());
            }

            TextComponent prevPage = buildAllPageNavButton("<<", page > 1, page - 1, keyword);
            TextComponent nextPage = buildAllPageNavButton(">>", page < totalPages, page + 1, keyword);

            messageBuilder.append(Component.text("\n§a" + page + "/" + totalPages + " "));
            messageBuilder.append(prevPage).append(Component.text(" "));
            messageBuilder.append(nextPage);
            messageBuilder.append(Component.text("\n§f----------------------\n"));
            messageBuilder.append(Component.text("§c输入/chatrecorder all {关键字} 查询所有玩家记录"));
            messageBuilder.append(Component.text("\n§f----------------------"));

            admin.sendMessage(messageBuilder.build());
        }

        private List<ChatRecord> loadChatRecordsFromDatabase(UUID playerUuid, String keyword) {
            List<ChatRecord> records = new ArrayList<>();
            String sql = "SELECT message, server_name, is_command, timestamp FROM chat_records WHERE player_uuid = ? ORDER BY timestamp ASC";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String message = resultSet.getString("message");
                        String serverName = resultSet.getString("server_name");
                        boolean isCommand = resultSet.getBoolean("is_command");
                        long timestamp = resultSet.getLong("timestamp");

                        if (keyword == null || keyword.isEmpty() || containsKeywordIncludingPinyin(message, keyword)) {
                            records.add(new ChatRecord(message, timestamp, serverName, isCommand));
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("从数据库加载聊天记录时出错:", e);
            }

            return records;
        }

        private List<ChatRecord> loadAllChatRecordsFromDatabase(String keyword) {
            List<ChatRecord> records = new ArrayList<>();
            String sql = "SELECT cr.message, cr.server_name, cr.is_command, cr.timestamp, pi.name as player_name " +
                    "FROM chat_records cr " +
                    "JOIN player_info pi ON cr.player_uuid = pi.uuid " +
                    "ORDER BY cr.timestamp ASC";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String message = resultSet.getString("message");
                        String serverName = resultSet.getString("server_name");
                        boolean isCommand = resultSet.getBoolean("is_command");
                        long timestamp = resultSet.getLong("timestamp");
                        String playerName = resultSet.getString("player_name");

                        if (keyword == null || keyword.isEmpty() ||
                                containsKeywordIncludingPinyin(message, keyword) ||
                                containsKeywordIncludingPinyin(playerName, keyword)) {
                            records.add(new ChatRecord(message, timestamp, serverName, isCommand, playerName));
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("从数据库加载所有聊天记录时出错:", e);
            }

            return records;
        }

        private TextComponent buildPageNavButton(String label, boolean enabled, int targetPage, String targetName, String keyword) {
            if (enabled) {
                String hoverText = label.equals("<<")
                        ? "点击切换到上一页"
                        : "点击切换到下一页";
                return Component.text()
                        .content("§a(" + label + ")")
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.YELLOW)))
                        .clickEvent(ClickEvent.runCommand("/chatrecorderclick " + targetName + "|" +
                                (keyword != null ? keyword : "") + "|" + targetPage))
                        .build();
            } else {
                String hoverText = label.equals("<<")
                        ? "没有更多的上一页"
                        : "没有更多的下一页";
                return Component.text()
                        .content("§7(" + label + ")")
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)))
                        .build();
            }
        }

        private TextComponent buildAllPageNavButton(String label, boolean enabled, int targetPage, String keyword) {
            if (enabled) {
                String hoverText = label.equals("<<")
                        ? "点击切换到上一页"
                        : "点击切换到下一页";
                return Component.text()
                        .content("§a(" + label + ")")
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.YELLOW)))
                        .clickEvent(ClickEvent.runCommand("/chatrecorderclick all|" +
                                (keyword != null ? keyword : "") + "|" + targetPage))
                        .build();
            } else {
                String hoverText = label.equals("<<")
                        ? "没有更多的上一页"
                        : "没有更多的下一页";
                return Component.text()
                        .content("§7(" + label + ")")
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)))
                        .build();
            }
        }

        private String formatTime(long timestamp) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
        }

        private boolean containsKeywordIncludingPinyin(String message, String keyword) {
            if (message == null || keyword == null) return false;

            String lowerMessage = message.toLowerCase();
            String lowerKeyword = keyword.toLowerCase();

            if (lowerMessage.contains(lowerKeyword)) {
                return true;
            }

            StringBuilder pinyinInitialsBuilder = new StringBuilder();
            StringBuilder fullPinyinBuilder = new StringBuilder();

            for (int i = 0; i < message.length(); i++) {
                char ch = message.charAt(i);
                if (Character.toString(ch).matches("[\\u4E00-\\u9FA5]")) {
                    String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch);
                    if (pinyins != null && pinyins.length > 0) {
                        String pinyin = pinyins[0].replaceAll("[^a-zA-Z]", "").toLowerCase();
                        if (!pinyin.isEmpty()) {
                            pinyinInitialsBuilder.append(pinyin.charAt(0));
                            fullPinyinBuilder.append(pinyin);
                        }
                    }
                } else {
                    char lowerCh = Character.toLowerCase(ch);
                    pinyinInitialsBuilder.append(lowerCh);
                    fullPinyinBuilder.append(lowerCh);
                }
            }

            String pinyinInitials = pinyinInitialsBuilder.toString();
            String fullPinyin = fullPinyinBuilder.toString();

            return pinyinInitials.contains(lowerKeyword) || fullPinyin.contains(lowerKeyword);
        }
    }

    private static class ChatRecord {
        private final String message;
        private final long timestamp;
        private final String serverName;
        private final boolean isCommand;
        private final String playerName;

        public ChatRecord(String message, long timestamp, String serverName, boolean isCommand) {
            this(message, timestamp, serverName, isCommand, null);
        }

        public ChatRecord(String message, long timestamp, String serverName, boolean isCommand, String playerName) {
            this.message = message;
            this.timestamp = timestamp;
            this.serverName = serverName;
            this.isCommand = isCommand;
            this.playerName = playerName;
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getServerName() {
            return serverName;
        }

        public boolean isCommand() {
            return isCommand;
        }

        public String getPlayerName() {
            return playerName;
        }
    }

    private class ChatRecorderClickCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("只有玩家可以使用此命令!", NamedTextColor.RED));
                return;
            }
            Player player = (Player) source;
            String[] args = invocation.arguments();
            if (args.length == 0) {
                player.sendMessage(Component.text("参数错误，请使用分页按钮！", NamedTextColor.RED));
                return;
            }

            String[] parts = args[0].split("\\|", 3);
            if (parts.length < 3) {
                player.sendMessage(Component.text("参数格式错误，请使用分页按钮！", NamedTextColor.RED));
                return;
            }

            String targetName = parts[0];
            String keyword = parts[1].isEmpty() ? null : parts[1];
            int page;
            try {
                page = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                page = 1;
            }

            ChatRecorderCommand command = new ChatRecorderCommand();
            if (targetName.equalsIgnoreCase("all")) {
                command.showAllChatRecords(player, keyword, page);
                return;
            }

            Optional<Player> targetPlayer = server.getPlayer(targetName);
            if (targetPlayer.isPresent()) {
                command.showChatRecords(player, targetPlayer.get(), keyword, page);
            } else {
                Optional<UUID> targetUuid = command.findPlayerUuid(targetName);
                if (targetUuid.isPresent()) {
                    command.showChatRecords(player, targetUuid.get(), targetName, keyword, page);
                } else {
                    player.sendMessage(Component.text("找不到玩家: " + targetName, NamedTextColor.RED));
                }
            }
        }
    }

    private class ChatRecorderTeleportCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("只有玩家可以使用此命令!", NamedTextColor.RED));
                return;
            }

            Player player = (Player) source;
            String[] args = invocation.arguments();

            if (args.length < 2) {
                player.sendMessage(Component.text("§c无效的传送请求!", NamedTextColor.RED));
                return;
            }

            String serverName = args[0];
            String token = args[1];

            if (!clickTeleportTokens.contains(token)) {
                player.sendMessage(Component.text("§c无效的传送令牌，请通过点击聊天记录中的链接传送!", NamedTextColor.RED));
                return;
            }

            clickTeleportTokens.remove(token);

            server.getServer(serverName).ifPresentOrElse(
                    serverConnection -> {
                        player.createConnectionRequest(serverConnection).fireAndForget();
                        player.sendMessage(Component.text("正在传送到子服: " + serverName, NamedTextColor.GREEN));
                    },
                    () -> player.sendMessage(Component.text("找不到该子服: " + serverName, NamedTextColor.RED))
            );
        }
    }
}