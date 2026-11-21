package link.dabois.mixin;

import link.dabois.ServerCommands;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
abstract class CommandsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void daboisLink$register(CallbackInfo callback) {
        ServerCommands.register(((Commands) (Object) this).getDispatcher());
    }
}
