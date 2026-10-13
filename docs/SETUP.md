# Development setup

The machine MINEKILL is developed and tested on, so work can resume without rediscovering it.
For installing as a player, see the README.

## Host

- Arch Linux, kernel 7.2.4.
- AMD Radeon 860M (integrated). Both games run at once on it, so it is the performance ceiling.
- Wine 11.17 (not Proton).
- .NET SDK 10.0.111, Gradle 9.7.1, Python 3.
- `java` 26 on PATH, but the Minecraft instance runs **Java 25**
  (`~/.local/share/FreesmLauncher/java/java-runtime-epsilon`), which is what the mod targets.
- `ilspycmd` (dotnet global tool, `~/.dotnet/tools`) for reading ULTRAKILL's and BepInEx's
  assemblies. CFR (`~/Downloads/cfr-0.152.jar`) for reading Minecraft's classes; Minecraft 26.3
  is unobfuscated, so `javap` and CFR show real names.

## ULTRAKILL

- A copy at `ULTRAKILL/` in the project folder (3.6 GB). The Steam install is never touched.
- Unity **2022.3.29f1**, **Mono** backend; `ULTRAKILL.exe` is **PE32+ / x86-64**, so BepInEx must
  be the **win_x64** build. The wrong build fails without an error.
- BepInEx **5.4.23.2 win_x64**: `winhttp.dll`, `doorstop_config.ini`, `.doorstop_version`,
  `BepInEx/`. Plugin goes to `BepInEx/plugins/Minekill.ULTRAKILL.dll`.
- Set `[Logging.Disk] WriteUnityLog = true` in `BepInEx/config/BepInEx.cfg`, or Unity exceptions
  in plugin code are invisible.
- Wine prefix: the user's own `~/.wine` (it maps `s:` to `/home`).

## Minecraft

- **FreesmLauncher**, instance at `UltraCraft/` in the project folder (the instance folder kept
  its pre-rename name); `~/Games/Minecraft/Instances/UltraCraft` is a symlink to it.
- **Minecraft 26.3**, **Fabric Loader 0.19.5**, mixinextras 0.5.5 (bundled with the loader),
  Java 25. Native Linux: LWJGL 3.4.3, OpenGL 4.6 via Mesa 26.2.2.
- Assets as loose objects under `~/.local/share/FreesmLauncher/assets`. Downloaded skins are
  cached under `.../assets/skins/`.
- Test world: Superflat, Void preset. The jar goes to `UltraCraft/minecraft/mods/`.
- Logs: `UltraCraft/minecraft/logs/latest.log`, crash reports in `.../crash-reports/`.

## Build tools

- Fabric Loom 1.18.3. No Fabric API dependency. The `mappings` line in `build.gradle` names an
  empty intermediary set on purpose (26.3 ships unobfuscated; see the comments there).
- The ULTRAKILL project reads game DLLs from `../ULTRAKILL` by default
  (`-p:GameDir=...` to override).

## Runtime files

- `/var/tmp/minekill.bridge` (Minecraft) = `Z:\var\tmp\minekill.bridge` (ULTRAKILL under Wine).
- `/var/tmp/minekill.stream` next to it.
- `/var/tmp/ultrakill-status.txt`: ULTRAKILL's live state, rewritten periodically. More reliable
  than BepInEx's log, which can stop flushing under Wine.
- `MINEKILL_BRIDGE` overrides the bridge path on either side (absolute, same file on both).
