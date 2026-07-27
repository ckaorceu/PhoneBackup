package com.phonebackup.smb;

import com.phonebackup.backup.BackupTarget;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * SMB 网络备份目标，备份到 NAS / Windows 共享文件夹。
 * 基于 jcifs-ng，支持 SMB2/3。
 */
public class SmbBackupTarget implements BackupTarget {

    private final String host;
    private final String share;        // 共享名，如 backup
    private final String subFolder;    // 共享内的子目录，可为空
    private final String username;
    private final String password;
    private final String domain;

    private CIFSContext context;

    public SmbBackupTarget(String host, String share, String subFolder,
                           String domain, String username, String password) {
        this.host = host;
        this.share = share;
        this.subFolder = subFolder == null ? "" : subFolder.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    /** 拼接 smb:// URL。 */
    private String baseUrl() {
        StringBuilder sb = new StringBuilder("smb://").append(host).append('/').append(share).append('/');
        if (!subFolder.isEmpty()) {
            sb.append(subFolder);
            if (!subFolder.endsWith("/")) sb.append('/');
        }
        return sb.toString();
    }

    @Override
    public void connect() throws IOException {
        try {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "30000");
            props.setProperty("jcifs.smb.client.soTimeout", "35000");
            props.setProperty("jcifs.smb.client.connTimeout", "15000");
            props.setProperty("jcifs.smb.client.dfs.disabled", "true");

            BaseContext base = new BaseContext(new PropertyConfiguration(props));
            if (!username.isEmpty()) {
                NtlmPasswordAuthenticator auth =
                        new NtlmPasswordAuthenticator(domain, username, password);
                context = base.withCredentials(auth);
            } else {
                context = base.withAnonymousCredentials();
            }
        } catch (Exception e) {
            throw new IOException("SMB 初始化失败：" + e.getMessage(), e);
        }

        // 尝试访问共享根，验证连通与凭据
        SmbFile probe = null;
        try {
            probe = new SmbFile(baseUrl(), context);
            probe.setConnectTimeout(15000);
            probe.setReadTimeout(30000);
            if (!probe.exists()) {
                // 子目录不存在则创建（共享本身必须存在）
                probe.mkdirs();
            }
            if (!probe.isDirectory()) {
                throw new IOException("SMB 目标不是目录：" + baseUrl());
            }
        } catch (IOException e) {
            throw new IOException("无法连接 SMB 共享，请检查地址/账号/网络：" + e.getMessage(), e);
        } finally {
            closeQuietly(probe);
        }
    }

    @Override
    public long getFreeSpace() {
        // jcifs-ng 不直接提供剩余空间，返回 -1 表示未知（跳过空间预检）
        return -1;
    }

    @Override
    public void ensureDir(String relativePath) throws IOException {
        SmbFile dir = null;
        try {
            dir = new SmbFile(baseUrl() + relativePath + "/", context);
            if (!dir.exists()) dir.mkdirs();
        } finally {
            closeQuietly(dir);
        }
    }

    @Override
    public boolean isUpToDate(String relativePath, long size, long lastModified) {
        SmbFile f = null;
        try {
            f = new SmbFile(baseUrl() + relativePath, context);
            return f.exists() && !f.isDirectory()
                    && f.length() == size
                    && Math.abs(f.lastModified() - lastModified) < 2000;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(f);
        }
    }

    @Override
    public void writeFile(String relativePath, InputStream in, long size, long lastModified)
            throws IOException {
        // SMB 无法可靠原子改名，直接写入；写完后校验大小
        SmbFile dest = null;
        OutputStream out = null;
        try {
            dest = new SmbFile(baseUrl() + relativePath, context);
            out = dest.getOutputStream();
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
                throw new IOException("SMB 写入字节数不一致：" + relativePath);
            }
            try {
                dest.setLastModified(lastModified);
            } catch (Exception ignored) {
                // 部分服务器不允许设置修改时间，忽略
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            closeQuietly(dest);
        }
    }

    @Override
    public void close() {
        // jcifs 连接由库管理，这里无需显式释放
    }

    private static void closeQuietly(SmbFile f) {
        if (f != null) {
            try { f.close(); } catch (Exception ignored) {}
        }
    }
}
