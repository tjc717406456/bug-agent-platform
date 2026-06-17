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

    public void save(SaveAiConfigRequest request) {
        jdbcTemplate.update("update ai_provider_config set enabled = 0");
        jdbcTemplate.update(
                "insert into ai_provider_config(provider, base_url, model_name, api_key_cipher, timeout_seconds, enabled, created_at, updated_at) values (?, ?, ?, ?, ?, ?, now(), now())",
                request.getProvider(), request.getBaseUrl(), request.getModelName(), encode(request.getApiKey()), request.getTimeoutSeconds(), request.isEnabled());
    }

    public AiConfig getEnabledConfig() {
        List<AiConfig> list = jdbcTemplate.query(
                "select id, provider, base_url, model_name, api_key_cipher, timeout_seconds, enabled from ai_provider_config where enabled = 1 order by id desc limit 1",
                new AiConfigMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public AiConfig getMaskedConfig() {
        AiConfig config = getEnabledConfig();
        if (config != null) {
            config.setApiKey("******");
        }
        return config;
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
            return config;
        }
    }
}

