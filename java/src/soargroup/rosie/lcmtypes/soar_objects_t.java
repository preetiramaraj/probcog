/* LCM type definition class file
 * This file was automatically generated by lcm-gen
 * DO NOT MODIFY BY HAND!!!!
 */

package soargroup.rosie.lcmtypes;
 
import java.io.*;
import java.util.*;
import lcm.lcm.*;
 
public final class soar_objects_t implements lcm.lcm.LCMEncodable
{
    public long utime;
    public int num_objects;
    public soargroup.rosie.lcmtypes.object_data_t objects[];
 
    public soar_objects_t()
    {
    }
 
    public static final long LCM_FINGERPRINT;
    public static final long LCM_FINGERPRINT_BASE = 0x92af6330dbe6ff69L;
 
    static {
        LCM_FINGERPRINT = _hashRecursive(new ArrayList<Class<?>>());
    }
 
    public static long _hashRecursive(ArrayList<Class<?>> classes)
    {
        if (classes.contains(soargroup.rosie.lcmtypes.soar_objects_t.class))
            return 0L;
 
        classes.add(soargroup.rosie.lcmtypes.soar_objects_t.class);
        long hash = LCM_FINGERPRINT_BASE
             + soargroup.rosie.lcmtypes.object_data_t._hashRecursive(classes)
            ;
        classes.remove(classes.size() - 1);
        return (hash<<1) + ((hash>>63)&1);
    }
 
    public void encode(DataOutput outs) throws IOException
    {
        outs.writeLong(LCM_FINGERPRINT);
        _encodeRecursive(outs);
    }
 
    public void _encodeRecursive(DataOutput outs) throws IOException
    {
        outs.writeLong(this.utime); 
 
        outs.writeInt(this.num_objects); 
 
        for (int a = 0; a < this.num_objects; a++) {
            this.objects[a]._encodeRecursive(outs); 
        }
 
    }
 
    public soar_objects_t(byte[] data) throws IOException
    {
        this(new LCMDataInputStream(data));
    }
 
    public soar_objects_t(DataInput ins) throws IOException
    {
        if (ins.readLong() != LCM_FINGERPRINT)
            throw new IOException("LCM Decode error: bad fingerprint");
 
        _decodeRecursive(ins);
    }
 
    public static soargroup.rosie.lcmtypes.soar_objects_t _decodeRecursiveFactory(DataInput ins) throws IOException
    {
        soargroup.rosie.lcmtypes.soar_objects_t o = new soargroup.rosie.lcmtypes.soar_objects_t();
        o._decodeRecursive(ins);
        return o;
    }
 
    public void _decodeRecursive(DataInput ins) throws IOException
    {
        this.utime = ins.readLong();
 
        this.num_objects = ins.readInt();
 
        this.objects = new soargroup.rosie.lcmtypes.object_data_t[(int) num_objects];
        for (int a = 0; a < this.num_objects; a++) {
            this.objects[a] = soargroup.rosie.lcmtypes.object_data_t._decodeRecursiveFactory(ins);
        }
 
    }
 
    public soargroup.rosie.lcmtypes.soar_objects_t copy()
    {
        soargroup.rosie.lcmtypes.soar_objects_t outobj = new soargroup.rosie.lcmtypes.soar_objects_t();
        outobj.utime = this.utime;
 
        outobj.num_objects = this.num_objects;
 
        outobj.objects = new soargroup.rosie.lcmtypes.object_data_t[(int) num_objects];
        for (int a = 0; a < this.num_objects; a++) {
            outobj.objects[a] = this.objects[a].copy();
        }
 
        return outobj;
    }
 
}

