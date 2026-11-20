package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.panel.*;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pure, shared layout for Snapshot screens. Services and viewer state live in SnapshotPanels. */
final class SnapshotPanel {
    record Button(String id, String label, Material icon, String value, boolean danger, boolean confirm, boolean leave) {}
    record Row(String id, String title, String subtitle, Material icon, List<Stat> stats, List<Button> buttons) {}
    record Stat(String label, String value) {}
    record Option(String id, String label) {}
    /** One camera setting; {@code description} is the line under its name in the settings list. */
    record Choice(String id, String label, String selected, List<Option> options, String description) {
        Choice(String id, String label, String selected, List<Option> options) { this(id, label, selected, options, ""); }
    }
    record View(String title, String subtitle, Material icon, List<Row> rows, String selected,
                int page, int pages, String search, List<Choice> choices, List<Button> footer) {}
    interface Actions { Result run(Player player, String action, String value); }

    static Panel render(PanelContext ctx, View view, Actions actions) {
        Panel panel = Panel.page(view.title()).subtitle(view.subtitle()).icon(Ui.item(view.icon()))
                .size(view.choices().isEmpty() ? Panel.Size.LARGE : Panel.Size.MEDIUM);
        if (view.search() != null) panel.child(Ui.input("search", ctx.text("panel.search")).value(view.search()).maxLength(200)
                .search((a, value) -> change(a, actions, "search", value)));
        if (!view.choices().isEmpty()) {
            // camera settings: one row each on Vystorm Screens (Core 0.66.0 list layout)
            Ui.Section settings = Ui.section(ctx.text("panel.camera-settings")).list().id("settings");
            for (Choice choice : view.choices()) {
                Ui.Select select = Ui.select(choice.id(), choice.label()).value(choice.selected())
                        .onChange((a, value) -> change(a, actions, choice.id(), value));
                for (Option option : choice.options()) select.option(option.id(), option.label());
                if (!choice.description().isBlank()) select.tooltip(choice.description());
                settings.child(select);
            }
            panel.child(settings);
        } else if (view.rows().isEmpty()) panel.child(Ui.text(ctx.text("panel.empty")).id("empty"));
        else {
            Row chosen = view.rows().stream().filter(r -> r.id().equals(view.selected())).findFirst().orElse(view.rows().getFirst());
            Ui.ListView list = Ui.list("entries").items(view.rows(), row -> Ui.listItem(row.id(), row.title())
                            .subtitle(row.subtitle()).icon(Ui.item(row.icon())))
                    .selected(chosen.id()).grow(2).onSelect((a, value) -> change(a, actions, "select", value));
            Ui.Box detail = Ui.col().gap(8).grow(3).child(Ui.heading(chosen.title()).level(2));
            Ui.Kv stats = Ui.kv().id("details");
            for (Stat stat : chosen.stats()) stats.row(stat.label(), stat.value());
            detail.child(stats);
            for (Button button : chosen.buttons()) detail.child(button(ctx, button, actions));
            panel.child(Ui.row().gap(8).children(list, detail));
        }
        if (view.pages() > 1) {
            panel.child(Ui.badge(ctx.text("panel.page", "page", view.page() + 1, "pages", view.pages())).id("page"));
            panel.footer(Ui.button("previous").label(ctx.text("collections.previous")).icon(Ui.item(Material.ARROW)).ghost()
                    .disabled(view.page() == 0).onClick(a -> change(a, actions, "page", Integer.toString(view.page() - 1))),
                    Ui.button("next").label(ctx.text("collections.next")).icon(Ui.item(Material.ARROW)).ghost()
                    .disabled(view.page() + 1 >= view.pages()).onClick(a -> change(a, actions, "page", Integer.toString(view.page() + 1))));
        }
        for (Button button : view.footer()) panel.footer(button(ctx, button, actions));
        return panel;
    }

    private static Ui.Button button(PanelContext ctx, Button spec, Actions actions) {
        Ui.Button button = Ui.button(spec.id()).label(spec.label()).icon(Ui.item(spec.icon()));
        if (spec.danger()) button.danger(); else if (spec.leave()) button.ghost(); else button.primary();
        if (spec.confirm()) button.confirm(spec.label(), ctx.text(spec.id().equals("delete") ? "collections.delete-confirm"
                : spec.id().startsWith("print") ? "panel.print-confirm" : "panel.confirm"));
        return button.onClick(a -> {
            if (spec.leave()) { a.closeAndThen(p -> actions.run(p, spec.id(), spec.value())); return Result.ok(); }
            return change(a, actions, spec.id(), spec.value());
        });
    }

    private static Result change(PanelAction a, Actions actions, String key, String value) {
        Result result = actions.run(a.player(), key, value);
        a.refresh();
        return result;
    }
}
