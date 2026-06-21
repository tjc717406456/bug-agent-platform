package com.tjc.bugagent.analysis.verify;

import com.tjc.bugagent.dbhub.DbhubClient;
import com.tjc.bugagent.project.ProjectDatasource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动验证确定性结论：字段/表是否存在能直接连库核对，无需人工判断对错。
 * 验证通过的结论才有资格自动进评估飞轮，没法机器验证的留给人工延迟标注。
 */
@Service
public class AnalysisAutoVerifier {
    private static final Pattern UNKNOWN_COLUMN = Pattern.compile("Unknown column '([^']+)'");
    private static final Pattern FROM_TABLE = Pattern.compile("(?i)\\bfrom\\s+([`\\w.]+)");
    private static final Pattern TABLE_MISSING = Pattern.compile("Table '([^']+)' doesn't exist");

    private final DbhubClient dbhubClient;

    public AnalysisAutoVerifier(DbhubClient dbhubClient) {
        this.dbhubClient = dbhubClient;
    }

    /**
     * 从分析文本里找确定性结论并连库核对。
     * CONFIRMED：库里确实没这个字段/表，结论成立；REFUTED：库里有，结论可能错；UNVERIFIABLE：没法机器验证。
     */
    public Result verify(String text, ProjectDatasource datasource) {
        if (datasource == null || text == null || text.trim().isEmpty()) {
            return Result.unverifiable();
        }
        String key = datasource.getDbhubKey();

        Matcher column = UNKNOWN_COLUMN.matcher(text);
        Matcher fromTable = FROM_TABLE.matcher(text);
        if (column.find() && fromTable.find()) {
            Boolean exists = dbhubClient.columnExists(key, fromTable.group(1), column.group(1));
            if (exists != null) {
                return exists
                        ? Result.refuted("字段 " + column.group(1) + " 在库里实际存在，结论可能不准")
                        : Result.confirmed(Arrays.asList(column.group(1), "字段", "不存在"));
            }
        }

        Matcher table = TABLE_MISSING.matcher(text);
        if (table.find()) {
            Boolean exists = dbhubClient.tableExists(key, table.group(1));
            if (exists != null) {
                return exists
                        ? Result.refuted("表 " + table.group(1) + " 实际存在，结论可能不准")
                        : Result.confirmed(Arrays.asList(stripDatabase(table.group(1)), "表", "不存在"));
            }
        }

        return Result.unverifiable();
    }

    private static String stripDatabase(String table) {
        String clean = table.replace("`", "");
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    /**
     * 自动验证结果：状态 + 验证通过时得出的期望关键词。
     */
    public static class Result {
        private final String status;
        private final List<String> keywords;
        private final String note;

        private Result(String status, List<String> keywords, String note) {
            this.status = status;
            this.keywords = keywords;
            this.note = note;
        }

        static Result confirmed(List<String> keywords) {
            return new Result("CONFIRMED", keywords, null);
        }

        static Result refuted(String note) {
            return new Result("REFUTED", new ArrayList<String>(), note);
        }

        static Result unverifiable() {
            return new Result("UNVERIFIABLE", new ArrayList<String>(), null);
        }

        public String getStatus() {
            return status;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public String getNote() {
            return note;
        }
    }
}
