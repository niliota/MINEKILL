// Prints the actual runtime layout of every generated C# struct as
//   STRUCT:<name>:<size>
//   FIELD:<Struct>.<field>:<offset>
// so it can be diffed against the Java side. If these two outputs ever differ,
// the two halves of the bridge are reading different bytes and will corrupt each
// other's data silently.
using System;
using System.Reflection;
using System.Runtime.InteropServices;

class LayoutCheck
{
    static void Main()
    {
        var t = typeof(UltraCraft.Bridge.Protocol);
        Console.WriteLine($"CONST:HEADER_SIZE:{ProtocolConst(t, "HEADER_SIZE")}");
        Console.WriteLine($"CONST:TOTAL_SIZE:{ProtocolConst(t, "TOTAL_SIZE")}");
        // Every slot/ring offset and every table constant, by name. The Java side prints
        // the same set, so a drift in any of them fails the diff.
        foreach (var f in t.GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            if (!f.IsLiteral || f.FieldType != typeof(int)) continue;
            if (f.Name.StartsWith("OFFSET_") || f.Name.StartsWith("TBL_"))
                Console.WriteLine($"CONST:{f.Name}:{f.GetValue(null)}");
        }

        foreach (var st in t.GetNestedTypes(BindingFlags.Public))
        {
            if (!st.IsValueType) continue;                 // skip the ring's array holder
            if (st.Name.Contains("Record")) continue;
            if (st.Name.StartsWith("Ring")) continue;   // holds a managed array
            if (st.IsEnum) continue;              // EventKind, not a wire struct
            int size = Marshal.SizeOf(st);
            Console.WriteLine($"STRUCT:{st.Name}:{size}");
            foreach (var f in st.GetFields(BindingFlags.Public | BindingFlags.Instance))
            {
                int off = Marshal.OffsetOf(st, f.Name).ToInt32();
                int fsize = Marshal.SizeOf(f.FieldType);
                Console.WriteLine($"FIELD:{st.Name}.{f.Name}:{off}:{fsize}");
            }
        }
    }

    static object ProtocolConst(Type t, string name)
    {
        var f = t.GetField(name, BindingFlags.Public | BindingFlags.Static);
        return f == null ? "?" : f.GetValue(null);
    }
}