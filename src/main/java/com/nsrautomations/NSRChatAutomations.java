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

    // Regex pattern to capture math expressions from chat
    private static final Pattern MATH_PATTERN = Pattern.compile("(?i)(?:solve|calculate|what is)?\\s*([0-9+\\-*/xX^().\\s]+)");
    
    // Regex pattern for Fast-Type games (e.g., "Type -> dungeon")
    private static final Pattern TYPE_GAME_PATTERN = Pattern.compile("(?i)Type\\s*->\\s*([a-zA-Z0-9_]+)");

    // Scheduled executor to handle 2-3 seconds natural delay before answering
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final Random RANDOM = new Random();

    @Override
    public void onInitializeClient() {
        // Register event listener for incoming chat messages safely
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, instant) -> {
            if (message == null) return;
            String messageText = message.getString();
            
            if (messageText == null || messageText.isEmpty()) return;

            // Process math games using the built-in calculator
            processMathGame(messageText);
            
            // Process type/chat games automatically
            processTypeGame(messageText);
        });
    }

    private void processMathGame(String text) {
        try {
            Matcher matcher = MATH_PATTERN.matcher(text);
            if (matcher.find()) {
                String expression = matcher.group(1).trim();
                
                // Ensure it contains numbers and math operators to avoid false triggers
                if (expression.matches(".*[0-9]+.*") && expression.matches(".*[+\\-*/xX^].*")) {
                    double result = eval(expression);
                    int finalResult = (int) Math.round(result);
                    
                    delayedSendChatMessage(String.valueOf(finalResult));
                }
            }
        } catch (Exception e) {
            // Suppress unexpected parsing exceptions gracefully
        }
    }

    // Built-in lightweight calculator for evaluating expressions safely
    private double eval(String str) {
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
                if (pos < str.length()) throw new RuntimeException("Unexpected: " + (char)ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if      (eat('+')) x += parseTerm(); // addition
                    else if (eat('-')) x -= parseTerm(); // subtraction
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if      (eat('*')) x *= parseFactor(); // multiplication
                    else if (eat('/')) x /= parseFactor(); // division
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor(); // unary plus
                if (eat('-')) return -parseFactor(); // unary minus

                double x;
                int startPos = this.pos;
                if (eat('(')) { // parentheses
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("Unexpected: " + (char)ch);
                }

                if (eat('^')) x = Math.pow(x, parseFactor()); // exponentiation

                return x;
            }
        }.parse();
    }

    private void processTypeGame(String text) {
        try {
            Matcher matcher = TYPE_GAME_PATTERN.matcher(text);
            if (matcher.find()) {
                String wordToType = matcher.group(1);
                if (wordToType != null && !wordToType.isEmpty()) {
                    delayedSendChatMessage(wordToType);
                }
            }
        } catch (Exception e) {
            // Suppress unexpected parsing exceptions gracefully
        }
    }

    private void delayedSendChatMessage(String message) {
        // Generate a random delay between 2000ms (2 seconds) and 3000ms (3 seconds) to look natural
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
                // Handle thread execution safety
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
