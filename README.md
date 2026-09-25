# PokeWilds 0.8.11 BugFix

Fixes four bugs in [PokeWilds](https://github.com/SheerSt/pokewilds) 0.8.11. You run it once on your own copy of the
game, and the game plays the same afterwards, minus the bugs. It does not include any game files.

## What it fixes

- **Pokémon on different floors of a building no longer interfere with each other.**
  Before, a Pokémon on the 10th floor could block, freeze or hide a Pokémon standing at the same spot on the 2nd
  floor, and the player could get stuck on things that were really on another floor. Now every floor is separate.
- **Eggs stay on the floor where they were laid.** Before, an egg laid on an upper floor showed up on the first floor
  after you saved and reloaded.
- **Ride, Cut and Build Pokémon face the right way** when you move up or down. Before, with a modded Pokémon sprite,
  they turned sideways and flipped left and right. Popular sprite mods (such as the 3rd Gen Overhaul) were affected.
- **Ho-Oh no longer freezes the Pokémon around it.** Before, every Pokémon near Ho-Oh threw an error each frame and
  stopped moving.

## How to use it (Windows)

You need Java, the same one you use to run the game.

1. **Download** `PokeWilds-BugFix-v1.0.zip` from the [Releases](../../releases) page and unzip it anywhere.
2. **Get the official game.** Download `pokewilds-otherplatforms.zip` from the
   [PokeWilds v0.8.11 release](https://github.com/SheerSt/pokewilds/releases/tag/v0.8.11) and unzip it, or use the
   `app\pokewilds.jar` from a copy of the game you already have. It must be the original, unmodified 0.8.11 jar.
3. **Drag `pokewilds.jar` onto `Patch PokeWilds (Windows, drag and drop).bat`.** It takes a minute or two.
   The window ends with `Done. pokewilds.jar is now the patched game.`
4. **Play as usual.** Your original is kept next to it as `pokewilds-original.jar.bak`.

**To undo:** drag the patched `pokewilds.jar` onto `Restore original PokeWilds (Windows, drag and drop).bat`.
Your original comes back, and the patched one is kept as `pokewilds-bugfix.jar`.

### macOS and Linux

```sh
java -jar bugfix.jar pokewilds.jar               # patch in place (keeps pokewilds-original.jar.bak)
java -jar bugfix.jar --restore pokewilds.jar     # undo
java -jar bugfix.jar in.jar out.jar              # write a patched copy and leave the input alone
```

## Questions

**Will it break my saves?** No. The save format is unchanged, and your saves and mods are not touched. You can also
go back to the original game at any time. One difference: if two Pokémon on different floors were standing on exactly
the same spot when you saved, one is saved a tile or two away on its own floor, so nothing is lost.

**Do mods still work?** Yes. Sprite, music and other mods load as before. The fixes only change the game's own code.

**What if it says "already patched" or "does not match PokeWilds 0.8.11"?** It only accepts the original 0.8.11 jar.
"Already patched" means it has been done. Use the restore step first if you want to start again.

**Does it work on Android or ROCKNIX?** It patches the same `pokewilds.jar` those ports use, but the author has only
tested it on desktop.

**Can I use other add-ons with it?** Yes. A patched jar behaves like a normal jar, so add-ons that attach with
`-javaagent` still work on top of it.

**Is it safe?** The patcher checks that your jar is exactly the official 0.8.11, and it writes nothing unless every fix
applied exactly as expected. Your original is always kept.

---

# For developers

## The four fixes in detail

1. **Pokémon on different floors of a building affect each other.**
   Every floor of an interior is its own tile map, but all Pokémon (overworld and every floor) live in one map,
   `PkmnMap.pokemon`, keyed only by position. Pokémon on different floors that share coordinates therefore block
   each other, scan each other, overwrite each other's slot, and hide or freeze each other (a Ho-Oh on floor 10
   froze Pokémon on floor 2). The patch gives every tile map its own Pokémon map:
   - the game's own `pokemon` field is switched to the current floor's map whenever the player changes floor (all 11
     places that assign `PkmnMap.tiles` are hooked), so player, drawing and UI code run unchanged on a normal
     one-floor map; the drawn-Pokémon list and tile cache are rebuilt on a change;
   - code that belongs to a Pokémon uses that Pokémon's own floor; world-level code (day/night spawning, world
     generation) uses the overworld or all floors;
   - a Pokémon is registered in exactly one place, so leftover registrations ("trails") cannot pile up;
   - saving: the save file is keyed by position only, so a Pokémon whose position is already taken by one from
     another floor is written to the nearest free tile of its own floor. Nothing is dropped. The save format is
     unchanged.
2. **Eggs laid on upper floors reload on the first floor.** `Network.PokemonDataV07(Pokemon)` saves
   `pokemon.interiorIndex`, which is only kept up to date for Pokémon the player dropped; a new egg keeps the
   default 100 (the first floor). The patch saves the index of the tile map the Pokémon is really on. Eggs that are
   already in a save on the wrong floor stay there.
3. **Cut / Ride / Build Pokémon face sideways when moving up or down** with per-species mod sprites
   (`mods/pokemon/<name>/overworld.png`, a vertical 16x96 strip). `DrawPlayerUpper/Lower` force the drawn region's Y
   from `player.spriteOffsetY`, which vertical mod sheets never set. The patch uses the sprite's own region Y, which
   is identical for the built-in sheet. Popular mods such as the 3rd Gen Overhaul are affected by the bug.
4. **`ho_oh` missing from `Pokemon.baseSpecies`.** The table is keyed from `evos_attacks.asm` headers
   (`HoOhEvosAttacks:` becomes `hooh`) but the species is `ho_oh`, so every Pokémon near Ho-Oh threw a
   `NullPointerException` each frame and stopped updating. The patch adds the missing entry.

## Patcher options

`--no-sprites`, `--no-hooh`, `--no-floors`, `--no-eggs` leave individual fixes out. The patcher also prints a **patch
fingerprint**, a hash of the patched classes that is the same on every machine (the hash of the jar file is not, since
it depends on how the zip is compressed). It should read `identical to the reference build`. The patched jar reads and
writes the same save format as the original.

### Using it as a `-javaagent` instead (nothing on disk changes)

```sh
java -javaagent:bugfix.jar -jar pokewilds.jar
```

Same patches, applied when the classes load. Each can be switched off with `-Dbugfix.sprites=false`,
`-Dbugfix.hooh=false`, `-Dbugfix.floors=false`, `-Dbugfix.eggs=false`. The floors patch also checks the class files
(SHA-256), so on any other version it turns itself off. `-Dbugfix.debug=true` logs floor changes, cleaned-up
registrations and suspicious registrations to stderr.

## Build and test

Needs a JDK 11+ and `curl`. ASM 9.7 is downloaded from Maven Central and checked against pinned SHA-1s; it is
bundled under a relocated package so it cannot clash with the older ASM inside `pokewilds.jar`.

```sh
./build.sh                          # writes dist/bugfix.jar
./test.sh /path/to/pokewilds.jar    # builds, then runs all checks against the official jar
```

The checks cover: every patched site accounted for against an independent scan of the jar (95 reads of the Pokémon
map: 40 routed to the Pokémon's floor, 7 to the overworld, 3 to all floors, 4 to the save; 11 floor assignments; 1
constructor init); every patched class passing `-Xverify:all`; all 405 game classes loading both through the agent
and from a patched jar with no agent; the per-floor maps' logic, including a randomized run of the game's
register/unregister protocol with leftover registrations injected; and the patcher refusing anything but the
official jar.

## Status

The fixes were played through on desktop (Windows, stock 0.8.11 jar) on a save with a 20+ floor tower full of
roaming Pokémon: collisions on every floor behave like a one-floor game, saving and reloading keeps each floor's
Pokémon, Ride/Cut/Build face the right way, and the Ho-Oh error is gone. Overhead is small: about 0.2 ms extra per
frame for a frame's worth of Pokémon scans (300 Pokémon), measured in isolation. Not tested by the author: other
platforms (Android, ROCKNIX).

## Layout

| Path | What |
| --- | --- |
| `src/local/pokewilds/bugfix/BugFixAgent.java` | the bytecode patches and the `-javaagent` entry point |
| `src/local/pokewilds/bugfix/PatchJar.java` | the offline patcher (`java -jar bugfix.jar in out`) |
| `src/local/pokewilds/bugfix/Hooks.java` | runtime support the patched classes call (per-floor maps, saving, egg floor) |
| `Patch PokeWilds (Windows, drag and drop).bat` | drag-and-drop patcher on Windows (in place, keeps a backup) |
| `Restore original PokeWilds (Windows, drag and drop).bat` | puts the original jar back |
| `tools/RelocateAsm.java` | copies ASM into the bugfix package at build time |
| `tests/` | the checks run by `test.sh` |

## License

MIT, see `LICENSE`. The bundled ASM is BSD-3-Clause, see `licenses/ASM-LICENSE.txt`. PokeWilds and its assets belong
to their authors and are not part of this repository.
