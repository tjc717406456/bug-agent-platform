package com.tjc.bugagent.analysis.log;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
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
    // 时间戳：日期可选，兼容「2026-06-18 14:00:23.669」和只有「14:00:23.669」两种
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}[ T])?\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    // traceId / X-B3-TraceId / TID 等常见键
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?i)(?:trace[-_]?id|x-b3-traceid|tid)[\"'\\s:=]+([0-9a-fA-F\\-]{8,})");
    // 异常类名：xxxException 或 xxxError
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([\\w.$]+(?:Exception|Error))(:.*)?");
    // MyBatis 打的 SQL：==> Preparing: / ==> Parameters:
    private static final Pattern MYBATIS_SQL_PATTERN = Pattern.compile("(?i)(Preparing:|Parameters:)\\s*(.*)");
    // 裸 SQL 行
    private static final Pattern RAW_SQL_PATTERN = Pattern.compile("(?i)\\b(select|insert|update|delete)\\b.+\\bfrom\\b|(?i)^\\s*(insert|update|delete)\\b");
    // 一条新日志条目的行首时间戳（带不带日期都认）：异常栈续行没有，用它当切块边界
    private static final Pattern NEW_ENTRY_PATTERN = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2}[ T])?\\d{2}:\\d{2}:\\d{2}");
    private static final int MAX_SQL_LINES = 20;
    private static final int MAX_ERROR_LINES = 15;
    private static final int MAX_STACK_LINES = 80;
    private static final int MAX_RELEVANT_LINES = 40;
    // 关键词最短长度，过滤掉"的""了"这类无意义短词，只留货架号/单号这种有区分度的
    private static final int MIN_HINT_LENGTH = 5;

    /**
     * 解析日志文本，抠出各类线索。文本为空返回空线索。
     * apiPath 和用户描述用来按接口名/关键词捞相关业务日志行，补上异常/SQL 抓不到的 INFO 证据。
     */
    public LogClues extract(String logText, String apiPath, String userDescription) {
        LogClues clues = new LogClues();
        if (logText == null || logText.trim().isEmpty()) {
            return clues;
        }
        String[] lines = logText.replace("\r", "").split("\n");
        clues.setTraceId(firstTraceId(logText));
        clues.setRequestTime(firstTimestamp(logText));
        // 先把所有异常块切出来，再按目标接口锚定，避免整文件多接口时抓错别的接口异常
        List<int[]> blocks = findExceptionBlocks(lines);
        String endpoint = lastSegment(apiPath).toLowerCase();
        clues.setStackTrace(extractStack(lines, blocks, endpoint));
        collectSqlAndErrors(lines, blocks, endpoint, clues);
        collectRelevantLines(lines, apiPath, userDescription, clues);
        return clues;
    }

    /**
     * 按接口名（路径末段）和用户描述里的关键词，从日志里捞包含它们的行。
     * 业务日志（如 result=false）多藏在这里，异常/SQL 提取器抓不到。
     */
    private void collectRelevantLines(String[] lines, String apiPath, String userDescription, LogClues clues) {
        Set<String> hints = new LinkedHashSet<String>();
        String endpoint = lastSegment(apiPath);
        if (endpoint.length() >= 3) {
            hints.add(endpoint.toLowerCase());
        }
        if (userDescription != null) {
            for (String token : userDescription.split("[\\s,，。；;：:\"'（）()\\[\\]]+")) {
                if (token.length() >= MIN_HINT_LENGTH) {
                    hints.add(token.toLowerCase());
                }
            }
        }
        if (hints.isEmpty()) {
            return;
        }
        List<String> relevant = new ArrayList<String>();
        for (String line : lines) {
            String lower = line.toLowerCase();
            for (String hint : hints) {
                if (lower.contains(hint)) {
                    relevant.add(line.trim());
                    break;
                }
            }
            if (relevant.size() >= MAX_RELEVANT_LINES) {
                break;
            }
        }
        clues.setRelevantLines(relevant);
    }

    private String lastSegment(String apiPath) {
        if (apiPath == null) {
            return "";
        }
        String[] parts = apiPath.split("/");
        return parts.length == 0 ? "" : parts[parts.length - 1].trim();
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
     * 切出日志里所有异常块：每块从一个异常行开始，连收后续 at / Caused by / ### / 异常行，遇非堆栈行收尾。
     * 块之间不重叠，后续按目标接口从中挑出最相关的一块。
     */
    private List<int[]> findExceptionBlocks(String[] lines) {
        List<int[]> blocks = new ArrayList<int[]>();
        int i = 0;
        while (i < lines.length) {
            if (!EXCEPTION_PATTERN.matcher(lines[i]).find()) {
                i++;
                continue;
            }
            int start = i;
            int taken = 1;
            int j = i + 1;
            for (; j < lines.length && taken < MAX_STACK_LINES; j++) {
                // 行首带时间戳 = 另起一条新日志条目，哪怕它也含异常名也得收尾，否则两个接口的异常会粘成一块
                if (NEW_ENTRY_PATTERN.matcher(lines[j]).lookingAt()) {
                    break;
                }
                String trimmed = lines[j].trim();
                boolean stackLine = trimmed.startsWith("at ")
                        || trimmed.startsWith("Caused by:")
                        || trimmed.startsWith("...")
                        || trimmed.startsWith("###")  // MyBatis 包装异常的 ### Error/### Cause 行
                        || EXCEPTION_PATTERN.matcher(lines[j]).find();
                // 堆栈块中间偶尔夹日志框架包装行，遇到明显非堆栈行就收尾
                if (!stackLine) {
                    break;
                }
                taken++;
            }
            blocks.add(new int[]{start, j}); // [start, j) 左闭右开
            i = j;
        }
        return blocks;
    }

    /**
     * 块里任意一行（多为栈帧 com.x.Controller.openMachine(...)）含接口方法名即视为属于该接口。
     * 这是格式无关的最硬信号：Java 栈天生带方法名，不依赖任何项目自定义的日志话术。
     */
    private boolean blockMatchesEndpoint(String[] lines, int[] block, String endpoint) {
        if (endpoint == null || endpoint.length() < 3) {
            return false;
        }
        for (int k = block[0]; k < block[1]; k++) {
            if (lines[k].toLowerCase().contains(endpoint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 挑出要返回的异常块：优先栈帧匹配目标接口的那块；锚不到（无接口名/没命中）退回第一个异常，绝不空着。
     */
    private String extractStack(String[] lines, List<int[]> blocks, String endpoint) {
        if (blocks.isEmpty()) {
            return null;
        }
        int[] chosen = null;
        for (int[] block : blocks) {
            if (blockMatchesEndpoint(lines, block, endpoint)) {
                chosen = block;
                break;
            }
        }
        if (chosen == null) {
            chosen = blocks.get(0);
        }
        StringBuilder stack = new StringBuilder();
        for (int k = chosen[0]; k < chosen[1]; k++) {
            stack.append(lines[k]).append("\n");
        }
        return stack.toString().trim();
    }

    private void collectSqlAndErrors(String[] lines, List<int[]> blocks, String endpoint, LogClues clues) {
        Set<Integer> droppedErrorIdx = errorLinesToDrop(lines, blocks, endpoint);
        Set<String> sqlLines = new LinkedHashSet<String>();
        List<String> errorLines = new ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher mybatis = MYBATIS_SQL_PATTERN.matcher(line);
            if (mybatis.find()) {
                if (sqlLines.size() < MAX_SQL_LINES) {
                    sqlLines.add(mybatis.group(1) + " " + mybatis.group(2).trim());
                }
            } else if (RAW_SQL_PATTERN.matcher(line).find() && sqlLines.size() < MAX_SQL_LINES) {
                sqlLines.add(line.trim());
            }
            if (line.contains("ERROR") && errorLines.size() < MAX_ERROR_LINES && !droppedErrorIdx.contains(i)) {
                errorLines.add(line.trim());
            }
        }
        clues.setSqlLines(new ArrayList<String>(sqlLines));
        clues.setErrorLines(errorLines);
    }

    /**
     * 锚定到目标接口的异常块后，把「别的接口异常块」里的 ERROR 行行号收集起来，收集 errorLines 时跳过它们。
     * 只有真锚定上才过滤；锚不到（无接口名 / 没有任何块命中接口）返回空集 = 退回原来的「全收」，绝不漏。
     * 不在异常块内的独立 ERROR 行（如业务告警）一律保留，无法归属就别误删。
     */
    private Set<Integer> errorLinesToDrop(String[] lines, List<int[]> blocks, String endpoint) {
        Set<Integer> drop = new HashSet<Integer>();
        if (endpoint == null || endpoint.length() < 3) {
            return drop;
        }
        boolean anchored = false;
        for (int[] block : blocks) {
            if (blockMatchesEndpoint(lines, block, endpoint)) {
                anchored = true;
                break;
            }
        }
        if (!anchored) {
            return drop;
        }
        for (int[] block : blocks) {
            if (blockMatchesEndpoint(lines, block, endpoint)) {
                continue;
            }
            for (int k = block[0]; k < block[1]; k++) {
                if (lines[k].contains("ERROR")) {
                    drop.add(k);
                }
            }
        }
        return drop;
    }
}
