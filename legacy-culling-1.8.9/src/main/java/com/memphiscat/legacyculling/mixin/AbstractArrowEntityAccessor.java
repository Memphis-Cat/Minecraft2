package com.memphiscat.legacyculling.mixin;

import net.minecraft.entity.projectile.AbstractArrowEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractArrowEntity.class)
public interface AbstractArrowEntityAccessor {
    @Accessor("inGround")
    boolean legacyculling$isInGround();
}
