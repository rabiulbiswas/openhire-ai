package com.openhire.integration.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TokenCipher {
    private final String encodedKey;

    public TokenCipher(@Value("${openhire.integration.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    private SecretKeySpec key() {
        try {
            byte[] key = Base64.getDecoder().decode(encodedKey);
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Integration token encryption is not configured");
        }
    }

    public String encrypt(String context, String value) {
        var key = key();
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce)
                            .put(ciphertext)
                            .array());
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Token encryption failed");
        }
    }

    public String decrypt(String context, String value) {
        var key = key();
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Token authentication failed");
        }
    }
}
