
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
  var bits=[el.getAttribute('data-label'), el.getAttribute('data-info')].filter(function(s){return !!s;});
  if(kind==='file') bits.push(downloadToOpen);
  hint.textContent=bits.join(' · ');
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
