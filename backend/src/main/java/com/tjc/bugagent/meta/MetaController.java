package com.tjc.bugagent.meta;

import com.tjc.bugagent.ai.AiConfig;
import com.tjc.bugagent.ai.AiConfigService;
import com.tjc.bugagent.common.ApiResponse;
import com.tjc.bugagent.dbhub.DbhubDatasourceConfig;
import com.tjc.bugagent.dbhub.mapper.DbhubDatasourceMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通用户需要、但不该走管理员接口的少量只读元信息。
 *
 * <p>刻意挂在中立的 /meta 前缀下：/ai-config 与 /dbhub/datasources 已被
 * AuthInterceptor 整段限定为管理员，塞在那底下要么被挡、要么得开脆弱的例外。
 * 这里返回的一律是脱敏视图——不含 apiKey，也不含库的 host/账号/密码。
 */
@RestController
@RequestMapping("/meta")
public class MetaController {

    private final AiConfigService aiConfigService;
    private final DbhubDatasourceMapper dbhubDatasourceMapper;

    public MetaController(AiConfigService aiConfigService, DbhubDatasourceMapper dbhubDatasourceMapper) {
        this.aiConfigService = aiConfigService;
        this.dbhubDatasourceMapper = dbhubDatasourceMapper;
    }

    /** 当前生效的主模型，只回provider/模型名/是否支持视觉。 */
    @GetMapping("/active-model")
    public ApiResponse<Map<String, Object>> activeModel() {
        AiConfig config = aiConfigService.getEnabledConfig();
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (config == null) {
            view.put("configured", false);
            return ApiResponse.ok(view);
        }
        view.put("configured", true);
        view.put("provider", config.getProvider());
        view.put("modelName", config.getModelName());
        view.put("supportsVision", config.isSupportsVision());
        return ApiResponse.ok(view);
    }

    /** 可供项目绑定的数据源清单，只有 key 与库名。 */
    @GetMapping("/datasource-keys")
    public ApiResponse<List<Map<String, String>>> datasourceKeys() {
        List<Map<String, String>> keys = new ArrayList<Map<String, String>>();
        for (DbhubDatasourceConfig config : dbhubDatasourceMapper.listKeys()) {
            Map<String, String> item = new LinkedHashMap<String, String>();
            item.put("key", config.getKey());
            item.put("database", config.getDatabase());
            keys.add(item);
        }
        return ApiResponse.ok(keys);
    }
}
