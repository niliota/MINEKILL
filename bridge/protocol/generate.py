#!/usr/bin/env python3
"""
UltraCraft bridge protocol generator.

Single source of truth for the memory layout shared by the BepInEx plugin (C#)
and the Fabric mod (Java). Hand-written structs in two languages drift apart the
first time someone adds a field, so both sides are generated from this file.

Run:  python3 bridge/protocol/generate.py
"""

import os
import textwrap

# ---------------------------------------------------------------------------
# Type table.  ctype = C# type, jtype = Java type, size = bytes on the wire.
# ---------------------------------------------------------------------------
TYPES = {
    "u8":  ("byte",   "byte",     1),
    "i8":  ("sbyte",  "byte",     1),
    "u16": ("ushort", "short",    2),   # Java has no unsigned short: read with & 0xFFFF
    "u32": ("uint",   "int",      4),
    "i32": ("int",    "int",      4),
    "f32": ("float",  "float",    4),
    "u64": ("ulong",  "long",     8),
    "i64": ("long",   "long",     8),
    # bool is a single byte on the wire in BOTH languages. C#'s `bool` is 4 bytes and
    # non-blittable, so the C# side uses `byte` (0 = false) instead. Mixing them would
    # silently shift every field after the first boolean.
    "bool":("byte",   "boolean",  1),
}

ALIGN = 8   # every slot starts on an 8-byte boundary


def pad(n):
    """Round n up to the alignment."""
    return (n + ALIGN - 1) // ALIGN * ALIGN


# ---------------------------------------------------------------------------
# The layout.
# ---------------------------------------------------------------------------
# (field_name, type, comment)
HEADER = [
    ("magic",          "u32",  "always 0x5554_4C52 ('ULTR')"),
    ("version",        "u32",  "protocol version; both sides refuse to read a mismatch"),
    ("headerSize",     "u32",  "bytes in this header, so the body can move later"),
    ("structSize",     "u32",  "bytes per slot record, so new fields can be appended"),
    ("mcPid",          "i32",  "Minecraft process id, 0 when not running"),
    ("ukPid",          "i32",  "ULTRAKILL process id, 0 when not running"),
    ("mcHeartbeat",    "i64",  "EPOCH ms, advanced every 100ms by Minecraft"),
    ("ukHeartbeat",    "i64",  "EPOCH ms, advanced every 100ms by ULTRAKILL"),
    ("mcDroppedEvents","u32",  "events Minecraft could not send; non-zero means we are overloaded"),
    ("ukDroppedEvents","u32",  "events ULTRAKILL could not send"),
    ("ukSpawnRequest", "u32",  "ULTRAKILL bumps this on every level load; Minecraft moves its player to the spawn spot and echoes it in PLAYER.spawnAck"),
    ("ukFlags",        "u32",  "bit 0 = ULTRAKILL movement enabled, bit 1 = ULTRAKILL paused"),
]

# Each slot is a seqlock record: seq, then payload, then seq again.
SLOTS = [
    ("SlotPlayer", "PLAYER", "Minecraft -> ULTRAKILL", "player position, velocity and state", [
        ("tickTimestamp","i64", "epoch ms of the tick that produced this"),
        ("posX","f32", "Minecraft block coords"),
        ("posY","f32", ""),
        ("posZ","f32", ""),
        ("velX","f32", "blocks per tick"),
        ("velY","f32", ""),
        ("velZ","f32", ""),
        ("yaw","f32", "Minecraft degrees"),
        ("pitch","f32", "Minecraft degrees"),
        ("onGround","bool", "false while airborne"),
        ("sprinting","bool", ""),
        ("crouching","bool", ""),
        ("sneaking","bool", ""),
        ("inWater","bool", ""),
        ("onLadder","bool", ""),
        ("hasBlockHit","bool", "ray hit a block, for the debug overlay"),
        ("health","f32", "Minecraft half-hearts, 20 = full"),
        ("food","f32", "Minecraft food points, 20 = full"),
        ("air","f32", "air supply, 300 = full"),
        ("spawnAck","u32", "last header ukSpawnRequest Minecraft has completed: player is at the spawn spot and held"),
    ]),
    ("SlotCamera", "CAMERA", "Minecraft -> ULTRAKILL", "what the player is aiming with", [
        ("yaw","f32", "Minecraft degrees"),
        ("pitch","f32", "Minecraft degrees"),
        ("fov","f32", "Minecraft FOV, for the debug overlay only"),
        ("eyeHeight","f32", "blocks from the feet to the eye, current pose (1.62 standing, lower sneaking)"),
        ("cameraType","u32", "F5 perspective: 0 first person, 1 third person behind, 2 third person in front"),
        ("cameraDistance","f32", "third person: blocks from the eye to Minecraft's camera (already clipped by Minecraft's blocks)"),
    ]),
    ("SlotInput", "INPUT", "ULTRAKILL -> Minecraft",
     "held keys and mouse delta, forwarded from the focused ULTRAKILL window", [
        ("tickTimestamp","i64", "epoch ms when ULTRAKILL sampled the keyboard"),
        ("keys","u32", "KEY_* bit flags below"),
        ("mouseDx","f32", "mouse delta in pixels since the previous sample"),
        ("mouseDy","f32", "mouse delta in pixels since the previous sample"),
        ("attack","bool", "held, left mouse button"),
        ("use","bool", "held, right mouse button"),
        ("inventory","bool", "edge-triggered: true for one sample when pressed"),
        ("hotbarSeq","u8", "bumps on every hotbar key press (1-9) in ULTRAKILL; Minecraft acts when it changes"),
        ("hotbarSlot","u8", "slot 0-8 of the latest hotbar key press"),
        ("scrollTotal","f32", "running sum of mouse-wheel steps; Minecraft applies the difference"),
        ("screenWidth","u16", "ULTRAKILL's screen in pixels: Minecraft lays its GUI out for this size"),
        ("screenHeight","u16", ""),
        ("cursorX","f32", "ULTRAKILL's cursor in Minecraft GUI pixels, while a Minecraft screen is open"),
        ("cursorY","f32", ""),
        ("cursorActive","u8", "1 while cursorX/Y drive Minecraft's cursor"),
    ]),
    ("SlotCorrection", "CORRECTION", "ULTRAKILL -> Minecraft",
     "ULTRAKILL's collision pushed the player; Minecraft must accept it", [
        ("tickTimestamp","i64", "epoch ms when ULTRAKILL resolved the collision"),
        ("posX","f32", "corrected position in Minecraft block coords"),
        ("posY","f32", ""),
        ("posZ","f32", ""),
        ("pushX","f32", "how far the push moved us, for diagnostics"),
        ("pushY","f32", ""),
        ("pushZ","f32", ""),
        ("scale","f32", "ULTRAKILL units per Minecraft block that produced this correction"),
    ]),
    ("SlotLevel", "LEVEL", "ULTRAKILL -> Minecraft", "which level is loaded and how to map it", [
        ("levelId","u32", "hash of the level name"),
        ("posOffsetX","f32", "ULTRAKILL origin -> Minecraft origin, in Minecraft blocks"),
        ("posOffsetY","f32", ""),
        ("posOffsetZ","f32", ""),
        ("scale","f32", "ULTRAKILL units per Minecraft block; measured, not guessed"),
        ("yawOffset","f32", "constant added after the handedness sign flip"),
        ("pitchScale","f32", "usually 1.0"),
        ("boundsMinX","f32", "voxelized region, in ULTRAKILL units"),
        ("boundsMinY","f32", ""),
        ("boundsMinZ","f32", ""),
        ("boundsMaxX","f32", ""),
        ("boundsMaxY","f32", ""),
        ("boundsMaxZ","f32", ""),
    ]),
    ("SlotSelection", "SELECTION", "Minecraft -> ULTRAKILL",
     "what the crosshair targets, for ULTRAKILL to draw Minecraft's outline", [
        ("kind","u32", "0 nothing, 1 a Minecraft block (its outline), 2 where a held block would go on ULTRAKILL geometry"),
        ("minX","f32", "Minecraft block coords"),
        ("minY","f32", ""),
        ("minZ","f32", ""),
        ("maxX","f32", ""),
        ("maxY","f32", ""),
        ("maxZ","f32", ""),
    ]),
]

# Ring buffers carry one-shot events. Variable-length data goes in as a count
# then fixed-size records.
RINGS = [
    ("RingMcEvents", "MC_EVENTS", "Minecraft -> ULTRAKILL", 256, [
        ("kind","u32", "event type"),
        ("a","i32", "meaning depends on kind"),
        ("b","i32", ""),
        ("c","f32", ""),
        ("d","f32", ""),
    ]),
    ("RingUkEvents", "UK_EVENTS", "ULTRAKILL -> Minecraft", 256, [
        ("kind","u32", "event type"),
        ("a","i32", "meaning depends on kind"),
        ("b","i32", ""),
        ("c","f32", ""),
        ("d","f32", ""),
    ]),
]


# Tables carry a variable-length list under ONE seqlock: u32 seq, a fixed header,
# then up to `capacity` fixed-size records. The reader copies header + `count`
# records and re-checks seq, so it never sees half of one list and half of the next.
# Tables are appended AFTER the rings, so adding one never moves an existing offset
# and an older build on the other side simply never looks at it.
TABLES = [
    ("COLLISION", "ULTRAKILL -> Minecraft",
     "ULTRAKILL collision boxes near the player, already in Minecraft block coords", 4096, [
        ("tickTimestamp","i64", "epoch ms when ULTRAKILL sampled the colliders"),
        ("generation","u32", "bumps on every re-anchor; boxes from an older mapping are discarded"),
        ("count","u32", "valid records, <= capacity"),
     ], [
        ("minX","f32", "Minecraft block coords"),
        ("minY","f32", ""),
        ("minZ","f32", ""),
        ("maxX","f32", ""),
        ("maxY","f32", ""),
        ("maxZ","f32", ""),
     ]),
    ("GUI", "Minecraft -> ULTRAKILL",
     "every quad Minecraft's GUI draws this frame (HUD, text, items), in draw order, for ULTRAKILL to replay", 32768, [
        ("tickTimestamp","i64", "epoch ms of the frame"),
        ("count","u32", "valid vertices; 4 per quad"),
        ("guiScale","u32", "Minecraft's GUI scale for ULTRAKILL's screen"),
        ("guiWidth","u32", "screen size in GUI pixels"),
        ("guiHeight","u32", ""),
        ("screenOpen","u32", "1 while a Minecraft screen is open (inventory, chat, pause): mouse and keys go to it"),
        ("pauseScreen","u32", "1 while that screen pauses Minecraft's game (ULTRAKILL freezes too)"),
     ], [
        ("x","f32", "GUI pixels, origin top-left"),
        ("y","f32", ""),
        ("u","f32", "texture coordinates"),
        ("v","f32", ""),
        ("rgba","u32", "colour, r in the low byte"),
        ("tex","u32", "GUI_TEX_* key"),
     ]),
    ("HAND", "Minecraft -> ULTRAKILL",
     "Minecraft's first-person hands and held items this frame: posed, lit quads in Minecraft view space (camera looks down -z, y up), far first", 8192, [
        ("tickTimestamp","i64", "epoch ms of the frame"),
        ("count","u32", "valid vertices; 4 per quad"),
        ("fov","f32", "Minecraft's hand field of view, vertical degrees (it draws hands with their own projection)"),
     ], [
        ("x","f32", "Minecraft view space, blocks"),
        ("y","f32", ""),
        ("z","f32", ""),
        ("u","f32", "texture coordinates"),
        ("v","f32", ""),
        ("rgba","u32", "colour with lighting applied, r in the low byte"),
        ("tex","u32", "GUI_TEX_* key"),
     ]),
    ("WORLD_MODELS", "Minecraft -> ULTRAKILL",
     "posed model geometry in the world this frame (the player in third person, block-breaking cracks), unlit, in Minecraft block coords", 16384, [
        ("tickTimestamp","i64", "epoch ms of the frame"),
        ("count","u32", "valid vertices; 4 per quad, counter-clockwise front as in Minecraft"),
     ], [
        ("x","f32", "Minecraft block coords"),
        ("y","f32", ""),
        ("z","f32", ""),
        ("u","f32", "texture coordinates"),
        ("v","f32", ""),
        ("rgba","u32", "tint, r in the low byte"),
        ("tex","u32", "GUI_TEX_* key"),
     ]),
    ("PARTICLES", "Minecraft -> ULTRAKILL",
     "Minecraft's live block particles (TerrainParticle), interpolated, for ULTRAKILL to draw as billboards", 2048, [
        ("tickTimestamp","i64", "epoch ms of the frame that sampled them"),
        ("count","u32", "valid records, <= capacity"),
     ], [
        ("x","f32", "Minecraft block coords, particle centre"),
        ("y","f32", ""),
        ("z","f32", ""),
        ("size","f32", "half-size of the quad, blocks"),
        ("u0","f32", "block-atlas UV rectangle"),
        ("v0","f32", ""),
        ("u1","f32", ""),
        ("v1","f32", ""),
        ("rgba","u32", "colour, r in the low byte"),
     ]),
]


# The STREAM file: a second mapped file for bulk, variable-length data Minecraft sends
# to ULTRAKILL (texture atlas, block meshes). The bridge's fixed slots cannot carry tens of
# megabytes, so this is a single-producer single-consumer byte ring:
#   Minecraft writes records and then publishes HEAD (total bytes written, u64).
#   ULTRAKILL reads up to HEAD and then publishes TAIL (total bytes consumed, u64).
# Free space is RING_SIZE - (HEAD - TAIL). A record never wraps: if it does not fit before
# the end, the writer puts a PAD record there and starts again at ring offset 0. Records are
# 8-byte aligned: u32 type, u32 payload length, payload, padding.
# Resync: ULTRAKILL sets TAIL = HEAD (drops anything pending) and bumps UK_RESYNC_REQUEST;
# Minecraft answers with CLEAR_ALL, the atlas and every section again, then records the
# request it served in MC_RESYNC_SERVED.
STREAM_RING_SIZE = 64 * 1024 * 1024
STREAM = [
    ("STREAM_MAGIC", 0x5453_4C55, "'ULST'"),
    ("STREAM_VERSION", 1, ""),
    ("STREAM_HEADER_SIZE", 64, ""),
    ("STREAM_RING_OFFSET", 64, "ring starts here"),
    ("STREAM_RING_SIZE", STREAM_RING_SIZE, ""),
    ("STREAM_TOTAL_SIZE", 64 + STREAM_RING_SIZE, "bytes in the mapped file"),
    ("STREAM_OFF_MAGIC", 0, "u32"),
    ("STREAM_OFF_VERSION", 4, "u32"),
    ("STREAM_OFF_UK_RESYNC_REQUEST", 8, "u32, written by ULTRAKILL"),
    ("STREAM_OFF_MC_RESYNC_SERVED", 12, "u32, written by Minecraft"),
    ("STREAM_OFF_HEAD", 16, "u64, total bytes written, Minecraft"),
    ("STREAM_OFF_TAIL", 24, "u64, total bytes consumed, ULTRAKILL"),
    ("STREAM_RECORD_HEADER", 8, "u32 type, u32 payload length"),
    ("STREAM_MSG_PAD", 0, "skip to ring offset 0"),
    ("STREAM_MSG_CLEAR_ALL", 1, "drop every section and the atlas"),
    ("STREAM_MSG_ATLAS", 2, "u32 width, u32 height, RGBA8 rows top to bottom"),
    ("STREAM_MSG_SECTION", 3, "i32 sx, sy, sz, u32 vertexCount, vertices (0 = remove section)"),
    ("STREAM_MSG_EXTRA_ATLAS", 4, "u32 atlasId (1 items, 2 gui), u32 width, u32 height, RGBA8 rows top to bottom"),
    ("STREAM_MSG_GUI_SPRITES", 5, "u32 count, then per sprite: u16 nameLength, name (ASCII), f32 u0,v0,u1,v1, i32 width,height (pixels)"),
    ("STREAM_MSG_TEXTURE", 6, "u32 key, u32 width, u32 height, u32 x, u32 y, u32 patchWidth, u32 patchHeight, RGBA8 patch: create texture `key` if new, then write the patch"),
    ("GUI_TEX_NONE", 0, "GUI vertex texture keys: untextured (white)"),
    ("GUI_TEX_GUI", 1, "Minecraft's GUI atlas"),
    ("GUI_TEX_BLOCKS", 2, "block atlas"),
    ("GUI_TEX_ITEMS", 3, "item atlas"),
    ("MODEL_TEX_MULTIPLY", 1 << 24, "WORLD_MODELS tex flag: multiply-blend decal (block-breaking cracks: Minecraft blends them as dst * src * 2)"),
    ("GUI_TEX_DYNAMIC_BASE", 16, "first key of textures mirrored through STREAM_MSG_TEXTURE (font sheets)"),
    ("STREAM_ATLAS_ITEMS", 1, ""),
    ("STREAM_ATLAS_GUI", 2, ""),
    ("STREAM_SECTION_HEADER", 16, ""),
    ("STREAM_VERTEX_SIZE", 28, "triangle list, 3 vertices per triangle"),
    ("STREAM_VTX_OFF_POS", 0, "f32 x3, block units relative to the section origin"),
    ("STREAM_VTX_OFF_UV", 12, "f32 x2, atlas UV, v down"),
    ("STREAM_VTX_OFF_COLOR", 20, "u8 r,g,b,a: Minecraft tint x face shade x ambient occlusion"),
    ("STREAM_VTX_OFF_FLAGS", 24, "u32: bits 0-3 block light, 8-11 sky light, 16 translucent, 17 cutout (neither = solid)"),
]


def struct_size(fields):
    """Seqlock record: u32 seq, payload, u32 seqEnd.

    Fields are CONTIGUOUS with no interior padding, which is what both
    `[StructLayout(Pack = 1)]` on the C# side and the generated Java offset
    constants actually produce. Do not pad the payload here: padding it would
    make the generator's offsets disagree with both real layouts, and every
    field after the gap would be read at the wrong place. Alignment is applied
    to where a record STARTS, not to its size.
    """
    payload = sum(TYPES[t][2] for _, t, _ in fields)
    return 4 + payload + 4, payload


def compute_layout():
    header_size = pad(sum(TYPES[t][2] for _, t, _ in HEADER))
    offset = header_size
    slots = []
    for cls, name, direction, doc, fields in SLOTS:
        total, payload = struct_size(fields)
        offset = pad(offset)          # records start 8-byte aligned
        slots.append(dict(cls=cls, name=name, direction=direction, doc=doc,
                          fields=fields, offset=offset, total=total, payload=payload))
        offset += total
    rings = []
    for cls, name, direction, capacity, fields in RINGS:
        rec = pad(sum(TYPES[t][2] for _, t, _ in fields))
        total = 4 + 4 + capacity * rec          # head u32, tail u32, records
        total = pad(total)
        rings.append(dict(cls=cls, name=name, direction=direction, doc=f"{capacity} records",
                          fields=fields, capacity=capacity, recsize=rec,
                          offset=offset, total=total))
        offset += total
    tables = []
    for name, direction, doc, capacity, hfields, rfields in TABLES:
        offset = pad(offset)
        hoffs, o = [], 4                        # after the seq word
        for n, t, c in hfields:
            hoffs.append((n, t, c, o)); o += TYPES[t][2]
        rec_off = pad(o)
        roffs, ro = [], 0
        for n, t, c in rfields:
            roffs.append((n, t, c, ro)); ro += TYPES[t][2]
        total = pad(rec_off + capacity * ro)
        tables.append(dict(name=name, direction=direction, doc=doc, capacity=capacity,
                           hfields=hoffs, rfields=roffs, rec_off=rec_off, recsize=ro,
                           offset=offset, total=total))
        offset += total
    total_size = pad(offset)
    return header_size, slots, rings, tables, total_size


def table_consts(tables):
    """(name, value, comment) for every table constant. Identical names on both sides,
    so check-layout.sh can compare them one for one."""
    out = []
    for t in tables:
        p = f"TBL_{t['name']}"
        out.append((f"{p}_OFFSET", t["offset"], f"{t['direction']}: {t['doc']}"))
        out.append((f"{p}_SIZE", t["total"], ""))
        out.append((f"{p}_CAPACITY", t["capacity"], ""))
        out.append((f"{p}_OFF_SEQ", 0, "seqlock, odd while writing"))
        for n, _, c, o in t["hfields"]:
            out.append((f"{p}_OFF_{n.upper()}", o, c))
        out.append((f"{p}_OFF_RECORDS", t["rec_off"], ""))
        out.append((f"{p}_RECORD_SIZE", t["recsize"], ""))
        for n, _, c, o in t["rfields"]:
            out.append((f"{p}_REC_OFF_{n.upper()}", o, c))
    return out


def emit_csharp(header_size, slots, rings, tables, total_size, path):
    L = []
    w = L.append
    w("// <auto-generated>  Generated by bridge/protocol/generate.py. Do not edit by hand.")
    w("// Source of truth: bridge/protocol/generate.py </auto-generated>")
    w("using System.Runtime.InteropServices;")
    w("")
    w("namespace UltraCraft.Bridge")
    w("{")
    w("    public static class Protocol")
    w("    {")
    w('        public const uint MAGIC = 0x55544C52u;')
    w(f"        public const uint VERSION = {VERSION_VALUE}u;   // layout fingerprint")
    w(f"        public const int HEADER_SIZE = {header_size};")
    w(f"        public const int TOTAL_SIZE = {total_size};")
    w("        public const int SEQ_SIZE = 4;")
    w("")
    # header struct
    w("        [StructLayout(LayoutKind.Sequential, Pack = 1)]")
    w("        public struct Header")
    w("        {")
    for n, t, c in HEADER:
        w(f"            public {TYPES[t][0]} {n};{(chr(32) * max(1, 24 - len(n) - len(TYPES[t][0])))}// {c}")
    w("        }")
    w("")
    w(f"        public const int HEADER_OFFSET = 0;")
    for s in slots:
        w(f"        public const int OFFSET_{s['name']} = {s['offset']};")
        w(f"        public const int {s['cls'].upper()}_SIZE = {s['total']};")
    for r in rings:
        w(f"        public const int OFFSET_{r['name']} = {r['offset']};")
    w("")
    w("        public const uint FLAG_UK_MOVEMENT = 1u << 0;")
    w("        public const uint FLAG_UK_PAUSED = 1u << 1;")
    w("        // ULTRAKILL's window currently has OS focus.")
    w("        public const uint FLAG_UK_FOCUSED = 1u << 2;")
    w("")
    for s in slots:
        w("        [StructLayout(LayoutKind.Sequential, Pack = 1)]")
        w(f"        public struct {s['cls']}")
        w("        {")
        w(f"            public uint seq;{' ' * max(1, 22 - len(s['cls']))}// seqlock, odd while writing")
        for n, t, c in s["fields"]:
            w(f"            public {TYPES[t][0]} {n};{' ' * max(1, 22 - len(n) - len(TYPES[t][0]))}// {c}")
        w(f"            public uint seqEnd;{' ' * max(1, 22 - len(s['cls']) - 7)}// must equal seq")
        w("        }")
        w("")
    for r in rings:
        w("        [StructLayout(LayoutKind.Sequential, Pack = 1)]")
        w(f"        public struct {r['cls']}Record")
        w("        {")
        for n, t, c in r["fields"]:
            w(f"            public {TYPES[t][0]} {n};{' ' * max(1, 22 - len(n) - len(TYPES[t][0]))}// {c}")
        w("        }")
        w("")
        w("        [StructLayout(LayoutKind.Sequential, Pack = 1)]")
        w(f"        public struct {r['cls']}")
        w("        {")
        w("            public uint head;")
        w("            public uint tail;")
        w(f"            public {r['cls']}Record[] records;{' ' * 6}// {r['capacity']} entries")
        w("        }")
        w("")
        w(f"        public const int {r['cls'].upper()}_OFFSET = {r['offset']};")
        w(f"        public const int {r['cls'].upper()}_CAPACITY = {r['capacity']};")
        w(f"        public const int {r['cls'].upper()}_RECORD_SIZE = {r['recsize']};")
        w(f"        public const int {r['cls'].upper()}_OFF_HEAD = 0;")
        w(f"        public const int {r['cls'].upper()}_OFF_TAIL = 4;")
        w(f"        public const int {r['cls'].upper()}_OFF_RECORDS = 8;")
        w("")
    w("        // STREAM file (bulk Minecraft -> ULTRAKILL data). See generate.py.")
    for n, v, c in STREAM:
        w(f"        public const int {n} = {v};" + (f"  // {c}" if c else ""))
    w("")
    if tables:
        w("        // Tables: one seqlock over a header and a list of fixed-size records.")
    for n, v, c in table_consts(tables):
        w(f"        public const int {n} = {v};" + (f"  // {c}" if c else ""))
    if tables:
        w("")
    # event kinds
    w("        // Key bit flags carried by SLOT_INPUT, shared with the Java side.")
    for k, v in KEY_FLAGS.items():
        w(f"        public const uint {k} = {v}u;")
    w("")
    w("        // Event kinds, shared with the Java side.")
    w("        public enum EventKind : uint")
    w("        {")
    for kind in EVENT_KINDS:
        w(f"            {kind} = {EVENT_KINDS[kind]},")
    w("        }")
    w("    }")
    w("}")
    open(path, "w").write("\n".join(L) + "\n")


KEY_FLAGS = {
    "KEY_FORWARD":  1 << 0,
    "KEY_BACK":     1 << 1,
    "KEY_LEFT":     1 << 2,
    "KEY_RIGHT":    1 << 3,
    "KEY_JUMP":     1 << 4,
    "KEY_SNEAK":    1 << 5,
    "KEY_SPRINT":   1 << 6,
    # 1 << 7 was the H lock toggle (removed: spawn sync and the void net made it unnecessary).
}

EVENT_KINDS = {
    "BLOCK_PLACED": 1,
    "BLOCK_BROKEN": 2,
    "ENTITY_ATTACKED": 3,
    "PLAYER_HURT": 4,
    "LEVEL_LOADED": 5,
    "PLAYER_RESPAWN": 6,
    # Keyboard passthrough (UK_EVENTS ring). Minecraft 26.x input is SDL3 (not GLFW): a KeyEvent
    # carries the SDL scancode (key bindings, isEscape: InputConstants.KEY_ESCAPE = 41) AND the SDL
    # keycode (text editing: EditBox switches on shortcutKey(), e.g. 8 = Backspace).
    # a = SDL keycode; b = action (0 release, 1 press, 2 repeat) | SDL modifier bits << 8
    # (lshift 0x1, rshift 0x2, lctrl 0x40, rctrl 0x80, lalt 0x100, ralt 0x200) | scancode << 22.
    "KEY": 7,
    # Typed character (UK_EVENTS ring): a = Unicode code point.
    "CHAR": 8,
}


def emit_java(header_size, slots, rings, tables, total_size, path):
    L = []
    w = L.append
    w("// <auto-generated>  Generated by bridge/protocol/generate.py. Do not edit by hand.")
    w("// Source of truth: bridge/protocol/generate.py </auto-generated>")
    w("package dev.ultracraft.bridge;")
    w("")
    w("public final class Protocol {")
    w("    public static final int MAGIC = 0x55544C52;")
    w(f"    public static final int VERSION = {VERSION_VALUE};   // layout fingerprint")
    w(f"    public static final int HEADER_SIZE = {header_size};")
    w(f"    public static final int TOTAL_SIZE = {total_size};")
    w("    public static final int SEQ_SIZE = 4;")
    w("")
    w("    public static final int FLAG_UK_MOVEMENT = 1;")
    w("    public static final int FLAG_UK_PAUSED = 1 << 1;")
    w("    /** ULTRAKILL's window currently has OS focus. */")
    w("    public static final int FLAG_UK_FOCUSED = 1 << 2;")
    w("")
    w("    public static final int HEADER_OFFSET = 0;")
    for s in slots:
        w(f"    public static final int OFFSET_{s['name']} = {s['offset']};")
        w(f"    public static final int {s['cls'].upper()}_SIZE = {s['total']};")
    for r in rings:
        w(f"    public static final int OFFSET_{r['name']} = {r['offset']};")
        w(f"    public static final int {r['cls'].upper()}_OFFSET = {r['offset']};")
        w(f"    public static final int {r['cls'].upper()}_CAPACITY = {r['capacity']};")
        w(f"    public static final int {r['cls'].upper()}_RECORD_SIZE = {r['recsize']};")
        w(f"    public static final int {r['cls'].upper()}_OFF_HEAD = 0;")
        w(f"    public static final int {r['cls'].upper()}_OFF_TAIL = 4;")
        w(f"    public static final int {r['cls'].upper()}_OFF_RECORDS = 8;")
    w("")
    for s in slots:
        w(f"    public static final class {s['cls']} {{")
        off = 0
        w(f"        public static final int OFF_SEQ = 0;")
        off = 4
        for n, t, c in s["fields"]:
            w(f"        public static final int OFF_{n.upper()} = {off};{' ' * max(1, 8 - len(n))}// {c}")
            off += TYPES[t][2]
        w(f"        public static final int OFF_SEQ_END = {off};")
        w(f"        public static final int SIZE = {s['total']};")
        w("")
        for n, t, c in s["fields"]:
            w(f"        public {TYPES[t][1]} {n};")
        w("    }")
        w("")
    for r in rings:
        w(f"    public static final class {r['cls']} {{")
        w(f"        public static final int OFF_HEAD = 0;")
        w("        public static final int OFF_TAIL = 4;")
        w(f"        public static final int OFF_RECORDS = 8;")
        w(f"        public static final int RECORD_SIZE = {r['recsize']};")
        w(f"        public static final int CAPACITY = {r['capacity']};")
        w(f"        public static final int SIZE = {r['total']};")
        w("")
        w(f"        public static final class Rec {{")
        off = 0
        for n, t, c in r["fields"]:
            w(f"            public static final int OFF_{n.upper()} = {off};{' ' * max(1, 10 - len(n))}// {c}")
            off += TYPES[t][2]
        w(f"            public static final int SIZE = {r['recsize']};")
        w("")
        for n, t, c in r["fields"]:
            w(f"            public {TYPES[t][1]} {n};")
        w("        }")
        w("    }")
        w("")
    w("    // STREAM file (bulk Minecraft -> ULTRAKILL data). See generate.py.")
    for n, v, c in STREAM:
        w(f"    public static final int {n} = {v};" + (f"  // {c}" if c else ""))
    w("")
    if tables:
        w("    // Tables: one seqlock over a header and a list of fixed-size records.")
    for n, v, c in table_consts(tables):
        w(f"    public static final int {n} = {v};" + (f"  // {c}" if c else ""))
    if tables:
        w("")
    w("    // Key bit flags carried by SLOT_INPUT, shared with the C# side.")
    for k, v in KEY_FLAGS.items():
        w(f"    public static final int {k} = {v};")
    w("")
    w("    // Event kinds, shared with the C# side.")
    for kind, value in EVENT_KINDS.items():
        w(f"    public static final int EV_{kind} = {value};")
    w("}")
    open(path, "w").write("\n".join(L) + "\n")


def emit_doc(header_size, slots, rings, tables, total_size, path):
    L = []
    w = L.writer if hasattr(L, "writer") else L.append
    L2 = []
    a = L2.append
    a("# UltraCraft bridge protocol, version 1")
    a("")
    a("Generated from `bridge/protocol/generate.py`. Do not edit this file by hand.")
    a("")
    a(f"Total mapped file size: **{total_size} bytes**. All fields little-endian, all")
    a("records 8-byte aligned. `bool` is one byte on the wire in both languages.")
    a("")
    a("## Header")
    a("")
    a("| offset | field | type | notes |")
    a("|---|---|---|---|")
    off = 0
    for n, t, c in HEADER:
        a(f"| {off} | `{n}` | {t} | {c} |")
        off += TYPES[t][2]
    a("")
    a("## Slots (seqlock)")
    a("")
    a("Each slot is `uint seq`, payload, `uint seqEnd`. The writer sets `seq` to an odd")
    a("number before writing and back to the matching even number when done. A reader that")
    a("sees different values, or an odd value, retries. A slot is always *latest value wins*;")
    a("nothing is queued here.")
    a("")
    for s in slots:
        a(f"### {s['name']} — {s['direction']} ({s['total']} bytes at offset {s['offset']})")
        a("")
        a(s["doc"] + ".")
        a("")
        a("| offset in record | field | type | notes |")
        a("|---|---|---|---|")
        o = 4
        a(f"| 0 | `seq` | u32 | odd while writing |")
        for n, t, c in s["fields"]:
            a(f"| {o} | `{n}` | {t} | {c} |")
            o += TYPES[t][2]
        a(f"| {o} | `seqEnd` | u32 | equals `seq` when the write finished |")
        a("")
    a("## Ring buffers")
    a("")
    a("One-shot events. A full ring drops the **newest** event and bumps the header's")
    a("dropped counter, because a stale slot is worse than a lost event.")
    a("")
    for r in rings:
        a(f"### {r['name']} — {r['direction']} ({r['capacity']} records at offset {r['offset']})")
        a("")
        a("| offset | field | type | notes |")
        a("|---|---|---|---|")
        a("| 0 | `head` | u32 | next index to write |")
        a("| 4 | `tail` | u32 | next index to read |")
        a(f"| 8 | `records` | {r['recsize']}-byte records | ring buffer |")
        a("")
        a("Record fields:")
        a("")
        a("| offset in record | field | type | notes |")
        a("|---|---|---|---|")
        o = 0
        for n, t, c in r["fields"]:
            a(f"| {o} | `{n}` | {t} | {c} |")
            o += TYPES[t][2]
        a("")
    a("## Tables (one seqlock over a list)")
    a("")
    a("`u32 seq` at offset 0, a fixed header, then up to `capacity` records. The writer")
    a("makes `seq` odd, writes header and records, then makes it even. The reader copies the")
    a("header and `count` records and discards the copy if `seq` changed or was odd.")
    a("")
    for t in tables:
        a(f"### {t['name']} — {t['direction']} ({t['total']} bytes at offset {t['offset']})")
        a("")
        a(t["doc"] + ".")
        a("")
        a("| offset | field | type | notes |")
        a("|---|---|---|---|")
        a("| 0 | `seq` | u32 | odd while writing |")
        for n, ty, c, o in t["hfields"]:
            a(f"| {o} | `{n}` | {ty} | {c} |")
        a(f"| {t['rec_off']} | `records` | {t['capacity']} x {t['recsize']}-byte records | |")
        a("")
        a("Record fields:")
        a("")
        a("| offset in record | field | type | notes |")
        a("|---|---|---|---|")
        for n, ty, c, o in t["rfields"]:
            a(f"| {o} | `{n}` | {ty} | {c} |")
        a("")
    a("## STREAM file (bulk Minecraft -> ULTRAKILL)")
    a("")
    a("A second mapped file, `ultracraft.stream`, next to the bridge. Single-producer")
    a("single-consumer byte ring; records are 8-byte aligned `u32 type, u32 length, payload`.")
    a("")
    a("| constant | value | notes |")
    a("|---|---|---|")
    for n, v, c in STREAM:
        a(f"| `{n}` | {v} | {c} |")
    a("")
    a("## Event kinds")
    a("")
    a("| value | name |")
    a("|---|---|")
    for k, v in EVENT_KINDS.items():
        a(f"| {v} | `{k}` |")
    a("")
    open(path, "w").write("\n".join(L2))


LAYOUT_VERSION = 1


def layout_fingerprint(header_size, slots, rings, tables, total_size):
    """The header's version: a fingerprint of the whole computed layout (every region's name,
    offset and size, every field's name and type). A bridge file laid out by any other build
    has a different one, and is wiped and re-laid on open: otherwise counters left at offsets
    that moved read as garbage (2026-10-13: the key ring's head/tail landed on an old record,
    so ULTRAKILL saw the ring as full forever and dropped every key)."""
    import json, zlib
    regions = []
    for kind, items in (("slot", slots), ("ring", rings), ("table", tables)):
        for r in items:
            regions.append([kind, r["name"], r["offset"], r["total"],
                            json.loads(json.dumps(r, default=str, sort_keys=True))])
    text = json.dumps([LAYOUT_VERSION, header_size, total_size, regions], sort_keys=True, default=str)
    return (zlib.crc32(text.encode()) & 0x7FFFFFFF) or 1


def main():
    global VERSION_VALUE
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    header_size, slots, rings, tables, total_size = compute_layout()
    VERSION_VALUE = layout_fingerprint(header_size, slots, rings, tables, total_size)

    cs = os.path.join(root, "ultrakill-mod", "src", "Protocol.cs")
    java = os.path.join(root, "minecraft-mod", "src", "main", "java", "dev", "ultracraft", "bridge", "Protocol.java")
    doc = os.path.join(here, "PROTOCOL.md")

    for p in (cs, java):
        os.makedirs(os.path.dirname(p), exist_ok=True)

    emit_csharp(header_size, slots, rings, tables, total_size, cs)
    emit_java(header_size, slots, rings, tables, total_size, java)
    emit_doc(header_size, slots, rings, tables, total_size, doc)

    print(f"header {header_size}B, total {total_size}B")
    for s in slots:
        print(f"  slot  {s['name']:<12} off {s['offset']:>5}  {s['total']:>4}B  {s['direction']}")
    for r in rings:
        print(f"  ring  {r['name']:<12} off {r['offset']:>5}  {r['total']:>4}B  {r['direction']}")
    for t in tables:
        print(f"  table {t['name']:<12} off {t['offset']:>5}  {t['total']:>4}B  {t['direction']}")
    print(f"wrote {cs}")
    print(f"wrote {java}")
    print(f"wrote {doc}")


if __name__ == "__main__":
    main()