package ti4.service.game;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.components.buttons.Button;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.Helper;
import ti4.message.MessageHelper;
import ti4.service.game.ImperatorService;

@UtilityClass
public class ImperatorService {

    private static final String ORDER_KEY = "imperator_order";
    private static final String INDEX_KEY = "imperator_index";
    private static final String RANK_KEY_PREFIX = "imperator_rank_"; // persistent per-player
    private static final String THIS_ROUND_PREFIX = "imperator_thisround_"; // ephemeral
    private static final String PASSED_PREFIX = "imperator_passed_"; // ephemeral
    private static final String REQ_EVENT_PREFIX = "imperator_req_event_"; // ephemeral
    private static final String REQ_STATE_PREFIX = "imperator_req_state_"; // ephemeral
    private static final String REQ_OTHER_PREFIX = "imperator_req_other_"; // ephemeral

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

        offerNextPlayerButtons(game);
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
        // Requirement buttons (players can use these to mark they met things)
        buttons.add(Buttons.gray("imperator_req_event_" + faction, "Met: event (during round)"));
        buttons.add(Buttons.gray("imperator_req_state_" + faction, "Met: state (now)"));
        buttons.add(Buttons.gray("imperator_req_other_" + faction, "Met: other"));
        // Action buttons
        buttons.add(Buttons.green("imperator_rankup_" + faction, "Rank Up"));
        buttons.add(Buttons.red("imperator_pass_" + faction, "Pass"));

        String msg = player.getRepresentation() + ", if you meet the Imperator requirements, you may Rank Up now."
                + " Use the buttons to mark which requirements you met, or press Rank Up to consume them and increase your imperator rank."
                + " If you decline, press Pass.";

        MessageHelper.sendMessageToChannelWithButtons(player.getCardsInfoThread(), msg, buttons);
    }

    public static void advanceImperator(Game game) {
        int idx = getIndex(game);
        setIndex(game, idx + 1);
        offerNextPlayerButtons(game);
    }

    public static void applyRankUp(Game game, Player player) {
        String key = RANK_KEY_PREFIX + player.getFaction();
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
}
