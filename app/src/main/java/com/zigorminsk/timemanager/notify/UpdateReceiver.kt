package com.zigorminsk.timemanager.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.update.UpdateManager

/** Кнопки в уведомлениях об обновлении. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        when (intent.action) {
            ACTION_DOWNLOAD -> UpdateManager.enqueueDownload(context)
            ACTION_INSTALL -> UpdateManager.installLatestDownloaded(context)
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.zigorminsk.timemanager.action.DOWNLOAD_UPDATE"
        const val ACTION_INSTALL = "com.zigorminsk.timemanager.action.INSTALL_UPDATE"
    }
}
