package forge.ai;

import java.net.http.HttpClient;
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
    
        RESPONSE FORMAT RULES - follow these exactly:
        - For ACTION decisions: respond with ONLY the action number. Nothing else.
        - For SUBSET decisions (e.g. choose blockers): respond with comma-separated
          indices, or NONE if you choose nothing. Example: 0,2,3
        - For YES/NO decisions: respond with YES or NO.
        - For ORDERING decisions: respond with comma-separated indices in your
          preferred order.
        - For ATTACK ASSIGNMENT: respond with comma-separated pairs like CN-DN,CN-DN
          where CN is a valid creature index and DN is a valid defender index. Reply NONE
          for no attacks. Only use indices that are listed.
        - For BLOCK ASSIGNMENT: respond with comma-separated pairs like B0-A0,B1-A0
          where BN is the blocker index and AN is the attacker index. Multiple
          blockers can block the same attacker (e.g. B0-A0,B1-A0 = double block).
          Reply NONE to not block anything. Only use indices that are listed.
    
        Do NOT explain your reasoning. Do NOT add any other text.
    
        PRIMARY OBJECTIVE:
        Maximize probability of winning the game from the current position.
    
        GENERAL PRINCIPLES:
        - Use mana efficiently, but preserve flexibility when strategically valuable.
        - Sequence plays to maximize tempo, card advantage, and combat effectiveness.
        - Consider future turns, hidden information, and likely opposing interaction.
        - Avoid unnecessary overextension into sweepers or combat blowouts.
        - Identify whether you are advantaged in the long game or need to race.
        - Use removal and interaction on the most strategically important threats.
        - Prioritize lethal attacks and forced winning lines when available.
        - Play a land on each of your turns.
        - Treat the listed heuristics as guidelines, not absolute rules.
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

    public String chooseRaw(String prompt) {
        String response = callLLM(prompt);
        gameLog.append("[Raw] ").append(response.trim()).append("\n");
        return response.trim();
    }

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
        // Manual mode: route to command line instead of LLM
        if (Boolean.getBoolean("forge.external.agent.manual")) {
            Logger.info("human-player");
            Logger.info("=== LLM REQUEST ===\n{}", userMessage);
            System.out.println("\n" + userMessage);
            System.out.print("YOUR CHOICE> ");
            String input = new java.util.Scanner(System.in).nextLine().trim();
            Logger.info("=== LLM RESPONSE ===\n{}\n====================", input);
            gameLog.append("[Manual] ").append(input).append("\n");
            return input;
        }

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
