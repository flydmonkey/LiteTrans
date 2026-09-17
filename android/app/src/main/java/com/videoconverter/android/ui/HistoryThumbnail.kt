package com.videoconverter.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.ui.theme.ShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_MAX_PX = 256

@Composable
fun HistoryThumbnail(
    job: Job,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val location = historyThumbFileName(job)
    val kind = historyThumbKind(job)
    var bitmap by remember(job.id, location, kind, job.status) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(job.id, location, kind, job.status) {
        bitmap = if (job.status == JobStatus.Completed) {
            withContext(Dispatchers.IO) {
                loadHistoryThumbnail(context, location, kind)?.asImageBitmap()
            }
        } else {
            null
        }
    }
    val failed = job.status == JobStatus.Failed
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(
                if (failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            GlyphBadge(
                kind = when (kind) {
                    HistoryThumbKind.Audio -> AppGlyph.Audio
                    HistoryThumbKind.Image -> AppGlyph.Image
                    HistoryThumbKind.Document -> AppGlyph.Document
                    HistoryThumbKind.Video -> AppGlyph.Video
                },
                emphasized = job.status == JobStatus.Running || job.status == JobStatus.Completed,
                size = 40.dp,
            )
        }
        if (job.status == JobStatus.Running) {
            CircularProgressIndicator(
                progress = { (job.progress / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
            )
        }
        StatusDot(
            job = job,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun StatusDot(
    job: Job,
    modifier: Modifier = Modifier,
) {
    val (color, onColor) = when (job.status) {
        JobStatus.Completed -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        JobStatus.Running -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        JobStatus.Failed -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when (job.status) {
            JobStatus.Completed -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = onColor,
                modifier = Modifier.size(14.dp),
            )
            JobStatus.Running -> Text(
                "${kotlin.math.round(job.progress).toInt()}",
                color = onColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
            )
            JobStatus.Failed -> Text("!", color = onColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            JobStatus.Cancelled -> Text("–", color = onColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            JobStatus.Queued -> Text("…", color = onColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
    }
}

internal fun loadHistoryThumbnail(
    context: Context,
    location: String,
    kind: HistoryThumbKind,
): Bitmap? = runCatching {
    when (kind) {
        HistoryThumbKind.Audio -> null
        HistoryThumbKind.Video -> decodeVideoFrame(context, location)
        HistoryThumbKind.Image -> decodeImage(context, location)
        HistoryThumbKind.Document -> decodePdfPage(context, location)
    }?.let { scaleThumbnail(it) }
}.getOrNull()

private fun decodeVideoFrame(context: Context, location: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        setRetrieverDataSource(retriever, context, location)
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
    } finally {
        retriever.release()
    }
}

private fun setRetrieverDataSource(
    retriever: MediaMetadataRetriever,
    context: Context,
    location: String,
) {
    val uri = Uri.parse(location)
    if (uri.scheme == "content") {
        retriever.setDataSource(context, uri)
    } else {
        retriever.setDataSource(location)
    }
}

private fun decodeImage(context: Context, location: String): Bitmap? {
    val uri = Uri.parse(location)
    return if (uri.scheme == "content") {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    } else {
        BitmapFactory.decodeFile(location.removePrefix("file://"))
    }
}

private fun decodePdfPage(context: Context, location: String): Bitmap? {
    val uri = Uri.parse(location)
    val pfd = if (uri.scheme == "content") {
        context.contentResolver.openFileDescriptor(uri, "r")
    } else {
        android.os.ParcelFileDescriptor.open(
            java.io.File(location.removePrefix("file://")),
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        )
    } ?: return null
    return pfd.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return@use null
            renderer.openPage(0).use { page ->
                val width = page.width.coerceAtLeast(1)
                val height = page.height.coerceAtLeast(1)
                val scale = THUMB_MAX_PX.toFloat() / maxOf(width, height)
                val bmp = Bitmap.createBitmap(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
}

private fun scaleThumbnail(src: Bitmap): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= THUMB_MAX_PX) return src
    val scale = THUMB_MAX_PX.toFloat() / longest
    return Bitmap.createScaledBitmap(
        src,
        (src.width * scale).toInt().coerceAtLeast(1),
        (src.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}
