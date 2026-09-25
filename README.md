# PokeWilds 0.8.11 BugFix

Fixes four bugs in [PokeWilds](https://github.com/SheerSt/pokewilds) 0.8.11 by patching the compiled game.
It ships **no game files**: it is a patcher (and an equivalent `-javaagent`) that you run on your own copy of the
official 0.8.11 release. Everything here is MIT-licensed.

## What it fixes

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

## Use it

You need the official PokeWilds **0.8.11** release: the `pokewilds-otherplatforms.zip` asset on the
[v0.8.11 release page](https://github.com/SheerSt/pokewilds/releases/tag/v0.8.11) (SHA-256
`5c0aca7f447ee6b4ed587f3ab2cefaf445219059d790862a7121c56a72fb22ba`), which contains `pokewilds.jar`.
Download `bugfix.jar` from this repo's releases (or build it, see below).

### Option A: patch the jar once (recommended)

It is a Java program, so it runs anywhere Java 8+ does (Windows, macOS, Linux). Patching **in place** replaces the
jar with the fixed one under the same name, so nothing else has to change, and keeps your original next to it:

```sh
java -jar bugfix.jar pokewilds.jar
# -> pokewilds.jar               the patched game
# -> pokewilds-original.jar.bak  your original, untouched
```

On Windows, drag `pokewilds.jar` onto `Patch PokeWilds (Windows, drag and drop).bat` (keep it next to `bugfix.jar`).
Patching rewrites the whole ~150 MB jar and can take a minute or two.

To undo, run `java -jar bugfix.jar --restore pokewilds.jar` (on Windows, drag the patched jar onto
`Restore original PokeWilds (Windows, drag and drop).bat`). The patched jar is kept as `pokewilds-bugfix.jar`.

To write a patched copy and leave the input alone, give two file names: `java -jar bugfix.jar in.jar out.jar`.

Then run the game exactly like the original: no launcher changes, and other `-javaagent` add-ons still work on top
of it. The patcher only accepts the official, unmodified 0.8.11 class files, and writes nothing unless every patch
applied exactly as expected. It never overwrites an existing, different backup. Options `--no-sprites`,
`--no-hooh`, `--no-floors`, `--no-eggs` leave individual fixes out.

It also prints a **patch fingerprint**, a hash of the patched classes that is the same on every machine (unlike the
hash of the jar file, which depends on how the zip was compressed). It should read
`identical to the reference build`.

Your saves and mods are not touched. A patched jar reads and writes the same save format. If you go back to the
original jar, Pokémon that were written to a different tile because two floors shared one are simply where the save
put them.

### Option B: use it as a `-javaagent` (nothing on disk changes)

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
