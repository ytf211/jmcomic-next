package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ImageResultState
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.ui.components.ComicPicImage
import com.par9uet.jm.ui.viewModel.ComicReadViewModel
import com.par9uet.jm.utils.log
import org.koin.compose.getKoin
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

internal data class VisibleReaderItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal fun mostVisibleReaderPage(
    items: List<VisibleReaderItem>,
    viewportStart: Int,
    viewportEnd: Int,
    pageCount: Int,
): Int? {
    return items
        .asSequence()
        .filter { it.index in 0 until pageCount && it.size > 0 }
        .map { item ->
            val visibleStart = maxOf(item.offset, viewportStart)
            val visibleEnd = minOf(item.offset + item.size, viewportEnd)
            item.index to (visibleEnd - visibleStart).coerceAtLeast(0)
        }
        .filter { (_, visibleSize) -> visibleSize > 0 }
        .maxByOrNull { (_, visibleSize) -> visibleSize }
        ?.first
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun ComicScrollRead(
    lazyListState: LazyListState,
    pagerState: PagerState,
    targetIndex: Int,
    zoomState: ReaderZoomState,
    comicReadViewModel: ComicReadViewModel = koinViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    onUpdateSliderValue: (value: Float) -> Unit,
    localOnly: Boolean,
    chapters: List<ComicChapter>,
    continuousEnabled: Boolean,
) {
    val coroutineScope = rememberCoroutineScope()
    var currentIndexState by comicReadViewModel.currentIndexState
    val comicPicState by comicReadViewModel.comicPicState.collectAsState()
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val list = comicPicState.data ?: listOf()
    val continuousReaderState by comicReadViewModel.continuousReaderState.collectAsState()
    val context = LocalContext.current
    var programmaticScroll by remember { mutableStateOf(false) }

    fun scrollToCurrentPage() {
        if (list.isEmpty()) return
        val target = currentIndexState.coerceIn(0, list.lastIndex)
        currentIndexState = target
        coroutineScope.launch {
            lazyListState.scrollToItem(target)
            pagerState.scrollToPage(target)
            onUpdateSliderValue(target.toFloat())
        }
    }

    LaunchedEffect(targetIndex) {
        if (list.isEmpty()) return@LaunchedEffect
        val target = targetIndex.coerceIn(0, list.lastIndex)
        if (lazyListState.firstVisibleItemIndex != target) {
            programmaticScroll = true
            lazyListState.scrollToItem(target)
            pagerState.scrollToPage(target)
            programmaticScroll = false
        }
    }

    LaunchedEffect(lazyListState, list.size, continuousEnabled, chapters, localSetting.shunt) {
        launch {
            snapshotFlow { lazyListState.isScrollInProgress }
                .filter { it }
                .collect {
                    comicReadViewModel.hideToolBar()
                }
        }
        launch {
            snapshotFlow {
                lazyListState.layoutInfo.visibleItemsInfo
                    .filter { it.index < list.size }
                    .takeIf { it.isNotEmpty() }
                    ?.let { it.first().index to it.last().index }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(120)
                .collect { (first, last) ->
                    comicReadViewModel.decodeVisibleRange(first, last, context)
                    comicReadViewModel.onVisiblePage(
                        globalIndex = last,
                        context = context,
                        localOnly = localOnly,
                        shunt = localSetting.shunt,
                        chapters = chapters,
                        enabled = continuousEnabled,
                    )
                }
        }
        launch {
            snapshotFlow {
                val layoutInfo = lazyListState.layoutInfo
                mostVisibleReaderPage(
                    items = layoutInfo.visibleItemsInfo.map { item ->
                        VisibleReaderItem(
                            index = item.index,
                            offset = item.offset,
                            size = item.size,
                        )
                    },
                    viewportStart = layoutInfo.viewportStartOffset,
                    viewportEnd = layoutInfo.viewportEndOffset,
                    pageCount = list.size,
                )
            }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(150)
                .collect { visibleIndex ->
                    if (programmaticScroll) return@collect
                    log("most visible reader page current=$currentIndexState visible=$visibleIndex")
                    if (currentIndexState != visibleIndex) {
                        currentIndexState = visibleIndex
                        onUpdateSliderValue(visibleIndex.toFloat())
                    }
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(localSetting.readTapMode) {
                awaitPointerEventScope {
                    while (true) {
                        // 1. 在 Initial 阶段观察按下，不消耗事件，确保 Pager 能收到
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = true,
                                pass = PointerEventPass.Final
                            )
                        // 2. 等待抬起
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                        // 3. 判定逻辑：只有在没被消费（说明不是滑动）且距离很短时触发
                        if (up != null && !up.isConsumed) {
                            val distance = (up.position - down.position).getDistance()
                            if (distance < 10.dp.toPx()) {
                                // --- 获取点击位置 ---
                                val screenHeight = size.height
                                val screenWidth = size.width
                                val clickY = up.position.y
                                val clickX = up.position.x

                                when {
                                    localSetting.readTapMode == "side" && clickX < screenWidth / 3 -> {
                                        comicReadViewModel.prev(context)
                                        scrollToCurrentPage()
                                    }

                                    localSetting.readTapMode == "side" && clickX > screenWidth * 2 / 3 -> {
                                        comicReadViewModel.next(context)
                                        scrollToCurrentPage()
                                    }

                                    localSetting.readTapMode != "side" && clickY < screenHeight / 3 -> {
                                        comicReadViewModel.prev(context)
                                        scrollToCurrentPage()
                                    }

                                    localSetting.readTapMode != "side" && clickY > screenHeight * 2 / 3 -> {
                                        comicReadViewModel.next(context)
                                        scrollToCurrentPage()
                                    }

                                    else -> {
                                        comicReadViewModel.triggerToolBar()
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = lazyListState,
            userScrollEnabled = true,
            modifier = Modifier
                .fillMaxSize()
                .readerZoomable(zoomState, enableVerticalPan = false)
        ) {
            items(list, key = {
                "${it.comicId}_${it.originSrc}"
            }) {
                ComicPicImage(
                    comicPicImageState = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            when (val state = it.imageResultState) {
                                is ImageResultState.Success -> {
                                    state.decodeImageAspectRatio
                                }

                                else -> {
                                    9f / 16
                                }
                            }
                        )
                )
            }
            when (val appendState = continuousReaderState.appendState) {
                is com.par9uet.jm.ui.models.ChapterAppendState.Loading -> {
                    item(key = "continuous-reader-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                is com.par9uet.jm.ui.models.ChapterAppendState.Error -> {
                    item(key = "continuous-reader-error") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = appendState.message)
                            Button(
                                onClick = {
                                    comicReadViewModel.retryNextChapter(
                                        context = context,
                                        localOnly = localOnly,
                                        shunt = localSetting.shunt,
                                        chapters = chapters,
                                    )
                                }
                            ) {
                                Text(text = "重试")
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}
