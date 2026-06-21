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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 索引 Java 源码：建类/方法/路由节点，解析方法调用连出调用链，
 * 顺带提取 Mapper 接口上的注解 SQL。符号求解带熔断，依赖缺失也不会拖垮整轮。
 */
@Component
public class JavaSourceIndexer {

    private static final long MAX_JAVA_FILE_BYTES = 800 * 1024;
    private static final int MAX_JAVA_FILES = 1200;
    /** 单个文件内符号解析连续失败到这个数，该文件剩余调用直接降级。 */
    private static final int SYMBOL_FAIL_PER_FILE = 15;
    /** 整个版本符号解析累计失败到这个数，全局关闭符号求解，后续全部走启发式。 */
    private static final int SYMBOL_FAIL_GLOBAL = 400;

    private final CodeGraphWriter writer;
    private final SqlSupport sqlSupport;

    public JavaSourceIndexer(CodeGraphWriter writer, SqlSupport sqlSupport) {
        this.writer = writer;
        this.sqlSupport = sqlSupport;
    }

    /**
     * 索引整个版本的 Java 源码。progress 用于回报"已处理 N/总数"，由调用方落库展示。
     */
    public void index(Long projectId, Long versionId, Path sourceRoot, Consumer<String> progress) throws Exception {
        JavaParser javaParser = createSymbolParser(sourceRoot);
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            javaFiles = stream
                    .filter(IndexPaths::isIndexablePath)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> IndexPaths.isBelowSize(path, MAX_JAVA_FILE_BYTES))
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
                    progress.accept("indexing java source " + parsedCount + "/" + javaFiles.size());
                }
                indexJavaFile(projectId, versionId, file, javaParser, state, guard);
            } catch (Exception ignored) {
                writer.addNode(projectId, versionId, "PARSE_ERROR", file.getFileName().toString(), file.toString(), file, null, "{}");
            }
        }
        linkKnownCalls(projectId, versionId, state);
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
                // 用高版本语言级，兼容 var/record/switch 表达式等新语法；旧代码向下兼容。
                // 锁死 JAVA_8 会让用了新语法的文件直接 parse 失败、整文件丢失
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(configuration);
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
            Long classNodeId = writer.addNode(projectId, versionId, "CLASS", className, qualifiedClass, file, clazz.getBegin().map(p -> p.line).orElse(null), "{}");
            state.classNodeIds.put(qualifiedClass, classNodeId);
            state.classNodeIds.put(className, classNodeId);
            // 记下实现/继承关系，链接调用时把接口/父类调用展开到实现类、子类
            for (ClassOrInterfaceType implemented : clazz.getImplementedTypes()) {
                addImplementor(state, implemented.getNameAsString(), qualifiedClass);
            }
            for (ClassOrInterfaceType extended : clazz.getExtendedTypes()) {
                addImplementor(state, extended.getNameAsString(), qualifiedClass);
            }
            Map<String, String> fieldTypes = collectFieldTypes(clazz, packageName);
            // 每个类重置本类失败计数，避免一个类的噪音连累同类其他方法
            guard.beginClass();
            for (MethodDeclaration method : clazz.getMethods()) {
                String methodName = method.getNameAsString();
                String qualifiedMethod = qualifiedClass + "." + methodName;
                Long methodNodeId = writer.addNode(projectId, versionId, "METHOD", methodName, qualifiedMethod, file, method.getBegin().map(p -> p.line).orElse(null), "{}");
                addMethodIndex(state, methodName, qualifiedClass, qualifiedMethod, methodNodeId);
                writer.addEdge(projectId, versionId, classNodeId, methodNodeId, "CLASS_HAS_METHOD", "{}");
                addRouteNodes(projectId, versionId, clazz, method, methodNodeId, file);
                // MyBatis 注解 SQL（@Select 等）也建 SQL 节点，linkMapperMethods 会按全限定名把方法连过来
                indexAnnotationSql(projectId, versionId, method, qualifiedMethod, file);
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

    private void addImplementor(JavaIndexState state, String superSimpleName, String implQualified) {
        List<String> impls = state.implementorsBySimpleName.get(superSimpleName);
        if (impls == null) {
            impls = new ArrayList<String>();
            state.implementorsBySimpleName.put(superSimpleName, impls);
        }
        if (!impls.contains(implQualified)) {
            impls.add(implQualified);
        }
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
        writer.batchInsertEdges(edges);
    }

    private List<Long> resolveCallTargets(PendingCall call, JavaIndexState state) {
        String method = call.getTargetMethodName();
        if (call.getOwnerType() != null) {
            // owner 自身 + 它的实现/子类，跨过接口抽象方法连到有方法体的实现，链路才能到 SQL
            LinkedHashSet<Long> targets = new LinkedHashSet<Long>();
            collectByOwner(state, call.getOwnerType(), method, targets);
            for (String impl : implementorsOf(state, call.getOwnerType())) {
                collectByOwner(state, impl, method, targets);
            }
            if (!targets.isEmpty()) {
                return new ArrayList<Long>(targets);
            }
        }
        List<Long> sameClassTargets = state.methodsByQualifiedClassAndName.get(call.getSourceClassName() + "#" + method);
        if (sameClassTargets != null) {
            return sameClassTargets;
        }
        List<Long> targets = state.methodsByName.get(method);
        if (targets == null || targets.size() > 8) {
            return new ArrayList<Long>();
        }
        return targets;
    }

    private void collectByOwner(JavaIndexState state, String owner, String method, Set<Long> out) {
        List<Long> scoped = state.methodsByQualifiedClassAndName.get(owner + "#" + method);
        if (scoped != null) {
            out.addAll(scoped);
        }
        List<Long> simple = state.methodsByQualifiedClassAndName.get(simpleName(owner) + "#" + method);
        if (simple != null) {
            out.addAll(simple);
        }
    }

    private List<String> implementorsOf(JavaIndexState state, String ownerType) {
        List<String> impls = state.implementorsBySimpleName.get(simpleName(ownerType));
        return impls == null ? Collections.<String>emptyList() : impls;
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
                Long routeNodeId = writer.addNode(projectId, versionId, "API_ROUTE", apiPath, apiPath, file, method.getBegin().map(p -> p.line).orElse(null), "{}");
                writer.addEdge(projectId, versionId, routeNodeId, methodNodeId, "ROUTE_TO_METHOD", "{}");
            }
        }
    }

    private List<String> mappingValues(List<AnnotationExpr> annotations) {
        List<String> values = new ArrayList<String>();
        for (AnnotationExpr annotation : annotations) {
            if (!annotation.getNameAsString().endsWith("Mapping")) {
                continue;
            }
            values.addAll(extractMappingPaths(annotation));
        }
        return values;
    }

    /**
     * 从 *Mapping 注解里取真实路由路径：明确读 value/path 属性，支持数组多路径。
     * 避免老逻辑盲取第一个字符串字面量（会把 produces 等属性误当路径）。
     */
    private List<String> extractMappingPaths(AnnotationExpr annotation) {
        List<String> paths = new ArrayList<String>();
        if (annotation.isSingleMemberAnnotationExpr()) {
            collectStringValues(annotation.asSingleMemberAnnotationExpr().getMemberValue(), paths);
        } else if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String pairName = pair.getNameAsString();
                if ("value".equals(pairName) || "path".equals(pairName)) {
                    collectStringValues(pair.getValue(), paths);
                }
            }
        }
        // 裸用 @GetMapping（无路径）也给一个空路径，让它挂到类级路径上
        if (paths.isEmpty()) {
            paths.add("");
        }
        return paths;
    }

    /**
     * 收集表达式里的字符串字面量，数组形式逐个展开。
     */
    private void collectStringValues(Expression expression, List<String> out) {
        if (expression == null) {
            return;
        }
        if (expression.isStringLiteralExpr()) {
            out.add(expression.asStringLiteralExpr().getValue());
        } else if (expression.isArrayInitializerExpr()) {
            for (Expression item : expression.asArrayInitializerExpr().getValues()) {
                collectStringValues(item, out);
            }
        }
    }

    private String normalizePath(String classPath, String methodPath) {
        String joined = ("/" + CodeGraphText.safe(classPath) + "/" + CodeGraphText.safe(methodPath)).replaceAll("/++", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    /**
     * 索引 Mapper 接口方法上的注解 SQL（@Select/@Insert/@Update/@Delete）。
     * 建的节点结构与 XML 一致，复用 linkMapperMethods 把 Java 方法连到 SQL，再连到表。
     * 注：@SelectProvider 等动态构造 SQL 的注解无法静态提取，跳过。
     */
    private void indexAnnotationSql(Long projectId, Long versionId, MethodDeclaration method, String qualifiedMethod, Path file) {
        String sql = extractAnnotationSql(method);
        if (sql == null) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String name = method.getNameAsString();
        Long sqlNodeId = writer.addNode(projectId, versionId, "SQL", name, qualifiedMethod, file, line, sqlSupport.json("sql", sql));
        Long mapperNodeId = writer.addNode(projectId, versionId, "MAPPER_METHOD", name, qualifiedMethod, file, line, "{}");
        writer.addEdge(projectId, versionId, mapperNodeId, sqlNodeId, "MAPPER_TO_SQL", "{}");
        for (String table : sqlSupport.extractTables(sql)) {
            Long tableNodeId = writer.addNode(projectId, versionId, "DB_TABLE", table, table, file, null, "{}");
            writer.addEdge(projectId, versionId, sqlNodeId, tableNodeId, "SQL_TO_TABLE", "{}");
        }
    }

    private String extractAnnotationSql(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if (!"Select".equals(name) && !"Insert".equals(name) && !"Update".equals(name) && !"Delete".equals(name)) {
                continue;
            }
            List<String> parts = new ArrayList<String>();
            if (annotation.isSingleMemberAnnotationExpr()) {
                collectStringValues(annotation.asSingleMemberAnnotationExpr().getMemberValue(), parts);
            } else if (annotation.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if ("value".equals(pair.getNameAsString())) {
                        collectStringValues(pair.getValue(), parts);
                    }
                }
            }
            if (!parts.isEmpty()) {
                return sqlSupport.compactSql(String.join(" ", parts));
            }
        }
        return null;
    }

    private String simpleName(String qualifiedName) {
        if (qualifiedName == null) {
            return "";
        }
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
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
        // 接口/父类简单名 → 实现类/子类全限定名列表，把接口调用展开到实现，跨过抽象方法直达 SQL
        private final Map<String, List<String>> implementorsBySimpleName = new HashMap<String, List<String>>();
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
