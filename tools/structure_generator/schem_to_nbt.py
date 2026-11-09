"""
WorldEdit .schem to Minecraft Native Structure Template (.nbt) Converter.
Converts Sponge Schematic format (Sponge v1, v2, v3 used by WorldEdit 7+) into Minecraft 1.21.1 Structure Templates.
"""

import sys
import os
from io import BytesIO
from nbt_writer import read_structure_template, write_structure_template


def parse_block_state_string(state_str: str) -> tuple[str, dict]:
    """
    Parses 'minecraft:stone_stairs[facing=north,half=bottom]'
    into ('minecraft:stone_stairs', {'facing': 'north', 'half': 'bottom'}).
    """
    if "[" not in state_str:
        return state_str, {}
    name, props_str = state_str.split("[", 1)
    props_str = props_str.rstrip("]")
    props = {}
    for part in props_str.split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            props[k.strip()] = v.strip()
    return name.strip(), props


def read_varint(stream: BytesIO) -> int:
    """Reads a VarInt from a byte stream."""
    value = 0
    shift = 0
    while True:
        b = stream.read(1)
        if not b:
            return 0
        byte = b[0]
        value |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return value


def convert_schem_to_nbt(schem_path: str, output_nbt_path: str) -> bool:
    """
    Converts a WorldEdit Sponge .schem file to a Minecraft Structure Template .nbt file.
    """
    if not os.path.exists(schem_path):
        print(f"Error: Schematic file not found: {schem_path}")
        return False

    raw_nbt = read_structure_template(schem_path)
    if not raw_nbt:
        print("Error: Could not parse schematic file as NBT.")
        return False

    # In Sponge v3, schematic data is enclosed under root tag 'Schematic'
    root = raw_nbt.get("Schematic", raw_nbt)

    width = root.get("Width")
    height = root.get("Height")
    length = root.get("Length")

    if width is None or height is None or length is None:
        print(f"Error: Missing dimensions in schematic: Width={width}, Height={height}, Length={length}")
        return False

    # In Sponge v3, block palette and data are under 'Blocks' compound
    blocks_compound = root.get("Blocks", root)
    raw_palette = blocks_compound.get("Palette", root.get("Palette", {}))
    block_data_bytes = blocks_compound.get("Data", blocks_compound.get("BlockData", b""))

    if not raw_palette or not block_data_bytes:
        print("Error: Schematic contains empty Palette or BlockData.")
        return False

    # Invert palette: schem_idx -> state_str
    schem_idx_to_str = {}
    for state_str, idx in raw_palette.items():
        schem_idx_to_str[idx] = state_str

    template_palette = []
    schem_idx_to_pal_idx = {}

    for schem_idx, state_str in schem_idx_to_str.items():
        name, props = parse_block_state_string(state_str)
        pal_entry = {"Name": name}
        if props:
            pal_entry["Properties"] = props
        template_palette.append(pal_entry)
        schem_idx_to_pal_idx[schem_idx] = len(template_palette) - 1

    # Extract blocks: Sponge coordinate indexing is index = (y * length + z) * width + x
    stream = BytesIO(block_data_bytes)
    blocks = []
    non_air_count = 0

    for y in range(height):
        for z in range(length):
            for x in range(width):
                schem_idx = read_varint(stream)
                pal_idx = schem_idx_to_pal_idx.get(schem_idx, 0)
                if template_palette[pal_idx]["Name"] != "minecraft:air":
                    non_air_count += 1
                blocks.append({"pos": (x, y, z), "state": pal_idx})

    data_version = root.get("DataVersion", 3955)

    os.makedirs(os.path.dirname(os.path.abspath(output_nbt_path)), exist_ok=True)
    write_structure_template(
        output_nbt_path,
        (width, height, length),
        template_palette,
        blocks,
        data_version=data_version
    )

    print(f"Successfully converted '{schem_path}' ({width}x{height}x{length}, {non_air_count} non-air blocks, {len(template_palette)} palette entries) -> '{output_nbt_path}'!")
    return True


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python schem_to_nbt.py <input.schem> <output.nbt>")
        sys.exit(1)
    success = convert_schem_to_nbt(sys.argv[1], sys.argv[2])
    sys.exit(0 if success else 1)
