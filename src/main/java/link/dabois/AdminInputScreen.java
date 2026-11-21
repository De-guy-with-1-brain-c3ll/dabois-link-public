package link.dabois;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AdminInputScreen extends Screen {
    record Field(String label, String value, int length) {}

    private final Screen parent;
    private final List<Field> fields;
    private final List<String> values = new ArrayList<>();
    private final Consumer<List<String>> save;
    private final String saveLabel;

    AdminInputScreen(Screen parent, String title, List<Field> fields, String saveLabel, Consumer<List<String>> save) {
        super(Component.literal(title));
        this.parent = parent;
        this.fields = fields;
        this.save = save;
        this.saveLabel = saveLabel;
        fields.forEach(field -> values.add(field.value));
    }

    private int formWidth() {
        return Math.min(440, width - 24);
    }

    private int formTop() {
        return Math.max(42, (height - (fields.size() * 42 + 32)) / 2);
    }

    @Override
    protected void init() {
        int x = (width - formWidth()) / 2;
        int y = formTop();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int index = i;
            var input = new EditBox(font, x, y + 13, formWidth(), 20, Component.literal(field.label));
            input.setMaxLength(field.length);
            input.setValue(values.get(i));
            input.setResponder(next -> values.set(index, next));
            addRenderableWidget(input);
            if (i == 0) {
                setInitialFocus(input);
            }
            y += 42;
        }
        addRenderableWidget(Button.builder(Component.literal(saveLabel), button -> {
            onClose();
            save.accept(List.copyOf(values));
        }).bounds(width / 2 - 103, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 + 3, y, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 20, 0xffffffff);
        int x = (width - formWidth()) / 2;
        for (int i = 0; i < fields.size(); i++) {
            graphics.text(font, font.plainSubstrByWidth(fields.get(i).label, formWidth()),
                    x, formTop() + i * 42, 0xffcccccc);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
