package com.rakku212.voice;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

@Structure.FieldOrder({"data", "len"})
public class SoundCoreBuffer extends Structure {

    public Pointer data;
    public long len;

    public SoundCoreBuffer() {
        super();
    }

    public SoundCoreBuffer(Pointer pointer) {
        super(pointer);
        read();
    }

    public static class ByReference extends SoundCoreBuffer implements Structure.ByReference {
    }
}
