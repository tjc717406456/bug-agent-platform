package com.tjc.bugagent.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;

/**
 * Saves and reads global AI configuration.
 */
@Service
public class AiConfigService {
    private final JdbcTemplate jdbcTemplate;

    public AiConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 列出全部 AI 配置（API Key 脱敏），按新增顺序返回，第一条即最早添加的。
     */
    public List<AiConfig> list() {
        List<AiConfig> list = jdbcTemplate.query(
                "select id, provider, base_url, model_name, api_key_cipher, timeout_seconds, enabled, supports_vision from ai_provider_config order by id asc",
                new AiConfigMapper());
        for (AiConfig config : list) {
            config.setApiKey("******");
        }
        return list;
    }

    /**
     * 新增一条配置。库里没有任何配置时，新增的这条自动设为启用，保证默认有一个可用 AI。
     */
    public void create(SaveAiConfigRequest request) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from ai_provider_config", Integer.class);
        boolean enableNow = count == null || count == 0;
        jdbcTemplate.update(
                "insert into ai_provider_config(provider, base_url, model_name, api_key_cipher, timeout_seconds, enabled, supports_vision, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, now(), now())",
                request.getProvider(), request.getBaseUrl(), request.getModelName(), encode(request.getApiKey()), request.getTimeoutSeconds(), enableNow, request.isSupportsVision());
    }

    /**
     * 编辑配置。API Key 留空或仍是脱敏掩码时保持原值不动，避免把 ****** 覆盖回真实密钥。
     * 不在这里改启用状态，启用切换走 activate。
     */
    public void update(Long id, SaveAiConfigRequest request) {
        Integer exists = jdbcTemplate.queryForObject("select count(*) from ai_provider_config where id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("AI 配置不存在: " + id);
        }
        boolean keepKey = request.getApiKey() == null || request.getApiKey().trim().isEmpty() || request.getApiKey().contains("*");
        if (keepKey) {
            jdbcTemplate.update(
                    "update ai_provider_config set provider = ?, base_url = ?, model_name = ?, timeout_seconds = ?, supports_vision = ?, updated_at = now() where id = ?",
                    request.getProvider(), request.getBaseUrl(), request.getModelName(), request.getTimeoutSeconds(), request.isSupportsVision(), id);
        } else {
            jdbcTemplate.update(
                    "update ai_provider_config set provider = ?, base_url = ?, model_name = ?, api_key_cipher = ?, timeout_seconds = ?, supports_vision = ?, updated_at = now() where id = ?",
                    request.getProvider(), request.getBaseUrl(), request.getModelName(), encode(request.getApiKey()), request.getTimeoutSeconds(), request.isSupportsVision(), id);
        }
    }

    /**
     * 切换启用的配置，决定 Agent 分析走哪个 AI。同一时间只有一条启用。
     */
    public void activate(Long id) {
        Integer exists = jdbcTemplate.queryForObject("select count(*) from ai_provider_config where id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("AI 配置不存在: " + id);
        }
        jdbcTemplate.update("update ai_provider_config set enabled = 0");
        jdbcTemplate.update("update ai_provider_config set enabled = 1, updated_at = now() where id = ?", id);
    }

    /**
     * 删除配置。删掉的恰好是当前启用项时，自动把剩下最新的一条设为启用。
     */
    public void delete(Long id) {
        AiConfig enabled = getEnabledConfig();
        jdbcTemplate.update("delete from ai_provider_config where id = ?", id);
        if (enabled != null && id.equals(enabled.getId())) {
            List<Long> remaining = jdbcTemplate.queryForList("select id from ai_provider_config order by id desc limit 1", Long.class);
            if (!remaining.isEmpty()) {
                activate(remaining.get(0));
            }
        }
    }

    public AiConfig getEnabledConfig() {
        List<AiConfig> list = jdbcTemplate.query(
                "select id, provider, base_url, model_name, api_key_cipher, timeout_seconds, enabled, supports_vision from ai_provider_config where enabled = 1 order by id desc limit 1",
                new AiConfigMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private class AiConfigMapper implements RowMapper<AiConfig> {
        @Override
        public AiConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            AiConfig config = new AiConfig();
            config.setId(rs.getLong("id"));
            config.setProvider(rs.getString("provider"));
            config.setBaseUrl(rs.getString("base_url"));
            config.setModelName(rs.getString("model_name"));
            config.setApiKey(decode(rs.getString("api_key_cipher")));
            config.setTimeoutSeconds(rs.getInt("timeout_seconds"));
            config.setEnabled(rs.getBoolean("enabled"));
            config.setSupportsVision(rs.getBoolean("supports_vision"));
            return config;
        }
    }
}

