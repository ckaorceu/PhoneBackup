package com.phonebackup.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描设备上的待备份文件。
 * 使用 MediaStore 查询图片/视频/音频，效率高且兼容分区存储。
 * “其他文件”通过扫描公共目录（Download/Documents 等）补充。
 */
public class FileScanner {

    /** 一个待备份文件的元数据。 */
    public static class Item {
        public final Uri uri;            // 内容 URI，用于打开输入流
        public final String relativePath; // 备份目标内的相对路径（含子目录）
        public final long size;
        public final long lastModified;  // 毫秒

        public Item(Uri uri, String relativePath, long size, long lastModified) {
            this.uri = uri;
            this.relativePath = relativePath;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    public static class Options {
        public boolean includeImages = true;
        public boolean includeVideos = true;
        public boolean includeAudio = false;
        public boolean includeOther = false; // Download/Documents 等目录里的其他文件
    }

    private final Context context;

    public FileScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<Item> scan(Options opt) {
        List<Item> result = new ArrayList<>();
        if (opt.includeImages) queryMedia(result, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures");
        if (opt.includeVideos) queryMedia(result, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies");
        if (opt.includeAudio) queryMedia(result, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music");
        if (opt.includeOther) scanOtherDirs(result);
        return result;
    }

    private void queryMedia(List<Item> out, Uri base, String topFolder) {
        ContentResolver cr = context.getContentResolver();
        String[] proj;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };
        } else {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };
        }

        Cursor c = null;
        try {
            c = cr.query(base, proj, null, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;

            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int relCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    : c.getColumnIndex(MediaStore.MediaColumns.DATA);

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                String name = safeName(c.getString(nameCol));
                long size = c.getLong(sizeCol);
                long dateSec = c.getLong(dateCol);
                String rel = relCol >= 0 ? c.getString(relCol) : null;

                Uri uri = android.content.ContentUris.withAppendedId(base, id);
                String relative = buildRelative(topFolder, rel, name);
                out.add(new Item(uri, relative, size, dateSec * 1000L));
            }
        } catch (Exception ignored) {
            // 单类查询失败不影响其他类型
        } finally {
            if (c != null) c.close();
        }
    }

    /** 扫描 Download / Documents 等公共目录中的“其他文件”。
     *  使用 MediaStore.Files 查询，兼容 Android 10+ 分区存储（返回 content:// URI）。 */
    private void scanOtherDirs(List<Item> out) {
        ContentResolver cr = context.getContentResolver();
        Uri filesUri = MediaStore.Files.getContentUri("external");
        String[] proj = new String[]{
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MEDIA_TYPE
        };
        // 只要 Download 与 Documents 目录下的普通文件，排除已被媒体库归类的项以避免重复
        String selection = "(" + MediaStore.Files.FileColumns.DATA + " LIKE ? OR "
                + MediaStore.Files.FileColumns.DATA + " LIKE ?) AND "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
                + MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
        String[] args = new String[]{"%/Download/%", "%/Documents/%"};

        Cursor c = null;
        try {
            c = cr.query(filesUri, proj, selection, args, null);
            if (c == null) return;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                String name = safeName(c.getString(nameCol));
                String data = c.getString(dataCol);
                long size = c.getLong(sizeCol);
                long dateSec = c.getLong(dateCol);
                if (size <= 0 || data == null) continue;

                Uri uri = android.content.ContentUris.withAppendedId(filesUri, id);
                String relative = relativeFromData(data, name);
                out.add(new Item(uri, relative, size, dateSec * 1000L));
            }
        } catch (Exception ignored) {
            // 查询失败则跳过“其他文件”
        } finally {
            if (c != null) c.close();
        }
    }

    /** 从完整路径提取 Download/ 或 Documents/ 之后的相对路径。 */
    private String relativeFromData(String data, String name) {
        String lower = data.toLowerCase();
        int idx = lower.indexOf("/download/");
        if (idx < 0) idx = lower.indexOf("/documents/");
        if (idx >= 0) {
            // 去掉前导 '/'，保留 "Download/xxx" 目录结构
            return data.substring(idx + 1);
        }
        return "Other/" + name;
    }

    /** 生成备份目标内的相对路径，保留原始子目录结构。 */
    private String buildRelative(String topFolder, String rel, String name) {
        if (rel == null || rel.isEmpty()) {
            return topFolder + "/" + name;
        }
        // RELATIVE_PATH 形如 "Pictures/Screenshots/"；DATA 是完整路径
        String clean = rel;
        if (clean.startsWith("/")) {
            // 旧版 DATA 字段：取最后两级之前的目录
            int idx = clean.lastIndexOf('/');
            clean = idx > 0 ? clean.substring(0, idx) : "";
        } else {
            if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) return topFolder + "/" + name;
        return clean + "/" + name;
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) return "file_" + System.currentTimeMillis();
        // 去掉路径分隔符，防止越权写到目标外
        return name.replace('/', '_').replace('\\', '_');
    }
}
