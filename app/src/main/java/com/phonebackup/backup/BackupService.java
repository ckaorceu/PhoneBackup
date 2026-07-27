package com.phonebackup.backup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.phonebackup.BackupApp;
import com.phonebackup.R;
import com.phonebackup.smb.SmbBackupTarget;
import com.phonebackup.ui.MainActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台备份服务：在通知栏显示进度，防止备份被系统杀死。
 * 通过 startService 传入参数启动；通过 ACTION_CANCEL 取消。
 */
public class BackupService extends Service implements BackupListener {

    public static final String ACTION_START = "com.phonebackup.START";
    public static final String ACTION_CANCEL = "com.phonebackup.CANCEL";
    public static final String ACTION_PROGRESS = "com.phonebackup.PROGRESS";

    public static final String EXTRA_TARGET_TYPE = "target_type"; // "saf" | "local" | "smb"
    public static final String EXTRA_SAF_URI = "saf_uri";
    public static final String EXTRA_LOCAL_PATH = "local_path";
    public static final String EXTRA_SMB_HOST = "smb_host";
    public static final String EXTRA_SMB_SHARE = "smb_share";
    public static final String EXTRA_SMB_FOLDER = "smb_folder";
    public static final String EXTRA_SMB_DOMAIN = "smb_domain";
    public static final String EXTRA_SMB_USER = "smb_user";
    public static final String EXTRA_SMB_PASS = "smb_pass";
    public static final String EXTRA_INC_IMAGES = "inc_images";
    public static final String EXTRA_INC_VIDEOS = "inc_videos";
    public static final String EXTRA_INC_AUDIO = "inc_audio";
    public static final String EXTRA_INC_OTHER = "inc_other";

    private static final int NOTIF_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile BackupEngine engine;
    private int totalFiles;
    private long totalBytes;

    public static void start(Context ctx, Intent intent) {
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            if (engine != null) engine.requestCancel();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildProgressNotif("准备备份…", 0, 0));
            beginBackup(intent);
        }
        return START_NOT_STICKY;
    }

    private void beginBackup(Intent intent) {
        // 解析目标
        BackupTarget target;
        String type = intent.getStringExtra(EXTRA_TARGET_TYPE);
        if ("smb".equals(type)) {
            target = new SmbBackupTarget(
                    intent.getStringExtra(EXTRA_SMB_HOST),
                    intent.getStringExtra(EXTRA_SMB_SHARE),
                    intent.getStringExtra(EXTRA_SMB_FOLDER),
                    intent.getStringExtra(EXTRA_SMB_DOMAIN),
                    intent.getStringExtra(EXTRA_SMB_USER),
                    intent.getStringExtra(EXTRA_SMB_PASS));
        } else if ("saf".equals(type)) {
            String uriStr = intent.getStringExtra(EXTRA_SAF_URI);
            if (uriStr == null) {
                BackupResult r = new BackupResult();
                r.errors.add("未选择备份目录");
                onFinish(r);
                return;
            }
            target = new SafBackupTarget(this, android.net.Uri.parse(uriStr));
        } else {
            target = new LocalBackupTarget(intent.getStringExtra(EXTRA_LOCAL_PATH));
        }

        FileScanner.Options opt = new FileScanner.Options();
        opt.includeImages = intent.getBooleanExtra(EXTRA_INC_IMAGES, true);
        opt.includeVideos = intent.getBooleanExtra(EXTRA_INC_VIDEOS, true);
        opt.includeAudio = intent.getBooleanExtra(EXTRA_INC_AUDIO, false);
        opt.includeOther = intent.getBooleanExtra(EXTRA_INC_OTHER, false);

        engine = new BackupEngine(this, target, this);

        executor.execute(() -> {
            try {
                List<FileScanner.Item> items = new FileScanner(BackupService.this).scan(opt);
                engine.run(items);
            } catch (Exception e) {
                BackupResult r = new BackupResult();
                r.errors.add("扫描失败：" + e.getMessage());
                onFinish(r);
            }
        });
    }

    // ---------- BackupListener ----------

    @Override
    public void onScanComplete(int totalFiles, long totalBytes) {
        this.totalFiles = totalFiles;
        this.totalBytes = totalBytes;
        updateNotif("发现 " + totalFiles + " 个文件，共 "
                + BackupResult.formatSize(totalBytes), 0, 0);
    }

    @Override
    public void onFileProgress(int doneFiles, long doneBytes, String currentFile) {
        int pct = totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0;
        String text = doneFiles + "/" + totalFiles + "  "
                + BackupResult.formatSize(doneBytes) + "/" + BackupResult.formatSize(totalBytes);
        updateNotif(text, pct, 100);
        broadcastProgress(doneFiles, totalFiles, doneBytes, totalBytes, currentFile);
    }

    @Override
    public void onFileError(String file, String reason) {
        // 单文件错误仅记录，不打断
    }

    @Override
    public void onFinish(BackupResult result) {
        String title = result.cancelled ? "备份已取消"
                : (result.isOk() ? "备份完成" : "备份完成（有错误）");
        showResultNotif(title, result.summary());
        broadcastFinish(result);
        stopForeground(false);
        stopSelf();
    }

    // ---------- 通知 ----------

    private Notification buildProgressNotif(String text, int progress, int max) {
        Intent cancel = new Intent(this, BackupService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 2, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, BackupApp.CHANNEL_PROGRESS)
                .setContentTitle("正在备份")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_backup)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "取消", cancelPi)
                .setProgress(max, progress, max == 0)
                .build();
    }

    private void updateNotif(String text, int progress, int max) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildProgressNotif(text, progress, max));
    }

    private void showResultNotif(String title, String text) {
        Notification n = new NotificationCompat.Builder(this, BackupApp.CHANNEL_RESULT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_backup)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID + 1, n);
    }

    // ---------- 广播给 UI ----------

    private void broadcastProgress(int done, int total, long doneBytes, long totalBytes, String file) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra("done", done);
        i.putExtra("total", total);
        i.putExtra("doneBytes", doneBytes);
        i.putExtra("totalBytes", totalBytes);
        i.putExtra("file", file);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastFinish(BackupResult r) {
        Intent i = new Intent("com.phonebackup.FINISH");
        i.putExtra("summary", r.summary());
        i.putExtra("ok", r.isOk());
        i.putExtra("cancelled", r.cancelled);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
