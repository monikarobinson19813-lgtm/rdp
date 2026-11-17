package com.remotephone.direct;
import java.security.KeyPair;
public final class HostIdentity {
    private HostIdentity() {}
    public static KeyPair getOrCreate() { throw new UnsupportedOperationException("test overload supplies identity"); }
}
