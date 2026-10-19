package ti4.discord.interactions.commands.homebrew.imperator;

import java.util.Arrays;
import java.util.List;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import ti4.discord.interactions.commands.GameStateSubcommand;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.Constants;
import ti4.message.MessageHelper;
import ti4.service.game.ImperatorService;

class ImperatorEventAdd extends GameStateSubcommand {

    ImperatorEventAdd() {
        super("event_add", "Add an Imperator event definition", true, true);
        addOptions(new OptionData(OptionType.STRING, "name", "Event name").setRequired(true));
        addOptions(new OptionData(OptionType.STRING, "description", "Event description").setRequired(true));
        addOptions(new OptionData(OptionType.INTEGER, "rank", "Optional rank to assign this event to").setRequired(false));
        addOptions(new OptionData(OptionType.STRING, Constants.FACTION_COLOR, "Target faction or color").setAutoComplete(true).setRequired(false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Game game = getGame();
        Player caller = getPlayer();
        String name = event.getOption("name").getAsString();
        String desc = event.getOption("description").getAsString();
        Integer rank = event.getOption("rank", null, OptionMapping::getAsInt);
        String target = event.getOption(Constants.FACTION_COLOR, null, OptionMapping::getAsString);
        String slug = ImperatorService.slugify(name);
        ImperatorService.addDefinition(game, "event", slug, name, desc);
        if (rank != null) {
            ImperatorService.addRankDefinition(game, rank, Arrays.asList(slug), List.of());
        }
        Player targetPlayer = caller;
        if (target != null && !target.isEmpty()) {
            Player p = game.getPlayerFromColorOrFaction(target);
            if (p != null) targetPlayer = p;
        }
        MessageHelper.sendMessageToChannel(targetPlayer.getCardsInfoThread(), "Added imperator event: " + name);
    }
}
