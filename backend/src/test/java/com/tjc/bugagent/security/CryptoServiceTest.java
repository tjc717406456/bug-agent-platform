package com.tjc.bugagent.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加密与两种历史格式的兼容读。这一环搞错会直接把数据库连接搞崩，
 * 所以 AI(Base64) 与 dbhub(明文) 的降级路径分别验证。
 */
class CryptoServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private CryptoService enabled() {
        CryptoService service = new CryptoService(KEY);
        service.init();
        return service;
    }

    private CryptoService disabled() {
        CryptoService service = new CryptoService("");
        service.init();
        return service;
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        CryptoService service = enabled();
        String cipher = service.encrypt("sk-secret-123");
        assertTrue(cipher.startsWith("v2:"), cipher);
        assertNotEquals("sk-secret-123", cipher);
        assertEquals("sk-secret-123", service.decryptAiKey(cipher));
        assertEquals("sk-secret-123", service.decryptDbPassword(cipher));
    }

    /** 同一明文两次加密结果不同：IV 每次随机，密文不可比对 */
    @Test
    void sameInputProducesDifferentCipherText() {
        CryptoService service = enabled();
        assertNotEquals(service.encrypt("same"), service.encrypt("same"));
    }

    /** 历史 AI key 是 Base64，必须仍能解出来 */
    @Test
    void legacyAiKeyIsBase64() {
        String legacy = Base64.getEncoder().encodeToString("sk-old".getBytes(StandardCharsets.UTF_8));
        assertEquals("sk-old", enabled().decryptAiKey(legacy));
    }

    /** 历史 dbhub 密码是明文，直接透传——按 Base64 去解会得到乱码并连不上库 */
    @Test
    void legacyDbPasswordIsPlaintext() {
        assertEquals("1234", enabled().decryptDbPassword("1234"));
    }

    /** 未配主密钥时不加密，且历史数据照常可读——本地开发不受影响，也不会造成密钥丢失的不可逆事故 */
    @Test
    void withoutMasterKeyValuesArePassedThrough() {
        CryptoService service = disabled();
        assertFalse(service.isEnabled());
        assertEquals("plain", service.encrypt("plain"));
        assertEquals("1234", service.decryptDbPassword("1234"));
        assertEquals("sk-old", service.decryptAiKey(
                Base64.getEncoder().encodeToString("sk-old".getBytes(StandardCharsets.UTF_8))));
    }

    /** 有密文却没配密钥：必须明确报错，而不是悄悄返回乱码 */
    @Test
    void cipherTextWithoutKeyFailsLoudly() {
        String cipher = enabled().encrypt("secret");
        assertThrows(IllegalStateException.class, () -> disabled().decryptDbPassword(cipher));
    }

    @Test
    void nullAndEmptyPassThrough() {
        CryptoService service = enabled();
        assertEquals(null, service.encrypt(null));
        assertEquals("", service.encrypt(""));
        assertEquals(null, service.decryptAiKey(null));
        assertEquals(null, service.decryptDbPassword(null));
    }

    @Test
    void rejectsMalformedMasterKey() {
        CryptoService service = new CryptoService(Base64.getEncoder().encodeToString(new byte[7]));
        assertThrows(IllegalStateException.class, service::init);
    }
}
