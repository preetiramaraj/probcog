/* LCM type definition class file
 * This file was automatically generated by lcm-gen
 * DO NOT MODIFY BY HAND!!!!
 */

package soargroup.rosie.lcmtypes;
 
import java.io.*;
import java.util.*;
import lcm.lcm.*;
 
public final class classification_list_t implements lcm.lcm.LCMEncodable
{
    public long utime;
    public int num_classifications;
    public soargroup.rosie.lcmtypes.classification_t classifications[];
 
    public classification_list_t()
    {
    }
 
    public static final long LCM_FINGERPRINT;
    public static final long LCM_FINGERPRINT_BASE = 0xffc072a4e3f6c6cfL;
 
    static {
        LCM_FINGERPRINT = _hashRecursive(new ArrayList<Class<?>>());
    }
 
    public static long _hashRecursive(ArrayList<Class<?>> classes)
    {
        if (classes.contains(soargroup.rosie.lcmtypes.classification_list_t.class))
            return 0L;
 
        classes.add(soargroup.rosie.lcmtypes.classification_list_t.class);
        long hash = LCM_FINGERPRINT_BASE
             + soargroup.rosie.lcmtypes.classification_t._hashRecursive(classes)
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
 
        outs.writeInt(this.num_classifications); 
 
        for (int a = 0; a < this.num_classifications; a++) {
            this.classifications[a]._encodeRecursive(outs); 
        }
 
    }
 
    public classification_list_t(byte[] data) throws IOException
    {
        this(new LCMDataInputStream(data));
    }
 
    public classification_list_t(DataInput ins) throws IOException
    {
        if (ins.readLong() != LCM_FINGERPRINT)
            throw new IOException("LCM Decode error: bad fingerprint");
 
        _decodeRecursive(ins);
    }
 
    public static soargroup.rosie.lcmtypes.classification_list_t _decodeRecursiveFactory(DataInput ins) throws IOException
    {
        soargroup.rosie.lcmtypes.classification_list_t o = new soargroup.rosie.lcmtypes.classification_list_t();
        o._decodeRecursive(ins);
        return o;
    }
 
    public void _decodeRecursive(DataInput ins) throws IOException
    {
        this.utime = ins.readLong();
 
        this.num_classifications = ins.readInt();
 
        this.classifications = new soargroup.rosie.lcmtypes.classification_t[(int) num_classifications];
        for (int a = 0; a < this.num_classifications; a++) {
            this.classifications[a] = soargroup.rosie.lcmtypes.classification_t._decodeRecursiveFactory(ins);
        }
 
    }
 
    public soargroup.rosie.lcmtypes.classification_list_t copy()
    {
        soargroup.rosie.lcmtypes.classification_list_t outobj = new soargroup.rosie.lcmtypes.classification_list_t();
        outobj.utime = this.utime;
 
        outobj.num_classifications = this.num_classifications;
 
        outobj.classifications = new soargroup.rosie.lcmtypes.classification_t[(int) num_classifications];
        for (int a = 0; a < this.num_classifications; a++) {
            outobj.classifications[a] = this.classifications[a].copy();
        }
 
        return outobj;
    }
 
}

