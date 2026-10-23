# Legal notes

Not legal advice. These notes record what the project does and does not contain, and the risks
the author considered before publishing.

## The rule this project follows

**No game-derived content enters this repository.**

- ULTRAKILL's code, art, audio, meshes and level data stay in the player's own install. The
  plugin reads ULTRAKILL's collision at runtime through Unity's physics queries; nothing is
  extracted or saved.
- Minecraft's textures, models and fonts are read from the player's own Minecraft (and resource
  packs) at runtime and passed to ULTRAKILL in memory. They are never written to the repository
  or shipped with the mod.
- No decompiled source of either game, no extracted assets, no game binaries.
- The repository contains original code and documentation only.

`.gitignore` keeps the local game copies (`ULTRAKILL/`, and the Minecraft instance folder
`UltraCraft/`), the runtime bridge files and build output out of git. Before each publish, check
with `git status --ignored` and `git log --all --stat`.

## What the build needs

- The ULTRAKILL plugin compiles against `Assembly-CSharp.dll`, Unity's assemblies and BepInEx's,
  read from the player's install. These are **compile-time references only** and are not
  redistributed.
- The Minecraft mod is built with Fabric Loom, which downloads Minecraft from Mojang for the
  developer as every Fabric mod build does. Minecraft 26.3 is distributed unobfuscated; the mod
  refers to its classes by those names.

## Releases

Each GitHub release carries two files, both built from this repository: `Minekill.ULTRAKILL.dll`
(the BepInEx plugin) and `minekill-minecraft-<version>.jar` (the Fabric mod). They contain only
MINEKILL's own compiled code: no game files, no assets, no code from either game. The plugin
uses ULTRAKILL's, Unity's and BepInEx's assemblies from the player's install at runtime; the
mod uses Minecraft's classes from the player's game. Release builds carry no paths from the
build machine (the plugin's debug symbols are embedded with mapped source paths).

## License

MINEKILL's own code is under the **GNU GPL v3.0** (`LICENSE`). The license covers this project's
code only. ULTRAKILL, Minecraft, BepInEx and Fabric keep their own licenses and terms.

A GPL mod that loads into proprietary games is common practice (many Minecraft mods are GPL), but
the GPL's linking terms were written with other situations in mind. If that matters for someone
redistributing the mod, an explicit exception permitting use with the games' proprietary code
could be added to the license notice later.

## Risks, largest first

1. **Distribution.** Publishing is where the risk concentrates. Only original code is published.
2. **The games' terms.** Steam's terms, the Minecraft EULA and the Minecraft Usage Guidelines set
   rules for mods. Mojang's guidelines allow mods that do not distribute Minecraft's code or
   assets and are not sold. Read them before any release.
3. **ULTRAKILL's publisher.** New Blood Interactive and Hakita are generally friendly to modding;
   check any current policy before releasing builds.
4. **Do not sell it** or put it behind a paywall without permission from both publishers.

## Not doing, deliberately

- Nothing bypasses DRM, copy protection or activation.
- Nothing touches anti-cheat. ULTRAKILL has none, and both games are used single-player only.
- No online play, no accounts touched, no network traffic.

## Prior work

The architecture follows [SkyCraft](https://github.com/chasmlol/SkyCraft) (MIT): Minecraft as a
hidden simulation engine whose world is re-meshed and drawn by the host game, with the host's
collision injected into Minecraft. SkyCraft's source was read for ideas; no SkyCraft code is
included. It is credited in the README.

## AI tooling

Most of the code and documentation was written with Anthropic's Claude, directed, reviewed and
playtested by the author. The development log (`docs/MODLOG.md`) records how.
