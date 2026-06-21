package com.tjc.bugagent.analysis.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 把分析报告翻译成运维/测试/实施能看懂的一句"人话"。
 * 优先走确定性错误模式库（准确率最高），没命中再退回报告里的结论字段。
 */
@Component
public class PlainAnswerResolver {

    private static final Pattern UNKNOWN_COLUMN_PATTERN = Pattern.compile("Unknown column '([^']+)'");
    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([`\\w.]+)");
    // 常见确定性错误的识别模式，命中后直接给运维/测试/实施能看懂的人话
    private static final Pattern DATA_TOO_LONG_PATTERN = Pattern.compile("Data too long for column '([^']+)'");
    private static final Pattern DATA_TRUNCATED_PATTERN = Pattern.compile("Data truncated for column '([^']+)'");
    private static final Pattern COLUMN_NULL_PATTERN = Pattern.compile("Column '([^']+)' cannot be null");
    private static final Pattern NO_DEFAULT_PATTERN = Pattern.compile("Field '([^']+)' doesn't have a default value");
    private static final Pattern DUPLICATE_ENTRY_PATTERN = Pattern.compile("Duplicate entry '([^']*)' for key '([^']+)'");
    private static final Pattern TABLE_MISSING_PATTERN = Pattern.compile("Table '([^']+)' doesn't exist");
    private static final Pattern INCORRECT_VALUE_PATTERN = Pattern.compile("Incorrect (\\w+) value: '([^']*)'");
    private static final Pattern SQL_SYNTAX_PATTERN = Pattern.compile("You have an error in your SQL syntax");
    private static final Pattern OUT_OF_RANGE_PATTERN = Pattern.compile("Out of range value for column '([^']+)'");

    public String buildPlainAnswer(String finalReport, String evidence) {
        String text = safe(finalReport) + "\n" + safe(evidence);
        // 先用确定性错误规则库出人话，命中即返回（准确率最高）
        String patternAnswer = errorPatternPlainAnswer(text);
        if (patternAnswer != null) {
            return patternAnswer;
        }
        // 其次取报告里模型写的一句话摘要：Bug 分析叫"通俗结论"，接口分析叫"通俗说明"，都没有再退回"问题结论"
        String plain = extractSectionFirstLine(finalReport, "通俗结论");
        if (isBlank(plain)) {
            plain = extractSectionFirstLine(finalReport, "通俗说明");
        }
        if (isBlank(plain)) {
            plain = extractSectionFirstLine(finalReport, "问题结论");
        }
        if (!isBlank(plain) && !isGenericConclusion(plain)) {
            return "简单说：" + trim(plain, 120);
        }
        // 实在没有结构化结论，抓报告第一句有意义的话兜底，别甩通用废话
        String firstLine = firstMeaningfulLine(finalReport);
        if (!isBlank(firstLine)) {
            return "简单说：" + firstLine;
        }
        return "简单说：Agent 已完成分析，详细原因请看“分析报告”和“证据”标签。";
    }

    /**
     * 确定性错误模式库：命中常见数据库/运行时错误时，直接给运维、测试、实施能看懂的一句话。
     * 规则优先、LLM 兜底——规则覆盖的部分准确率拉满。没命中返回 null。
     */
    static String errorPatternPlainAnswer(String text) {
        String safeText = text == null ? "" : text;
        String columnAnswer = unknownColumnPlainAnswer(safeText);
        if (columnAnswer != null) {
            return columnAnswer;
        }
        Matcher matcher = DATA_TOO_LONG_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段存的值太长了，超过数据库该字段的长度限制，所以写入失败。让开发确认是否要截断或加长度校验，或让 DBA 确认字段长度要不要调大。";
        }
        matcher = DATA_TRUNCATED_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段存的值被截断了，通常是值超长或类型不匹配。让开发确认传入的值和字段类型/长度是否对得上。";
        }
        matcher = COLUMN_NULL_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：必填字段 " + matcher.group(1) + " 没有值（为空），但数据库要求它不能为空。让开发确认这个字段为什么没传值或没查到值。";
        }
        matcher = NO_DEFAULT_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段没给值，而数据库又没设默认值，所以插入失败。让开发确认这个字段是否漏传。";
        }
        matcher = DUPLICATE_ENTRY_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：数据重复了，唯一键 " + matcher.group(2) + " 上已存在值 '" + matcher.group(1) + "'，不能再插入同样的。让开发确认是不是重复提交，或本该走更新而不是新增。";
        }
        matcher = TABLE_MISSING_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：SQL 用到的表 " + matcher.group(1) + " 在当前数据库里不存在。让实施/DBA 确认表是否漏建，或开发确认表名、连的库是否写错。";
        }
        matcher = INCORRECT_VALUE_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：存进数据库的值类型不对，字段期望 " + matcher.group(1) + " 类型，实际给的是 '" + matcher.group(2) + "'。让开发确认传入值的格式/类型。";
        }
        matcher = OUT_OF_RANGE_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段的值超出了该字段类型能存的范围（比如 int 存了太大的数）。让开发确认值的范围或字段类型是否要放大。";
        }
        if (safeText.contains("foreign key constraint fails") || safeText.contains("Cannot delete or update a parent row")) {
            return "简单说：违反了外键约束——要删/改的数据还被别的表引用着，或插入的关联数据在主表里不存在。让开发确认表间关联关系和删除顺序。";
        }
        if (safeText.contains("Deadlock found")) {
            return "简单说：数据库发生死锁，两个事务互相等对方的锁。多是并发下加锁顺序不一致，让开发检查事务里 update 的顺序，必要时加重试。";
        }
        if (safeText.contains("Lock wait timeout exceeded")) {
            return "简单说：等数据库行锁超时——有别的事务长时间占着锁没提交。让开发查是不是有大事务或慢 SQL 一直占着锁不放。";
        }
        if (safeText.contains("Connection is not available") || safeText.contains("Data source rejected")) {
            return "简单说：数据库连接池被占满，拿不到连接。通常是慢 SQL 或连接没释放导致泄漏，让开发查慢查询和连接关闭，或调大连接池。";
        }
        if (SQL_SYNTAX_PATTERN.matcher(safeText).find()) {
            return "简单说：SQL 语句语法有问题，数据库直接拒绝执行。常见于动态拼接漏了参数或写错关键字，让开发检查这条 SQL。";
        }
        if (safeText.contains("NullPointerException")) {
            return "简单说：代码里有个对象是空的（null）还被使用了，导致接口报错。让开发定位是哪个值没取到或没初始化。";
        }
        if (safeText.contains("NumberFormatException")) {
            return "简单说：程序想把一段文本转成数字，但内容不是合法数字，转换失败。让开发确认传入或存的值格式对不对。";
        }
        if (safeText.contains("IndexOutOfBoundsException") || safeText.contains("ArrayIndexOutOfBoundsException")) {
            return "简单说：代码访问列表或数组时下标越界了。让开发确认数据条数和取值逻辑。";
        }
        if (safeText.contains("ClassCastException")) {
            return "简单说：类型强转出错——把一个对象当成了不兼容的类型来用。让开发确认对象的实际类型和强转的目标类型。";
        }
        if (safeText.contains("ConcurrentModificationException")) {
            return "简单说：遍历集合的同时又改了它（增删元素）导致报错。让开发改用迭代器删除，或遍历副本再改。";
        }
        if (safeText.contains("Connection refused")) {
            return "简单说：连不上目标服务（数据库/Redis/下游接口）——对方没启动、地址端口不对或网络不通。让运维确认服务在不在、地址对不对。";
        }
        if (safeText.contains("Read timed out") || safeText.contains("connect timed out") || safeText.contains("Connection timed out")) {
            return "简单说：调用超时——连上了但对方响应太慢或网络卡。让开发/运维查下游接口或数据库是不是慢，必要时调大超时、加重试。";
        }
        return null;
    }

    /**
     * 当证据里包含 MySQL "Unknown column" 报错时，输出一句面向测试/实施的人话解释；否则返回 null。
     */
    static String unknownColumnPlainAnswer(String text) {
        String safeText = text == null ? "" : text;
        Matcher columnMatcher = UNKNOWN_COLUMN_PATTERN.matcher(safeText);
        if (!columnMatcher.find()) {
            return null;
        }
        String column = columnMatcher.group(1);
        Matcher tableMatcher = FROM_TABLE_PATTERN.matcher(safeText);
        String table = tableMatcher.find() ? tableMatcher.group(1).replace("`", "") : "相关表";
        return "简单说：问题出在数据库字段不匹配。SQL 查询用了 " + table + "." + column
                + " 字段，但当前数据库表里没有这个字段，所以接口报 500。让开发确认 SQL 是否写错，或让实施/DBA 确认表结构是否漏了字段。";
    }

    /**
     * 过滤强制收口那种"已基于证据收口"的通用结论，这类话当通俗答案没意义。
     */
    private boolean isGenericConclusion(String conclusion) {
        return conclusion.contains("已基于") || conclusion.contains("已完成分析") || conclusion.contains("达到最大");
    }

    private String firstMeaningfulLine(String report) {
        if (isBlank(report)) {
            return null;
        }
        for (String line : report.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 6) {
                return trim(trimmed, 120);
            }
        }
        return null;
    }

    private String extractSectionFirstLine(String report, String sectionName) {
        if (isBlank(report)) {
            return null;
        }
        int start = report.indexOf(sectionName);
        if (start < 0) {
            return null;
        }
        // 分隔符兼容三种写法：通俗结论：xxx、通俗结论:xxx、【通俗说明】xxx
        int delimiter = firstIndexOf(report, start + sectionName.length(), '：', ':', '】');
        if (delimiter < 0) {
            return null;
        }
        int end = report.indexOf('\n', delimiter + 1);
        return report.substring(delimiter + 1, end < 0 ? report.length() : end).trim();
    }

    private int firstIndexOf(String text, int from, char... chars) {
        int best = -1;
        for (char ch : chars) {
            int index = text.indexOf(ch, from);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }
}
