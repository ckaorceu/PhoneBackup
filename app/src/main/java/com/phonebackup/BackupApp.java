package com.phonebackup;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 全局 Application：创建通知渠道等一次性初始化。
 */
public class BackupApp extends Application {

    public static final String CHANNEL_PROGRESS = "backup_progress";
    public static final String CHANNEL_RESULT = "backup_result";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel progress = new NotificationChannel(
                    CHANNEL_PROGRESS, "备份进行中",
                    NotificationManager.IMPORTANCE_LOW);
            progress.setDescription("显示备份进度，备份期间不可关闭");
            progress.setShowBadge(false);
            nm.createNotificationChannel(progress);

            NotificationChannel result = new NotificationChannel(
                    CHANNEL_RESULT, "备份结果",
                    NotificationManager.IMPORTANCE_HIGH);
            result.setDescription("备份完成或失败的通知");
            nm.createNotificationChannel(result);
        }
    }
}
