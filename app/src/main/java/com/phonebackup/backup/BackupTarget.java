package com.phonebackup.backup;

import java.io.IOException;
import java.io.InputStream;

/**
 * 备份目的地抽象。USB 硬盘用本地路径实现，SMB 共享用网络实现。
 * 这样上层备份逻辑无需关心目标到底是哪种。
 */
public interface BackupTarget extends AutoCloseable {

    /** 连接/校验目标是否可用。失败抛 IOException。 */
    void connect() throws IOException;

    /** 目标剩余可用空间（字节）。无法获取时返回 -1。 */
    long getFreeSpace();

    /** 确保目录存在（含多级父目录）。 */
    void ensureDir(String relativePath) throws IOException;

    /**
     * 判断目标文件是否已存在且与源一致（大小+修改时间），用于增量备份跳过。
     * @return 一致返回 true
     */
    boolean isUpToDate(String relativePath, long size, long lastModified) throws IOException;

    /**
     * 写入一个文件。实现需保证原子性（先写临时再改名），避免中途断开产生坏文件。
     */
    void writeFile(String relativePath, InputStream in, long size, long lastModified)
            throws IOException;

    @Override
    void close();
}
