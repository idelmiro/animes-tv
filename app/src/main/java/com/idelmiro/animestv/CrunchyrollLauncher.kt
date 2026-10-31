package com.idelmiro.animestv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object CrunchyrollLauncher {

    const val SAMPLE_URL = "https://www.crunchyroll.com/series/GY5P48XEY/demon-slayer-kimetsu-no-yaiba"

    /** Forma de abrir a série que funcionou no teste da TV Semp (crunchyroll://series/ID). */
    const val DEFAULT_SERIES_METHOD = "cr-series"

    class Variant(val key: String, val label: String, val intent: Intent)

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

    private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    private fun view(pkg: String, u: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg).addFlags(FLAGS)

    /** Formas candidatas de abrir a página de uma série. */
    fun seriesVariants(ctx: Context, url: String): List<Variant> {
        val pkg = installedPackages(ctx).firstOrNull() ?: return emptyList()
        val m = Regex("/series/([A-Z0-9]+)(?:/([^/?#]+))?").find(url)
        val id = m?.groupValues?.get(1)
        val slug = m?.groupValues?.get(2).orEmpty()
        val list = mutableListOf<Variant>()
        if (id != null) {
            list += Variant("cr-series", "crunchyroll://series/$id", view(pkg, "crunchyroll://series/$id"))
            if (slug.isNotEmpty()) {
                list += Variant("cr-series-slug", "crunchyroll://series/$id/$slug", view(pkg, "crunchyroll://series/$id/$slug"))
            }
        }
        mainComponent(ctx, pkg)?.let { comp ->
            list += Variant("site-direto", "Link do site entregue direto ao Crunchyroll",
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setComponent(comp).addFlags(FLAGS))
        }
        return list
    }

    /** Formas candidatas de abrir (e tocar) um episódio. */
    fun episodeVariants(ctx: Context, episodeId: String, slug: String): List<Variant> {
        val pkg = installedPackages(ctx).firstOrNull() ?: return emptyList()
        val list = mutableListOf<Variant>()
        list += Variant("ep-watch", "crunchyroll://watch/$episodeId", view(pkg, "crunchyroll://watch/$episodeId"))
        if (slug.isNotEmpty()) {
            list += Variant("ep-watch-slug", "crunchyroll://watch/$episodeId/$slug", view(pkg, "crunchyroll://watch/$episodeId/$slug"))
        }
        list += Variant("ep-episode", "crunchyroll://episode/$episodeId", view(pkg, "crunchyroll://episode/$episodeId"))
        list += Variant("ep-play", "crunchyroll://play/$episodeId", view(pkg, "crunchyroll://play/$episodeId"))
        list += Variant("ep-www", "crunchyroll://www.crunchyroll.com/watch/$episodeId",
            view(pkg, "crunchyroll://www.crunchyroll.com/watch/$episodeId"))
        mainComponent(ctx, pkg)?.let { comp ->
            list += Variant("ep-site-direto", "Link do site do episódio entregue direto ao Crunchyroll",
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.crunchyroll.com/watch/$episodeId/$slug"))
                    .setComponent(comp).addFlags(FLAGS))
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
     * Abre a página do anime. Retorna null se deu certo, ou uma mensagem de aviso.
     */
    fun openSeries(ctx: Context, url: String?): String? {
        val method = Prefs(ctx).openMethod ?: DEFAULT_SERIES_METHOD
        if (url != null) {
            val v = seriesVariants(ctx, url).firstOrNull { it.key == method }
            if (v != null && launch(ctx, v)) return null
        }
        return openApp(ctx, if (url == null) "Este anime não tem link do Crunchyroll; abri o app." else "Não consegui abrir o anime; abri o app.")
    }

    /**
     * Abre o episódio, se a forma de abrir episódios já foi descoberta no teste;
     * senão abre a página do anime.
     */
    fun openEpisode(ctx: Context, episodeId: String?, slug: String, seriesUrl: String?): String? {
        val method = Prefs(ctx).episodeMethod
        if (episodeId != null && method != null) {
            val v = episodeVariants(ctx, episodeId, slug).firstOrNull { it.key == method }
            if (v != null && launch(ctx, v)) return null
        }
        return openSeries(ctx, seriesUrl)
    }

    private fun openApp(ctx: Context, msg: String): String {
        for (pkg in installedPackages(ctx)) {
            val li = ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(li)
                return msg
            }
        }
        return "App do Crunchyroll não encontrado nesta TV."
    }
}
