# Warfront — Pillager Headquarters (HQ) Slot & Structure Contract

## 1. Overview & Canonical Coordinate System

The Pillager Headquarters is a modular fortress structure designed for Warfront's macro-strategic warfare system. It spans across 2 adjacent tactical subregions ($52 \times 93$ blocks total) and houses two rolled mission facilities.

### Canonical Bounding Box
* **Footprint:** $X \in [0, 51]$ ($52\text{ blocks wide}$), $Z \in [0, 92]$ ($93\text{ blocks long}$).
* **Height Bounds:** $Y \in [-1, 24]$ ($26\text{ blocks high}$).
  * $Y = -1$: Subfloor foundation baseline (stepped cobblestone down to solid ground).
  * $Y = 0$: Courtyard ground & interior floor datum.
  * $Y = 1 \dots 5$: Wall interior & room ground floors.
  * $Y = 6$: Perimeter rampart walkway.
  * $Y = 7$: Outer wall parapet & crenellations.
  * $Y = 12 \dots 14$: Corner bastion upper fighting decks.
  * $Y = 15 \dots 22$: Building roofs, watchtowers, and antenna masts.

---

## 2. Partitioning: Shell & 40×40 Slots

```text
  Z = 0  ┌──────────────────────[ NORTH GATE ]──────────────────────┐
         │ Bastion NW (8×8)                                Bastion NE (8×8) │
  Z = 5  ├ - - - - - - - - - - - - - - - - - - - - - - - - - - - - -┤
  Z = 6  │ 6-block Outer Wall                               6-block Wall   │
         │                                                                 │
         │                         SLOT A                                  │
         │                 (North Wing: 40 × 40)                           │
         │                  X: [6..45], Z: [6..45]                         │
         │                                                                 │
  Z = 45 ├ - - - - - - - - - - - - - - - - - - - - - - - - - - - - -┤
  Z = 46 │ ─────── 1-block Central Thoroughfare Walkway ────────── │
  Z = 47 ├ - - - - - - - - - - - - - - - - - - - - - - - - - - - - -┤
         │                                                                 │
         │                         SLOT B                                  │
         │                 (South Wing: 40 × 40)                           │
         │                  X: [6..45], Z: [47..86]                        │
         │                                                                 │
  Z = 86 │ 6-block Outer Wall                               6-block Wall   │
  Z = 87 ├ - - - - - - - - - - - - - - - - - - - - - - - - - - - - -┤
         │ Bastion SW (8×8)                                Bastion SE (8×8) │
  Z = 92 └──────────────────────[ SOUTH MAIN GATE ]─────────────────┘
         X = 0                                                     X = 51
```

### 2.1. Outer Shell (`pillager_hq_shell.nbt`)
* **Footprint:** $52 \times 93$ ($X: 0..51, Z: 0..92$).
* **Wall Thickness:** 6 blocks wide along outer perimeters:
  * West Wall: $X \in [0, 5]$
  * East Wall: $X \in [46, 51]$
  * North Wall: $Z \in [0, 5]$
  * South Wall: $Z \in [87, 92]$
* **Gates & Openings:**
  * **South Main Gate:** Centered at $X \in [23, 28]$ (6 blocks wide), $Z \in [87, 92]$, Arch height $Y \in [1, 5]$.
  * **North Sally Port:** Centered at $X \in [24, 27]$ (4 blocks wide), $Z \in [0, 5]$, Arch height $Y \in [1, 4]$.
* **Interior Courtyard:** $X \in [6, 45]$ ($40\text{ blocks wide}$), $Z \in [6, 86]$ ($81\text{ blocks long}$).

### 2.2. Slot A — North Wing ($40 \times 40$)
* **Global HQ Relative Bounds:** $X \in [6, 45], Z \in [6, 45]$.
* **Building Relative Origin:** $(0, 0, 0)$ maps to $(6, 0, 6)$ in HQ shell space.
* **Internal Grid:** $40 \times 40$ blocks ($x \in [0, 39], z \in [0, 39]$).
* **Primary Entry:** Faces South ($z = 39$), aligned with central thoroughfare ($x \in [17, 22]$).

### 2.3. Slot B — South Wing ($40 \times 40$)
* **Global HQ Relative Bounds:** $X \in [6, 45], Z \in [47, 86]$.
* **Building Relative Origin:** $(0, 0, 0)$ maps to $(6, 0, 47)$ in HQ shell space.
* **Internal Grid:** $40 \times 40$ blocks ($x \in [0, 39], z \in [0, 39]$).
* **Primary Entry:** Faces North ($z = 0$), aligned with central thoroughfare ($x \in [17, 22]$).

---

## 3. The 3 HQ Facility Types

Each Headquarters rolls exactly 2 of the 3 facilities deterministically based on `HQWingContract` (one for Wing 0 / Slot A, one for Wing 1 / Slot B):

| Facility | Mission Type | Functional Points & Requirements |
|---|---|---|
| **Garrison Commander Keep** | `BASE_KILL_COMMANDER` | Elevated Dais, Commander Iron Throne, Red Carpet aisle, 2 Bodyguard warrior posts, multi-level hall, rooftop battlements for Marksmen. |
| **Command Center & Intel Post** | `BASE_DESTROY_INTEL` | Signal/radar tower mast, War Map table, Cipher record chests, communications vault, radio dispatch desks, officer bunks. |
| **Reinforced Supply Depot** | `BASE_DESTROY_SUPPLIES` | Heavy ammo racks, powder barrels, armored supply vaults, logistics desk, iron portcullis doors, loot chests. |

---

## 4. Mob Spawning & Functional Placement Invariant

* **Mob Spawning by Code:** Mob entities (Pillager Commander, Armored Elites, Warriors, Marksmen) are **strictly spawned via Java code** in `BaseGarrisonManager` and `HeadquartersGarrisonDefinition` using canonical relative offsets.
* **No Structure DATA Mode Dependency:** No custom or vanilla `DATA` mode marker blocks are required in the templates. The Java garrison manager resolves positions directly relative to the structure origin and anchor, ensuring custom AI goals, patrol paths, and 3-minute respawn mechanics operate with 100% stability.
* **Objective Prize Blocks:** Mission target blocks (War Desk, Supply Cache, etc.) are positioned cleanly at designated interior coordinates registered in the mission and garrison profiles.

---

## 5. Architectural Style & Block Palette

Adhering strictly to Pillager Conquerors militaristic architecture:
* **Foundation & Subfloor:** `minecraft:cobblestone`, `minecraft:mossy_cobblestone`.
* **Primary Walls & Parapets:** `minecraft:deepslate_bricks`, `minecraft:cracked_deepslate_bricks`, `minecraft:stone_bricks`.
* **Structural Timbering:** `minecraft:dark_oak_log` (axis-aligned vertical pilasters and horizontal tie-beams), `minecraft:dark_oak_planks`.
* **Defensive Elements:** `minecraft:deepslate_brick_wall`, `minecraft:dark_oak_fence`, `minecraft:iron_bars` (arrow slits), `minecraft:chains`.
* **Flooring & Detail:** `minecraft:polished_deepslate_tiles`, `minecraft:gravel`, `minecraft:red_carpet`, `minecraft:lantern`, `minecraft:soul_lantern`.
