package com.tjc.bugagent.dbhub;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * dbhub 数据源管理接口。
 */
@RestController
@RequestMapping("/dbhub/datasources")
public class DbhubDatasourceController {
    private final DbhubClient dbhubClient;

    public DbhubDatasourceController(DbhubClient dbhubClient) {
        this.dbhubClient = dbhubClient;
    }

    /**
     * 查询已配置的数据源。
     */
    @GetMapping
    public ApiResponse<List<DbhubDatasourceConfig>> listDatasources() {
        return ApiResponse.ok(dbhubClient.listDatasourceConfigs());
    }

    /**
     * 保存数据源。
     */
    @PostMapping
    public ApiResponse<DbhubDatasourceConfig> saveDatasource(@RequestBody DbhubDatasourceConfig request) {
        return ApiResponse.ok(dbhubClient.saveDatasourceConfig(request));
    }

    /**
     * 测试数据源连接。
     */
    @PostMapping("/test")
    public ApiResponse<String> testDatasource(@RequestBody DbhubDatasourceConfig request) {
        return ApiResponse.ok(dbhubClient.testDatasourceConfig(request));
    }

    /**
     * 删除数据源。
     */
    @DeleteMapping("/{key}")
    public ApiResponse<String> deleteDatasource(@PathVariable String key) {
        dbhubClient.deleteDatasourceConfig(key);
        return ApiResponse.ok("ok");
    }
}
