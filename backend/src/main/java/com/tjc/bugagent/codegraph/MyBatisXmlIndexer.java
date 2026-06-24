package com.tjc.bugagent.codegraph;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 索引 MyBatis Mapper XML：解析 select/insert/update/delete 标签，
 * 建 SQL 节点并连到表。解析器禁用外部实体防 XXE。
 *
 * SQL 文本保留完整结构：内联 &lt;include refid&gt; 引用的 &lt;sql&gt; 片段、保留 &lt;if&gt;/&lt;foreach&gt; 等动态标签，
 * 并给节点补上源码行号，让 Agent 既能从 metadata 看全貌、也能按行号读原文，不必反复 grep 拼 SQL。
 */
@Component
public class MyBatisXmlIndexer {

    private static final long MAX_XML_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_XML_FILES = 2000;
    private static final int MAX_INCLUDE_DEPTH = 20;
    // 匹配 <select|insert|update|delete ... id="xxx">，用来定位每条语句在文件里的起始行号
    private static final Pattern STMT_ID_PATTERN =
            Pattern.compile("<(?:select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"");

    private final CodeGraphWriter writer;
    private final SqlSupport sqlSupport;

    public MyBatisXmlIndexer(CodeGraphWriter writer, SqlSupport sqlSupport) {
        this.writer = writer;
        this.sqlSupport = sqlSupport;
    }

    public void index(Long projectId, Long versionId, Path sourceRoot) throws Exception {
        List<Path> xmlFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            xmlFiles = stream
                    .filter(IndexPaths::isIndexablePath)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .filter(path -> IndexPaths.isBelowSize(path, MAX_XML_FILE_BYTES))
                    .limit(MAX_XML_FILES)
                    .collect(Collectors.toList());
        }
        for (Path file : xmlFiles) {
            try {
                Document document = secureXmlFactory().newDocumentBuilder().parse(file.toFile());
                Element mapper = document.getDocumentElement();
                if (!"mapper".equals(mapper.getTagName())) {
                    continue;
                }
                String namespace = mapper.getAttribute("namespace");
                // 先收集全部 <sql id> 片段，供 <include> 内联（片段可能定义在引用语句之后，故预扫一遍）
                Map<String, Element> fragments = collectSqlFragments(mapper);
                Map<String, Integer> idLines = scanStatementLines(file);
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
                    // 内联 include + 保留动态标签的完整 SQL，再压平空白
                    String sql = sqlSupport.compactSql(serializeWithIncludes(element, fragments, 0));
                    Integer line = idLines.get(id);
                    Long sqlNodeId = writer.addNode(projectId, versionId, "SQL", id, namespace + "." + id, file, line, sqlSupport.json("sql", sql));
                    Long mapperNodeId = writer.addNode(projectId, versionId, "MAPPER_METHOD", id, namespace + "." + id, file, line, "{}");
                    writer.addEdge(projectId, versionId, mapperNodeId, sqlNodeId, "MAPPER_TO_SQL", "{}");
                    for (String table : sqlSupport.extractTables(sql)) {
                        Long tableNodeId = writer.addNode(projectId, versionId, "DB_TABLE", table, table, file, null, "{}");
                        writer.addEdge(projectId, versionId, sqlNodeId, tableNodeId, "SQL_TO_TABLE", "{}");
                    }
                    sqlSupport.tryParseSql(sql);
                }
            } catch (Exception ignored) {
                writer.addNode(projectId, versionId, "XML_PARSE_ERROR", file.getFileName().toString(), file.toString(), file, null, "{}");
            }
        }
    }

    /** 收集 mapper 下所有带 id 的 &lt;sql&gt; 片段，键为 id，供 &lt;include&gt; 内联。 */
    private Map<String, Element> collectSqlFragments(Element mapper) {
        Map<String, Element> fragments = new HashMap<String, Element>();
        NodeList children = mapper.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (!(children.item(index) instanceof Element)) {
                continue;
            }
            Element element = (Element) children.item(index);
            if ("sql".equals(element.getTagName()) && !element.getAttribute("id").isEmpty()) {
                fragments.put(element.getAttribute("id"), element);
            }
        }
        return fragments;
    }

    /**
     * 序列化语句子树为完整 SQL：&lt;include&gt; 内联对应 &lt;sql&gt; 片段，&lt;if&gt;/&lt;foreach&gt; 等动态标签原样保留，
     * 让模型能读懂"什么条件下拼什么子句"，无需再去 grep 还原。
     */
    private String serializeWithIncludes(Node node, Map<String, Element> fragments, int depth) {
        StringBuilder builder = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                builder.append(child.getTextContent());
                continue;
            }
            if (type != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            if ("include".equals(element.getTagName())) {
                Element fragment = fragments.get(element.getAttribute("refid"));
                if (fragment != null && depth < MAX_INCLUDE_DEPTH) {
                    builder.append(serializeWithIncludes(fragment, fragments, depth + 1));
                }
                continue;
            }
            // 动态标签保留 tag + 关键属性（test/collection/separator 等），模型据此理解拼接逻辑
            builder.append('<').append(element.getTagName());
            NamedNodeMap attributes = element.getAttributes();
            for (int a = 0; a < attributes.getLength(); a++) {
                Node attr = attributes.item(a);
                builder.append(' ').append(attr.getNodeName()).append("=\"").append(attr.getNodeValue()).append('"');
            }
            builder.append('>');
            builder.append(serializeWithIncludes(element, fragments, depth));
            builder.append("</").append(element.getTagName()).append('>');
        }
        return builder.toString();
    }

    /** 扫描文件文本，定位每条 select/insert/update/delete 语句的起始行号（按 id 索引）。 */
    private Map<String, Integer> scanStatementLines(Path file) {
        Map<String, Integer> idLines = new HashMap<String, Integer>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return idLines;
        }
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = STMT_ID_PATTERN.matcher(lines.get(index));
            while (matcher.find()) {
                idLines.putIfAbsent(matcher.group(1), index + 1);
            }
        }
        return idLines;
    }

    /**
     * 构造禁用外部实体和 DTD 的 XML 工厂，防 XXE。解析用户上传的 Mapper 必须走这里。
     */
    private DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // MyBatis Mapper 头部带 DOCTYPE，不能禁 DOCTYPE，只切断外部实体/DTD 加载即可防 XXE
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private boolean isSqlTag(String tagName) {
        return "select".equals(tagName) || "insert".equals(tagName) || "update".equals(tagName) || "delete".equals(tagName);
    }
}
