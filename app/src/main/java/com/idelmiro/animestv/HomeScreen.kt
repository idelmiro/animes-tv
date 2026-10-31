package com.idelmiro.animestv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Coloca os episódios do dia dos favoritos no "Continuar assistindo" do Google TV. */
object HomeScreen {

    fun publish(ctx: Context, airings: List<Airing>, prefs: Prefs): String {
        val cr = ctx.contentResolver
        for (id in prefs.homeIds) {
            try {
                cr.delete(TvContractCompat.buildWatchNextProgramUri(id.toLong()), null, null)
            } catch (_: Exception) {
            }
        }
        val ids = mutableSetOf<String>()
        var lastError = ""
        for (a in airings) {
            try {
                val ep = try {
                    CrunchyrollApi.seriesIdFromUrl(a.anime.crunchyrollUrl)?.let { CrunchyrollApi.findEpisode(it, a.episode) }
                } catch (_: Exception) {
                    null
                }
                val launch = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "animestv://open?id=${a.anime.id}&url=" + Uri.encode(a.anime.crunchyrollUrl ?: "") +
                            "&ep=" + Uri.encode(ep?.id ?: "") + "&slug=" + Uri.encode(ep?.slug ?: "")
                    )
                ).setPackage(ctx.packageName)

                val b = WatchNextProgram.Builder()
                b.setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
                    .setLastEngagementTimeUtcMillis(a.releaseMillis(prefs.offsetMinutes))
                    .setTitle(a.anime.title)
                    .setEpisodeTitle("Episódio ${a.episode}")
                    .setEpisodeNumber(a.episode)
                    .setIntentUri(Uri.parse(launch.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId("${a.anime.id}-${a.episode}")
                a.anime.cover?.let {
                    b.setPosterArtUri(Uri.parse(it))
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                }
                val uri = cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
                if (uri != null) ids.add(ContentUris.parseId(uri).toString())
                else lastError = "o sistema recusou sem explicar"
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        prefs.homeIds = ids
        val time = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        return buildString {
            append("$time — ${airings.size} episódio(s) de hoje nos favoritos, ${ids.size} publicado(s)")
            if (lastError.isNotEmpty()) append(". Erro: $lastError")
        }
    }
}

class HomeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        return try {
            val nowSec = System.currentTimeMillis() / 1000
            val off = prefs.offsetMinutes * 60L
            val list = withContext(Dispatchers.IO) { AniList.fetch(nowSec - 86400 - off, nowSec - off) }
            val favs = prefs.favorites
            val selected = list.filter { it.anime.id in favs }.sortedByDescending { it.airingAt }
            prefs.homeStatus = HomeScreen.publish(applicationContext, selected, prefs)
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
