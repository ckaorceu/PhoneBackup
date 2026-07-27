package com.phonebackup.backup;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 基于 SAF（存储访问框架）的备份目标。
 *
 * 重要：在现代安卓上，普通应用无法直接用文件路径写入“可移动”USB 存储，
 * 系统会拒绝。可靠做法是让用户通过 ACTION_OPEN_DOCUMENT_TREE 选择 USB 盘上的
 * 一个目录并授权，之后用 DocumentFile 读写。这是备份到移动硬盘最稳妥的方式。
 */
public class SafBackupTarget implements BackupTarget {

    private final Context context;
    private final Uri treeUri;
    private DocumentFile root;

    public SafBackupTarget(Context context, Uri treeUri) {
        this.context = context.getApplicationContext();
        this.treeUri = treeUri;
    }

    @Override
    public void connect() throws IOException {
        root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists()) {
            throw new IOException("无法访问授权目录，可能硬盘已拔出或授权失效");
        }
        if (!root.canWrite()) {
            throw new IOException("授权目录不可写，请重新选择目录并授权");
        }
    }

    @Override
    public long getFreeSpace() {
        return -1; // SAF 不提供剩余空间
    }

    @Override
    public void ensureDir(String relativePath) throws IOException {
        DocumentFile dir = resolveDir(relativePath, true);
        if (dir == null) throw new IOException("创建目录失败：" + relativePath);
    }

    @Override
    public boolean isUpToDate(String relativePath, long size, long lastModified) {
        // SAF 写入后无法设置修改时间，故只按“存在 + 大小一致”判断是否跳过，
        // 否则每次都会因时间不一致而全量重传。
        DocumentFile f = resolveFile(relativePath);
        return f != null && f.exists() && f.isFile() && f.length() == size;
    }

    @Override
    public void writeFile(String relativePath, InputStream in, long size, long lastModified)
            throws IOException {
        int slash = relativePath.lastIndexOf('/');
        String dirPath = slash > 0 ? relativePath.substring(0, slash) : "";
        String name = slash > 0 ? relativePath.substring(slash + 1) : relativePath;

        DocumentFile dir = dirPath.isEmpty() ? root : resolveDir(dirPath, true);
        if (dir == null) throw new IOException("无法创建目录：" + dirPath);

        // 若已存在先删除，避免 createFile 自动改名成 "xxx (1)"
        DocumentFile existing = dir.findFile(name);
        if (existing != null && existing.exists()) {
            if (!existing.delete()) throw new IOException("无法覆盖旧文件：" + name);
        }

        String mime = guessMime(name);
        DocumentFile file = dir.createFile(mime, name);
        if (file == null) throw new IOException("创建文件失败：" + name);

        OutputStream out = null;
        try {
            out = context.getContentResolver().openOutputStream(file.getUri(), "w");
            if (out == null) throw new IOException("无法写入文件：" + name);
            byte[] buf = new byte[64 * 1024];
            int n;
            long written = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                written += n;
            }
            out.flush();
            out.close();
            out = null;
            if (written != size) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                throw new IOException("写入字节数不一致：" + name);
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public void close() {
        // 无需释放
    }

    /** 逐级解析/创建目录。 */
    private DocumentFile resolveDir(String relativePath, boolean create) throws IOException {
        DocumentFile cur = root;
        for (String part : relativePath.split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile next = cur.findFile(part);
            if (next == null || !next.exists()) {
                if (!create) return null;
                next = cur.createDirectory(part);
                if (next == null) throw new IOException("创建目录失败：" + part);
            }
            cur = next;
        }
        return cur;
    }

    private DocumentFile resolveFile(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String dirPath = slash > 0 ? relativePath.substring(0, slash) : "";
        String name = slash > 0 ? relativePath.substring(slash + 1) : relativePath;
        try {
            DocumentFile dir = dirPath.isEmpty() ? root : resolveDir(dirPath, false);
            if (dir == null) return null;
            return dir.findFile(name);
        } catch (IOException e) {
            return null;
        }
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
