package com.tjc.bugagent.codegraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 构建 Java 和 MyBatis XML 的轻量代码图谱。
 */
@Service
public class CodeGraphIndexService {
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(from|join|update|into)\\s+([`\\w.]+)");
    private static final long MAX_JAVA_FILE_BYTES = 800 * 1024;
    private static final long MAX_XML_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_JAVA_FILES = 1200;
    private static final int MAX_XML_FILES = 2000;
    /** 单个文件内符号解析连续失败到这个数，该文件剩余调用直接降级。 */
    private static final int SYMBOL_FAIL_PER_FILE = 15;
    /** 整个版本符号解析累计失败到这个数，全局关闭符号求解，后续全部走启发式。 */
    private static final int SYMBOL_FAIL_GLOBAL = 400;
    private final JdbcTemplate jdbcTemplate;
    private final CodeGraphRepository codeGraphRepository;

    public CodeGraphIndexService(JdbcTemplate jdbcTemplate, CodeGraphRepository codeGraphRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGraphRepository = codeGraphRepository;
    }

    /**
     * 异步索引指定版本源码。
     */
    @Async
    public void indexAsync(Long projectId, Long versionId, Path sourceRoot) {
        try {
            jdbcTemplate.update("update project_version set index_status = 'INDEXING', index_started_at = now(), index_message = null where id = ?", versionId);
            codeGraphRepository.clearVersion(projectId, versionId);
            jdbcTemplate.update("update project_version set index_message = 'indexing java source' where id = ?", versionId);
            JavaParser javaParser = createSymbolParser(sourceRoot);
            indexJava(projectId, versionId, sourceRoot, javaParser);
            jdbcTemplate.update("update project_version set index_message = 'indexing mybatis xml' where id = ?", versionId);
            indexMyBatisXml(projectId, versionId, sourceRoot);
            jdbcTemplate.update("update project_version set index_message = 'linking mapper sql' where id = ?", versionId);
            linkMapperMethods(projectId, versionId);
            jdbcTemplate.update("update project_version set index_status = 'SUCCESS', indexed_at = now(), index_message = 'index completed' where id = ?", versionId);
        } catch (Exception exception) {
            jdbcTemplate.update("update project_version set index_status = 'FAILED', index_message = ? where id = ?", trim(exception.getMessage(), 3000), versionId);
        }
    }

    /**
     * 构建带符号求解的 JavaParser，能解析方法调用的真实声明类型，
     * 解决接口名/实现名对不上导致调用链断裂的问题。
     */
    private JavaParser createSymbolParser(Path sourceRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        if (sourceRoot != null) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot.toFile()));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        return new JavaParser(configuration);
    }

    private void indexJava(Long projectId, Long versionId, Path sourceRoot, JavaParser javaParser) throws Exception {
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            javaFiles = stream
                    .filter(this::isIndexablePath)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> isBelowSize(path, MAX_JAVA_FILE_BYTES))
                    .limit(MAX_JAVA_FILES)
                    .collect(Collectors.toList());
        }
        JavaIndexState state = new JavaIndexState();
        // 符号解析熔断器，累计失败超阈值后全局降级，避免三方库类型把索引拖垮
        SymbolResolveGuard guard = new SymbolResolveGuard();
        int parsedCount = 0;
        for (Path file : javaFiles) {
            try {
                parsedCount++;
                if (parsedCount % 50 == 0) {
                    jdbcTemplate.update("update project_version set index_message = ? where id = ?", "indexing java source " + parsedCount + "/" + javaFiles.size(), versionId);
                }
                indexJavaFile(projectId, versionId, file, javaParser, state, guard);
            } catch (Exception ignored) {
                addNode(projectId, versionId, "PARSE_ERROR", file.getFileName().toString(), file.toString(), file, null, "{}");
            }
        }
        linkKnownCalls(projectId, versionId, state);
    }

    private void indexJavaFile(Long projectId, Long versionId, Path file, JavaParser javaParser, JavaIndexState state, SymbolResolveGuard guard) throws Exception {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
        if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
            throw new IllegalStateException("parse failed: " + parseResult.getProblems());
        }
        CompilationUnit unit = parseResult.getResult().get();
        String packageName = unit.getPackageDeclaration().map(item -> item.getName().asString()).orElse("");
        for (ClassOrInterfaceDeclaration clazz : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = clazz.getNameAsString();
            String qualifiedClass = packageName.isEmpty() ? className : packageName + "." + className;
            Long classNodeId = addNode(projectId, versionId, "CLASS", className, qualifiedClass, file, clazz.getBegin().map(p -> p.line).orElse(null), "{}");
            state.classNodeIds.put(qualifiedClass, classNodeId);
            state.classNodeIds.put(className, classNodeId);
            Map<String, String> fieldTypes = collectFieldTypes(clazz, packageName);
            // 每个类重置本类失败计数，避免一个类的噪音连累同类其他方法
            guard.beginClass();
            for (MethodDeclaration method : clazz.getMethods()) {
                String methodName = method.getNameAsString();
                String qualifiedMethod = qualifiedClass + "." + methodName;
                Long methodNodeId = addNode(projectId, versionId, "METHOD", methodName, qualifiedMethod, file, method.getBegin().map(p -> p.line).orElse(null), "{}");
                addMethodIndex(state, methodName, qualifiedClass, qualifiedMethod, methodNodeId);
                addEdge(projectId, versionId, classNodeId, methodNodeId, "CLASS_HAS_METHOD", "{}");
                addRouteNodes(projectId, versionId, clazz, method, methodNodeId, file);
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    String symbolOwner = resolveSymbolOwnerType(call, guard);
                    String heuristicOwner = symbolOwner == null ? resolveCallOwnerType(call, fieldTypes, packageName) : symbolOwner;
                    state.pendingCalls.add(new PendingCall(methodNodeId, call.getNameAsString(), heuristicOwner, qualifiedClass));
                }
            }
        }
    }

    /**
     * 用符号求解器解析方法调用的声明类型，拿到接口/类全限定名。
     * 失败（JDK、三方库、动态调用）返回 null 交回启发式兜底；累计失败超阈值后直接跳过，
     * 防止依赖缺失的类把符号求解器拖慢甚至拖垮整个索引。
     */
    private String resolveSymbolOwnerType(MethodCallExpr call, SymbolResolveGuard guard) {
        if (guard.isDisabled()) {
            return null;
        }
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            ResolvedReferenceTypeDeclaration declaring = resolved.declaringType();
            return declaring == null ? null : declaring.getQualifiedName();
        } catch (Exception ignored) {
            guard.recordFailure();
            return null;
        }
    }

    private Map<String, String> collectFieldTypes(ClassOrInterfaceDeclaration clazz, String packageName) {
        Map<String, String> fieldTypes = new HashMap<String, String>();
        for (FieldDeclaration field : clazz.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                fieldTypes.put(variable.getNameAsString(), qualifyType(variable.getType().asString(), packageName));
            }
        }
        return fieldTypes;
    }

    private String resolveCallOwnerType(MethodCallExpr call, Map<String, String> fieldTypes, String packageName) {
        if (!call.getScope().isPresent()) {
            return null;
        }
        String scope = call.getScope().get().toString();
        String fieldType = fieldTypes.get(scope);
        if (fieldType != null) {
            return fieldType;
        }
        if (!scope.isEmpty() && Character.isUpperCase(scope.charAt(0))) {
            return qualifyType(scope, packageName);
        }
        return null;
    }

    private String qualifyType(String typeName, String packageName) {
        String clean = typeName.replaceAll("<.*>", "").replace("[]", "").trim();
        if (clean.contains(".")) {
            return clean;
        }
        return packageName == null || packageName.isEmpty() ? clean : packageName + "." + clean;
    }

    private void addMethodIndex(JavaIndexState state, String methodName, String qualifiedClass, String qualifiedMethod, Long methodNodeId) {
        addToMapList(state.methodsByName, methodName, methodNodeId);
        addToMapList(state.methodsByQualifiedClassAndName, qualifiedClass + "#" + methodName, methodNodeId);
        addToMapList(state.methodsByQualifiedClassAndName, simpleName(qualifiedClass) + "#" + methodName, methodNodeId);
        state.methodsByQualifiedName.put(qualifiedMethod, methodNodeId);
    }

    private void addToMapList(Map<String, List<Long>> map, String key, Long value) {
        List<Long> values = map.get(key);
        if (values == null) {
            values = new ArrayList<Long>();
            map.put(key, values);
        }
        values.add(value);
    }

    private void linkKnownCalls(Long projectId, Long versionId, JavaIndexState state) {
        List<CodeGraphRepository.EdgeInsert> edges = new ArrayList<CodeGraphRepository.EdgeInsert>();
        for (PendingCall call : state.pendingCalls) {
            List<Long> targetNodes = resolveCallTargets(call, state);
            for (Long targetNodeId : targetNodes) {
                if (!call.getSourceMethodId().equals(targetNodeId)) {
                    edges.add(new CodeGraphRepository.EdgeInsert(
                            projectId, versionId, call.getSourceMethodId(), targetNodeId, "METHOD_CALLS_METHOD", "{}"));
                }
            }
        }
        codeGraphRepository.batchInsertEdges(edges);
    }

    private List<Long> resolveCallTargets(PendingCall call, JavaIndexState state) {
        if (call.getOwnerType() != null) {
            List<Long> scopedTargets = state.methodsByQualifiedClassAndName.get(call.getOwnerType() + "#" + call.getTargetMethodName());
            if (scopedTargets != null) {
                return scopedTargets;
            }
            String simpleOwner = simpleName(call.getOwnerType());
            List<Long> simpleTargets = state.methodsByQualifiedClassAndName.get(simpleOwner + "#" + call.getTargetMethodName());
            if (simpleTargets != null) {
                return simpleTargets;
            }
        }
        List<Long> sameClassTargets = state.methodsByQualifiedClassAndName.get(call.getSourceClassName() + "#" + call.getTargetMethodName());
        if (sameClassTargets != null) {
            return sameClassTargets;
        }
        List<Long> targets = state.methodsByName.get(call.getTargetMethodName());
        if (targets == null || targets.size() > 8) {
            return new ArrayList<Long>();
        }
        return targets;
    }

    private void addRouteNodes(Long projectId, Long versionId, ClassOrInterfaceDeclaration clazz, MethodDeclaration method, Long methodNodeId, Path file) {
        List<String> classPaths = mappingValues(clazz.getAnnotations());
        List<String> methodPaths = mappingValues(method.getAnnotations());
        if (methodPaths.isEmpty()) {
            return;
        }
        if (classPaths.isEmpty()) {
            classPaths.add("");
        }
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                String apiPath = normalizePath(classPath, methodPath);
                Long routeNodeId = addNode(projectId, versionId, "API_ROUTE", apiPath, apiPath, file, method.getBegin().map(p -> p.line).orElse(null), "{}");
                addEdge(projectId, versionId, routeNodeId, methodNodeId, "ROUTE_TO_METHOD", "{}");
            }
        }
    }

    private List<String> mappingValues(List<AnnotationExpr> annotations) {
        List<String> values = new ArrayList<String>();
        for (AnnotationExpr annotation : annotations) {
            String name = annotation.getNameAsString();
            if (!name.endsWith("Mapping")) {
                continue;
            }
            Matcher matcher = Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(annotation.toString());
            if (matcher.find()) {
                values.add(matcher.group(1));
            } else if (name.equals("GetMapping") || name.equals("PostMapping") || name.equals("PutMapping") || name.equals("DeleteMapping")) {
                values.add("");
            }
        }
        return values;
    }

    private String normalizePath(String classPath, String methodPath) {
        String joined = ("/" + safe(classPath) + "/" + safe(methodPath)).replaceAll("/++", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    private void indexMyBatisXml(Long projectId, Long versionId, Path sourceRoot) throws Exception {
        List<Path> xmlFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            xmlFiles = stream
                    .filter(this::isIndexablePath)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .filter(path -> isBelowSize(path, MAX_XML_FILE_BYTES))
                    .limit(MAX_XML_FILES)
                    .collect(Collectors.toList());
        }
        for (Path file : xmlFiles) {
            try {
                Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
                Element mapper = document.getDocumentElement();
                if (!"mapper".equals(mapper.getTagName())) {
                    continue;
                }
                String namespace = mapper.getAttribute("namespace");
                NodeList children = mapper.getChildNodes();
                for (int index = 0; index < children.getLength(); index++) {
                    if (!(children.item(index) instanceof Element)) {
                        continue;
                    }
                    Element element = (Element) children.item(index);
                    if (!isSqlTag(element.getTagName())) {
                        continue;
                    }
                    String id = element.getAttribute("id");
                    String sql = compactSql(element.getTextContent());
                    Long sqlNodeId = addNode(projectId, versionId, "SQL", id, namespace + "." + id, file, null, json("sql", sql));
                    Long mapperNodeId = addNode(projectId, versionId, "MAPPER_METHOD", id, namespace + "." + id, file, null, "{}");
                    addEdge(projectId, versionId, mapperNodeId, sqlNodeId, "MAPPER_TO_SQL", "{}");
                    for (String table : extractTables(sql)) {
                        Long tableNodeId = addNode(projectId, versionId, "DB_TABLE", table, table, file, null, "{}");
                        addEdge(projectId, versionId, sqlNodeId, tableNodeId, "SQL_TO_TABLE", "{}");
                    }
                    tryParseSql(sql);
                }
            } catch (Exception ignored) {
                addNode(projectId, versionId, "XML_PARSE_ERROR", file.getFileName().toString(), file.toString(), file, null, "{}");
            }
        }
    }

    private void linkMapperMethods(Long projectId, Long versionId) {
        List<CodeGraphRepository.MapperNodeRef> mapperLinks = codeGraphRepository.findMapperNodes(projectId, versionId);
        List<CodeGraphRepository.EdgeInsert> edges = new ArrayList<CodeGraphRepository.EdgeInsert>();
        for (CodeGraphRepository.MapperNodeRef mapperLink : mapperLinks) {
            String mapperInterface = ownerClass(mapperLink.getQualifiedName());
            // 优先按 namespace.id 精确匹配 Java 侧调用接口方法
            Long exactId = codeGraphRepository.findMethodIdByQualifiedName(
                    projectId, versionId, mapperInterface + "." + mapperLink.getName());
            if (exactId != null) {
                edges.add(new CodeGraphRepository.EdgeInsert(
                        projectId, versionId, exactId, mapperLink.getId(), "METHOD_TO_MAPPER", "{}"));
                continue;
            }
            // 退回按方法名匹配，连到所有同名方法
            for (Long methodId : codeGraphRepository.findMethodIdsByName(projectId, versionId, mapperLink.getName(), 20)) {
                edges.add(new CodeGraphRepository.EdgeInsert(
                        projectId, versionId, methodId, mapperLink.getId(), "METHOD_TO_MAPPER", "{}"));
            }
        }
        codeGraphRepository.batchInsertEdges(edges);
    }

    private String ownerClass(String qualifiedName) {
        if (qualifiedName == null) {
            return "";
        }
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(0, index);
    }

    private String simpleName(String qualifiedName) {
        if (qualifiedName == null) {
            return "";
        }
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
    }

    private boolean isSqlTag(String tagName) {
        return "select".equals(tagName) || "insert".equals(tagName) || "update".equals(tagName) || "delete".equals(tagName);
    }

    private boolean isIndexablePath(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase();
        return !normalized.contains("/.git/")
                && !normalized.contains("/target/")
                && !normalized.contains("/out/")
                && !normalized.contains("/logs/")
                && !normalized.contains("/node_modules/")
                && !normalized.contains("/.idea/")
                && !normalized.contains("/.serena/");
    }

    private boolean isBelowSize(Path path, long maxBytes) {
        try {
            return Files.isRegularFile(path) && Files.size(path) <= maxBytes;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Set<String> extractTables(String sql) {
        Set<String> tables = new HashSet<String>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(2).replace("`", ""));
        }
        return tables;
    }

    private void tryParseSql(String sql) {
        try {
            String normalized = sql.replaceAll("#\\{[^}]+}", "1").replaceAll("\\$\\{[^}]+}", "x");
            Statement ignored = CCJSqlParserUtil.parse(normalized);
        } catch (Exception ignored) {
            // 动态 MyBatis SQL 仍会通过正则提取表名。
        }
    }

    private Long addNode(Long projectId, Long versionId, String type, String name, String qualifiedName, Path file, Integer lineNo, String metadataJson) {
        return codeGraphRepository.insertNode(new CodeGraphRepository.NodeInsert(
                projectId, versionId, type, trim(name, 250), trim(qualifiedName, 760),
                file == null ? null : trim(file.toString(), 760), lineNo, metadataJson));
    }

    private void addEdge(Long projectId, Long versionId, Long fromNodeId, Long toNodeId, String edgeType, String metadataJson) {
        codeGraphRepository.batchInsertEdges(java.util.Collections.singletonList(
                new CodeGraphRepository.EdgeInsert(projectId, versionId, fromNodeId, toNodeId, edgeType, metadataJson)));
    }

    private String compactSql(String value) {
        return trim(value == null ? "" : value.replaceAll("\\s+", " ").trim(), 10000);
    }

    private String json(String key, String value) {
        return "{\"" + key + "\":\"" + trim(value, 3000).replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 符号解析熔断器：跟踪单类失败次数和全局累计失败次数。
     * 单类超阈值降级该类，全局超阈值彻底关闭符号求解，回退启发式。
     */
    private static final class SymbolResolveGuard {
        private int classFailures;
        private int globalFailures;
        private boolean globallyDisabled;

        /** 进入新类时清零本类计数。 */
        void beginClass() {
            classFailures = 0;
        }

        /** 记一次解析失败，累计触发阈值后置位全局降级。 */
        void recordFailure() {
            classFailures++;
            globalFailures++;
            if (classFailures >= SYMBOL_FAIL_PER_FILE || globalFailures >= SYMBOL_FAIL_GLOBAL) {
                globallyDisabled = true;
            }
        }

        /** 是否已降级，true 时调用方应直接跳过符号解析。 */
        boolean isDisabled() {
            return globallyDisabled;
        }
    }

    private static class JavaIndexState {
        private final Map<String, Long> classNodeIds = new HashMap<String, Long>();
        private final Map<String, Long> methodsByQualifiedName = new HashMap<String, Long>();
        private final Map<String, List<Long>> methodsByQualifiedClassAndName = new HashMap<String, List<Long>>();
        private final Map<String, List<Long>> methodsByName = new HashMap<String, List<Long>>();
        private final List<PendingCall> pendingCalls = new ArrayList<PendingCall>();
    }

    private static class PendingCall {
        private final Long sourceMethodId;
        private final String targetMethodName;
        private final String ownerType;
        private final String sourceClassName;

        private PendingCall(Long sourceMethodId, String targetMethodName, String ownerType, String sourceClassName) {
            this.sourceMethodId = sourceMethodId;
            this.targetMethodName = targetMethodName;
            this.ownerType = ownerType;
            this.sourceClassName = sourceClassName;
        }

        public Long getSourceMethodId() {
            return sourceMethodId;
        }

        public String getTargetMethodName() {
            return targetMethodName;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getSourceClassName() {
            return sourceClassName;
        }
    }
}


