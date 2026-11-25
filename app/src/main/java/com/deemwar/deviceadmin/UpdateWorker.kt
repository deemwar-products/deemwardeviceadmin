package com.deemwar.deviceadmin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Daily unattended update job: fetch the repo index and silently update any
 * package that is out of date and already installed with us as installer of
 * record. Runs only while charging and on unmetered network.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val log = StringBuilder()
        try {
            val index = Repo.fetchIndex()
            val pm = applicationContext.packageManager
            for ((_, entry) in index) {
                val installed = try {
                    pm.getPackageInfo(entry.packageName, 0).longVersionCode
                } catch (e: Exception) { 0L }
                if (installed == 0L || installed >= entry.versionCode) continue
                val apk = File(applicationContext.cacheDir, entry.apkName)
                try {
                    Repo.downloadApk(entry, apk)
                    Installer.install(applicationContext, entry, apk) { log.append(it).append('\n') }
                } catch (t: Throwable) {
                    log.append("${entry.packageName}: ${t.message}\n")
                } finally {
                    apk.delete()
                }
            }
            return Result.success()
        } catch (t: Throwable) {
            return Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "deemwar-repo-updates",
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
