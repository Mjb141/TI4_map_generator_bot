package ti4.service.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.components.buttons.Button;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.Helper;
import ti4.message.MessageHelper;

@UtilityClass
public class ImperatorService {

    private static final String ORDER_KEY = "imperator_order";
    private static final String INDEX_KEY = "imperator_index";
    private static final String RANK_KEY_PREFIX = "imperator_rank_"; // persistent per-player
    private static final String THIS_ROUND_PREFIX = "imperator_thisround_"; // ephemeral marker that player ranked this round
    private static final String MET_KEY_PREFIX = "imperator_met_"; // per-player met flags: type:slug,comma-separated
    private static final String DEFS_EVENTS = "imperator_defs_events"; // stored defs: entries sep ';' each slug|name|desc
    private static final String DEFS_STATES = "imperator_defs_states";
    private static final String RANK_DEFS = "imperator_rank_defs"; // rank definitions: entries sep ';' each rank|eventsCsv|statesCsv

    public static void beginImperatorPhase(GenericInteractionCreateEvent event, Game game, MessageChannel channel) {
        // Build order starting left of the speaker and going anti-clockwise.
        Player speaker = game.getSpeaker();
        List<Player> order = Helper.getSpeakerOrderFromThisPlayer(speaker, game);
        if (order == null || order.isEmpty()) return;

        // We want starting left of the speaker -> that is the player immediately after the speaker in the speaker-order list.
        // Helper.getSpeakerOrderFromThisPlayer returns [speaker, next, next, ...]. So rotate to start at index 1.
        List<String> factions = new ArrayList<>();
        for (int i = 1; i < order.size(); i++) {
            factions.add(order.get(i).getFaction());
        }
        // wrap around to include players before speaker
        factions.add(order.get(0).getFaction());

        game.setStoredValue(ORDER_KEY, String.join("_", factions));
        game.setStoredValue(INDEX_KEY, "0");

        ensureDefaultDefinitions(game);
        offerNextPlayerButtons(game);
    }

    private static void ensureDefaultDefinitions(Game game) {
        if (game.getStoredValue(DEFS_EVENTS).isEmpty()) {
            // format slug|name|desc;slug2|name2|desc2...
            String entry = "placeholder|Placeholder Event|A placeholder event that players may meet.";
            game.setStoredValue(DEFS_EVENTS, entry);
        }
        if (game.getStoredValue(DEFS_STATES).isEmpty()) {
            String entry = "placeholderstate|Placeholder State|A placeholder state to be met at rank-up time.";
            game.setStoredValue(DEFS_STATES, entry);
        }
    }

    private static List<Def> getDefs(Game game, String key) {
        String val = game.getStoredValue(key);
        if (val == null || val.isEmpty()) return new ArrayList<>();
        return Arrays.stream(val.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String[] p = s.split("\\|", 3);
                    String slug = p.length > 0 ? p[0] : "";
                    String name = p.length > 1 ? p[1] : slug;
                    String desc = p.length > 2 ? p[2] : "";
                    return new Def(slug, name, desc);
                })
                .collect(Collectors.toList());
    }

    public static List<Def> getEventDefs(Game game) {
        return getDefs(game, DEFS_EVENTS);
    }

    public static List<Def> getStateDefs(Game game) {
        return getDefs(game, DEFS_STATES);
    }

    private static List<String> getOrder(Game game) {
        String val = game.getStoredValue(ORDER_KEY);
        if (val == null || val.isEmpty()) return new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (String p : val.split("_")) {
            if (!p.isBlank()) parts.add(p);
        }
        return parts;
    }

    private static int getIndex(Game game) {
        String v = game.getStoredValue(INDEX_KEY);
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void setIndex(Game game, int idx) {
        game.setStoredValue(INDEX_KEY, String.valueOf(idx));
    }

    public static void offerNextPlayerButtons(Game game) {
        List<String> order = getOrder(game);
        int idx = getIndex(game);
        if (idx >= order.size()) {
            // done
            summarizeImperatorCycle(game);
            game.removeStoredValue(ORDER_KEY);
            game.removeStoredValue(INDEX_KEY);
            return;
        }
        String faction = order.get(idx);
        Player player = game.getPlayerFromColorOrFaction(faction);
        if (player == null || !player.isRealPlayer()) {
            // skip NPCs/dummies
            setIndex(game, idx + 1);
            offerNextPlayerButtons(game);
            return;
        }

        List<Button> buttons = new ArrayList<>();

        // Offer single opener button only (imperator_open_<faction>) - caller should post this in player's cards-info thread
        // For convenience when called internally, send opener now
        buttons.add(Buttons.gray("imperator_open_" + faction, "Imperator"));
        String msg = player.getRepresentation() + ", click the Imperator button to manage your Imperator requirements and rank up.";
        MessageHelper.sendMessageToChannelWithButtons(player.getCardsInfoThread(), msg, buttons);
    }

    public static void advanceImperator(Game game) {
        int idx = getIndex(game);
        setIndex(game, idx + 1);
        offerNextPlayerButtons(game);
    }

    /**
     * Apply rank up only if requirements satisfied. Returns true when rank was applied.
     */
    public static boolean applyRankUp(Game game, Player player) {
        String faction = player.getFaction();
        if (!canRankUp(game, player)) {
            String missing = getMissingRequirementsMessage(game, player);
            MessageHelper.sendMessageToChannel(player.getCorrectChannel(), "Cannot rank up — missing requirements:\n" + missing);
            return false;
        }

        String key = RANK_KEY_PREFIX + faction;
        String cur = game.getStoredValue(key);
        int rank = 0;
        try {
            rank = Integer.parseInt(cur);
        } catch (Exception e) {
        }
        rank++;
        game.setStoredValue(key, String.valueOf(rank));
        game.setStoredValue(THIS_ROUND_PREFIX + player.getFaction(), "true");
        MessageHelper.sendMessageToChannel(player.getCorrectChannel(), player.getRepresentation() + " ranked up to Imperator " + rank + ".");
        return true;
    }

    public static void toggleMetFlag(Game game, String faction, String type, String slug) {
        String key = MET_KEY_PREFIX + faction;
        String cur = game.getStoredValue(key);
        String token = type + ":" + slug;
        List<String> parts = new ArrayList<>();
        if (cur != null && !cur.isEmpty()) {
            for (String s : cur.split(",")) {
                if (!s.isBlank()) parts.add(s);
            }
        }
        if (parts.contains(token)) {
            parts.remove(token);
        } else {
            parts.add(token);
        }
        game.setStoredValue(key, String.join(",", parts));
    }

    public static List<String> getMetFlags(Game game, String faction) {
        String cur = game.getStoredValue(MET_KEY_PREFIX + faction);
        if (cur == null || cur.isEmpty()) return new ArrayList<>();
        return Arrays.stream(cur.split(",")).filter(s -> !s.isBlank()).collect(Collectors.toList());
    }

    // ---- Rank definitions and requirement checking ----

    public static void addRankDefinition(Game game, int rank, List<String> eventSlugs, List<String> stateSlugs) {
        // load existing, replace or append
        String cur = game.getStoredValue(RANK_DEFS);
        List<String> parts = new ArrayList<>();
        if (cur != null && !cur.isEmpty()) {
            for (String s : cur.split(";")) {
                if (!s.isBlank()) parts.add(s);
            }
        }
        String newEntry = rank + "|" + String.join(",", eventSlugs) + "|" + String.join(",", stateSlugs);
        boolean replaced = false;
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            String r = p.split("\\|", 2)[0];
            try {
                if (Integer.parseInt(r) == rank) {
                    parts.set(i, newEntry);
                    replaced = true;
                    break;
                }
            } catch (Exception ignored) {}
        }
        if (!replaced) parts.add(newEntry);
        game.setStoredValue(RANK_DEFS, String.join(";", parts));
    }

    public static RankDef getRankDefinition(Game game, int rank) {
        String cur = game.getStoredValue(RANK_DEFS);
        if (cur == null || cur.isEmpty()) return new RankDef(rank, new ArrayList<>(), new ArrayList<>());
        for (String s : cur.split(";")) {
            if (s.isBlank()) continue;
            String[] p = s.split("\\|", -1);
            try {
                int r = Integer.parseInt(p[0]);
                if (r == rank) {
                    List<String> events = p.length > 1 && !p[1].isEmpty() ? Arrays.stream(p[1].split(",")).filter(x->!x.isBlank()).collect(Collectors.toList()) : new ArrayList<>();
                    List<String> states = p.length > 2 && !p[2].isEmpty() ? Arrays.stream(p[2].split(",")).filter(x->!x.isBlank()).collect(Collectors.toList()) : new ArrayList<>();
                    return new RankDef(rank, events, states);
                }
            } catch (Exception ignored) {}
        }
        return new RankDef(rank, new ArrayList<>(), new ArrayList<>());
    }

    public static RankDef getCumulativeRequirementsUpToRank(Game game, int rank) {
        Set<String> events = new HashSet<>();
        Set<String> states = new HashSet<>();
        String cur = game.getStoredValue(RANK_DEFS);
        if (cur == null || cur.isEmpty()) return new RankDef(rank, new ArrayList<>(events), new ArrayList<>(states));
        for (String s : cur.split(";")) {
            if (s.isBlank()) continue;
            String[] p = s.split("\\|", -1);
            try {
                int r = Integer.parseInt(p[0]);
                if (r <= rank) {
                    if (p.length > 1 && !p[1].isEmpty()) {
                        for (String e : p[1].split(",")) if (!e.isBlank()) events.add(e);
                    }
                    if (p.length > 2 && !p[2].isEmpty()) {
                        for (String e : p[2].split(",")) if (!e.isBlank()) states.add(e);
                    }
                }
            } catch (Exception ignored) {}
        }
        return new RankDef(rank, new ArrayList<>(events), new ArrayList<>(states));
    }

    public static boolean canRankUp(Game game, Player player) {
        String faction = player.getFaction();
        String cur = game.getStoredValue(RANK_KEY_PREFIX + faction);
        int rank = 0;
        try { rank = Integer.parseInt(cur); } catch (Exception ignored) {}
        int nextRank = rank + 1;
        RankDef needed = getCumulativeRequirementsUpToRank(game, nextRank);
        List<String> met = getMetFlags(game, faction);
        Set<String> metSet = new HashSet<>(met);
        // required tokens are event:slug and state:slug
        for (String ev : needed.events) {
            if (!metSet.contains("event:" + ev)) return false;
        }
        for (String st : needed.states) {
            if (!metSet.contains("state:" + st)) return false;
        }
        return true;
    }

    public static String getMissingRequirementsMessage(Game game, Player player) {
        String faction = player.getFaction();
        String cur = game.getStoredValue(RANK_KEY_PREFIX + faction);
        int rank = 0;
        try { rank = Integer.parseInt(cur); } catch (Exception ignored) {}
        int nextRank = rank + 1;
        RankDef needed = getCumulativeRequirementsUpToRank(game, nextRank);
        List<String> met = getMetFlags(game, faction);
        Set<String> metSet = new HashSet<>(met);
        StringBuilder sb = new StringBuilder();
        if (!needed.events.isEmpty()) {
            sb.append("Events missing:\n");
            for (String ev : needed.events) {
                if (!metSet.contains("event:" + ev)) {
                    String name = ev;
                    for (Def d : getEventDefs(game)) if (d.slug.equals(ev)) { name = d.name; break; }
                    sb.append("- ").append(name).append("\n");
                }
            }
        }
        if (!needed.states.isEmpty()) {
            sb.append("States missing:\n");
            for (String st : needed.states) {
                if (!metSet.contains("state:" + st)) {
                    String name = st;
                    for (Def d : getStateDefs(game)) if (d.slug.equals(st)) { name = d.name; break; }
                    sb.append("- ").append(name).append("\n");
                }
            }
        }
        if (sb.length() == 0) return "(none)";
        return sb.toString();
    }

    public static String listRequirementsMessage(Game game, String faction) {
        StringBuilder sb = new StringBuilder();
        sb.append("Imperator Rank Requirements:\n");
        // list each defined rank in order
        String cur = game.getStoredValue(RANK_DEFS);
        if (cur == null || cur.isEmpty()) {
            sb.append("(no rank definitions)");
            return sb.toString();
        }
        List<RankDef> defs = new ArrayList<>();
        for (String s : cur.split(";")) {
            if (s.isBlank()) continue;
            String[] p = s.split("\\|", -1);
            try {
                int r = Integer.parseInt(p[0]);
                List<String> events = p.length > 1 && !p[1].isEmpty() ? Arrays.stream(p[1].split(",")).filter(x->!x.isBlank()).collect(Collectors.toList()) : new ArrayList<>();
                List<String> states = p.length > 2 && !p[2].isEmpty() ? Arrays.stream(p[2].split(",")).filter(x->!x.isBlank()).collect(Collectors.toList()) : new ArrayList<>();
                defs.add(new RankDef(r, events, states));
            } catch (Exception ignored) {}
        }
        defs.sort((a,b)->Integer.compare(a.rank,b.rank));
        List<String> met = getMetFlags(game, faction);
        Set<String> metSet = new HashSet<>(met);
        for (RankDef rd : defs) {
            sb.append("Rank ").append(rd.rank).append(":\n");
            if (!rd.events.isEmpty()) {
                sb.append("  Events:\n");
                for (String ev : rd.events) {
                    String name = ev;
                    for (Def d : getEventDefs(game)) if (d.slug.equals(ev)) { name = d.name; break; }
                    boolean done = metSet.contains("event:" + ev);
                    sb.append("    - ").append(name).append(done?" (done)":"").append("\n");
                }
            }
            if (!rd.states.isEmpty()) {
                sb.append("  States:\n");
                for (String st : rd.states) {
                    String name = st;
                    for (Def d : getStateDefs(game)) if (d.slug.equals(st)) { name = d.name; break; }
                    boolean done = metSet.contains("state:" + st);
                    sb.append("    - ").append(name).append(done?" (done)":"").append("\n");
                }
            }
        }
        // also show whether faction can rank up now
        Player p = game.getPlayerFromColorOrFaction(faction);
        if (p != null) {
            sb.append("\nCurrent rank: ").append(game.getStoredValue(RANK_KEY_PREFIX + faction).isEmpty()?"0":game.getStoredValue(RANK_KEY_PREFIX + faction)).append('\n');
            sb.append("Can rank up now: ").append(canRankUp(game, p) ? "Yes" : "No");
        }
        return sb.toString();
    }

    public static void addDefinition(Game game, String type, String slug, String name, String desc) {
        String key = "";
        if ("event".equalsIgnoreCase(type)) key = DEFS_EVENTS;
        else key = DEFS_STATES;
        String cur = game.getStoredValue(key);
        String entry = slug + "|" + name + "|" + desc;
        if (cur == null || cur.isEmpty()) {
            game.setStoredValue(key, entry);
        } else {
            game.setStoredValue(key, cur + ";" + entry);
        }
    }

    public static boolean removeDefinition(Game game, String type, String slug) {
        String key = "event".equalsIgnoreCase(type) ? DEFS_EVENTS : DEFS_STATES;
        String cur = game.getStoredValue(key);
        if (cur == null || cur.isEmpty()) return false;
        List<String> parts = Arrays.stream(cur.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.startsWith(slug + "|"))
                .collect(Collectors.toList());
        game.setStoredValue(key, String.join(";", parts));
        // also remove from rank defs
        String rankCur = game.getStoredValue(RANK_DEFS);
        if (rankCur != null && !rankCur.isEmpty()) {
            List<String> rankParts = new ArrayList<>();
            for (String r : rankCur.split(";")) {
                if (r.isBlank()) continue;
                String[] p = r.split("\\|", -1);
                String evs = p.length > 1 ? p[1] : "";
                String sts = p.length > 2 ? p[2] : "";
                List<String> evList = evs.isEmpty() ? new ArrayList<>() : Arrays.stream(evs.split(",")).filter(x->!x.isBlank()).filter(x->!x.equals(slug)).collect(Collectors.toList());
                List<String> stList = sts.isEmpty() ? new ArrayList<>() : Arrays.stream(sts.split(",")).filter(x->!x.isBlank()).filter(x->!x.equals(slug)).collect(Collectors.toList());
                String rebuilt = p[0] + "|" + String.join(",", evList) + "|" + String.join(",", stList);
                rankParts.add(rebuilt);
            }
            game.setStoredValue(RANK_DEFS, String.join(";", rankParts));
        }
        return true;
    }

    public static String slugify(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static void summarizeImperatorCycle(Game game) {
        StringBuilder sb = new StringBuilder("### Imperator Cycle Results\n");
        for (Player p : game.getRealPlayers()) {
            String rk = game.getStoredValue(RANK_KEY_PREFIX + p.getFaction());
            if (rk == null || rk.isEmpty()) rk = "0";
            sb.append(p.getFactionEmoji()).append(" ").append(p.getRepresentation()).append(": ").append(rk).append('\n');
        }
        MessageHelper.sendMessageToChannel(game.getMainGameChannel(), sb.toString());
    }

    public static class Def {
        public final String slug;
        public final String name;
        public final String desc;

        public Def(String slug, String name, String desc) {
            this.slug = slug;
            this.name = name;
            this.desc = desc;
        }
    }

    public static class RankDef {
        public final int rank;
        public final List<String> events;
        public final List<String> states;

        public RankDef(int rank, List<String> events, List<String> states) {
            this.rank = rank;
            this.events = events;
            this.states = states;
        }
    }
}
