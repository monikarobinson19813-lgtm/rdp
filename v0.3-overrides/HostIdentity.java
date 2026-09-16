package com.remotephone.direct;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

public final class HostIdentity {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "remotephone_host_identity_v1";

    private HostIdentity() {}

    public static synchronized KeyPair getOrCreate() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            PrivateKey priv = (PrivateKey) ks.getKey(ALIAS, null);
            PublicKey pub = ks.getCertificate(ALIAS).getPublicKey();
            if (priv != null && pub != null) return new KeyPair(pub, priv);
        }

        KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build();
        gen.initialize(spec);
        return gen.generateKeyPair();
    }
}
