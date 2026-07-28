package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.gui.LegacyCullingOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    @Inject(method = "init", at = @At("RETURN"))
    private void legacyculling$addOptionsButton(CallbackInfo ci) {
        buttons.add(new ButtonWidget(18989, width - 155, 5, 150, 20, "Legacy Culling..."));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void legacyculling$openOptions(ButtonWidget button, CallbackInfo ci) {
        if (button.id == 18989) {
            client.setScreen(new LegacyCullingOptionsScreen((Screen) (Object) this));
            ci.cancel();
        }
    }
}
