package com.par9uet.jm.worker

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.par9uet.jm.cache.getComicChapterDownloadDir
import com.par9uet.jm.cache.getComicCoverDownloadFile
import com.par9uet.jm.cache.writeComicCacheConfig
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.data.models.ImageResultState
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.UpdateComicCover
import com.par9uet.jm.database.model.UpdateComicProgress
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.database.model.UpdateComicZipPath
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.DownloadWorkCoordinator
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.utils.COMIC_CACHE_NOTIFICATION_ID_BASE
import com.par9uet.jm.utils.DownloadSpeedTracker
import com.par9uet.jm.utils.cancelProgressNotification
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.showProgressNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream

private const val DOWNLOAD_PAGE_TIMEOUT_MS = 180_000L
private const val DOWNLOAD_MAX_ATTEMPTS = 6
private const val MAX_PROGRESS_UPDATES_PER_CHAPTER = 20
private val downloadConcurrencyGate = Semaphore(
    Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
)
private val cacheWriteMutex = Mutex()

internal fun shouldStreamOriginalPage(
    comicId: Int,
    scrambleId: Int,
    speed: String,
    url: String,
): Boolean {
    val isGif = url.substringBefore('?').endsWith(".gif", ignoreCase = true)
    return !isGif && (comicId <= scrambleId || speed == "1")
}

class DownloadComicWorker(
    private val appContext: Context,
    params: WorkerParameters,
    private val downloadComicDao: DownloadComicDao,
    private val remoteSettingManager: RemoteSettingManager,
    private val localSettingManager: LocalSettingManager,
    private val comicRepository: ComicRepository,
    private val imageLoader: ImageLoader,
    private val downloadToastAggregator: DownloadToastAggregator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val comicId = inputData.getInt("comicId", -1)
        if (comicId == -1) return Result.failure()
        return downloadConcurrencyGate.withPermit {
            DownloadWorkCoordinator.withChapterLock(comicId) {
                performDownload()
            }
        }
    }

    private suspend fun performDownload(): Result {
        val comicId = inputData.getInt("comicId", -1)
        val batchId = inputData.getString("batchId").orEmpty()
        val batchTotal = inputData.getInt("batchTotal", 1)
        if (comicId == -1) {
            return Result.failure()
        }

        val coverOwnerId = downloadComicDao.getById(comicId)?.let {
            it.groupId.takeIf { g -> g != 0 } ?: comicId
        } ?: comicId

        var trackingStarted = false
        return try {
            val downloadTask = downloadComicDao.getById(comicId) ?: return Result.failure()
            if (downloadTask.status != "pending" && downloadTask.status != "downloading") {
                return Result.success()
            }
            downloadComicDao.updateStatus(UpdateComicStatus(comicId, "downloading"))
            DownloadSpeedTracker.startTracking(coverOwnerId)
            trackingStarted = true
            showComicCacheNotification(
                downloadTask,
                resolveGroupProgress(downloadTask, downloadTask.progress)
            )

            val coverPath = downloadCover(downloadTask, coverOwnerId)
            downloadComicDao.updateCover(UpdateComicCover(comicId, coverPath))

            downloadPicList(downloadTask, localSettingManager.localSettingState.value.shunt)
            showComicCacheNotification(downloadTask, updateChapterProgress(downloadTask, 1f))

            val chapterDirPath = getComicChapterDownloadDir(appContext, downloadTask).absolutePath
            downloadComicDao.updateZipPath(UpdateComicZipPath(comicId, chapterDirPath))
            downloadComicDao.updateStatus(UpdateComicStatus(comicId, "complete"))
            writeCacheConfig(comicId)
            cancelComicCacheNotificationIfIdle(downloadTask)
            downloadToastAggregator.report(batchId, batchTotal, comicId, success = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < DOWNLOAD_MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                downloadComicDao.updateStatus(UpdateComicStatus(comicId, "error"))
                downloadComicDao.getById(comicId)?.let {
                    cancelComicCacheNotificationIfIdle(it)
                }
                downloadToastAggregator.report(batchId, batchTotal, comicId, success = false)
                Result.failure()
            }
        } finally {
            if (trackingStarted) {
                DownloadSpeedTracker.stopTracking(coverOwnerId)
            }
        }
    }

    private suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int): String {
        return cacheWriteMutex.withLock {
            val file = getComicCoverDownloadFile(appContext, downloadTask)
            if (isValidImageFile(file)) return@withLock file.absolutePath
            file.delete()

            val tempFile = File(file.parentFile, "${file.name}.${id}.part")
            tempFile.delete()
            try {
                val coverUrl =
                    "${remoteSettingManager.remoteSettingState.value.imgHost}/media/albums/${coverOwnerId}_3x4.jpg"
                val loader = imageLoader
                val request = ImageRequest.Builder(appContext)
                    .data(coverUrl)
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()

                when (val result = loader.execute(request)) {
                    is ErrorResult -> throw IllegalStateException(
                        "封面下载失败：${result.throwable.message ?: "未知错误"}",
                        result.throwable,
                    )
                    is SuccessResult -> {
                        val bitmap = result.drawable.toBitmap()
                        FileOutputStream(tempFile).use { out ->
                            check(bitmap.compressWebpCompat(50, out)) { "封面压缩失败" }
                        }
                        check(isValidImageFile(tempFile)) { "封面文件无效" }
                        replaceAtomically(tempFile, file)
                        file.absolutePath
                    }
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun downloadPicList(downloadTask: DownloadComic, shunt: String): List<String> {
        return withContext(Dispatchers.IO) {
            val comicId = downloadTask.id
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> throw IllegalStateException(data.message)
                is NetWorkResult.Success<ComicPicListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        throw IllegalStateException("图片列表为空")
                    }

                    val dir = getComicChapterDownloadDir(appContext, downloadTask)
                    val loader = imageLoader
                    val totalPages = data.data.list.size
                    val progressInterval = (totalPages / MAX_PROGRESS_UPDATES_PER_CHAPTER)
                        .coerceAtLeast(1)
                    var maxProgress = downloadComicDao.getById(comicId)?.progress ?: 0f

                    buildList(totalPages) {
                        data.data.list.forEachIndexed { index, url ->
                            val file = File(dir, "$index.webp")
                            val nextProgress = (index + 1).toFloat() / totalPages
                            var downloaded = false
                            if (!isValidImageFile(file)) {
                                file.delete()
                                downloadPage(
                                    file = file,
                                    url = url,
                                    index = index,
                                    comicId = comicId,
                                    scrambleId = data.data.__scrambleId,
                                    speed = data.data.__speed,
                                    loader = loader,
                                )
                                downloaded = true
                            }
                            if (downloaded) {
                                DownloadSpeedTracker.addBytes(
                                    downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id,
                                    file.length()
                                )
                            }
                            if (shouldReportProgress(index + 1, totalPages, progressInterval)) {
                                val progress = updateChapterProgressIfAdvanced(
                                    downloadTask = downloadTask,
                                    currentMaxProgress = maxProgress,
                                    nextProgress = nextProgress
                                )
                                maxProgress = progress.chapterProgress
                                showComicCacheNotification(downloadTask, progress.groupProgress)
                            }
                            add(file.absolutePath)
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadPage(
        file: File,
        url: String,
        index: Int,
        comicId: Int,
        scrambleId: Int,
        speed: String,
        loader: ImageLoader,
    ) {
        val tempFile = File(file.parentFile, ".${file.name}.${id}.part")
        tempFile.delete()
        try {
            withTimeout(DOWNLOAD_PAGE_TIMEOUT_MS) {
                if (shouldStreamOriginalPage(comicId, scrambleId, speed, url)) {
                    check(comicRepository.downloadImageToFile(url, tempFile)) {
                        "第 ${index + 1} 页下载失败"
                    }
                } else {
                    val imageState = ComicPicImageState(
                        index = index,
                        comicId = comicId,
                        originSrc = url,
                        __scrambleId = scrambleId,
                        __speed = speed,
                        picImageLoader = loader,
                        imageFetcher = { comicRepository.downloadImageBytes(comicId, index) },
                        decodedFileOverride = tempFile,
                        cacheInMemory = false,
                        cacheOnDisk = false,
                    )
                    try {
                        imageState.decode(appContext)
                        when (val result = imageState.imageResultState) {
                            is ImageResultState.Success -> Unit
                            is ImageResultState.Failure -> error("第 ${index + 1} 页下载失败：${result.reason}")
                            ImageResultState.Loading -> error("第 ${index + 1} 页仍在加载中")
                        }
                    } finally {
                        imageState.clearDecodedImage()
                    }
                }
            }
            check(isValidImageFile(tempFile)) { "第 ${index + 1} 页文件无效" }
            replaceAtomically(tempFile, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("第 ${index + 1} 页下载或解码失败", e)
        } finally {
            tempFile.delete()
        }
    }

    private fun shouldReportProgress(completedPages: Int, totalPages: Int, interval: Int): Boolean {
        return completedPages == totalPages || completedPages % interval == 0
    }

    private fun isValidImageFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val sampleSize = generateSequence(1) { it * 2 }
            .takeWhile { bounds.outWidth / it > 128 || bounds.outHeight / it > 128 }
            .lastOrNull()
            ?: 1
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return false
        decoded.recycle()
        return true
    }

    private fun replaceAtomically(source: File, target: File) {
        check(source.renameTo(target)) { "无法完成缓存文件写入" }
    }

    private suspend fun writeCacheConfig(comicId: Int) {
        cacheWriteMutex.withLock {
            val current = downloadComicDao.getById(comicId) ?: return@withLock
            val groupId = current.groupId.takeIf { it != 0 } ?: current.id
            val chapters = downloadComicDao.getByGroupId(groupId)
            withContext(Dispatchers.IO) {
                writeComicCacheConfig(appContext, current, chapters)
            }
        }
    }

    private suspend fun updateChapterProgress(downloadTask: DownloadComic, progress: Float): Float {
        val chapterProgress = progress.coerceIn(0f, 1f)
        downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        return resolveGroupProgress(downloadTask, chapterProgress)
    }

    private suspend fun updateChapterProgressIfAdvanced(
        downloadTask: DownloadComic,
        currentMaxProgress: Float,
        nextProgress: Float
    ): DownloadProgress {
        val chapterProgress = maxOf(currentMaxProgress, nextProgress.coerceIn(0f, 1f))
        if (chapterProgress > currentMaxProgress) {
            downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        }
        return DownloadProgress(
            chapterProgress = chapterProgress,
            groupProgress = resolveGroupProgress(downloadTask, chapterProgress)
        )
    }

    private suspend fun resolveGroupProgress(downloadTask: DownloadComic, currentProgress: Float): Float {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        if (chapters.isEmpty()) return currentProgress
        return chapters.map { chapter ->
            when {
                chapter.id == downloadTask.id -> currentProgress
                chapter.status == "complete" -> 1f
                else -> chapter.progress.coerceIn(0f, 1f)
            }
        }.average().toFloat().coerceIn(0f, 1f)
    }

    private suspend fun cancelComicCacheNotificationIfIdle(downloadTask: DownloadComic) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        val hasActiveTask = chapters.any { it.status == "pending" || it.status == "downloading" }
        if (!hasActiveTask) {
            cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)
        }
    }

    private fun showComicCacheNotification(downloadTask: DownloadComic, progress: Float) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val setting = localSettingManager.localSettingState.value
        if (!setting.showComicCacheNotification) {
            cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)
            return
        }
        val comicName = downloadTask.groupName.ifBlank { downloadTask.name }
        val title = if (setting.showComicCacheNotificationName && comicName.isNotBlank()) {
            "正在缓存$comicName"
        } else {
            "正在缓存漫画"
        }
        val progressPercent = (progress.coerceIn(0f, 1f) * 100).toInt()
        showProgressNotification(
            context = appContext,
            notificationId = COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
            title = title,
            text = "$progressPercent%",
            progressPercent = progressPercent
        )
    }

    private data class DownloadProgress(
        val chapterProgress: Float,
        val groupProgress: Float
    )
}
