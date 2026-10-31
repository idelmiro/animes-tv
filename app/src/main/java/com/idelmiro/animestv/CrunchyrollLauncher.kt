package com.idelmiro.animestv

import android.content.ComponentName
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

    class Variant(val key: String, val label: String, val intent: Intent)

    /** Tela do Crunchyroll que aceita links crunchyroll:// (lida do manifesto do app). */
    private fun mainComponent(ctx: Context, pkg: String): ComponentName? {
        val comps = try {
            ManifestReader.read(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
        } catch (_: Exception) {
            return null
        }
        val c = comps.firstOrNull { comp -> comp.filters.any { f -> f.data.any { it.startsWith("crunchyroll://") } } }
            ?: return null
        val name = if (c.name.startsWith(".")) pkg + c.name else c.name
        return ComponentName(pkg, name)
    }

    /** Formas candidatas de abrir a página de uma série no app da TV. */
    fun variants(ctx: Context, url: String): List<Variant> {
        val pkg = installedPackages(ctx).firstOrNull() ?: return emptyList()
        val m = Regex("/series/([A-Z0-9]+)(?:/([^/?#]+))?").find(url)
        val id = m?.groupValues?.get(1)
        val slug = m?.groupValues?.get(2).orEmpty()
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        fun view(u: String) = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg).addFlags(flags)
        val list = mutableListOf<Variant>()
        val comp = mainComponent(ctx, pkg)
        if (comp != null) {
            list += Variant("site-direto", "Link do site entregue direto ao Crunchyroll",
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setComponent(comp).addFlags(flags))
        }
        if (id != null) {
            list += Variant("cr-series", "crunchyroll://series/$id", view("crunchyroll://series/$id"))
            if (slug.isNotEmpty()) {
                list += Variant("cr-series-slug", "crunchyroll://series/$id/$slug", view("crunchyroll://series/$id/$slug"))
            }
            val www = "crunchyroll://www.crunchyroll.com/series/$id" + (if (slug.isNotEmpty()) "/$slug" else "")
            list += Variant("cr-www", www, view(www))
            list += Variant("cr-host", "crunchyroll://crunchyroll.com/series/$id", view("crunchyroll://crunchyroll.com/series/$id"))
            list += Variant("cr-watch", "crunchyroll://watch/series/$id", view("crunchyroll://watch/series/$id"))
        }
        return list
    }

    fun launch(ctx: Context, v: Variant): Boolean = try {
        ctx.startActivity(v.intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Abre a página do anime no app do Crunchyroll, usando a forma que já funcionou
     * (escolhida no teste). Se não houver, abre o app do Crunchyroll.
     * Retorna null se deu certo, ou uma mensagem de aviso.
     */
    fun open(ctx: Context, url: String?): String? {
        val pkgs = installedPackages(ctx)
        val method = Prefs(ctx).openMethod
        if (url != null && method != null) {
            val v = variants(ctx, url).firstOrNull { it.key == method }
            if (v != null && launch(ctx, v)) return null
        }
        for (pkg in pkgs) {
            val li = ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(li)
                return if (url == null) "Este anime não tem link do Crunchyroll; abri o app."
                else "Ainda não sei abrir o anime direto (use \"Testar formas de abrir\"); abri o app."
            }
        }
        return "App do Crunchyroll não encontrado nesta TV."
    }
}
