package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.Player;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decision log of one LLM seat in one game, so seats can be exported and annotated in isolation.
 * <p>
 * One file per seat and game in {@code <Forge user dir>/llm_games} (override with
 * {@code -Dforge.external.agent.seatLogDir}), named {@code <date-time>_g<game id>_<seat>.log}; all seats of a game share
 * the prefix. The header names the seat, model and every player at the table with its controller (human, Forge AI or
 * LLM); requests and responses use the same format as Forge's main log (readable by ForgeAI's parse_game_log.py); the
 * seat's result is appended when the game ends ({@code # outcome: WON|LOST|DRAW}).
 */
public final class LlmSeatLog {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Start time per game, so every seat of a game gets the same file prefix. */
    private static final Map<Integer, String> GAME_STARTS = new ConcurrentHashMap<>();

    private final Game game;
    private final Player player;
    private final PrintWriter out;
    private boolean tableWritten;
    private boolean closed;

    private LlmSeatLog(Game game, Player player, PrintWriter out) {
        this.game = game;
        this.player = player;
        this.out = out;
    }

    /** Opens the log for this seat; returns null (logging only to the main log) if the file can't be created. */
    public static LlmSeatLog open(Game game, Player player, String model, String url, String template) {
        try {
            Path dir = logDir();
            Files.createDirectories(dir);
            String start = GAME_STARTS.computeIfAbsent(game.getId(), id -> LocalDateTime.now().format(FILE_TIME));
            String seat = player.getName().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
            Path file = dir.resolve(start + "_g" + game.getId() + "_" + seat + ".log");
            PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true);
            out.println("# Forge LLM seat log");
            out.println("# seat: " + player.getName());
            out.println("# model: " + model + " @ " + url + ", system prompt template: " + template);
            out.println("# game: " + game.getId() + ", started " + start);
            LlmSeatLog log = new LlmSeatLog(game, player, out);
            game.subscribeToEvents(log);
            return log;
        } catch (IOException | RuntimeException e) {
            Logger.warn(e, "Could not open the LLM seat log for {}", player.getName());
            return null;
        }
    }

    private static Path logDir() {
        String override = System.getProperty("forge.external.agent.seatLogDir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String appData = System.getenv("APPDATA");
        return appData != null
                ? Paths.get(appData, "Forge", "llm_games")
                : Paths.get(System.getProperty("user.home"), ".forge", "llm_games");
    }

    /** One request/response pair, in the main log's format. */
    public synchronized void exchange(String request, String response) {
        if (closed) {
            return;
        }
        writeTable();
        String now = LocalTime.now().format(LINE_TIME);
        out.println(now + " [INFO ] ExternalAgentClient: === LLM REQUEST ===");
        out.println(request);
        out.println(now + " [INFO ] ExternalAgentClient: === LLM RESPONSE ===");
        out.println(response);
        out.println("====================");
    }

    /** Players and their controllers; written with the first decision, when the table is complete. */
    private void writeTable() {
        if (tableWritten) {
            return;
        }
        tableWritten = true;
        for (Player p : game.getPlayers()) {
            String kind = p.getController() instanceof PlayerControllerExternal ? "LLM"
                    : p.getController() != null && p.getController().isAI() ? "Forge AI" : "human";
            List<String> commanders = new ArrayList<>();
            for (Card c : p.getCommanders()) {
                commanders.add(c.getName());
            }
            out.println("# player: " + p.getName() + " | " + kind + (p == player ? " | this seat" : "")
                    + (commanders.isEmpty() ? "" : " | commander: " + String.join(", ", commanders)));
        }
    }

    @Subscribe
    public synchronized void onGameOutcome(GameEventGameOutcome event) {
        if (closed) {
            return;
        }
        String result = event.winningPlayerName() == null ? "DRAW"
                : event.winningPlayerName().equals(player.getName()) ? "WON" : "LOST";
        out.println("# outcome: " + result);
        for (String line : event.outcomeStrings()) {
            if (line.contains(player.getName())) {
                out.println("# outcome detail: " + line);
            }
        }
        out.println("# last turn: " + event.lastTurnNumber());
        for (String line : event.outcomeStrings()) {
            out.println("Game Outcome: " + line);
        }
        closed = true;
        out.close();
    }
}
