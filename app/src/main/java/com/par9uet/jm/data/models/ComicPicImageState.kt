package com.par9uet.jm.data.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.par9uet.jm.cache.getCommonPicDecodeCacheDir
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.logError
import com.par9uet.jm.utils.md5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class ImageResultState {
    object Loading : ImageResultState()
    data class Success(
        val decodeImageBitmap: ImageBitmap,
        val decodeImageAspectRatio: Float
    ) :
        ImageResultState()

    data class Failure(val reason: String) : ImageResultState()
}

internal class DecodeGeneration {
    private var current = 0

    @Synchronized
    fun begin(): Int = ++current

    @Synchronized
    fun invalidate(block: () -> Unit = {}) {
        current++
        block()
    }

    @Synchronized
    fun isCurrent(generation: Int): Boolean = generation == current

    @Synchronized
    fun commit(generation: Int, block: () -> Unit): Boolean {
        if (generation != current) return false
        block()
        return true
    }
}

class ComicPicImageState(
    val index: Int,
    val comicId: Int,
    val originSrc: String,
    val __scrambleId: Int,
    val __speed: String,
    private val picImageLoader: ImageLoader,
    private val imageFetcher: (suspend () -> ByteArray?)? = null,
    private val decodedFileOverride: File? = null,
    private val cacheInMemory: Boolean = true,
    private val cacheOnDisk: Boolean = true,
) {

    companion object {
        private val seedMap = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
        private val cacheWriteMutex = Mutex()
        private val imageProcessingGate = Semaphore(2)
    }

    private val decodeGeneration = DecodeGeneration()

    var imageResultState by mutableStateOf<ImageResultState>(ImageResultState.Loading)

    suspend fun decode(context: Context, downscale: Boolean = false) {
        val generation = decodeGeneration.begin()
        updateImageResult(generation, ImageResultState.Loading)
        withContext(Dispatchers.Default) {
            try {
                imageProcessingGate.withPermit {
                    decodeImage(context, downscale, generation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                logError("ComicPicImage", "解码图片 OOM: ${e.message}")
                System.gc()
                updateImageResult(
                    generation,
                    ImageResultState.Failure("内存不足，无法解码图片")
                )
            } catch (e: Exception) {
                logError("ComicPicImage", "解码图片异常: ${e.stackTraceToString()}")
                updateImageResult(
                    generation,
                    ImageResultState.Failure("图片解码失败：${e.message ?: "未知错误"}")
                )
            }
        }
    }

    private suspend fun decodeImage(
        context: Context,
        downscale: Boolean = false,
        generation: Int,
    ) {
        val cacheDir = getCommonPicDecodeCacheDir(context, comicId)
        val page = extractPageFromUrl()
        val cacheFile = decodedFileOverride ?: File(cacheDir, "$page.webp")
        cacheFile.parentFile?.mkdirs()

        // 检查缓存文件是否存在
        if (cacheFile.exists()) {
            try {
                val options = if (downscale) {
                    BitmapFactory.Options().apply { inSampleSize = 2 }
                } else null
                val decodeImageBitmap =
                    BitmapFactory.decodeFile(cacheFile.absolutePath, options)?.asImageBitmap()
                        ?: run {
                            cacheFile.delete()
                            throw IllegalStateException("缓存图片解码为空")
                        }
                val decodeImageAspectRatio =
                    decodeImageBitmap.width * 1.0f / decodeImageBitmap.height
                updateImageResult(
                    generation,
                    ImageResultState.Success(decodeImageBitmap, decodeImageAspectRatio)
                )
                return
            } catch (e: Exception) {
                logError("ComicPicImage", "缓存图片解码失败，删除并重新解码: ${e.message}")
                cacheFile.delete()
            }
        }

        // 加载原始图片
        val imageData = File(originSrc).takeIf { it.exists() } ?: originSrc
        val request = ImageRequest.Builder(context)
            .data(imageData)
            // 这里必须使用原始 size ，不然解密会有问题，出现白线
            .size { Size.ORIGINAL }
            .allowHardware(false)
            .memoryCachePolicy(if (cacheInMemory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCachePolicy(if (cacheOnDisk) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()

        when (val result = picImageLoader.execute(request)) {
            is SuccessResult -> {
                try {
                    val originalBitmap = result.drawable.toBitmap()
                    val originalImageBitmap = originalBitmap.asImageBitmap()
                    val decodeImageAspectRatio =
                        originalImageBitmap.width * 1.0f / originalImageBitmap.height
                    var decodedImageBitmap = originalImageBitmap
                    if (isGif() || comicId <= __scrambleId || __speed == "1") {
                        saveBitmapAsWebp(originalBitmap, cacheFile)
                    } else {
                        val decodedBitmap = decodeBitmap(originalBitmap, page)
                        saveBitmapAsWebp(decodedBitmap, cacheFile)
                        decodedImageBitmap = decodedBitmap.asImageBitmap()
                    }
                    updateImageResult(
                        generation,
                        ImageResultState.Success(decodedImageBitmap, decodeImageAspectRatio)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    logError("ComicPicImage", "图片处理 OOM: ${e.message}")
                    System.gc()
                    updateImageResult(generation, ImageResultState.Failure("内存不足"))
                } catch (e: Exception) {
                    logError("ComicPicImage", "图片处理失败: ${e.stackTraceToString()}")
                    updateImageResult(
                        generation,
                        ImageResultState.Failure("图片处理失败：${e.message ?: "未知错误"}")
                    )
                }
            }

            is ErrorResult -> {
                // Coil 加载失败，尝试使用内置 API 的 imageFetcher 回退
                val fetchedBytes = try {
                    imageFetcher?.invoke()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("ComicPicImage", "imageFetcher 调用失败: ${e.stackTraceToString()}")
                    null
                }
                if (fetchedBytes != null) {
                    try {
                        val options = if (downscale) {
                            BitmapFactory.Options().apply { inSampleSize = 2 }
                        } else null
                        val originalBitmap = BitmapFactory.decodeByteArray(fetchedBytes, 0, fetchedBytes.size, options)
                        if (originalBitmap != null) {
                            val originalImageBitmap = originalBitmap.asImageBitmap()
                            val decodeImageAspectRatio =
                                originalImageBitmap.width * 1.0f / originalImageBitmap.height
                            var decodedImageBitmap = originalImageBitmap
                            if (isGif() || comicId <= __scrambleId || __speed == "1") {
                                saveBitmapAsWebp(originalBitmap, cacheFile)
                            } else {
                                val decodedBitmap = decodeBitmap(originalBitmap, page)
                                saveBitmapAsWebp(decodedBitmap, cacheFile)
                                decodedImageBitmap = decodedBitmap.asImageBitmap()
                            }
                            updateImageResult(
                                generation,
                                ImageResultState.Success(decodedImageBitmap, decodeImageAspectRatio)
                            )
                            return
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        logError("ComicPicImage", "内置API图片解码 OOM: ${e.message}")
                        System.gc()
                        updateImageResult(generation, ImageResultState.Failure("内存不足"))
                        return
                    } catch (e: Exception) {
                        logError("ComicPicImage", "内置API图片解码失败: ${e.stackTraceToString()}")
                    }
                }
                logError("ComicPicImage", "图片加载失败: ${result.throwable.stackTraceToString()}")
                updateImageResult(generation, ImageResultState.Failure("网络错误"))
            }
        }
    }

    fun clearDecodedImage() {
        decodeGeneration.invalidate {
            val previous = imageResultState
            imageResultState = ImageResultState.Loading
            if (!cacheInMemory && !cacheOnDisk && previous is ImageResultState.Success) {
                runCatching {
                    previous.decodeImageBitmap.asAndroidBitmap()
                        .takeIf { !it.isRecycled }
                        ?.recycle()
                }
            }
        }
    }

    private fun updateImageResult(generation: Int, result: ImageResultState) {
        decodeGeneration.commit(generation) {
            imageResultState = result
        }
    }

    private fun decodeBitmap(originalBitmap: Bitmap, page: String): Bitmap {
        val naturalWidth = originalBitmap.width
        val naturalHeight = originalBitmap.height
        val seed = calculateSeed(comicId, page)
        val remainder = naturalHeight % seed

        val decodedBitmap =
            createBitmap(naturalWidth, naturalHeight)
        val canvas = Canvas(decodedBitmap.asImageBitmap())
        val paint = Paint().apply {
            this.isAntiAlias = false
        }
        val originImageBitmap = originalBitmap.asImageBitmap()

        for (i in 0 until seed) {
            var height = naturalHeight / seed
            var dy = height * i
            val sy = naturalHeight - height * (i + 1) - remainder
            if (i == 0) {
                height += remainder
            } else {
                dy += remainder
            }

            val srcOffset = IntOffset(0, sy)
            val srcSize = IntSize(naturalWidth, height)
            val destOffset = IntOffset(0, dy)
            val destSize = IntSize(naturalWidth, height)

            canvas.drawImageRect(
                originImageBitmap,
                srcOffset,
                srcSize,
                destOffset,
                destSize,
                paint
            )
        }

        return decodedBitmap
    }

    private fun calculateSeed(comicId: Int, pageStr: String): Int {
        val key = "$comicId$pageStr"
        val keyMd5 = md5(key)
        var charCodeOfLastChar = keyMd5.last().code
        val left = 268850
        val right = 421925

        when {
            comicId in left..right -> charCodeOfLastChar %= 10
            comicId >= right + 1 -> charCodeOfLastChar %= 8
        }

        return seedMap.getOrNull(charCodeOfLastChar) ?: 10
    }

    private fun extractPageFromUrl(): String {
        return originSrc.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
    }

    private suspend fun saveBitmapAsWebp(bitmap: Bitmap, file: File) {
        cacheWriteMutex.withLock {
            val tempFile = File(file.parentFile, ".${file.name}.part")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                tempFile.delete()
            }
            try {
                withContext(Dispatchers.Default) {
                    FileOutputStream(tempFile).use { out ->
                        check(bitmap.compressWebpCompat(50, out)) { "图片压缩失败" }
                    }
                }
                withContext(Dispatchers.IO) {
                    check(tempFile.renameTo(file)) { "无法完成图片缓存写入" }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    tempFile.delete()
                }
            }
        }
    }

    private fun isGif(): Boolean {
        return originSrc.substringBefore('?').endsWith(".gif", ignoreCase = true)
    }
}
