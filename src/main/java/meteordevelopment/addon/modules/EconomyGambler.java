package meteordevelopment.addon.modules;

import meteordevelopment.addon.Addon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.stream.Collectors;

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

    private final Setting<Long> minBet = sgGeneral.add(new LongSetting.Builder()
            .name("min-bet")
            .description("Minimaler erlaubter Einsatz.")
            .defaultValue(0L)
            .min(0L)
            .build()
    );

    private final Setting<Long> maxBet = sgGeneral.add(new LongSetting.Builder()
            .name("max-bet")
            .description("Maximaler erlaubter Einsatz.")
            .defaultValue(10000000000L) // 10 Milliarden
            .min(0L)
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
        super(Addon.GAMBLING_CATEGORY, "economy-gambler", "Automatisches Casino mit intelligentem Werbe-Flüstern.");
    }

    @Override
    public void onActivate() {
        ticksPassed = 0;
        alreadyMessaged.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null || !enableAutoMsg.get()) return;

        ticksPassed++;
        
        if (ticksPassed >= (msgInterval.get() * 20)) {
            ticksPassed = 0;
            sendAdvertisingMessage();
        }
    }

    private void sendAdvertisingMessage() {
        Collection<PlayerListEntry> playerList = mc.getNetworkHandler().getPlayerList();
        if (playerList == null || playerList.isEmpty()) return;

        String myName = mc.player.getGameProfile().getName();

        List<String> validTargets = playerList.stream()
                .map(entry -> entry.getProfile().getName())
                .filter(name -> !name.equalsIgnoreCase(myName))
                .filter(name -> !isBlacklisted(name))
                .filter(name -> !alreadyMessaged.contains(name))
                .collect(Collectors.toList());

        if (validTargets.isEmpty() && !alreadyMessaged.isEmpty()) {
            alreadyMessaged.clear();
            validTargets = playerList.stream()
                    .map(entry -> entry.getProfile().getName())
                    .filter(name -> !name.equalsIgnoreCase(myName))
                    .filter(name -> !isBlacklisted(name))
                    .collect(Collectors.toList());
        }

        if (validTargets.isEmpty()) return;

        String luckyPlayer = validTargets.get(random.nextInt(validTargets.size()));
        
        String finalCommand = "msg " + luckyPlayer + " /pay philipp86 for AUTOMATED 50/50 Gambling";
        mc.player.networkHandler.sendChatCommand(finalCommand);

        alreadyMessaged.add(luckyPlayer);
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.world == null) return;

        if (event.isMessage() && event.getSender() != null) {
            return; 
        }

        String rawMessage = event.getMessage().getString();
        Matcher matcher = paymentPattern.matcher(rawMessage);

        if (matcher.find()) {
            String playerName = matcher.group(1);
            String amountStr = matcher.group(2).replaceAll("[.,]", "");
            String suffix = matcher.group(3).toLowerCase();
            
            if (isBlacklisted(playerName)) {
                if (refundBlacklisted.get()) {
                    try {
                        long baseAmount = Long.parseLong(amountStr);
                        long finalBet = parseMultiplier(baseAmount, suffix);
                        executePayout(playerName, finalBet);
                    } catch (NumberFormatException e) {
                        // Ignorieren
                    }
                }
                return;
            }

            try {
                long baseAmount = Long.parseLong(amountStr);
                long finalBet = parseMultiplier(baseAmount, suffix);
                
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

    private long parseMultiplier(long amount, String suffix) {
        switch (suffix) {
            case "k": return amount * 1_000L;
            case "m": return amount * 1_000_000L;
            case "b": return amount * 1_000_000_000L;
            default: return amount;
        }
    }

    private void processBet(String player, long amount) {
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
            long payout = (long) (amount * payoutMultiplier.get());
            sendChatResponse(player, " GEWONNEN! Gewürfelt: " + String.format("%.1f", roll) + "/" + winRate.get() + "%. Gewinn: $" + formatAmount(payout));
            executePayout(player, payout);
        } else {
            sendChatResponse(player, " VERLOREN! Gewürfelt: " + String.format("%.1f", roll) + "/" + winRate.get() + "%. Viel Glück beim nächsten Mal!");
        }
    }

    private void refundPlayer(String player, long amount) {
        executePayout(player, amount);
    }

    private void executePayout(String player, long amount) {
        String command = payCommand.get()
                .replace("{player}", player)
                .replace("{amount}", String.valueOf(amount));
        
        mc.player.networkHandler.sendChatCommand(command.replace("/", ""));
    }

    private void sendChatResponse(String player, String message) {
        mc.player.networkHandler.sendChatMessage("/msg " + player + " " + message);
    }

    private String formatAmount(long amount) {
        if (amount >= 1_000_000_000L) return (amount / 1_000_000_000L) + "B";
        if (amount >= 1_000_000L) return (amount / 1_000_000L) + "M";
        if (amount >= 1_000L) return (amount / 1_000L) + "k";
        return String.valueOf(amount);
    }
}
