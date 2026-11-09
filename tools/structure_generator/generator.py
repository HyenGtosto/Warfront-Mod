"""
Warfront Structure Generator: Pillager Headquarters Modular Templates (Final-Product Edition).
Produces native Minecraft 1.21.1 Structure Templates (.nbt) adhering to hq_slot_contract.md.
Strictly outputs 4 single canonical NBT files (initial entrance facing SOUTH); rotation is handled in Java.
"""

import os
import random
from nbt_writer import write_structure_template


class StructureBuilder:
    """Constructs a 3D block grid with block state properties, weathering pass, and .nbt export."""

    def __init__(self, size_x: int, size_y: int, size_z: int):
        self.size_x = size_x
        self.size_y = size_y
        self.size_z = size_z
        self.palette = []
        self.palette_map = {}
        self.grid = {}

    def get_or_add_state(self, name: str, properties: dict = None) -> int:
        key = (name, tuple(sorted((properties or {}).items())))
        if key in self.palette_map:
            return self.palette_map[key]
        idx = len(self.palette)
        entry = {"Name": name}
        if properties:
            entry["Properties"] = properties
        self.palette.append(entry)
        self.palette_map[key] = idx
        return idx

    def set_block(self, x: int, y: int, z: int, name: str, properties: dict = None):
        if 0 <= x < self.size_x and 0 <= y < self.size_y and 0 <= z < self.size_z:
            state_idx = self.get_or_add_state(name, properties)
            self.grid[(x, y, z)] = state_idx

    def get_block_name(self, x: int, y: int, z: int) -> str:
        idx = self.grid.get((x, y, z))
        if idx is None:
            return "minecraft:air"
        return self.palette[idx]["Name"]

    def fill_box(self, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, name: str, properties: dict = None):
        min_x, max_x = min(x1, x2), max(x1, x2)
        min_y, max_y = min(y1, y2), max(y1, y2)
        min_z, max_z = min(z1, z2), max(z1, z2)
        state_idx = self.get_or_add_state(name, properties)
        for x in range(min_x, max_x + 1):
            for y in range(min_y, max_y + 1):
                for z in range(min_z, max_z + 1):
                    if 0 <= x < self.size_x and 0 <= y < self.size_y and 0 <= z < self.size_z:
                        self.grid[(x, y, z)] = state_idx

    def apply_weathering(self, seed: int = 42):
        """
        Pass 2 Tactile Surface Detail:
        Applies deterministic weighted weathering to full stone blocks while preserving stairs, logs, and trim.
        """
        rng = random.Random(seed)
        for (x, y, z), state_idx in list(self.grid.items()):
            entry = self.palette[state_idx]
            if "Properties" in entry and entry["Properties"]:
                continue
            name = entry["Name"]
            r = rng.random()
            if name == "minecraft:deepslate_bricks":
                if r < 0.18:
                    self.set_block(x, y, z, "minecraft:cracked_deepslate_bricks")
                elif r < 0.24:
                    self.set_block(x, y, z, "minecraft:deepslate_tiles")
            elif name == "minecraft:stone_bricks":
                if y <= 2 and r < 0.14:
                    self.set_block(x, y, z, "minecraft:mossy_stone_bricks")
                elif r < 0.20:
                    self.set_block(x, y, z, "minecraft:cracked_stone_bricks")
            elif name == "minecraft:cobblestone":
                if y <= 1 and r < 0.12:
                    self.set_block(x, y, z, "minecraft:mossy_cobblestone")
            elif name == "minecraft:deepslate_tiles":
                if r < 0.15:
                    self.set_block(x, y, z, "minecraft:cracked_deepslate_tiles")

    def export_nbt(self, file_path: str):
        os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
        blocks = []
        for (x, y, z), state_idx in self.grid.items():
            blocks.append({"pos": (x, y, z), "state": state_idx})
        write_structure_template(file_path, (self.size_x, self.size_y, self.size_z), self.palette, blocks)
        print(f"Exported NBT: {file_path} ({self.size_x}x{self.size_y}x{self.size_z}, {len(blocks)} blocks, {len(self.palette)} palette entries)")


def build_hq_shell() -> StructureBuilder:
    """
    Constructs the canonical 50x18x90 Pillager Headquarters Outer Shell (Main Gate facing SOUTH at Z=89).
    Features:
    - Battered stone plinths, recessed wall bays, dark oak pilasters, and inverted stair machicolations.
    - 4 multi-story 8x8 corner bastions with pitched timber pyramid roofs and iron finials.
    - Grand South Barbican Gatehouse with double portcullis, murder holes, and winch loft.
    - North Sally Port postern gate.
    - Paved courtyard road network, drainage curbs, lamp posts, and wide rampart stairs.
    """
    sx, sy, sz = 50, 18, 90
    b = StructureBuilder(sx, sy, sz)

    # 1. Courtyard Baseline & Textured Paving (Y=0)
    for x in range(sx):
        for z in range(sz):
            b.set_block(x, 0, z, "minecraft:cobblestone")

    # Interior Courtyard Framing & Drainage Curbs
    for x in range(5, 45):
        for z in range(5, 85):
            if x in (5, 44) or z in (5, 84) or z in (44, 45):
                b.set_block(x, 0, z, "minecraft:stone_bricks")

    # Main North-South Thoroughfare (X: 21..28) & Cross Plaza (Z: 41..48)
    for z in range(0, sz):
        for x in range(21, 29):
            if x in (21, 28):
                b.set_block(x, 0, z, "minecraft:polished_deepslate")
            else:
                mat = "minecraft:gravel" if (x * 3 + z) % 4 == 0 else ("minecraft:stone_bricks" if (x + z) % 2 == 0 else "minecraft:cobblestone")
                b.set_block(x, 0, z, mat)

    for x in range(5, 45):
        for z in range(42, 48):
            if z in (42, 47):
                b.set_block(x, 0, z, "minecraft:polished_deepslate")
            else:
                b.set_block(x, 0, z, "minecraft:stone_bricks" if (x + z) % 2 == 0 else "minecraft:gravel")

    # 2. Curtain Walls (5 blocks thick: West X=0..4, East X=45..49, North Z=0..4, South Z=85..89)
    for x in range(sx):
        is_west = (x <= 4)
        is_east = (x >= sx - 5)
        is_outer_x = is_west or is_east
        is_edge_x = (x == 0 or x == sx - 1)
        is_inner_x = (x == 4 or x == sx - 5)

        for z in range(sz):
            is_north = (z <= 4)
            is_south = (z >= sz - 5)
            is_outer_z = is_north or is_south
            is_edge_z = (z == 0 or z == sz - 1)
            is_inner_z = (z == 4 or z == sz - 5)

            if not (is_outer_x or is_outer_z):
                continue

            # Skip corner bastion zones (handled separately in step 3)
            if (x <= 8 or x >= sx - 9) and (z <= 8 or z >= sz - 9):
                continue

            is_outer_face = is_edge_x or is_edge_z
            is_inner_face = (is_inner_x and not is_outer_z) or (is_inner_z and not is_outer_x)

            # Pilaster spacing every 5 blocks
            is_pilaster = (z % 5 == 0) if is_outer_x else (x % 5 == 0)

            # Wall Core & Faces (Y=1..5)
            for y in range(1, 6):
                if is_outer_face:
                    if is_pilaster:
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    elif y == 1:
                        b.set_block(x, y, z, "minecraft:stone_bricks")
                    elif y == 5:
                        # Horizontal timber tie-beam at top of curtain wall
                        axis = "z" if is_outer_x else "x"
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": axis})
                    elif y == 3 and ((x + z) % 5 == 2):
                        b.set_block(x, y, z, "minecraft:iron_bars")
                    else:
                        b.set_block(x, y, z, "minecraft:deepslate_bricks")
                elif is_inner_face:
                    if is_pilaster:
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    elif y == 1:
                        b.set_block(x, y, z, "minecraft:stone_bricks")
                    else:
                        b.set_block(x, y, z, "minecraft:deepslate_bricks")
                else:
                    # Solid masonry fill inside the 5-block wall
                    b.set_block(x, y, z, "minecraft:cobblestone" if y < 5 else "minecraft:dark_oak_planks")

            # Rampart Walkway (Y=6) & Crenellated Parapets (Y=7..8)
            if is_outer_face:
                b.set_block(x, 6, z, "minecraft:polished_deepslate")
                # Merlons & Embrasures
                cycle = (z % 4) if is_outer_x else (x % 4)
                if cycle in (0, 1):
                    b.set_block(x, 7, z, "minecraft:deepslate_bricks")
                    b.set_block(x, 8, z, "minecraft:deepslate_brick_slab", {"type": "bottom"})
                else:
                    b.set_block(x, 7, z, "minecraft:deepslate_brick_wall")
            elif is_inner_face:
                b.set_block(x, 6, z, "minecraft:stone_bricks")
                if is_pilaster:
                    b.set_block(x, 7, z, "minecraft:cobblestone_wall")
                    if ((x + z) % 10 == 0):
                        b.set_block(x, 8, z, "minecraft:lantern")
                else:
                    b.set_block(x, 7, z, "minecraft:dark_oak_fence")
            else:
                b.set_block(x, 6, z, "minecraft:dark_oak_planks")

    # 3. Four 9x9 Corner Bastions (NW, NE, SW, SE)
    bastion_coords = [
        (0, 0),            # NW
        (sx - 9, 0),       # NE
        (0, sz - 9),       # SW
        (sx - 9, sz - 9),  # SE
    ]
    for bx0, bz0 in bastion_coords:
        bx1, bz1 = bx0 + 8, bz0 + 8
        for x in range(bx0, bx1 + 1):
            for z in range(bz0, bz1 + 1):
                is_edge = (x == bx0 or x == bx1 or z == bz0 or z == bz1)
                is_corner = (x in (bx0, bx1)) and (z in (bz0, bz1))
                is_mid = (x == bx0 + 4 or z == bz0 + 4)

                # Tower Body (Y=1..11)
                for y in range(1, 12):
                    if is_corner:
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    elif is_edge:
                        if y in (1, 2):
                            b.set_block(x, y, z, "minecraft:stone_bricks")
                        elif y in (6, 11):
                            b.set_block(x, y, z, "minecraft:polished_deepslate")
                        elif y in (4, 8, 9) and is_mid:
                            b.set_block(x, y, z, "minecraft:iron_bars")
                        else:
                            b.set_block(x, y, z, "minecraft:deepslate_bricks")
                    else:
                        if y in (6, 11):
                            b.set_block(x, y, z, "minecraft:dark_oak_planks")
                        else:
                            b.set_block(x, y, z, "minecraft:air")

                # Connect bastion at Y=6 to rampart walkways (openings on inner faces)
                if not (x == 0 or x == sx - 1 or z == 0 or z == sz - 1):
                    if (bx0 + 1 <= x <= bx1 - 1) and (bz0 + 1 <= z <= bz1 - 1):
                        pass

                # Upper Fighting Deck & Crenellations (Y=12..13)
                if is_edge:
                    b.set_block(x, 12, z, "minecraft:deepslate_bricks")
                    if (x + z) % 2 == 0:
                        b.set_block(x, 13, z, "minecraft:deepslate_brick_wall")
                    else:
                        b.set_block(x, 13, z, "minecraft:deepslate_brick_slab", {"type": "bottom"})

                # Timber Roof Pillars (Y=12..14 at inset corners)
                if (x in (bx0 + 1, bx1 - 1)) and (z in (bz0 + 1, bz1 - 1)):
                    for y in range(12, 15):
                        b.set_block(x, y, z, "minecraft:dark_oak_fence")

                # Pitched Pyramid Roof (Y=14..17)
                dist = min(x - bx0, bx1 - x, z - bz0, bz1 - z)
                if dist >= 1:
                    b.set_block(x, 13 + dist, z, "minecraft:dark_oak_planks")
                    if dist == 1:
                        b.set_block(x, 14, z, "minecraft:dark_oak_slab", {"type": "bottom"})

        # Bastion Spire Finial & Internal Stairs
        cx, cz = bx0 + 4, bz0 + 4
        b.set_block(cx, 17, cz, "minecraft:deepslate_brick_wall")
        b.set_block(cx, 8, cz, "minecraft:soul_lantern", {"hanging": "true"})

        # Carve doorways connecting bastion Y=6 room to adjacent ramparts
        for y in range(7, 10):
            if bx0 == 0:
                b.set_block(2, y, bz1 if bz0 == 0 else bz0, "minecraft:air")
                b.set_block(3, y, bz1 if bz0 == 0 else bz0, "minecraft:air")
            else:
                b.set_block(sx - 3, y, bz1 if bz0 == 0 else bz0, "minecraft:air")
                b.set_block(sx - 4, y, bz1 if bz0 == 0 else bz0, "minecraft:air")
            if bz0 == 0:
                b.set_block(bx1 if bx0 == 0 else bx0, y, 2, "minecraft:air")
                b.set_block(bx1 if bx0 == 0 else bx0, y, 3, "minecraft:air")
            else:
                b.set_block(bx1 if bx0 == 0 else bx0, y, sz - 3, "minecraft:air")
                b.set_block(bx1 if bx0 == 0 else bx0, y, sz - 4, "minecraft:air")

        # Internal steps inside bastion from Y=6 to Y=11
        for step in range(5):
            b.set_block(bx0 + 2 + step, 6 + step, bz0 + 2, "minecraft:dark_oak_stairs", {"facing": "east", "half": "bottom"})
            b.set_block(bx0 + 2 + step, 11, bz0 + 2, "minecraft:air")

    # 4. Grand South Barbican Gatehouse (X: 19..30, Z: 84..89)
    # Gate Towers flanking the 6-block wide passage (X: 22..27)
    for x in range(19, 31):
        for z in range(84, 90):
            is_passage = (22 <= x <= 27)
            if not is_passage:
                # Flanking Gatehouse Towers (X: 19..21 and X: 28..30)
                for y in range(1, 12):
                    if (x in (19, 21, 28, 30)) and (z in (84, 89)):
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    else:
                        b.set_block(x, y, z, "minecraft:deepslate_bricks" if y > 2 else "minecraft:stone_bricks")
                b.set_block(x, 12, z, "minecraft:polished_deepslate")
                if z in (84, 89) or x in (19, 30):
                    b.set_block(x, 13, z, "minecraft:deepslate_brick_wall")
            else:
                # Clear Gate Passage (Y=1..5)
                for y in range(1, 6):
                    b.set_block(x, y, z, "minecraft:air")
                # Portcullis Fangs at Y=5 (Z=85 and Z=88)
                if z in (85, 88):
                    b.set_block(x, 5, z, "minecraft:iron_bars")
                    if x in (22, 27):
                        b.set_block(x, 4, z, "minecraft:iron_bars")
                # Gatehouse Ceiling & Murder Holes (Y=6)
                if z == 86 and x in (23, 26):
                    b.set_block(x, 6, z, "minecraft:iron_bars")
                else:
                    b.set_block(x, 6, z, "minecraft:polished_deepslate")
                # Upper Gatehouse Winch Room (Y=7..10)
                for y in range(7, 11):
                    if z in (84, 89):
                        if y == 8 and x in (23, 26):
                            b.set_block(x, y, z, "minecraft:iron_bars")
                        else:
                            b.set_block(x, y, z, "minecraft:deepslate_bricks")
                    else:
                        b.set_block(x, y, z, "minecraft:air")
                b.set_block(x, 11, z, "minecraft:dark_oak_planks")
                if z in (84, 89):
                    b.set_block(x, 12, z, "minecraft:deepslate_bricks")
                    if x % 2 == 0:
                        b.set_block(x, 13, z, "minecraft:deepslate_brick_wall")

    # Gatehouse Banners & Lanterns
    b.set_block(21, 5, 89, "minecraft:soul_lantern")
    b.set_block(28, 5, 89, "minecraft:soul_lantern")
    b.set_block(21, 8, 89, "minecraft:black_wall_banner", {"facing": "south"})
    b.set_block(28, 8, 89, "minecraft:black_wall_banner", {"facing": "south"})

    # 5. North Sally Port Gate (X: 22..27, Z: 0..4)
    for x in range(23, 27):
        for z in range(0, 5):
            for y in range(1, 5):
                b.set_block(x, y, z, "minecraft:air")
            if z in (1, 3):
                b.set_block(x, 4, z, "minecraft:iron_bars")
    for y in range(1, 8):
        for gx in (22, 27):
            for gz in (0, 4):
                b.set_block(gx, y, gz, "minecraft:dark_oak_log", {"axis": "y"})

    # 6. Grand Courtyard Rampart Staircases (West & East Mid-Courtyard)
    for step in range(6):
        sz_step = 40 + step
        # West Stairs (X: 5..6)
        b.fill_box(5, 1, sz_step, 6, step, sz_step, "minecraft:stone_bricks")
        b.set_block(5, step + 1, sz_step, "minecraft:stone_brick_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(6, step + 1, sz_step, "minecraft:stone_brick_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(7, step + 1, sz_step, "minecraft:cobblestone_wall")
        # Clear fence at top landing (Z=45)
        b.set_block(4, 7, 45, "minecraft:air")
        b.set_block(4, 7, 46, "minecraft:air")

        # East Stairs (X: 43..44)
        b.fill_box(43, 1, sz_step, 44, step, sz_step, "minecraft:stone_bricks")
        b.set_block(43, step + 1, sz_step, "minecraft:stone_brick_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(44, step + 1, sz_step, "minecraft:stone_brick_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(42, step + 1, sz_step, "minecraft:cobblestone_wall")
        b.set_block(45, 7, 45, "minecraft:air")
        b.set_block(45, 7, 46, "minecraft:air")

    # 7. Courtyard Lamp Posts along Central Plaza
    for lx in (19, 30):
        for lz in (20, 43, 46, 70):
            b.set_block(lx, 1, lz, "minecraft:polished_deepslate_wall")
            b.set_block(lx, 2, lz, "minecraft:dark_oak_fence")
            b.set_block(lx, 3, lz, "minecraft:dark_oak_fence")
            b.set_block(lx, 4, lz, "minecraft:lantern")

    b.apply_weathering(seed=101)
    return b


def build_commander_keep() -> StructureBuilder:
    """
    Constructs the 40x22x40 Garrison Commander Keep (Entrance facing SOUTH at Z=36).
    Features:
    - Gothic Pillager Citadel architecture with stone buttresses, recessed tall windows, and 4 corner watchtowers.
    - Steep pitched dark oak timber roof over the central nave with vaulted interior trusses.
    - Grand South Entrance Portico with Soul Fire braziers.
    - Great Hall with patterned deepslate floor, Crimson & Gold carpet runner, colonnade pillars, and chandeliers.
    - Elevated North Command Dais with the Iron Throne, banners, and war map table.
    - Second-floor Mezzanine Gallery overlooking the throne room, plus rooftop sniper battlements.
    """
    sx, sy, sz = 40, 22, 40
    b = StructureBuilder(sx, sy, sz)

    # 1. Courtyard Perimeter Paving (Y=0)
    for x in range(sx):
        for z in range(sz):
            b.set_block(x, 0, z, "minecraft:cobblestone")
            if 17 <= x <= 22 and z >= 34:
                b.set_block(x, 0, z, "minecraft:stone_bricks" if (x + z) % 2 == 0 else "minecraft:polished_deepslate")

    kx1, kx2 = 5, 34
    kz1, kz2 = 4, 34

    # 2. Great Hall Floor Parquet (Y=0) & Royal Carpet Runner (Y=1)
    for x in range(kx1, kx2 + 1):
        for z in range(kz1, kz2 + 1):
            if (x + z) % 2 == 0:
                b.set_block(x, 0, z, "minecraft:polished_deepslate")
            else:
                b.set_block(x, 0, z, "minecraft:deepslate_tiles")

    for z in range(11, kz2 + 1):
        for x in range(17, 23):
            if x in (17, 22):
                b.set_block(x, 1, z, "minecraft:yellow_carpet")
            else:
                b.set_block(x, 1, z, "minecraft:red_carpet")

    # 3. Main Keep Exterior Walls (Y=1..12) with Buttresses & Arched Windows
    for y in range(1, 13):
        for x in range(kx1, kx2 + 1):
            for z in range(kz1, kz2 + 1):
                is_wx = (x == kx1 or x == kx2)
                is_wz = (z == kz1 or z == kz2)
                if not (is_wx or is_wz):
                    continue

                # Grand South Entrance Archway (Z=kz2, X: 18..21, Y: 1..4)
                if z == kz2 and 18 <= x <= 21 and y <= 4:
                    b.set_block(x, y, z, "minecraft:air")
                    continue

                is_corner = is_wx and is_wz
                is_pilaster = ((x - kx1) % 5 == 0) or ((z - kz1) % 5 == 0)

                if is_corner or is_pilaster:
                    b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    # Exterior stone buttress base (Y=1..3)
                    if y <= 3:
                        if x == kx1: b.set_block(x - 1, y, z, "minecraft:stone_bricks" if y < 3 else "minecraft:stone_brick_stairs", {"facing": "east", "half": "bottom"} if y == 3 else None)
                        if x == kx2: b.set_block(x + 1, y, z, "minecraft:stone_bricks" if y < 3 else "minecraft:stone_brick_stairs", {"facing": "west", "half": "bottom"} if y == 3 else None)
                elif y in (1, 6, 12):
                    b.set_block(x, y, z, "minecraft:polished_deepslate")
                elif y in (3, 4, 8, 9, 10) and ((x + z) % 5 in (2, 3)) and not (z == kz2 and 15 <= x <= 24):
                    b.set_block(x, y, z, "minecraft:iron_bars")
                else:
                    b.set_block(x, y, z, "minecraft:deepslate_bricks" if y < 7 else "minecraft:stone_bricks")

    # 4. Four Corner Watchtowers (6x6 footprint at corners, rising to Y=19)
    towers = [
        (kx1 - 1, kz1 - 1),
        (kx2 - 4, kz1 - 1),
        (kx1 - 1, kz2 - 4),
        (kx2 - 4, kz2 - 4),
    ]
    for tx0, tz0 in towers:
        tx1, tz1 = tx0 + 5, tz0 + 5
        for x in range(tx0, tx1 + 1):
            for z in range(tz0, tz1 + 1):
                is_edge = (x == tx0 or x == tx1 or z == tz0 or z == tz1)
                is_corner = (x in (tx0, tx1)) and (z in (tz0, tz1))
                for y in range(1, 16):
                    if is_corner:
                        b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                    elif is_edge:
                        if y in (1, 6, 12, 15):
                            b.set_block(x, y, z, "minecraft:polished_deepslate")
                        elif y in (4, 9, 14) and ((x == tx0 + 2 or x == tx0 + 3) or (z == tz0 + 2 or z == tz0 + 3)):
                            b.set_block(x, y, z, "minecraft:iron_bars")
                        else:
                            b.set_block(x, y, z, "minecraft:deepslate_bricks")
                    else:
                        if y in (6, 12, 15):
                            b.set_block(x, y, z, "minecraft:dark_oak_planks")
                        else:
                            b.set_block(x, y, z, "minecraft:air")

                # Tower Crenellations & Spire Roof (Y=16..19)
                if is_edge:
                    b.set_block(x, 16, z, "minecraft:deepslate_bricks")
                    if (x + z) % 2 == 0:
                        b.set_block(x, 17, z, "minecraft:deepslate_brick_wall")
                dist = min(x - tx0, tx1 - x, z - tz0, tz1 - z)
                if dist >= 1:
                    b.set_block(x, 16 + dist, z, "minecraft:dark_oak_planks")
        # Open doorway from roof deck (Y=13) into corner towers
        b.set_block((tx0 + tx1) // 2, 13, tz1 if tz0 < 10 else tz0, "minecraft:air")
        b.set_block((tx0 + tx1) // 2, 14, tz1 if tz0 < 10 else tz0, "minecraft:air")

    # 5. Interior Colonnade Pillars & Mezzanine Gallery (Y=6)
    for pz in range(10, 31, 5):
        for px in (11, 28):
            for y in range(1, 12):
                b.set_block(px, y, pz, "minecraft:dark_oak_log", {"axis": "y"})

    # Mezzanine Floor (Y=6) & Railing (Y=7) along West, East, and North
    for x in range(kx1 + 1, kx2):
        for z in range(kz1 + 1, kz2):
            is_gallery = (x <= 11 or x >= 28 or z <= 9)
            if is_gallery:
                b.set_block(x, 6, z, "minecraft:dark_oak_planks")
                if (x == 11 and z > 9) or (x == 28 and z > 9) or (z == 9 and 11 <= x <= 28):
                    if (x + z) % 3 == 0:
                        b.set_block(x, 7, z, "minecraft:cobblestone_wall")
                        b.set_block(x, 8, z, "minecraft:lantern")
                    else:
                        b.set_block(x, 7, z, "minecraft:dark_oak_fence")

    # Twin Grand Staircases to Mezzanine (West X: 7..8 and East X: 31..32, Z: 22..27)
    for step in range(6):
        sz_step = 27 - step
        for sx_col in (7, 8, 31, 32):
            b.fill_box(sx_col, 1, sz_step, sx_col, step, sz_step, "minecraft:stone_bricks")
            b.set_block(sx_col, step + 1, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
            b.set_block(sx_col, 6, sz_step, "minecraft:air")
            b.set_block(sx_col, 7, sz_step, "minecraft:air")

    # Stairs from Mezzanine (Y=6) to Roof Deck (Y=12) on North-West & North-East
    for step in range(6):
        sz_step = 13 + step
        b.set_block(7, 7 + step, sz_step, "minecraft:dark_oak_stairs", {"facing": "south", "half": "bottom"})
        b.set_block(7, 12, sz_step, "minecraft:air")
        b.set_block(32, 7 + step, sz_step, "minecraft:dark_oak_stairs", {"facing": "south", "half": "bottom"})
        b.set_block(32, 12, sz_step, "minecraft:air")

    # 6. Elevated North Command Dais & Iron Throne (X: 14..25, Z: 6..11)
    for x in range(14, 26):
        for z in range(6, 12):
            b.set_block(x, 1, z, "minecraft:polished_deepslate")
            if 15 <= x <= 24 and 6 <= z <= 10:
                b.set_block(x, 2, z, "minecraft:deepslate_tiles")

    for x in range(16, 24):
        b.set_block(x, 1, 12, "minecraft:polished_deepslate_stairs", {"facing": "north", "half": "bottom"})
        if 17 <= x <= 22:
            b.set_block(x, 2, 11, "minecraft:polished_deepslate_stairs", {"facing": "north", "half": "bottom"})

    # The Commander's Iron Throne (X: 19..20, Z: 7)
    b.set_block(19, 3, 7, "minecraft:iron_block")
    b.set_block(20, 3, 7, "minecraft:iron_block")
    b.set_block(19, 4, 6, "minecraft:chiseled_deepslate")
    b.set_block(20, 4, 6, "minecraft:chiseled_deepslate")
    b.set_block(19, 5, 6, "minecraft:wither_skeleton_skull")
    b.set_block(20, 5, 6, "minecraft:wither_skeleton_skull")
    b.set_block(18, 3, 7, "minecraft:dark_oak_stairs", {"facing": "east", "half": "bottom"})
    b.set_block(21, 3, 7, "minecraft:dark_oak_stairs", {"facing": "west", "half": "bottom"})

    # Dais Banners & Soul Fire Braziers
    b.set_block(15, 3, 7, "minecraft:polished_deepslate_wall")
    b.set_block(15, 4, 7, "minecraft:soul_campfire", {"lit": "true"})
    b.set_block(24, 3, 7, "minecraft:polished_deepslate_wall")
    b.set_block(24, 4, 7, "minecraft:soul_campfire", {"lit": "true"})
    b.set_block(17, 4, 5, "minecraft:black_wall_banner", {"facing": "south"})
    b.set_block(22, 4, 5, "minecraft:black_wall_banner", {"facing": "south"})

    # Tactical War Map Table in Great Hall (X: 18..21, Z: 15..17)
    for x in range(18, 22):
        for z in range(15, 18):
            b.set_block(x, 1, z, "minecraft:dark_oak_planks")
            if x == 18 and z == 16:
                b.set_block(x, 2, z, "minecraft:cartography_table")
            elif x == 21 and z == 16:
                b.set_block(x, 2, z, "minecraft:fletching_table")
            else:
                b.set_block(x, 2, z, "minecraft:green_wool" if (x + z) % 2 == 0 else "minecraft:brown_wool")
    b.set_block(19, 3, 16, "minecraft:redstone_torch")

    # Great Hall Chandeliers (Hanging from Y=12)
    for cz in (16, 24):
        for y in range(9, 12):
            b.set_block(19, y, cz, "minecraft:chain")
            b.set_block(20, y, cz, "minecraft:chain")
        b.set_block(19, 8, cz, "minecraft:dark_oak_fence")
        b.set_block(20, 8, cz, "minecraft:dark_oak_fence")
        b.set_block(18, 8, cz, "minecraft:soul_lantern")
        b.set_block(21, 8, cz, "minecraft:soul_lantern")

    # Mezzanine Furnishings (Bookshelves, Chests, Armor/Weapon Stations)
    for z in range(12, 20, 2):
        b.set_block(kx1 + 1, 7, z, "minecraft:bookshelf")
        b.set_block(kx1 + 1, 8, z, "minecraft:chiseled_bookshelf")
        b.set_block(kx2 - 1, 7, z, "minecraft:bookshelf")
        b.set_block(kx2 - 1, 8, z, "minecraft:chiseled_bookshelf")
    b.set_block(kx1 + 1, 7, 11, "minecraft:chest", {"facing": "east"})
    b.set_block(kx2 - 1, 7, 11, "minecraft:chest", {"facing": "west"})

    # 7. Rooftop Battlements (Y=12..14) & Central Pitched Gable Roof (X: 12..27, Z: 8..31)
    for x in range(kx1, kx2 + 1):
        for z in range(kz1, kz2 + 1):
            if b.get_block_name(x, 12, z) != "minecraft:air":
                pass
            else:
                b.set_block(x, 12, z, "minecraft:dark_oak_planks")
            if x in (kx1, kx2) or z in (kz1, kz2):
                b.set_block(x, 13, z, "minecraft:deepslate_bricks")
                if (x + z) % 2 == 0:
                    b.set_block(x, 14, z, "minecraft:deepslate_brick_wall")

    # Pitched Roof over Central Nave (X: 13..26, Z: 10..30, Y: 13..19)
    for z in range(10, 31):
        for step in range(7):
            lx = 13 + step
            rx = 26 - step
            ry = 13 + step
            mat = "minecraft:stone_brick_stairs" if z in (10, 30) else "minecraft:dark_oak_stairs"
            b.set_block(lx, ry, z, mat, {"facing": "east", "half": "bottom"})
            b.set_block(rx, ry, z, mat, {"facing": "west", "half": "bottom"})
            if z in (10, 30) and lx + 1 <= rx - 1:
                b.fill_box(lx + 1, 13, z, rx - 1, ry, z, "minecraft:deepslate_bricks")
        # Roof ridge cap
        b.set_block(19, 20, z, "minecraft:stone_brick_slab", {"type": "bottom"})
        b.set_block(20, 20, z, "minecraft:stone_brick_slab", {"type": "bottom"})

    # 8. Grand South Entrance Portico (X: 15..24, Z: 35..38)
    for x in range(15, 25):
        for z in range(35, 39):
            b.set_block(x, 0, z, "minecraft:polished_deepslate")
            b.set_block(x, 5, z, "minecraft:dark_oak_planks")
            if x in (15, 24) or z == 38:
                b.set_block(x, 6, z, "minecraft:deepslate_brick_wall")
    for y in range(1, 5):
        for px in (15, 17, 22, 24):
            b.set_block(px, y, 38, "minecraft:dark_oak_log", {"axis": "y"})
    b.set_block(16, 4, 37, "minecraft:soul_lantern", {"hanging": "true"})
    b.set_block(23, 4, 37, "minecraft:soul_lantern", {"hanging": "true"})

    b.apply_weathering(seed=202)
    return b


def build_intel_bunker() -> StructureBuilder:
    """
    Constructs the 40x24x40 Command Center & Intel Bunker (Entrance facing SOUTH at Z=35).
    Features:
    - Heavily armored deepslate bunker with sloped blast walls and reinforced pilasters.
    - Sunken Situation Map Pit with 3D terrain table and frontline markers.
    - Cryptography & Radio Dispatch bays with chiseled bookshelves and lecterns.
    - High-Security North Cipher Vault with double iron-bar airlock, prize pedestal, and archive chests.
    - Rooftop Signal Deck with a 23-block tall guy-wired communications mast and radar dish cupola.
    """
    sx, sy, sz = 40, 24, 40
    b = StructureBuilder(sx, sy, sz)

    for x in range(sx):
        for z in range(sz):
            b.set_block(x, 0, z, "minecraft:cobblestone")
            if 17 <= x <= 22 and z >= 34:
                b.set_block(x, 0, z, "minecraft:polished_deepslate")

    bx1, bx2 = 6, 33
    bz1, bz2 = 5, 35

    # 1. Bunker Floor (Y=0)
    for x in range(bx1, bx2 + 1):
        for z in range(bz1, bz2 + 1):
            b.set_block(x, 0, z, "minecraft:polished_deepslate" if (x + z) % 2 == 0 else "minecraft:stone_bricks")

    # 2. Sloped Armored Exterior Blast Skirt (Y=1..2 around perimeter)
    for x in range(bx1 - 1, bx2 + 2):
        for z in range(bz1 - 1, bz2 + 2):
            if 17 <= x <= 22 and z >= bz2:
                continue
            if x == bx1 - 1 and bz1 <= z <= bz2:
                b.set_block(x, 1, z, "minecraft:deepslate_brick_stairs", {"facing": "east", "half": "bottom"})
            elif x == bx2 + 1 and bz1 <= z <= bz2:
                b.set_block(x, 1, z, "minecraft:deepslate_brick_stairs", {"facing": "west", "half": "bottom"})
            elif z == bz1 - 1 and bx1 <= x <= bx2:
                b.set_block(x, 1, z, "minecraft:deepslate_brick_stairs", {"facing": "south", "half": "bottom"})
            elif z == bz2 + 1 and bx1 <= x <= bx2:
                b.set_block(x, 1, z, "minecraft:deepslate_brick_stairs", {"facing": "north", "half": "bottom"})

    # 3. Bunker Exterior Walls (Y=1..11)
    for y in range(1, 12):
        for x in range(bx1, bx2 + 1):
            for z in range(bz1, bz2 + 1):
                is_wx = (x == bx1 or x == bx2)
                is_wz = (z == bz1 or z == bz2)
                if not (is_wx or is_wz):
                    continue

                # South Security Entrance (Z=bz2, X: 18..21, Y: 1..3)
                if z == bz2 and 18 <= x <= 21 and y <= 3:
                    b.set_block(x, y, z, "minecraft:air")
                    continue

                is_corner = is_wx and is_wz
                is_col = ((x - bx1) % 5 == 0) or ((z - bz1) % 5 == 0)
                if is_corner or is_col:
                    b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                elif y in (1, 6, 11):
                    b.set_block(x, y, z, "minecraft:polished_deepslate")
                elif y in (3, 9) and ((x + z) % 3 == 0) and not (z == bz2 and 16 <= x <= 23):
                    b.set_block(x, y, z, "minecraft:iron_bars")
                else:
                    b.set_block(x, y, z, "minecraft:deepslate_bricks")

    # 4. Central Strategic Situation Map Table & Command Pit (X: 16..23, Z: 19..25)
    for x in range(17, 23):
        for z in range(20, 25):
            b.set_block(x, 1, z, "minecraft:dark_oak_planks")
            if (x, z) in [(17, 20), (22, 20), (17, 24), (22, 24)]:
                b.set_block(x, 2, z, "minecraft:cartography_table")
            else:
                mat = "minecraft:green_wool" if (x + z) % 3 != 0 else ("minecraft:blue_wool" if x == 19 else "minecraft:brown_wool")
                b.set_block(x, 2, z, mat)
    b.set_block(19, 3, 22, "minecraft:redstone_torch")
    b.set_block(20, 3, 21, "minecraft:soul_lantern")

    # Cryptography & Radio Consoles along West & East Ground Wings
    for z in range(17, 31, 2):
        b.set_block(bx1 + 1, 1, z, "minecraft:chiseled_bookshelf")
        b.set_block(bx1 + 1, 2, z, "minecraft:bookshelf")
        b.set_block(bx1 + 2, 1, z, "minecraft:lectern", {"facing": "east"})

        b.set_block(bx2 - 1, 1, z, "minecraft:chiseled_bookshelf")
        b.set_block(bx2 - 1, 2, z, "minecraft:bookshelf")
        b.set_block(bx2 - 2, 1, z, "minecraft:loom", {"facing": "west"})

    # 5. Fortified North Cipher & Intel Vault (X: 12..27, Z: 7..15)
    for y in range(1, 6):
        for x in range(12, 28):
            for z in range(7, 16):
                is_vx = (x == 12 or x == 27)
                is_vz = (z == 7 or z == 15)
                if is_vx or is_vz:
                    if z == 15 and 19 <= x <= 20 and y <= 3:
                        b.set_block(x, y, z, "minecraft:air")
                    elif y in (2, 3, 4) and ((is_vx and z % 2 == 0) or (z == 15 and 16 <= x <= 23)):
                        b.set_block(x, y, z, "minecraft:iron_bars")
                    else:
                        b.set_block(x, y, z, "minecraft:deepslate_tiles")

    # Vault Ceiling & Floor
    for x in range(13, 27):
        for z in range(8, 15):
            b.set_block(x, 0, z, "minecraft:deepslate_tiles")
            b.set_block(x, 6, z, "minecraft:polished_deepslate")

    # Inside Vault: Intel Objective Pedestal & Archive Chests
    b.fill_box(18, 1, 9, 21, 1, 11, "minecraft:polished_deepslate")
    b.set_block(19, 2, 10, "minecraft:lodestone")
    b.set_block(20, 2, 10, "minecraft:target")
    b.set_block(19, 3, 10, "minecraft:soul_lantern")
    b.set_block(18, 2, 10, "minecraft:lectern", {"facing": "south"})
    b.set_block(21, 2, 10, "minecraft:cartography_table")

    for vz in (9, 11, 13):
        b.set_block(14, 1, vz, "minecraft:chest", {"facing": "east"})
        b.set_block(25, 1, vz, "minecraft:chest", {"facing": "west"})
    b.set_block(14, 1, 8, "minecraft:anvil", {"facing": "east"})
    b.set_block(25, 1, 8, "minecraft:smithing_table")

    # 6. Second Floor — Officer Quarters & Signal Loft (Y=6)
    for x in range(bx1 + 1, bx2):
        for z in range(bz1 + 1, bz2):
            b.set_block(x, 6, z, "minecraft:dark_oak_planks")

    # Internal Staircase from Ground to Floor 2 (West side: X: 9..10, Z: 26..30)
    for step in range(5):
        sz_step = 30 - step
        for sx_col in (9, 10):
            b.fill_box(sx_col, 1, sz_step, sx_col, step, sz_step, "minecraft:cobblestone")
            b.set_block(sx_col, step + 1, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
            b.set_block(sx_col, 6, sz_step, "minecraft:air")

    # Stairs from Floor 2 (Y=6) to Roof Deck (Y=12) (East side: X: 29..30, Z: 25..30)
    for step in range(6):
        sz_step = 30 - step
        b.fill_box(29, 7, sz_step, 30, 6 + step, sz_step, "minecraft:cobblestone")
        b.set_block(29, 7 + step, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(30, 7 + step, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(29, 12, sz_step, "minecraft:air")
        b.set_block(30, 12, sz_step, "minecraft:air")

    # Officer Bunks & Planning Tables on Floor 2
    for bz_bed in (10, 14, 18):
        b.set_block(8, 7, bz_bed, "minecraft:red_wool")
        b.set_block(9, 7, bz_bed, "minecraft:red_wool")
        b.set_block(10, 7, bz_bed, "minecraft:chest", {"facing": "south"})

    # 7. Rooftop Deck & Parapets (Y=12..14)
    for x in range(bx1, bx2 + 1):
        for z in range(bz1, bz2 + 1):
            if b.get_block_name(x, 12, z) != "minecraft:air":
                b.set_block(x, 12, z, "minecraft:polished_deepslate")
            if x in (bx1, bx2) or z in (bz1, bz2):
                b.set_block(x, 13, z, "minecraft:deepslate_bricks")
                if (x + z) % 2 == 0:
                    b.set_block(x, 14, z, "minecraft:deepslate_brick_wall")

    # 8. Towering Lattice Communications Mast (X: 18..21, Z: 18..21, Y=13..23)
    b.fill_box(17, 13, 17, 22, 14, 22, "minecraft:deepslate_bricks")
    b.fill_box(18, 15, 18, 21, 15, 21, "minecraft:polished_deepslate")

    for y in range(16, 22):
        for mx in (19, 20):
            for mz in (19, 20):
                b.set_block(mx, y, mz, "minecraft:iron_bars" if (mx + mz + y) % 2 == 0 else "minecraft:dark_oak_fence")

    # Cross-Arm Signal Yardarms at Y=19 and Y=21
    for dx in range(-3, 5):
        b.set_block(19 + dx, 19, 19, "minecraft:iron_bars")
        b.set_block(19, 21, 19 + dx, "minecraft:iron_bars")
    b.set_block(16, 18, 19, "minecraft:soul_lantern", {"hanging": "true"})
    b.set_block(23, 18, 19, "minecraft:soul_lantern", {"hanging": "true"})

    # Lightning Rod Spire Cap (Y=22..23)
    b.set_block(19, 22, 19, "minecraft:iron_block")
    b.set_block(20, 22, 20, "minecraft:iron_block")
    b.set_block(19, 23, 19, "minecraft:lightning_rod")
    b.set_block(20, 23, 20, "minecraft:lightning_rod")

    # Diagonal Guy-Wires Anchored to Roof Corners
    for i in range(1, 7):
        b.set_block(18 - i, 14 + i, 18 - i, "minecraft:chain")
        b.set_block(21 + i, 14 + i, 18 - i, "minecraft:chain")
        b.set_block(18 - i, 14 + i, 21 + i, "minecraft:chain")
        b.set_block(21 + i, 14 + i, 21 + i, "minecraft:chain")

    b.apply_weathering(seed=303)
    return b


def build_supply_depot() -> StructureBuilder:
    """
    Constructs the 40x18x40 Reinforced Supply Depot & Armory (Entrance facing SOUTH at Z=35).
    Features:
    - Half-timbered military storehouse with stone buttresses and wide South cargo portcullis.
    - Cantilevered timber A-Frame Hoist Crane with hanging chains and suspended cargo pallet.
    - Armored NW Munitions & Powder Magazine (iron bar cage, TNT, barrels, supply target core).
    - NE Siege Foundry & Armory with blast furnaces, lava cauldron, anvils, and weapon racks.
    - Central multi-tier storage racking and upper crane winch catwalks.
    """
    sx, sy, sz = 40, 18, 40
    b = StructureBuilder(sx, sy, sz)

    for x in range(sx):
        for z in range(sz):
            b.set_block(x, 0, z, "minecraft:cobblestone")
            if 16 <= x <= 23 and z >= 34:
                b.set_block(x, 0, z, "minecraft:gravel" if (x + z) % 2 == 0 else "minecraft:packed_mud")

    sx1, sx2 = 5, 34
    sz1, sz2 = 5, 34

    # 1. Warehouse Floor (Y=0) with Heavy Haulage Lane
    for x in range(sx1, sx2 + 1):
        for z in range(sz1, sz2 + 1):
            if 17 <= x <= 22:
                b.set_block(x, 0, z, "minecraft:packed_mud" if (x + z) % 2 == 0 else "minecraft:gravel")
            else:
                b.set_block(x, 0, z, "minecraft:stone_bricks" if (x + z) % 2 == 0 else "minecraft:cobblestone")

    # 2. Warehouse Exterior Walls (Y=1..11)
    for y in range(1, 12):
        for x in range(sx1, sx2 + 1):
            for z in range(sz1, sz2 + 1):
                is_wx = (x == sx1 or x == sx2)
                is_wz = (z == sz1 or z == sz2)
                if not (is_wx or is_wz):
                    continue

                # Wide South Cargo Portcullis Opening (Z=sz2, X: 17..22, Y: 1..4)
                if z == sz2 and 17 <= x <= 22 and y <= 4:
                    if y == 4:
                        b.set_block(x, y, z, "minecraft:iron_bars")
                    else:
                        b.set_block(x, y, z, "minecraft:air")
                    continue

                is_corner = is_wx and is_wz
                is_col = ((x - sx1) % 5 == 0) or ((z - sz1) % 5 == 0)
                if is_corner or is_col:
                    b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "y"})
                elif y <= 2:
                    b.set_block(x, y, z, "minecraft:stone_bricks")
                elif y == 6 or y == 11:
                    b.set_block(x, y, z, "minecraft:dark_oak_log", {"axis": "x" if is_wz else "z"})
                elif y in (4, 9) and ((x + z) % 3 == 0) and not (z == sz2 and 15 <= x <= 24):
                    b.set_block(x, y, z, "minecraft:iron_bars")
                else:
                    b.set_block(x, y, z, "minecraft:deepslate_bricks" if y < 6 else "minecraft:dark_oak_planks")

    # 3. Cantilevered Timber Hoist Crane & Suspended Cargo Pallet (X: 19..20, Z: 31..38)
    for z in range(31, 39):
        b.set_block(19, 9, z, "minecraft:dark_oak_log", {"axis": "z"})
        b.set_block(20, 9, z, "minecraft:dark_oak_log", {"axis": "z"})
    b.set_block(19, 10, 35, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
    b.set_block(20, 10, 35, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
    b.set_block(19, 8, 35, "minecraft:dark_oak_fence")
    b.set_block(20, 8, 35, "minecraft:dark_oak_fence")

    # Hanging Crane Chains & Cargo Pallet (Z: 36..38)
    for y in range(5, 9):
        b.set_block(19, y, 37, "minecraft:chain")
        b.set_block(20, y, 37, "minecraft:chain")
    b.fill_box(18, 4, 36, 21, 4, 38, "minecraft:dark_oak_slab", {"type": "top"})
    b.set_block(19, 5, 36, "minecraft:barrel", {"facing": "up"})
    b.set_block(20, 5, 36, "minecraft:chest", {"facing": "south"})
    b.set_block(19, 3, 37, "minecraft:lantern", {"hanging": "true"})

    # 4. Armored NW Munitions & Powder Magazine (X: 7..15, Z: 7..16)
    for y in range(1, 5):
        for x in range(7, 16):
            b.set_block(x, y, 16, "minecraft:iron_bars" if (x not in (7, 11, 15)) else "minecraft:deepslate_bricks")
        for z in range(7, 17):
            b.set_block(15, y, z, "minecraft:iron_bars" if (z not in (7, 11, 16)) else "minecraft:deepslate_bricks")
    # Vault Door Opening at Z=16, X=12
    b.set_block(12, 1, 16, "minecraft:air")
    b.set_block(12, 2, 16, "minecraft:air")

    # Inside Munitions Vault: Supply Objective Core, TNT, & Powder Barrels
    b.fill_box(9, 1, 9, 12, 1, 12, "minecraft:iron_block")
    b.set_block(10, 2, 10, "minecraft:target")
    b.set_block(11, 2, 10, "minecraft:tnt")
    b.set_block(10, 2, 11, "minecraft:tnt")
    b.set_block(11, 2, 11, "minecraft:soul_lantern")

    for z in range(8, 15, 2):
        b.set_block(7, 1, z, "minecraft:barrel", {"facing": "east"})
        b.set_block(7, 2, z, "minecraft:barrel", {"facing": "east"})
        b.set_block(7, 3, z, "minecraft:chest", {"facing": "east"})

    # 5. NE Siege Foundry & Armory (X: 24..32, Z: 7..17)
    b.fill_box(28, 1, 6, 32, 3, 7, "minecraft:deepslate_bricks")
    b.set_block(29, 1, 7, "minecraft:blast_furnace", {"facing": "south"})
    b.set_block(30, 1, 7, "minecraft:blast_furnace", {"facing": "south"})
    b.set_block(31, 1, 7, "minecraft:lava_cauldron")
    # Foundry Chimney rising through roof (X: 29..30, Z: 6, Y: 4..16)
    for y in range(4, 17):
        b.set_block(29, y, 6, "minecraft:deepslate_bricks")
        b.set_block(30, y, 6, "minecraft:deepslate_bricks")
    b.set_block(29, 17, 6, "minecraft:campfire", {"lit": "true"})
    b.set_block(30, 17, 6, "minecraft:cobblestone_wall")

    # Armory Workstations & Weapon Chests
    b.set_block(26, 1, 10, "minecraft:anvil", {"facing": "west"})
    b.set_block(26, 1, 12, "minecraft:smithing_table")
    b.set_block(26, 1, 14, "minecraft:grindstone", {"facing": "west"})
    b.set_block(26, 1, 16, "minecraft:fletching_table")
    for z in range(9, 17, 2):
        b.set_block(32, 1, z, "minecraft:chest", {"facing": "west"})
        b.set_block(32, 2, z, "minecraft:barrel", {"facing": "west"})

    # 6. Multi-Tier Timber Storage Racks (West & East Bays, Z: 19..27)
    for z in (20, 24):
        for rx1, rx2 in [(8, 14), (25, 31)]:
            for x in range(rx1, rx2 + 1):
                if x in (rx1, (rx1 + rx2) // 2, rx2):
                    for y in range(1, 5):
                        b.set_block(x, y, z, "minecraft:dark_oak_fence")
                else:
                    b.set_block(x, 1, z, "minecraft:barrel", {"facing": "up"})
                    b.set_block(x, 2, z, "minecraft:dark_oak_slab", {"type": "top"})
                    b.set_block(x, 3, z, "minecraft:chest" if x % 2 == 0 else "minecraft:hay_block")
                    b.set_block(x, 4, z, "minecraft:dark_oak_slab", {"type": "bottom"})

    # Quartermaster's Weigh Desk near Entrance (X: 9..14, Z: 29..31)
    for x in range(9, 15):
        b.set_block(x, 1, 29, "minecraft:dark_oak_planks")
        b.set_block(x, 2, 29, "minecraft:lectern" if x == 11 else "minecraft:dark_oak_slab", {"facing": "south"} if x == 11 else {"type": "bottom"})
    b.set_block(10, 2, 29, "minecraft:lantern")

    # 7. Second Floor Crane Loft & Catwalks (Y=6)
    for x in range(sx1 + 1, sx2):
        for z in range(sz1 + 1, sz2):
            if x <= 10 or x >= 29 or z <= 9 or (18 <= x <= 21 and z >= 26):
                b.set_block(x, 6, z, "minecraft:dark_oak_planks")
                if x in (10, 29) or z == 9:
                    b.set_block(x, 7, z, "minecraft:dark_oak_fence")

    # Catwalk Stairs along East Wall (X: 31..32, Z: 25..29)
    for step in range(5):
        sz_step = 29 - step
        b.fill_box(31, 1, sz_step, 32, step, sz_step, "minecraft:cobblestone")
        b.set_block(31, step + 1, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(32, step + 1, sz_step, "minecraft:dark_oak_stairs", {"facing": "north", "half": "bottom"})
        b.set_block(31, 6, sz_step, "minecraft:air")
        b.set_block(32, 6, sz_step, "minecraft:air")

    # 8. Pitched Industrial Roof & Parapets (Y=12..16)
    for x in range(sx1, sx2 + 1):
        for z in range(sz1, sz2 + 1):
            if b.get_block_name(x, 12, z) == "minecraft:air":
                b.set_block(x, 12, z, "minecraft:dark_oak_planks")
            if x in (sx1, sx2) or z in (sz1, sz2):
                b.set_block(x, 13, z, "minecraft:deepslate_bricks")
                if (x + z) % 2 == 0:
                    b.set_block(x, 14, z, "minecraft:deepslate_brick_wall")

    # Twin Pitched Timber Roof Ridges (West Bay X: 8..16 & East Bay X: 23..31)
    for z in range(sz1 + 1, sz2):
        for step in range(4):
            # West Ridge
            b.set_block(8 + step, 13 + step, z, "minecraft:dark_oak_stairs", {"facing": "east", "half": "bottom"})
            b.set_block(15 - step, 13 + step, z, "minecraft:dark_oak_stairs", {"facing": "west", "half": "bottom"})
            # East Ridge
            b.set_block(24 + step, 13 + step, z, "minecraft:dark_oak_stairs", {"facing": "east", "half": "bottom"})
            b.set_block(31 - step, 13 + step, z, "minecraft:dark_oak_stairs", {"facing": "west", "half": "bottom"})

    b.apply_weathering(seed=404)
    return b


def generate_all():
    out_dir = "src/main/resources/data/warfront/structure/base/headquarters"
    os.makedirs(out_dir, exist_ok=True)

    print("Building Canonical Pillager HQ Shell (50x18x90)...")
    shell = build_hq_shell()
    shell.export_nbt(os.path.join(out_dir, "pillager_hq_shell.nbt"))

    print("Building Garrison Commander Keep (40x22x40)...")
    keep = build_commander_keep()
    keep.export_nbt(os.path.join(out_dir, "pillager_hq_commander_keep.nbt"))

    print("Building Command Center & Intel Bunker (40x24x40)...")
    intel = build_intel_bunker()
    intel.export_nbt(os.path.join(out_dir, "pillager_hq_intel_bunker.nbt"))

    print("Building Reinforced Supply Depot (40x18x40)...")
    supply = build_supply_depot()
    supply.export_nbt(os.path.join(out_dir, "pillager_hq_supply_depot.nbt"))

    print("All 4 canonical final-product templates generated successfully!")


if __name__ == "__main__":
    generate_all()
