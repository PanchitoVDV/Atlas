package be.esmay.atlas.base.utils;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;

@UtilityClass
public final class SecureKeyGen {

    private static final int KEY_LENGTH = 32;

    public static String generateKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] key = new byte[KEY_LENGTH];

        secureRandom.nextBytes(key);
        return bytesToHex(key);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        return hexString.toString();
    }

}
