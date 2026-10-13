// Prints the offsets the generated Java side will actually use, in the same format
// as the C# layout check, so the two can be diffed. Java gives us the offsets
// explicitly (from Protocol.java), so this is really a check that the generated
// Java constants agree with the generator's own C# view of the world.
public class LayoutCheck
{
    public static void main(String[] args)
    {
        System.out.println("CONST:HEADER_SIZE:" + dev.ultracraft.bridge.Protocol.HEADER_SIZE);
        System.out.println("CONST:TOTAL_SIZE:" + dev.ultracraft.bridge.Protocol.TOTAL_SIZE);
        // Same set the C# side prints: every slot/ring offset and every table constant.
        for (java.lang.reflect.Field f : dev.ultracraft.bridge.Protocol.class.getFields())
        {
            String n = f.getName();
            if (f.getType() != int.class) continue;
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!(n.startsWith("OFFSET_") || n.startsWith("TBL_") || n.startsWith("STREAM_"))) continue;
            try { System.out.println("CONST:" + n + ":" + f.getInt(null)); }
            catch (IllegalAccessException e) { System.out.println("CONST:" + n + ":UNREADABLE"); }
        }

        dumpSlot("SlotPlayer", dev.ultracraft.bridge.Protocol.SlotPlayer.class,
                 dev.ultracraft.bridge.Protocol.SlotPlayer.SIZE,
                 dev.ultracraft.bridge.Protocol.SlotPlayer.OFF_SEQ);
        dumpSlot("SlotCamera", dev.ultracraft.bridge.Protocol.SlotCamera.class,
                 dev.ultracraft.bridge.Protocol.SlotCamera.SIZE,
                 dev.ultracraft.bridge.Protocol.SlotCamera.OFF_SEQ);
        dumpSlot("SlotInput", dev.ultracraft.bridge.Protocol.SlotInput.class,
                 dev.ultracraft.bridge.Protocol.SlotInput.SIZE,
                 dev.ultracraft.bridge.Protocol.SlotInput.OFF_SEQ);
        dumpSlot("SlotCorrection", dev.ultracraft.bridge.Protocol.SlotCorrection.class,
                 dev.ultracraft.bridge.Protocol.SlotCorrection.SIZE,
                 dev.ultracraft.bridge.Protocol.SlotCorrection.OFF_SEQ);
        dumpSlot("SlotLevel", dev.ultracraft.bridge.Protocol.SlotLevel.class,
                 dev.ultracraft.bridge.Protocol.SlotLevel.SIZE,
                 dev.ultracraft.bridge.Protocol.SlotLevel.OFF_SEQ);
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