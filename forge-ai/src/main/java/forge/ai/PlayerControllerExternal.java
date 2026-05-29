package forge.ai;

import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;

import forge.LobbyPlayer;
import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollectionView;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlayerController that routes strategic decisions to an external LLM agent
 * (via ExternalAgentClient) and delegates mechanical/trivial decisions to the
 * built-in PlayerControllerAi as a super.
 */
public class PlayerControllerExternal extends PlayerControllerAi {

    private final ExternalAgentClient agent;

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp,
                                    String agentUrl, String modelName) {
        super(game, p, lp);
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

    private String playerTag(Player p) {
        if (p == player) return "you";
        int oppIdx = 0;
        for (Player gp : getGame().getPlayers()) {
            if (gp == player) continue;
            oppIdx++;
            if (gp == p) break;
        }
        return getGame().getPlayers().size() > 2 ? "opp" + oppIdx : "opp";
    }

    private String buildManualGameState() {
        StringBuilder sb = new StringBuilder();

        PhaseType ph = getGame().getPhaseHandler().getPhase();
        sb.append("turn=").append(getGame().getPhaseHandler().getTurn());
        sb.append(" phase=").append(ph != null ? ph.toString() : "PREGAME");
        sb.append(" player=").append(playerTag(getGame().getPhaseHandler().getPlayerTurn()));
        sb.append("\n");

        int oppCount = 0;
        for (Player p : getGame().getPlayers()) {
            String tag = playerTag(p);
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

            sb.append(tag).append("_deck: ").append(p.getCardsIn(ZoneType.Library).size()).append(" cards");
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

            CardCollectionView exile = p.getCardsIn(ZoneType.Exile);
            if (!exile.isEmpty()) {
                sb.append(tag).append("_exile: ");
                List<String> exileCards = new ArrayList<>();
                for (Card c : exile) {
                    exileCards.add(c.getName());
                }
                sb.append(String.join(", ", exileCards));
                sb.append("\n");
            }
        }

        Combat combat = getGame().getCombat();
        if (combat != null && !combat.getAttackers().isEmpty()) {
            sb.append("COMBAT:\n");
            for (Card attacker : combat.getAttackers()) {
                sb.append("  attacking: ").append(cardToStringCompact(attacker));
                GameEntity def = combat.getDefenderByAttacker(attacker);
                sb.append(" -> ").append(def instanceof Player p ? playerTag(p) : def.toString());

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
            sb.append("STACK: ");
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                sb.append(si.getSpellAbility().getHostCard().getName()).append(": ")
                        .append(si.getStackDescription()).append("\n");
            }
        }

        boolean isYourMainPhase = (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2)
                && getGame().getPhaseHandler().getPlayerTurn() == player;
            if (!isYourMainPhase) {
                    sb.append("PRIORITY NOTE: You have priority at instant speed. ")
                        .append("Passing is normal here unless you have a beneficial ")
                        .append("instant-speed play (e.g. combat trick, removal, counterspell, ")
                        .append("or activated ability).\n");
            }

        return sb.toString();
    }

    private static final List<Character> MANA_ORDER =
            Arrays.asList('W', 'U', 'B', 'R', 'G', 'C');

    private String buildAvailableMana(Player p) {
        List<String> fixed = new ArrayList<>();
        List<String> flexible = new ArrayList<>();

        int total = 0;

        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            // Include ALL untapped mana sources, not just lands
            if (c.isTapped()) {
                continue;
            }
            List<SpellAbility> manaAbilities = new ArrayList<>(c.getManaAbilities());
            if (manaAbilities.isEmpty()) {
                continue;
            }
            // Collect all unique mana colors this permanent can produce
            Set<Character> colors = new LinkedHashSet<>();


            for (SpellAbility sa : manaAbilities) {
                if (sa.getManaPart() == null) {
                    continue;
                }

                String produced = sa.getManaPart().getOrigProduced();
                if (produced == null) {
                    continue;
                }

                boolean producesColored = false;

                Matcher matcher = Pattern.compile("[WUBRG]").matcher(produced);

                while (matcher.find()) {
                    colors.add(matcher.group().charAt(0));
                    producesColored = true;
                }

                // Only include colorless if it's truly colorless-only
                if (!producesColored && produced.contains("C")) {
                    colors.add('C');
                }
            }
            if (colors.isEmpty()) {
                continue;
            }

            // Normalize mana ordering to WUBRGC
            List<Character> ordered = new ArrayList<>(colors);
            ordered.sort(Comparator.comparingInt(MANA_ORDER::indexOf));

            if (ordered.size() == 1) {
                fixed.add(String.valueOf(ordered.get(0)));
            } else {
                StringBuilder flex = new StringBuilder("{");

                for (int i = 0; i < ordered.size(); i++) {
                    if (i > 0) {
                        flex.append("/");
                    }
                    flex.append(ordered.get(i));
                }
                flex.append("}");
                flexible.add(flex.toString());
            }
            total++;
        }

        // Sort fixed mana in WUBRGC order
        fixed.sort(Comparator.comparingInt(s ->
                MANA_ORDER.indexOf(s.charAt(0))));
        Collections.sort(flexible);
        StringBuilder sb = new StringBuilder();

        for (String s : fixed) {
            sb.append(s);
        }
        for (String s : flexible) {
            sb.append(s);
        }
        if (total == 0) {
            return "0 (0 total)";
        }

        return sb.toString() + " (" + total + " total)";
    }

    private static String cardToStringCompact(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        if (c.isCreature()) {
            sb.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness()).append(" ");
        }
        if (c.isTapped()) sb.append(" (tapped)");
        if (c.isSick()) sb.append(" (summoning sick)");
        Map<CounterType, Integer> counters = c.getCounters();
        if (!counters.isEmpty()) {
            sb.append(" [");
            counters.forEach((type, count) ->
                    sb.append(type).append("=").append(count).append(","));
            sb.setLength(sb.length() - 1);
            sb.append("] ");
        }

        List<KeywordInterface> keywords = c.getKeywords();
        if (!keywords.isEmpty()) {
            sb.append(" [");
            keywords.forEach((kw) ->
                    sb.append(kw).append(","));
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
        sb.append(" ").append(c.getOracleText().replace("\\n", " "));
        return sb.toString();
    }

    private static String saToString(SpellAbility sa) {
        Card c = sa.getHostCard();
        String prefix = "";
        String desc = "";

        // Determine which face/state this SA represents
        CardState saState = null;
        try {
            saState = sa.getCardState();
        } catch (Exception ignored) {}

        // Adventure / MDFC back / Omen: secondary state has its own name & text
        String displayName = c.getName();
        if (saState != null && saState.getStateName() == CardStateName.Secondary) {
            displayName = saState.getName();
        }

        if (sa.isAbility()) {
            prefix = "Activate ability ";
            desc = sa.getDescription().replace("\\n", " ") +
            " - " + sa.getHostCard().getOracleText();
        } else if (sa.isSpell()) {
            prefix = "Cast ";
            if (saState != null) {
                desc = saState.getType().toString() + " "
                        + saState.getOracleText().replace("\\n", " ");
            } else {
                desc = c.getType().toString() + " " + c.getOracleText().replace("\\n", " ");
            }
        }

        StringBuilder tags = new StringBuilder();
        try {
            Cost payCost = sa.getPayCosts();
            String costStr = payCost != null ? payCost.toString() : "";
            if (!costStr.isEmpty()) {
                tags.append(" [cost: ").append(costStr).append("]");
            }
            for (OptionalCost cost : OptionalCost.values()) {
                if (sa.isOptionalCostPaid(cost)) tags.append(" [").append(cost.toString()).append("]");
            }
        } catch (Exception ignored) {}

        return prefix + displayName + tags + ": " + desc;
    }

    // ===================================================================
    // STRATEGIC DECISIONS - routed to external LLM
    // ===================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        try {
            String gameState = serializeGameState();

            CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), player);
            List<SpellAbility> baseAbilities = ComputerUtilAbility.getSpellAbilities(cards, player);

            // Expand each base SA into all alternative-cost and optional-cost variants
            // (kicked, buyback, flashback, "pay 2 life untapped" shockland modes, etc.),
            // exactly like the heuristic AiController does. Each variant is a distinct
            // SpellAbility with the optional costs already baked into its pay cost and
            // its isOptionalCostPaid()/isKicked() flags already set.
            List<SpellAbility> spellAbilities =
                    new ArrayList<>(ComputerUtilAbility.getOriginalAndAltCostAbilities(baseAbilities, player));

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

                // CRITICAL: For adventure/secondary-face SAs, getPayCosts() may return
                // the host card's cost (or empty) if the CardState is not properly bound.
                // Guard against this: skip any non-land spell SA whose pay cost is null
                // or empty, as it cannot be meaningfully validated or displayed.
                if (sa.isSpell()) {
                    Cost payCost = sa.getPayCosts();
                    if (payCost == null || payCost.hasNoManaCost() && payCost.isOnlyManaCost()) {
                        // Zero-cost spells (e.g. Ancestral Vision suspended, Force of Will
                        // alternative cost) are legitimate — only skip if the cost object
                        // itself is null or its string representation is empty AND the card
                        // is not a known zero-mana spell.
                        if (payCost == null) continue;
                        // If the cost string is empty it means the SA was not properly
                        // initialised — most likely an adventure whose CardState was not
                        // bound. Skip it to avoid offering an unplayable action.
                        String costStr = payCost.toString();
                        if (costStr == null || costStr.isEmpty()) continue;
                    }
                }

                if (!sa.canPlay(false)) continue;          // false: don't probe optional costs here
                if (!ComputerUtilCost.canPayCost(sa, player, false)) continue;
                if (sa.usesTargeting()) {
                    boolean anyCardTarget = !CardUtil.getValidCardsToTarget(sa).isEmpty();
                    boolean anyPlayerTarget = false;
                    for (Player p : getGame().getPlayers()) {
                        if (sa.canTarget(p)) { anyPlayerTarget = true; break; }
                    }
                    boolean anyStackTarget = false;
                    for (SpellAbilityStackInstance si : getGame().getStack()) {
                        if (sa.canTarget(si.getSpellAbility())) { anyStackTarget = true; break; }
                    }
                    if (!anyCardTarget && !anyPlayerTarget && !anyStackTarget) continue;
                } else if (sa.getApi() == ApiType.Charm) {
                    // Modal Charm: check whether ANY mode has a legal target.
                    boolean anyModeTargetable = false;
                    List<AbilitySub> charmChoices = CharmEffect.makePossibleOptions(sa);
                    for (AbilitySub mode : charmChoices) {
                        if (!mode.usesTargeting()) {
                            // Mode doesn't require targets — always playable.
                            anyModeTargetable = true;
                            break;
                        }
                        if (!CardUtil.getValidCardsToTarget(mode).isEmpty()) {
                            anyModeTargetable = true;
                            break;
                        }
                        for (Player p : getGame().getPlayers()) {
                            if (mode.canTarget(p)) { anyModeTargetable = true; break; }
                        }
                        if (anyModeTargetable) break;
                        for (SpellAbilityStackInstance si : getGame().getStack()) {
                            if (mode.canTarget(si.getSpellAbility())) { anyModeTargetable = true; break; }
                        }
                        if (anyModeTargetable) break;
                    }
                    if (!anyModeTargetable) continue;
                }

                // Base (unkicked) action
                actions.add(saToString(sa));
                actionSources.add(sa);

                // Manually offer each affordable optional-cost variant as its own action
                for (OptionalCostValue ocv : GameActionUtil.getOptionalCostValues(sa)) {
                    SpellAbility withOpt = GameActionUtil.addOptionalCosts(sa, new ArrayList<>(List.of(ocv)));
                    withOpt.setActivatingPlayer(player);
                    if (withOpt.canPlay() && ComputerUtilCost.canPayCost(withOpt, player, false)) {
                        actions.add(saToString(withOpt));
                        actionSources.add(withOpt);
                    }
                }
            }

            if (actions.size() <= 1) {
                return null;
            }

            // Dedup actions with identical display strings
            Set<String> seen = new HashSet<>();
            List<String> dedupActions = new ArrayList<>();
            List<Object> dedupSources = new ArrayList<>();
            for (int i = 0; i < actions.size(); i++) {
                if (seen.add(actions.get(i))) {
                    dedupActions.add(actions.get(i));
                    dedupSources.add(actionSources.get(i));
                }
            }
            actions = dedupActions;
            actionSources = dedupSources;

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
            System.err.println("[ExternalAI] chooseSpellAbilityToPlay failed, falling back: " + e);
            e.printStackTrace();
            return super.chooseSpellAbilityToPlay();
        }
    }

    private static List<SpellAbility> singleSpellAbilityList(SpellAbility sa) {
        if (sa == null) return null;
        List<SpellAbility> list = new ArrayList<>();
        list.add(sa);
        return list;
    }

    private void removeUnpayableAttackers(Combat combat) {
        for (Card attacker : combat.getAttackers().threadSafeIterable()) {
            Cost attackCost = CombatUtil.getAttackCost(
                    getGame(), attacker, combat.getDefenderByAttacker(attacker));
            if (attackCost == null) {
                continue;
            }
            SpellAbility fakeSA = new SpellAbility.EmptySa(attacker, attacker.getController());
            fakeSA.setCardState(attacker.getCurrentState());
            fakeSA.setPayCosts(attackCost);
            fakeSA.setSVar("X", "0");
            if (!ComputerUtilCost.canPayCost(attackCost, fakeSA, player, true)) {
                combat.removeFromCombat(attacker);
            }
        }
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        try {
            List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
            if (defenders.isEmpty()) return;

            CardCollection possibleAttackers = new CardCollection();
            for (Card c : player.getCreaturesInPlay()) {
                if (CombatUtil.canAttack(c)) {
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
                    String tag = playerTag(p);
                    context.append("  D").append(i).append(": ").append(tag)
                            .append(" (").append(p.getLife()).append(" life)\n");
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

            removeUnpayableAttackers(combat);

            if (!CombatUtil.validateAttackers(combat)) {
                combat.clearAttackers();
                final Map<Card, GameEntity> legal =
                        combat.getAttackConstraints().getLegalAttackers().getLeft();
                for (Map.Entry<Card, GameEntity> e : legal.entrySet()) {
                    combat.addAttacker(e.getKey(), e.getValue());
                }
                // Last resort: full heuristic redeclaration
                if (!CombatUtil.validateAttackers(combat)) {
                    combat.clearAttackers();
                    super.declareAttackers(attacker, combat);
                }
            }
        } catch (Exception e) {
            combat.clearAttackers();
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

            // Build list of attackers and available blockers
            List<Card> attackers = new ArrayList<>(combat.getAttackers());

            // Filter to only blockers that can legally block at least one attacker
            CardCollection relevantBlockers = new CardCollection();
            for (Card blocker : possibleBlockers) {
                for (Card attacker : attackers) {
                    if (CombatUtil.canBlock(attacker, blocker, combat)) {
                        relevantBlockers.add(blocker);
                        break;
                    }
                }
            }

            if (relevantBlockers.isEmpty()) return;

            String gameState = serializeGameState();

            StringBuilder context = new StringBuilder();
            context.append("ATTACKERS:\n");
            for (int i = 0; i < attackers.size(); i++) {
                Card atk = attackers.get(i);
                String controllerTag = playerTag(atk.getController());
                context.append("  A").append(i).append(": ").append(cardToStringCompact(atk))
                        .append(" (controlled by ").append(controllerTag).append(")\n");
            }

            context.append("YOUR BLOCKERS:\n");
            for (int i = 0; i < relevantBlockers.size(); i++) {
                context.append("  B").append(i).append(": ").append(cardToStringCompact(relevantBlockers.get(i))).append("\n");
            }
            context.append("\nAssign blocks.");
            context.append("\nRespond with ONLY NONE or the comma-separated blocker assignment pairs." +
                    "Omit creatures you don't want to block with. ");

            String response = agent.chooseRaw(gameState + "\n" + context.toString());

            if (response != null && !response.trim().equalsIgnoreCase("NONE")) {
                for (String pair : response.split(",")) {
                    pair = pair.trim().toUpperCase();
                    String[] parts = pair.split("-");
                    if (parts.length == 2) {
                        try {
                            int bIdx = Integer.parseInt(parts[0].replace("B", "").trim());
                            int aIdx = Integer.parseInt(parts[1].replace("A", "").trim());
                            if (bIdx >= 0 && bIdx < relevantBlockers.size()
                                    && aIdx >= 0 && aIdx < attackers.size()) {
                                Card blocker = relevantBlockers.get(bIdx);
                                Card attacker = attackers.get(aIdx);
                                if (CombatUtil.canBlock(attacker, blocker, combat)) {
                                    combat.addBlocker(attacker, blocker);
                                }
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
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
                question = sa.getHostCard().getName() + " - " + question;
            }
            return agent.chooseYesNo(gameState, question);
        } catch (Exception e) {
            System.err.println("[ExternalAI] confirmAction FELL BACK: " + e);
            e.printStackTrace();
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
            return super.confirmTrigger(wrapper);
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        try {
            String gameState = serializeGameState();
            StringBuilder handDesc = new StringBuilder();
            handDesc.append("Current cards in Hand:");
            for (Card c : player.getCardsIn(ZoneType.Hand)) {
                handDesc.append("\n").append(cardToString(c));
            }
            handDesc.append("\nCards to return if you mulligan: ");
            handDesc.append(cardsToReturn);
            return agent.chooseYesNo(gameState + "\n" + handDesc,
                    "Keep this hand? (YES = keep, NO = mulligan)");
        } catch (Exception e) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : topN) {
                options.add(cardToString(c));
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
            return super.arrangeForScry(topN);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : topN) {
                options.add(cardToString(c));
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
            return super.arrangeForSurveil(topN);
        }
    }

    private boolean canAffordMode(SpellAbility sa, AbilitySub mode) {
        try {
            Cost combined = sa.getPayCosts() != null ? sa.getPayCosts().copy() : new Cost("0", false);

            // Tiered modes store the additional cost in the "ModeCost" param,
            // as a bare generic number (e.g. "0", "2", "5"). Not in mode.getPayCosts().
            String modeCostStr = mode.getParam("ModeCost");
            if (modeCostStr != null && !modeCostStr.isEmpty()) {
                try {
                    combined.add(new Cost(modeCostStr, false));
                } catch (Exception ignored) {
                    // malformed cost string — fall through
                }
            }

            // Fallback: if the mode also has a real Cost on its AbilitySub (other mechanics
            // beyond Tiered may use this), include it.
            Cost modeStructuredCost = mode.getPayCosts();
            if (modeStructuredCost != null) {
                combined.add(modeStructuredCost);
            }

            SpellAbility probe = sa.copy();
            probe.setPayCosts(combined);
            probe.setActivatingPlayer(player);
            return ComputerUtilCost.canPayCost(probe, player, false);
        } catch (Exception e) {
            return true;  // unsure → don't hide it
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE MODE FOR ABILITY (Charms, Commands, MDFCs)
    // ---------------------------------------------------------------
    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                 int min, int num, boolean allowRepeat) {

        try {

            List<AbilitySub> affordable = new ArrayList<>();
            for (AbilitySub mode : possible) {
                if (canAffordMode(sa, mode)) {
                    affordable.add(mode);
                }
            }

            List<AbilitySub> pool = (affordable.size() >= min) ? affordable : possible;

            List<AbilitySub> result;
            if (pool.size() <= min) {
                result = new ArrayList<>(pool);
            } else {

                String gameState = serializeGameState();
                List<String> options = new ArrayList<>();
                for (AbilitySub mode : pool) {
                    String desc = mode.getDescription();
                    if (desc == null || desc.isEmpty()) desc = mode.toString();

                    String modeCostStr = mode.getParam("ModeCost");
                    if (modeCostStr != null && !modeCostStr.isEmpty()) {
                        desc = "[+{" + modeCostStr + "}] " + desc;
                    } else if (mode.getPayCosts() != null && !mode.getPayCosts().toString().isEmpty()) {
                        desc = "[+" + mode.getPayCosts() + "] " + desc;
                    }
                    options.add(desc);
                }

                String context = "Choose " + min + " to " + num + " modes for "
                        + sa.getHostCard().getName() + "."
                        + (allowRepeat ? " You may choose the same mode more than once." : "");

                List<Integer> chosen = agent.chooseSubset(gameState, options, context);

                result = new ArrayList<>();
                for (int idx : chosen) {
                    if (idx >= 0 && idx < pool.size() && result.size() < num) {
                        if (allowRepeat || !result.contains(pool.get(idx))) {
                            result.add(pool.get(idx));
                        }
                    }
                }

                if (result.size() < min) {
                    for (AbilitySub mode : pool) {
                        if (!result.contains(mode) && result.size() < min) {
                            result.add(mode);
                        }
                    }
                }
            }

            // Set up targets for each chosen mode
            for (AbilitySub mode : result) {
                if (mode.usesTargeting()) {
                    mode.clearTargets();
                    chooseTargetsFor(mode);
                }
                // Also handle sub-abilities of each mode
                SpellAbility sub = mode.getSubAbility();
                while (sub != null) {
                    if (sub.usesTargeting()) {
                        sub.clearTargets();
                        chooseTargetsFor(sub);
                    }
                    sub = sub.getSubAbility();
                }
            }

            return result;
        } catch (Exception e) {
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
                fullQuestion = sa.getHostCard().getName() + " - " + question;
            }
            if (kindOfChoice != null) {
                fullQuestion += " (Choice type: " + kindOfChoice + ")";
            }
            return agent.chooseYesNo(gameState, fullQuestion);
        } catch (Exception e) {
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
                desc += " (controlled by " + playerTag(host.getController()) + ")";
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
            return super.chooseSingleSpellForEffect(spells, sa, title, params);
        }
    }

    // ---------------------------------------------------------------
    // CHOOSE COLOR / CHOOSE COLORS
    // ---------------------------------------------------------------
// ---------------- chooseColor ----------------
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        try {
            // SHORT-CIRCUIT: forced choice — no real decision to make.
            // Mirrors the heuristic PlayerControllerAi.chooseColor.
            if (colors.countColors() < 2) {
                return Iterables.getFirst(colors, MagicColor.Color.WHITE).getColorMask();
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (colors.hasWhite()) { options.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (colors.hasBlue())  { options.add("Blue");  colorBytes.add(MagicColor.BLUE); }
            if (colors.hasBlack()) { options.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (colors.hasRed())   { options.add("Red");   colorBytes.add(MagicColor.RED); }
            if (colors.hasGreen()) { options.add("Green"); colorBytes.add(MagicColor.GREEN); }

            if (options.isEmpty()) {
                return super.chooseColor(message, sa, colors);
            }

            String context = message != null ? message : "Choose a color";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " - " + context;
                context += "\nFrom effect: " + sa.getDescription();
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < colorBytes.size()) {
                return colorBytes.get(choice);
            }
            return colorBytes.get(0);
        } catch (Exception e) {
            return super.chooseColor(message, sa, colors);
        }
    }


    // ---------------- chooseColorAllowColorless ----------------
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        try {
            // SHORT-CIRCUIT: with no allowed colors, colorless is the only option.
            // NOTE: do NOT short-circuit on countColors() == 1 here — colorless is
            // always offered as a distinct choice in this method, so 1 color + colorless
            // = 2 real options the LLM may still want to choose between.
            if (colors.countColors() == 0) {
                return MagicColor.COLORLESS;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (colors.hasWhite()) { options.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (colors.hasBlue())  { options.add("Blue");  colorBytes.add(MagicColor.BLUE); }
            if (colors.hasBlack()) { options.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (colors.hasRed())   { options.add("Red");   colorBytes.add(MagicColor.RED); }
            if (colors.hasGreen()) { options.add("Green"); colorBytes.add(MagicColor.GREEN); }
            options.add("Colorless"); colorBytes.add(MagicColor.COLORLESS);

            String context = message != null ? message : "Choose a color";
            if (c != null) {
                context = c.getName() + " - " + context;
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < colorBytes.size()) {
                return colorBytes.get(choice);
            }
            return colorBytes.get(0);
        } catch (Exception e) {
            return super.chooseColorAllowColorless(message, c, colors);
        }
    }


    // ---------------- chooseColors ----------------
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        try {
            // SHORT-CIRCUIT: if the number of available colors is ≤ the required
            // minimum, the player must take all of them. No real choice exists.
            if (options.countColors() <= min) {
                return options;
            }

            String gameState = serializeGameState();
            List<String> colorOptions = new ArrayList<>();
            List<Byte> colorBytes = new ArrayList<>();

            if (options.hasWhite()) { colorOptions.add("White"); colorBytes.add(MagicColor.WHITE); }
            if (options.hasBlue())  { colorOptions.add("Blue");  colorBytes.add(MagicColor.BLUE); }
            if (options.hasBlack()) { colorOptions.add("Black"); colorBytes.add(MagicColor.BLACK); }
            if (options.hasRed())   { colorOptions.add("Red");   colorBytes.add(MagicColor.RED); }
            if (options.hasGreen()) { colorOptions.add("Green"); colorBytes.add(MagicColor.GREEN); }

            if (colorOptions.isEmpty()) {
                return super.chooseColors(message, sa, min, max, options);
            }

            String context = (message != null ? message : "Choose colors")
                    + " (choose " + min + " to " + max + ")";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " - " + context;
                context += "\nFrom effect: " + sa.getDescription();
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
            return super.chooseColors(message, sa, min, max, options);
        }
    }

    // ===================================================================
    // DELEGATED TO FALLBACK AI - mechanical / trivial decisions
    // ===================================================================

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        try {
            if (abilities == null || abilities.isEmpty()) {
                return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
            }
            if (abilities.size() == 1) {
                return abilities.get(0);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (SpellAbility sa : abilities) {
                String desc = sa.getDescription();
                if (desc == null || desc.isEmpty()) {
                    desc = sa.toString();
                }
                options.add(hostCard.getName() + ": " + desc);
            }

            String context = "Choose which ability to use for " + hostCard.getName();
            if (triggerEvent != null) {
                context += " (triggered by: " + triggerEvent.toString() + ")";
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < abilities.size()) {
                return abilities.get(choice);
            }
            return abilities.get(0);
        } catch (Exception e) {
            return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        }
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
            }
            return true;
        }
        // If this is an X-cost spell and X isn't set yet, ask the LLM.
        if (sa.isSpell() && sa.costHasManaX() && sa.getXManaCostPaid() == null) {
            int min = 0;
            int max = ComputerUtilMana.determineLeftoverMana(sa, player, false);
            if (max > min) {
                Integer chosenX = announceRequirements(sa, min, max, "X");
                if (chosenX != null) {
                    sa.setSVar("PayX", chosenX.toString());
                    sa.setXManaCostPaid(chosenX);
                }
            }
        }

        if (sa.usesTargeting()) {
            sa.clearTargets();  // wipe any heuristic-set targets from candidate enumeration
            if (!chooseTargetsFor(sa)) return false;
        }

        SpellAbility sub = sa.getSubAbility();
        while (sub != null) {
            if (sub.usesTargeting() && (sub.getTargets() == null || sub.getTargets().isEmpty())) {
                chooseTargetsFor(sub);
            }
            sub = sub.getSubAbility();
        }
        ComputerUtil.handlePlayingSpellAbility(player, sa, null);
        return true;
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
        // Keep the hardcoded mana logic for specific APIs - LLM can't do mana math
        if (ability.getApi() != null) {
            switch (ability.getApi()) {
                case ChooseNumber:
                    String logic = ability.getParamOrDefault("AILogic", "");
                    if (logic.startsWith("MaxMana.") || logic.startsWith("PowerLeakMaxMana.")) {
                        return super.announceRequirements(ability, min, max, announce);
                    }
                    break;
                case BidLife:
                    break;
                default:
                    break;
            }
        }

        // For general X announcements, ask the LLM
        try {
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (int i = min; i <= Math.min(max, min + 20); i++) {
                options.add(String.valueOf(i));
            }

            String context = "Choose a number for " + ability.getHostCard().getName();
            if (announce != null) {
                context += " - " + announce;
            }
            context += "\nRange: " + min + " to " + max;
            context += "\nHigher usually means stronger effect but higher cost.";

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < options.size()) {
                return Integer.parseInt(options.get(choice));
            }
            return min;
        } catch (Exception e) {
            return super.announceRequirements(ability, min, max, announce);
        }
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                        CardCollectionView validTargets, String message) {
        try {
            if (validTargets.size() <= min) {
                return validTargets;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : validTargets) {
                String owner = c.getController() == player ? "(yours) " : "(opponent's) ";
                options.add(owner + cardToString(c));
            }

            String context = "Choose permanents to destroy"
                    + (message != null ? ": " + message : "")
                    + " (choose " + min + " to " + max + ")"
                    + "\nPrefer destroying opponent's best permanents. Avoid destroying your own.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + min + " to " + max + " permanents to destroy.");

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
            return super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
        }
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return super.chooseNewTargetsFor(ability, filter, optional);
    }

//    private void fireBecomesTargetTriggers(SpellAbility sa) {
//        if (sa == null || sa.getTargets() == null || sa.getTargets().isEmpty()) return;
//        for (GameObject tgt : sa.getTargets()) {
//            Map<AbilityKey, Object> runParams = AbilityKey.newMap();
//            runParams.put(AbilityKey.SourceSA, sa);
//            runParams.put(AbilityKey.Target, tgt);
//            getGame().getTriggerHandler().runTrigger(
//                    TriggerType.BecomesTarget, runParams, false);
//        }
//        // Also fire the "becomes target once" trigger (different event in Forge)
//        Map<AbilityKey, Object> onceParams = AbilityKey.newMap();
//        onceParams.put(AbilityKey.SourceSA, sa);
//        onceParams.put(AbilityKey.Targets, sa.getTargets());
//        getGame().getTriggerHandler().runTrigger(
//                TriggerType.BecomesTargetOnce, onceParams, false);
//    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {

        try {

            TargetRestrictions tgt = currentAbility.getTargetRestrictions();
            if (tgt == null) {
                return super.chooseTargetsFor(currentAbility);
            }

            if (currentAbility.getTargets() != null
                    && !currentAbility.getTargets().isEmpty()
                    && currentAbility.isTargetNumberValid()) {
                return true;
            }

            currentAbility.clearTargets();

            CardCollectionView validCards = CardUtil.getValidCardsToTarget(currentAbility);

            List<Player> validPlayers = new ArrayList<>();
            for (Player p : getGame().getPlayers()) {
                if (currentAbility.canTarget(p)) {
                    validPlayers.add(p);
                }
            }

            List<SpellAbility> validStackSAs = new ArrayList<>();
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                SpellAbility stackSa = si.getSpellAbility();
                if (currentAbility.canTarget(stackSa)) {
                    validStackSAs.add(stackSa);
                }
            }

            if (validCards.isEmpty() && validPlayers.isEmpty() && validStackSAs.isEmpty()) {
                return super.chooseTargetsFor(currentAbility);
            }

            // Build combined target list
            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            List<GameObject> targetObjects = new ArrayList<>();

            for (Card c : validCards) {
                String owner = c.getController() == player ? "(yours) " : "(opponent's) ";
                options.add(owner + cardToString(c));
                targetObjects.add(c);
            }
            for (Player p : validPlayers) {
                options.add(playerTag(p) + " (" + p.getLife() + " life)");
                targetObjects.add(p);
            }
            for (SpellAbility stackSa : validStackSAs) {
                Card host = stackSa.getHostCard();
                String ownerTag = playerTag(host.getController());
                options.add("SPELL ON STACK: " + host.getName() + " (cast by " + ownerTag + ")"
                        + " — " + stackSa.getDescription());
                targetObjects.add(stackSa);
            }

            if (options.isEmpty()) {
                return super.chooseTargetsFor(currentAbility);
            }

            int minTargets = tgt.getMinTargets(currentAbility.getHostCard(), currentAbility);
            int maxTargets = tgt.getMaxTargets(currentAbility.getHostCard(), currentAbility);

            boolean divided = currentAbility.isDividedAsYouChoose();
            int totalToDivide = divided ? currentAbility.getStillToDivide() : 0;

            StringBuilder ctxSb = new StringBuilder();
            ctxSb.append("Choose target for: ").append(saToString(currentAbility));
            ctxSb.append("\nMin targets: ").append(minTargets)
                    .append(", Max targets: ").append(maxTargets);
            if (divided) {
                ctxSb.append("\nDIVIDED AS YOU CHOOSE: distribute exactly ")
                        .append(totalToDivide)
                        .append(" among your chosen targets (each target gets at least 1).");
            }
            String context = ctxSb.toString();

            // ----- pick the targets (no allocation yet) -----
            List<GameObject> chosenTargets = new ArrayList<>();

            if (maxTargets == 1) {
                // Single target
                int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);
                if (choice >= 0 && choice < targetObjects.size()) {
                    chosenTargets.add(targetObjects.get(choice));
                }
            } else {
                // Multiple targets
                List<Integer> chosen = agent.chooseSubset(
                        gameState + "\nCONTEXT: " + context, options,
                        "Choose " + minTargets + " to " + maxTargets + " targets.");
                for (int idx : chosen) {
                    if (idx >= 0 && idx < targetObjects.size() && chosenTargets.size() < maxTargets) {
                        chosenTargets.add(targetObjects.get(idx));
                    }
                }
            }

            if (chosenTargets.size() < minTargets) {
                // Couldn't get enough targets - fall back
                currentAbility.clearTargets();
                return super.chooseTargetsFor(currentAbility);
            }

            // ----- commit targets (with divided allocation when applicable) -----
            if (divided) {
                int[] allocation = chooseDividedAllocation(
                        currentAbility, chosenTargets, totalToDivide, gameState);

                for (int i = 0; i < chosenTargets.size(); i++) {
                    GameObject t = chosenTargets.get(i);
                    if (t instanceof Card c) {
                        currentAbility.getTargets().add(c);
                    } else if (t instanceof Player p) {
                        currentAbility.getTargets().add(p);
                    } else if (t instanceof SpellAbility stackSa) {
                        currentAbility.getTargets().add(stackSa);
                    }
                    currentAbility.addDividedAllocation(t, allocation[i]);
                }

                // sanity: total must match
                if (currentAbility.getTotalDividedValue() != totalToDivide) {
                    currentAbility.clearTargets();
                    return super.chooseTargetsFor(currentAbility);
                }
            } else {
                for (GameObject t : chosenTargets) {
                    if (t instanceof Card c) {
                        currentAbility.getTargets().add(c);
                    } else if (t instanceof Player p) {
                        currentAbility.getTargets().add(p);
                    } else if (t instanceof SpellAbility stackSa) {
                        currentAbility.getTargets().add(stackSa);
                    }
                }
            }

            //fireBecomesTargetTriggers(currentAbility);

            return true;
        } catch (Exception e) {
            currentAbility.clearTargets();
            return super.chooseTargetsFor(currentAbility);
        }
    }

    /**
     * Ask the LLM how to split `total` among `targets`. Returns an int[] of
     * length targets.size() summing to `total`, with each entry >= 1.
     *
     * Falls back to an even split (with remainder on the first target) if the
     * LLM response is unparseable or invalid.
     */
    private int[] chooseDividedAllocation(SpellAbility sa, List<GameObject> targets,
                                          int total, String gameState) {
        int n = targets.size();
        int[] result = new int[n];

        // Trivial cases
        if (n == 1) {
            result[0] = total;
            return result;
        }
        // If total < n, we can't give each target >=1. The engine should have
        // prevented selecting this many targets, but guard anyway.
        if (total < n) {
            // Give 1 to the first `total` targets, 0 to the rest. This will
            // fail validation downstream and trigger the heuristic fallback,
            // which is the right behavior.
            for (int i = 0; i < n; i++) {
                result[i] = i < total ? 1 : 0;
            }
            return result;
        }

        // Build the prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n").append(gameState).append("\n\n");
        prompt.append("Distribute exactly ").append(total)
                .append(" among these ").append(n).append(" targets of ")
                .append(sa.getHostCard().getName()).append(" (")
                .append(sa.getDescription().replace("\\n", " ")).append(").\n");
        prompt.append("Each target must receive at least 1. The amounts must sum to exactly ")
                .append(total).append(".\n\n");
        prompt.append("TARGETS:\n");
        for (int i = 0; i < n; i++) {
            GameObject t = targets.get(i);
            String desc;
            if (t instanceof Card c) {
                String owner = c.getController() == player ? "(yours) " : "(opponent's) ";
                desc = owner + cardToStringCompact(c);
            } else if (t instanceof Player p) {
                desc = playerTag(p) + " (" + p.getLife() + " life)";
            } else {
                desc = t.toString();
            }
            prompt.append(i).append(": ").append(desc).append("\n");
        }
        prompt.append("\nRespond with ONLY ").append(n)
                .append(" comma-separated integers in target order, e.g. ");
        // Show an example: even split
        int base = total / n;
        int rem = total - base * n;
        StringBuilder example = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) example.append(",");
            example.append(base + (i < rem ? 1 : 0));
        }
        prompt.append(example).append(" (sums to ").append(total).append(").");

        String response = agent.chooseRaw(prompt.toString());

        // Parse: extract integers in order
        int[] parsed = new int[n];
        int idx = 0;
        Matcher m = Pattern.compile("-?\\d+").matcher(response);
        while (m.find() && idx < n) {
            try {
                parsed[idx++] = Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {}
        }

        boolean valid = (idx == n);
        if (valid) {
            int sum = 0;
            for (int v : parsed) {
                if (v < 1) { valid = false; break; }
                sum += v;
            }
            if (valid && sum != total) valid = false;
        }

        if (valid) {
            return parsed;
        }

        // Fallback: even split with remainder on first targets
        for (int i = 0; i < n; i++) {
            result[i] = base + (i < rem ? 1 : 0);
        }
        return result;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
                                                                    List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        try {
            if (allTargets.size() <= 1) {
                return allTargets.isEmpty() ? null : allTargets.get(0);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Pair<SpellAbilityStackInstance, GameObject> pair : allTargets) {
                SpellAbilityStackInstance si = pair.getLeft();
                GameObject target = pair.getRight();
                String desc = si.getSpellAbility().getHostCard().getName()
                        + " (cast by " + playerTag(si.getSpellAbility().getHostCard().getController()) + ")";
                if (target != null) {
                    desc += " targeting " + target.toString();
                }
                options.add(desc);
            }

            String context = "Choose a spell or ability on the stack to target";
            if (sa != null && sa.getHostCard() != null) {
                context += " (for " + sa.getHostCard().getName() + ")";
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < allTargets.size()) {
                return allTargets.get(choice);
            }
            return allTargets.get(0);
        } catch (Exception e) {
            return super.chooseTarget(sa, allTargets);
        }
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
        try {
            if (validMap.isEmpty()) {
                return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
            }

            String gameState = serializeGameState();
            StringBuilder context = new StringBuilder();
            context.append(title != null ? title : "Choose cards for effect");
            if (sa != null && sa.getHostCard() != null) {
                context.append(" (from ").append(sa.getHostCard().getName()).append(")");
            }
            context.append(isOptional ? "\nThis is optional - you may skip." : "");

            // Flatten all categories into one list with labels
            List<String> options = new ArrayList<>();
            List<Map.Entry<String, Card>> flatEntries = new ArrayList<>();

            if (isOptional) {
                options.add("NONE (skip, choose nothing)");
            }

            for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
                String category = entry.getKey();
                for (Card c : entry.getValue()) {
                    options.add("[" + category + "] " + cardToString(c));
                    flatEntries.add(Map.entry(category, c));
                }
            }

            if (options.isEmpty() || (isOptional && options.size() <= 1)) {
                return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
            }

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose one card from each category if required.");

            CardCollection result = new CardCollection();
            int offset = isOptional ? 1 : 0;
            for (int idx : chosen) {
                int adjustedIdx = idx - offset;
                if (adjustedIdx >= 0 && adjustedIdx < flatEntries.size()) {
                    result.add(flatEntries.get(adjustedIdx).getValue());
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
        }
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList,
                                                                  int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title,
                                                                  Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        try {
            if (optionList.size() <= min) {
                return new ArrayList<>(optionList);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (T entity : optionList) {
                if (entity instanceof Card c) {
                    String owner = c.getController() == player ? "(yours) " : "(opponent's) ";
                    options.add(owner + cardToString(c));
                } else if (entity instanceof Player p) {
                    String label = p == player ? "(you) " : "(opponent) ";
                    options.add(label + p.getName() + " (" + p.getLife() + " life)");
                } else {
                    options.add(entity.toString());
                }
            }

            String context = (title != null ? title : "Choose targets")
                    + " (choose " + min + " to " + max + ")";
            if (sa != null && sa.getHostCard() != null) {
                context += " for " + sa.getHostCard().getName();
            }

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + min + " to " + max + " targets.");

            List<T> result = new ArrayList<>();
            for (int idx : chosen) {
                if (idx >= 0 && idx < optionList.size() && result.size() < max) {
                    result.add(optionList.get(idx));
                }
            }

            while (result.size() < min && result.size() < optionList.size()) {
                for (T entity : optionList) {
                    if (!result.contains(entity) && result.size() < min) {
                        result.add(entity);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        }
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells,
                                                            SpellAbility sa, String title, int num, Map<String, Object> params) {
        try {
            if (spells.size() <= num) {
                return spells;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (SpellAbility spell : spells) {
                Card host = spell.getHostCard();
                String desc = host.getName();
                String spellDesc = spell.getDescription();
                if (spellDesc != null && !spellDesc.isEmpty()) {
                    desc += ": " + spellDesc;
                }
                desc += " (controlled by " + playerTag(host.getController()) + ")";
                options.add(desc);
            }

            String context = (title != null ? title : "Choose spell abilities")
                    + " (choose " + num + ")";
            if (sa != null && sa.getHostCard() != null) {
                context += " for " + sa.getHostCard().getName();
            }

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose exactly " + num + " spell abilities.");

            List<SpellAbility> result = new ArrayList<>();
            for (int idx : chosen) {
                if (idx >= 0 && idx < spells.size() && result.size() < num) {
                    result.add(spells.get(idx));
                }
            }

            while (result.size() < num && result.size() < spells.size()) {
                for (SpellAbility spell : spells) {
                    if (!result.contains(spell) && result.size() < num) {
                        result.add(spell);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        }
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode,
                                    String string, int bid, Player winner) {
        return super.confirmBidAction(sa, mode, string, bid, winner);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect,
                                            SpellAbility effectSA, GameEntity affected, String question) {
        try {
            String gameState = serializeGameState();
            String fullQuestion = question != null ? question : "Apply replacement effect?";
            if (effectSA != null && effectSA.getHostCard() != null) {
                fullQuestion = effectSA.getHostCard().getName() + " - " + fullQuestion;
            }
            if (affected != null) {
                fullQuestion += "\nAffected: " + affected.toString();
            }
            return agent.chooseYesNo(gameState, fullQuestion);
        } catch (Exception e) {
            return super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
        }
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode,
                                            String message, String logic) {
        return super.confirmStaticApplication(hostCard, mode, message, logic);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        try {
            String gameState = serializeGameState();
            String question = prompt != null ? prompt : "Pay this cost?";
            if (sa != null && sa.getHostCard() != null) {
                question = sa.getHostCard().getName() + " - " + question;
            }
            if (costPart != null) {
                question += "\nCost: " + costPart.toString();
            }
            return agent.chooseYesNo(gameState, question);
        } catch (Exception e) {
            return super.confirmPayment(costPart, prompt, sa);
        }
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
        try {
            if (blockers.size() <= 1) {
                return blockers;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card b : blockers) {
                options.add(cardToStringCompact(b));
            }

            String context = "Order blockers for your attacker: " + cardToStringCompact(attacker)
                    + "\nDamage is assigned in this order - first blocker takes damage first."
                    + "\nPut the creature you most want to kill first.";

            List<Integer> ordering = agent.chooseOrdering(
                    gameState + "\nCONTEXT: " + context, options,
                    "Order blockers (first = takes damage first).");

            CardCollection result = new CardCollection();
            Set<Integer> used = new HashSet<>();
            for (int idx : ordering) {
                if (idx >= 0 && idx < blockers.size() && !used.contains(idx)) {
                    result.add(blockers.get(idx));
                    used.add(idx);
                }
            }
            for (int i = 0; i < blockers.size(); i++) {
                if (!used.contains(i)) {
                    result.add(blockers.get(i));
                }
            }
            return result;
        } catch (Exception e) {
            return super.orderBlockers(attacker, blockers);
        }
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        try {
            if (oldBlockers.isEmpty()) {
                CardCollection result = new CardCollection();
                result.add(blocker);
                return result;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            // Show current order with the new blocker needing to be inserted
            for (int i = 0; i <= oldBlockers.size(); i++) {
                StringBuilder desc = new StringBuilder("Insert " + cardToStringCompact(blocker) + " at position " + i + ": [");
                for (int j = 0; j < oldBlockers.size() + 1; j++) {
                    if (j == i) {
                        desc.append(">>").append(blocker.getName()).append("<<, ");
                    }
                    if (j < oldBlockers.size()) {
                        desc.append(oldBlockers.get(j).getName()).append(", ");
                    }
                }
                desc.append("]");
                options.add(desc.toString());
            }

            String context = "Your attacker: " + cardToStringCompact(attacker)
                    + "\nWhere should " + cardToStringCompact(blocker) + " be in the damage order?"
                    + "\nEarlier position = takes damage first.";

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            CardCollection result = new CardCollection(oldBlockers);
            if (choice >= 0 && choice <= result.size()) {
                result.add(choice, blocker);
            } else {
                result.add(blocker);
            }
            return result;
        } catch (Exception e) {
            return super.orderBlocker(attacker, blocker, oldBlockers);
        }
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        try {
            if (attackers.size() <= 1) {
                return attackers;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card a : attackers) {
                options.add(cardToStringCompact(a));
            }

            String context = "Order attackers for your blocker: " + cardToStringCompact(blocker)
                    + "\nYour blocker assigns damage in this order - first attacker takes damage first."
                    + "\nPut the creature you most want to kill first.";

            List<Integer> ordering = agent.chooseOrdering(
                    gameState + "\nCONTEXT: " + context, options,
                    "Order attackers (first = takes damage first).");

            CardCollection result = new CardCollection();
            Set<Integer> used = new HashSet<>();
            for (int idx : ordering) {
                if (idx >= 0 && idx < attackers.size() && !used.contains(idx)) {
                    result.add(attackers.get(idx));
                    used.add(idx);
                }
            }
            for (int i = 0; i < attackers.size(); i++) {
                if (!used.contains(i)) {
                    result.add(attackers.get(i));
                }
            }
            return result;
        } catch (Exception e) {
            return super.orderAttackers(blocker, attackers);
        }
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
        try {
            String gameState = serializeGameState();
            String question = "Put " + c.getName() + " on top of your library?"
                    + "\nYES = draw it next turn, NO = leave it where it is (usually bottom/graveyard).";
            return agent.chooseYesNo(gameState, question);
        } catch (Exception e) {
            return super.willPutCardOnTop(c);
        }
    }
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards,
                                                  ZoneType destinationZone, SpellAbility source) {
        try {
            if (cards.size() <= 1) {
                return super.orderMoveToZoneList(cards, destinationZone, source);
            }

            if (destinationZone == ZoneType.Graveyard || destinationZone == ZoneType.Exile) {
                return cards;
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
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand,
                                                             String[] uTypes, SpellAbility sa) {
        try {
            String typesDesc = String.join(" or ", uTypes);

            // Bucket the hand
            List<Card> cardsOfType = new ArrayList<>();
            for (Card c : hand) {
                for (String type : uTypes) {
                    if (c.getType().hasStringType(type)) {
                        cardsOfType.add(c);
                        break;
                    }
                }
            }

            String gameState = serializeGameState();

            // ---- Path A: no card of the required type — must discard N ----
            if (cardsOfType.isEmpty()) {
                List<String> options = new ArrayList<>();
                for (Card c : hand) {
                    options.add(c.isLand()
                            ? c.getName()
                            : c.getName() + " " + c.getManaCost() + " - " + c.getOracleText());
                }
                String ctx = "No " + typesDesc + " in hand. You must discard " + num
                        + " card(s). Choose your worst cards.";
                List<Integer> chosen = agent.chooseSubset(
                        gameState + "\nCONTEXT: " + ctx, options,
                        "Choose " + num + " card(s) to discard.");

                return buildDiscardResult(chosen, hand, num);
            }

            // ---- Path B: choice between discarding 1 type-match OR N others ----
            // Build a unified action list:
            //   0..K-1  -> discard this single type-matching card
            //   K       -> discard N cards (LLM will be re-prompted for which)
            List<String> options = new ArrayList<>();
            for (Card c : cardsOfType) {
                options.add("Discard 1 " + typesDesc + ": " + c.getName() + " " + c.getManaCost()
                        + " - " + c.getOracleText());
            }
            options.add("Instead discard " + num + " other card(s) (you'll choose which)");

            String ctx = "Choose how to pay: discard ONE " + typesDesc
                    + " card, OR discard " + num + " cards of any type."
                    + " Compare the value of your best " + typesDesc
                    + " against the " + num + " worst cards you'd otherwise have to pitch.";

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + ctx, options);

            // LLM picked "discard N others"
            if (choice == cardsOfType.size()) {
                List<String> discardOptions = new ArrayList<>();
                for (Card c : hand) {
                    discardOptions.add(c.isLand()
                            ? c.getName()
                            : c.getName() + " " + c.getManaCost() + " - " + c.getOracleText());
                }
                String ctx2 = "You chose to discard " + num + " card(s) instead of a "
                        + typesDesc + ". Pick your worst " + num + " cards.";
                List<Integer> chosen = agent.chooseSubset(
                        gameState + "\nCONTEXT: " + ctx2, discardOptions,
                        "Choose " + num + " card(s) to discard.");
                return buildDiscardResult(chosen, hand, num);
            }

            // LLM picked a specific type-matching card
            if (choice >= 0 && choice < cardsOfType.size()) {
                return new CardCollection(cardsOfType.get(choice));
            }

            // Out of range — fall back to discarding the first type-match
            return new CardCollection(cardsOfType.get(0));

        } catch (Exception e) {
            return super.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa);
        }
    }

    /** Helper: convert a subset of indices into a CardCollection, padding to num. */
    private CardCollection buildDiscardResult(List<Integer> chosen, CardCollectionView hand, int num) {
        CardCollection result = new CardCollection();
        for (int idx : chosen) {
            if (idx >= 0 && idx < hand.size() && result.size() < num) {
                result.add(hand.get(idx));
            }
        }
        int safety = hand.size() * 2;
        while (result.size() < num && result.size() < hand.size() && safety-- > 0) {
            for (Card c : hand) {
                if (!result.contains(c)) {
                    result.add(c);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        try {
            if (grave.isEmpty() || genericAmount <= 0) {
                return super.chooseCardsToDelve(genericAmount, grave);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : grave) {
                options.add(c.getName());
            }

            String context = "Delve: exile cards from your graveyard to pay for generic mana."
                    + "\nYou need up to " + genericAmount + " generic mana. Each card exiled pays {1}."
                    + "\nExile cards you don't need for graveyard synergies (flashback, recursion)."
                    + "\nKeep cards you might want to bring back later.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose up to " + genericAmount + " cards to exile for delve.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < grave.size() && result.size() < genericAmount) {
                    result.add(grave.get(idx));
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseCardsToDelve(genericAmount, grave);
        }
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
        if (canSetupTargets) {
            if (effectSA.usesTargeting()) {
                effectSA.clearTargets();
                chooseTargetsFor(effectSA);
            }
            SpellAbility sub = effectSA.getSubAbility();
            while (sub != null) {
                if (sub.usesTargeting()) {
                    sub.clearTargets();
                    chooseTargetsFor(sub);
                }
                sub = sub.getSubAbility();
            }
        }
        super.playSpellAbilityNoStack(effectSA, canSetupTargets);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        try {
            if (activePlayerSAs.size() <= 1) {
                return activePlayerSAs;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (SpellAbility sa : activePlayerSAs) {
                String desc = sa.getHostCard().getName();
                String saDesc = sa.getStackDescription();
                if (saDesc != null && !saDesc.isEmpty()) {
                    desc += ": " + saDesc;
                }
                options.add(desc);
            }

            String context = "Multiple abilities are triggering simultaneously."
                    + "\nOrder them - first in the list resolves LAST (stack order)."
                    + "\nPut the ability you want to resolve first at the END.";

            List<Integer> ordering = agent.chooseOrdering(
                    gameState + "\nCONTEXT: " + context, options,
                    "Order these simultaneous abilities (first = resolves last).");

            List<SpellAbility> result = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            for (int idx : ordering) {
                if (idx >= 0 && idx < activePlayerSAs.size() && !used.contains(idx)) {
                    result.add(activePlayerSAs.get(idx));
                    used.add(idx);
                }
            }
            for (int i = 0; i < activePlayerSAs.size(); i++) {
                if (!used.contains(i)) {
                    result.add(activePlayerSAs.get(i));
                }
            }
            return result;
        } catch (Exception e) {
            return super.orderSimultaneousSa(activePlayerSAs);
        }
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        for (final SpellAbility sa : orderSimultaneousSa(activePlayerSAs)) {
            if (sa.isTrigger() && !sa.isCopied()) {
                // Replicate prepareSingleSa, but without brains.doTrigger
                if (prepareTriggerSa(sa.getHostCard(), sa, true)) {
                    ComputerUtil.playStack(sa, player, getGame());
                }
            } else {
                // Copied-spell branch: defer to parent (no LLM routing here)
                // by handing it back through a single-element list.
                super.orderAndPlaySimultaneousSa(java.util.List.of(sa));
            }
        }
    }

    private boolean prepareTriggerSa(Card host, SpellAbility sa, boolean isMandatory) {
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return true;
            }
            sa = sa.getSubAbility();
        }

        // TargetingPlayer: same as parent — let the targeted player's controller pick
        if (sa.hasParam("TargetingPlayer")) {
            Player targetingPlayer = AbilityUtils.getDefinedPlayers(
                    host, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        }

        // The key change: route target selection through chooseTargetsFor (LLM-routed)
        // instead of brains.doTrigger (which picks targets via heuristic).
        if (sa.usesTargeting()) {
            sa.clearTargets();
            if (!chooseTargetsFor(sa)) {
                return false;
            }
        }
        SpellAbility sub = sa.getSubAbility();
        while (sub != null) {
            if (sub.usesTargeting()) {
                sub.clearTargets();
                chooseTargetsFor(sub);
            }
            sub = sub.getSubAbility();
        }
        return true;
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {

        SpellAbility sa = wrapperAbility.getWrappedAbility();

        // For optional triggers, ask LLM first whether to use it at all
        if (!isMandatory) {
            if (!confirmTrigger(wrapperAbility)) return false;
        }


        // Handle Charm
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
            }
            sa = sa.getSubAbility();
        }

        // Handle TargetingPlayer
        if (sa.hasParam("TargetingPlayer")) {
            Player targetingPlayer = AbilityUtils.getDefinedPlayers(host, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            targetingPlayer.getController().chooseTargetsFor(sa);
            return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
        }

        // Now set up targets via LLM (chooseTargetsFor routes to your LLM override)
        if (sa.usesTargeting()
                && (sa.getTargets() == null || sa.getTargets().isEmpty() || !sa.isTargetNumberValid())) {
            if (!chooseTargetsFor(sa)) {
                if (isMandatory) {
                    return super.playTrigger(host, wrapperAbility, true);
                }
                return false;
            }
        }

        // Handle sub-abilities that need targets
        SpellAbility sub = sa.getSubAbility();
        while (sub != null) {
            if (sub.usesTargeting()
            && (sub.getTargets() == null || sub.getTargets().isEmpty() || !sub.isTargetNumberValid())) {
                if (!chooseTargetsFor(sub)) {
                    super.chooseTargetsFor(sub);
                }
            }
            sub = sub.getSubAbility();
        }

        // Play the trigger
        return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
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
        try {
            if (options.size() <= 1 && !optional) {
                return options.isEmpty() ? null : options.get(0);
            }

            String gameState = serializeGameState();
            List<String> optionDescs = new ArrayList<>();
            for (Object opt : options) {
                String desc = opt.toString();
                // Show who already voted for what
                if (votes.containsKey(opt) && !votes.get(opt).isEmpty()) {
                    desc += " (voted by: ";
                    List<String> voterNames = new ArrayList<>();
                    for (Player p : votes.get(opt)) {
                        voterNames.add(p.getName());
                    }
                    desc += String.join(", ", voterNames) + ")";
                }
                optionDescs.add(desc);
            }

            String context = sa.getHostCard().getName() + " - " + prompt
                    + (optional ? "\nVoting is optional - you may abstain." : "");

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, optionDescs);

            if (choice >= 0 && choice < options.size()) {
                return options.get(choice);
            }
            return options.get(0);
        } catch (Exception e) {
            return super.vote(sa, prompt, options, votes, forPlayer, optional);
        }
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        try {
            if (cardsToReturn <= 0 || hand.isEmpty()) {
                return super.tuckCardsViaMulligan(hand, cardsToReturn);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : hand) {
                options.add(cardToString(c));
            }

            String context = "Mulligan: put " + cardsToReturn + " card(s) from your hand on the bottom of your library."
                    + "\nKeep your best cards - put back lands if you have too many, or expensive spells you can't cast early.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + cardsToReturn + " card(s) to put back.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < hand.size() && result.size() < cardsToReturn) {
                    result.add(hand.get(idx));
                }
            }

            while (result.size() < cardsToReturn) {
                for (Card c : hand) {
                    if (!result.contains(c)) {
                        result.add(c);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
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
        // Predicate-based - potentially huge list. Heuristic handles better.
        return super.chooseSingleCardFace(sa, message, cpp, name);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        try {
            if (faces.size() <= 1) {
                return faces.isEmpty() ? null : faces.get(0);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (ICardFace face : faces) {
                options.add(face.getName());
            }

            String context = message != null ? message : "Choose a card face";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " - " + context;
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < faces.size()) {
                return faces.get(choice);
            }
            return faces.get(0);
        } catch (Exception e) {
            return super.chooseSingleCardFace(sa, faces, message);
        }
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states,
                                           String message, Map<String, Object> params) {
        try {
            if (states.size() <= 1) {
                return states.isEmpty() ? null : states.get(0);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (CardState state : states) {
                options.add(state.getName() + " - " + state.getType());
            }

            String context = message != null ? message : "Choose a card state";
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " - " + context;
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < states.size()) {
                return states.get(choice);
            }
            return states.get(0);
        } catch (Exception e) {
            return super.chooseSingleCardState(sa, states, message, params);
        }
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
            return super.chooseCardsPile(sa, pile1, pile2, faceUp);
        }
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa,
                                         String prompt, Map<String, Object> params) {
        try {
            if (options.size() <= 1) {
                return options.isEmpty() ? null : options.get(0);
            }

            String gameState = serializeGameState();
            List<String> optionDescs = new ArrayList<>();
            for (CounterType ct : options) {
                optionDescs.add(ct.toString());
            }

            String context = (prompt != null ? prompt : "Choose a counter type");
            if (sa != null && sa.getHostCard() != null) {
                context = sa.getHostCard().getName() + " - " + context;
            }

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, optionDescs);

            if (choice >= 0 && choice < options.size()) {
                return options.get(choice);
            }
            return options.get(0);
        } catch (Exception e) {
            return super.chooseCounterType(options, sa, prompt, params);
        }
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
                    + " - Choose a keyword to give to " + cardToStringCompact(tgtCard) +
                    " (has: " + tgtCard.getKeywords() + ")"
                    + (prompt != null ? "\n" + prompt : "");

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);

            if (choice >= 0 && choice < options.size()) {
                return options.get(choice);
            }
            return options.get(0);
        } catch (Exception e) {
            return super.chooseKeywordForPump(options, sa, prompt, tgtCard);
        }
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return super.chooseSingleReplacementEffect(possibleReplacers);
    }

//    @Override
//    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
//        // Match heuristic AI behavior: empty/single → return first (no choice to make).
//        // Only consult LLM when there's a real choice between competing replacements.
//        if (possibleReplacers == null || possibleReplacers.isEmpty()) {
//            return null;  // matches what list.get(0) would do on empty (NPE-safe variant)
//        }
//        if (possibleReplacers.size() == 1) {
//            return possibleReplacers.get(0);
//        }
//
//        try {
//            String gameState = serializeGameState();
//            List<String> options = new ArrayList<>();
//            for (ReplacementEffect re : possibleReplacers) {
//                Card host = re.getHostCard();
//                String hostName = host != null ? host.getName() : "unknown";
//                String desc = re.getDescription();
//                if (desc == null || desc.isEmpty()) desc = re.toString();
//                options.add(hostName + " - " + desc);
//            }
//
//            String context = "Chose which replacement effect to apply.";
//
//            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);
//            if (choice >= 0 && choice < possibleReplacers.size()) {
//                return possibleReplacers.get(choice);
//            }
//            return possibleReplacers.get(0);
//        } catch (Exception e) {
//            System.err.println("[ExternalAI] chooseSingleReplacementEffect FELL BACK: " + e);
//            e.printStackTrace();
//            return super.chooseSingleReplacementEffect(possibleReplacers);
//        }
//    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleStatics) {
        return super.chooseSingleStaticAbility(possibleStatics);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        try {
            if (choices.size() <= 1) {
                return choices.isEmpty() ? null : choices.get(0);
            }

            String gameState = serializeGameState();

            String context = sa.getHostCard().getName() + " - Choose protection type."
                    + "\nPick the color or type that blocks the most threats on the board."
                    + "\nProtection prevents damage, blocking, targeting, and enchanting from that source.";

            int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, choices);

            if (choice >= 0 && choice < choices.size()) {
                return choices.get(choice);
            }
            return choices.get(0);
        } catch (Exception e) {
            return super.chooseProtectionType(sa, choices);
        }
    }

    // =====================================================================
// PATCH for PlayerControllerExternal.java
//
// Adds:
//   1. enumerateHybridResolutions(ManaCost) — produces every concrete
//      monocolored resolution of a cost containing hybrid pips, deduplicated.
//   2. shardToConcreteAlternatives(ManaCostShard) — per-shard alternative
//      list (the inner combinatoric step).
//   3. payManaCost override — consults the LLM when the spell tracks
//      which colors are spent, then forces the chosen concrete cost.
//
// Required imports:
//   import forge.card.mana.ManaCost;
//   import forge.card.mana.ManaCostParser;
//   import forge.card.mana.ManaCostShard;
//   import forge.card.MagicColor;            // already present
//   (Cost is already imported. ManaConversionMatrix, ManaCostBeingPaid
//    are already imported.)
// =====================================================================


    // ---------------- payManaCost override ----------------
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
                               String prompt, ManaConversionMatrix matrix, boolean effect) {

        // Only diverge from the heuristic when (a) the spell cares which colors
        // were spent, and (b) the cost actually has a hybrid pip to choose between.
        if (sa != null && sa.tracksManaSpent() && costHasHybrid(toPay)) {
            try {
                List<ManaCost> variants = enumerateHybridResolutions(toPay);

                // Keep only resolutions the player can actually afford right now.
                List<ManaCost> payable = new ArrayList<>();
                for (ManaCost v : variants) {
                    try {
                        ManaCostBeingPaid probe = new ManaCostBeingPaid(v);
                        boolean ok = ComputerUtilMana.canPayManaCost(probe, sa, player, effect);
                        if (ok) payable.add(v);
                    } catch (Exception inner) {
                        inner.printStackTrace();
                    }
                }
                System.err.println("[ExternalAI payManaCost] payable.size=" + payable.size());

                if (payable.size() > 1) {
                    String gameState = serializeGameState();
                    List<String> options = new ArrayList<>();
                    for (ManaCost v : payable) {
                        options.add(v.toString());
                    }
                    String context = "Casting " + sa.getHostCard().getName()
                            + " with cost " + toPay
                            + ". This spell's effect depends on which colors are spent."
                            + "\nText: " + sa.getHostCard().getOracleText().replace("\\n", " ")
                            + "\nChoose how the hybrid pips should resolve.";
                    int choice = agent.chooseAction(gameState + "\nCONTEXT: " + context, options);
                    if (choice >= 0 && choice < payable.size()) {
                        ManaCost chosen = payable.get(choice);
                        return ComputerUtilMana.payManaCost(
                                new Cost(chosen, effect), player, sa, effect);
                    }
                }
            } catch (Exception e) {
                System.err.println("[ExternalAI] payManaCost hybrid override fell back: " + e);
            }
        }

        return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }

    /** True iff the cost string contains a "/" — i.e. at least one hybrid shard. */
    private static boolean costHasHybrid(ManaCost cost) {
        if (cost == null) return false;
        for (ManaCostShard s : cost) {
            if (s.isOr2Generic() || s.isPhyrexian()) {
                return true;
            }
            // Monochrome shards have exactly one bit set in the color mask;
            // hybrids have two.
            byte mask = s.getColorMask();
            if (mask != 0 && Integer.bitCount(mask) >= 2) {
                return true;
            }
        }
        return false;
    }


    private static List<ManaCost> enumerateHybridResolutions(ManaCost cost) {
        List<ManaCost> result = new ArrayList<>();
        if (cost == null) return result;

        // Per-shard list of textual alternatives (e.g. {U/B} -> ["U", "B"]).
        // Each inner list has at least one entry.
        List<List<String>> perShard = new ArrayList<>();
        for (ManaCostShard s : cost) {
            List<String> alts = shardToConcreteAlternatives(s);
            if (alts.isEmpty()) {
                // Unknown shard type — keep its raw form, no expansion.
                alts = List.of(s.toString().replace("{", "").replace("}", ""));
            }
            perShard.add(alts);
        }

        // Cartesian product. Bail out if it would explode.
        long total = 1;
        for (List<String> alts : perShard) {
            total *= alts.size();
            if (total > 64) return result;  // caller falls back
        }

        int n = perShard.size();
        int[] idx = new int[n];
        Set<String> seenCanonical = new HashSet<>();

        while (true) {
            // Build the cost string for this combination
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(" ");
                sb.append(perShard.get(i).get(idx[i]));
            }
            String raw = sb.toString();

            // Canonicalize: sort the colored pips so UB and BU collapse.
            // Generic numbers and X are kept positionally at the start.
            String canonical = canonicalizeCostString(raw);

            if (seenCanonical.add(canonical)) {
                try {
                    result.add(new ManaCost(new ManaCostParser(canonical)));
                } catch (Exception ignored) {
                    // skip unparseable
                }
            }

            // Increment counter (least significant digit first)
            int k = n - 1;
            while (k >= 0) {
                idx[k]++;
                if (idx[k] < perShard.get(k).size()) break;
                idx[k] = 0;
                k--;
            }
            if (k < 0) break;
        }

        return result;
    }

    private static List<String> shardToConcreteAlternatives(ManaCostShard s) {
        List<String> alts = new ArrayList<>();
        if (s == null) return alts;

        String raw = s.toString();  // e.g. "{U}", "{U/B}", "{2/U}", "{X}"
        String inner = raw.startsWith("{") && raw.endsWith("}")
                ? raw.substring(1, raw.length() - 1)
                : raw;

        // X, Y, Z and other variable cost letters: keep as-is
        if (inner.equals("X") || inner.equals("Y") || inner.equals("Z")) {
            alts.add(inner);
            return alts;
        }

        // Generic numeric: keep as-is (we don't enumerate within generic mana)
        if (inner.matches("\\d+")) {
            alts.add(inner);
            return alts;
        }

        // Phyrexian {U/P}: treat as the color side (don't enumerate life payment here)
        if (s.isPhyrexian()) {
            // Extract the color from before the slash
            String colorPart = inner.split("/")[0];
            alts.add(colorPart);
            return alts;
        }

        // {2/Color} hybrid: two alternatives - "2" or the colored pip
        if (s.isOr2Generic()) {
            String[] parts = inner.split("/");
            if (parts.length == 2) {
                alts.add(parts[0]);   // "2"
                alts.add(parts[1]);   // color
                return alts;
            }
        }

        // Two-color hybrid {U/B}: split on slash
        if (inner.contains("/")) {
            for (String part : inner.split("/")) {
                alts.add(part);
            }
            return alts;
        }

        // Plain monochrome {U}/{B}/{C}/etc.
        alts.add(inner);
        return alts;
    }

    private static String canonicalizeCostString(String raw) {
        int genericTotal = 0;
        List<String> variables = new ArrayList<>();
        List<String> colors = new ArrayList<>();

        for (String token : raw.split(" ")) {
            if (token.isEmpty()) continue;
            if (token.matches("\\d+")) {
                try { genericTotal += Integer.parseInt(token); } catch (NumberFormatException ignored) {}
            } else if (token.equals("X") || token.equals("Y") || token.equals("Z")) {
                variables.add(token);
            } else {
                colors.add(token);
            }
        }

        colors.sort(Comparator.comparingInt(s -> {
            if (s.isEmpty()) return 99;
            int idx = "WUBRGC".indexOf(s.charAt(0));
            return idx < 0 ? 99 : idx;
        }));

        List<String> tokens = new ArrayList<>();
        if (genericTotal > 0) tokens.add(String.valueOf(genericTotal));
        tokens.addAll(variables);
        tokens.addAll(colors);

        return tokens.isEmpty() ? "0" : String.join(" ", tokens);
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
        try {
            if (optionList.size() <= amount && !isOptional) {
                return optionList;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            if (isOptional) {
                options.add("NONE (don't pay this cost)");
            }
            for (Card c : optionList) {
                String owner = c.getController() == player ? "(yours) " : "";
                options.add(owner + cardToString(c));
            }

            String context = "Pay cost for " + sa.getHostCard().getName()
                    + ": " + (prompt != null ? prompt : cpl.toString())
                    + "\nChoose " + amount + " card(s). Pick your least valuable.";

            int offset = isOptional ? 1 : 0;

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + amount + " card(s) to pay the cost.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                int adjustedIdx = idx - offset;
                if (adjustedIdx >= 0 && adjustedIdx < optionList.size() && result.size() < amount) {
                    result.add(optionList.get(adjustedIdx));
                }
            }

            // If optional and model chose NONE (index 0), return empty
            if (isOptional && !chosen.isEmpty() && chosen.get(0) == 0) {
                return new CardCollection();
            }

            while (result.size() < amount && !isOptional && result.size() < optionList.size()) {
                for (Card c : optionList) {
                    if (!result.contains(c) && result.size() < amount) {
                        result.add(c);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
        }
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability,
                                                      boolean effect, String prompt) {
        return super.getCostDecisionMaker(player, ability, effect, prompt);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
                                          FCollectionView<Player> allPayers) {
        try {
            String gameState = serializeGameState();
            String hostName = sa != null && sa.getHostCard() != null
                    ? sa.getHostCard().getName() : "an effect";
            String question = hostName + " - pay " + cost + " to prevent effect? - "
                    + (sa != null ? sa.getStackDescription() : "");

            boolean wantsToPay = agent.chooseYesNo(gameState, question);
            if (!wantsToPay) {
                return false;
            }

            // LLM said yes — now actually pay the cost
            if (!ComputerUtilCost.canPayCost(cost, sa, player, true)) {
                return false;  // can't afford, even though we'd like to
            }
            final CostPayment pay = new CostPayment(cost, sa);
            return pay.payComputerCosts(new AiCostDecision(player, sa, true));

        } catch (Exception e) {
            System.err.println("[ExternalAI] payCostToPreventEffect FELL BACK: " + e);
            e.printStackTrace();
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
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
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList,
                    delayedReveal, selectPrompt, isOptional, decider);
        }
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                               SpellAbility sa, CardCollection fetchList, int min, int max,
                                               DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        try {
            if (fetchList.isEmpty()) return new ArrayList<>();
            if (fetchList.size() <= min) return new ArrayList<>(fetchList);

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : fetchList) {
                if (c.isLand()) {
                    options.add(c.getName());
                } else {
                    options.add(c.getName() + " " + c.getManaCost() + " - " + c.getOracleText());
                }
            }

            String context = (selectPrompt != null ? selectPrompt : "Choose cards")
                    + " (choose " + min + " to " + max + ")"
                    + "\nDestination: " + destination.toString();
            if (sa != null && sa.getHostCard() != null) {
                context += " (from " + sa.getHostCard().getName() + ")";
            }

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + min + " to " + max + " cards.");

            List<Card> result = new ArrayList<>();
            for (int idx : chosen) {
                if (idx >= 0 && idx < fetchList.size() && result.size() < max) {
                    result.add(fetchList.get(idx));
                }
            }

            while (result.size() < min && result.size() < fetchList.size()) {
                for (Card c : fetchList) {
                    if (!result.contains(c) && result.size() < min) {
                        result.add(c);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
                    delayedReveal, selectPrompt, decider);
        }
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        super.notifyOfValue(saSource, realtedTarget, value);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        try {
            if (valid.size() <= min) {
                return valid;
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (Card c : valid) {
                if (c.isLand()) {
                    options.add(c.getName());
                } else {
                    options.add(c.getName() + " " + c.getManaCost());
                }
            }

            String context = "Choose " + min + " to " + max + " card(s) to reveal from your hand."
                    + "\nRevealing gives your opponent information - reveal cards they already know about"
                    + " or cards that matter least to keep hidden.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose " + min + " to " + max + " card(s) to reveal.");

            CardCollection result = new CardCollection();
            for (int idx : chosen) {
                if (idx >= 0 && idx < valid.size() && result.size() < max) {
                    result.add(valid.get(idx));
                }
            }

            while (result.size() < min && result.size() < valid.size()) {
                for (Card c : valid) {
                    if (!result.contains(c) && result.size() < min) {
                        result.add(c);
                        break;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseCardsToRevealFromHand(min, max, valid);
        }
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        try {
            if (usableFromOpeningHand.isEmpty()) {
                return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
            }

            String gameState = serializeGameState();
            List<String> options = new ArrayList<>();
            for (SpellAbility sa : usableFromOpeningHand) {
                String desc = sa.getHostCard().getName();
                String saDesc = sa.getDescription();
                if (saDesc != null && !saDesc.isEmpty()) {
                    desc += ": " + saDesc;
                }
                options.add(desc);
            }

            String context = "You may activate these abilities from your opening hand before the game starts."
                    + "\nChoose which to activate, or NONE to skip.";

            List<Integer> chosen = agent.chooseSubset(
                    gameState + "\nCONTEXT: " + context, options,
                    "Choose abilities to activate from opening hand, or NONE.");

            List<SpellAbility> result = new ArrayList<>();
            for (int idx : chosen) {
                if (idx >= 0 && idx < usableFromOpeningHand.size()) {
                    result.add(usableFromOpeningHand.get(idx));
                }
            }

            return result;
        } catch (Exception e) {
            return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
        }
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

    private boolean canAffordOptionalCost(SpellAbility sa, OptionalCostValue ocv) {
        try {
            Cost combined = sa.getPayCosts().copy();
            combined.add(ocv.getCost());
            combined = CostAdjustment.adjust(combined, sa, false);
            SpellAbility probe = sa.copy();
            probe.setPayCosts(combined);
            probe.setActivatingPlayer(player);
            return ComputerUtilCost.canPayCost(probe, player, false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                       List<OptionalCostValue> optionalCostValues) {
        // Decision is made at action-selection time now. Echo only what's marked,
        // never prompt - prevents speculative instant-speed kicker questions.
        List<OptionalCostValue> result = new ArrayList<>();
        for (OptionalCostValue ocv : optionalCostValues) {
            if (chosen.isOptionalCostPaid(ocv.getType())) {
                result.add(ocv);
            }
        }
        return result;
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