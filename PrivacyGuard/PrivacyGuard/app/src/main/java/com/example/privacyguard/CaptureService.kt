package com.example.privacyguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import java.io.File

/**
 * Scatta una foto con la fotocamera frontale e la salva nella memoria privata dell'app.
 * Usa la vecchia API Camera perché è la più semplice da far funzionare senza schermo.
 */
@Suppress("DEPRECATION")
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var overlay: View? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Throwable) {
            FailLog.setError(this, "Servizio non avviato: $e")
            finish()
            return START_NOT_STICKY
        }
        addOverlay()
        handler.postDelayed({ finish() }, 8000)
        try {
            capture()
        } catch (e: Throwable) {
            FailLog.setError(this, "Fotocamera: $e")
            finish()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("guard", "PrivacyGuard", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, "guard")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PrivacyGuard")
            .setContentText("Registro tentativo di sblocco")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(42, n)
        }
    }

    /** Una finestra di 1 pixel trasparente: aiuta Android a considerare l'app "visibile". */
    private fun addOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            overlay = v
        } catch (e: Throwable) {
            // si prova lo stesso senza
        }
    }

    private fun capture() {
        val info = Camera.CameraInfo()
        var id = -1
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                id = i
                break
            }
        }
        if (id < 0) {
            FailLog.setError(this, "Nessuna fotocamera frontale trovata")
            finish()
            return
        }
        val orientation = info.orientation

        val cam = Camera.open(id)
        camera = cam
        val p = cam.parameters
        val sizes = p.supportedPictureSizes
        if (sizes != null && sizes.isNotEmpty()) {
            val best = sizes.minByOrNull { Math.abs(it.width - 1280) }
            if (best != null) p.setPictureSize(best.width, best.height)
        }
        cam.parameters = p
        cam.setPreviewTexture(SurfaceTexture(10))
        cam.startPreview()

        // piccola pausa per lasciare regolare esposizione e messa a fuoco
        handler.postDelayed({
            try {
                cam.takePicture(null, null, Camera.PictureCallback { data, _ ->
                    save(data, orientation)
                    finish()
                })
            } catch (e: Throwable) {
                FailLog.setError(this, "Scatto: $e")
                finish()
            }
        }, 700)
    }

    private fun save(data: ByteArray, orientation: Int) {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            val m = Matrix().apply { postRotate(orientation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val dir = FailLog.photosDir(this)
            dir.mkdirs()
            File(dir, "${System.currentTimeMillis()}.jpg").outputStream().use {
                rotated.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            FailLog.clearError(this)
        } catch (e: Throwable) {
            FailLog.setError(this, "Salvataggio: $e")
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        try { camera?.release() } catch (e: Throwable) { }
        camera = null
        try {
            val v = overlay
            if (v != null) (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Throwable) { }
        overlay = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!finished) finish()
        super.onDestroy()
    }
}
