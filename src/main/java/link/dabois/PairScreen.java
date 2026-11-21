package link.dabois;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

public final class PairScreen extends Screen {
    private final Screen previous;
    private final Screen multiplayerParent;
    private EditBox code;
    private Button submit;
    private Component status = Component.literal("Enter the four-digit code from your host.");
    private boolean busy;
    private final String deviceToken = Link.secret();

    public PairScreen(Screen previous, Screen multiplayerParent) {
        super(Component.literal("Pair server"));
        this.previous = previous;
        this.multiplayerParent = multiplayerParent;
    }

    @Override
    protected void init() {
        String value = code == null ? "" : code.getValue();
        code = addRenderableWidget(new EditBox(font, width / 2 - 55, height / 2 - 16,
                110, 20, Component.literal("Pairing code")));
        code.setMaxLength(4);
        code.setValue(value);
        submit = addRenderableWidget(Button.builder(Component.literal("Pair"), button -> pair())
                .bounds(width / 2 - 105, height / 2 + 16, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 + 5, height / 2 + 16, 100, 20).build());
        submit.active = !busy;
        setInitialFocus(code);
    }

    private void pair() {
        String entered = code.getValue();
        if (!entered.matches("[0-9]{4}")) {
            status = Component.literal("Enter all four digits.");
            return;
        }
        String name = minecraft.getUser().getName();
        busy = true;
        submit.active = false;
        status = Component.literal("Connecting…");
        Thread.startVirtualThread(() -> {
            try {
                Guest.Invite invite = Guest.Invite.packaged();
                configureOverlay(invite);
                Guest.request(invite, name, entered, deviceToken);
                Guest.remember(invite, storage(invite), deviceToken);
                minecraft.execute(() -> {
                    busy = false;
                    minecraft.gui.setScreen(new JoinMultiplayerScreen(multiplayerParent));
                });
            } catch (IOException | RuntimeException e) {
                minecraft.execute(() -> {
                    status = Component.literal(e.getMessage() == null ? "Pairing failed." : e.getMessage());
                    busy = false;
                    submit.active = true;
                });
            }
        });
    }

    static Path storage(Guest.Invite invite) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(HostSetup.DIRECTORY)
                .resolve("paired-" + invite.fingerprint() + ".properties");
    }

    public static boolean addSavedServer(ServerList servers) {
        try {
            Guest.Invite invite = Guest.Invite.packaged();
            configureOverlay(invite);
            String address = Guest.resume(invite, storage(invite));
            if (address == null || servers.get(address) != null) {
                return false;
            }
            servers.add(new ServerData(invite.name(), address, ServerData.Type.OTHER), false);
            servers.save();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void configureOverlay(Guest.Invite invite) {
        EmbeddedZeroTier.startClient(Minecraft.getInstance().gameDirectory.toPath()
                .resolve(HostSetup.DIRECTORY), invite.overlay());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 63, 0xffffffff);
        graphics.centeredText(font, status.getString().length() > 85
                ? status.getString().substring(0, 82) + "…" : status.getString(),
                width / 2, height / 2 - 41, 0xffffffff);
        String network = EmbeddedZeroTier.status();
        if (!network.isEmpty()) {
            graphics.centeredText(font, font.plainSubstrByWidth(network, width - 16),
                    width / 2, height / 2 + 49, 0xffaaaaaa);
        }
    }

    @Override
    public void onClose() {
        if (!busy) {
            minecraft.gui.setScreen(previous);
        }
    }
}
