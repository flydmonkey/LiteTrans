package com.videoconverter.android.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoconverter.android.R
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.clampPageRange
import com.videoconverter.android.domain.documentSourceKind
import com.videoconverter.android.domain.stepPage
import com.videoconverter.android.ui.theme.ShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DocumentSourcePreview(
    media: MediaInfo,
    onChange: (MediaInfo) -> Unit,
) {
    when (documentSourceKind(media.displayName)) {
        DocumentSourceKind.Pdf -> PdfPagePanel(media, onChange)
        DocumentSourceKind.Image -> ImageSourcePreview(media)
        DocumentSourceKind.Word, DocumentSourceKind.Excel -> OfficeSourcePreview(media)
        else -> Unit
    }
}

@Composable
private fun PdfPagePanel(
    media: MediaInfo,
    onChange: (MediaInfo) -> Unit,
) {
    val pages = media.pageCount ?: return
    val start = media.pageStart ?: 1
    val end = media.pageEnd ?: pages
    val previewPage = start.coerceIn(1, pages)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Panel))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.document_page_range), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        SourceBitmap(media.sourceUri, previewPage)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageStepper(
                label = stringResource(R.string.document_start_page),
                value = start,
                pages = pages,
                modifier = Modifier.weight(1f),
            ) { value ->
                val (lo, hi) = clampPageRange(value, end, pages)
                onChange(media.copy(pageStart = lo, pageEnd = hi))
            }
            PageStepper(
                label = stringResource(R.string.document_end_page),
                value = end,
                pages = pages,
                modifier = Modifier.weight(1f),
            ) { value ->
                val (lo, hi) = clampPageRange(start, value, pages)
                onChange(media.copy(pageStart = lo, pageEnd = hi))
            }
        }
        Text(
            stringResource(R.string.document_total_pages, pages),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ImageSourcePreview(media: MediaInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Panel))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.document_preview), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        SourceBitmap(media.sourceUri, page = null)
    }
}

@Composable
private fun OfficeSourcePreview(media: MediaInfo) {
    val kind = documentSourceKind(media.displayName) ?: return
    val titleRes = officePreviewTitleRes(kind)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Panel))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (titleRes != 0) stringResource(titleRes) else media.displayName,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            media.displayName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Text(
            stringResource(R.string.document_office_preview),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun PageStepper(
    label: String,
    value: Int,
    pages: Int,
    modifier: Modifier = Modifier,
    onValue: (Int) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepperButton("−") { onValue(stepPage(value, pages, -1)) }
            Text(
                value.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            StepperButton("+") { onValue(stepPage(value, pages, 1)) }
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(ShapeTokens.Chip))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceBitmap(uri: String, page: Int?) {
    val context = LocalContext.current
    var bitmap by remember(uri, page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, page) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (page != null) {
                    renderPdfPreview(context, uri, page)
                } else {
                    decodeImagePreview(context, uri)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 280.dp)
            .clip(RoundedCornerShape(ShapeTokens.Chip))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(stringResource(R.string.document_preview_failed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

private fun renderPdfPreview(context: android.content.Context, uri: String, page: Int): ImageBitmap? {
    return context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val index = (page - 1).coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(index).use { pdfPage ->
                val width = pdfPage.width.coerceAtLeast(1)
                val height = pdfPage.height.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }
    }
}

private fun decodeImagePreview(context: android.content.Context, uri: String): ImageBitmap? {
    val parsed = Uri.parse(uri)
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, parsed))
    } else {
        @Suppress("DEPRECATION")
        android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, parsed)
    }
    return bitmap.asImageBitmap()
}
