package forge.ai;

import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;

import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollectionView;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * PlayerController that routes strategic decisions to an external LLM agent
 * (via ExternalAgentClient) and delegates mechanical/trivial decisions to the
 * built-in PlayerControllerAi as a super.
 */
public class PlayerControllerExternal extends PlayerControllerAi {

    private final AiController brains;
    private final ExternalAgentClient agent;

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp,
                                    String agentUrl, String modelName) {
        super(game, p, lp);
        this.brains = getAi();
        this.agent = new ExternalAgentClient(agentUrl, modelName);
    }

    @Override
    public boolean isAI() {
        return true;
    }

    // ===================================================================
    // Game state serialization
    // ===================================================================

    private String serializeGameState() {
        return buildManualGameState();
    }

    private String buildManualGameState() {
        StringBuilder sb = new StringBuilder();

        PhaseType ph = getGame().getPhaseHandler().getPhase();
        sb.append("turn=").append(getGame().getPhaseHandler().getTurn());
        sb.append(" phase=").append(ph != null ? ph.toString() : "PREGAME");
        sb.append("\n");

        int oppCount = 0;
        for (Player p : getGame().getPlayers()) {
            String tag;
            if (p == player) {
                tag = "you";
            } else {
                oppCount++;
                tag = getGame().getPlayers().size() > 2 ? "opp" + oppCount : "opp";
            }
            boolean isMe = p == player;

            sb.append(tag).append(": ").append(p.getLife()).append(" life");

            if (isMe) {
                String manaStr = buildAvailableMana(p);
                if (!manaStr.isEmpty()) {
                    sb.append(" | mana=").append(manaStr);
                }
            }
            sb.append("\n");

            sb.append(tag).append("_hand: ");
            if (isMe) {
                List<String> handCards = new ArrayList<>();
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    if (c.isLand()) {
                        handCards.add(c.getName());
                    } else {
                        handCards.add(c.getName() + " " + c.getManaCost().toString());
                    }
                }
                sb.append(String.join(", ", handCards));
            } else {
                sb.append(p.getCardsIn(ZoneType.Hand).size()).append(" cards");
            }
            sb.append("\n");

            sb.append(tag).append("_board: ");
            List<String> boardCards = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                boardCards.add(cardToStringCompact(c));
            }
            sb.append(boardCards.isEmpty() ? "empty" : String.join(", ", boardCards));
            sb.append("\n");

            CardCollectionView grave = p.getCardsIn(ZoneType.Graveyard);
            if (!grave.isEmpty()) {
                sb.append(tag).append("_grave: ");
                List<String> graveCards = new ArrayList<>();
                for (Card c : grave) {
                    graveCards.add(c.getName());
                }
                sb.append(String.join(", ", graveCards));
                sb.append("\n");
            }
        }

        Combat combat = getGame().getCombat();
        if (combat != null && !combat.getAttackers().isEmpty()) {
            sb.append("COMBAT:\n");
            for (Card attacker : combat.getAttackers()) {
                sb.append("  attacking: ").append(cardToStringCompact(attacker));
                sb.append(" -> ").append(combat.getDefenderByAttacker(attacker));

                CardCollection blockers = combat.getBlockers(attacker);
                if (blockers != null && !blockers.isEmpty()) {
                    sb.append(" blocked by [");
                    List<String> blockerNames = new ArrayList<>();
                    for (Card blocker : blockers) {
                        blockerNames.add(cardToStringCompact(blocker));
                    }
                    sb.append(String.join(", ", blockerNames));
                    sb.append("]");
                } else {
                    sb.append(" UNBLOCKED");
                }
                sb.append("\n");
            }
        }

        if (!getGame().getStack().isEmpty()) {
            sb.append("stack: ");
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                sb.append(si.getSpellAbility().getHostCard().getName()).append(", ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildAvailableMana(Player p) {
        int W = 0, U = 0, B = 0, R = 0, G = 0, C = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                for (SpellAbility sa : c.getManaAbilities()) {
                    if (sa.getManaPart() == null) continue;
                    String produced = sa.getManaPart().getOrigProduced();
                    if (produced.contains("W")) W++;
                    else if (produced.contains("R")) R++;
                    else if (produced.contains("G")) G++;
                    else if (produced.contains("U")) U++;
                    else if (produced.contains("B")) B++;
                    else C++;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < W; i++) sb.append("W");
        for (int i = 0; i < U; i++) sb.append("U");
        for (int i = 0; i < B; i++) sb.append("B");
        for (int i = 0; i < R; i++) sb.append("R");
        for (int i = 0; i < G; i++) sb.append("G");
        for (int i = 0; i < C; i++) sb.append("C");
        int total = W + U + B + R + G + C;
        if (total == 0) return "";
        return sb.toString() + " (" + total + " total)";
    }

    private static String cardToStringCompact(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        if (c.isCreature()) {
            sb.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness()).append(" ");
        }
        if (c.isTapped()) sb.append("(tapped)");
        if (c.isSick()) sb.append("(sick)");
        Map<CounterType, Integer> counters = c.getCounters();
        if (!counters.isEmpty()) {
            sb.append("[");
            counters.forEach((type, count) ->
                    sb.append(type).append("=").append(count).append(","));
            sb.setLength(sb.length() - 1);
            sb.append("] ");
        }
        return sb.toString();
    }

    private static String cardToString(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        if (c.isCreature()) {
            sb.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness());
        }
        if (c.isTapped()) {
            sb.append(" (tapped)");
        }
        if (c.isSick()) {
            sb.append(" (summoning sick)");
        }
        Map<CounterType, Integer> counters = c.getCounters();
        if (!counters.isEmpty()) {
            sb.append(" [");
            counters.forEach((type, count) -> sb.append(type).append("=").append(count).append(","));
            sb.setLength(sb.length() - 1);
            sb.append("] ");
        }
        sb.append(c.getOracleText().replace("\\n", " "));
        return sb.toString();
    }

    private static String saToString(SpellAbility sa) {
        Card c = sa.getHostCard();
        String prefix = "";
        String desc = "";
        if (sa.isAbility()) {
            prefix = "Activate ability ";
            desc = sa.getDescription().replace("\\n", " ");
        } else if (sa.isSpell()) {
            prefix = "Cast ";
            desc = c.getType().toString() + " " + c.getOracleText().replace("\\n", " ");
        }
        return prefix + c.getName() + ": " + desc;
    }

    // ===================================================================
    // STRATEGIC DECISIONS — routed to external LLM
    // ===================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        try {
            String gameState = serializeGameState();

            CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), player);
            List<SpellAbility> spellAbilities = ComputerUtilAbility.getSpellAbilities(cards, player);

            spellAbilities.removeIf(sa -> sa.isManaAbility() || sa.isLandAbility());

            CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(getGame(), player);

            List<String> actions = new ArrayList<>();
            actions.add("PASS (do nothing, pass priority)");

            List<Object> actionSources = new ArrayList<>();
            actionSources.add(null);

            if (lands != null && !lands.isEmpty()) {
                for (Card land : lands) {
                    actions.add("Play land: " + land.getName() + " " + (land.getOracleText().replace("\\n", " ")));
                    actionSources.add(land);
                }
            }

            for (SpellAbility sa : spellAbilities) {
                sa.setActivatingPlayer(player);
                if (!brains.canPlaySa(sa).willingToPlay()) continue;
                if (!ComputerUtilCost.canPayCost(sa, player, false)) continue;

                actions.add(saToString(sa));
                actionSources.add(sa);
            }

            if (actions.size() <= 1) {
                return null;
            }

            int choice = agent.chooseAction(gameState, actions);

            if (choice == 0 || choice >= actionSources.size()) {
                return null;
            }

            Object chosen = actionSources.get(choice);

            if (chosen instanceof Card land) {
                List<SpellAbility> landAbilities = land.getAllPossibleAbilities(player, true);
                landAbilities.removeIf(sa -> !sa.isLandAbility());
                if (!landAbilities.isEmpty()) {
                    return landAbilities;
                }
                return null;
            } else if (chosen instanceof SpellAbility sa) {
                return singleSpellAbilityList(sa);
            }

            return null;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseSpellAbilityToPlay failed, falling back");
            return super.chooseSpellAbilityToPlay();
        }
    }

    private static List<SpellAbility> singleSpellAbilityList(SpellAbility sa) {
        if (sa == null) return null;
        List<SpellAbility> list = new ArrayList<>();
        list.add(sa);
        return list;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        try {
            List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
            if (defenders.isEmpty()) return;

            CardCollection possibleAttackers = new CardCollection();
            for (Card c : player.getCreaturesInPlay()) {
                if (c.isCreature() && !c.isSick() && !c.isTapped()) {
                    possibleAttackers.add(c);
                }
            }

            if (possibleAttackers.isEmpty()) return;

            String gameState = serializeGameState();

            StringBuilder context = new StringBuilder();
            context.append("CREATURES:\n");
            for (int i = 0; i < possibleAttackers.size(); i++) {
                context.append("  C").append(i).append(": ").append(cardToStringCompact(possibleAttackers.get(i))).append("\n");
            }
            context.append("DEFENDERS:\n");
            for (int i = 0; i < defenders.size(); i++) {
                GameEntity d = defenders.get(i);
                if (d instanceof Player p) {
                    context.append("  D").append(i).append(": ").append(p.getName()).append(" (").append(p.getLife()).append(" life)\n");
                } else {
                    context.append("  D").append(i).append(": ").append(d.toString()).append("\n");
                }
            }
            context.append("\nDeclare attackers.");
            context.append("\nRespond with ONLY NONE or the comma-separated attack assignment pairs." +
                    "Omit creatures you don't want to attack with. ");

            String response = agent.chooseRaw(gameState + "\n" + context.toString());

            if (response != null && !response.trim().equalsIgnoreCase("NONE")) {
                for (String pair : response.split(",")) {
                    pair = pair.trim().toUpperCase();
                    String[] parts = pair.split("-");
                    if (parts.length == 2) {
                        try {
                            int cIdx = Integer.parseInt(parts[0].replace("C", "").trim());
                            int dIdx = Integer.parseInt(parts[1].replace("D", "").trim());
                            if (cIdx >= 0 && cIdx < possibleAttackers.size()
                                    && dIdx >= 0 && dIdx < defenders.size()) {
                                combat.addAttacker(possibleAttackers.get(cIdx), defenders.get(dIdx));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.error(e, "External AI declareAttackers failed, falling back");
            super.declareAttackers(attacker, combat);
        }
    }

    // ---------------------------------------------------------------
    // DECLARE BLOCKERS (LLM-routed)
    // ---------------------------------------------------------------
    @Override
    public void declareBlockers(Player defender, Combat combat) {
        try {
            if (combat.getAttackers().isEmpty()) return;

            CardCollection possibleBlockers = new CardCollection();
            for (Card c : player.getCreaturesInPlay()) {
                if (!c.isTapped()) {
                    possibleBlockers.add(c);
                }
            }

            if (possibleBlockers.isEmpty()) return;

            String gameState = serializeGameState();

            for (Card attacker : combat.getAttackers()) {
                List<Card> legalBlockers = new ArrayList<>();
                for (Card blocker : possibleBlockers) {
                    if (CombatUtil.canBlock(attacker, blocker, combat)) {
                        legalBlockers.add(blocker);
                    }
                }

                if (legalBlockers.isEmpty()) continue;

                List<String> options = new ArrayList<>();
                for (Card b : legalBlockers) {
                    options.add(cardToStringCompact(b));
                }

                String context = "Attacker: " + cardToStringCompact(attacker)
                        + " (controlled by " + attacker.getController().getName() + ")"
                        + "\nChoose creatures to block with, or NONE to let it through."
                        + "\nConsider: will your blocker survive? Is it worth trading?";

                List<Integer> chosen = agent.chooseSubset(gameState, options, context);

                for (int idx : chosen) {
                    if (idx >= 0 && idx < legalBlockers.size()) {
                        Card blocker = legalBlockers.get(idx);
                        combat.addBlocker(attacker, blocker);
                        possibleBlockers.remove(blocker);
                    }
                }
            }
        } catch (Exception e) {
            Logger.error(e, "External AI declareBlockers failed, falling back");
            super.declareBlockers(defender, combat);
        }
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(
            FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional,
            Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        try {
            if (optionList.size() <= 1 && !isOptional) {
                return optionList.isEmpty() ? null : optionList.getFirst();
            }

            String gameState = serializeGameState();
            List<String> actionList = new ArrayList<>();
            if (isOptional) {
                actionList.add("NONE (skip, choose nothing)");
            }
            for (T entity : optionList) {
                if (entity instanceof Card c) {
                    actionList.add(cardToString(c));
                } else {
                    actionList.add(entity.toString());
                }
            }

            String context = title != null ? title : saToString(sa);
            int offset = isOptional ? 1 : 0;

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, actionList);

            if (isOptional && choice == 0) {
                return null;
            }

            int entityIdx = choice - offset;
            if (entityIdx >= 0 && entityIdx < optionList.size()) {
                return optionList.get(entityIdx);
            }
            return optionList.isEmpty() ? null : optionList.getFirst();
        } catch (Exception e) {
            Logger.error(e, "External AI chooseSingleEntityForEffect failed, falling back");
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList,
                                                   SpellAbility sa, String title, int min, int max, boolean isOptional,
                                                   Map<String, Object> params) {
        try {
            if (sourceList.size() <= min && !isOptional) {
                return sourceList;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : sourceList) {
                options.add(cardToString(c));
            }

            String context = (title != null ? title : saToString(sa))
                    + " (choose " + min + " to " + max + ")";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + min + " to " + max + " cards.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < sourceList.size() && result.size() < max) {
                    result.add(sourceList.get(idx));
                }
            }

            if (result.size() < min && !isOptional) {
                for (Card c : sourceList) {
                    if (!result.contains(c) && result.size() < min) {
                        result.add(c);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseCardsForEffect failed, falling back");
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                          CardCollectionView validTargets, String message) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : validTargets) {
                options.add(cardToString(c));
            }

            String context = "Choose permanents to sacrifice"
                    + (message != null ? ": " + message : "")
                    + " (min=" + min + ", max=" + max + ")";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose which permanents to sacrifice. Sacrifice your least valuable permanents.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < validTargets.size() && result.size() < max) {
                    result.add(validTargets.get(idx));
                }
            }

            while (result.size() < min && result.size() < validTargets.size()) {
                for (Card c : validTargets) {
                    if (!result.contains(c) && result.size() < min) {
                        result.add(c);
                        break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI choosePermanentsToSacrifice failed, falling back");
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        }
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
                                                   CardCollection validCards, int min, int max,
                                                   CardCollectionView visibleToChooser) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : validCards) {
                options.add(c.getName());
            }

            String context = "Choose " + min + " to " + max + " cards to discard.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Discard your worst cards. Keep removal, card draw, and key threats.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < validCards.size() && result.size() < max) {
                    result.add(validCards.get(idx));
                }
            }

            while (result.size() < min && result.size() < validCards.size()) {
                for (Card c : validCards) {
                    if (!result.contains(c)) {
                        result.add(c);
                        break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseCardsToDiscardFrom failed, falling back");
            return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        }
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode,
                                 String message, List<String> options, Card cardToShow,
                                 Map<String, Object> params) {
        try {
            String gameState = serializeGameState();
            String question = message != null ? message : "Confirm action?";
            if (sa != null && sa.getHostCard() != null) {
                question = sa.getHostCard().getName() + " — " + question;
            }
            return agent.chooseYesNo(gameState, question);
        } catch (Exception e) {
            Logger.error(e, "External AI confirmAction failed, falling back");
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        if (wrapper.isMandatory()) {
            return true;
        }
        try {
            String gameState = serializeGameState();
            String question = "Use optional trigger: " + wrapper.getWrappedAbility().getOriginalDescription()
                    + " from " + wrapper.getHostCard().getName() + "? (CONTEXT: " +
                    wrapper.getHostCard().getOracleText() + ")";
            return agent.chooseYesNo(gameState, question);
        } catch (Exception e) {
            Logger.error(e, "External AI confirmTrigger failed, falling back");
            return super.confirmTrigger(wrapper);
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        try {
            String gameState = serializeGameState();
            StringBuilder handDesc = new StringBuilder("\nCards to return if you mulligan: ");
            handDesc.append(cardsToReturn);
            return agent.chooseYesNo(gameState + "\n" + handDesc,
                    "Keep this hand? (YES = keep, NO = mulligan)");
        } catch (Exception e) {
            Logger.error(e, "External AI mulliganKeepHand failed, falling back");
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : topN) {
                options.add(c.getName());
            }

            List<Integer> keepOnTop = agent.chooseSubset(
                    gameState, options,
                    "Scry: choose which cards to keep on TOP of library. Unchosen cards go to bottom.");

            CardCollection toTop = new CardCollection();
            CardCollection toBottom = new CardCollection();
            Set<Integer> topSet = new HashSet<>(keepOnTop);
            for (int i = 0; i < topN.size(); i++) {
                if (topSet.contains(i)) {
                    toTop.add(topN.get(i));
                } else {
                    toBottom.add(topN.get(i));
                }
            }
            return ImmutablePair.of(toTop, toBottom);
        } catch (Exception e) {
            Logger.error(e, "External AI arrangeForScry failed, falling back");
            return super.arrangeForScry(topN);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : topN) {
                options.add(c.getName());
            }

            List<Integer> keepOnTop = agent.chooseSubset(
                    gameState, options,
                    "Surveil: choose which cards to keep on TOP of library. Unchosen go to graveyard.");

            CardCollection toTop = new CardCollection();
            CardCollection toGraveyard = new CardCollection();
            Set<Integer> topSet = new HashSet<>(keepOnTop);
            for (int i = 0; i < topN.size(); i++) {
                if (topSet.contains(i)) {
                    toTop.add(topN.get(i));
                } else {
                    toGraveyard.add(topN.get(i));
                }
            }
            return ImmutablePair.of(toTop, toGraveyard);
        } catch (Exception e) {
            Logger.error(e, "External AI arrangeForSurveil failed, falling back");
            return super.arrangeForSurveil(topN);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE MODE FOR ABILITY (Charms, Commands, MDFCs)
    // ---------------------------------------------------------------
    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                 int min, int num, boolean allowRepeat) {
        try {
            if (possible.size() <= min) {
                return possible;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (AbilitySub mode : possible) {
                String desc = mode.getDescription();
                if (desc == null || desc.isEmpty()) {
                    desc = mode.toString();
                }
                options.add(desc);
            }

            String context = "Choose " + min + " to " + num + " modes for "
                    + sa.getHostCard().getName() + "."
                    + (allowRepeat ? " You may choose the same mode more than once." : "");

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose the most impactful modes for the current game state.");

            List<AbilitySub> result = new ArrayList<>();
            for (int idx : chosen) {
                if (idx >= 0 && idx < possible.size() && result.size() < num) {
                    if (allowRepeat || !result.contains(possible.get(idx))) {
                        result.add(possible.get(idx));
                    }
                }
            }

            if (result.size() < min) {
                for (AbilitySub mode : possible) {
                    if (!result.contains(mode) && result.size() < min) {
                        result.add(mode);
                    }
                }
            }

            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseModeForAbility failed, falling back");
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE BINARY (yes/no decisions throughout the game)
    // ---------------------------------------------------------------
    @Override
    public boolean chooseBinary(SpellAbility sa, String question,
                                BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        try {
            String gameState = serializeGameState();
            String fullQuestion = question;
            if (sa != null && sa.getHostCard() != null) {
                fullQuestion = sa.getHostCard().getName() + " — " + question;
            }
            if (kindOfChoice != null) {
                fullQuestion += " (Choice type: " + kindOfChoice + ")";
            }
            return agent.chooseYesNo(gameState, fullQuestion);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseBinary failed, falling back");
            return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE CARDS TO DISCARD TO MAXIMUM HAND SIZE
    // ---------------------------------------------------------------
    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        try {
            String gameState = serializeGameState();
            CardCollectionView hand = player.getCardsIn(ZoneType.Hand);

            List<String> options = new ArrayList<>();
            for (Card c : hand) {
                if (c.isLand()) {
                    options.add(c.getName());
                } else {
                    options.add(c.getName() + " " + c.getManaCost() + " - " + c.getOracleText());
                }
            }

            String context = "You must discard " + numDiscard + " card(s) to get to maximum hand size."
                    + " Choose the least useful cards to discard."
                    + " Keep your best spells, removal, and cards that fit your game plan.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose exactly " + numDiscard + " card(s) to discard.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < hand.size() && result.size() < numDiscard) {
                    result.add(hand.get(idx));
                }
            }

            while (result.size() < numDiscard) {
                for (Card c : hand) {
                    if (!result.contains(c)) {
                        result.add(c);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseCardsToDiscardToMaximumHandSize failed, falling back");
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE SINGLE SPELL FOR EFFECT (counter target, copy, etc.)
    // ---------------------------------------------------------------
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells,
                                                   SpellAbility sa, String title, Map<String, Object> params) {
        try {
            if (spells.size() <= 1) {
                return spells.isEmpty() ? null : spells.get(0);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (SpellAbility spell : spells) {
                Card host = spell.getHostCard();
                String desc = host.getName();
                if (spell.getDescription() != null && !spell.getDescription().isEmpty()) {
                    desc += ": " + spell.getDescription();
                }
                desc += " (controlled by " + host.getController().getName() + ")";
                options.add(desc);
            }

            String context = title != null ? title : "Choose a spell";
            if (sa != null && sa.getHostCard() != null) {
                context += " (for " + sa.getHostCard().getName() + ")";
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < spells.size()) {
                return spells.get(choice);
            }
            return spells.get(0);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseSingleSpellForEffect failed, falling back");
            return super.chooseSingleSpellForEffect(spells, sa, title, params);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE COLOR / CHOOSE COLORS
    // ---------------------------------------------------------------
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (colors.hasWhite()) { options.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (colors.hasBlue()) { options.add("Blue"); colorBytes.add(MagicColor.BLUE); }
            if (colors.hasBlack()) { options.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (colors.hasRed()) { options.add("Red"); colorBytes.add(MagicColor.RED); }
            if (colors.hasGreen()) { options.add("Green"); colorBytes.add(MagicColor.GREEN); }

            if (options.isEmpty()) {
                return super.chooseColor(message, sa, colors);
            }

            String context = message != null ? message : "Choose a color";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " — " + context;
            }
            context += "\nConsider what colors your opponents are playing.";

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < colorBytes.size()) {
                return colorBytes.get(choice);
            }
            return colorBytes.get(0);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseColor failed, falling back");
            return super.chooseColor(message, sa, colors);
        }
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (colors.hasWhite()) { options.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (colors.hasBlue()) { options.add("Blue"); colorBytes.add(MagicColor.BLUE); }
            if (colors.hasBlack()) { options.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (colors.hasRed()) { options.add("Red"); colorBytes.add(MagicColor.RED); }
            if (colors.hasGreen()) { options.add("Green"); colorBytes.add(MagicColor.GREEN); }
            options.add("Colorless"); colorBytes.add(MagicColor.COLORLESS);

            String context = message != null ? message : "Choose a color";
            if (c != null) {
                context = c.getName() + " — " + context;
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < colorBytes.size()) {
                return colorBytes.get(choice);
            }
            return colorBytes.get(0);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseColorAllowColorless failed, falling back");
            return super.chooseColorAllowColorless(message, c, colors);
        }
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        try {
            String gameState = serializeGameState();
            List<String> colorOptions = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (options.hasWhite()) { colorOptions.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (options.hasBlue()) { colorOptions.add("Blue"); colorBytes.add(MagicColor.BLUE); }
            if (options.hasBlack()) { colorOptions.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (options.hasRed()) { colorOptions.add("Red"); colorBytes.add(MagicColor.RED); }
            if (options.hasGreen()) { colorOptions.add("Green"); colorBytes.add(MagicColor.GREEN); }

            if (colorOptions.isEmpty()) {
                return super.chooseColors(message, sa, min, max, options);
            }

            String context = (message != null ? message : "Choose colors")
                    + " (choose " + min + " to " + max + ")";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " — " + context;
            }

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, colorOptions,
                    "Choose " + min + " to " + max + " colors.");

            byte result = 0;
            int count = 0;
            for (int idx : chosen) {
                if (idx >= 0 && idx < colorBytes.size() && count < max) {
                    result |= colorBytes.get(idx);
                    count++;
                }
            }

            if (count < min) {
                for (byte cb : colorBytes) {
                    if ((result & cb) == 0 && count < min) {
                        result |= cb;
                        count++;
                    }
                }
            }

            return ColorSet.fromMask(result);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseColors failed, falling back");
            return super.chooseColors(message, sa, min, max, options);
        }
    }

    // ===================================================================
    // DELEGATED TO FALLBACK AI — mechanical / trivial decisions
    // ===================================================================

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return super.playChosenSpellAbility(sa);
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return super.sideboard(deck, gameType, message);
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return super.chooseCardsYouWonToAddToDeck(losses);
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
                                                 CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return super.divideShield(effectSource, affected, shieldAmount);
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return super.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return super.announceRequirements(ability, min, max, announce);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                        CardCollectionView validTargets, String message) {
        return super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return super.chooseNewTargetsFor(ability, filter, optional);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        try {
            TargetRestrictions tgt = currentAbility.getTargetRestrictions();
            if (tgt == null) {
                return super.chooseTargetsFor(currentAbility);
            }

            // Get all valid targets
            CardCollectionView validCards = CardUtil.getValidCardsToTarget(
                    currentAbility);

            // Also check for player targets
            List<Player> validPlayers = new ArrayList<>();
            for (Player p : getGame().getPlayers()) {
                if (currentAbility.canTarget(p)) {
                    validPlayers.add(p);
                }
            }

            // If no valid targets at all, fall back
            if (validCards.isEmpty() && validPlayers.isEmpty()) {
                return super.chooseTargetsFor(currentAbility);
            }

            // Build combined target list
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<Object> targetObjects = new ArrayList<>();

            for (Card c : validCards) {
                String owner = c.getController() == player ? "(yours) " : "(opponent's) ";
                options.add(owner + cardToString(c));
                targetObjects.add(c);
            }
            for (Player p : validPlayers) {
                String label = p == player ? "(you) " : "(opponent) ";
                options.add(label + p.getName() + " (" + p.getLife() + " life)");
                targetObjects.add(p);
            }

            if (options.isEmpty()) {
                return super.chooseTargetsFor(currentAbility);
            }

            String context = "Choose target for: " + saToString(currentAbility)
                    + "\nMin targets: " + tgt.getMinTargets(currentAbility.getHostCard(), currentAbility)
                    + ", Max targets: " + tgt.getMaxTargets(currentAbility.getHostCard(), currentAbility);

            int minTargets = tgt.getMinTargets(currentAbility.getHostCard(), currentAbility);
            int maxTargets = tgt.getMaxTargets(currentAbility.getHostCard(), currentAbility);

            if (maxTargets == 1) {
                // Single target — use chooseAction
                int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);
                if (choice >= 0 && choice < targetObjects.size()) {
                    Object target = targetObjects.get(choice);
                    if (target instanceof Card c) {
                        currentAbility.getTargets().add(c);
                    } else if (target instanceof Player p) {
                        currentAbility.getTargets().add(p);
                    }
                    return true;
                }
            } else {
                // Multiple targets — use chooseSubset
                List<Integer> chosen = agent.chooseSubset(
                        gameState + "\nCONTEXT: " + context, options,
                        "Choose " + minTargets + " to " + maxTargets + " targets.");

                int added = 0;
                for (int idx : chosen) {
                    if (idx >= 0 && idx < targetObjects.size() && added < maxTargets) {
                        Object target = targetObjects.get(idx);
                        if (target instanceof Card c) {
                            currentAbility.getTargets().add(c);
                            added++;
                        } else if (target instanceof Player p) {
                            currentAbility.getTargets().add(p);
                            added++;
                        }
                    }
                }

                if (added >= minTargets) {
                    return true;
                }
            }

            // If we failed to get enough targets, fall back to heuristic
            currentAbility.getTargets().clear();
            return super.chooseTargetsFor(currentAbility);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseTargetsFor failed, falling back");
            currentAbility.getTargets().clear();
            return super.chooseTargetsFor(currentAbility);
        }
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
                                                                    List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return super.chooseTarget(sa, allTargets);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return super.helpPayForAssistSpell(cost, sa, max, requested);
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return super.choosePlayerToAssistPayment(optionList, sa, title, max);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
                                                       SpellAbility sa, String title, boolean isOptional) {
        return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList,
                                                                  int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title,
                                                                  Player targetedPlayer, Map<String, Object> params) {
        return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells,
                                                            SpellAbility sa, String title, int num, Map<String, Object> params) {
        return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode,
                                    String string, int bid, Player winner) {
        return super.confirmBidAction(sa, mode, string, bid, winner);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect,
                                            SpellAbility effectSA, GameEntity affected, String question) {
        return super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode,
                                            String message, String logic) {
        return super.confirmStaticApplication(hostCard, mode, message, logic);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        return super.confirmPayment(costPart, prompt, sa);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return super.exertAttackers(attackers);
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return super.enlistAttackers(attackers);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return super.orderBlockers(attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return super.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return super.orderAttackers(blocker, attackers);
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner,
                       String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner,
                       String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return super.willPutCardOnTop(c);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards,
                                                  ZoneType destinationZone, SpellAbility source) {
        try {
            if (cards.size() <= 1) {
                return super.orderMoveToZoneList(cards, destinationZone, source);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : cards) {
                options.add(c.getName());
            }

            String zoneDesc = destinationZone.toString();
            String context = "Order these cards being put into " + zoneDesc;
            if (source != null && source.getHostCard() != null) {
                context += " (from " + source.getHostCard().getName() + ")";
            }

            if (destinationZone == ZoneType.Library) {
                context += "\nFirst in the list = TOP of library (you draw it first)."
                        + "\nPut the card you want to draw next first.";
            } else if (destinationZone == ZoneType.Graveyard) {
                context += "\nFirst in the list = top of graveyard.";
            }

            List<Integer> ordering = agent.chooseOrdering(gameState + "\nCONTEXT: " + context,
                    options, "Order these cards (first = top).");

            CardCollection result = new CardCollection();
            Set<Integer> used = new HashSet<>();

            // Add cards in the order the LLM specified
            for (int idx : ordering) {
                if (idx >= 0 && idx < cards.size() && !used.contains(idx)) {
                    result.add(cards.get(idx));
                    used.add(idx);
                }
            }

            // Add any cards the LLM missed (preserve original order for those)
            for (int i = 0; i < cards.size(); i++) {
                if (!used.contains(i)) {
                    result.add(cards.get(i));
                }
            }

            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI orderMoveToZoneList failed, falling back");
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand,
                                                             String[] uTypes, SpellAbility sa) {
        return super.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return super.chooseCardsToDelve(genericAmount, grave);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa,
                                                                     ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts,
                                                                     boolean creatures, Integer maxReduction) {
        return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return super.chooseCardsForSplice(sa, cards);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        super.playSpellAbilityNoStack(effectSA, canSetupTargets);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return super.orderSimultaneousSa(activePlayerSAs);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        super.orderAndPlaySimultaneousSa(activePlayerSAs);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return super.playTrigger(host, wrapperAbility, isMandatory);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return super.playSaFromPlayEffect(tgtSA);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return super.chooseManaFromPool(manaChoices);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa,
                                 Collection<String> validTypes, boolean isOptional) {
        return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return super.chooseSector(assignee, ai, sectors);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return super.chooseContraptionsToCrank(contraptions);
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return super.chooseSprocket(assignee, sprockets);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return super.choosePDRollToIgnore(rolls);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return super.chooseRollToIgnore(rolls);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return super.chooseDiceToReroll(rolls);
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return super.chooseRollToModify(rolls);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return super.chooseRollToSwap(rolls);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult,
                                      int power, int toughness) {
        return super.chooseRollSwapValue(swapChoices, currentResult, power, toughness);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                       ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return super.vote(sa, prompt, options, votes, forPlayer, optional);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return super.tuckCardsViaMulligan(hand, cardsToReturn);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return super.chooseNumberForCostReduction(sa, min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost,
                                          KeywordInterface keyword, String prompt, int max) {
        return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return super.chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return super.chooseNumber(sa, title, values, relatedPlayer);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return super.chooseFlipResult(sa, flipper, call);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message,
                                          Predicate<ICardFace> cpp, String name) {
        return super.chooseSingleCardFace(sa, message, cpp, name);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return super.chooseSingleCardFace(sa, faces, message);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states,
                                           String message, Map<String, Object> params) {
        return super.chooseSingleCardState(sa, states, message, params);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1,
                                   CardCollectionView pile2, String faceUp) {
        try {
            String gameState = serializeGameState();

            StringBuilder pile1Desc = new StringBuilder("Pile 1: ");
            for (Card c : pile1) {
                pile1Desc.append(c.getName()).append(", ");
            }
            pile1Desc.append("(").append(pile1.size()).append(" cards)");

            StringBuilder pile2Desc = new StringBuilder("Pile 2: ");
            for (Card c : pile2) {
                pile2Desc.append(c.getName()).append(", ");
            }
            pile2Desc.append("(").append(pile2.size()).append(" cards)");

            String context = sa.getHostCard().getName()
                    + "\nChoose which pile to take."
                    + "\n" + pile1Desc
                    + "\n" + pile2Desc;

            return agent.chooseYesNo(gameState + "\nCONTEXT: " + context,
                    "Take Pile 1? (YES = Pile 1, NO = Pile 2)");
        } catch (Exception e) {
            Logger.error(e, "External AI chooseCardsPile failed, falling back");
            return super.chooseCardsPile(sa, pile1, pile2, faceUp);
        }
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa,
                                         String prompt, Map<String, Object> params) {
        return super.chooseCounterType(options, sa, prompt, params);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa,
                                       String prompt, Card tgtCard) {
        try {
            if (options.size() <= 1) {
                return options.isEmpty() ? null : options.get(0);
            }

            String gameState = serializeGameState();
            String context = sa.getHostCard().getName()
                    + " — Choose a keyword to give to " + cardToStringCompact(tgtCard)
                    + (prompt != null ? "\n" + prompt : "");

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < options.size()) {
                return options.get(choice);
            }
            return options.get(0);
        } catch (Exception e) {
            Logger.error(e, "External AI chooseKeywordForPump failed, falling back");
            return super.chooseKeywordForPump(options, sa, prompt, tgtCard);
        }
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return super.chooseSingleReplacementEffect(possibleReplacers);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleStatics) {
        return super.chooseSingleStaticAbility(possibleStatics);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return super.chooseProtectionType(sa, choices);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
                               String prompt, ManaConversionMatrix matrix, boolean effect) {
        return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return super.payCombatCost(card, cost, sa, prompt);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability,
                                   String prompt, ManaConversionMatrix matrix, boolean effect) {
        return super.applyManaToCost(toPay, ability, prompt, matrix, effect);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
                                                 CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability,
                                                      boolean effect, String prompt) {
        return super.getCostDecisionMaker(player, ability, effect, prompt);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
                                          FCollectionView<Player> allPayers) {
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return super.payCostDuringRoll(cost, sa);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp,
                                 String valid, String message) {
        return super.chooseCardName(sa, cpp, valid, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return super.chooseCardName(sa, faces, message);
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
                                              SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal,
                                              String selectPrompt, boolean isOptional, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        try {
            if (fetchList.isEmpty()) return null;
            if (fetchList.size() == 1 && !isOptional) return fetchList.getFirst();

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            if (isOptional) {
                options.add("NONE (don't search)");
            }
            for (Card c : fetchList) {
                if (c.isLand()) {
                    options.add(c.getName());
                } else {
                    options.add(c.getName() + " " + c.getManaCost() + " - " + c.getOracleText());
                }
            }

            String context = selectPrompt != null ? selectPrompt : "Search for a card";
            int offset = isOptional ? 1 : 0;
            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (isOptional && choice == 0) return null;

            int idx = choice - offset;
            if (idx >= 0 && idx < fetchList.size()) {
                return fetchList.get(idx);
            }
            return fetchList.getFirst();
        } catch (Exception e) {
            Logger.error(e, "External AI chooseSingleCardForZoneChange failed, falling back");
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList,
                    delayedReveal, selectPrompt, isOptional, decider);
        }
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                               SpellAbility sa, CardCollection fetchList, int min, int max,
                                               DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
                delayedReveal, selectPrompt, decider);
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        super.notifyOfValue(saSource, realtedTarget, value);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return super.chooseCardsToRevealFromHand(min, max, valid);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return this.player;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return super.chooseStartingHand(zones);
    }

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        super.revealAnte(message, removedAnteCards);
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        super.revealAISkipCards(message, deckCards);
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        super.revealUnsupported(unsupported);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                       List<OptionalCostValue> optionalCostValues) {
        try {
            if (optionalCostValues.isEmpty()) {
                return super.chooseOptionalCosts(chosen, optionalCostValues);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (OptionalCostValue ocv : optionalCostValues) {
                options.add(ocv.getType().toString() + " — " + ocv.getCost().toString());
            }

            String context = chosen.getHostCard().getName()
                    + " — Choose which optional costs to pay (e.g. Kicker, Buyback, Entwine)."
                    + "\nOnly pay if you have the mana AND the effect is worth it."
                    + "\nAvailable mana: " + buildAvailableMana(player);

            List<Integer> chosenIndices = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose optional costs to pay, or NONE to skip all.");

            List<OptionalCostValue> result = new ArrayList<>();
            for (int idx : chosenIndices) {
                if (idx >= 0 && idx < optionalCostValues.size()) {
                    result.add(optionalCostValues.get(idx));
                }
            }

            return result;
        } catch (Exception e) {
            Logger.error(e, "External AI chooseOptionalCosts failed, falling back");
            return super.chooseOptionalCosts(chosen, optionalCostValues);
        }
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return super.orderCosts(costs);
    }

    @Override
    public void resetAtEndOfTurn() {
        super.resetAtEndOfTurn();
    }
}