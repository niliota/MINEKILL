// Prints the offsets the generated Java side will actually use, in the same format
// as the C# layout check, so the two can be diffed. Java gives us the offsets
// explicitly (from Protocol.java), so this is really a check that the generated
// Java constants agree with the generator's own C# view of the world.
public class LayoutCheck
{
    public static void main(String[] args)
    {
        System.out.println("CONST:HEADER_SIZE:" + space.niliota.minekill.bridge.Protocol.HEADER_SIZE);
        System.out.println("CONST:TOTAL_SIZE:" + space.niliota.minekill.bridge.Protocol.TOTAL_SIZE);
        // Same set the C# side prints: every slot/ring offset and every table constant.
        for (java.lang.reflect.Field f : space.niliota.minekill.bridge.Protocol.class.getFields())
        {
            String n = f.getName();
            if (f.getType() != int.class) continue;
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!(n.startsWith("OFFSET_") || n.startsWith("TBL_") || n.startsWith("STREAM_"))) continue;
            try { System.out.println("CONST:" + n + ":" + f.getInt(null)); }
            catch (IllegalAccessException e) { System.out.println("CONST:" + n + ":UNREADABLE"); }
        }

        dumpSlot("SlotPlayer", space.niliota.minekill.bridge.Protocol.SlotPlayer.class,
                 space.niliota.minekill.bridge.Protocol.SlotPlayer.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotPlayer.OFF_SEQ);
        dumpSlot("SlotCamera", space.niliota.minekill.bridge.Protocol.SlotCamera.class,
                 space.niliota.minekill.bridge.Protocol.SlotCamera.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotCamera.OFF_SEQ);
        dumpSlot("SlotInput", space.niliota.minekill.bridge.Protocol.SlotInput.class,
                 space.niliota.minekill.bridge.Protocol.SlotInput.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotInput.OFF_SEQ);
        dumpSlot("SlotCorrection", space.niliota.minekill.bridge.Protocol.SlotCorrection.class,
                 space.niliota.minekill.bridge.Protocol.SlotCorrection.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotCorrection.OFF_SEQ);
        dumpSlot("SlotLevel", space.niliota.minekill.bridge.Protocol.SlotLevel.class,
                 space.niliota.minekill.bridge.Protocol.SlotLevel.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotLevel.OFF_SEQ);
        dumpSlot("SlotSelection", space.niliota.minekill.bridge.Protocol.SlotSelection.class,
                 space.niliota.minekill.bridge.Protocol.SlotSelection.SIZE,
                 space.niliota.minekill.bridge.Protocol.SlotSelection.OFF_SEQ);
    }

    static void dumpSlot(String name, Class<?> cls, int size, int seqOff)
    {
        System.out.println("STRUCT:" + name + ":" + size);
        for (java.lang.reflect.Field f : cls.getDeclaredFields())
        {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String fn = f.getName();
            String constName = "OFF_" + fn.toUpperCase();
            int off;
            try
            {
                off = cls.getField(constName).getInt(null);
            }
            catch (Exception e)
            {
                System.out.println("FIELD:" + name + "." + fn + ":MISSING_CONSTANT");
                continue;
            }
            System.out.println("FIELD:" + name + "." + fn + ":" + off + ":?");
        }
    }
}