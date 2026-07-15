package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;

/**
 * 只读源码读取与路径护栏。从 AgentToolExecutor 抽出，工具执行和初始证据预取共用，
 * 避免 InitialEvidenceBuilder 反向依赖 AgentToolExecutor。
 */
@Component
public class SourceReader {
    private static final int MAX_RANGE_LINES = 400;

    private final CodeGraphQueryService codeGraphQueryService;
    private final ProjectService projectService;

    public SourceReader(CodeGraphQueryService codeGraphQueryService, ProjectService projectService) {
        this.codeGraphQueryService = codeGraphQueryService;
        this.projectService = projectService;
    }

    /**
     * 读取节点所在源码片段，从定位行往前留几行上下文。
     */
    public String readSnippet(CodeNode node, Long projectId, Long versionId, int maxLines) {
        if (node.getFilePath() == null || node.getLineNo() == null) {
            return "无源码定位信息";
        }
        try {
            Path path = resolveSourcePath(node.getFilePath(), node.getQualifiedName(), versionId);
            // 只读定位窗口内的行，避免大文件一次性读入内存撑爆堆
            int start = Math.max(0, node.getLineNo() - 8);
            StringBuilder snippet = new StringBuilder();
            try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
                List<String> window = stream.skip(start).limit(maxLines).collect(Collectors.toList());
                for (int index = 0; index < window.size(); index++) {
                    snippet.append(start + index + 1).append(": ").append(window.get(index)).append("\n");
                }
            }
            return snippet.length() == 0 ? "无源码内容" : snippet.toString();
        } catch (Exception exception) {
            return "读取源码失败: " + exception.getMessage();
        }
    }

    // 读整段方法时的行数上限：覆盖绝大多数方法，超长方法兜底截断（避免一次塞爆上下文）
    private static final int MAX_METHOD_LINES = 400;

    /**
     * 读整段方法：从定位行往下做花括号配对，把整个方法体读全，省得长方法尾部读不到、逼模型 grep 拼凑。
     * 找不到方法边界（如非 Java 方法、超长）就退回固定窗口。
     */
    public String readMethodSnippet(CodeNode node, Long projectId, Long versionId) {
        if (node.getFilePath() == null || node.getLineNo() == null) {
            return "无源码定位信息";
        }
        try {
            Path path = resolveSourcePath(node.getFilePath(), node.getQualifiedName(), versionId);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int scanStart = Math.max(0, node.getLineNo() - 1);
            int end = findMethodEnd(lines, scanStart);
            if (end < 0) {
                // 没配平（非方法/超长）→ 退回固定窗口，至少不比原来差
                return readSnippet(node, projectId, versionId, MAX_METHOD_LINES);
            }
            int displayStart = Math.max(0, scanStart - 2);
            StringBuilder snippet = new StringBuilder();
            for (int index = displayStart; index <= end && index < lines.size(); index++) {
                snippet.append(index + 1).append(": ").append(lines.get(index)).append("\n");
            }
            return snippet.length() == 0 ? "无源码内容" : snippet.toString();
        } catch (Exception exception) {
            return readSnippet(node, projectId, versionId, MAX_METHOD_LINES);
        }
    }

    /**
     * 按当前版本内的文件路径和行号读取源码。全文搜索命中文件后直接走这里，不再依赖代码节点索引。
     */
    public String readSourceRange(String filePath, Long versionId, Integer line, Integer contextLines) {
        if (isBlank(filePath)) {
            return "读取源码失败: 缺少 filePath";
        }
        int center = line == null || line < 1 ? 1 : line;
        int context = contextLines == null ? 40 : Math.max(0, contextLines);
        int start = (int) Math.max(1L, (long) center - context);
        int maxLines = (int) Math.min(MAX_RANGE_LINES, Math.max(1L, (long) context * 2L + 1L));
        try {
            Path path = resolveSourcePath(filePath, null, versionId);
            StringBuilder snippet = new StringBuilder();
            try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
                List<String> window = stream.skip(start - 1L).limit(maxLines).collect(Collectors.toList());
                for (int index = 0; index < window.size(); index++) {
                    snippet.append(start + index).append(": ").append(window.get(index)).append("\n");
                }
            }
            return snippet.length() == 0 ? "无源码内容" : snippet.toString();
        } catch (Exception exception) {
            return "读取源码失败: " + exception.getMessage();
        }
    }

    /**
     * 无行号的 mapper xml 节点兜底定位：按 id="节点名" 扫到标签行，回一段带行号的语句片段（读到闭合标签即止）。
     * 免得模型拿到"无源码定位信息"死胡同后，还得自己 grep 好几轮补救。找不到或读不了返回 null。
     */
    public String readXmlTagSnippet(CodeNode node, Long versionId, int maxLines) {
        if (node.getFilePath() == null || isBlank(node.getName())) {
            return null;
        }
        try {
            Path path = resolveSourcePath(node.getFilePath(), node.getQualifiedName(), versionId);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String needle = "id=\"" + node.getName() + "\"";
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(needle)) {
                    continue;
                }
                StringBuilder snippet = new StringBuilder();
                int end = Math.min(lines.size(), i + Math.max(1, maxLines));
                for (int index = i; index < end; index++) {
                    snippet.append(index + 1).append(": ").append(lines.get(index)).append("\n");
                    if (index > i && lines.get(index).matches(".*</(select|update|insert|delete)>.*")) {
                        break;
                    }
                }
                return snippet.toString();
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 从 startLine0(0-based) 起做花括号配对，返回方法体闭合 '}' 所在行下标；跳过字符串/字符/行注释/块注释里的花括号。
     * MAX_METHOD_LINES 行内没配平就返回 -1（超长或不是方法）。
     */
    private int findMethodEnd(List<String> lines, int startLine0) {
        int depth = 0;
        boolean opened = false;
        boolean inBlockComment = false;
        int limit = Math.min(lines.size(), startLine0 + MAX_METHOD_LINES);
        for (int i = startLine0; i < limit; i++) {
            String line = lines.get(i);
            boolean inString = false;
            boolean inChar = false;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (inBlockComment) {
                    if (ch == '*' && c + 1 < line.length() && line.charAt(c + 1) == '/') {
                        inBlockComment = false;
                        c++;
                    }
                    continue;
                }
                if (inString) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (inChar) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == '\'') {
                        inChar = false;
                    }
                    continue;
                }
                if (ch == '/' && c + 1 < line.length() && line.charAt(c + 1) == '/') {
                    break;
                }
                if (ch == '/' && c + 1 < line.length() && line.charAt(c + 1) == '*') {
                    inBlockComment = true;
                    c++;
                    continue;
                }
                if (ch == '"') {
                    inString = true;
                } else if (ch == '\'') {
                    inChar = true;
                } else if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 按"类全限定名 + 行号"读源码，用于异常堆栈栈帧的精确定位。
     * 先在图谱里按类名找真实文件路径，找不到再按全限定名推断源码路径兜底。
     */
    public String readSourceAtClassLine(Long projectId, Long versionId, String className, Integer lineNo, int maxLines) {
        if (isBlank(className) || lineNo == null) {
            return "无定位信息";
        }
        CodeNode located = firstNodeWithFile(
                codeGraphQueryService.searchNodesByClassName(projectId, versionId, className));
        CodeNode node = new CodeNode();
        node.setLineNo(lineNo);
        if (located != null) {
            node.setName(located.getName());
            node.setFilePath(located.getFilePath());
            node.setQualifiedName(located.getQualifiedName());
        } else {
            // 内部类/lambda 的 $ 部分对应同一个源文件，去掉再推断路径
            String stripped = stripInnerClass(className);
            node.setName(stripped);
            node.setQualifiedName(stripped + ".__stackframe");
            node.setFilePath(stripped.replace('.', '/') + ".java");
        }
        return readSnippet(node, projectId, versionId, maxLines);
    }

    private CodeNode firstNodeWithFile(List<CodeNode> nodes) {
        if (nodes == null) {
            return null;
        }
        for (CodeNode node : nodes) {
            if (node.getFilePath() != null) {
                return node;
            }
        }
        return null;
    }

    private String stripInnerClass(String className) {
        int dollar = className.indexOf('$');
        return dollar >= 0 ? className.substring(0, dollar) : className;
    }

    private Path resolveSourcePath(String filePath, String qualifiedName, Long versionId) throws IOException {
        // 收集本次允许触达的根目录，任何解析结果都必须落在其中，杜绝路径遍历
        List<Path> allowedRoots = allowedRoots(versionId);

        Path directPath = resolveExistingPath(filePath);
        if (directPath != null && isWithinAny(allowedRoots, directPath)) {
            return directPath;
        }
        ProjectVersion version = projectService.getVersion(versionId);
        if (version != null && !isBlank(version.getSourcePath())) {
            Path sourceRoot = Paths.get(version.getSourcePath()).toAbsolutePath().normalize();
            Path fromVersionRoot = resolveFromSourceRoot(sourceRoot, filePath, qualifiedName);
            if (fromVersionRoot != null && isWithin(sourceRoot, fromVersionRoot)) {
                return fromVersionRoot;
            }
        }
        Path backendRoot = Paths.get("backend").toAbsolutePath().normalize();
        Path backendPath = backendRoot.resolve(filePath).normalize();
        if (Files.exists(backendPath) && isWithin(backendRoot, backendPath)) {
            return backendPath;
        }
        throw new IOException("无法定位源码文件: " + filePath);
    }

    /**
     * 本次执行允许读取的源码根目录：项目版本源码目录 + backend 自身目录。
     */
    private List<Path> allowedRoots(Long versionId) {
        List<Path> roots = new ArrayList<Path>();
        ProjectVersion version = projectService.getVersion(versionId);
        if (version != null && !isBlank(version.getSourcePath())) {
            roots.add(Paths.get(version.getSourcePath()).toAbsolutePath().normalize());
        }
        roots.add(Paths.get("backend").toAbsolutePath().normalize());
        return roots;
    }

    private boolean isWithinAny(List<Path> roots, Path target) {
        for (Path root : roots) {
            if (isWithin(root, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithin(Path root, Path target) {
        return target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    private Path resolveExistingPath(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Path absolute = path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
            return Files.exists(absolute) ? absolute : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private Path resolveFromSourceRoot(Path sourceRoot, String filePath, String qualifiedName) {
        String normalized = filePath.replace('\\', '/');
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(sourceRoot.resolve(filePath));
        int javaIndex = normalized.indexOf("src/main/java/");
        if (javaIndex >= 0) {
            candidates.add(sourceRoot.resolve(normalized.substring(javaIndex + "src/main/java/".length())));
        }
        int resourceIndex = normalized.indexOf("src/main/resources/");
        if (resourceIndex >= 0) {
            candidates.add(sourceRoot.resolve(normalized.substring(resourceIndex + "src/main/resources/".length())));
        }
        String className = classQualifiedName(qualifiedName);
        if (!isBlank(className)) {
            candidates.add(sourceRoot.resolve("src/main/java").resolve(className.replace('.', '/') + ".java"));
        }
        for (Path candidate : candidates) {
            Path normalizedCandidate = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalizedCandidate)) {
                return normalizedCandidate;
            }
        }
        return null;
    }

    private String classQualifiedName(String qualifiedName) {
        if (isBlank(qualifiedName)) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return qualifiedName;
        }
        return qualifiedName.substring(0, lastDot);
    }
}
