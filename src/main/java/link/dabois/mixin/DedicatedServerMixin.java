package link.dabois.mixin;

import link.dabois.ServerCommands;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("RETURN"))
    private void daboisLink$start(CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue()) {
            ServerCommands.start((DedicatedServer) (Object) this);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void daboisLink$stop(CallbackInfo callback) {
        ServerCommands.stop();
    }
}
