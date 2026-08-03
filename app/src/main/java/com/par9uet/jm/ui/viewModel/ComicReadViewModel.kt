package com.par9uet.jm.ui.viewModel

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.par9uet.jm.cache.getComicChapterDownloadDir
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.listComicImageFiles
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.models.ChapterAppendState
import com.par9uet.jm.ui.models.ChapterPageRange
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.models.ContinuousReaderState
import com.par9uet.jm.ui.models.ReaderPosition
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

class ComicReadViewModel(
    private val comicRepository: ComicRepository,
    private val picImageLoader: ImageLoader,
    private val localSettingManager: LocalSettingManager,
    private val downloadComicDao: DownloadComicDao,
    private val toastManager: ToastManager,
    private val readHistoryManager: ReadHistoryManager,
) : ViewModel() {
    var isShowToolBar = mutableStateOf(false)
    var currentIndexState = mutableIntStateOf(0)
    var loadedComicId = mutableIntStateOf(-1)
    var readHistoryComicId = mutableIntStateOf(-1)
    private val _comicPicState = MutableStateFlow(
        CommonUIState<List<ComicPicImageState>>(
            isLoading = true
        )
    )
    val comicPicState = _comicPicState.asStateFlow()
    private val _comicDetailState = MutableStateFlow(CommonUIState<Comic>())
    val comicDetailState = _comicDetailState.asStateFlow()
    private val _localChapterList = MutableStateFlow<List<ComicChapter>>(emptyList())
    val localChapterList = _localChapterList.asStateFlow()
    private val _continuousReaderState = MutableStateFlow(ContinuousReaderState())
    val continuousReaderState = _continuousReaderState.asStateFlow()

    val currentReaderPosition: ReaderPosition?
        get() = _continuousReaderState.value.positionAt(currentIndexState.intValue)

    private var appendJob: Job? = null
    private var readerGeneration = 0

    val size: Int get() = _comicPicState.value.data?.size ?: 0

    private val prefetchSet = mutableSetOf<Int>()
    private val decodeJobs = mutableMapOf<Int, Job>()
    // 内存优化模式下的并发解码信号量，按需创建
    private var decodeSemaphore: Semaphore? = null
    private var decodeSemaphorePermits: Int = 0
    private fun getDecodeSemaphore(): Semaphore? {
        val setting = localSettingManager.localSettingState.value
        if (!setting.readMemoryOptEnabled) return null
        val target = setting.readDecodeConcurrency.coerceAtLeast(1)
        if (decodeSemaphore == null || decodeSemaphorePermits != target) {
            decodeSemaphore = Semaphore(target)
            decodeSemaphorePermits = target
        }
        return decodeSemaphore
    }

    private fun resetContinuousReader() {
        appendJob?.cancel()
        appendJob = null
        decodeJobs.values.forEach { it.cancel() }
        decodeJobs.clear()
        readerGeneration++
        _comicPicState.value.data?.forEach { it.clearDecodedImage() }
        _continuousReaderState.value = ContinuousReaderState()
        prefetchSet.clear()
    }

    fun cancelContinuousAppend() {
        if (_continuousReaderState.value.appendState !is ChapterAppendState.Loading) return
        readerGeneration++
        appendJob?.cancel()
        appendJob = null
        _continuousReaderState.value = _continuousReaderState.value.copy(
            appendState = ChapterAppendState.Idle
        )
    }

    private fun setInitialChapterRange(chapterId: Int, chapterName: String, pageCount: Int) {
        _continuousReaderState.value = ContinuousReaderState().append(
            ChapterPageRange(
                chapterId = chapterId,
                chapterName = chapterName,
                startIndex = 0,
                pageCount = pageCount,
            )
        )
    }

    fun getComicDetail(comicId: Int, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            _comicDetailState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.getComicDetail(comicId)) {
                is NetWorkResult.Error -> {
                    readHistoryComicId.intValue = comicId
                    _comicDetailState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicDetailResponse> -> {
                    val comic = data.data.toComic()
                    readHistoryComicId.intValue = readHistoryManager.historyKey(comic, comicId)
                    _comicDetailState.update {
                        it.copy(
                            data = comic
                        )
                    }
                }
            }
            _comicDetailState.update {
                it.copy(isLoading = false)
            }
            onComplete?.invoke()
        }
    }

    fun clearComicDetail() {
        _comicDetailState.update { CommonUIState() }
    }

    fun collect(comicId: Int) {
        updateCollectState(comicId, true)
    }

    fun unCollect(comicId: Int) {
        updateCollectState(comicId, false)
    }

    private fun updateCollectState(comicId: Int, targetCollect: Boolean) {
        viewModelScope.launch {
            when (val data: NetWorkResult<CollectComicResponse> = if (targetCollect) {
                comicRepository.collectComic(comicId)
            } else {
                comicRepository.unCollectComic(comicId)
            }) {
                is NetWorkResult.Error -> {
                    toastManager.showAsync(data.message)
                }

                is NetWorkResult.Success<CollectComicResponse> -> {
                    toastManager.showAsync(if (targetCollect) "收藏成功" else "取消收藏成功")
                    _comicDetailState.update {
                        it.copy(
                            data = it.data?.copy(isCollect = targetCollect)
                        )
                    }
                }
            }
        }
    }

    fun getComicPicList(comicId: Int, shunt: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            resetContinuousReader()
            _localChapterList.value = emptyList()
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            prefetchSet.clear()
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> {
                    _comicPicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicPicListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        _comicPicState.update {
                            it.copy(
                                data = emptyList(),
                                isError = true,
                                errorMsg = "图片列表为空"
                            )
                        }
                    } else {
                        _comicPicState.update {
                            it.copy(
                                data = data.data.list.mapIndexed { index, item ->
                                    ComicPicImageState(
                                        index,
                                        comicId,
                                        item,
                                        data.data.__scrambleId,
                                        data.data.__speed,
                                        picImageLoader,
                                        imageFetcher = {
                                            comicRepository.downloadImageBytes(comicId, index)
                                        }
                                    )
                                }
                            )
                        }
                        setInitialChapterRange(
                            chapterId = comicId,
                            chapterName = comicDetailState.value.data?.comicChapterList
                                ?.firstOrNull { it.id == comicId }?.name.orEmpty(),
                            pageCount = data.data.list.size,
                        )
                        readHistoryComicId.intValue = readHistoryManager.markRead(
                            readHistoryComicId.intValue.takeIf { it > 0 } ?: comicId,
                            comicId
                        )
                        onSuccess?.invoke()
                    }
                }
            }
            _comicPicState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun getLocalComicPicList(comicId: Int, context: Context, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            resetContinuousReader()
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            prefetchSet.clear()
            val downloadComic = downloadComicDao.getById(comicId)
            val groupId = downloadComic?.groupId?.takeIf { it != 0 } ?: comicId
            loadLocalChapterList(comicId, downloadComic)
            val imageDir = ensureLocalImageDir(context, comicId, downloadComic)
            val files = imageDir
                ?.let(::listComicImageFiles)
                .orEmpty()

            if (files.isEmpty()) {
                _comicPicState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMsg = "未找到本地缓存图片"
                    )
                }
                return@launch
            }

            _comicPicState.update {
                it.copy(
                    data = files.mapIndexed { index, file ->
                        ComicPicImageState(
                            index = index,
                            comicId = comicId,
                            originSrc = file.absolutePath,
                            __scrambleId = Int.MAX_VALUE,
                            __speed = "1",
                            picImageLoader = picImageLoader
                        )
                    },
                    isLoading = false
                )
            }
            setInitialChapterRange(
                chapterId = comicId,
                chapterName = downloadComic?.chapterName.orEmpty(),
                pageCount = files.size,
            )
            readHistoryComicId.intValue = readHistoryManager.markRead(groupId, comicId)
            onSuccess?.invoke()
        }
    }

    private suspend fun loadLocalChapterList(comicId: Int, currentComic: DownloadComic?) {
        val groupId = currentComic?.groupId?.takeIf { it != 0 } ?: comicId
        val chapters = downloadComicDao.getCompleteByGroupId(groupId)
        _localChapterList.value = chapters.mapIndexed { index, item ->
            ComicChapter(
                id = item.id,
                name = item.chapterName.ifBlank {
                    if (chapters.size > 1) "第 ${index + 1} 章" else item.name
                }
            )
        }
    }

    private fun ensureLocalImageDir(context: Context, comicId: Int, downloadComic: DownloadComic?): File? {
        val zipPath = downloadComic?.zipPath.orEmpty()
        val directDir = zipPath.takeIf { it.isNotBlank() }?.let(::File)
        if (directDir?.isDirectory == true && listComicImageFiles(directDir).isNotEmpty()) {
            return directDir
        }

        if (downloadComic != null) {
            val namedDir = getComicChapterDownloadDir(context, downloadComic)
            if (namedDir.exists() && listComicImageFiles(namedDir).isNotEmpty()) {
                return namedDir
            }
        }

        val dir = File(getDownloadDir(context), "$comicId")
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            return dir
        }
        if (zipPath.isBlank()) {
            return dir.takeIf { it.exists() }
        }
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            return dir.takeIf { it.exists() }
        }
        dir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                if (!entry.isDirectory) {
                    val output = File(dir, File(entry.name).name)
                    FileOutputStream(output).use { out ->
                        zipIn.copyTo(out)
                    }
                }
                zipIn.closeEntry()
            }
        }
        return dir
    }

    fun onVisiblePage(
        globalIndex: Int,
        context: Context,
        localOnly: Boolean,
        shunt: String,
        chapters: List<ComicChapter>,
        enabled: Boolean,
    ) {
        if (!enabled) return
        val state = _continuousReaderState.value
        if (state.appendState !is ChapterAppendState.Idle) return
        if (!state.shouldPrefetch(globalIndex, trailingPages = 3)) return
        val currentChapterId = state.positionAt(globalIndex)?.chapterId ?: return
        val chapterIndex = chapters.indexOfFirst { it.id == currentChapterId }
        val nextChapter = chapters.getOrNull(chapterIndex + 1)
        if (nextChapter == null) {
            _continuousReaderState.value = state.copy(appendState = ChapterAppendState.End)
            return
        }
        if (state.chapters.any { it.chapterId == nextChapter.id }) return

        val generation = readerGeneration
        _continuousReaderState.value = state.copy(
            appendState = ChapterAppendState.Loading(nextChapter.id)
        )
        appendJob = viewModelScope.launch {
            try {
                val pages = if (localOnly) {
                    buildLocalPageStates(nextChapter.id, context)
                } else {
                    buildOnlinePageStates(nextChapter.id, shunt)
                }
                if (generation != readerGeneration) return@launch
                if (pages.isEmpty()) {
                    throw IllegalStateException("下一章没有可阅读图片")
                }
                _comicPicState.update { current ->
                    current.copy(data = current.data.orEmpty() + pages)
                }
                _continuousReaderState.value = _continuousReaderState.value.append(
                    ChapterPageRange(
                        chapterId = nextChapter.id,
                        chapterName = nextChapter.name,
                        startIndex = 0,
                        pageCount = pages.size,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == readerGeneration) {
                    _continuousReaderState.value = _continuousReaderState.value.copy(
                        appendState = ChapterAppendState.Error(
                            chapterId = nextChapter.id,
                            message = e.message ?: "下一章加载失败"
                        )
                    )
                }
            } finally {
                appendJob = null
            }
        }
    }

    fun retryNextChapter(
        context: Context,
        localOnly: Boolean,
        shunt: String,
        chapters: List<ComicChapter>,
    ) {
        if (_continuousReaderState.value.appendState !is ChapterAppendState.Error) return
        val retryIndex = _continuousReaderState.value.chapters.lastOrNull()?.endIndex ?: return
        _continuousReaderState.value = _continuousReaderState.value.copy(
            appendState = ChapterAppendState.Idle
        )
        onVisiblePage(retryIndex, context, localOnly, shunt, chapters, enabled = true)
    }

    private suspend fun buildOnlinePageStates(
        comicId: Int,
        shunt: String,
    ): List<ComicPicImageState> {
        return when (val data = comicRepository.getComicPicList(comicId, shunt)) {
            is NetWorkResult.Error -> throw IllegalStateException(data.message)
            is NetWorkResult.Success<ComicPicListResponse> -> data.data.list.mapIndexed { index, item ->
                ComicPicImageState(
                    index = index,
                    comicId = comicId,
                    originSrc = item,
                    __scrambleId = data.data.__scrambleId,
                    __speed = data.data.__speed,
                    picImageLoader = picImageLoader,
                    imageFetcher = { comicRepository.downloadImageBytes(comicId, index) }
                )
            }
        }
    }

    private suspend fun buildLocalPageStates(
        comicId: Int,
        context: Context,
    ): List<ComicPicImageState> {
        val downloadComic = downloadComicDao.getById(comicId)
        val files = ensureLocalImageDir(context, comicId, downloadComic)
            ?.let(::listComicImageFiles)
            .orEmpty()
        return files.mapIndexed { index, file ->
            ComicPicImageState(
                index = index,
                comicId = comicId,
                originSrc = file.absolutePath,
                __scrambleId = Int.MAX_VALUE,
                __speed = "1",
                picImageLoader = picImageLoader
            )
        }
    }
    fun decodeIndex(index: Int, context: Context) {
        if (size <= 0 || index !in 0 until size) return
        log("decode index $index")
        val count = localSettingManager.localSettingState.value.prefetchCount
        val start = max(0, index - count)
        val end = min(size - 1, index + count)
        trimDecodedImages(start, end)
        decode(index, context) {
            for (i in index + 1..end) {
                log("pre decode index $i")
                decode(i, context)
            }
            for (i in index - 1 downTo start) {
                log("pre decode index $i")
                decode(i, context)
            }
        }
    }

    fun decodeVisibleRange(firstIndex: Int, lastIndex: Int, context: Context) {
        if (size <= 0) return
        val count = localSettingManager.localSettingState.value.prefetchCount
        val start = max(0, min(firstIndex, lastIndex) - count)
        val end = min(size - 1, max(firstIndex, lastIndex) + count)
        trimDecodedImages(start, end)
        for (i in start..end) {
            decode(i, context)
        }
    }

    fun prev(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = max(0, currentIndexState.intValue - 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    fun next(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = min(size - 1, currentIndexState.intValue + 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    private fun trimDecodedImages(keepStart: Int, keepEnd: Int) {
        val pages = comicPicState.value.data.orEmpty()
        prefetchSet
            .filter { it !in keepStart..keepEnd }
            .toList()
            .forEach { index ->
                prefetchSet.remove(index)
                decodeJobs.remove(index)?.cancel()
                pages.getOrNull(index)?.clearDecodedImage()
            }
    }

    private fun decode(index: Int, context: Context, onComplete: (() -> Unit)? = null) {
        val comicPicImageState = comicPicState.value.data?.getOrNull(index) ?: return
        if (prefetchSet.contains(index)) {
            onComplete?.invoke()
            return
        }
        val setting = localSettingManager.localSettingState.value
        val downscale = setting.readMemoryOptEnabled
        val semaphore = getDecodeSemaphore()
        prefetchSet.add(index)
        val job = viewModelScope.launch {
            try {
                if (semaphore != null) {
                    semaphore.withPermit {
                        comicPicImageState.decode(context, downscale = downscale)
                    }
                } else {
                    comicPicImageState.decode(context, downscale = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("decode index $index failed: ${e.message}")
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (decodeJobs[index] === currentJob) {
                    decodeJobs.remove(index)
                }
            }
            onComplete?.invoke()
        }
        decodeJobs[index] = job
    }

    fun triggerToolBar() {
        isShowToolBar.value = !isShowToolBar.value
    }

    fun hideToolBar() {
        isShowToolBar.value = false
    }

    fun showToolBar() {
        isShowToolBar.value = true
    }
}
