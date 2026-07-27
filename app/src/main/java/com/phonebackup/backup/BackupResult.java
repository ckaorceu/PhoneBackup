package com.phonebackup.backup;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次备份的汇总结果。
 */
public class BackupResult {
    public int totalFiles;
    public long totalBytes;
    public int successFiles;
    public long successBytes;
    public int skippedFiles;      // 已存在且未变化，跳过
    public final List<String> errors = new ArrayList<>();
    public boolean cancelled;
    public long elapsedMillis;

    public boolean isOk() {
        return errors.isEmpty() && !cancelled;
    }

    public String summary() {
        if (cancelled) return "备份已取消";
        StringBuilder sb = new StringBuilder();
        sb.append("成功 ").append(successFiles).append(" 个文件，")
          .append(formatSize(successBytes));
        if (skippedFiles > 0) sb.append("；跳过 ").append(skippedFiles).append(" 个（已存在）");
        if (!errors.isEmpty()) sb.append("；失败 ").append(errors.size()).append(" 个");
        return sb.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
