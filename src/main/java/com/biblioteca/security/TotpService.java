package com.biblioteca.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP with RFC 4648 Base32 secrets.
 * Compatible with Google Authenticator, Authy, 1Password, Microsoft Authenticator.
 */
@Component
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;

    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public boolean verify(String base32Secret, String code) {
        if (base32Secret == null || code == null) return false;
        String clean = code.replaceAll("\\s", "");
        if (clean.length() != DIGITS) return false;
        try {
            int provided = Integer.parseInt(clean);
            byte[] key = base32Decode(base32Secret);
            long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;
            for (int i = -WINDOW; i <= WINDOW; i++) {
                if (generate(key, counter + i) == provided) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String otpAuthUri(String issuer, String account, String secret) {
        String enc = java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8);
        String acc = java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8);
        return "otpauth://totp/" + enc + ":" + acc
                + "?secret=" + secret
                + "&issuer=" + enc
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private int generate(byte[] key, long counter) throws Exception {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xff);
            counter >>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int mod = (int) Math.pow(10, DIGITS);
        return binary % mod;
    }

    static String base32Encode(byte[] data) {
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1f;
                sb.append(BASE32_ALPHABET.charAt(idx));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.trim().toUpperCase().replace("=", "").replaceAll("\\s", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int idx = BASE32_ALPHABET.indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("Invalid Base32 char: " + c);
            buffer = (buffer << 5) | idx;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
