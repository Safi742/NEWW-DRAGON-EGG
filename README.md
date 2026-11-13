# DragonEggSolo — updated test build

Paper plugin source for a floating, rotating Dragon Egg event.

## Features in this version
- Scales the displayed egg up to approximately a full block and makes it bob and rotate.
- Replaces the text hologram with a boss bar shown to players within the configured radius.
- Boss bar and egg outline are blue when exactly one player is nearby, red otherwise.
- The egg can be claimed only when exactly one player is nearby.
- A player carrying a Dragon Egg item receives the glowing effect while they hold it in their inventory.

## Build
Requires the same Paper API/JDK setup as the previous project. Run `mvn package`; output is in `target/`.

## Important limitations
- This version uses Minecraft's built-in Dragon Egg texture. A custom animated texture pack is a separate resource-pack ZIP that players must download; a plugin JAR cannot silently install a pack on every client. To use a custom texture later, host the pack and configure the server resource-pack URL/hash.
- The blue/red outline uses the server scoreboard team color and may interact with other plugins that manage scoreboard teams.
- This source has not been compiled or tested on your server yet. Build it with GitHub Actions and test on a copy/staging server first.
- The glow effect is applied when a player has a Dragon Egg in their inventory. It is refreshed while carried and removed when the item is no longer present, unless the player already had glowing from another source.
