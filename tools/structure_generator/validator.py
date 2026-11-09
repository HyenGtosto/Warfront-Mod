"""
Structure Template Validator for Warfront.
Inspects generated or hand-carved .nbt files against the HQ slot contract.
"""

import sys
from nbt_writer import read_structure_template

PALETTE_WHITELIST = {
    "minecraft:air",
    "minecraft:cobblestone",
    "minecraft:mossy_cobblestone",
    "minecraft:cobblestone_stairs",
    "minecraft:cobblestone_slab",
    "minecraft:cobblestone_wall",
    "minecraft:stone",
    "minecraft:cobbled_deepslate",
    "minecraft:cobbled_deepslate_stairs",
    "minecraft:cobbled_deepslate_slab",
    "minecraft:cobbled_deepslate_wall",
    "minecraft:ladder",
    "warfront:pillager_ballista",
    "warfront:heavy_mortar",
    "minecraft:stone_bricks",
    "minecraft:mossy_stone_bricks",
    "minecraft:cracked_stone_bricks",
    "minecraft:chiseled_stone_bricks",
    "minecraft:stone_brick_stairs",
    "minecraft:stone_brick_slab",
    "minecraft:stone_brick_wall",
    "minecraft:deepslate_bricks",
    "minecraft:cracked_deepslate_bricks",
    "minecraft:deepslate_brick_stairs",
    "minecraft:deepslate_brick_slab",
    "minecraft:deepslate_brick_wall",
    "minecraft:polished_deepslate",
    "minecraft:polished_deepslate_stairs",
    "minecraft:polished_deepslate_slab",
    "minecraft:polished_deepslate_wall",
    "minecraft:deepslate_tiles",
    "minecraft:cracked_deepslate_tiles",
    "minecraft:deepslate_tile_stairs",
    "minecraft:deepslate_tile_slab",
    "minecraft:deepslate_tile_wall",
    "minecraft:chiseled_deepslate",
    "minecraft:dark_oak_log",
    "minecraft:stripped_dark_oak_log",
    "minecraft:dark_oak_wood",
    "minecraft:stripped_dark_oak_wood",
    "minecraft:dark_oak_planks",
    "minecraft:dark_oak_stairs",
    "minecraft:dark_oak_slab",
    "minecraft:dark_oak_fence",
    "minecraft:dark_oak_fence_gate",
    "minecraft:dark_oak_door",
    "minecraft:dark_oak_trapdoor",
    "minecraft:spruce_planks",
    "minecraft:spruce_stairs",
    "minecraft:spruce_slab",
    "minecraft:spruce_fence",
    "minecraft:spruce_trapdoor",
    "minecraft:iron_bars",
    "minecraft:chain",
    "minecraft:iron_block",
    "minecraft:iron_door",
    "minecraft:iron_trapdoor",
    "minecraft:heavy_weighted_pressure_plate",
    "minecraft:lightning_rod",
    "minecraft:gravel",
    "minecraft:coarse_dirt",
    "minecraft:packed_mud",
    "minecraft:red_carpet",
    "minecraft:yellow_carpet",
    "minecraft:black_carpet",
    "minecraft:gray_carpet",
    "minecraft:red_wool",
    "minecraft:green_wool",
    "minecraft:brown_wool",
    "minecraft:blue_wool",
    "minecraft:black_wool",
    "minecraft:gray_wool",
    "minecraft:white_banner",
    "minecraft:gray_banner",
    "minecraft:black_banner",
    "minecraft:red_banner",
    "minecraft:white_wall_banner",
    "minecraft:gray_wall_banner",
    "minecraft:black_wall_banner",
    "minecraft:red_wall_banner",
    "minecraft:lantern",
    "minecraft:soul_lantern",
    "minecraft:torch",
    "minecraft:wall_torch",
    "minecraft:redstone_torch",
    "minecraft:campfire",
    "minecraft:soul_campfire",
    "minecraft:wither_skeleton_skull",
    "minecraft:skeleton_skull",
    "minecraft:chest",
    "minecraft:trapped_chest",
    "minecraft:barrel",
    "minecraft:crafting_table",
    "minecraft:cartography_table",
    "minecraft:fletching_table",
    "minecraft:smithing_table",
    "minecraft:loom",
    "minecraft:lectern",
    "minecraft:bookshelf",
    "minecraft:chiseled_bookshelf",
    "minecraft:anvil",
    "minecraft:chipped_anvil",
    "minecraft:damaged_anvil",
    "minecraft:grindstone",
    "minecraft:cauldron",
    "minecraft:lava_cauldron",
    "minecraft:water_cauldron",
    "minecraft:bell",
    "minecraft:target",
    "minecraft:tnt",
    "minecraft:hay_block",
    "minecraft:LODESTONE".lower(),
    "minecraft:blast_furnace",
    "minecraft:smoker",
    "minecraft:furnace",
    "minecraft:stonecutter",
}


def validate_template(file_path: str, expected_type: str = "building") -> tuple[bool, list[str]]:
    """
    Validates an NBT template against slot contract rules.
    expected_type: 'shell' (50x90) or 'building' (<=40x40)
    Returns: (is_valid, list_of_errors_or_warnings)
    """
    issues = []
    try:
        data = read_structure_template(file_path)
    except Exception as e:
        return False, [f"Failed to read/parse NBT: {e}"]

    size = data.get("size")
    if not size or len(size) != 3:
        return False, [f"Invalid or missing 'size' tag: {size}"]

    sx, sy, sz = size
    palette = [p["Name"] for p in data.get("palette", [])]
    blocks = data.get("blocks", [])

    # 1. Bounds check
    if expected_type == "shell":
        if (sx, sz) != (52, 93) and (sx, sz) != (93, 52):
            issues.append(f"Canonical shell dimensions must be 52x93 (or 93x52), but got {sx}x{sz} (height {sy}).")
        if sy > 32:
            issues.append(f"Shell height ({sy}) exceeds maximum budget of 32 blocks.")
    elif expected_type == "building":
        if sx > 40 or sz > 40:
            issues.append(f"Building dimensions exceed 40x40 maximum slot! Got {sx}x{sz} (height {sy}).")
        if sy > 30:
            issues.append(f"Building height ({sy}) exceeds maximum budget of 30 blocks.")

    # 2. Block coordinate integrity
    for b in blocks:
        px, py, pz = b["pos"]
        if px < 0 or px >= sx or py < 0 or py >= sy or pz < 0 or pz >= sz:
            issues.append(f"Block at ({px}, {py}, {pz}) out of bounds [0..{sx}, 0..{sy}, 0..{sz}]!")
            break

    # 3. Palette whitelist check
    off_palette = [p for p in palette if p not in PALETTE_WHITELIST]
    if off_palette:
        issues.append(f"Off-palette blocks detected: {off_palette}")

    # 4. Air block efficiency
    air_idx = -1
    for i, p in enumerate(palette):
        if p == "minecraft:air":
            air_idx = i
            break

    air_count = sum(1 for b in blocks if b["state"] == air_idx)
    solid_count = len(blocks) - air_count

    if solid_count == 0:
        issues.append("Template contains zero solid blocks!")

    # Doorway check for shell
    if expected_type == "shell":
        block_grid = {}
        for b in blocks:
            block_grid[tuple(b["pos"])] = palette[b["state"]]

        # Check South Gate passage (X: 23..26, Z: 86..88, Y: 1..3) is not blocked by solid walls
        south_blocked = False
        for x in range(23, 27):
            for z in range(86, 89):
                for y in range(1, 4):
                    st = block_grid.get((x, y, z), "minecraft:air")
                    if st != "minecraft:air":
                        south_blocked = True
                        break
        if south_blocked:
            issues.append("Warning: South main gate passage (X:23..26, Z:86..88, Y:1..3) contains solid blocks!")

    is_valid = len([i for i in issues if not i.startswith("Warning:")]) == 0
    return is_valid, issues


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python validator.py <path_to_template.nbt> [shell|building]")
        sys.exit(1)

    path = sys.argv[1]
    b_type = sys.argv[2] if len(sys.argv) > 2 else "building"
    valid, msgs = validate_template(path, b_type)
    print(f"Validation for {path} ({b_type}): {'PASS' if valid else 'FAIL'}")
    for m in msgs:
        print(f" - {m}")
    sys.exit(0 if valid else 1)
