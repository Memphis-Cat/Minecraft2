package com.memphiscat.legacyculling.renderer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PistonExtensionBlock;
import net.minecraft.block.PistonHeadBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkProvider;

/** Shared full-cube rules used by object rays and section portals. */
public final class OccluderRules {
    private static final double EPSILON = 0.002D;

    private OccluderRules() {
    }

    public static boolean isChunkLoaded(World world, int blockX, int blockZ) {
        if (world == null) return false;
        ChunkProvider provider = world.getChunkProvider();
        return provider != null && provider.chunkExists(blockX >> 4, blockZ >> 4);
    }

    public static boolean isTrustedFullCube(World world, BlockPos pos) {
        return trustedBox(world, pos) != null;
    }

    public static Box trustedBox(World world, BlockPos pos) {
        if (world == null || pos == null || !isChunkLoaded(world, pos.getX(), pos.getZ())) return null;
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == null || block == Blocks.AIR || block == Blocks.BARRIER) return null;
        if (block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock
                || block instanceof PistonBlock || block instanceof PistonHeadBlock
                || block instanceof PistonExtensionBlock) {
            return null;
        }
        Material material = block.getMaterial();
        if (material == null || material.isFluid() || material.isTranslucent() || !material.isSolid()
                || !material.blocksMovement() || !material.isOpaque()) {
            return null;
        }
        if (block.isLeafBlock() || block.hasTransparency() || block.isTranslucent()
                || !block.isFullBlock() || !block.isFullCube() || !block.isNormalBlock()
                || !block.renderAsNormalBlock() || block.getOpacity() < 255 || block.hasBlockEntity()) {
            return null;
        }
        Box collision = block.getCollisionBox(world, pos, state);
        if (collision == null) return null;
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        if (collision.minX > minX + EPSILON || collision.minY > minY + EPSILON
                || collision.minZ > minZ + EPSILON
                || collision.maxX < minX + 1.0D - EPSILON
                || collision.maxY < minY + 1.0D - EPSILON
                || collision.maxZ < minZ + 1.0D - EPSILON) {
            return null;
        }
        return collision;
    }
}
