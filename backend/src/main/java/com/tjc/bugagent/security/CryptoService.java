package com.tjc.bugagent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置的落盘加密：AI apiKey 与 dbhub 数据库密码。
 *
 * <p>密文格式自描述：{@code v2:{keyId}:{base64(iv || ciphertext||tag)}}。
 * 带 keyId 是为了将来轮换密钥时老密文仍能解开——换密钥只需新增一个 key 并改 active-key-id，
 * 旧行在下一次保存时自然升级到新密钥。
 *
 * <p><b>两种历史格式不同，必须由调用方指明</b>：AI apiKey 过去是 Base64，
 * dbhub 密码过去是明文。靠猜格式去解会把数据库连接搞崩，所以对外暴露两个不同的解密入口。
 *
 * <p>未配置主密钥时不加密（原样存），只打一次告警：这样本地开发无痛，
 * 且绝不会出现"加密了却丢了密钥"的不可逆事故。生产请务必配置 BUGAGENT_MASTER_KEY。
 */
@Component
public class CryptoService {
    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String PREFIX = "v2:";
    private static final String DEFAULT_KEY_ID = "k1";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String masterKeyBase64;
    private SecretKeySpec masterKey;

    public CryptoService(@Value("${app.security.master-key:}") String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    @PostConstruct
    void init() {
        if (masterKeyBase64 == null || masterKeyBase64.trim().isEmpty()) {
            log.warn("未配置 app.security.master-key（环境变量 BUGAGENT_MASTER_KEY），"
                    + "AI apiKey 与数据库密码将以原格式存储、不加密。生产环境请务必配置。");
            return;
        }
        byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64.trim());
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("app.security.master-key 必须是 base64 编码的 16/24/32 字节密钥");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        log.info("敏感配置加密已启用（AES-GCM，密钥长度 {} 位）", keyBytes.length * 8);
    }

    public boolean isEnabled() {
        return masterKey != null;
    }

    /** 加密。未配主密钥时原样返回，保证功能不受影响。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || !isEnabled()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return PREFIX + DEFAULT_KEY_ID + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("加密失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 解密 AI apiKey：新密文走 AES，历史值是 Base64。
     */
    public String decryptAiKey(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (isCipherText(stored)) {
            return decryptCipherText(stored);
        }
        try {
            return new String(Base64.getDecoder().decode(stored), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            // 连 Base64 都不是，只能当明文处理，别让 AI 调用因此彻底不可用
            return stored;
        }
    }

    /**
     * 解密数据库密码：新密文走 AES，历史值是明文，直接透传。
     */
    public String decryptDbPassword(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        return isCipherText(stored) ? decryptCipherText(stored) : stored;
    }

    private boolean isCipherText(String value) {
        return value.startsWith(PREFIX);
    }

    private String decryptCipherText(String stored) {
        if (!isEnabled()) {
            throw new IllegalStateException("检测到加密数据，但未配置 app.security.master-key，无法解密");
        }
        try {
            // v2:{keyId}:{payload}
            int keyIdEnd = stored.indexOf(':', PREFIX.length());
            if (keyIdEnd < 0) {
                throw new IllegalArgumentException("密文格式非法");
            }
            byte[] payload = Base64.getDecoder().decode(stored.substring(keyIdEnd + 1));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("解密失败，主密钥可能已变更: " + exception.getMessage(), exception);
        }
    }
}
