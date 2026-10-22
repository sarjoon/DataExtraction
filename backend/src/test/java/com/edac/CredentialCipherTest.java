package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;

class CredentialCipherTest {
    @TempDir Path directory;
    @Test void randomizedEncryptionSurvivesRestartAndRejectsTampering() {
        var environment=new MockEnvironment();environment.setActiveProfiles("local");
        String path=directory.resolve("credential.key").toString();
        var first=new CredentialCipher("",path,environment);
        String encrypted=first.encrypt("database-secret");
        assertThat(first.encrypt("database-secret")).isNotEqualTo(encrypted);
        assertThat(new CredentialCipher("",path,environment).decrypt(encrypted)).isEqualTo("database-secret");
        byte[] bytes=Base64.getDecoder().decode(encrypted.substring(3));bytes[15]^=1;
        assertThatThrownBy(()->first.decrypt("v1:"+Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Models.ConnectionInput("x","ORACLE","db",1521,"SID","db","u","U","database-secret").toString()).doesNotContain("database-secret");
    }
    @Test void requiresManagedKeyOutsideLocalMode() {
        var environment=new MockEnvironment();environment.setActiveProfiles("production");
        assertThatThrownBy(()->new CredentialCipher("",directory.resolve("key").toString(),environment)).isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitEmptyPasswordSurvivesRestartWithDifferentKeyWithoutMaskingUnreadableSecrets() {
        var environment=new MockEnvironment();environment.setActiveProfiles("local");
        String keyPath=directory.resolve("first.key").toString();
        var first=new CredentialCipher("",keyPath,environment);
        String empty=first.encrypt(""),secret=first.encrypt("nonempty-secret");
        assertThat(new CredentialCipher("",keyPath,environment).decrypt(empty)).isEmpty();
        var restarted=new CredentialCipher("",directory.resolve("replacement.key").toString(),environment);
        assertThat(restarted.decrypt(empty)).isEmpty();
        assertThatThrownBy(()->restarted.decrypt(secret)).hasMessageContaining("Cannot decrypt");
        assertThatThrownBy(()->restarted.decrypt("v1:unreadable")).hasMessageContaining("Cannot decrypt");
        assertThatThrownBy(()->restarted.decrypt(null)).hasMessageContaining("Cannot decrypt");
    }
}
