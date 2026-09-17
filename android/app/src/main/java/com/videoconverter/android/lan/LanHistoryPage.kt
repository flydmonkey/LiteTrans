package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.documentExtension
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.resolveConfig
import com.videoconverter.android.ui.HistorySegment
import com.videoconverter.android.ui.historyJobs

fun renderLanHistoryHtml(
    jobs: List<Job>,
    token: String,
    copy: LanHistoryCopy,
    fileExists: (String) -> Boolean,
): String {
    val sections = listOf(
        Triple(HistorySegment.Video, "video", copy.video),
        Triple(HistorySegment.Audio, "audio", copy.audio),
        Triple(HistorySegment.Document, "document", copy.document),
    )
    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>LiteTrans</title>")
        append("<style>")
        append("body{margin:0;background:#ecece8;color:#1f2428;font-family:-apple-system,sans-serif;display:flex;flex-direction:column;min-height:100vh}")
        append("header{padding:16px 20px;border-bottom:1px solid #d5d2cc}")
        append("main{display:flex;flex:1;min-height:0}")
        append("nav{width:320px;overflow:auto;padding:12px 16px;box-sizing:border-box}")
        append(".item{padding:10px 12px;border-radius:8px;cursor:pointer}")
        append(".item.selected{background:#fff;box-shadow:inset 3px 0 0 #c45a2a}")
        append(".child{padding-left:20px;font-size:13px}")
        append(".stage{flex:1;display:flex;flex-direction:column;background:#ecece8;padding:16px}")
        append(".player{flex:1;background:#111;border-radius:12px;display:flex;align-items:center;justify-content:center;min-height:240px;overflow:hidden}")
        append(".player video,.player audio,.player img,.player iframe{max-width:100%;max-height:100%;display:none}")
        append(".player iframe{width:100%;height:100%;border:0}")
        append(".meta{padding:12px 4px}")
        append("a{color:#c45a2a}")
        append("</style></head><body>")
        append("<header><strong>LiteTrans</strong>")
        if (token.isEmpty()) {
            append("<p>").append(escapeHtml(copy.warning)).append("</p>")
        }
        append("</header><main><nav>")
        var selectedAssigned = false
        for ((segment, key, title) in sections) {
            append("<section data-segment=\"").append(key).append("\">")
            append("<h2>").append(escapeHtml(title)).append("</h2>")
            val items = historyJobs(jobs, segment)
            if (items.isEmpty()) {
                append("<p>").append(escapeHtml(lanHistoryEmptyLabel(segment, copy))).append("</p>")
            } else {
                for (job in items) {
                    selectedAssigned = appendJobItems(job, token, copy, fileExists, selectedAssigned)
                }
            }
            append("</section>")
        }
        append("</nav>")
        append("<div id=\"stage\" class=\"stage\">")
        append("<div class=\"player\">")
        append("<video controls></video>")
        append("<audio controls></audio>")
        append("<img alt=\"\">")
        append("<iframe title=\"preview\"></iframe>")
        append("</div>")
        append("<div class=\"meta\">")
        append("<p id=\"hint\"></p>")
        append("<a id=\"download\" href=\"#\">").append(escapeHtml(copy.download)).append("</a>")
        append("</div></div></main>")
        append("<script>")
        append("var previewFailed=").append(jsString(copy.previewFailed)).append(";")
        append("var downloadToOpen=").append(jsString(copy.downloadToOpen)).append(";")
        append("var token=").append(jsString(token)).append(";")
        append(LAN_HISTORY_PAGE_JS)
        append("</script></body></html>")
    }
}

private fun StringBuilder.appendJobItems(
    job: Job,
    token: String,
    copy: LanHistoryCopy,
    fileExists: (String) -> Boolean,
    selectedAssigned: Boolean,
): Boolean {
    val format = resolveConfig(job.config).getOrNull()?.container ?: job.config.preset
    val existing = if (job.status == JobStatus.Completed) {
        jobOutputPaths(job).mapIndexedNotNull { index, path ->
            if (fileExists(path)) index to path else null
        }
    } else {
        emptyList()
    }
    val multi = existing.size > 1
    var selected = selectedAssigned
    if (multi) {
        append("<div class=\"item\">")
        append(escapeHtml(job.displayName))
        append(" ")
        append(escapeHtml(format))
        append(" ")
        append(escapeHtml(lanStatusLabel(job.status, copy)))
        for ((index, path) in existing) {
            val base = java.io.File(path).name.ifBlank { job.displayName }
            selected = appendOpenableItem(
                job = job,
                index = index,
                multi = true,
                path = path,
                label = base,
                token = token,
                extraClass = " child",
                selected = !selected,
            )
        }
        append("</div>")
        return selected
    }
    val openable = existing.singleOrNull()
    if (openable != null) {
        val (index, path) = openable
        return appendOpenableItem(
            job = job,
            index = index,
            multi = false,
            path = path,
            label = "${job.displayName} $format ${lanStatusLabel(job.status, copy)}",
            token = token,
            extraClass = "",
            selected = !selected,
        )
    }
    append("<div class=\"item\">")
    append(escapeHtml(job.displayName))
    append(" ")
    append(escapeHtml(format))
    append(" ")
    append(escapeHtml(lanStatusLabel(job.status, copy)))
    append("</div>")
    return selected
}

private fun StringBuilder.appendOpenableItem(
    job: Job,
    index: Int,
    multi: Boolean,
    path: String,
    label: String,
    token: String,
    extraClass: String,
    selected: Boolean,
): Boolean {
    val kind = lanPreviewKind(lanPreviewFileName(path, job)).wireName()
    val media = lanHistoryDownloadHref(job.id, index, multi, "", "m")
    val download = lanHistoryDownloadHref(job.id, index, multi, token, "d")
    append("<div class=\"item")
    append(extraClass)
    if (selected) append(" selected")
    append("\" data-media=\"").append(escapeHtml(media))
    append("\" data-download=\"").append(escapeHtml(download))
    append("\" data-kind=\"").append(kind)
    append("\" data-id=\"").append(escapeHtml(job.id))
    append("\" data-index=\"").append(index)
    append("\">")
    append(escapeHtml(label))
    append("</div>")
    return true
}

private fun lanPreviewFileName(path: String, job: Job): String {
    val base = java.io.File(path).name
    val extension = base.substringAfterLast('.', "")
    val needsOutputExtension = isLanContentLocation(path) || extension.isBlank()
    if (!needsOutputExtension) return base
    val outputExtension = previewExtensionFromOutput(job)
    if (!outputExtension.isNullOrBlank()) return "file.$outputExtension"
    if ('.' in job.displayName) return job.displayName
    return base
}

private fun previewExtensionFromOutput(job: Job): String? =
    if (isDocumentPreset(job.config.preset)) {
        documentExtension(job.config.preset, job.config.container)
    } else {
        resolveConfig(job.config).getOrNull()?.container
    }

private fun LanPreviewKind.wireName(): String = when (this) {
    LanPreviewKind.Video -> "video"
    LanPreviewKind.Audio -> "audio"
    LanPreviewKind.Pdf -> "pdf"
    LanPreviewKind.Image -> "image"
    LanPreviewKind.File -> "file"
}

private fun jsString(raw: String): String = buildString {
    append('"')
    for (ch in raw) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003c")
            else -> append(ch)
        }
    }
    append('"')
}

private const val LAN_HISTORY_PAGE_JS = """
function withToken(url){
  if(!token) return url;
  return url+(url.indexOf('?')>=0?'&':'?')+'k='+encodeURIComponent(token);
}
var video=document.querySelector('video');
var audio=document.querySelector('audio');
var img=document.querySelector('.player img');
var iframe=document.querySelector('iframe');
var hint=document.getElementById('hint');
var download=document.getElementById('download');
function hideAll(){
  video.removeAttribute('src');video.load();video.style.display='none';
  audio.removeAttribute('src');audio.load();audio.style.display='none';
  img.removeAttribute('src');img.style.display='none';
  iframe.removeAttribute('src');iframe.style.display='none';
  hint.textContent='';
}
function showError(){hideAll();hint.textContent=previewFailed;}
video.onerror=showError;audio.onerror=showError;img.onerror=showError;iframe.onerror=showError;
function select(el){
  document.querySelectorAll('.item.selected').forEach(function(n){n.classList.remove('selected');});
  el.classList.add('selected');
  var kind=el.getAttribute('data-kind');
  var media=el.getAttribute('data-media');
  var dl=el.getAttribute('data-download');
  hideAll();
  if(dl) download.setAttribute('href',dl);
  if(kind==='video'){video.style.display='block';video.src=withToken(media);}
  else if(kind==='audio'){audio.style.display='block';audio.src=withToken(media);}
  else if(kind==='image'){img.style.display='block';img.src=withToken(media);}
  else if(kind==='pdf'){iframe.style.display='block';iframe.src=withToken(media);}
  else {hint.textContent=downloadToOpen;}
}
document.querySelectorAll('[data-media]').forEach(function(el){
  el.addEventListener('click',function(){select(el);});
});
function fromHash(){
  var m=location.hash.match(/^#m\/([^/]+)(?:\/(\d+))?$/);
  if(!m) return null;
  var id=m[1], index=m[2];
  var nodes=document.querySelectorAll('[data-media][data-id="'+id+'"]');
  if(index!=null){
    for(var i=0;i<nodes.length;i++){
      if(nodes[i].getAttribute('data-index')===index) return nodes[i];
    }
  }
  return nodes[0]||null;
}
var initial=fromHash()||document.querySelector('.item.selected[data-media]')||document.querySelector('[data-media]');
if(initial) select(initial);
"""
