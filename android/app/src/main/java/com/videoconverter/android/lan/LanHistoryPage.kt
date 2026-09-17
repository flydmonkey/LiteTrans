package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job

fun renderLanHistoryHtml(
    jobs: List<Job>,
    token: String,
    copy: LanHistoryCopy,
    fileExists: (String) -> Boolean,
): String {
    val items = lanLibraryItems(jobs, fileExists)
    val defaultTab = lanDefaultLibraryTab(items)
    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>LiteTrans</title>")
        append("<style>")
        append(LAN_HISTORY_PAGE_CSS)
        append("</style></head><body>")
        append("<header><strong>LiteTrans</strong>")
        if (token.isEmpty()) {
            append("<p class=\"warn\">").append(escapeHtml(copy.warning)).append("</p>")
        }
        append("</header>")
        append("<nav class=\"tabs\" role=\"tablist\">")
        for (tab in LanLibraryTab.entries) {
            val count = lanLibraryItemsFor(items, tab).size
            val on = tab == defaultTab
            append("<button type=\"button\" data-tab-btn=\"").append(tab.wireName()).append("\"")
            if (on) append(" class=\"on\"")
            append(" aria-selected=\"").append(if (on) "true" else "false").append("\" role=\"tab\">")
            append(escapeHtml(lanLibraryTabLabel(tab, copy)))
            append(" ").append(count)
            append("</button>")
        }
        append("</nav>")
        append("<main>")
        append("<div class=\"rail\">")
        for (tab in LanLibraryTab.entries) {
            val tabItems = lanLibraryItemsFor(items, tab)
            append("<div class=\"pane\" data-pane=\"").append(tab.wireName()).append("\"")
            if (tab != defaultTab) append(" hidden")
            append(">")
            if (tabItems.isEmpty()) {
                append("<p class=\"empty\">").append(escapeHtml(lanLibraryEmptyLabel(tab, copy))).append("</p>")
            } else {
                if (tab == LanLibraryTab.Image) append("<div class=\"thumbs\">")
                tabItems.forEachIndexed { index, item ->
                    appendLibraryItem(
                        item = item,
                        token = token,
                        downloadLabel = copy.download,
                        selected = tab == defaultTab && index == 0,
                    )
                }
                if (tab == LanLibraryTab.Image) append("</div>")
            }
            append("</div>")
        }
        append("</div>")
        append("<div class=\"stage\">")
        append("<div class=\"player\">")
        append("<video controls></video>")
        append("<audio controls></audio>")
        append("<img alt=\"\">")
        append("<iframe title=\"preview\"></iframe>")
        append("</div>")
        append("<div class=\"meta\"><p id=\"hint\"></p></div></div></main>")
        append("<script>")
        append("var previewFailed=").append(jsString(copy.previewFailed)).append(";")
        append("var downloadToOpen=").append(jsString(copy.downloadToOpen)).append(";")
        append("var token=").append(jsString(token)).append(";")
        append(LAN_HISTORY_PAGE_JS)
        append("</script></body></html>")
    }
}

private fun StringBuilder.appendLibraryItem(
    item: LanLibraryItem,
    token: String,
    downloadLabel: String,
    selected: Boolean,
) {
    val media = lanHistoryDownloadHref(item.jobId, item.index, item.needsIndex, "", "m")
    val download = lanHistoryDownloadHref(item.jobId, item.index, item.needsIndex, token, "d")
    append("<div class=\"item")
    if (item.tab == LanLibraryTab.Image) append(" thumb")
    if (selected) append(" selected")
    append("\" data-media=\"").append(escapeHtml(media))
    append("\" data-download=\"").append(escapeHtml(download))
    append("\" data-kind=\"").append(item.kind.wireName())
    append("\" data-id=\"").append(escapeHtml(item.jobId))
    append("\" data-index=\"").append(item.index)
    append("\" data-tab=\"").append(item.tab.wireName())
    append("\">")
    append("<span class=\"name\">")
    when (item.tab) {
        LanLibraryTab.Image -> {
            val thumb = lanHistoryDownloadHref(item.jobId, item.index, item.needsIndex, token, "m")
            append("<img class=\"thumb-src\" alt=\"\" src=\"").append(escapeHtml(thumb)).append("\">")
        }
        LanLibraryTab.Document -> {
            append(escapeHtml(item.label))
            append(" <span class=\"fmt\">").append(escapeHtml(item.format)).append("</span>")
        }
        else -> append(escapeHtml(item.label))
    }
    append("</span>")
    append("<a class=\"row-dl\" href=\"").append(escapeHtml(download)).append("\">")
    append(escapeHtml(downloadLabel))
    append("</a>")
    append("</div>")
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

private const val LAN_HISTORY_PAGE_CSS = """
html,body{height:100%}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:#ecece8;color:#1f2428;font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Noto Sans SC",sans-serif}
header{display:flex;align-items:baseline;gap:16px;padding:18px 22px 10px}
header strong{font-size:1.25rem;font-weight:650;letter-spacing:-.02em}
header .warn{margin:0;color:#5c6460;font-size:.92rem}
.tabs{display:flex;gap:4px;padding:0 18px;border-bottom:1px solid #d5d2cc}
.tabs button{appearance:none;background:none;border:0;border-bottom:2px solid transparent;margin:0;padding:10px 12px 8px;color:#5c6460;font:inherit;font-weight:500;cursor:pointer;transition:color .15s ease,border-color .15s ease}
.tabs button.on{color:#c45a2a;font-weight:600;border-bottom-color:#c45a2a}
.tabs button:focus-visible{outline:2px solid #c45a2a;outline-offset:2px}
main{display:flex;flex:1;min-height:0;gap:16px;padding:16px 18px 20px}
.rail{width:320px;flex:0 0 320px;background:#fff;border:1px solid #d5d2cc;border-radius:14px;overflow:auto;padding:10px;box-sizing:border-box}
.pane[hidden]{display:none}
.empty{margin:18px 10px;color:#5c6460}
.item{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:8px;cursor:pointer}
.item .name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item.selected{background:#f6f1ea;box-shadow:inset 3px 0 0 #c45a2a}
.row-dl{flex:0 0 auto;color:#c45a2a;font-weight:600;font-size:.9rem;text-decoration:none}
.row-dl:hover{text-decoration:underline}
.thumbs{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.item.thumb{display:block;padding:0;overflow:hidden;border:2px solid transparent;position:relative}
.item.thumb.selected{box-shadow:none;border-color:#c45a2a;background:transparent}
.item.thumb .name{display:block}
.item.thumb .row-dl{position:absolute;right:6px;bottom:6px;padding:3px 8px;border-radius:6px;background:rgba(255,255,255,.92)}
.thumb-src{display:block;width:100%;height:88px;object-fit:cover;background:#d5d2cc}
.fmt{color:#5c6460;font-size:.85em;margin-left:.35em}
.stage{flex:1;display:flex;flex-direction:column;min-width:0}
.player{flex:1;min-height:240px;background:#111;border-radius:14px;display:flex;align-items:center;justify-content:center;overflow:hidden}
.player video,.player audio,.player img,.player iframe{max-width:100%;max-height:100%;display:none}
.player iframe{width:100%;height:100%;border:0}
.player audio{width:80%}
.meta{padding:12px 4px 0}
#hint{margin:0;color:#5c6460}
@media (max-width:720px){main{flex-direction:column}.rail{width:auto;flex:none;max-height:40vh}.player{min-height:200px}}
@media (prefers-reduced-motion: reduce){*{transition:none!important}}
"""

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
function hideAll(){
  video.removeAttribute('src');video.load();video.style.display='none';
  audio.removeAttribute('src');audio.load();audio.style.display='none';
  img.removeAttribute('src');img.style.display='none';
  iframe.removeAttribute('src');iframe.style.display='none';
  hint.textContent='';
}
function showError(){hideAll();hint.textContent=previewFailed;}
video.onerror=showError;audio.onerror=showError;img.onerror=showError;iframe.onerror=showError;
function showTab(name){
  document.querySelectorAll('[data-tab-btn]').forEach(function(btn){
    var on=btn.getAttribute('data-tab-btn')===name;
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-selected', on?'true':'false');
  });
  document.querySelectorAll('[data-pane]').forEach(function(pane){
    if(pane.getAttribute('data-pane')===name) pane.removeAttribute('hidden');
    else pane.setAttribute('hidden','');
  });
  hideAll();
}
function select(el){
  document.querySelectorAll('.item.selected').forEach(function(n){n.classList.remove('selected');});
  el.classList.add('selected');
  var kind=el.getAttribute('data-kind');
  var media=el.getAttribute('data-media');
  hideAll();
  if(kind==='video'){video.style.display='block';video.src=withToken(media);}
  else if(kind==='audio'){audio.style.display='block';audio.src=withToken(media);}
  else if(kind==='image'){img.style.display='block';img.src=withToken(media);}
  else if(kind==='pdf'){iframe.style.display='block';iframe.src=withToken(media);}
  else {hint.textContent=downloadToOpen;}
}
document.querySelectorAll('[data-tab-btn]').forEach(function(btn){
  btn.addEventListener('click',function(){
    var name=btn.getAttribute('data-tab-btn');
    showTab(name);
    var first=document.querySelector('[data-pane="'+name+'"] [data-media]');
    if(first) select(first);
  });
});
document.querySelectorAll('[data-media]').forEach(function(el){
  el.addEventListener('click',function(){select(el);});
});
document.querySelectorAll('.row-dl').forEach(function(a){
  a.addEventListener('click',function(e){e.stopPropagation();});
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
var hashed=fromHash();
if(hashed){
  showTab(hashed.getAttribute('data-tab'));
  select(hashed);
}else{
  var initial=document.querySelector('.item.selected[data-media]');
  if(initial) select(initial);
}
"""
