"""
Visual Layer Previewer for Warfront Structure Templates.
Renders 2D top-down PNG slices of any .nbt template using Pillow.
"""

import os
import sys
from PIL import Image, ImageDraw
from nbt_writer import read_structure_template

BLOCK_COLORS = {
    "minecraft:air": (24, 24, 28, 255),
    "minecraft:cobblestone": (120, 120, 120, 255),
    "minecraft:mossy_cobblestone": (100, 125, 100, 255),
    "minecraft:cobblestone_stairs": (115, 115, 115, 255),
    "minecraft:cobblestone_slab": (118, 118, 118, 255),
    "minecraft:cobblestone_wall": (130, 130, 130, 255),
    "minecraft:stone_bricks": (135, 135, 135, 255),
    "minecraft:mossy_stone_bricks": (115, 140, 115, 255),
    "minecraft:cracked_stone_bricks": (110, 110, 110, 255),
    "minecraft:chiseled_stone_bricks": (145, 145, 145, 255),
    "minecraft:stone_brick_stairs": (130, 130, 130, 255),
    "minecraft:stone_brick_slab": (132, 132, 132, 255),
    "minecraft:stone_brick_wall": (140, 140, 140, 255),
    "minecraft:deepslate_bricks": (60, 60, 65, 255),
    "minecraft:cracked_deepslate_bricks": (50, 50, 55, 255),
    "minecraft:deepslate_brick_stairs": (65, 65, 70, 255),
    "minecraft:deepslate_brick_slab": (62, 62, 68, 255),
    "minecraft:deepslate_brick_wall": (75, 75, 80, 255),
    "minecraft:polished_deepslate": (68, 68, 72, 255),
    "minecraft:polished_deepslate_stairs": (72, 72, 76, 255),
    "minecraft:polished_deepslate_slab": (70, 70, 74, 255),
    "minecraft:polished_deepslate_wall": (80, 80, 85, 255),
    "minecraft:deepslate_tiles": (48, 48, 52, 255),
    "minecraft:cracked_deepslate_tiles": (44, 44, 48, 255),
    "minecraft:deepslate_tile_stairs": (52, 52, 56, 255),
    "minecraft:deepslate_tile_slab": (50, 50, 54, 255),
    "minecraft:deepslate_tile_wall": (58, 58, 62, 255),
    "minecraft:chiseled_deepslate": (76, 76, 82, 255),
    "minecraft:dark_oak_log": (55, 36, 20, 255),
    "minecraft:stripped_dark_oak_log": (65, 44, 25, 255),
    "minecraft:dark_oak_wood": (55, 36, 20, 255),
    "minecraft:stripped_dark_oak_wood": (65, 44, 25, 255),
    "minecraft:dark_oak_planks": (76, 50, 28, 255),
    "minecraft:dark_oak_stairs": (86, 58, 34, 255),
    "minecraft:dark_oak_slab": (80, 54, 30, 255),
    "minecraft:dark_oak_fence": (102, 68, 40, 255),
    "minecraft:dark_oak_fence_gate": (102, 68, 40, 255),
    "minecraft:dark_oak_door": (110, 72, 42, 255),
    "minecraft:dark_oak_trapdoor": (100, 65, 38, 255),
    "minecraft:spruce_planks": (114, 84, 48, 255),
    "minecraft:spruce_stairs": (120, 90, 52, 255),
    "minecraft:spruce_slab": (116, 86, 50, 255),
    "minecraft:spruce_fence": (125, 95, 55, 255),
    "minecraft:spruce_trapdoor": (110, 80, 45, 255),
    "minecraft:iron_bars": (176, 196, 222, 255),
    "minecraft:chain": (150, 170, 190, 255),
    "minecraft:iron_block": (220, 220, 225, 255),
    "minecraft:iron_door": (200, 200, 205, 255),
    "minecraft:iron_trapdoor": (195, 195, 200, 255),
    "minecraft:heavy_weighted_pressure_plate": (210, 210, 215, 255),
    "minecraft:lightning_rod": (205, 127, 50, 255),
    "minecraft:gravel": (160, 155, 150, 255),
    "minecraft:coarse_dirt": (119, 85, 59, 255),
    "minecraft:packed_mud": (142, 106, 79, 255),
    "minecraft:red_carpet": (178, 34, 34, 255),
    "minecraft:yellow_carpet": (218, 165, 32, 255),
    "minecraft:black_carpet": (30, 30, 35, 255),
    "minecraft:gray_carpet": (70, 70, 75, 255),
    "minecraft:red_wool": (170, 30, 30, 255),
    "minecraft:green_wool": (60, 120, 45, 255),
    "minecraft:brown_wool": (110, 75, 45, 255),
    "minecraft:blue_wool": (45, 65, 150, 255),
    "minecraft:black_wool": (28, 28, 32, 255),
    "minecraft:gray_wool": (68, 68, 72, 255),
    "minecraft:white_banner": (235, 235, 235, 255),
    "minecraft:gray_banner": (100, 100, 105, 255),
    "minecraft:black_banner": (35, 35, 40, 255),
    "minecraft:red_banner": (180, 35, 35, 255),
    "minecraft:white_wall_banner": (235, 235, 235, 255),
    "minecraft:gray_wall_banner": (100, 100, 105, 255),
    "minecraft:black_wall_banner": (35, 35, 40, 255),
    "minecraft:red_wall_banner": (180, 35, 35, 255),
    "minecraft:chest": (198, 125, 50, 255),
    "minecraft:trapped_chest": (198, 110, 45, 255),
    "minecraft:barrel": (140, 95, 45, 255),
    "minecraft:crafting_table": (150, 100, 55, 255),
    "minecraft:cartography_table": (135, 90, 50, 255),
    "minecraft:fletching_table": (185, 160, 110, 255),
    "minecraft:smithing_table": (75, 60, 65, 255),
    "minecraft:loom": (160, 125, 80, 255),
    "minecraft:lectern": (165, 115, 60, 255),
    "minecraft:bookshelf": (155, 115, 65, 255),
    "minecraft:chiseled_bookshelf": (145, 105, 58, 255),
    "minecraft:anvil": (70, 70, 75, 255),
    "minecraft:chipped_anvil": (70, 70, 75, 255),
    "minecraft:damaged_anvil": (70, 70, 75, 255),
    "minecraft:grindstone": (130, 130, 135, 255),
    "minecraft:cauldron": (65, 65, 70, 255),
    "minecraft:lava_cauldron": (230, 100, 20, 255),
    "minecraft:water_cauldron": (50, 90, 180, 255),
    "minecraft:bell": (235, 195, 45, 255),
    "minecraft:target": (220, 90, 90, 255),
    "minecraft:tnt": (210, 45, 45, 255),
    "minecraft:hay_block": (200, 175, 50, 255),
    "minecraft:lodestone": (140, 145, 150, 255),
    "minecraft:blast_furnace": (85, 85, 90, 255),
    "minecraft:smoker": (95, 90, 85, 255),
    "minecraft:furnace": (110, 110, 115, 255),
    "minecraft:stonecutter": (125, 120, 115, 255),
    "minecraft:lantern": (255, 220, 80, 255),
    "minecraft:soul_lantern": (0, 229, 255, 255),
    "minecraft:torch": (255, 210, 60, 255),
    "minecraft:wall_torch": (255, 210, 60, 255),
    "minecraft:redstone_torch": (220, 30, 30, 255),
    "minecraft:campfire": (245, 150, 35, 255),
    "minecraft:soul_campfire": (30, 215, 240, 255),
    "minecraft:wither_skeleton_skull": (35, 35, 35, 255),
    "minecraft:skeleton_skull": (210, 210, 205, 255),
}

DEFAULT_COLOR = (180, 100, 200, 255)


def render_layer_png(template_data: dict, y_layer: int, block_scale: int = 10) -> Image.Image:
    """Renders a single Y layer of the template into a PIL Image."""
    size = template_data["size"]
    sx, sy, sz = size
    palette = [p["Name"] for p in template_data.get("palette", [])]

    grid = {}
    for b in template_data.get("blocks", []):
        x, y, z = b["pos"]
        if y == y_layer:
            grid[(x, z)] = palette[b["state"]]

    img_w = sx * block_scale
    img_h = sz * block_scale
    img = Image.new("RGBA", (img_w, img_h), (20, 20, 24, 255))
    draw = ImageDraw.Draw(img)

    for x in range(sx):
        for z in range(sz):
            block_name = grid.get((x, z), "minecraft:air")
            color = BLOCK_COLORS.get(block_name, DEFAULT_COLOR)
            rx0 = x * block_scale
            rz0 = z * block_scale
            rx1 = rx0 + block_scale - 1
            rz1 = rz0 + block_scale - 1

            if block_name != "minecraft:air":
                draw.rectangle([rx0, rz0, rx1, rz1], fill=color, outline=(0, 0, 0, 80))
            else:
                draw.rectangle([rx0, rz0, rx1, rz1], fill=(28, 28, 32, 255), outline=(36, 36, 42, 255))

    return img


def render_composite_overview(template_path: str, output_path: str, selected_layers: list[int] = None, block_scale: int = 8):
    """Renders multiple key horizontal slices into a clean side-by-side / grid composite PNG."""
    data = read_structure_template(template_path)
    sx, sy, sz = data["size"]

    if selected_layers is None:
        step = max(1, sy // 6)
        selected_layers = [y for y in range(0, sy, step)]
        if (sy - 1) not in selected_layers:
            selected_layers.append(sy - 1)

    layer_images = []
    for y in selected_layers:
        if 0 <= y < sy:
            lim = render_layer_png(data, y, block_scale=block_scale)
            labeled = Image.new("RGBA", (lim.width, lim.height + 24), (16, 16, 20, 255))
            draw = ImageDraw.Draw(labeled)
            draw.text((8, 4), f"Layer Y = {y} (Local)", fill=(220, 220, 220, 255))
            labeled.paste(lim, (0, 24))
            layer_images.append((y, labeled))

    if not layer_images:
        return

    num_panels = len(layer_images)
    cols = min(3, num_panels)
    rows = (num_panels + cols - 1) // cols

    panel_w = layer_images[0][1].width
    panel_h = layer_images[0][1].height
    gap = 12

    total_w = cols * panel_w + (cols + 1) * gap
    total_h = rows * panel_h + (rows + 1) * gap + 36

    composite = Image.new("RGBA", (total_w, total_h), (12, 12, 16, 255))
    draw = ImageDraw.Draw(composite)

    title = f"{os.path.basename(template_path)} — Size: {sx}×{sy}×{sz} — Key Layers"
    draw.text((gap, gap), title, fill=(255, 215, 0, 255))

    for idx, (y, p_img) in enumerate(layer_images):
        c = idx % cols
        r = idx // cols
        px = gap + c * (panel_w + gap)
        py = 36 + gap + r * (panel_h + gap)
        composite.paste(p_img, (px, py))

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    composite.save(output_path, "PNG")
    print(f"Rendered composite layer preview: {output_path}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python layer_preview.py <template.nbt> <output_overview.png>")
        sys.exit(1)
    render_composite_overview(sys.argv[1], sys.argv[2])
