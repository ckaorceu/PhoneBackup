package com.phonebackup.backup;

/**
 * 备份进度与日志回调，由 BackupService 实现，用于更新通知和 UI。
 */
public interface BackupListener {
    /** 扫描阶段：发现待备份文件总数与总字节 */
    void onScanComplete(int totalFiles, long totalBytes);

    /** 每完成一个文件回调 */
    void onFileProgress(int doneFiles, long doneBytes, String currentFile);

    /** 单文件失败（不中断整体） */
    void onFileError(String file, String reason);

    /** 全部结束 */
    void onFinish(BackupResult result);
}
