# Setup on this machine

Recorded 2026-10-08 so a future session does not have to rediscover it.

## Host

- Arch Linux, kernel 7.2.4
- GPU: AMD Radeon 860M (integrated). This is the performance ceiling: ULTRAKILL and
  Minecraft both run at once.
- `wine` 11.17. No Proton in use; a `proton-10.0-1.tar.xz` sits unused in `~/Downloads`.
- `dotnet` SDK 10.0.111. `java` 26 on PATH, but the launcher instance pins **Java 25**
  (`~/.local/share/FreesmLauncher/java/java-runtime-epsilon`), which is what the mod targets.
- `gradle` 9.7.1.
- `ilspycmd` installed as a dotnet global tool (`~/.dotnet/tools`), used only for reading
  BepInEx's and ULTRAKILL's assemblies during diagnosis.

## ULTRAKILL

- Copy at `ULTRAKILL/` (3.6 GB). The Steam install is never touched.
- Unity **2022.3.29f1**, **Mono** backend, build dated 2026-02-25.
- `ULTRAKILL.exe` is **PE32+ / x86-64**, so BepInEx must be **win_x64**, not the x86 build.
  Getting this wrong is easy and produces no error.
- The exact ULTRAKILL version string is not exposed in the binary; read it from
  `GameManager` at runtime when convenient.
- BepInEx **5.4.23.2 win_x64** installed: `winhttp.dll`, `doorstop_config.ini`,
  `.doorstop_version`, `BepInEx/`.
- Wine prefix for play: the user's own `~/.wine` (it maps `s:` to `/home`). Early diagnosis
  used throwaway prefixes and a hardlink copy under `/tmp/opencode/`; those are not part of the
  project and may no longer exist.

## Minecraft

- Managed by **FreesmLauncher**, instance name `UltraCraft`.
- Instance at `UltraCraft/` (canonical), launched from FreesmLauncher.
  `~/Games/Minecraft/Instances/UltraCraft` is a symlink to it.
- **Minecraft 26.3**, **Fabric Loader 0.19.5**, mixinextras 0.5.5, Java 25.
- Runs natively on Linux: LWJGL 3.4.3, OpenGL 4.6 via Mesa 26.2.2.
- Real assets are present as loose objects under
  `~/.local/share/FreesmLauncher/assets` (12,464 files, 3.0 GB). Read at runtime, never
  copied into the project.
- `mods/` is where the built jar goes.

## Versions that matter

- BepInEx 5.4.23.2 — must match the plugin's reference, or it is rejected silently.
- Fabric Loader 0.19.5 — the instance's version.
- Fabric Loom 1.18.3, Fabric API 0.162.0+26.3 (API not needed yet, so not depended on).

## Paths used by the bridge

No configuration is needed. Both sides default to the same file:

- Minecraft (native): `/var/tmp/ultracraft.bridge`
- ULTRAKILL (Wine): `Z:\var\tmp\ultracraft.bridge`

Wine maps `Z:` to `/`, so those are the same file. ULTRAKILL also writes its live state to
`/var/tmp/ultrakill-status.txt`, which is more reliable than BepInEx's log under Wine.

`ULTRACRAFT_BRIDGE` overrides the path on either side, for unusual setups only. If set, it
must name the same file on both sides (Linux form for Minecraft, `Z:` form for ULTRAKILL) and
must be absolute.

## Disk

66 GB free when this was written. The ULTRAKILL copy is 3.6 GB; a hardlink copy costs almost
nothing, a full copy 3.6 GB.