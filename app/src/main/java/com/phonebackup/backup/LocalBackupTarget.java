package com.phonebackup.backup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 本地文件系统备份目标，用于 OTG 挂载的移动硬盘 / U 盘。
 * 安卓通过 StorageManager 挂载的 USB 存储通常表现为一个可读写的目录路径。
 */
public class LocalBackupTarget implements BackupTarget {

    private final File root;

    public LocalBackupTarget(String rootPath) {
        this.root = new File(rootPath);
    }

    @Override
    public void connect() throws IOException {
        if (!root.exists()) {
            throw new IOException("目标目录不存在：" + root.getAbsolutePath());
        }
        if (!root.canWrite()) {
            throw new IOException("目标目录不可写，请检查硬盘权限或是否为只读挂载");
        }
        // 尝试创建探测文件，确认确实可写（某些挂载点 canWrite 不准）
        File probe = new File(root, ".write_probe_" + System.currentTimeMillis());
        try {
            if (!probe.createNewFile()) {
                throw new IOException("无法在目标目录写入测试文件");
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
    }

    @Override
    public long getFreeSpace() {
        return root.getUsableSpace();
    }

    @Override
    public void ensureDir(String relativePath) throws IOException {
        File dir = new File(root, relativePath);
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IOException("路径已被文件占用：" + relativePath);
            return;
        }
        if (!dir.mkdirs()) {
            throw new IOException("创建目录失败：" + dir.getAbsolutePath());
        }
    }

    @Override
    public boolean isUpToDate(String relativePath, long size, long lastModified) {
        File f = new File(root, relativePath);
        return f.exists() && f.isFile()
                && f.length() == size
                && Math.abs(f.lastModified() - lastModified) < 1000;
    }

    @Override
    public void writeFile(String relativePath, InputStream in, long size, long lastModified)
            throws IOException {
        File dest = new File(root, relativePath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建父目录失败：" + parent.getAbsolutePath());
        }
        // 原子写：先写 .tmp，成功后改名，避免坏文件
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            // 确保落盘，防止断电丢数据
            ((FileOutputStream) out).getFD().sync();
            out.close();
            out = null;

            if (tmp.length() != size) {
                throw new IOException("写入字节数与源不一致：" + relativePath);
            }
            if (dest.exists() && !dest.delete()) {
                throw new IOException("无法覆盖旧文件：" + relativePath);
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("重命名临时文件失败：" + relativePath);
            }
            //noinspection ResultOfMethodCallIgnored
            dest.setLastModified(lastModified);
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    @Override
    public void close() {
        // 本地文件无需释放
    }
}
