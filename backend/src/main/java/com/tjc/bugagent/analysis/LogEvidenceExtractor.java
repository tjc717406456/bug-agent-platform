package com.tjc.bugagent.analysis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一段应用日志里抠出定位 Bug 需要的线索：异常堆栈、SQL、traceId、时间、ERROR 行。
 * 主要覆盖 Spring Boot + MyBatis 的常见日志格式，抠不到的退回原始文本即可。
 */
@Service
public class LogEvidenceExtractor {
    // 行首时间戳：2026-06-18 14:00:23[.669]
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    // traceId / X-B3-TraceId / TID 等常见键
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?i)(?:trace[-_]?id|x-b3-traceid|tid)[\"'\\s:=]+([0-9a-fA-F\\-]{8,})");
    // 异常类名：xxxException 或 xxxError
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([\\w.$]+(?:Exception|Error))(:.*)?");
    // MyBatis 打的 SQL：==> Preparing: / ==> Parameters:
    private static final Pattern MYBATIS_SQL_PATTERN = Pattern.compile("(?i)(Preparing:|Parameters:)\\s*(.*)");
    // 裸 SQL 行
    private static final Pattern RAW_SQL_PATTERN = Pattern.compile("(?i)\\b(select|insert|update|delete)\\b.+\\bfrom\\b|(?i)^\\s*(insert|update|delete)\\b");
    private static final int MAX_SQL_LINES = 20;
    private static final int MAX_ERROR_LINES = 15;
    private static final int MAX_STACK_LINES = 80;

    /**
     * 解析日志文本，抠出各类线索。文本为空返回空线索。
     */
    public LogClues extract(String logText) {
        LogClues clues = new LogClues();
        if (logText == null || logText.trim().isEmpty()) {
            return clues;
        }
        String[] lines = logText.replace("\r", "").split("\n");
        clues.setTraceId(firstTraceId(logText));
        clues.setRequestTime(firstTimestamp(logText));
        clues.setStackTrace(extractStack(lines));
        collectSqlAndErrors(lines, clues);
        return clues;
    }

    private String firstTraceId(String logText) {
        Matcher matcher = TRACE_ID_PATTERN.matcher(logText);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstTimestamp(String logText) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(logText);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * 抽取异常堆栈块：从第一个异常行开始，连同后续 at / Caused by 行一起截下来。
     */
    private String extractStack(String[] lines) {
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (EXCEPTION_PATTERN.matcher(lines[i]).find()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder stack = new StringBuilder();
        int taken = 0;
        for (int i = start; i < lines.length && taken < MAX_STACK_LINES; i++) {
            String trimmed = lines[i].trim();
            boolean stackLine = trimmed.startsWith("at ")
                    || trimmed.startsWith("Caused by:")
                    || trimmed.startsWith("...")
                    || EXCEPTION_PATTERN.matcher(lines[i]).find();
            // 堆栈块中间偶尔夹日志框架包装行，遇到明显非堆栈行就停
            if (i > start && !stackLine) {
                break;
            }
            stack.append(lines[i]).append("\n");
            taken++;
        }
        return stack.toString().trim();
    }

    private void collectSqlAndErrors(String[] lines, LogClues clues) {
        Set<String> sqlLines = new LinkedHashSet<String>();
        List<String> errorLines = new ArrayList<String>();
        for (String line : lines) {
            Matcher mybatis = MYBATIS_SQL_PATTERN.matcher(line);
            if (mybatis.find()) {
                if (sqlLines.size() < MAX_SQL_LINES) {
                    sqlLines.add(mybatis.group(1) + " " + mybatis.group(2).trim());
                }
            } else if (RAW_SQL_PATTERN.matcher(line).find() && sqlLines.size() < MAX_SQL_LINES) {
                sqlLines.add(line.trim());
            }
            if (line.contains("ERROR") && errorLines.size() < MAX_ERROR_LINES) {
                errorLines.add(line.trim());
            }
        }
        clues.setSqlLines(new ArrayList<String>(sqlLines));
        clues.setErrorLines(errorLines);
    }
}
