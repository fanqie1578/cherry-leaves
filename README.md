# Falling Cherry Petals Mod

**Version: 1.0.0-beat**

A NeoForge 1.21.1 mod that makes cherry leaves fall and create cherry leaf piles on the ground.

## Features

- When cherry leaves are broken, they drop a cherry petal item.
- The cherry petal item will transform into a cherry leaf pile block when it lands on the ground.
- Cherry leaf piles can stack up to 4 layers (configurable).
- Adds a creative tab with the cherry petal item and cherry leaf pile block.
- Fully configurable through config file.
- Custom mod logo included.

## Configuration

The mod includes a comprehensive configuration file (`falling_cherry_petals-common.toml`) with the following options:

### Petal Drop Settings
- `petalDropChance`: Chance for cherry leaves to drop petals (0.0-1.0)
- `petalDropMin`: Minimum petals dropped per leaf block
- `petalDropMax`: Maximum petals dropped per leaf block

### Leaf Pile Settings
- `pileFormChance`: Chance for petals to form leaf piles when landing (0.0-1.0)
- `maxPileLayers`: Maximum stack height for leaf piles (1-8)

### Fire Settings
- `canBeIgnited`: Whether players can ignite leaf piles with flint and steel
- `spreadFire`: Whether fire can spread to leaf piles naturally
- `burnTime`: How long leaf piles burn when ignited (in ticks, 20 ticks = 1 second)

### Visual/Audio Settings
- `enableParticleEffects`: Toggle particle effects for petal drops and pile formation
- `enableSoundEffects`: Toggle sound effects for all interactions

## Building

1. Ensure you have Java 17 or later installed.
2. Run `./gradlew build` (or `gradlew.bat build` on Windows) in the project root.
3. The built mod JAR will be in `build/libs/`.

## Installation

Place the built JAR file in your Minecraft `mods` folder.

## Usage

Break cherry leaves to obtain cherry petals. Throw the cherry petals on the ground to create cherry leaf piles.

## License

All Rights Reserved.

## Logo

The mod includes a placeholder logo file (`falling_cherry_petals.png`). To use your own logo:
1. Replace `src/main/resources/assets/falling_cherry_petals/falling_cherry_petals.png` with your own 128x128 pixel PNG image.
2. Rebuild the mod using `./gradlew build`.

## Author

tomato