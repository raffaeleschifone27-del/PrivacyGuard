package com.example.privacyguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Riceve gli eventi di sblocco del telefono (password/PIN/schema sbagliato o corretto). */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private var lastPhoto = 0L
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        FailLog.add(context, FailLog.FAILED)

        // una sola foto ogni 8 secondi, anche se i tentativi sono ravvicinati
        val now = System.currentTimeMillis()
        if (now - lastPhoto < 8000) return
        lastPhoto = now
        try {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        } catch (e: Throwable) {
            FailLog.setError(context, "Avvio foto bloccato da Android: $e")
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        FailLog.add(context, FailLog.OK)
    }
}
