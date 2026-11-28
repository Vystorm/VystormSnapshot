package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import org.bukkit.entity.Player;

/**
 * Everything that names a type of Vystorm Core 0.71.0 (player picker, pick return). Only reached after
 * {@code CoreApi.has("player-picker")}, so the plugin still loads on Core 0.66.0 to 0.70.x.
 */
final class SnapshotPicks {
    private SnapshotPicks() {}

    /**
     * "Send as postcard" on the album screen: the pick sends the card at once (the greeting was typed in the screen)
     * and the picker comes back to the album with the same photo selected. A refused card keeps the picker open with
     * the reason.
     */
    static Result postcard(API core, SnapshotPlugin plugin, Lang lang, PanelAction action, int map, String greeting, Runnable sent) {
        PlayerPick pick = builder(lang).onPick((viewer, picks) -> {
            if (picks.chosen().isEmpty()) return Result.ok();
            PickedPlayer chosen = picks.chosen().getFirst();
            SnapshotPlugin.Msg msg = plugin.postcard(viewer, map, plugin.byId(chosen.id()), chosen.name(), greeting);
            String text = lang.plain(viewer, msg.key(), msg.args());
            if (!msg.ok()) return Result.error(text);
            sent.run();
            return Result.ok(text);
        }).build();
        return core.gui().pickers().players(action, pick);
    }

    /** "Send as postcard" in the album chest: pick, then the greeting prompt, then {@code back} (the chest again). */
    static void postcardFromChest(API core, SnapshotPlugin plugin, Lang lang, Player player, int map, Runnable back) {
        PickedPlayer[] chosen = new PickedPlayer[1];
        PlayerPick pick = builder(lang).onPick((viewer, picks) -> {
            if (!picks.chosen().isEmpty()) chosen[0] = picks.chosen().getFirst();
            return Result.ok();
        }).build();
        core.gui().pickers().players(plugin, player, pick, PickReturn.to(p -> {
            if (chosen[0] == null) back.run();
            else plugin.greetingThenSend(p, map, plugin.byId(chosen[0].id()), chosen[0].name(), back);
        }));
    }

    private static PlayerPick.Builder builder(Lang lang) {
        return PlayerPick.builder(lang.ref("panel.postcard-pick-title"))
                .subtitle(lang.ref("panel.postcard-pick-subtitle"))
                .includeSelf(false)
                .single();
    }
}
