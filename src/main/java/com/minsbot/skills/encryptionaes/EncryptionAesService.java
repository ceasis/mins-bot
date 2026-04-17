package com.minsbot.skills.encryptionaes;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EncryptionAesService {

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_LEN = 16;
    private static final int PBKDF2_ITER = 200_000;
    private static final int AES_KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public Map<String, Object> encryptText(String plaintext, String passphrase) throws Exception {
        if (plaintext == null || passphrase == null) throw new IllegalArgumentException("plaintext and passphrase required");
        byte[] salt = new byte[SALT_LEN]; random.nextBytes(salt);
        byte[] iv = new byte[GCM_IV_LEN]; random.nextBytes(iv);
        SecretKey key = deriveKey(passphrase, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Envelope: salt (16) || iv (12) || ciphertext
        byte[] envelope = new byte[salt.length + iv.length + ct.length];
        System.arraycopy(salt, 0, envelope, 0, salt.length);
        System.arraycopy(iv, 0, envelope, salt.length, iv.length);
        System.arraycopy(ct, 0, envelope, salt.length + iv.length, ct.length);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", "AES-256-GCM");
        out.put("kdf", "PBKDF2WithHmacSHA256 (" + PBKDF2_ITER + " iterations)");
        out.put("ciphertextBase64", Base64.getEncoder().encodeToString(envelope));
        return out;
    }

    public Map<String, Object> decryptText(String ciphertextBase64, String passphrase) throws Exception {
        if (ciphertextBase64 == null || passphrase == null) throw new IllegalArgumentException("ciphertextBase64 and passphrase required");
        byte[] envelope = Base64.getDecoder().decode(ciphertextBase64);
        if (envelope.length < SALT_LEN + GCM_IV_LEN + 16) throw new IllegalArgumentException("envelope too short");
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[GCM_IV_LEN];
        byte[] ct = new byte[envelope.length - SALT_LEN - GCM_IV_LEN];
        System.arraycopy(envelope, 0, salt, 0, SALT_LEN);
        System.arraycopy(envelope, SALT_LEN, iv, 0, GCM_IV_LEN);
        System.arraycopy(envelope, SALT_LEN + GCM_IV_LEN, ct, 0, ct.length);

        SecretKey key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] pt = cipher.doFinal(ct);
        return Map.of("plaintext", new String(pt, StandardCharsets.UTF_8));
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITER, AES_KEY_BITS);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }
}
