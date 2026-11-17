package com.remotephone.direct;

import java.io.DataInputStream;
import java.io.FileInputStream;

public final class GesturePayloadTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("payload path required");
        try (DataInputStream in = new DataInputStream(new FileInputStream(args[0]))) {
            float x1 = in.readFloat();
            float y1 = in.readFloat();
            float x2 = in.readFloat();
            float y2 = in.readFloat();
            long duration = in.readLong();
            if (in.read() != -1) throw new AssertionError("unexpected trailing bytes");
            if (x1 != 0.5f || y1 != 0.5f || x2 != 0.5f || y2 != 0.5f || duration != 123L) {
                throw new AssertionError(
                    "decoded mismatch: " + x1 + "," + y1 + "," + x2 + "," + y2 + "," + duration
                );
            }
        }
        System.out.println("WEB1 F39 JVM gesture decode PASS");
    }
}
