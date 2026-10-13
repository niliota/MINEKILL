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
    w("        public const uint VERSION = 1u;")
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
    for kind in ["BLOCK_PLACED", "BLOCK_BROKEN", "ENTITY_ATTACKED",
                 "PLAYER_HURT", "LEVEL_LOADED", "PLAYER_RESPAWN"]:
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
    # H in ULTRAKILL (unbound there by default): lock/unlock the Minecraft player in place.
    "KEY_HOLD_TOGGLE": 1 << 7,
}

EVENT_KINDS = {
    "BLOCK_PLACED": 1,
    "BLOCK_BROKEN": 2,
    "ENTITY_ATTACKED": 3,
    "PLAYER_HURT": 4,
    "LEVEL_LOADED": 5,
    "PLAYER_RESPAWN": 6,
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
    w("    public static final int VERSION = 1;")
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
    w("    public static final int EV_BLOCK_PLACED = 1;")
    w("    public static final int EV_BLOCK_BROKEN = 2;")
    w("    public static final int EV_ENTITY_ATTACKED = 3;")
    w("    public static final int EV_PLAYER_HURT = 4;")
    w("    public static final int EV_LEVEL_LOADED = 5;")
    w("    public static final int EV_PLAYER_RESPAWN = 6;")
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
    a("## Event kinds")
    a("")
    a("| value | name |")
    a("|---|---|")
    for k, v in EVENT_KINDS.items():
        a(f"| {v} | `{k}` |")
    a("")
    open(path, "w").write("\n".join(L2))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    header_size, slots, rings, tables, total_size = compute_layout()

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