package com.edac;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${edac.credential-key}") String configuredKey,
            @Value("${edac.credential-key-file}") String keyFile, Environment environment) {
        try {
            String encoded = configuredKey;
            if (encoded.isBlank()) {
                if (!environment.acceptsProfiles(Profiles.of("local")))
                    throw new IllegalStateException("Set EDAC_CREDENTIAL_KEY to a Base64-encoded 32-byte key outside local mode.");
                Path path = Path.of(keyFile).toAbsolutePath();
                Files.createDirectories(path.getParent());
                if (!Files.exists(path)) {
                    byte[] generated = new byte[32];
                    random.nextBytes(generated);
                    try {
                        Files.writeString(path, Base64.getEncoder().encodeToString(generated), StandardOpenOption.CREATE_NEW);
                    } catch (FileAlreadyExistsException concurrentStartup) { /* Read the established key below. */ }
                }
                encoded = Files.readString(path).trim();
            }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalStateException("EDAC credential key must contain exactly 32 decoded bytes.");
            key = new SecretKeySpec(bytes, "AES");
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load the EDAC credential encryption key. Check the key configuration.");
        }
    }

    public String encrypt(String password) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot encrypt the database password."); }
    }

    public String decrypt(String value) {
        try {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            if (buffer.remaining() < 28) throw new IllegalArgumentException();
            byte[] iv = new byte[12]; buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()]; buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot decrypt the saved password. Restore the original encryption key or save this connection with a new password.");
        }
    }
}
