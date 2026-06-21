package com.tjc.bugagent.codegraph;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 索引 MyBatis Mapper XML：解析 select/insert/update/delete 标签，
 * 建 SQL 节点并连到表。解析器禁用外部实体防 XXE。
 */
@Component
public class MyBatisXmlIndexer {

    private static final long MAX_XML_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_XML_FILES = 2000;

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
                    String sql = sqlSupport.compactSql(element.getTextContent());
                    Long sqlNodeId = writer.addNode(projectId, versionId, "SQL", id, namespace + "." + id, file, null, sqlSupport.json("sql", sql));
                    Long mapperNodeId = writer.addNode(projectId, versionId, "MAPPER_METHOD", id, namespace + "." + id, file, null, "{}");
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
