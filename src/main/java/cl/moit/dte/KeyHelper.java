package cl.moit.dte;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class KeyHelper {

    private final static String PKCS_1_PEM_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
    private final static String PKCS_1_PEM_FOOTER = "-----END RSA PRIVATE KEY-----";
    private final static String PKCS_8_PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private final static String PKCS_8_PEM_FOOTER = "-----END PRIVATE KEY-----";

    public static PrivateKey parseKey(String privateKeyData) throws GeneralSecurityException {
        PrivateKey key = null;
        if (privateKeyData.startsWith(PKCS_1_PEM_HEADER)) {
            privateKeyData = privateKeyData.replace(PKCS_1_PEM_HEADER, "");
            privateKeyData = privateKeyData.replace(PKCS_1_PEM_FOOTER, "");
            return readPkcs1PrivateKey(Base64.getMimeDecoder().decode(privateKeyData));
        } else if (privateKeyData.startsWith(PKCS_8_PEM_HEADER)) {
            privateKeyData = privateKeyData.replace(PKCS_8_PEM_HEADER, "");
            privateKeyData = privateKeyData.replace(PKCS_8_PEM_FOOTER, "");
            return readPkcs8PrivateKey(Base64.getMimeDecoder().decode(privateKeyData));
        } else {
            throw new RuntimeException("Unsupported key format");
        }
    }

    private static PrivateKey readPkcs8PrivateKey(byte[] pkcs8Bytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA", "SunRsaSign");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        try {
            return keyFactory.generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("Unexpected key format!", e);
        }
    }

    private static PrivateKey readPkcs1PrivateKey(byte[] pkcs1Bytes) throws GeneralSecurityException {
        // We can't use Java internal APIs to parse ASN.1 structures, so we build a PKCS#8 key Java can understand
        int pkcs1Length = pkcs1Bytes.length;
        int totalLength = pkcs1Length + 22;
        byte[] pkcs8Header = new byte[] {
                0x30, (byte) 0x82, (byte) ((totalLength >> 8) & 0xff), (byte) (totalLength & 0xff), // Sequence + total length
                0x2, 0x1, 0x0, // Integer (0)
                0x30, 0xD, 0x6, 0x9, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0xD, 0x1, 0x1, 0x1, 0x5, 0x0, // Sequence: 1.2.840.113549.1.1.1, NULL
                0x4, (byte) 0x82, (byte) ((pkcs1Length >> 8) & 0xff), (byte) (pkcs1Length & 0xff) // Octet string + length
        };
        byte[] pkcs8bytes = join(pkcs8Header, pkcs1Bytes);
        return readPkcs8PrivateKey(pkcs8bytes);
    }

    private static byte[] join(byte[] byteArray1, byte[] byteArray2){
        byte[] bytes = new byte[byteArray1.length + byteArray2.length];
        System.arraycopy(byteArray1, 0, bytes, 0, byteArray1.length);
        System.arraycopy(byteArray2, 0, bytes, byteArray1.length, byteArray2.length);
        return bytes;
    }

}
