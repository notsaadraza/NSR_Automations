package com.nsrautomations;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NSRAutomationsClient implements ClientModInitializer {

    // Matches lines like: "| Solve \u2192 3 * 4"  or  "| Solve \u2192 \u221a100"
    // Anchored per-line (MULTILINE) so it works whether the server sends the
    // 3-line box as one Text with embedded newlines, or as 3 separate messages.
    // Accepts both the real arrow (\u2192) the server uses and a plain "->" as a fallback.
    private static final Pattern MATH_PATTERN = Pattern.compile(
            "(?im)^\\s*\\|\\s*Solve\\s*(?:\u2192|->)\\s*(.+?)\\s*$");

    // Only characters a real math prompt from this server should contain.
    private static final Pattern MATH_EXPRESSION_VALIDATOR =
            Pattern.compile("^[0-9+\\-*/xX^().\\s\u221a\u00d7\u00f7]+$");

    // Matches lines like: "| Type \u2192 quarterback"
    private static final Pattern TYPE_GAME_PATTERN = Pattern.compile(
            "(?im)^\\s*\\|\\s*Type\\s*(?:\u2192|->)\\s*(.+?)\\s*$");

    // Scheduled executor to handle 2-3 seconds natural delay before answering
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final Random RANDOM = new Random();

    // Simple de-dupe / cooldown guard so the same prompt can't trigger twice
    // and rapid-fire chat can't queue up a pile of delayed replies.
    private static volatile String lastHandledMessage = null;
    private static volatile long lastHandledAtMillis = 0L;
    private static final long COOLDOWN_MILLIS = 1500L;

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, instant) -> {
            if (message == null) return;

            // Note: we deliberately do NOT filter by sender here. We don't know
            // for certain whether this server sends "| Solve -> ..." as a system
            // message (sender == null) or as chat from a fake/console account, and
            // guessing wrong would silently swallow every real prompt. Instead we
            // rely entirely on the strict "| Solve -> ..." / "| Type -> ..." line
            // match below, which is specific enough that no ordinary player chat
            // will ever accidentally match it.
            String messageText = message.getString();
            if (messageText == null || messageText.isEmpty()) return;

            if (isDuplicateOrTooSoon(messageText)) return;

            boolean handled = processMathGame(messageText);
            if (!handled) {
                handled = processTypeGame(messageText);
            }

            if (handled) {
                lastHandledMessage = messageText;
                lastHandledAtMillis = System.currentTimeMillis();
            }
        });
    }

    private boolean isDuplicateOrTooSoon(String messageText) {
        long now = System.currentTimeMillis();
        boolean sameAsLast = messageText.equals(lastHandledMessage);
        boolean withinCooldown = (now - lastHandledAtMillis) < COOLDOWN_MILLIS;
        return sameAsLast && withinCooldown;
    }

    private boolean processMathGame(String text) {
        try {
            Matcher matcher = MATH_PATTERN.matcher(text);
            if (matcher.find()) {
                // Strip trailing punctuation the server might tack on, e.g. "3 * 4 =" or "3 * 4?"
                String expression = matcher.group(1).trim().replaceAll("[=?:!]+$", "").trim();

                if (MATH_EXPRESSION_VALIDATOR.matcher(expression).matches()
                        && expression.matches(".*[0-9]+.*")) {
                    double result = eval(expression);
                    delayedSendChatMessage(formatResult(result));
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[NSRAutomations] Failed to parse/solve math expression from: \"" + text + "\" - " + e);
        }
        return false;
    }

    // Formats a solved answer: whole numbers print without a decimal point,
    // anything else keeps up to 4 decimal places with no trailing zeros.
    private String formatResult(double result) {
        if (!Double.isFinite(result)) return "0";
        if (result == Math.rint(result) && Math.abs(result) < 1_000_000_000L) {
            return String.valueOf((long) result);
        }
        String formatted = String.format("%.4f", result);
        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        return formatted;
    }

    // Built-in lightweight calculator for evaluating expressions safely.
    // Supports + - * / ^ (power), parentheses, unary +/-, and now:
    //   \u221a  (square root, e.g. \u221a100)
    //   \u00d7 \u00f7 (multiply/divide symbols, as alternates to * and /)
    private double eval(String str) {
        str = str.replace('\u00d7', '*').replace('\u00f7', '/');
        str = str.replaceAll("[xX]", "*");
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < str.length()) ? str.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < str.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                if (eat('\u221a')) return Math.sqrt(parseFactor());

                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("Unexpected: " + (char) ch);
                }

                if (eat('^')) x = Math.pow(x, parseFactor());

                return x;
            }
        }.parse();
    }

    private boolean processTypeGame(String text) {
        try {
            Matcher matcher = TYPE_GAME_PATTERN.matcher(text);
            if (matcher.find()) {
                String wordToType = matcher.group(1);
                if (wordToType != null && !wordToType.isEmpty()) {
                    delayedSendChatMessage(wordToType);
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[NSRAutomations] Failed to parse type-game word from: \"" + text + "\" - " + e);
        }
        return false;
    }

    private void delayedSendChatMessage(String message) {
        long delay = 2000 + RANDOM.nextInt(1000);

        SCHEDULER.schedule(() -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.execute(() -> {
                        if (client.player != null && client.getNetworkHandler() != null) {
                            client.getNetworkHandler().sendChatMessage(message);
                        }
                    });
                }
            } catch (Exception e) {
                System.err.println("[NSRAutomations] Failed to send chat message: " + e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
