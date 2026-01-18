package com.example.speechrecognitionapp

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

class FirebaseUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences(
                "prediction_logs",
                Context.MODE_PRIVATE
            )

            val json = prefs.getString("logs", null) ?: return Result.success()
            val logs = FirebaseLogSerializer.fromJson(json)

            if (logs.isEmpty()) return Result.success()

            val ref = FirebaseDatabase.getInstance()
                .getReference("predictions")
                .push()

            ref.setValue(logs)

            prefs.edit().remove("logs").apply()

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
