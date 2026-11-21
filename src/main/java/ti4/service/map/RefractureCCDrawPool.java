package ti4.service.map;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.service.unit.AddUnitService;

/**
 * The pool of Refracture systems that the Cosmic Convergence galactic event
 * draws from when the GM picks "Refracture Systems" during game setup. Each
 * entry is one physical Refracture tile: either a single hex or a cluster of
 * adjacent hexes that must be placed together. Placements, neutral units and
 * tokens are taken from the proven Refracture layouts in
 * {@link RefractureLayoutRegistry}.
 */
public final class RefractureCCDrawPool {

    private RefractureCCDrawPool() {}

    public static List<Cluster> clusters() {
        return CLUSTERS;
    }

    /**
     * Places every hex of the given placement, with its neutral units and
     * tokens.
     */
    public static void place(GenericInteractionCreateEvent event, Game game, Placement placement) {
        String neutralColorID =
                game.getNeutral() == null ? "gray" : game.getNeutral().getColorID();
        List<Tile> tiles = new ArrayList<>();
        for (RefractureLayoutRegistry.TilePlacement tilePlacement : placement.tiles()) {
            Tile tile = new Tile(tilePlacement.tileId(), tilePlacement.position());
            for (RefractureLayoutRegistry.TokenPlacement token : tilePlacement.tokens()) {
                tile.addToken(token.tokenId(), token.holder());
            }
            tiles.add(tile);
        }
        tiles.forEach(game::setTile);
        for (int index = 0; index < placement.tiles().size(); index++) {
            RefractureLayoutRegistry.TilePlacement tilePlacement =
                    placement.tiles().get(index);
            Tile tile = tiles.get(index);
            for (RefractureLayoutRegistry.UnitPlacement units : tilePlacement.neutralUnits()) {
                AddUnitService.addUnits(event, tile, game, neutralColorID, unitsWithHolder(units));
            }
        }
    }

    private static String unitsWithHolder(RefractureLayoutRegistry.UnitPlacement units) {
        return java.util.Arrays.stream(units.unitList().split(","))
                .map(String::trim)
                .map(unit -> unit + " " + units.holder())
                .collect(Collectors.joining(", "));
    }

    /**
     * One concrete way to place a tile or cluster: all of its hexes, at
     * positions proven to be adjacent by the Refracture layouts.
     */
    public record Placement(List<RefractureLayoutRegistry.TilePlacement> tiles) {

        public boolean canBePlacedIn(Game game) {
            return tiles.stream().noneMatch(tilePlacement -> game.getTileByPosition(tilePlacement.position()) != null);
        }

        public String anchorPosition() {
            return tiles.getFirst().position();
        }
    }

    /**
     * One physical Refracture tile (single hex or cluster), with every
     * placement variant that can fit on a map.
     */
    public record Cluster(int id, String name, List<Placement> placements) {

        public List<String> tileIds() {
            return placements.getFirst().tiles().stream()
                    .map(RefractureLayoutRegistry.TilePlacement::tileId)
                    .toList();
        }

        public boolean isAvailableIn(Game game) {
            List<String> ids = tileIds();
            return game.getTileMap().values().stream().noneMatch(tile -> ids.contains(tile.getTileID()));
        }
    }

    private static final List<Cluster> CLUSTERS = List.of(
            cluster(
                    0,
                    "Qilin Station & Anubis/Sekhmet/Nile",
                    placement(
                            tile("fracture8", "frac3", units(unit("2 dd", "space")), tokens()),
                            tile("fracture9", "frac4", units(), tokens()),
                            tile(
                                    "fracture10",
                                    "frac5",
                                    units(
                                            unit("1 dn, 1 cr", "space"),
                                            unit("3 inf, 1 pds", "nile"),
                                            unit("1 inf", "anubis"),
                                            unit("1 inf", "sekhmet")),
                                    relics("nile", "anubis", "sekhmet")))),
            cluster(
                    1,
                    "Mater Calyxa",
                    placement(
                            tile("fracture12", "frac2", units(), tokens()),
                            tile(
                                    "fracture13",
                                    "frac3",
                                    units(unit("3 dn", "space"), unit("1 inf, 2 pds", "matercalyxa")),
                                    relics("matercalyxa"))),
                    placement(
                            tile("fracture12", "frac12", units(), tokens()),
                            tile(
                                    "fracture13",
                                    "frac4",
                                    units(unit("3 dn", "space"), unit("2 pds, 1 inf", "matercalyxa")),
                                    relics("matercalyxa")))),
            cluster(
                    2,
                    "Charon",
                    placement(
                            tile("fracture14", "frac7", units(), tokens()),
                            tile(
                                    "fracture15",
                                    "frac16",
                                    units(unit("2 cr, 2 ff", "space"), unit("1 inf", "charontoken")),
                                    tokens(relic("charontoken"), charonToken())),
                            tile("fracture16", "frac17", units(), tokens()))),
            cluster(
                    3,
                    "The Experiment & Janus",
                    placement(
                            tile("fracture11", "frac11", units(), tokens()),
                            tile(
                                    "fracture17",
                                    "frac3",
                                    units(
                                            unit("1 dn, 1 ff", "space"),
                                            unit("1 inf, 1 pds", "experiment"),
                                            unit("1 inf", "janus")),
                                    relics("experiment", "janus")))),
            cluster(
                    4,
                    "Fenrir",
                    placement(
                            tile("fracture19", "frac4", units(), tokens()),
                            tile("fracture21", "frac5", units(), tokens()),
                            tile("fracture20", "frac13", units(), tokens()),
                            tile("fracture18", "frac12", units(unit("7 inf", "fenrir")), relics("fenrir"))),
                    placement(
                            tile("fracture19", "frac6", units(), tokens()),
                            tile("fracture21", "frac7", units(), tokens()),
                            tile("fracture20", "frac15", units(), tokens()),
                            tile("fracture18", "frac14", units(unit("7 inf", "fenrir")), relics("fenrir")))),
            cluster(
                    5,
                    "Nidhogg",
                    placement(
                            tile(
                                    "fracture23",
                                    "frac14",
                                    units(unit("1 cv, 3 ff", "space"), unit("1 mf", "nidhogg")),
                                    relics("nidhogg")),
                            tile("fracture22", "frac15", units(), tokens())),
                    placement(
                            tile(
                                    "fracture23",
                                    "frac1",
                                    units(unit("1 cv, 3 ff", "space"), unit("1 mf", "nidhogg")),
                                    relics("nidhogg")),
                            tile("fracture22", "frac25", units(), tokens()))),
            cluster(
                    6,
                    "The Lekythoi",
                    placement(
                            tile("fracture24", "frac6", units(), tokens()),
                            tile(
                                    "fracture25",
                                    "frac7",
                                    units(unit("1 ws, 1 dd", "space"), unit("1 mf, 2 inf", "lekythoi")),
                                    tokens(relic("lekythoi")))),
                    placement(
                            tile("fracture24", "frac28", units(), tokens()),
                            tile(
                                    "fracture25",
                                    "frac29",
                                    units(unit("1 ws, 1 dd", "space"), unit("1 mf, 2 inf", "lekythoi")),
                                    tokens(relic("lekythoi"))))),
            cluster(
                    7,
                    "Ptoma Station",
                    placement(
                            tile("fracture27", "frac6", units(), tokens()),
                            tile(
                                    "fracture26",
                                    "frac14",
                                    units(unit("1 fs, 2 ff", "space")),
                                    tokens(relic("ptomastation"))),
                            tile("fracture28", "frac15", units(), tokens())),
                    placement(
                            tile(
                                    "fracture26",
                                    "frac3",
                                    units(unit("1 fs, 2 ff", "space")),
                                    tokens(relic("ptomastation"))),
                            tile("fracture27", "frac4", units(), tokens()),
                            tile("fracture28", "frac11", units(), tokens()))),
            cluster(
                    8,
                    "Maahes",
                    placement(
                            tile(
                                    "fracture29",
                                    "frac4",
                                    units(unit("1 ff", "space"), unit("2 mf", "maahes")),
                                    tokens(relic("maahes"))),
                            tile("fracture30", "frac5", units(), tokens()),
                            tile("fracture31", "frac14", units(), tokens())),
                    placement(
                            tile(
                                    "fracture29",
                                    "frac5",
                                    units(unit("1 ff", "space"), unit("2 mf", "maahes")),
                                    tokens(relic("maahes"))),
                            tile("fracture31", "frac12", units(), tokens()),
                            tile("fracture30", "frac13", units(), tokens()))),
            cluster(
                    9,
                    "Castor & Pollux",
                    placement(
                            tile(
                                    "fracture34",
                                    "frac5",
                                    units(unit("6 ff", "space"), unit("1 inf", "pollux")),
                                    tokens(relic("pollux"))),
                            tile("fracture33", "frac6", units(), tokens()),
                            tile(
                                    "fracture32",
                                    "frac7",
                                    units(unit("1 dd, 4 ff", "space"), unit("1 inf", "castor")),
                                    tokens(relic("castor"))))),
            cluster(
                    10,
                    "Makara & the Izanas",
                    placement(
                            tile(
                                    "fracture35",
                                    "frac5",
                                    units(unit("5 ff", "space"), unit("2 inf", "makara")),
                                    tokens(relic("makara"))),
                            tile(
                                    "fracture36",
                                    "frac6",
                                    units(
                                            unit("2 cr, 1 dd", "space"),
                                            unit("2 inf", "izana-mi"),
                                            unit("2 inf", "izana-gi")),
                                    tokens(relic("izana-mi"), relic("izana-gi"))),
                            tile("fracture37", "frac7", units(), tokens())),
                    placement(
                            tile("fracture37", "frac1", units(), tokens()),
                            tile(
                                    "fracture36",
                                    "frac2",
                                    units(
                                            unit("2 cr, 1 dd", "space"),
                                            unit("2 inf", "izana-mi"),
                                            unit("2 inf", "izana-gi")),
                                    tokens(relic("izana-mi"), relic("izana-gi"))),
                            tile(
                                    "fracture35",
                                    "frac3",
                                    units(unit("5 ff", "space"), unit("2 inf", "makara")),
                                    tokens(relic("makara"))))),
            cluster(
                    11,
                    "Demeter II",
                    placement(tile(
                            "fracture38",
                            "tl",
                            units(unit("1 ws, 2 ff", "space"), unit("5 inf", "demeterii")),
                            tokens(relic("demeterii")))),
                    placement(tile(
                            "fracture38",
                            "tr",
                            units(unit("1 ws, 2 ff", "space"), unit("5 inf", "demeterii")),
                            tokens(relic("demeterii")))),
                    placement(tile(
                            "fracture38",
                            "bl",
                            units(unit("1 ws, 2 ff", "space"), unit("5 inf", "demeterii")),
                            tokens(relic("demeterii")))),
                    placement(tile(
                            "fracture38",
                            "br",
                            units(unit("1 ws, 2 ff", "space"), unit("5 inf", "demeterii")),
                            tokens(relic("demeterii"))))));

    private static Cluster cluster(int id, String name, Placement... placements) {
        return new Cluster(id, name, List.of(placements));
    }

    private static Placement placement(RefractureLayoutRegistry.TilePlacement... tiles) {
        return new Placement(List.of(tiles));
    }

    private static RefractureLayoutRegistry.TilePlacement tile(
            String tileId,
            String position,
            List<RefractureLayoutRegistry.UnitPlacement> neutralUnits,
            List<RefractureLayoutRegistry.TokenPlacement> tokens) {
        return new RefractureLayoutRegistry.TilePlacement(tileId, position, 0, neutralUnits, tokens);
    }

    private static RefractureLayoutRegistry.UnitPlacement unit(String unitList, String holder) {
        return new RefractureLayoutRegistry.UnitPlacement(unitList, holder);
    }

    private static List<RefractureLayoutRegistry.UnitPlacement> units(RefractureLayoutRegistry.UnitPlacement... units) {
        return List.of(units);
    }

    private static RefractureLayoutRegistry.TokenPlacement relic(String holder) {
        return new RefractureLayoutRegistry.TokenPlacement("token_relictoken.png", holder);
    }

    private static RefractureLayoutRegistry.TokenPlacement charonToken() {
        return new RefractureLayoutRegistry.TokenPlacement("charontoken.png", "space");
    }

    private static List<RefractureLayoutRegistry.TokenPlacement> relics(String... holders) {
        return java.util.Arrays.stream(holders).map(RefractureCCDrawPool::relic).toList();
    }

    private static List<RefractureLayoutRegistry.TokenPlacement> tokens(
            RefractureLayoutRegistry.TokenPlacement... tokens) {
        return List.of(tokens);
    }
}
