package com.tjc.bugagent.codegraph;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 源码遍历时的路径过滤：跳过构建产物/版本控制等无关目录，并限制单文件大小。
 */
final class IndexPaths {

    private IndexPaths() {
    }

    /** 排除 .git/target/out/logs/node_modules/.idea/.serena 等无需索引的目录。 */
    static boolean isIndexablePath(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase();
        return !normalized.contains("/.git/")
                && !normalized.contains("/target/")
                && !normalized.contains("/out/")
                && !normalized.contains("/logs/")
                && !normalized.contains("/node_modules/")
                && !normalized.contains("/.idea/")
                && !normalized.contains("/.serena/");
    }

    /** 普通文件且不超过上限才索引，避免超大文件拖垮解析。 */
    static boolean isBelowSize(Path path, long maxBytes) {
        try {
            return Files.isRegularFile(path) && Files.size(path) <= maxBytes;
        } catch (Exception ignored) {
            return false;
        }
    }
}
