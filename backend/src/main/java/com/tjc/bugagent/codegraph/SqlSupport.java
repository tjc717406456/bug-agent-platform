package com.tjc.bugagent.codegraph;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 文本处理：从 SQL 里抽表名、压缩空白、尝试语法解析、生成元数据 JSON。
 * Java 注解 SQL 和 MyBatis XML 两条索引链路共用这里。
 */
@Component
public class SqlSupport {

    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(from|join|update|into)\\s+([`\\w.]+)");

    /** 用正则从 SQL 抽出涉及的表名，去掉反引号。动态 SQL 也能命中。 */
    public Set<String> extractTables(String sql) {
        Set<String> tables = new HashSet<String>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(2).replace("`", ""));
        }
        return tables;
    }

    /** 把 MyBatis 占位符替换成合法值后试解析，失败无所谓——表名已经靠正则提取了。 */
    public void tryParseSql(String sql) {
        try {
            String normalized = sql.replaceAll("#\\{[^}]+}", "1").replaceAll("\\$\\{[^}]+}", "x");
            Statement ignored = CCJSqlParserUtil.parse(normalized);
        } catch (Exception ignored) {
            // 动态 MyBatis SQL 仍会通过正则提取表名。
        }
    }

    /** 压平 SQL 里的多余空白并截断超长内容。 */
    public String compactSql(String value) {
        return CodeGraphText.trim(value == null ? "" : value.replaceAll("\\s+", " ").trim(), 10000);
    }

    /** 把单个键值拼成节点元数据 JSON，转义引号和反斜杠避免破坏结构。 */
    public String json(String key, String value) {
        return "{\"" + key + "\":\"" + CodeGraphText.trim(value, 3000).replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
