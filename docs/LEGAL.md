# Legal notes

Not legal advice. I am not a lawyer. These notes exist so the risks are written down before
anything gets published, not instead of asking one.

## The rule this project follows

**No game-derived content ever enters this repository.**

- ULTRAKILL's code, art, audio, meshes and level data stay in the player's own install and are
  read at runtime if ever needed.
- Minecraft's textures are read from the launcher's asset store at runtime. They are never
  copied into the project.
- No decompiled ULTRAKILL source, no extracted meshes, no game binaries.
- The repository contains only code I wrote and this documentation.

`.gitignore` enforces the first two points by excluding `ULTRAKILL/`, `UltraCraft/` and
`*.bridge`. Check with `git log --all --stat` before publishing.

## What the project does need

A ULTRAKILL mod has to talk to ULTRAKILL's own classes, so it references
`Assembly-CSharp.dll`. That reference is **build-time only**: the DLL is read from the player's
install while compiling and is not redistributed. The same is true of BepInEx, which is
downloaded by the installer.

Voxelizing ULTRAKILL levels into Minecraft blocks, which is planned but not yet built, would
read level geometry from the player's install at runtime. That produces derivative works in the
player's own memory; it does not distribute anything. Whether it is lawful depends on
jurisdiction and is not settled by this document.

## Risks, largest first

1. **Publishing.** Distribution is where the risk concentrates. Private, local modding is
   lower risk than public distribution. Ship only original code.
2. **The game's terms.** Steam's terms of service and most game EULAs restrict reverse
   engineering and modification. Using a mod can breach them, and the realistic consequence is
   usually an account restriction rather than a lawsuit. Worth reading before publishing.
3. **ULTRAKILL's fan-content policy.** Hackita may have rules about derivative content.
   Check before uploading anything.
4. **Do not sell it.** Do not monetize without explicit permission from the publisher.

## Not doing, deliberately

- Nothing here bypasses DRM, copy protection or activation.
- Nothing here touches anti-cheat. ULTRAKILL has none and this mod is single-player only.
- Nothing here circumvents any technical protection measure.
- Nothing here is derived from another mod project's code. SkyCraft and LibertyCraft were
  read for architectural ideas only; both are credited where relevant, and neither was copied.

## Before publishing

1. Confirm no game files are staged: `git status --ignored` and `git log --all --stat`.
2. Credit upstream projects and keep their licences.
3. Record which exact game versions were tested, and which loader versions.
4. State that AI tooling was used and what it did.
5. Re-read the publisher's modding or fan-content policy.

Nothing has been published. The project is private.