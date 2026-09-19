import Foundation

public func renderLanHistoryHtml(
    jobs: [Job],
    token: String,
    copy: LanHistoryCopy,
    fileExists: (String) -> Bool
) -> String {
    let items = lanLibraryItems(jobs, fileExists: fileExists)
    let defaultTab = lanDefaultLibraryTab(items)
    var html = ""
    html += "<!DOCTYPE html><html><head>"
    html += "<meta charset=\"utf-8\">"
    html += "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
    html += "<title>LiteTrans</title>"
    html += "<link rel=\"icon\" type=\"image/png\" href=\"/favicon.png\">"
    html += "<style>"
    html += lanHistoryPageCSS
    html += "</style></head><body>"
    html += "<header><strong>LiteTrans</strong>"
    if token.isEmpty {
        html += "<p class=\"warn\">\(escapeHtml(copy.warning))</p>"
    }
    html += "</header>"
    html += "<nav class=\"tabs\" role=\"tablist\">"
    for tab in LanLibraryTab.allCases {
        let count = lanLibraryItemsFor(items, tab: tab).count
        let on = tab == defaultTab
        html += "<button type=\"button\" data-tab-btn=\"\(lanLibraryTabWireName(tab))\""
        if on { html += " class=\"on\"" }
        html += " aria-selected=\"\(on ? "true" : "false")\" role=\"tab\">"
        html += escapeHtml(lanLibraryTabLabel(tab, copy: copy))
        html += " \(count)"
        html += "</button>"
    }
    html += "</nav>"
    html += "<main>"
    html += "<div class=\"rail\">"
    for tab in LanLibraryTab.allCases {
        let tabItems = lanLibraryItemsFor(items, tab: tab)
        html += "<div class=\"pane\" data-pane=\"\(lanLibraryTabWireName(tab))\""
        if tab != defaultTab { html += " hidden" }
        html += ">"
        if tabItems.isEmpty {
            html += "<p class=\"empty\">\(escapeHtml(lanLibraryEmptyLabel(tab, copy: copy)))</p>"
        } else {
            if tab == .image { html += "<div class=\"thumbs\">" }
            for (index, item) in tabItems.enumerated() {
                html += appendLibraryItem(item, token: token, selected: tab == defaultTab && index == 0)
            }
            if tab == .image { html += "</div>" }
        }
        html += "</div>"
    }
    html += "</div>"
    html += "<div class=\"stage\">"
    html += "<div class=\"player\">"
    html += "<video controls></video>"
    html += "<audio controls></audio>"
    html += "<img alt=\"\">"
    html += "<iframe title=\"preview\"></iframe>"
    html += "</div>"
    html += "<div class=\"meta\">"
    html += "<p id=\"hint\"></p>"
    html += "<a id=\"download\" href=\"#\" style=\"display:none\">\(escapeHtml(copy.download))</a>"
    html += "</div></div></main>"
    html += "<script>"
    html += "var previewFailed=\(jsString(copy.previewFailed));"
    html += "var downloadToOpen=\(jsString(copy.downloadToOpen));"
    html += "var token=\(jsString(token));"
    html += lanHistoryPageJS
    html += "</script></body></html>"
    return html
}

private func appendLibraryItem(_ item: LanLibraryItem, token: String, selected: Bool) -> String {
    let media = lanHistoryDownloadHref(jobId: item.jobId, index: item.index, multi: item.needsIndex, token: "", kind: "m")
    let download = lanHistoryDownloadHref(jobId: item.jobId, index: item.index, multi: item.needsIndex, token: token, kind: "d")
    var html = "<div class=\"item"
    if item.tab == .image { html += " thumb" }
    if selected { html += " selected" }
    html += "\" data-media=\"\(escapeHtml(media))"
    html += "\" data-download=\"\(escapeHtml(download))"
    html += "\" data-kind=\"\(lanPreviewKindWireName(item.kind))"
    html += "\" data-id=\"\(escapeHtml(item.jobId))"
    html += "\" data-index=\"\(item.index)"
    html += "\" data-tab=\"\(lanLibraryTabWireName(item.tab))"
    html += "\">"
    switch item.tab {
    case .image:
        let thumb = lanHistoryDownloadHref(jobId: item.jobId, index: item.index, multi: item.needsIndex, token: token, kind: "m")
        html += "<img class=\"thumb-src\" alt=\"\" src=\"\(escapeHtml(thumb))\">"
    case .document:
        html += escapeHtml(item.label)
        html += " <span class=\"fmt\">\(escapeHtml(item.format))</span>"
    default:
        html += escapeHtml(item.label)
    }
    html += "</div>"
    return html
}

func lanPreviewKindWireName(_ kind: LanPreviewKind) -> String {
    switch kind {
    case .video: return "video"
    case .audio: return "audio"
    case .pdf: return "pdf"
    case .image: return "image"
    case .file: return "file"
    }
}

private func jsString(_ raw: String) -> String {
    var out = "\""
    for ch in raw {
        switch ch {
        case "\\": out += "\\\\"
        case "\"": out += "\\\""
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "<": out += "\\u003c"
        default: out.append(ch)
        }
    }
    out += "\""
    return out
}

let lanHistoryPageCSS = """
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
.item{padding:10px 12px;border-radius:8px;cursor:pointer}
.item.selected{background:#f6f1ea;box-shadow:inset 3px 0 0 #c45a2a}
.thumbs{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.item.thumb{padding:0;overflow:hidden;border:2px solid transparent}
.item.thumb.selected{box-shadow:none;border-color:#c45a2a;background:transparent}
.thumb-src{display:block;width:100%;height:88px;object-fit:cover;background:#d5d2cc}
.fmt{color:#5c6460;font-size:.85em;margin-left:.35em}
.stage{flex:1;display:flex;flex-direction:column;min-width:0}
.player{flex:1;min-height:240px;background:#111;border-radius:14px;display:flex;align-items:center;justify-content:center;overflow:hidden}
.player video,.player audio,.player img,.player iframe{max-width:100%;max-height:100%;display:none}
.player iframe{width:100%;height:100%;border:0}
.player audio{width:80%}
.meta{padding:12px 4px 0}
#hint{margin:0 0 8px;color:#5c6460}
#download{color:#c45a2a;font-weight:600;text-decoration:none}
#download:hover{text-decoration:underline}
@media (max-width:720px){main{flex-direction:column}.rail{width:auto;flex:none;max-height:40vh}.player{min-height:200px}}
@media (prefers-reduced-motion: reduce){*{transition:none!important}}
"""

let lanHistoryPageJS = """
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
  download.style.display='none';
}
function showError(){hideAll();hint.textContent=previewFailed;download.style.display='inline';}
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
  var dl=el.getAttribute('data-download');
  hideAll();
  if(dl){download.setAttribute('href',dl);download.style.display='inline';}
  if(kind==='video'){video.style.display='block';video.src=withToken(media);}
  else if(kind==='audio'){audio.style.display='block';audio.src=withToken(media);}
  else if(kind==='image'){img.style.display='block';img.src=withToken(media);}
  else if(kind==='pdf'){iframe.style.display='block';iframe.src=withToken(media);}
  else {hint.textContent=downloadToOpen;download.style.display='inline';}
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
function fromHash(){
  var m=location.hash.match(/^#m\\/([^/]+)(?:\\/(\\d+))?$/);
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
