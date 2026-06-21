# Warfront

Warfront is a strategic territorial warfare mod for Minecraft 1.21.1 on NeoForge.

## Requirements

- JDK 21: `C:\Program Files\Java\jdk-21`
- IntelliJ IDEA Community Edition
- Git
- Blockbench for block, item, entity, and structure models
- An image editor for textures

Gradle is provided through the committed wrapper. Do not install or use a global Gradle version.

## Development

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runData
.\gradlew.bat build
```

The project pins Gradle to JDK 21 in `gradle.properties`; Java 25 on the global PATH is not used.

## Region Ownership Test

Regions are 128 by 128 blocks. Press `R` in-game to open a 17 by 17 chunk strategic map centered on the player.

Use the following operator command to set an owner for the current region:

```text
/warfront region set-owner <unclaimed|zombie_horde|pillager_conquerors|piglin_expeditions|ender_ascendancy>
```

Ownership is persisted in the world save at `data/warfront_regions_128.dat`.

## Biome Map Colors

Map colors are server datapack data. Add a JSON file at:

```text
data/<namespace>/warfront/biome_map_colors/<file>.json
```

```json
{
  "colors": {
    "examplemod:crystal_forest": "#4B78C8"
  }
}
```

Use `/reload` after adding or changing a datapack. Unmapped biomes use `#666666`.

## Integration Policy

Create, Create Big Cannons, Create Aeronautics, and TACZ stay out of the first milestone. Add their development dependencies only after the base region simulation and persistence work in an isolated development client.
