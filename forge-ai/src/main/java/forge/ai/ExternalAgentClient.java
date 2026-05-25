package forge.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tinylog.Logger;

/**
 * HTTP client that communicates with an OpenAI-compatible LLM API
 * (e.g. LM Studio, Ollama, vLLM) for game decisions.
 *
 * Each decision is a blocking POST to /v1/chat/completions.
 * The model returns short structured responses (action indices).
 */
public class ExternalAgentClient {

    private final String baseUrl;
    private final String modelName;
    private final HttpClient client;
    private final StringBuilder gameLog;

    private static final String SYSTEM_PROMPT = """
            You are an expert Magic: The Gathering player. You are playing a game
            through the Forge engine. At each decision point you receive:

            1. The current game state (life totals, cards in zones, board state)
            2. A numbered list of legal decisions to take

            RESPONSE FORMAT RULES — follow these exactly:
            - For ACTION decisions: respond with ONLY the action number. Nothing else.
            - For SUBSET decisions (e.g. choose attackers): respond with comma-separated
              indices, or NONE if you choose nothing. Example: 0,2,3
            - For YES/NO decisions: respond with YES or NO.
            - For ORDERING decisions: respond with comma-separated indices in your
              preferred order.

            Do NOT explain your reasoning. Do NOT add any other text.

            CRITICAL RULE — NEVER PASS IF YOU CAN DO SOMETHING USEFUL:
            - Action 0 is PASS. Only choose 0 if every other action would actively
              hurt you. If ANY action is even slightly beneficial, DO IT.
            - Playing a creature is almost always better than passing.
            - If you have mana available and a spell you can cast, CAST IT.
            - If you are in MAIN1 or MAIN2 and can play a land, PLAY THE LAND.
            - Passing with unspent mana and castable spells is ALWAYS wrong.

            STRATEGY GUIDELINES:
            - LANDS: Always play a land first in MAIN1 if you have one in hand.
              Non-basic lands can tap for any color your deck needs.
            - CREATURES: Always play creatures when you have the mana. More creatures
              = more attackers = more pressure. An empty board loses games.
            - COMBAT: Attack with creatures when opponent has no untapped creatures
              that can block profitably (calculate their power and toughness versus
              your power and toughness). If opponent is at low life, attack with
              everything to try for lethal.
            - REMOVAL: Hold removal for the opponent's biggest threat, but don't hold
              it forever. If they have a creature and you have removal, use it.
            - MANA EFFICIENCY: Spend all your mana every turn. If you have 4 mana,
              play a 4-cost spell, not a 2-cost spell. Use leftover mana on
              activated abilities.
            - CARD ADVANTAGE: Drawing cards and creating tokens are high-value plays.
            - LATE GAME: If the game has gone past turn 20, prioritize winning NOW.
              Play every creature, attack every turn, use all resources aggressively.
            - COMBAT TRICKS: Cast instants during combat to save your creatures or
              kill the opponent's. A pump spell on an unblocked attacker can be lethal.
        """;

    public ExternalAgentClient(String baseUrl, String modelName) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gameLog = new StringBuilder();
    }

    // ---------------------------------------------------------------
    // Public decision methods
    // ---------------------------------------------------------------

    /**
     * Choose a single action from a numbered list.
     * Returns the chosen index, or 0 (PASS) on failure.
     */
    public int chooseAction(String gameState, List<String> actions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n").append(gameState).append("\n\n");
        prompt.append("LEGAL ACTIONS:\n");
        for (int i = 0; i < actions.size(); i++) {
            prompt.append(i).append(": ").append(actions.get(i)).append("\n");
        }
        prompt.append("\nRespond with ONLY the action number.");

        String response = callLLM(prompt.toString());
        int choice = parseFirstInt(response, 0, actions.size() - 1, 0);

        gameLog.append("[Action] Chose ").append(choice)
               .append(" (").append(actions.get(choice)).append(")\n");
        return choice;
    }

    /**
     * Choose a subset of items (e.g. which creatures to attack with).
     * Returns list of chosen indices, possibly empty.
     */
    public List<Integer> chooseSubset(String gameState, List<String> options, String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n").append(gameState).append("\n\n");
        prompt.append("CONTEXT: ").append(context).append("\n\n");
        prompt.append("OPTIONS (choose any combination, or NONE):\n");
        for (int i = 0; i < options.size(); i++) {
            prompt.append(i).append(": ").append(options.get(i)).append("\n");
        }
        prompt.append("\nRespond with comma-separated indices, or NONE.");

        String response = callLLM(prompt.toString());
        List<Integer> result = parseIntList(response, 0, options.size() - 1);

        gameLog.append("[Subset] ").append(context).append(" -> ").append(result).append("\n");
        return result;
    }

    /**
     * Binary yes/no decision.
     */
    public boolean chooseYesNo(String gameState, String question) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n").append(gameState).append("\n\n");
        prompt.append("QUESTION: ").append(question).append("\n");
        prompt.append("\nRespond with YES or NO.");

        String response = callLLM(prompt.toString());
        boolean result = response.toUpperCase().contains("YES");

        Logger.info("chooseYesNo raw response: '{}' -> parsed as: {}",
                response, result);

        gameLog.append("[YesNo] ").append(question).append(" -> ").append(result).append("\n");
        return result;
    }

    /**
     * Choose an ordering of items (e.g. scry ordering).
     * Returns indices in the chosen order.
     */
    public List<Integer> chooseOrdering(String gameState, List<String> items, String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n").append(gameState).append("\n\n");
        prompt.append("CONTEXT: ").append(context).append("\n\n");
        prompt.append("ITEMS TO ORDER (first = top/best):\n");
        for (int i = 0; i < items.size(); i++) {
            prompt.append(i).append(": ").append(items.get(i)).append("\n");
        }
        prompt.append("\nRespond with comma-separated indices in your preferred order.");

        String response = callLLM(prompt.toString());
        List<Integer> result = parseIntList(response, 0, items.size() - 1);

        gameLog.append("[Order] ").append(context).append(" -> ").append(result).append("\n");
        return result;
    }

    /**
     * Log a game event (not a decision — just for context).
     */
    public void logEvent(String event) {
        gameLog.append("[Event] ").append(event).append("\n");
    }

    /**
     * Reset game log for a new game.
     */
    public void resetGameLog() {
        gameLog.setLength(0);
    }

    // ---------------------------------------------------------------
    // LLM communication
    // ---------------------------------------------------------------

    private String callLLM(String userMessage) {
        String systemContent = SYSTEM_PROMPT + "\n\nGAME LOG SO FAR:\n" + gameLog.toString();

        String json = "{" +
                "\"model\":\"" + jsonEscape(modelName) + "\"," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"" + jsonEscape(systemContent) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + jsonEscape(userMessage) + "\"}" +
                "]," +
                "\"temperature\":0.3," +
                "\"max_tokens\":16000" +
                "}";

        Logger.info(jsonEscape(modelName));
        Logger.info("=== LLM REQUEST ===\n{}", userMessage);

        try {
            java.net.URL url = new java.net.URL(baseUrl + "/v1/chat/completions");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            int status = conn.getResponseCode();
            String responseBody = new String(
                    conn.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            if (status != 200) {
                Logger.warn("LLM API returned status {}: {}", status, responseBody);
                return "0";
            }

            String content = extractContent(responseBody);
            Logger.info("=== LLM RESPONSE ===\n{}\n====================", content);
            return content;

        } catch (Exception e) {
            Logger.error(e, "Failed to call LLM API at {}", baseUrl);
            return "0";
        }
    }

    /**
     * Extract the assistant's message content from OpenAI-format JSON response.
     * Avoids pulling in a JSON library — the response structure is simple and fixed.
     */
    private static String extractContent(String responseJson) {
        // Look for "content":"..." in the response
        // The format is: {"choices":[{"message":{"content":"THE_ANSWER",...},...}],...}
        Pattern p = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(responseJson);
        // Skip the first match (system message echo in some APIs) and find the assistant content
        // Actually in the standard response, there's typically just one "content" in choices
        if (m.find()) {
            String content = m.group(1);
            // Check if this looks like the system prompt (too long) — skip to next
            if (content.length() > 200 && m.find()) {
                return m.group(1);
            }
            return content;
        }
        Logger.warn("Could not parse LLM response: {}", responseJson);
        return "0";
    }

    // ---------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------

    private static int parseFirstInt(String response, int min, int max, int fallback) {
        response = response.strip().trim();
        // Try to find the first integer in the response
        Matcher m = Pattern.compile("\\d+").matcher(response);
        if (m.find()) {
            try {
                int val = Integer.parseInt(m.group());
                if (val >= min && val <= max) {
                    return val;
                }
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return fallback;
    }

    private static List<Integer> parseIntList(String response, int min, int max) {
        List<Integer> result = new ArrayList<>();
        response = response.trim().toUpperCase();
        if (response.equals("NONE") || response.isEmpty()) {
            return result;
        }
        for (String part : response.split("[,\\s]+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                int val = Integer.parseInt(part);
                if (val >= min && val <= max && !result.contains(val)) {
                    result.add(val);
                }
            } catch (NumberFormatException e) {
                // skip non-numeric tokens
            }
        }
        return result;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
