package link.dabois.mixin;

import link.dabois.PairScreen;
import link.dabois.AdminScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
abstract class MultiplayerMixin extends Screen {
    @Shadow @Final private Screen lastScreen;
    @Shadow protected ServerSelectionList serverSelectionList;
    @Shadow public abstract ServerList getServers();

    protected MultiplayerMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void daboisLink$init(CallbackInfo callback) {
        addRenderableWidget(Button.builder(Component.literal("Pair server"), button ->
                minecraft.gui.setScreen(new PairScreen(this, lastScreen))).bounds(6, 4, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Server admin"), button ->
                minecraft.gui.setScreen(new AdminScreen(this))).bounds(width - 98, 4, 92, 20).build());
        ServerList servers = getServers();
        if (PairScreen.addSavedServer(servers)) {
            serverSelectionList.updateOnlineServers(servers);
        }
    }
}
