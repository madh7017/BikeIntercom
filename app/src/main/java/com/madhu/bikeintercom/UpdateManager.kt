package com.madhu.bikeintercom

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

class UpdateManager(private val context: Context) {

    private val GITHUB_JSON_URL = "https://raw.githubusercontent.com/madh7017/BikeIntercom/main/update.json"

    fun checkForUpdates(currentVersion: Int, onUpdateAvailable: (String) -> Unit) {
        thread {
            try {
                val jsonText = URL(GITHUB_JSON_URL).readText()
                val json = JSONObject(jsonText)
                val latestVersion = json.getInt("versionCode")
                val apkUrl = json.getString("apkUrl")

                if (latestVersion > currentVersion) {
                    onUpdateAvailable(apkUrl)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Failed to check for updates", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun downloadAndInstall(apkUrl: String) {
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Bike Intercom Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex != -1 && DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(statusIndex)) {
                            installApk(destination)
                        }
                    }
                    cursor.close()
                    context.unregisterReceiver(this)
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(onComplete, filter)
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback for newer Android versions or specific restrictions
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        }
    }
}
