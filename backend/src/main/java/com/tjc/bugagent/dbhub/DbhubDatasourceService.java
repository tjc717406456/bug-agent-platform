package com.tjc.bugagent.dbhub;

import com.tjc.bugagent.audit.AuditService;
import com.tjc.bugagent.dbhub.mapper.DbhubDatasourceMapper;
import com.tjc.bugagent.security.CryptoService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理后端内置的 dbhub 数据源配置。
 *
 * <p>数据库密码落库前加密（历史行为是明文，读取时自动兼容）。
 * 明文密码只在 getDatasourceConfig 返回值里出现——那是连库时才走的内部路径，
 * 对外的 listDatasourceConfigs 一律打码。
 */
@Service
public class DbhubDatasourceService {
    private final DbhubDatasourceMapper dbhubDatasourceMapper;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    public DbhubDatasourceService(DbhubDatasourceMapper dbhubDatasourceMapper, CryptoService cryptoService,
                                  AuditService auditService) {
        this.dbhubDatasourceMapper = dbhubDatasourceMapper;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    /**
     * 查询全部数据源配置。密码不解密、直接打码，密文也不会流出接口。
     */
    public List<DbhubDatasourceConfig> listDatasourceConfigs() {
        List<DbhubDatasourceConfig> configs = dbhubDatasourceMapper.listAll();
        for (DbhubDatasourceConfig config : configs) {
            maskPassword(config);
        }
        return configs;
    }

    /**
     * 根据 key 查询数据源配置，密码已解密——供连库与保存时保留旧密码使用，勿直接返回给前端。
     */
    public DbhubDatasourceConfig getDatasourceConfig(String key) {
        DbhubDatasourceConfig config = dbhubDatasourceMapper.getByKey(key);
        if (config != null) {
            config.setPassword(cryptoService.decryptDbPassword(config.getPassword()));
        }
        return config;
    }

    /**
     * 保存数据源配置。密码留空表示沿用旧密码；落库前一律加密。
     */
    public DbhubDatasourceConfig saveDatasourceConfig(DbhubDatasourceConfig config) {
        validateConfig(config);
        auditService.log("DATASOURCE_SAVE", "DATASOURCE", config.getKey(), config.getHost() + "/" + config.getDatabase());
        int count = dbhubDatasourceMapper.countByKey(config.getKey());
        if (count > 0) {
            DbhubDatasourceConfig oldConfig = getDatasourceConfig(config.getKey());
            String password = isBlank(config.getPassword()) && oldConfig != null ? oldConfig.getPassword() : config.getPassword();
            dbhubDatasourceMapper.updateByKey(
                    config.getHost(), config.getPort(), config.getUser(), encryptPassword(password), config.getDatabase(), config.getKey());
            return maskedView(config.getKey());
        }
        dbhubDatasourceMapper.insert(
                config.getKey(), config.getHost(), config.getPort(), config.getUser(),
                encryptPassword(config.getPassword()), config.getDatabase());
        return maskedView(config.getKey());
    }

    /** 保存后回读一份打码视图返回给前端，不带任何形式的密码。 */
    private DbhubDatasourceConfig maskedView(String key) {
        DbhubDatasourceConfig saved = dbhubDatasourceMapper.getByKey(key);
        maskPassword(saved);
        return saved;
    }

    private String encryptPassword(String plain) {
        return cryptoService.encrypt(safe(plain));
    }

    /**
     * 删除数据源配置。
     */
    public void deleteDatasourceConfig(String key) {
        auditService.log("DATASOURCE_DELETE", "DATASOURCE", key, null);
        dbhubDatasourceMapper.deleteByKey(key);
    }

    private void validateConfig(DbhubDatasourceConfig config) {
        if (config == null || isBlank(config.getKey()) || !config.getKey().matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new IllegalArgumentException("Invalid datasource key");
        }
        if (isBlank(config.getHost()) || config.getPort() == null || config.getPort() <= 0 || isBlank(config.getUser()) || isBlank(config.getDatabase())) {
            throw new IllegalArgumentException("Missing datasource config");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void maskPassword(DbhubDatasourceConfig config) {
        if (config != null && !isBlank(config.getPassword())) {
            config.setPassword("******");
        }
    }
}
