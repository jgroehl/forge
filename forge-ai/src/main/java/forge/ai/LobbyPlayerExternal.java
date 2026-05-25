package forge.ai;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * LobbyPlayer implementation that creates a PlayerControllerExternal
 * for the external LLM agent, paired with a fallback PlayerControllerAi
 * for mechanical decisions.
 *
 * Usage in headless sim launcher:
 *   LobbyPlayerExternal llmPlayer = new LobbyPlayerExternal(
 *       "LLM-Agent", "http://localhost:1234", "qwen3.5-9b");
 */
public class LobbyPlayerExternal extends LobbyPlayer implements IGameEntitiesFactory {

    private final String agentUrl;
    private final String modelName;

    public LobbyPlayerExternal(String name, String agentUrl, String modelName) {
        super(name);
        this.agentUrl = agentUrl;
        this.modelName = modelName;
    }

    public LobbyPlayerExternal(String name, String agentUrl) {
        this(name, agentUrl, "qwen/qwen3-vl-4b");
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        // Fall back to normal AI if mind-slaved — not worth routing externally
        return new PlayerControllerAi(slave.getGame(), slave, this);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new PlayerControllerExternal(game, p, this, agentUrl, modelName));
        return p;
    }

    @Override
    public void hear(LobbyPlayer player, String message) {
        // External agent is deaf to chat messages
    }
}
