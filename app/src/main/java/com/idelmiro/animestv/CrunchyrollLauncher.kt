package com.idelmiro.animestv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object CrunchyrollLauncher {

    const val SAMPLE_URL = "https://www.crunchyroll.com/series/GY5P48XEY/demon-slayer-kimetsu-no-yaiba"

    fun installedPackages(ctx: Context): List<String> =
        ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it.packageName }
            .filter { it.contains("crunchyroll", ignoreCase = true) }

    /** Apps que se dizem capazes de abrir o link. */
    fun handlersFor(ctx: Context, url: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName + "/" + it.activityInfo.name }
    }

    /**
     * Abre a página do anime no app do Crunchyroll. Se o app não aceitar o link,
     * abre o app do Crunchyroll na tela inicial dele.
     * Retorna null se deu certo, ou uma mensagem de aviso.
     */
    fun open(ctx: Context, url: String?): String? {
        val pkgs = installedPackages(ctx)
        if (url != null) {
            val handlers = handlersFor(ctx, url).map { it.substringBefore('/') }
            val candidates = (handlers.filter { it.contains("crunchyroll", true) } + pkgs).distinct()
            for (pkg in candidates) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return null
                } catch (_: Exception) {
                }
            }
        }
        for (pkg in pkgs) {
            val launch = ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                return if (url == null) "Este anime não tem link do Crunchyroll; abri o app."
                else "O Crunchyroll não aceitou o link direto; abri o app."
            }
        }
        return "App do Crunchyroll não encontrado nesta TV."
    }
}
