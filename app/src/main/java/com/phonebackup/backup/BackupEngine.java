package com.phonebackup.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份引擎：把扫描到的文件复制到 BackupTarget。
 * 特性：增量跳过、空间预检、单文件失败不中断、可取消、写入校验。
 */
public class BackupEngine {

    private final Context context;
    private final BackupTarget target;
    private final BackupListener listener;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    public BackupEngine(Context context, BackupTarget target, BackupListener listener) {
        this.context = context.getApplicationContext();
        this.target = target;
        this.listener = listener;
    }

    public void requestCancel() {
        cancelFlag.set(true);
    }

    public void run(List<FileScanner.Item> items) {
        BackupResult result = new BackupResult();
        long start = System.currentTimeMillis();
        try {
            target.connect();

            // 计算总量
            long totalBytes = 0;
            for (FileScanner.Item it : items) totalBytes += it.size;
            result.totalFiles = items.size();
            result.totalBytes = totalBytes;
            listener.onScanComplete(items.size(), totalBytes);

            // 空间预检（仅本地目标可知剩余空间）
            long free = target.getFreeSpace();
            if (free >= 0 && free < totalBytes) {
                throw new IOException("目标空间不足：需要 "
                        + BackupResult.formatSize(totalBytes)
                        + "，仅剩 " + BackupResult.formatSize(free));
            }

            int done = 0;
            long doneBytes = 0;
            for (FileScanner.Item it : items) {
                if (cancelFlag.get()) {
                    result.cancelled = true;
                    break;
                }
                done++;
                try {
                    // 增量：已存在且一致则跳过
                    if (target.isUpToDate(it.relativePath, it.size, it.lastModified)) {
                        result.skippedFiles++;
                        doneBytes += it.size;
                        listener.onFileProgress(done, doneBytes, it.relativePath);
                        continue;
                    }

                    // 确保父目录存在
                    int slash = it.relativePath.lastIndexOf('/');
                    if (slash > 0) {
                        target.ensureDir(it.relativePath.substring(0, slash));
                    }

                    copyOne(it);
                    result.successFiles++;
                    result.successBytes += it.size;
                    doneBytes += it.size;
                    listener.onFileProgress(done, doneBytes, it.relativePath);
                } catch (Exception e) {
                    String msg = it.relativePath + "：" + e.getMessage();
                    result.errors.add(msg);
                    listener.onFileError(it.relativePath, e.getMessage());
                }
            }
        } catch (Exception e) {
            result.errors.add("致命错误：" + e.getMessage());
        } finally {
            target.close();
            result.elapsedMillis = System.currentTimeMillis() - start;
            listener.onFinish(result);
        }
    }

    private void copyOne(FileScanner.Item it) throws IOException {
        InputStream in = openInput(it.uri);
        if (in == null) {
            throw new IOException("无法打开源文件");
        }
        try {
            target.writeFile(it.relativePath, in, it.size, it.lastModified);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** 根据 URI 类型打开输入流：content:// 用 ContentResolver，file:// 用 FileInputStream。 */
    private InputStream openInput(Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equals("file")) {
            return new FileInputStream(new File(uri.getPath()));
        }
        if (scheme.equals("content")) {
            ContentResolver cr = context.getContentResolver();
            return cr.openInputStream(uri);
        }
        throw new IOException("不支持的 URI 类型：" + scheme);
    }
}
