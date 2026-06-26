package com.tjc.bugagent.ai;

import com.tjc.bugagent.ai.mapper.AiConfigMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Saves and reads global AI configuration.
 */
@Service
public class AiConfigService {
    private final AiConfigMapper aiConfigMapper;

    public AiConfigService(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    /**
     * 列出全部 AI 配置（API Key 脱敏），按新增顺序返回，第一条即最早添加的。
     */
    public List<AiConfig> list() {
        List<AiConfig> list = aiConfigMapper.listAll();
        for (AiConfig config : list) {
            config.setApiKey("******");
        }
        return list;
    }

    /**
     * 新增一条配置。库里没有任何配置时，新增的这条自动设为启用，保证默认有一个可用 AI。
     */
    public void create(SaveAiConfigRequest request) {
        boolean enableNow = aiConfigMapper.countAll() == 0;
        AiConfig config = new AiConfig();
        config.setProvider(request.getProvider());
        config.setBaseUrl(request.getBaseUrl());
        config.setModelName(request.getModelName());
        config.setApiKey(encode(request.getApiKey()));
        config.setTimeoutSeconds(request.getTimeoutSeconds());
        config.setEnabled(enableNow);
        config.setSupportsVision(request.isSupportsVision());
        config.setRole(normalizeRole(request.getRole()));
        aiConfigMapper.insert(config);
    }

    /**
     * 编辑配置。API Key 留空或仍是脱敏掩码时保持原值不动，避免把 ****** 覆盖回真实密钥。
     * 不在这里改启用状态，启用切换走 activate。
     */
    public void update(Long id, SaveAiConfigRequest request) {
        if (aiConfigMapper.countById(id) == 0) {
            throw new IllegalArgumentException("AI 配置不存在: " + id);
        }
        boolean keepKey = request.getApiKey() == null || request.getApiKey().trim().isEmpty() || request.getApiKey().contains("*");
        AiConfig config = new AiConfig();
        config.setId(id);
        config.setProvider(request.getProvider());
        config.setBaseUrl(request.getBaseUrl());
        config.setModelName(request.getModelName());
        config.setTimeoutSeconds(request.getTimeoutSeconds());
        config.setSupportsVision(request.isSupportsVision());
        config.setRole(normalizeRole(request.getRole()));
        if (keepKey) {
            aiConfigMapper.updateKeepKey(config);
        } else {
            config.setApiKey(encode(request.getApiKey()));
            aiConfigMapper.updateWithKey(config);
        }
    }

    /** 角色认 PRIMARY/UTILITY/EMBEDDING，其它一律当 PRIMARY。 */
    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase();
        if ("UTILITY".equals(value)) {
            return "UTILITY";
        }
        if ("EMBEDDING".equals(value)) {
            return "EMBEDDING";
        }
        return "PRIMARY";
    }

    /**
     * 取启用的 embedding 模型配置（role=EMBEDDING 且启用），用于语义召回。
     * 没有或没启用就返回 null，调用方据此退回纯词法匹配。
     */
    public AiConfig getEmbeddingConfig() {
        return decodeKey(aiConfigMapper.findEnabledEmbedding());
    }

    /**
     * 取辅助模型配置（接口讲解、多假设侦察等轻活用），没标 UTILITY 的就返回 null，调用方退回主模型。
     */
    public AiConfig getUtilityConfig() {
        return decodeKey(aiConfigMapper.findUtility());
    }

    /**
     * 切换启用的配置，决定 Agent 分析走哪个 AI。同一时间只有一条启用。
     */
    public void activate(Long id) {
        String role = aiConfigMapper.findRoleById(id);
        if (role == null) {
            throw new IllegalArgumentException("AI 配置不存在: " + id);
        }
        if ("EMBEDDING".equals(normalizeRole(role))) {
            // 嵌入模型是独立开关：翻转自身启用位，不波及主/辅模型，切换主模型时也不会被关掉
            aiConfigMapper.toggleEnabled(id);
            return;
        }
        // 主模型单选：只在非 embedding 行里互斥，别把预留启用的 embedding 一并关停
        aiConfigMapper.disableAllNonEmbedding();
        aiConfigMapper.enableById(id);
    }

    /**
     * 删除配置。删掉的恰好是当前启用项时，自动把剩下最新的一条设为启用。
     */
    public void delete(Long id) {
        AiConfig enabled = getEnabledConfig();
        aiConfigMapper.deleteById(id);
        if (enabled != null && id.equals(enabled.getId())) {
            Long latestId = aiConfigMapper.findLatestId();
            if (latestId != null) {
                activate(latestId);
            }
        }
    }

    public AiConfig getEnabledConfig() {
        return decodeKey(aiConfigMapper.findEnabledConfig());
    }

    /** 把查询返回的密文 api_key 解码成明文，null 透传。 */
    private AiConfig decodeKey(AiConfig config) {
        if (config != null) {
            config.setApiKey(decode(config.getApiKey()));
        }
        return config;
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
