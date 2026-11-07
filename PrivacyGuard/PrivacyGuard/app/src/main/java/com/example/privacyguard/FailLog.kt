package com.example.privacyguard

import android.content.Context

/** Registro locale (solo sul telefono) degli sblocchi riusciti e falliti. */
object FailLog {
    const val FAILED = "FAILED"
    const val OK = "OK"

    private const val PREFS = "faillog"
    private const val KEY = "events"
    private const val MAX = 100

    fun add(ctx: Context, type: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = p.getString(KEY, "") ?: ""
        val lines = (old.split("\n").filter { it.isNotBlank() } + "${System.currentTimeMillis()}|$type")
            .takeLast(MAX)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    /** Eventi dal più recente al più vecchio: (timestamp, tipo). */
    fun all(ctx: Context): List<Pair<Long, String>> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.split("\n")
            .filter { it.contains("|") }
            .mapNotNull {
                val parts = it.split("|")
                val t = parts[0].toLongOrNull()
                if (t == null) null else Pair(t, parts[1])
            }
            .reversed()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        photos(ctx).forEach { it.delete() }
    }

    fun photosDir(ctx: Context): java.io.File = java.io.File(ctx.filesDir, "intruders")

    /** Foto scattate, dalla più recente. Il nome del file è l'orario in millisecondi. */
    fun photos(ctx: Context): List<java.io.File> {
        val files = photosDir(ctx).listFiles() ?: return emptyList()
        return files.filter { it.extension == "jpg" }.sortedByDescending { it.name }
    }

    fun setError(ctx: Context, msg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("error", msg).apply()
    }

    fun getError(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("error", null)

    fun clearError(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("error").apply()
    }
}
