package com.openhire.compliance.security;

import com.openhire.compliance.service.port.CompliancePorts.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM with a random 96-bit IV per record. The key is the SHA-256 of {@code openhire.compliance.data-key}.
 * The record's context (tenant and application) is authenticated as additional data, so a ciphertext cannot be moved
 * to another record. Format: {@code v1:} + base64(iv || ciphertext || tag).
 */
@Component
public class AesGcmCipher implements Cipher {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${openhire.compliance.data-key}") String dataKey) {
        if (dataKey == null || dataKey.isBlank())
            throw new IllegalArgumentException("A compliance data key is required");
        try {
            key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256").digest(dataKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String encrypt(String plaintext, String context) {
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            var sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext, String context) {
        if (!ciphertext.startsWith(PREFIX)) throw new IllegalArgumentException("Unknown ciphertext format");
        try {
            var bytes = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The stored value could not be decrypted", e);
        }
    }
}
