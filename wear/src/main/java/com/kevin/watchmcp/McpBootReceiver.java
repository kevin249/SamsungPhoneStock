package com.kevin.watchmcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 用户已开启的接收任务在手表重启或应用更新后恢复。 */
public final class McpBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) McpService.ensureRunning(context);
    }
}
