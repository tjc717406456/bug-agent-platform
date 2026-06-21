package com.tjc.bugagent.codegraph;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码图谱索引的编排入口：推进版本状态，依次跑 Java 索引、MyBatis XML 索引、Mapper 关联。
 * 具体解析逻辑分别交给 JavaSourceIndexer 和 MyBatisXmlIndexer，状态持久化交给 IndexStatusRepository，
 * 这里只管流程和收尾。
 */
@Service
public class CodeGraphIndexService {

    private final CodeGraphRepository codeGraphRepository;
    private final IndexStatusRepository indexStatusRepository;
    private final JavaSourceIndexer javaSourceIndexer;
    private final MyBatisXmlIndexer myBatisXmlIndexer;

    public CodeGraphIndexService(CodeGraphRepository codeGraphRepository, IndexStatusRepository indexStatusRepository,
                                 JavaSourceIndexer javaSourceIndexer, MyBatisXmlIndexer myBatisXmlIndexer) {
        this.codeGraphRepository = codeGraphRepository;
        this.indexStatusRepository = indexStatusRepository;
        this.javaSourceIndexer = javaSourceIndexer;
        this.myBatisXmlIndexer = myBatisXmlIndexer;
    }

    /**
     * 异步索引指定版本源码。
     */
    @Async
    public void indexAsync(Long projectId, Long versionId, Path sourceRoot) {
        try {
            indexStatusRepository.markIndexing(versionId);
            codeGraphRepository.clearVersion(projectId, versionId);
            updateMessage(versionId, "indexing java source");
            javaSourceIndexer.index(projectId, versionId, sourceRoot, message -> updateMessage(versionId, message));
            updateMessage(versionId, "indexing mybatis xml");
            myBatisXmlIndexer.index(projectId, versionId, sourceRoot);
            updateMessage(versionId, "linking mapper sql");
            linkMapperMethods(projectId, versionId);
            indexStatusRepository.markSuccess(versionId);
        } catch (Exception exception) {
            indexStatusRepository.markFailed(versionId, CodeGraphText.trim(exception.getMessage(), 3000));
        }
    }

    private void updateMessage(Long versionId, String message) {
        indexStatusRepository.updateMessage(versionId, message);
    }

    /**
     * 把 Java 侧调用 Mapper 接口方法连到 XML/注解里的 SQL 节点，补全"方法→SQL→表"链路。
     * 先按 namespace.id 精确匹配，匹配不到退回按方法名连到所有同名方法。
     */
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
}
