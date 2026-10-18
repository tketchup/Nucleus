package de.nucleus.util;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;

import java.util.Set;

/**
 * Verbindet {@link SafeLocationFinder} mit einer echten Welt. Nur auf dem Hauptthread benutzen.
 */
public final class WorldBlockView implements SafeLocationFinder.BlockView {

    private static final Set<BlockType> HAZARDS = Set.of(
            BlockType.LAVA, BlockType.FIRE, BlockType.SOUL_FIRE, BlockType.MAGMA_BLOCK,
            BlockType.CAMPFIRE, BlockType.SOUL_CAMPFIRE, BlockType.CACTUS, BlockType.SWEET_BERRY_BUSH,
            BlockType.WITHER_ROSE, BlockType.POWDER_SNOW, BlockType.POINTED_DRIPSTONE, BlockType.COBWEB,
            BlockType.NETHER_PORTAL, BlockType.END_PORTAL, BlockType.END_GATEWAY, BlockType.BUBBLE_COLUMN);

    private final World world;

    public WorldBlockView(World world) {
        this.world = world;
    }

    @Override
    @SuppressWarnings("deprecation") // Block#getType liefert in 26.3 noch Material, asBlockType() ist der Weg zu BlockType
    public SafeLocationFinder.BlockKind kind(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        BlockType type = block.getType().asBlockType();
        if (type == null || HAZARDS.contains(type)) {
            return SafeLocationFinder.BlockKind.HAZARD;
        }
        if (type == BlockType.WATER) {
            return SafeLocationFinder.BlockKind.LIQUID;
        }
        return block.isPassable() ? SafeLocationFinder.BlockKind.EMPTY : SafeLocationFinder.BlockKind.SOLID;
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }
}
