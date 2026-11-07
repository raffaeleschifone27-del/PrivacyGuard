package com.example.privacyguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.widget.ImageView
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun text(s: String, sp: Float = 14f, bold: Boolean = false, color: Int = Color.BLACK): TextView {
        return TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // schermata non disponibile su questo telefono
        }
    }

    private fun fmt(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    private fun section(title: String) {
        val t = text(title, 18f, true)
        t.setPadding(0, dp(20), 0, dp(4))
        root.addView(t)
    }

    private fun render() {
        root.removeAllViews()

        root.addView(text("PrivacyGuard", 24f, true))
        root.addView(
            text(
                "Cerca indizi di app di controllo o spionaggio. Android non permette a nessuna app " +
                    "di vedere in tempo reale chi usa microfono e fotocamera: per quello usa le " +
                    "impostazioni Privacy del telefono (pulsante qui sotto). Un risultato pulito non " +
                    "è una garanzia assoluta.",
                13f, false, Color.DKGRAY
            )
        )

        root.addView(button("Apri Privacy: uso recente di microfono e fotocamera") {
            open(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        })
        root.addView(button("Accessibilità (app che leggono lo schermo)") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Accesso alle notifiche") {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Concedi accesso ai dati di utilizzo (ultimo uso app)") {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        renderUnlockLog()
        renderFindings()
    }

    private fun renderUnlockLog() {
        section("Tentativi di sblocco")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        if (!dpm.isAdminActive(admin)) {
            root.addView(
                text(
                    "Per registrare chi sbaglia password, PIN o schema serve attivare PrivacyGuard " +
                        "come amministratore del dispositivo. Registra data e ora e prova a scattare una foto con la fotocamera frontale. " +
                        "Le foto restano solo nel telefono.",
                    13f
                )
            )
            root.addView(button("Attiva il registro") {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Serve per registrare i tentativi di sblocco falliti."
                    )
                open(i)
            })
            return
        }

        val events = FailLog.all(this)
        val failed = events.count { it.second == FailLog.FAILED }
        root.addView(text("Registro attivo. Tentativi falliti registrati: $failed", 14f, true))
        renderPhotoSetup()

        if (events.isEmpty()) {
            root.addView(text("Nessun evento ancora.", 13f, false, Color.DKGRAY))
        }
        for ((time, type) in events.take(30)) {
            val bad = type == FailLog.FAILED
            root.addView(
                text(
                    (if (bad) "✗ Sbloccato male: " else "✓ Sbloccato: ") + fmt(time),
                    13f, false,
                    if (bad) Color.parseColor("#C62828") else Color.DKGRAY
                )
            )
        }
        renderPhotos()
        root.addView(button("Cancella registro e foto") {
            FailLog.clear(this)
            render()
        })
        root.addView(button("Disattiva il registro") {
            dpm.removeActiveAdmin(admin)
            render()
        })
    }

    private fun renderPhotoSetup() {
        val camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        root.addView(
            text(
                "Foto: fotocamera " + (if (camOk) "✓" else "✗") +
                    " · mostra sopra altre app " + (if (overlayOk) "✓" else "✗"),
                13f
            )
        )
        if (!camOk) {
            root.addView(button("Consenti fotocamera e notifiche") {
                val perms = if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    arrayOf(Manifest.permission.CAMERA)
                }
                requestPermissions(perms, 1)
            })
        }
        if (!overlayOk) {
            root.addView(button("Consenti \"Mostra sopra altre app\" (serve per la foto a telefono bloccato)") {
                open(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            })
        }
        if (camOk) {
            root.addView(button("Prova: scatta una foto ora") {
                try {
                    startForegroundService(Intent(this, CaptureService::class.java))
                } catch (e: Throwable) {
                    FailLog.setError(this, "Avvio: $e")
                }
                root.postDelayed({ render() }, 2500)
            })
        }
        val err = FailLog.getError(this)
        if (err != null) {
            root.addView(text("Ultimo errore foto: $err", 12f, false, Color.parseColor("#C62828")))
        }
    }

    private fun renderPhotos() {
        val photos = FailLog.photos(this)
        if (photos.isEmpty()) return
        root.addView(text("Foto scattate (${photos.size})", 15f, true))
        for (f in photos.take(10)) {
            val ms = f.nameWithoutExtension.toLongOrNull()
            if (ms != null) root.addView(text(fmt(ms), 12f, false, Color.DKGRAY))
            val bmp = BitmapFactory.decodeFile(f.path) ?: continue
            root.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
            })
        }
    }

    private fun renderFindings() {
        section("App sospette")
        val findings = Scanner(this).scan()
        val important = findings.filter { it.level != Level.INFO }
        val info = findings.filter { it.level == Level.INFO }

        if (important.isEmpty()) {
            root.addView(text("Nessuna app con indizi forti trovata.", 14f, false, Color.parseColor("#2E7D32")))
        }
        important.forEach { addCard(it) }

        section("App con microfono o fotocamera (${info.size})")
        info.forEach { addCard(it) }
    }

    private fun addCard(f: Finding) {
        val color = when (f.level) {
            Level.HIGH -> Color.parseColor("#C62828")
            Level.MEDIUM -> Color.parseColor("#EF6C00")
            Level.INFO -> Color.DKGRAY
        }
        val tag = when (f.level) {
            Level.HIGH -> "ALTO"
            Level.MEDIUM -> "MEDIO"
            Level.INFO -> "INFO"
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#F3F3F3"))
        }
        card.addView(text("[$tag] ${f.label}", 15f, true, color))
        card.addView(text(f.pkg, 11f, false, Color.GRAY))
        f.reasons.forEach { card.addView(text("• $it", 13f)) }
        if (f.lastUsed != null) {
            card.addView(text("Ultimo utilizzo: ${fmt(f.lastUsed)}", 12f, false, Color.DKGRAY))
        }
        card.addView(button("Apri dettagli app / disinstalla") {
            open(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${f.pkg}"))
            )
        })

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        root.addView(card, lp)
    }
}
