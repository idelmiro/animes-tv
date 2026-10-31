package com.idelmiro.animestv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Publica os episódios do dia dos favoritos:
 *  - no canal "Animes de hoje" (aparece como fileira em launchers como o Projectivy);
 *  - no "Continuar assistindo" do sistema (o Google TV costuma ignorar).
 */
object HomeScreen {

    private class Item(val airing: Airing, val released: Boolean, val episode: CrunchyrollApi.Episode?)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

    private fun launchUri(ctx: Context, it: Item): Uri {
        val a = it.airing
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "animestv://open?id=${a.anime.id}&url=" + Uri.encode(a.anime.crunchyrollUrl ?: "") +
                    "&ep=" + Uri.encode(it.episode?.id ?: "") + "&slug=" + Uri.encode(it.episode?.slug ?: "")
            )
        ).setPackage(ctx.packageName)
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    fun publish(ctx: Context, airings: List<Airing>, prefs: Prefs): String {
        val now = System.currentTimeMillis()
        val items = airings.map { a ->
            val released = a.releaseMillis(prefs.offsetMinutes) <= now
            val ep = if (!released) null else try {
                CrunchyrollApi.seriesIdFromUrl(a.anime.crunchyrollUrl)?.let { CrunchyrollApi.findEpisode(it, a.episode) }
            } catch (_: Exception) {
                null
            }
            Item(a, released, ep)
        }
        // Já lançados primeiro (mais recente antes), depois os que ainda vão sair.
        val ordered = items.filter { it.released }.sortedByDescending { it.airing.airingAt } +
            items.filter { !it.released }.sortedBy { it.airing.airingAt }

        val channelMsg = publishChannel(ctx, ordered, prefs)
        val watchNextMsg = publishWatchNext(ctx, ordered.filter { it.released }, prefs)
        val time = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        return "$time — canal: $channelMsg; continuar assistindo: $watchNextMsg"
    }

    private fun publishChannel(ctx: Context, items: List<Item>, prefs: Prefs): String {
        return try {
            val helper = PreviewChannelHelper(ctx)
            var channelId = prefs.channelId
            if (channelId <= 0 || helper.getPreviewChannel(channelId) == null) {
                val appLink = Intent(ctx, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)
                val channel = PreviewChannel.Builder()
                    .setDisplayName("Animes de hoje")
                    .setDescription("Episódios de hoje dos seus animes favoritos")
                    .setAppLinkIntentUri(Uri.parse(appLink))
                    .setLogo(Uri.parse("android.resource://${ctx.packageName}/${R.drawable.icon}"))
                    .build()
                channelId = helper.publishDefaultChannel(channel)
                prefs.channelId = channelId
            }
            for (id in prefs.programIds) {
                try {
                    helper.deletePreviewProgram(id.toLong())
                } catch (_: Exception) {
                }
            }
            val ids = mutableSetOf<String>()
            for ((weight, it) in items.withIndex()) {
                val a = it.airing
                val hour = timeFmt.format(Date(a.releaseMillis(prefs.offsetMinutes)))
                val b = PreviewProgram.Builder()
                b.setChannelId(channelId)
                    .setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
                    .setTitle(a.anime.title)
                    .setEpisodeNumber(a.episode)
                    .setEpisodeTitle(if (it.released) "Ep. ${a.episode} • saiu às $hour" else "Ep. ${a.episode} • sai às $hour")
                    .setDescription(if (it.released) "Episódio ${a.episode} disponível" else "Episódio ${a.episode} sai hoje às $hour")
                    .setIntentUri(launchUri(ctx, it))
                    .setInternalProviderId("${a.anime.id}-${a.episode}")
                    .setWeight(items.size - weight)
                a.anime.cover?.let {
                    b.setPosterArtUri(Uri.parse(it))
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                }
                ids.add(helper.publishPreviewProgram(b.build()).toString())
            }
            prefs.programIds = ids
            "${ids.size} episódio(s)"
        } catch (e: Exception) {
            "erro (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun publishWatchNext(ctx: Context, items: List<Item>, prefs: Prefs): String {
        val cr = ctx.contentResolver
        for (id in prefs.homeIds) {
            try {
                cr.delete(TvContractCompat.buildWatchNextProgramUri(id.toLong()), null, null)
            } catch (_: Exception) {
            }
        }
        val ids = mutableSetOf<String>()
        var lastError = ""
        for (it in items) {
            val a = it.airing
            try {
                val b = WatchNextProgram.Builder()
                b.setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
                    .setLastEngagementTimeUtcMillis(a.releaseMillis(prefs.offsetMinutes))
                    .setTitle(a.anime.title)
                    .setEpisodeTitle("Episódio ${a.episode}")
                    .setEpisodeNumber(a.episode)
                    .setIntentUri(launchUri(ctx, it))
                    .setInternalProviderId("${a.anime.id}-${a.episode}")
                a.anime.cover?.let {
                    b.setPosterArtUri(Uri.parse(it))
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                }
                val uri = cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
                if (uri != null) ids.add(ContentUris.parseId(uri).toString()) else lastError = "recusado"
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        prefs.homeIds = ids
        return "${ids.size} publicado(s)" + if (lastError.isNotEmpty()) " (erro: $lastError)" else ""
    }
}

class HomeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        return try {
            val nowSec = System.currentTimeMillis() / 1000
            val off = prefs.offsetMinutes * 60L
            val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            val list = withContext(Dispatchers.IO) { AniList.fetch(nowSec - 86400 - off, endOfToday - off) }
            val favs = prefs.favorites
            val selected = list.filter { it.anime.id in favs }
            prefs.homeStatus = withContext(Dispatchers.IO) { HomeScreen.publish(applicationContext, selected, prefs) }
            Result.success()
        } catch (e: Exception) {
            prefs.homeStatus = "Erro ao buscar a programação: ${e.message}"
            Result.retry()
        }
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HomeWorker>(1, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("home", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<HomeWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("home-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
