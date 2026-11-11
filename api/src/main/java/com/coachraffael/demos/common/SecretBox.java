package com.coachraffael.demos.common;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM für Demo-Secrets in der Datenbank. Ohne Schlüssel lassen sich keine Secrets speichern. */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(HubProperties props) {
        String k = props.secretKey();
        if (k == null || k.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(k.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("HUB_SECRET_KEY muss 32 Byte (Base64) lang sein");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschlüsseln fehlgeschlagen", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret lässt sich nicht entschlüsseln – HUB_SECRET_KEY geändert?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new Errors.Conflict("HUB_SECRET_KEY ist nicht gesetzt – Secrets lassen sich nicht speichern");
        }
    }
}
