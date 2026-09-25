# Porting guide

For people who build PokeWilds into another app or device: an Android port, PortMaster or ROCKNIX packages, Linux
handhelds and so on. Windows players don't need this page. They use `PokeWilds-BugFix-v1.0.zip`.

## What you get

The download for you is `v1.0-for-port-devs.zip`. Everything is loose in the zip (no folder):

| File | What it is |
| --- | --- |
| `bugfix.jar` | the patcher. It is also a `-javaagent`, but the patcher is what you want |
| `bugfix-manifest.json` | the same facts as this page, in a form a build script can read: input hashes, commands, exit codes, expected result |
| `apply-bugfix.sh` | a small example that patches a jar and checks the result |
| `PORTING.md` | this page |
| `LICENSE`, `ASM-LICENSE.txt` | the license texts that go with `bugfix.jar` |

## The idea

You don't ship a modified game. You ship `bugfix.jar` and run it once on the official `pokewilds.jar` you already
download and check. The output is the fixed jar. Your port keeps doing what it does now (fetch the official release,
verify it), with one extra step.

## Steps

1. Get the official 0.8.11 `pokewilds.jar` and verify it the way you already do. The release asset is
   `pokewilds-otherplatforms.zip`, SHA-256 `5c0aca7f447ee6b4ed587f3ab2cefaf445219059d790862a7121c56a72fb22ba`. The
   jar inside it (`pokewilds-v0.8.11-otherplatforms/pokewilds.jar`) is the one I tested with.
2. Patch it into a new file. Two file names means the input is left alone:

   ```sh
   java -jar bugfix.jar official/pokewilds.jar game/pokewilds.jar
   ```

3. Check that the exit code is `0`. On success the output also says `Patched 22 of 405 game classes.` and
   `Patch fingerprint: c941c4a9…12285  (identical to the reference build)`.
4. Run the game from the patched jar exactly like the original. No extra Java flags are needed.

If your port runs Java inside a guest (proot and similar), run the patcher in the same place as the game, for example
`/usr/bin/java -jar /tmp/bugfix.jar /game/official.jar /game/pokewilds.jar`. `bugfix.jar` has to be visible to that
Java, so copy it into the shared folder first, the way you would any other file.

`apply-bugfix.sh` does steps 2 and 3 and also checks the output. It is an example, so copy the idea and don't feel
bound to it.

## Requirements

| | |
| --- | --- |
| Java | 8 or newer |
| Memory | works with a 32 MB heap (`-Xmx32m`); 64 MB is comfortable |
| Disk | about the size of the jar (roughly 150 MB) for the output, plus a temporary file of the same size next to it while it is written |
| Time | about 20 seconds on a desktop. I have not measured phones |

The patcher streams the jar, so it doesn't hold the game in memory.

The official zip's own `linux-launcher` and `mac-launcher.template` just run `java -jar pokewilds.jar`. If you patch in
place (`java -jar bugfix.jar pokewilds.jar`) the jar keeps its name, so those launchers keep working unchanged.

## What can go wrong

The exit code is `1` for wrong arguments and `2` when it refuses or fails. On `2` nothing is written, and the reason is
on the `ERROR:` line. It refuses when:
- the jar is not the official 0.8.11 (it checks two class files, see the manifest),
- the jar is already patched,
- it can't write the output (no space, read-only folder).

The input jar is never changed in two-file mode. There is also an in-place mode (`java -jar bugfix.jar pokewilds.jar`)
that keeps the original as `pokewilds-original.jar.bak`, and `--restore` to undo it. Two file names is the easier one
to script.

## Checking the result

- The **patch fingerprint** is a hash of just the patched classes, so it is identical on every machine. That is the
  number to compare.
- The SHA-256 of the whole output jar is not stable, because different Java versions compress the zip slightly
  differently. Don't pin it.
- A patched jar contains the entry `META-INF/BUGFIX.txt`.

## Using the agent instead

`java -javaagent:bugfix.jar -jar pokewilds.jar` applies the same fixes while the game loads, and changes no file. The
fixes are identical. I recommend the patched jar because add-ons that attach to a jar treat it like any other jar, and
there is no launcher flag to keep track of. The individual fixes can be switched off with `-Dbugfix.sprites=false`,
`-Dbugfix.hooh=false`, `-Dbugfix.floors=false` and `-Dbugfix.eggs=false`.

## What it fixes

1. Monsters on different floors of a building interfered with each other, because all floors shared one map of monster
   positions. Every floor now has its own map. Saving writes every monster.
2. Eggs laid on an upper floor were saved as belonging to the first floor.
3. Ride, Cut and Build monsters faced the wrong way with modded sprites.
4. Ho-Oh made every monster near it throw an error each frame.

The save format doesn't change. Details for each fix are in the repository README.

## What I have tested

I played the patched game on Windows, on a save with a 20+ floor tower, and ran automated checks against the jar from
the official `pokewilds-otherplatforms.zip` (see the README). I have also run it on ROCKNIX and it works. I have not tested Android or anything else, so please tell me what you find.

## Questions and bug reports

Open an issue on the repository, or send me a message.
