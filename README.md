# Tool Canvas (NeoForge 1.21.1)

Repaint any tool or melee weapon, vanilla or modded, **one item at a time**, in-game.

## How it plays

1. Craft a **Design Table**:
   ```
   Paper  Dye*   Paper
   Iron   Craft  Iron        * any dye
   Plank  ·      Plank       Craft = crafting table
   ```
2. Put a tool in the top slot. The canvas loads the tool's current look (custom or original).
3. Draw. Tools: Pen, Eraser, Fill, Pick, Undo/Redo (Ctrl+Z / Ctrl+Y), Mirror, Grid, Clear, Original.
   **Upscale** doubles the resolution (16×16 → 32×32 → 64×64) keeping your drawing; Shift+click for a smooth
   (Scale2x) upscale. **Shrink** halves it.
   Left-click paints, right-click erases, middle-click picks a colour. The top palette row is made of
   the tool's own colours; type any `#RRGGBB` / `#AARRGGBB` in the hex box.
4. Put **1 repair material** in the payment slot (iron ingot for iron tools, planks for wood, a diamond
   for diamond, a modded weapon's own repair item...) and press **Apply**.
5. **Reset** puts the tool back to its original look for free.

Only that one item changes; every other tool of the same type keeps its own look.
The custom look shows everywhere the item is drawn: inventory, hand (1st and 3rd person), item frames,
dropped items, other players' hands. Enchantment glint still works.

## Install (everyone who joins the world needs it)

Players already need the same mods as the host to use other mods' weapons, so this goes in the same
mods folder. In the Modrinth app: open the profile → **⋯ → Open folder** → drop the jar into `mods/`.
Do this on the host's PC and every player's PC.

## Building the jar

Needs **JDK 21** (e.g. [Adoptium Temurin 21](https://adoptium.net/)).

```
# Windows
gradlew.bat build
# macOS / Linux
./gradlew build
```

The jar appears in `build/libs/toolcanvas-1.0.0.jar`. The first build downloads Minecraft + NeoForge (a few minutes).

**No local setup?** Push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`)
builds it automatically; download the jar from the run's **Artifacts** section.

To test in a dev client: `gradlew runClient`.

## Performance

- An item stores only a 64-bit design id (a few bytes), so inventory syncs, entity equipment and chunk
  saves stay as cheap as vanilla.
- Pixel data is stored once per unique design in the world save (`data/toolcanvas_designs.dat`), and is
  palette-compressed on the network. Identical drawings share one copy.
- Each client downloads a design once, the first time it sees it, then caches it (texture + baked model)
  until it leaves the world. There are no resource-pack reloads, ever.
- Tools without a design render through the original model untouched; the only overhead is one
  component lookup.

## Compatibility

- Works with any damageable item that is a tiered tool, is in `#minecraft:swords/axes/pickaxes/shovels/hoes`
  or `#c:tools/melee_weapon`, `#c:tools/mining_tool`, `#c:tools/shear`, `#c:tools/mace`, or simply deals
  melee damage when held. That covers almost all modded weapons.
- Add extras with the item tag `toolcanvas:customizable` (damageable items only) and exclude items with
  `toolcanvas:blacklist` (via a datapack or KubeJS). The trident is excluded by default because vanilla
  hard-codes its rendering.
- Weapons that use a separate in-hand model (like the trident trick many weapon mods use) still show the
  design in hand: a small client mixin puts the design model back right before the item is drawn.
- Weapons with 3D models become a flat sprite while they carry a design, posed like a vanilla sword.
  Reset brings the 3D model back.
- Bows, crossbows, shields and fishing rods are excluded on purpose: their pull/block/cast animations are
  separate models.
- Upgrading diamond → netherite at a smithing table keeps the design (vanilla copies item data).

## Config

`<world>/serverconfig/toolcanvas-server.toml` (synced to players):

| Option | Default | |
|---|---|---|
| `costEnabled` | `true` | Charge 1 repair material per apply |
| `freeInCreative` | `true` | No cost in creative |
| `fallbackCostItem` | `minecraft:iron_ingot` | Cost for tools with no repair material |
| `maxCanvasSize` | `64` | Largest canvas edge in pixels (16–64) |
