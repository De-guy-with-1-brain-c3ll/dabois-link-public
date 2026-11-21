package link.dabois;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AdminScreen extends Screen {
    private enum Tab { Players, Worlds, Mods, Settings }

    private enum Role {
        SUPER("superop", "Grant Super Operator"),
        REMOVE_SUPER("remove-superop", "Remove Super Operator"),
        OWNER("4", "Operator level 4"),
        ADMIN("3", "Operator level 3"),
        GAMEMASTER("2", "Operator level 2"),
        MODERATOR("1", "Operator level 1"),
        PLAYER("0", "Regular player");

        final String id, label;

        Role(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final List<String> PLAYER_LISTS = List.of("players", "superoperators", "operators", "bans");
    private static final List<String> DIFFICULTIES = List.of("peaceful", "easy", "normal", "hard");
    private static final int ROW_HEIGHT = 23;
    private static final int WHITE = 0xffffffff;
    private static final int MUTED = 0xffc8c8c8;
    private static final int ERROR = 0xffff7777;

    private final Screen parent;
    private JsonObject snapshot;
    private Tab tab = Tab.Players;
    private String status = "Contacting the server…";
    private boolean busy, error, started, restarting;
    private String player = "";
    private String playerList = "players";
    private String difficulty = "normal";
    private String filter = "";
    private String filterDraft = "";
    private Role role = Role.SUPER;
    private int page, pageCount;

    public AdminScreen(Screen parent) {
        super(Component.literal("DABOIS Server Admin"));
        this.parent = parent;
    }

    private int panelWidth() {
        return Math.min(520, width - 20);
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    @Override
    protected void init() {
        int x = left();
        int panel = panelWidth();
        int tabWidth = panel / Tab.values().length;
        for (Tab choice : Tab.values()) {
            button(choice.name(), x + choice.ordinal() * tabWidth, 29, tabWidth - 4,
                    choice != tab, () -> select(choice));
        }
        int footer = height - 26;
        int quarter = panel / 4;
        boolean staged = snapshot != null && flag("staged");
        boolean ready = staged && !busy && !restarting && !flag("applying");
        button("Apply & restart", x, footer, quarter - 4, ready && flag("canApply"), this::confirmRestart);
        button("Discard staged", x + quarter, footer, quarter - 4, ready, () -> action("discard"));
        button("Refresh", x + quarter * 2, footer, quarter - 4, !busy, () -> action("snapshot"));
        button("Back", x + quarter * 3, footer, quarter - 4, true, this::onClose);

        if (snapshot != null) {
            switch (tab) {
                case Players -> players(x, panel);
                case Worlds -> worlds(x, panel);
                case Mods -> mods(x, panel);
                case Settings -> settings(x, panel);
            }
        }
        if (!started) {
            started = true;
            var game = minecraft;
            game.schedule(() -> action("snapshot"));
        }
    }

    private void select(Tab selected) {
        tab = selected;
        page = 0;
        rebuildWidgets();
    }

    private void players(int x, int panel) {
        int half = panel / 2;
        input("Player name", player, 16, x, 59, half - 5, value -> player = value);
        cycle(x + half, 59, half - 4, playerList, PLAYER_LISTS, "List",
                AdminScreen::playerListLabel, value -> {
                    playerList = value;
                    page = 0;
                    rebuildWidgets();
                });
        cycle(x, 82, panel - 4, role, List.of(Role.values()), "Role",
                value -> value.label, value -> role = value);
        int third = panel / 3;
        button("Set role", x, 105, third - 4, !busy, () ->
                withPlayer("role", request -> request.addProperty("role", role.id)));
        button("Ban", x + third, 105, third - 4, !busy, this::ban);
        button("Unban", x + 2 * third, 105, third - 4, !busy, () -> withPlayer("pardon", request -> { }));
        rows(strings(playerList), 129, (name, y) -> button(name, x, y, panel - 4, !busy, () -> {
            player = name;
            rebuildWidgets();
        }));
    }

    private void worlds(int x, int panel) {
        int half = panel / 2;
        cycle(x, 73, half - 4, difficulty, DIFFICULTIES, "Difficulty",
                name -> name, changed -> difficulty = changed);
        button("Set difficulty", x + half, 73, half - 4, !busy, () -> {
            JsonObject request = request("difficulty");
            request.addProperty("difficulty", difficulty);
            request(request);
        });
        button("Create new world", x, 96, panel - 4, !busy, () -> form("New world",
                List.of(new AdminInputScreen.Field("World name", "", 40), new AdminInputScreen.Field("Seed (optional)", "", 128)),
                "Stage world", values -> world(values.get(0).strip(), values.get(1), true)));
        rows(strings("worlds"), 120, (name, y) -> button("Stage switch to " + name, x, y, panel - 4,
                !busy, () -> world(name, "", false)));
    }

    private void mods(int x, int panel) {
        var installed = snapshot.getAsJsonObject("mods");
        rows(installed.keySet().stream().sorted().toList(), 71, (name, y) -> {
            boolean enabled = installed.get(name).getAsBoolean();
            String label = (enabled ? "Stage disable: " : "Stage enable: ") + name;
            button(label, x, y, panel - 4, !busy, () -> {
                JsonObject request = request("mod");
                request.addProperty("mod", name);
                request.addProperty("enabled", !enabled);
                request(request);
            });
        });
    }

    private void settings(int x, int panel) {
        int search = panel - 78;
        input("Search settings", filterDraft, 64, x, 59, search - 4, value -> filterDraft = value);
        button("Filter", x + search, 59, 74, !busy, () -> {
            filter = filterDraft.strip().toLowerCase(Locale.ROOT);
            page = 0;
            rebuildWidgets();
        });
        var settings = snapshot.getAsJsonObject("settings");
        var keys = settings.keySet().stream().filter(key -> !Set.of("level-name", "level-seed").contains(key))
                .filter(key -> key.toLowerCase(Locale.ROOT).contains(filter)).sorted().toList();
        rows(keys, 83, (key, y) -> {
            String value = settings.get(key).getAsString();
            button(key + " = " + value, x, y, panel - 4, !busy,
                    () -> form("Edit setting", List.of(new AdminInputScreen.Field(key, value, 2000)),
                            "Stage setting", values -> {
                                JsonObject changes = new JsonObject();
                                changes.addProperty(key, values.getFirst());
                                JsonObject request = request("settings");
                                request.add("settings", changes);
                                request(request);
                            }));
        });
    }

    private void rows(List<String> items, int top, ConsumerRow row) {
        int count = Math.max(1, (height - 70 - top) / ROW_HEIGHT);
        pageCount = Math.max(1, (items.size() + count - 1) / count);
        page = Math.min(page, pageCount - 1);
        int start = page * count;
        for (int i = start; i < Math.min(start + count, items.size()); i++) {
            row.add(items.get(i), top + (i - start) * ROW_HEIGHT);
        }
        if (pageCount > 1) {
            button("Previous", left(), height - 63, 75, page > 0, () -> move(-1));
            button("Next", left() + panelWidth() - 79, height - 63, 75, page + 1 < pageCount, () -> move(1));
        }
    }

    private void move(int by) {
        page += by;
        rebuildWidgets();
    }

    private void input(String label, String value, int max, int x, int y, int width, Consumer<String> changed) {
        var field = new EditBox(font, x, y, width, 20, Component.literal(label));
        field.setMaxLength(max);
        field.setValue(value);
        field.setHint(Component.literal(label));
        field.setResponder(changed);
        addRenderableWidget(field);
    }

    private <T> void cycle(int x, int y, int width, T initial, List<T> values, String name,
                           Function<T, String> label, Consumer<T> changed) {
        var cycle = CycleButton.builder(value -> Component.literal(label.apply(value)), initial).withValues(values)
                .create(x, y, width, 20, Component.literal(name), (button, value) -> changed.accept(value));
        cycle.active = !busy;
        addRenderableWidget(cycle);
    }

    private void button(String text, int x, int y, int width, boolean enabled, Runnable click) {
        var widget = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(text, width - 8)),
                ignored -> click.run()).bounds(x, y, width, 20).build());
        widget.active = enabled;
        if (font.width(text) > width - 8) {
            widget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(text)));
        }
    }

    private void form(String title, List<AdminInputScreen.Field> fields, String label, Consumer<List<String>> save) {
        minecraft.gui.setScreen(new AdminInputScreen(this, title, fields, label, save));
    }

    private void ban() {
        if (!validPlayer()) {
            return;
        }
        String name = player.strip();
        form("Ban " + name, List.of(new AdminInputScreen.Field("Ban reason", "", 256)), "Ban player", values -> {
            JsonObject request = request("ban");
            request.addProperty("player", name);
            request.addProperty("reason", values.getFirst());
            request(request);
        });
    }

    private boolean validPlayer() {
        if (!Invitations.validName(player.strip())) {
            status = "Choose a player or enter their exact name.";
            error = true;
            return false;
        }
        return true;
    }

    private void withPlayer(String action, Consumer<JsonObject> extra) {
        if (!validPlayer()) {
            return;
        }
        JsonObject request = request(action);
        request.addProperty("player", player.strip());
        extra.accept(request);
        request(request);
    }

    private void world(String name, String seed, boolean create) {
        JsonObject request = request("world");
        request.addProperty("world", name);
        request.addProperty("seed", seed);
        request.addProperty("create", create);
        request(request);
    }

    private void confirmRestart() {
        minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            minecraft.gui.setScreen(this);
            if (yes) {
                action("apply");
            }
        }, Component.literal("Apply changes and restart?"),
                Component.literal("Online players will disconnect while the server restarts.")));
    }

    private void action(String name) {
        request(request(name));
    }

    private static JsonObject request(String action) {
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        return request;
    }

    private void request(JsonObject payload) {
        if (busy) {
            return;
        }
        busy = true;
        error = false;
        status = "Contacting the server…";
        rebuildWidgets();
        Thread.startVirtualThread(() -> {
            try {
                var invite = Guest.Invite.packaged();
                JsonObject response = AdminClient.request(invite, PairScreen.storage(invite), payload);
                minecraft.execute(() -> accept(payload.get("action").getAsString(), response));
            } catch (IOException | RuntimeException e) {
                minecraft.execute(() -> {
                    busy = false;
                    error = true;
                    status = e.getMessage() == null ? "Could not reach Server Admin." : e.getMessage();
                    rebuildWidgets();
                });
            }
        });
    }

    private void accept(String action, JsonObject response) {
        busy = false;
        restarting = response.has("restarting") && response.get("restarting").getAsBoolean();
        if (restarting) {
            status = "Restart requested. Refresh after the server is back.";
        } else {
            snapshot = response;
            difficulty = string(response, "difficulty", difficulty);
            String lastApply = string(response, "applyStatus", "");
            status = action.equals("snapshot") ? (lastApply.isBlank() ? "Connected." : lastApply) : "Change saved.";
        }
        rebuildWidgets();
    }

    private static String string(JsonObject data, String key, String fallback) {
        JsonElement value = data.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private boolean flag(String key) {
        return snapshot != null && snapshot.has(key) && snapshot.get(key).getAsBoolean();
    }

    private List<String> strings(String key) {
        var result = new ArrayList<String>();
        snapshot.getAsJsonArray(key).forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static String playerListLabel(String key) {
        return switch (key) {
            case "superoperators" -> "Super Operators";
            case "operators" -> "Operators";
            case "bans" -> "Banned";
            default -> "Known players";
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String header = snapshot == null ? title.getString() :
                title.getString() + " • " + string(snapshot, "admin", "");
        graphics.centeredText(font, header, width / 2, 11, WHITE);
        if (snapshot != null) {
            if (tab == Tab.Worlds) {
                var settings = snapshot.getAsJsonObject("settings");
                String live = string(snapshot, "liveWorld", "");
                String next = string(settings, "level-name", live);
                graphics.text(font, "Active: " + live + (live.equals(next) ? "" : "    Next: " + next), left(), 57, MUTED);
            } else if (tab == Tab.Mods) {
                graphics.text(font, "Mod changes take effect after applying and restarting.", left(), 55, MUTED);
            }
            if (pageCount > 1) {
                graphics.centeredText(font, "Page " + (page + 1) + " of " + pageCount, width / 2, height - 57, MUTED);
            }
        }
        String message = status + (flag("staged") && !restarting && !error ? "  •  Changes staged" : "");
        var lines = font.split(Component.literal(message), panelWidth() - 4);
        for (int i = 0; i < Math.min(lines.size(), 2); i++) {
            graphics.text(font, lines.get(i), left(), height - 43 + i * 9, error ? ERROR : MUTED);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private interface ConsumerRow {
        void add(String item, int y);
    }
}
