package com.example.privacyguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

enum class Level { HIGH, MEDIUM, INFO }

data class Finding(
    val pkg: String,
    val label: String,
    val level: Level,
    val score: Int,
    val reasons: List<String>,
    val lastUsed: Long?
)

class Scanner(private val ctx: Context) {

    @Suppress("DEPRECATION")
    private fun installed(pm: PackageManager): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
    }

    private fun accessibilityPackages(): Set<String> {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }

    private fun adminPackages(): Set<String> {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val list = dpm.activeAdmins ?: return emptySet()
        return list.map { it.packageName }.toSet()
    }

    private fun listenerPackages(): Set<String> {
        val raw = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return raw.split(":")
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            .toSet()
    }

    /** Ultimo utilizzo per app. Vuoto se l'utente non ha concesso "Accesso ai dati di utilizzo". */
    private fun lastUsed(): Map<String, Long> {
        val result = HashMap<String, Long>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600 * 1000, now
            )
            if (stats != null) {
                for (s in stats) {
                    if (s.lastTimeUsed > 0) {
                        result[s.packageName] = maxOf(result[s.packageName] ?: 0L, s.lastTimeUsed)
                    }
                }
            }
        } catch (e: Exception) {
            // nessun dato disponibile
        }
        return result
    }

    fun scan(): List<Finding> {
        val pm = ctx.packageManager
        val acc = accessibilityPackages()
        val admins = adminPackages()
        val listeners = listenerPackages()
        val used = lastUsed()
        val out = ArrayList<Finding>()

        for (pi in installed(pm)) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName
            if (pkg == ctx.packageName) continue

            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) continue

            val label = ai.loadLabel(pm).toString()
            val req = pi.requestedPermissions ?: emptyArray()
            val flags = pi.requestedPermissionsFlags ?: IntArray(0)

            fun granted(p: String): Boolean {
                val i = req.indexOf(p)
                return i >= 0 && i < flags.size &&
                    (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }

            var score = 0
            val reasons = ArrayList<String>()
            val mic = granted("android.permission.RECORD_AUDIO")
            val cam = granted("android.permission.CAMERA")

            if (mic) { score += 1; reasons.add("Ha il permesso del microfono") }
            if (cam) { score += 1; reasons.add("Ha il permesso della fotocamera") }
            if (granted("android.permission.READ_SMS")) { score += 1; reasons.add("Può leggere gli SMS") }
            if (granted("android.permission.READ_CALL_LOG")) { score += 1; reasons.add("Può leggere il registro chiamate") }
            if (granted("android.permission.ACCESS_BACKGROUND_LOCATION")) { score += 1; reasons.add("Posizione sempre attiva, anche in background") }
            if (pkg in acc) { score += 3; reasons.add("Servizio di accessibilità ATTIVO (può leggere lo schermo)") }
            if (pkg in admins) { score += 3; reasons.add("Amministratore del dispositivo ATTIVO (difficile da disinstallare)") }
            if (pkg in listeners) { score += 2; reasons.add("Legge le tue notifiche") }
            if (pm.getLaunchIntentForPackage(pkg) == null) { score += 2; reasons.add("Nessuna icona nel menu: app nascosta") }

            val key = (pkg + " " + label).lowercase()
            if (SuspectList.spyware.any { key.contains(it) }) {
                score += 5; reasons.add("Corrisponde a un'app di spionaggio nota")
            } else if (SuspectList.parental.any { key.contains(it) }) {
                score += 3; reasons.add("Corrisponde a un'app di controllo parentale nota")
            }

            val level = when {
                score >= 5 -> Level.HIGH
                score >= 3 -> Level.MEDIUM
                mic || cam -> Level.INFO
                else -> continue
            }
            out.add(Finding(pkg, label, level, score, reasons, used[pkg]))
        }
        return out.sortedByDescending { it.score }
    }
}
