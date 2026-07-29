package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.renderer.NativeMeshCapture;
import net.minecraft.client.render.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class VertexBufferCaptureMixin {
    @Inject(method = "data(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void legacyculling$captureUpload(ByteBuffer data, CallbackInfo ci) {
        NativeMeshCapture.capture((VertexBuffer) (Object) this, data);
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void legacyculling$forgetDeletedBuffer(CallbackInfo ci) {
        NativeMeshCapture.remove((VertexBuffer) (Object) this);
    }
}
