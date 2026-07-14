package meteordevelopment.addon.modules;

import meteordevelopment.addon.Addon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

public class EconomyGambler extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoMsg = settings.createGroup("Auto Werbe-Flüstern");
    private final SettingGroup sgBlacklist = settings.createGroup("Blacklist / Admins");

    // --- ALLGEMEINE EINSTELLUNGEN ---
    private final Setting<Double> winRate = sgGeneral.add(new DoubleSetting.Builder()
            .name("win-rate (%)")
            .description("Die Gewinnwahrscheinlichkeit für Spieler (0-100).")
            .defaultValue(45.0)
            .min(0.0)
            .max(100.0)
            .sliderMax(100.0)
            .build()
    );

    private final Setting<Double> payoutMultiplier = sgGeneral.add(new DoubleSetting.Builder()
            .name("payout-multiplier")
            .description("Multiplikator bei Gewinn (z.B. 2.0 für Verdopplung).")
            .defaultValue(2.0)
            .min(1.0)
            .sliderMax(5.0)
            .build()
    );

    private final Setting<Integer> minBet = sgGeneral.add(new IntSetting.Builder()
            .name("min-bet")
            .description("Minimaler erlaubter Einsatz.")
            .defaultValue(0)
            .min(0)
            .build()
    );

    private final Setting<Integer> maxBet = sgGeneral.add(new IntSetting.Builder()
            .name("max-bet")
            .description("Maximaler erlaubter Einsatz.")
            .defaultValue(2000000000) // Begrenzt auf 2 Milliarden wegen Int-Grenzbereich
            .min(0)
            .build()
    );

    private final Setting<String> payCommand = sgGeneral.add(new StringSetting.Builder()
            .name("payout-command")
            .description("Das Pay-Kommando des Servers. Nutze {player} und {amount}.")
            .defaultValue("/pay {player} {amount}")
            .build()
    );

    // --- AUTO MSG EINSTELLUNGEN ---
    private final Setting<Boolean> enableAutoMsg = sgAutoMsg.add(new BoolSetting.Builder()
            .name("werbung-aktivieren")
            .description("Schaltet das automatische Anschreiben zufälliger Spieler ein.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> msgInterval = sgAutoMsg.add(new IntSetting.Builder()
            .name("intervall (sekunden)")
            .description("Wie viele Sekunden zwischen den Nachrichten gewartet wird.")
            .defaultValue(60)
            .min(5)
            .sliderMax(300)
            .build()
    );

    // --- BLACKLIST EINSTELLUNGEN ---
    private final Setting<List<String>> blacklistedPlayers = sgBlacklist.add(new StringListSetting.Builder()
            .name("blockierte-spieler")
            .description("Namen von Admins, die nicht bespielt und NIEMALS angeschrieben werden.")
            .defaultValue(new ArrayList<>())
            .build()
    );

    private final Setting<Boolean> refundBlacklisted = sgBlacklist.add(new BoolSetting.Builder()
            .name("blacklist-zurückzahlen")
            .description("Ob blockierte Spieler ihr Geld zurückbekommen.")
            .defaultValue(false)
            .build()
    );

    private final Random random = new Random();
    private int ticksPassed = 0;
    
    private final Set<String> alreadyMessaged = new HashSet<>();
    private final Pattern paymentPattern = Pattern.compile("(?:From )?([a-zA-Z0-9_]{3,16})\\s*(?:has sent you|-> You:)\\s*\\$?([0-9.,]+)\\s*([kKmMbB]?)");

    public EconomyGambler() {
        super(Addon.GAMBLING_CATEGORY, "economy-gambler", "Automatisches Casino optimiert für Version 1.21.11 Mojang-Mappings.");
    }

    @Override
    public void onActivate() {
        ticksPassed = 0;
        alreadyMessaged.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || !enableAutoMsg.get()) return;

        ticksPassed++;
        
        if (ticksPassed >= (msgInterval.get() * 20)) {
            ticksPassed = 0;
            sendAdvertisingMessage();
        }
    }

    private void sendAdvertisingMessage() {
        if (mc.player.connection == null) return;
        
        // Nutzt die für 1.21.11 Mojang-Mappings stabilen Paket-IDs
        var onlineIds = mc.player.connection.getOnlinePlayerIds();
        if (onlineIds == null || onlineIds.isEmpty()) return;

        List<String> validTargets = new ArrayList<>();
        String myName = mc.player.getScoreboardName();

        for (UUID id : onlineIds) {
            var playerInfo = mc.player.connection.getPlayerInfo(id);
            if (playerInfo == null || playerInfo.getProfile() == null) continue;
            
            String name = playerInfo.getProfile().getName();
            if (name != null && !name.equalsIgnoreCase(myName) && !isBlacklisted(name) && !alreadyMessaged.contains(name)) {
                validTargets.add(name);
            }
        }

        if (validTargets.isEmpty() && !alreadyMessaged.isEmpty()) {
            alreadyMessaged.clear();
            for (UUID id : onlineIds) {
                var playerInfo = mc.player.connection.람getPlayerInfo(id);
                if (playerInfo == null || playerInfo.getProfile() == null) continue;
                String name = playerInfo.getProfile().getName();
                if (name != null && !name.equalsIgnoreCase(myName) && !isBlacklisted(name)) {
                    validTargets.add(name);
                }
            }
        }

        if (validTargets.isEmpty()) return;

        String luckyPlayer = validTargets.get(random.nextInt(validTargets.size()));
        
        String finalCommand = "msg " + luckyPlayer + " /pay philipp86 for AUTOMATED 50/50 Gambling";
        mc.player.connection.sendChatCommand(finalCommand);

        alreadyMessaged.add(luckyPlayer);
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null || event.getMessage() == null) return;

        String rawMessage = event.getMessage().getString();
        Matcher matcher = paymentPattern.matcher(rawMessage);

        if (matcher.find()) {
            String playerName = matcher.group(1);
            String amountStr = matcher.group(2).replaceAll("[.,]", "");
            String suffix = matcher.group(3).toLowerCase();
            
            if (isBlacklisted(playerName)) {
                if (refundBlacklisted.get()) {
                    try {
                        int baseAmount = Integer.parseInt(amountStr);
                        int finalBet = parseMultiplier(baseAmount, suffix);
                        executePayout(playerName, finalBet);
                    } catch (NumberFormatException e) {
                        // Ignorieren
                    }
                }
                return;
            }

            try {
                int baseAmount = Integer.parseInt(amountStr);
                int finalBet = parseMultiplier(baseAmount, suffix);
                
                processBet(playerName, finalBet);
            } catch (NumberFormatException e) {
                // Ignorieren
            }
        }
    }

    private boolean isBlacklisted(String username) {
        for (String blacklisted : blacklistedPlayers.get()) {
            if (blacklisted.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }

    private int parseMultiplier(int amount, String suffix) {
        switch (suffix) {
            case "k": return amount * 1_000;
            case "m": return amount * 1_000_000;
            default: return amount;
        }
    }

    private void processBet(String player, int amount) {
        if (amount < minBet.get()) {
            sendChatResponse(player, "Einsatz zu niedrig! Minimum ist $" + formatAmount(minBet.get()));
            refundPlayer(player, amount);
            return;
        }
        
        if (amount > maxBet.get()) {
            sendChatResponse(player, "Einsatz zu hoch! Maximum ist $" + formatAmount(maxBet.get()));
            refundPlayer(player, amount);
            return;
        }

        double roll = random.nextDouble() * 100;
        boolean isWin = roll <= winRate.get();

        if (isWin) {
            int payout = (int) (amount * payoutMultiplier.get());
            sendChatResponse(player, " GEWONNEN! Gewürfelt: " + String.format("%.1f", roll) + "/" + winRate.get() + "%. Gewinn: $" + formatAmount(payout));
            executePayout(player, payout);
        } else {
            sendChatResponse(player, " VERLOREN! Gewürfelt: " + String.format("%.1f", roll) + "/" + winRate.get() + "%. Viel Glück beim nächsten Mal!");
        }
    }

    private void refundPlayer(String player, int amount) {
        executePayout(player, amount);
    }

    private void executePayout(String player, int amount) {
        String command = payCommand.get()
                .replace("{player}", player)
                .replace("{amount}", String.valueOf(amount));
        
        if (command.startsWith("/")) command = command.substring(1);
        mc.player.connection.sendChatCommand(command);
    }

    private void sendChatResponse(String player, String message) {
        mc.player.connection.sendChatMessage("/msg " + player + " " + message);
    }

    private String formatAmount(int amount) {
        if (amount >= 1_000_000) return (amount / 1_000_000) + "M";
        if (amount >= 1_000) return (amount / 1_000) + "k";
        return String.valueOf(amount);
    }
}
