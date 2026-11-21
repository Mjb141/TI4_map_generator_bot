package ti4.service.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.apache.commons.lang3.function.Consumers;
import org.jetbrains.annotations.NotNull;
import ti4.discord.interactions.buttons.Buttons;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Constants;
import ti4.helpers.DiceHelper.Die;
import ti4.helpers.FoWHelper;
import ti4.helpers.RandomHelper;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.model.BreakthroughModel;
import ti4.model.TechnologyModel.TechnologyType;
import ti4.model.TileHelper;
import ti4.model.TileModel;
import ti4.service.breakthrough.AlRaithService;
import ti4.service.emoji.DiceEmojis;
import ti4.service.fow.GMService;
import ti4.service.rules.ThundersEdgeRulesService;
import ti4.service.unit.AddUnitService;

@UtilityClass
public class FractureService {

    private static final String PENDING_ROLLS_KEY = "pendingFractureRolls";

    /** Stored-value setup choice of which systems Cosmic Convergence draws from. */
    public static final String CC_SYSTEMS_OPTION_KEY = "cosmicConvergenceSystemsOption";

    public static final String CC_SYSTEMS_EXPANDED_FRACTURE = "cosmicConvergenceSystems";
    public static final String CC_SYSTEMS_REFRACTURE = "refractureSystems";

    /**
     * Did the GM pick "Refracture Systems" for the Cosmic Convergence galactic
     * event during game setup?
     */
    public static boolean drawsFromRefractureSystems(Game game) {
        return game.isCosmicConvergenceMode()
                && CC_SYSTEMS_REFRACTURE.equalsIgnoreCase(game.getStoredValue(CC_SYSTEMS_OPTION_KEY));
    }

    /**
     * Cosmic Convergence default draw: one of the twelve fixed expanded
     * fracture tiles (ef1-ef12), then buttons to choose its placement.
     */
    private static void drawAndOfferPlacementOfCCTile(ButtonInteractionEvent event, Game game, Player player) {
        List<String> redTilesToPullFrom = new ArrayList<>(
                List.of("ef1", "ef2", "ef3", "ef4", "ef5", "ef6", "ef7", "ef8", "ef9", "ef10", "ef11", "ef12"));
        redTilesToPullFrom.removeAll(
                game.getTileMap().values().stream().map(Tile::getTileID).toList());
        if (redTilesToPullFrom.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    player.getRepresentation()
                            + " has no Cosmic Convergence tiles left to draw: all of them are already in play.");
            return;
        }
        List<String> tileToPullFromUnshuffled = new ArrayList<>(redTilesToPullFrom);
        Collections.shuffle(redTilesToPullFrom, RandomHelper.current());
        String newTileID = redTilesToPullFrom.getFirst();
        TileModel tile = TileHelper.getTileById(newTileID);
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                player.getRepresentation() + " drew 1 fracture tile from this list:\n> " + tileToPullFromUnshuffled);
        if (tile == null) {
            MessageHelper.sendMessageToChannel(event.getMessageChannel(), "Could not find tile " + newTileID + ".");
            return;
        }
        event.getMessageChannel()
                .sendMessageEmbeds(tile.getRepresentationEmbed(false))
                .queue(Consumers.nop(), BotLogger::catchRestError);

        List<Button> buttons = new ArrayList<>();
        List<String> directlyAdjacentTiles =
                new ArrayList<>(List.of("frac9", "frac10", "frac14", "frac15", "frac19", "frac20", "frac22", "frac23"));
        if ("ef2".equalsIgnoreCase(newTileID)) {
            directlyAdjacentTiles = new ArrayList<>(List.of("tl", "tr", "bl", "br"));
        }
        for (String pos : directlyAdjacentTiles) {
            if (game.getTileByPosition(pos) == null) {
                buttons.add(
                        Buttons.green(player.factionButtonChecker() + "cosmicConStep3_" + newTileID + "_" + pos, pos));
            }
        }
        if (buttons.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    player.getRepresentation() + " drew " + newTileID
                            + ", but there is no free location left to place it in.");
            return;
        }
        MessageHelper.sendMessageToChannelWithButtons(
                player.getCorrectChannel(),
                player.getRepresentation()
                        + ", please choose the location for the new tile (it probably doesnt matter too much).",
                buttons);
    }

    /**
     * Cosmic Convergence alternative draw: one physical Refracture tile (a
     * single tile or a multi-hex cluster), then buttons for every placement
     * that fits on the current board.
     */
    private static void drawAndOfferPlacementOfRefractureCluster(
            ButtonInteractionEvent event, Game game, Player player) {
        List<RefractureCCDrawPool.Cluster> pool = new ArrayList<>(RefractureCCDrawPool.clusters());
        pool.removeIf(cluster -> !cluster.isAvailableIn(game));
        if (pool.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    player.getRepresentation()
                            + " has no Refracture tiles left to draw: all of them are already in play.");
            return;
        }
        pool.removeIf(cluster -> cluster.placements().stream().noneMatch(placement -> placement.canBePlacedIn(game)));
        if (pool.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    player.getRepresentation()
                            + " drew a Refracture tile, but none of the remaining ones can be placed.");
            return;
        }
        Collections.shuffle(pool, RandomHelper.current());
        RefractureCCDrawPool.Cluster drawn = pool.getFirst();

        List<MessageEmbed> tileEmbeds = new ArrayList<>();
        for (String tileId : drawn.tileIds()) {
            TileModel tileModel = TileHelper.getTileById(tileId);
            if (tileModel != null) tileEmbeds.add(tileModel.getRepresentationEmbed(false));
        }
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                player.getRepresentation() + " drew the Refracture tile **" + drawn.name() + "** ("
                        + String.join(", ", drawn.tileIds()) + ")");
        event.getMessageChannel().sendMessageEmbeds(tileEmbeds).queue(Consumers.nop(), BotLogger::catchRestError);

        List<Button> buttons = new ArrayList<>();
        for (int pIdx = 0; pIdx < drawn.placements().size(); pIdx++) {
            RefractureCCDrawPool.Placement placement = drawn.placements().get(pIdx);
            if (!placement.canBePlacedIn(game)) continue;
            buttons.add(Buttons.green(
                    player.factionButtonChecker() + "cosmicConStep3Ref_" + drawn.id() + "_" + pIdx,
                    "Place at " + placement.anchorPosition()));
        }
        if (buttons.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    player.getRepresentation() + " cannot place **" + drawn.name() + "** anywhere.");
            return;
        }
        MessageHelper.sendMessageToChannelWithButtons(
                player.getCorrectChannel(),
                player.getRepresentation() + ", please choose the placement for **" + drawn.name() + "**.",
                buttons);
    }

    @ButtonHandler("cosmicConStep3_")
    private static void placeDrawnCCTile(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        String[] parts = buttonID.split("_");
        String tileID = parts[2];
        String position = parts[3];
        Tile tile = new Tile(tileID, position);
        game.setTile(tile);
        MessageHelper.sendMessageToChannel(
                player.getCorrectChannel(),
                "Placed " + tile.getRepresentationForButtons(game, player) + " at " + position + ".");
        ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
    }

    @ButtonHandler("cosmicConStep3Ref_")
    private static void placeDrawnRefractureCluster(
            ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        String[] parts = buttonID.split("_");
        int clusterId = Integer.parseInt(parts[2]);
        int placementIdx = Integer.parseInt(parts[3]);
        RefractureCCDrawPool.Cluster drawn = RefractureCCDrawPool.clusters().stream()
                .filter(cluster -> cluster.id() == clusterId)
                .findFirst()
                .orElse(null);
        if (drawn == null
                || placementIdx < 0
                || placementIdx >= drawn.placements().size()) {
            ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
            return;
        }
        RefractureCCDrawPool.Placement placement = drawn.placements().get(placementIdx);
        if (!placement.canBePlacedIn(game)) {
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(),
                    "That placement no longer fits: some of its hexes are already occupied.");
            ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
            return;
        }
        RefractureCCDrawPool.place(event, game, placement);
        MessageHelper.sendMessageToChannel(
                player.getCorrectChannel(),
                "Placed **" + drawn.name() + "** (" + String.join(", ", drawn.tileIds()) + ") at "
                        + placement.anchorPosition() + ".");
        ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
    }

    /**
     * Is there any Fracture space on the board, whether from a tile or from a
     * fracture token?
     */
    public static boolean isFractureInPlay(Game game) {
        return game.getTileMap().values().stream().anyMatch(Tile::isFracture);
    }

    /**
     * Positional check, for map rendering only - use {@link #isFractureInPlay} for
     * rules.
     */
    public static boolean isFractureRegionOnMap(Game game) {
        return Stream.of("frac1", "frac2", "frac3", "frac4", "frac5", "frac6", "frac7")
                .anyMatch(pos -> game.getTileByPosition(pos) != null);
    }

    public static boolean isFractureExpandedRegionOnMap(Game game) {
        return Stream.of(
                        "frac8", "frac9", "frac10", "frac11", "frac12", "frac13", "frac14", "frac15", "frac16",
                        "frac17", "frac18", "frac19", "frac20", "frac21", "frac22", "frac23", "frac24", "frac25",
                        "frac26", "frac27", "frac28", "frac29", "frac30", "frac31", "frac32", "frac33", "frac34",
                        "frac35", "frac36", "frac37", "frac38", "frac39", "frac40", "frac41", "frac42", "frac43",
                        "frac44", "frac45", "frac46", "frac47", "frac48", "frac49")
                .anyMatch(pos -> game.getTileByPosition(pos) != null);
    }

    public static boolean isRefractureTopLayout(Game game) {
        return game.isRefractureMode() && "TOP".equals(game.getStoredValue(RefractureLayoutRegistry.LAYOUT_EDGE_KEY));
    }

    public static boolean canFractureEnterPlay(Game game) {
        if (game.isCosmicConvergenceMode()) {
            return true;
        }
        return !game.isNoFractureMode() && !isFractureInPlay(game);
    }

    public static String whyFractureCannotEnterPlay(Game game) {
        if (isFractureInPlay(game)) return "The Fracture is already in play.";
        if (game.isNoFractureMode()) return "The Fracture is disabled for this game, so it cannot enter play.";
        return "";
    }

    public static void markRollPending(Game game, Player player) {
        Set<String> pending = pendingRollUserIds(game);
        pending.add(player.getUserID());
        game.setStoredValue(PENDING_ROLLS_KEY, String.join(",", pending));
    }

    private static void clearRollPending(Game game, Player player) {
        Set<String> pending = pendingRollUserIds(game);
        pending.remove(player.getUserID());
        game.setStoredValue(PENDING_ROLLS_KEY, String.join(",", pending));
    }

    public static List<Player> playersWithPendingRoll(Game game) {
        if (!canFractureEnterPlay(game)) return List.of();
        Set<String> pending = pendingRollUserIds(game);
        return game.getRealPlayers().stream()
                .filter(player -> pending.contains(player.getUserID()))
                .toList();
    }

    private static Set<String> pendingRollUserIds(Game game) {
        String stored = game.getStoredValue(PENDING_ROLLS_KEY);
        if (stored.isEmpty()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Arrays.asList(stored.split(",")));
    }

    @ButtonHandler("rollFracture")
    private static void resolveFractureRoll(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        clearRollPending(game, player);
        String bt = player.getBreakthroughID();
        if (buttonID.contains("_")) bt = buttonID.split("_")[1];

        if (!canFractureEnterPlay(game)) {
            MessageHelper.sendMessageToChannel(player.getCorrectChannel(), whyFractureCannotEnterPlay(game));
            ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
            return;
        }

        // Roll dice: one d10 normally, or two d10s if Cosmic Convergence is active
        int result;
        String diceDisplay;
        if (game.isCosmicConvergenceMode()) {
            Die die1 = new Die(0);
            Die die2 = new Die(0);
            String die1Color = (die1.getResult() == 1 || die1.getResult() == 10) ? "green" : "gray";
            String die2Color = (die2.getResult() == 1 || die2.getResult() == 10) ? "green" : "gray";
            diceDisplay = DiceEmojis.getDieEmoji(die1Color, die1.getResult()) + " "
                    + DiceEmojis.getDieEmoji(die2Color, die2.getResult());
            boolean isSuccess = (die1.getResult() == 1 || die1.getResult() == 10)
                    || (die2.getResult() == 1 || die2.getResult() == 10);
            result = isSuccess ? 1 : 2; // use 1 for success, anything else for fail
        } else {
            Die singleDie = new Die(0);
            result = singleDie.getResult();
            String dieColor = (result == 1 || result == 10) ? "green" : "gray";
            diceDisplay = DiceEmojis.getDieEmoji(dieColor, result);
        }

        if ("onyxxabt".equals(bt)) {
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(),
                    player.getRepresentation(false, false)
                            + " has _Styx and Stones_ so The Fracture enters automatically!"
                            + " Ingress tokens will automatically have been placed in their position on the map, if there were no choices to be made.");
            if (spawnFracture(event, game, false)) spawnIngressTokens(event, game, player, bt);
        } else if ("cabalbt".equals(bt)) {
            String msg = player.getRepresentation(false, false)
                    + " has _Al'raith Ix Ianovar_ so The Fracture enters automatically"
                    + "! Ingress tokens will automatically have been placed in their position on the map, if there were no choices to be made.";
            MessageHelper.sendMessageToChannel(player.getCorrectChannel(), msg);
            if (spawnFracture(event, game, false)) spawnIngressTokens(event, game, player, bt);
            AlRaithService.serveBeginCabalBreakthroughButtons(event, game, player);
        } else {
            if (result == 1 || result == 10) { // success
                if (game.isCosmicConvergenceMode()) {
                    MessageHelper.sendMessageToChannel(
                            player.getCorrectChannel(),
                            player.getRepresentation(false, false) + " rolled " + diceDisplay
                                    + "! The Fracture is entering play. A new fracture tile will be drawn.");

                    int countPer = 1;
                    boolean goneThrough = false;
                    List<TechnologyType> techTypesToAddIngress = new ArrayList<>(TechnologyType.mainFour);
                    for (TechnologyType type : techTypesToAddIngress) {
                        List<Tile> tilesWithSkip =
                                getTilesWithSkipAndNoIngressAndNotAdding(game, type, new ArrayList<>());
                        if (tilesWithSkip.isEmpty()) continue;

                        // The GM presses these in fog, so they must not carry the player's FFCC_
                        // ownership prefix
                        String prefix = game.isFowMode() ? "" : player.factionButtonChecker();
                        List<Button> buttons = new ArrayList<>(tilesWithSkip.stream()
                                .map(tile -> {
                                    String id = prefix + "addIngressToken_" + tile.getPosition() + "_" + countPer;
                                    String label = "Add Ingress To " + tile.getRepresentationForButtons(game, player);
                                    return Buttons.red(id, label, type.emoji());
                                })
                                .toList());

                        String msg = game.isFowMode()
                                ? GMService.gmPing(game) + ", please choose a system with a " + type.emoji()
                                        + " to place an Ingress token for "
                                        + player.getRepresentationUnfoggedNoPing() + "."
                                : player.getRepresentation() + ", please choose a system with a " + type.emoji()
                                        + " to place an Ingress token.";
                        buttons.add(Buttons.DONE_DELETE_BUTTONS);
                        MessageHelper.sendMessageToChannelWithButtons(
                                game.isFowMode() ? GMService.getGMChannel(game) : player.getCorrectChannel(),
                                msg,
                                buttons);
                        if (!game.isFowMode() && !goneThrough) {
                            goneThrough = true;
                            MessageHelper.sendMessageToChannel(
                                    game.getMainGameChannel(), "## Please only place one ingress token.");
                        }
                    }
                    if (drawsFromRefractureSystems(game)) {
                        drawAndOfferPlacementOfRefractureCluster(event, game, player);
                    } else {
                        drawAndOfferPlacementOfCCTile(event, game, player);
                    }
                } else {
                    if (spawnFracture(event, game, game.isRefractureMode() && result == 10)) {
                        String msg = player.getRepresentation(false, false) + " rolled a " + diceDisplay
                                + "! The Fracture is now in play! Ingress tokens will automatically have been placed in their position on the map, if there were no choices to be made.";
                        MessageHelper.sendMessageToChannel(player.getCorrectChannel(), msg);
                        spawnIngressTokens(event, game, player, bt);
                    }
                }
            } else if (result == 6 && RandomHelper.isOneInX(10)) {
                MessageHelper.sendMessageToChannel(
                        player.getCorrectChannel(),
                        "> \"Thunder rolled...\n> It rolled a " + DiceEmojis.getGrayDieEmoji(6)
                                + ".\"\n> \\- Terry Pratchett, _Guards! Guards!_");
            } else { // fail
                String msg =
                        player.getRepresentation(true, false) + " rolled " + diceDisplay + ", better luck next time.";
                MessageHelper.sendMessageToChannel(player.getCorrectChannel(), msg);
            }
        }
        ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
    }

    /**
     * Brings The Fracture into play for an automatic effect, saying so only if it
     * is switched off for the game.
     */
    public static boolean enterPlayOrExplain(
            GenericInteractionCreateEvent event, Game game, @NotNull Player player, String breakthrough) {
        if (!spawnFracture(event, game, game.isRefractureMode())) {
            // Already in play is the normal case for a later effect, so stay quiet unless
            // it is actually disabled
            if (!isFractureInPlay(game)) {
                MessageHelper.sendMessageToChannel(player.getCorrectChannel(), whyFractureCannotEnterPlay(game));
            }
            return false;
        }
        spawnIngressTokens(event, game, player, breakthrough);
        return true;
    }

    /**
     * Places The Fracture if it is allowed to enter play. Returns true if the tiles
     * were actually placed.
     */
    public static boolean spawnFracture(GenericInteractionCreateEvent event, Game game) {
        return spawnFracture(event, game, game.isRefractureMode());
    }

    public static boolean spawnRefractureLayout(GenericInteractionCreateEvent event, Game game, int layout) {
        return spawnRefractureLayout(event, game, layout, false);
    }

    public static boolean spawnRefractureLayout(
            GenericInteractionCreateEvent event, Game game, int layout, boolean withDemeter) {
        if (!game.isRefractureMode() || !canFractureEnterPlay(game)) return false;
        if (!RefractureLayoutRegistry.isConfigured(layout)) return false;
        if (withDemeter && !spawnDemeter(event, game, layout)) return false;
        return layout == 1
                ? spawnNormalFracture(event, game)
                : RefractureLayoutRegistry.placeLayout(event, game, layout);
    }

    private static boolean spawnFracture(GenericInteractionCreateEvent event, Game game, boolean addDemeter) {
        if (!canFractureEnterPlay(game)) return false;
        if (game.isRefractureMode()) {
            if (addDemeter) spawnDemeter(event, game);
            int layout = new Die(0).getResult();
            MessageHelper.sendMessageToChannel(
                    game.isFowMode() ? GMService.getGMChannel(game) : event.getMessageChannel(),
                    "Refracture layout roll: " + DiceEmojis.getGrayDieEmoji(layout) + ".");
            if (!RefractureLayoutRegistry.isConfigured(layout)) {
                MessageHelper.sendMessageToChannel(
                        game.isFowMode() ? GMService.getGMChannel(game) : event.getMessageChannel(),
                        "Refracture layout " + layout
                                + " was rolled, but that layout has not been configured. The Fracture does not enter play.");
                return false;
            }
            boolean entered = layout == 1
                    ? spawnNormalFracture(event, game)
                    : RefractureLayoutRegistry.placeLayout(event, game, layout);
            return entered;
        }
        return spawnNormalFracture(event, game);
    }

    private static boolean spawnNormalFracture(GenericInteractionCreateEvent event, Game game) {
        List<String> fracture = Arrays.asList(
                "fracture1", "fracture2", "fracture3", "fracture4", "fracture5", "fracture6", "fracture7");
        List<String> positions = Arrays.asList("frac1", "frac2", "frac3", "frac4", "frac5", "frac6", "frac7");

        Player neutral = game.getNeutral();
        if (game.isRefractureMode()) game.setStoredValue(RefractureLayoutRegistry.LAYOUT_EDGE_KEY, "TOP");
        String neutralColorID = neutral.getColorID();
        List<String> units =
                Arrays.asList("2 ca, 2 inf c", "", "", "2 dn, 1 dd, 3 inf s", "", "", "1 cv, 4 ff, 1 inf l, 1 inf p");
        for (int i = 0; i < 7; ++i) {
            String pos = positions.get(i);
            Tile tile = new Tile(fracture.get(i), pos);
            // add tokens
            if (i == 0) tile.addToken("token_relictoken.png", "cocytus");
            if (i == 3) tile.addToken("token_relictoken.png", "styx");
            if (i == 6) {
                tile.addToken("token_relictoken.png", "lethe");
                tile.addToken("token_relictoken.png", "phlegethon");
            }
            // set tile
            game.setTile(tile);
            // add units
            AddUnitService.addUnits(event, game.getTileByPosition(pos), game, neutralColorID, units.get(i));
        }
        moveCornerHomeSystemsAwayFromRefracture(game);
        moveMalliceAwayFromRefracture(game);
        return true;
    }

    public static void moveCornerHomeSystemsAwayFromRefracture(Game game) {
        if (!game.isRefractureMode()) return;
        List<String> affectedCorners = switch (game.getStoredValue(RefractureLayoutRegistry.LAYOUT_EDGE_KEY)) {
            case "TOP" -> List.of("tl", "tr");
            case "LOWER_LEFT" -> List.of("tl", "bl");
            case "LOWER_RIGHT" -> List.of("tr", "br");
            default -> List.of();
        };
        List<String> destinations = switch (game.getStoredValue(RefractureLayoutRegistry.LAYOUT_EDGE_KEY)) {
            case "TOP" -> List.of("bl", "br");
            case "LOWER_LEFT" -> List.of("tr", "br");
            case "LOWER_RIGHT" -> List.of("tl", "bl");
            default -> List.of();
        };
        List<Tile> homesToMove = game.getTileMap().values().stream()
                .filter(tile -> Set.of("51", "118").contains(tile.getTileID()))
                .filter(tile -> affectedCorners.contains(tile.getPosition()))
                .sorted(java.util.Comparator.comparing(Tile::getTileID))
                .toList();
        for (Tile home : homesToMove) {
            String destination = destinations.stream()
                    .filter(corner -> game.getTileByPosition(corner) == null)
                    .findFirst()
                    .orElse(null);
            if (destination == null) continue;
            Player owner = game.getRealPlayers().stream()
                    .filter(player -> home.getPosition().equals(player.getHomeSystemPosition()))
                    .findFirst()
                    .orElse(null);
            relocateTileWithoutRemovingPlanets(game, home, destination);
            if (owner != null) owner.setHomeSystemPosition(destination);
        }
    }

    public static void moveMalliceAwayFromRefracture(Game game) {
        if (!game.isRefractureMode() || game.getTileMap().values().stream().noneMatch(Tile::isFracture)) {
            return;
        }
        Tile mallice = game.getTileMap().values().stream()
                .filter(tile -> Set.of("82a", "82b", "82ah", "82bh").contains(tile.getTileID()))
                .findFirst()
                .orElse(null);
        if (mallice == null) return;
        boolean lowerLeftLayout = "LOWER_LEFT".equals(game.getStoredValue(RefractureLayoutRegistry.LAYOUT_EDGE_KEY));
        if (!lowerLeftLayout && !"tl".equalsIgnoreCase(mallice.getPosition())) return;
        Stream<String> destinations = lowerLeftLayout ? Stream.of("br", "tr", "tl") : Stream.of("bl", "br", "tr");
        String destination = destinations
                .filter(corner ->
                        corner.equalsIgnoreCase(mallice.getPosition()) || game.getTileByPosition(corner) == null)
                .findFirst()
                .orElse(null);
        if (destination == null || destination.equalsIgnoreCase(mallice.getPosition())) return;
        relocateTileWithoutRemovingPlanets(game, mallice, destination);
    }

    private static void relocateTileWithoutRemovingPlanets(Game game, Tile tile, String destination) {
        game.getTileMap().remove(tile.getPosition());
        tile.setPosition(destination);
        game.setTile(tile);
    }

    private static boolean spawnDemeter(GenericInteractionCreateEvent event, Game game) {
        return spawnDemeter(event, game, 0);
    }

    private static boolean spawnDemeter(GenericInteractionCreateEvent event, Game game, int layout) {
        if (game.getTileMap().values().stream().anyMatch(tile -> "fracture38".equals(tile.getTileID()))) return true;
        Stream<String> corners = layout == 8 ? Stream.of("tl", "tr", "br", "bl") : Stream.of("tl", "tr", "bl", "br");
        return corners.filter(corner -> game.getTileByPosition(corner) == null)
                .findFirst()
                .map(corner -> {
                    Tile demeter = new Tile("fracture38", corner);
                    demeter.addToken("token_relictoken.png", "demeterii");
                    game.setTile(demeter);
                    AddUnitService.addUnits(
                            event, demeter, game, game.getNeutral().getColorID(), "1 ws, 2 ff space, 5 inf demeterii");
                    return true;
                })
                .orElse(false);
    }

    public static void spawnIngressTokens(
            GenericInteractionCreateEvent event, Game game, @NotNull Player player, String breakthrough) {
        List<Tile> automaticAdds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Tile extra = game.getTileFromPlanet(Constants.THUNDERSEDGE);
        if (extra == null) extra = game.getMecatolTile();
        if (extra != null) automaticAdds.add(extra);
        if (extra == null) errors.add("Could not find Thunder's Edge or Mecatol Rex.");

        List<TechnologyType> techTypesToAddIngress = new ArrayList<>();
        int numberOfIngressPerTechType = 3;
        BreakthroughModel bt = player.getBreakthroughModel(breakthrough);
        if (bt != null && bt.hasSynergy()) {
            techTypesToAddIngress.addAll(bt.getSynergy());
        } else {
            techTypesToAddIngress.addAll(TechnologyType.mainFour);
            numberOfIngressPerTechType = 1;
        }

        for (int rpt = 0; rpt < techTypesToAddIngress.size(); rpt++) {
            for (TechnologyType type : techTypesToAddIngress) {
                List<Tile> tilesWithSkip = getTilesWithSkipAndNoIngressAndNotAdding(game, type, automaticAdds);
                if (!game.isFowMode() && tilesWithSkip.size() <= numberOfIngressPerTechType)
                    automaticAdds.addAll(tilesWithSkip);
            }
        }

        StringBuilder automatic =
                new StringBuilder("## ").append(game.getPing()).append(" - The Fracture is now in play.");
        if (!game.isFowMode() && !automaticAdds.isEmpty()) {
            automatic.append(" Automatically added ingress tokens to the following tiles:");
        }
        for (Tile t : automaticAdds) {
            t.addToken(Constants.TOKEN_INGRESS, "space");
            if (!game.isFowMode()) {
                automatic.append("\n- ").append(t.getRepresentationForButtons(game, player));
            }
        }
        MessageHelper.sendMessageToChannel(game.getMainGameChannel(), automatic.toString());

        int countPer = numberOfIngressPerTechType;
        boolean anyChoicesToMake = false;
        boolean goneThrough = false;
        for (TechnologyType type : techTypesToAddIngress) {
            List<Tile> tilesWithSkip = getTilesWithSkipAndNoIngressAndNotAdding(game, type, automaticAdds);
            if (tilesWithSkip.isEmpty()) continue;
            // Nothing auto-places in fog, so the GM gets buttons for every type
            if (!game.isFowMode() && tilesWithSkip.size() <= numberOfIngressPerTechType) continue;
            anyChoicesToMake = true;

            // The GM presses these in fog, so they must not carry the player's FFCC_
            // ownership prefix
            String prefix = game.isFowMode() ? "" : player.factionButtonChecker();
            List<Button> buttons = new ArrayList<>(tilesWithSkip.stream()
                    .map(tile -> {
                        String id = prefix + "addIngressToken_" + tile.getPosition() + "_" + countPer;
                        String label = "Add Ingress To " + tile.getRepresentationForButtons(game, player);
                        return Buttons.red(id, label, type.emoji());
                    })
                    .toList());

            String msg = game.isFowMode()
                    ? GMService.gmPing(game) + ", please choose a system with a " + type.emoji()
                            + " to place an Ingress token for "
                            + player.getRepresentationUnfoggedNoPing() + "."
                    : player.getRepresentation() + ", please choose a system with a " + type.emoji()
                            + " to place an Ingress token.";
            buttons.add(Buttons.DONE_DELETE_BUTTONS);
            MessageHelper.sendMessageToChannelWithButtons(
                    game.isFowMode() ? GMService.getGMChannel(game) : player.getCorrectChannel(), msg, buttons);
            if (!game.isFowMode() && !goneThrough) {
                goneThrough = true;
                MessageHelper.sendMessageToChannel(
                        game.getMainGameChannel(),
                        "## Please do not place more ingress tokens than legal."
                                + " If brought in by breakthrough, that means up to 3 planets per technology type of the breakthrough (6 total)."
                                + " Otherwise, 1 planet per technology type (4 total).");
            }
        }

        if (game.isFowMode() && anyChoicesToMake) {
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(),
                    player.getRepresentationUnfogged()
                            + ", The Fracture is entering play. In fog games Ingress tokens are placed by the GM"
                            + " — please wait for " + GMService.gmPing(game)
                            + " to resolve it, and do not place any yourself.");
        }

        ThundersEdgeRulesService.alertTabletalkWithFractureRules(game);
    }

    @ButtonHandler("addIngressToken_")
    private static void addIngressTokenButtonHandler(
            ButtonInteractionEvent event, Game game, Player player, String buttonID) {

        Tile tile = game.getTileByPosition(buttonID.split("_")[1]);
        if (tile == null || tile.isFracture()) {
            ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
            return;
        }
        tile.addToken(Constants.TOKEN_INGRESS, "space");

        String confirmation = "Placed an ingress token in " + tile.getRepresentationForButtons() + ".";
        if (game.isFowMode()) {
            // In fog the presser is the GM, who need not be a seated player
            MessageHelper.sendMessageToChannel(GMService.getGMChannel(game), confirmation);
            FoWHelper.pingSystem(game, tile.getPosition(), "A new ingress tears into The Fracture.", false);
        } else {
            MessageHelper.sendMessageToChannel(player.getCorrectChannel(), confirmation);
        }

        ButtonHelper.deleteButtonAndDeleteMessageIfEmpty(event);
    }

    private static List<Tile> getTilesWithSkipAndNoIngressAndNotAdding(
            Game game, TechnologyType type, List<Tile> alreadyCounted) {
        List<Tile> tiles = new ArrayList<>();
        for (Tile tile : game.getTileMap().values()) {
            if (alreadyCounted.contains(tile)) continue;
            if (tile.isFracture()) continue;
            if (tile.getSpaceUnitHolder().getTokenList().contains(Constants.TOKEN_INGRESS)) continue;
            if (tile.getPlanetUnitHolders().stream().anyMatch(p -> p.hasTechSpecialty(type))) tiles.add(tile);
        }
        return tiles;
    }
}
