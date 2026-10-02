package forge.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tinylog.Logger;

/**
 * HTTP client that communicates with an OpenAI-compatible LLM API
 * (e.g. LM Studio, Ollama, vLLM) for game decisions.
 *
 * Each decision is a blocking POST to /v1/chat/completions.
 * The model answers with a JSON object {"reason": "...", "action": "..."}:
 * a short justification followed by the decision itself. Only the action is
 * used to drive the game; the reason is logged and kept in the game log so
 * later decisions can stay consistent with earlier plans.
 */
public class ExternalAgentClient {

    /** Parsed model answer. {@code action} is never null; {@code reason} may be empty. */
    public record AgentResponse(String reason, String action) {}

    private final String baseUrl;
    private final String modelName;
    private final HttpClient client;
    private final StringBuilder gameLog;

    private static final String SYSTEM_PROMPT = """
        You are an expert Magic: The Gathering player controlling one player in a
        game run by the Forge rules engine. The engine enforces all game rules; your
        job is only to make the decisions it asks you for.

        HOW EACH REQUEST IS STRUCTURED
        1. GAME STATE: the game from your point of view. "you" is you; "opp" (or
           "opp1", "opp2", ... in multiplayer games) are your opponents. Opponents'
           hands and libraries are hidden; only their sizes are shown.
        2. DECISION: what the engine is asking you to decide right now and, where
           known, which card or effect caused the question.
        3. OPTIONS: the legal choices, each with an index or label. Only these
           choices are legal.
        4. ANSWER FORMAT: exactly what the "action" field must contain.
        The GAME LOG at the end of this message lists earlier events and decisions
        in this game, including your own earlier reasoning. Use it to follow
        through on your plans.

        RESPONSE FORMAT - follow it exactly:
        Reply with ONE JSON object and nothing else (no markdown code fences, no
        text before or after it):
        {"reason": "<2-3 sentences>", "action": "<your answer>"}

        - "reason" comes FIRST. In 2-3 short sentences, state the key facts that
          matter (threats, life totals, available mana, what the opponent could
          do) and WHY your choice is better than the alternatives.
        - "action" is always a JSON string. Its format depends on the decision type:
            ACTION (choose exactly one)    -> a single index, e.g. "3"
            SUBSET (choose any number)     -> comma-separated indices, e.g. "0,2,3",
                                              or "NONE" to choose nothing
            YES/NO                         -> "YES" or "NO"
            ORDERING                       -> every index once, in order, e.g. "2,0,1"
            ATTACK ASSIGNMENT              -> creature-defender pairs, e.g. "C0-D0,C2-D1",
                                              or "NONE" to not attack
            BLOCK ASSIGNMENT               -> blocker-attacker pairs, e.g. "B0-A0,B1-A0"
                                              (several blockers may block one attacker),
                                              or "NONE" to not block
            DISTRIBUTION                   -> one integer per target, in target order,
                                              e.g. "2,1"
        - Only use indices and labels that are listed in OPTIONS. An invalid action
          is replaced by a default choice, which is usually bad for you.

        Example:
        {"reason": "Opp is at 4 life with no untapped creatures, and my two attackers deal 5 damage together. Attacking with both is lethal, so there is no reason to hold anything back.", "action": "C0-D0,C1-D0"}

        PRIMARY OBJECTIVE:
        Maximize your probability of winning the game from the current position.

        GENERAL PRINCIPLES:
        - Use mana efficiently, but preserve flexibility when strategically valuable.
        - Sequence plays to maximize tempo, card advantage, and combat effectiveness.
        - Consider future turns, hidden information, and likely opposing interaction.
        - Avoid unnecessary overextension into sweepers or combat blowouts.
        - Identify whether you are advantaged in the long game or need to race.
        - Use removal and interaction on the most strategically important threats.
        - Prioritize lethal attacks and forced winning lines when available.
        - Play a land on each of your turns.
        - Treat these principles as guidelines, not absolute rules.
        """;

    /** Opt-in: ask the server to enforce the JSON shape via response_format/json_schema. */
    private static final String RESPONSE_FORMAT = """
        ,"response_format":{"type":"json_schema","json_schema":{"name":"decision","strict":true,\
        "schema":{"type":"object","properties":{"reason":{"type":"string"},"action":{"type":"string"}},\
        "required":["reason","action"],"additionalProperties":false}}}""";

    public ExternalAgentClient(String baseUrl, String modelName) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gameLog = new StringBuilder();
    }

    // ---------------------------------------------------------------
    // Prompt templating
    // ---------------------------------------------------------------

    /**
     * Assemble a user message from the standard sections. {@code decisionType}
     * names one of the types listed in the system prompt so the model can map
     * the request to the matching action format.
     */
    public static String buildPrompt(String gameState, String decisionType, String decision,
                                     String optionsHeader, List<String> options, String answerFormat) {
        return buildPrompt(gameState, decisionType, decision, optionsHeader, options, true, answerFormat);
    }

    /**
     * Like {@link #buildPrompt(String, String, String, String, List, String)}, but
     * prints options verbatim. Use when options carry their own labels (C0, D1, ...).
     */
    public static String buildLabeledPrompt(String gameState, String decisionType, String decision,
                                            String optionsHeader, List<String> options, String answerFormat) {
        return buildPrompt(gameState, decisionType, decision, optionsHeader, options, false, answerFormat);
    }

    private static String buildPrompt(String gameState, String decisionType, String decision,
                                      String optionsHeader, List<String> options, boolean numberOptions,
                                      String answerFormat) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== GAME STATE ===\n").append(gameState.strip()).append("\n\n");
        prompt.append("=== DECISION: ").append(decisionType).append(" ===\n")
              .append(decision.strip()).append("\n\n");
        if (options != null) {
            prompt.append("=== OPTIONS");
            if (optionsHeader != null && !optionsHeader.isEmpty()) {
                prompt.append(" (").append(optionsHeader).append(")");
            }
            prompt.append(" ===\n");
            for (int i = 0; i < options.size(); i++) {
                if (numberOptions) {
                    prompt.append(i).append(": ");
                }
                prompt.append(options.get(i)).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("=== ANSWER FORMAT ===\n").append(answerFormat.strip()).append("\n")
              .append("Reply with only the JSON object {\"reason\": \"...\", \"action\": \"...\"}.");
        return prompt.toString();
    }

    private static String describe(String context, String fallback) {
        return context == null || context.isBlank() ? fallback : context;
    }

    // ---------------------------------------------------------------
    // Public decision methods
    // ---------------------------------------------------------------

    /**
     * Send a fully built prompt and return the parsed response. Callers are
     * responsible for parsing {@link AgentResponse#action()}.
     */
    public AgentResponse chooseRaw(String prompt) {
        AgentResponse response = ask(prompt);
        logDecision("Raw", null, response.action(), response);
        return response;
    }

    /**
     * Choose a single action from a numbered list.
     * Returns the chosen index, or 0 on failure (PASS / first option).
     */
    public int chooseAction(String gameState, List<String> actions) {
        return chooseAction(gameState, null, actions);
    }

    public int chooseAction(String gameState, String context, List<String> actions) {
        String prompt = buildPrompt(gameState, "ACTION (choose exactly one)",
                describe(context, "You have priority. Choose what to do next."),
                "choose exactly one", actions,
                "\"action\": a single index from 0 to " + (actions.size() - 1) + ", e.g. \"0\".");

        AgentResponse response = ask(prompt);
        int choice = parseFirstInt(response.action(), 0, actions.size() - 1, 0);

        logDecision("Action", context, choice + " (" + actions.get(choice) + ")", response);
        return choice;
    }

    /**
     * Choose a subset of items (e.g. which cards to discard).
     * {@code instruction} states the constraint, e.g. "Choose 1 to 2 cards."
     * Returns list of chosen indices, possibly empty.
     */
    public List<Integer> chooseSubset(String gameState, List<String> options, String instruction) {
        return chooseSubset(gameState, null, options, instruction);
    }

    public List<Integer> chooseSubset(String gameState, String context, List<String> options, String instruction) {
        String decision = context == null || context.isBlank() ? instruction : context + "\n" + instruction;
        String prompt = buildPrompt(gameState, "SUBSET (choose any number)", decision,
                "choose any combination", options,
                "\"action\": comma-separated indices from 0 to " + (options.size() - 1)
                        + ", e.g. \"0,2\", or \"NONE\" to choose nothing.");

        AgentResponse response = ask(prompt);
        List<Integer> result = parseIntList(response.action(), 0, options.size() - 1);

        logDecision("Subset", instruction, result.toString(), response);
        return result;
    }

    /**
     * Binary yes/no decision.
     */
    public boolean chooseYesNo(String gameState, String question) {
        return chooseYesNo(gameState, null, question);
    }

    public boolean chooseYesNo(String gameState, String context, String question) {
        String decision = context == null || context.isBlank() ? question : context + "\n" + question;
        String prompt = buildPrompt(gameState, "YES/NO", decision, null, null,
                "\"action\": \"YES\" or \"NO\".");

        AgentResponse response = ask(prompt);
        boolean result = parseYesNo(response.action());

        Logger.info("chooseYesNo action: '{}' -> parsed as: {}", response.action(), result);

        logDecision("YesNo", question, result ? "YES" : "NO", response);
        return result;
    }

    /**
     * Choose an ordering of items (e.g. damage assignment order).
     * Returns indices in the chosen order.
     */
    public List<Integer> chooseOrdering(String gameState, List<String> items, String instruction) {
        return chooseOrdering(gameState, null, items, instruction);
    }

    public List<Integer> chooseOrdering(String gameState, String context, List<String> items, String instruction) {
        String decision = context == null || context.isBlank() ? instruction : context + "\n" + instruction;
        String prompt = buildPrompt(gameState, "ORDERING", decision,
                "items to order", items,
                "\"action\": every index from 0 to " + (items.size() - 1)
                        + " exactly once, comma-separated, in your chosen order (first = first).");

        AgentResponse response = ask(prompt);
        List<Integer> result = parseIntList(response.action(), 0, items.size() - 1);

        logDecision("Order", instruction, result.toString(), response);
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

    private void logDecision(String kind, String context, String outcome, AgentResponse response) {
        gameLog.append("[").append(kind).append("] ");
        if (context != null && !context.isBlank()) {
            gameLog.append(firstLine(context)).append(" -> ");
        }
        gameLog.append(outcome);
        if (!response.reason().isEmpty()) {
            gameLog.append(" | reason: ").append(response.reason().replace('\n', ' '));
        }
        gameLog.append("\n");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }

    // ---------------------------------------------------------------
    // LLM communication
    // ---------------------------------------------------------------

    private AgentResponse ask(String userMessage) {
        String content = callLLM(userMessage);
        AgentResponse response = parseResponse(content);
        if (!response.reason().isEmpty()) {
            Logger.info("LLM reason: {}", response.reason());
        }
        return response;
    }

    private String callLLM(String userMessage) {
        // Manual mode: route to command line instead of LLM
        if (Boolean.getBoolean("forge.external.agent.manual")) {
            Logger.info("human-player");
            Logger.info("=== LLM REQUEST ===\n{}", userMessage);
            System.out.print("YOUR CHOICE> ");
            String input = new java.util.Scanner(System.in).nextLine().trim();
            Logger.info("=== LLM RESPONSE ===\n{}\n====================", input);
            return input;
        }

        String systemContent = SYSTEM_PROMPT + "\n\nGAME LOG SO FAR:\n"
                + (gameLog.length() == 0 ? "(no events yet)\n" : gameLog.toString());

        String json = "{" +
                "\"model\":\"" + jsonEscape(modelName) + "\"," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"" + jsonEscape(systemContent) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + jsonEscape(userMessage) + "\"}" +
                "]," +
                "\"temperature\":0.3," +
                "\"max_tokens\":16000" +
                (Boolean.getBoolean("forge.external.agent.jsonSchema") ? RESPONSE_FORMAT : "") +
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
            if (status != 200) {
                java.io.InputStream err = conn.getErrorStream();
                String errorBody = err == null ? "" : new String(err.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Logger.warn("LLM API returned status {}: {}", status, errorBody);
                return "0";
            }

            String responseBody = new String(
                    conn.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            String content = extractContent(responseBody);
            Logger.info("=== LLM RESPONSE ===\n{}\n====================", content);
            return content;

        } catch (Exception e) {
            Logger.error(e, "Failed to call LLM API at {}", baseUrl);
            return "0";
        }
    }

    /**
     * Extract the assistant's message content (choices[0].message.content)
     * from an OpenAI-format JSON response.
     */
    @SuppressWarnings("unchecked")
    static String extractContent(String responseJson) {
        try {
            Object root = new MiniJson(responseJson).parseValue();
            if (root instanceof Map<?, ?> rootMap
                    && rootMap.get("choices") instanceof List<?> choices
                    && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> choice
                    && choice.get("message") instanceof Map<?, ?> message
                    && message.get("content") instanceof String content) {
                return content;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        Logger.warn("Could not parse LLM response: {}", responseJson);
        return "0";
    }

    // ---------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------

    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Parse the model output into reason + action. Tolerates reasoning-model
     * {@code <think>} blocks, markdown fences, and prose around the JSON object.
     * If no JSON object with an "action" field is found, the whole output is
     * treated as the action (the legacy bare-answer format).
     */
    static AgentResponse parseResponse(String content) {
        if (content == null) {
            return new AgentResponse("", "");
        }
        String text = THINK_BLOCK.matcher(content).replaceAll("").strip();

        // Mana symbols like {G} may appear in prose, so try every '{' until one
        // starts a JSON object that carries an action.
        for (int start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
            try {
                MiniJson parser = new MiniJson(text);
                parser.pos = start;
                if (parser.parseValue() instanceof Map<?, ?> obj && obj.containsKey("action")) {
                    String reason = obj.get("reason") instanceof String r ? r.strip() : "";
                    return new AgentResponse(reason, valueToAction(obj.get("action")));
                }
            } catch (RuntimeException ignored) {
                // not a JSON object at this position
            }
        }
        return new AgentResponse("", text);
    }

    private static String valueToAction(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean b) return b ? "YES" : "NO";
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(valueToAction(o));
            return parts.isEmpty() ? "NONE" : String.join(",", parts);
        }
        return value.toString().strip();
    }

    private static boolean parseYesNo(String action) {
        String a = action.strip().toUpperCase();
        if (a.startsWith("YES") || a.equals("Y") || a.equals("TRUE")) return true;
        if (a.startsWith("NO") || a.equals("N") || a.equals("FALSE")) return false;
        return a.contains("YES") && !a.contains("NO");
    }

    private static int parseFirstInt(String response, int min, int max, int fallback) {
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
        for (String part : response.split("[^0-9]+")) {
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
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Minimal JSON reader (objects, arrays, strings, numbers, literals) so the
     * client needs no JSON library. Numbers are returned as their source text.
     */
    static final class MiniJson {
        private final String s;
        int pos;

        MiniJson(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (peek() != ':') throw new IllegalStateException("expected ':' at " + pos);
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') return map;
                if (c != ',') throw new IllegalStateException("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') return list;
                if (c != ',') throw new IllegalStateException("expected ',' or ']' at " + pos);
            }
        }

        private String parseString() {
            if (peek() != '"') throw new IllegalStateException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e); // \" \\ \/
                }
            }
        }

        private String parseNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalStateException("unexpected character at " + pos);
            return s.substring(start, pos);
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) throw new IllegalStateException("expected " + literal + " at " + pos);
            pos += literal.length();
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            if (pos >= s.length()) throw new IllegalStateException("unexpected end of input");
            return s.charAt(pos);
        }
    }
}
