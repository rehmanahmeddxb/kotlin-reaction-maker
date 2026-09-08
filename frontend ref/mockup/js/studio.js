/* Reaction Studio carbon-copy mockup — mirrors StudioViewModel state + UI rules. */
const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
const fmt = ms => { const t = Math.floor(ms/1000); return String(Math.floor(t/60)).padStart(2,'0')+':'+String(t%60).padStart(2,'0'); };

let _id = 0; const nid = p => p + (++_id);
const S = {
  layers: [], sel: null, playing:false, recording:false, recMs:0, posMs:0,
  sidebar:true, fc:false, chrome:true, hud:false, aspect:'16:9', bg:'Dark',
  mic:1, master:1, solo:null, dirty:true, canUndo:false, canRedo:false,
  light:'Off', speed:1
};
const ASPECTS = {'16:9':{r:16/9,label:'16:9 Landscape',res:'1920x1080',chip:'16:9',short:'16:9'},
  '9:16':{r:9/16,label:'9:16 Portrait',res:'1080x1920',chip:'9:16',short:'9:16'},
  '1:1':{r:1,label:'1:1 Square',res:'1080x1080',chip:'1:1',short:'1:1'}};
const BGS = {Dark:'#0E1016',Black:'#000000',White:'#FFFFFF',Orange:'#E65100',Navy:'#0D1B2A',Green:'#1B4332',Purple:'#3A0CA3'};
const TYPE_ICON = {video:'movie',camera:'videocam',image:'image',screen:'screen_share',text:'text_fields'};

function mkLayer(o){ return Object.assign({id:nid('L'),visible:true,locked:false,muted:false,playing:true,
  volume:1,opacity:1,speed:1,fit:'FIT',torch:false,t:{cx:.5,cy:.5,w:.8,h:.8,rot:0}}, o); }
function sampleLayers(){
  _id = 0;
  S.layers = [
    mkLayer({name:'Video Clip #1',type:'video',accent:'#38BDF8',t:{cx:.5,cy:.5,w:.8,h:.8,rot:0}}),
    mkLayer({name:'Front Camera',type:'camera',facing:'front',accent:'#F59E0B',t:{cx:.78,cy:.28,w:.35,h:.38,rot:0}}),
    mkLayer({name:'Text: WOW!',type:'text',accent:'#EF4444',text:{s:'WOW! Look at this part!',c:'#FFFFFF',size:22},t:{cx:.5,cy:.82,w:.75,h:.12,rot:0}}),
  ];
  S.sel = S.layers[1].id;
}
const sel = () => S.layers.find(l=>l.id===S.sel);
const dur = () => S.layers.some(l=>l.type==='video') ? 96000 : 0;

/* ---------- toasts ---------- */
function toast(msg){ const d=document.createElement('div'); d.className='toast'; d.textContent=msg;
  $('#toasts').appendChild(d); setTimeout(()=>{d.style.opacity='0';d.style.transition='.3s';setTimeout(()=>d.remove(),320);},2100); }
function touch(undo=true){ if(undo){S.canUndo=true;} S.dirty=true; syncTop(); }

/* ---------- top strip ---------- */
function syncTop(){
  $('#btn-hamb').classList.toggle('open',S.sidebar);
  $('#aspect-lbl').textContent = ASPECTS[S.aspect].chip;
  $('#btn-undo').disabled=!S.canUndo; $('#btn-redo').disabled=!S.canRedo;
  const b=$('#btn-save'); b.classList.toggle('dirty',S.dirty); $('#save-lbl').textContent=S.dirty?'Save Draft':'Saved';
  $('#fc-ic').textContent=S.fc?'fullscreen_exit':'fullscreen';
  $('#m-fc span:last-child').textContent=S.fc?'Exit Full Screen Canvas':'Full Screen Canvas';
  $('#bd-canvas').textContent=ASPECTS[S.aspect].short;
  $('#aspect-sub').textContent='Aspect Ratio ('+ASPECTS[S.aspect].label+')';
  $$('#sub-aspect .ar').forEach(a=>a.classList.toggle('active',a.dataset.ar===S.aspect));
  $$('#dlg-startup .optcard').forEach(c=>c.classList.toggle('on',c.dataset.ar===S.aspect));
}

/* ---------- sidebar: layers ---------- */
function typeIcon(l){ if(l.type==='camera') return l.facing==='front'?'camera_front':l.facing==='back'?'photo_camera':'videocam'; return TYPE_ICON[l.type]; }
function renderLayerList(){
  const box=$('#layer-list'); box.innerHTML='';
  [...S.layers].reverse().forEach(l=>{
    const r=document.createElement('div'); r.className='layer-row'+(l.id===S.sel?' sel':'');
    r.innerHTML=`<span class="ms type" style="color:${l.accent}">${typeIcon(l)}</span><span class="nm">${l.name}</span>
      <button class="mini ${l.visible?'':'off'}" data-a="eye" title="Toggle visibility"><span class="ms">${l.visible?'visibility':'visibility_off'}</span></button>
      <button class="mini ${l.muted?'warn':''}" data-a="mute" title="Toggle mute"><span class="ms">${l.muted?'volume_off':'volume_up'}</span></button>
      <button class="mini sm" data-a="up" title="Move up"><span class="ms">arrow_upward</span></button>
      <button class="mini sm" data-a="dn" title="Move down"><span class="ms">arrow_downward</span></button>`;
    r.addEventListener('click',e=>{ const b=e.target.closest('button');
      if(!b){S.sel=l.id; renderAll(); return;}
      e.stopPropagation();
      const a=b.dataset.a, i=S.layers.findIndex(x=>x.id===l.id);
      if(a==='eye'){l.visible=!l.visible;}
      if(a==='mute'){l.muted=!l.muted;}
      if(a==='up'&&i<S.layers.length-1){S.layers.splice(i,1);S.layers.splice(i+1,0,l);}
      if(a==='dn'&&i>0){S.layers.splice(i,1);S.layers.splice(i-1,0,l);}
      touch(); renderAll(); });
    box.appendChild(r);
  });
  $('#bd-sources').textContent=S.layers.length;
  const s=sel(), has=!!s;
  $('#act-remove').disabled=!has; $('#act-dup').disabled=!has; $('#act-tobg').disabled=!has;
  $('#bd-controls').textContent=s?s.name.slice(0,10):''; $('#bd-controls').style.display=s?'':'none';
  $('#persource-wrap').style.display=s?'':'none';
  if(s) $('#persrc-lbl').textContent=s.name.slice(0,12)+' Audio';
  $('#hud-l3').textContent=`Layers: ${S.layers.length} active · Latency: 12ms`;
}

/* ---------- sidebar: source controls ---------- */
function renderControls(){
  const box=$('#sec-controls'), l=sel();
  if(!l){ box.innerHTML='<div class="hint">Select a source to adjust controls</div>'; return; }
  const isVI=(l.type==='video'||l.type==='image');
  box.innerHTML=`
  ${isVI?`<div class="filecard"><div class="fh"><span class="ms">${l.type==='video'?'movie':'image'}</span>Sample Template Media</div>
    <p>Using demo visual. Attach a real video or image from your device storage.</p>
    <button id="ct-pick"><span class="ms">folder_open</span>Select Real File from Device</button></div>`:''}
  <button class="act ${l.visible?'active':''}" id="ct-vis"><span class="ms">${l.visible?'visibility_off':'visibility'}</span><span>${l.visible?'Hide Layer':'Show Layer'}</span></button>
  <button class="act ${l.locked?'active':''}" id="ct-lock"><span class="ms">${l.locked?'lock':'lock_open'}</span><span>${l.locked?'Unlock Position':'Lock Position'}</span></button>
  <button class="act" id="ct-play"><span class="ms">${l.playing?'pause_circle':'play_circle'}</span><span>${l.playing?'Pause Layer':'Resume Layer'}</span></button>
  ${l.type==='camera'?`<button class="act ${l.torch?'active':''}" id="ct-torch"><span class="ms">${l.torch?'flash_off':'flash_on'}</span><span>Torch ${l.torch?'OFF':'ON'} (${l.facing==='front'?'Front':'Back'})</span></button>`:''}
  <button class="sub-head" data-sub="sub-fit"><span class="ms">crop_free</span><span class="lbl">Fit Mode (${l.fit})</span><span class="ms chev">keyboard_arrow_right</span></button>
  <div class="submenu" id="sub-fit">
    <button class="act ${l.fit==='FILL'?'active':''}" id="ct-fill"><span class="ms">fullscreen</span><span>Fill (Cover / Crop)</span></button>
    <button class="act ${l.fit==='FIT'?'active':''}" id="ct-fit"><span class="ms">fit_screen</span><span>Fit (Letterbox)</span></button>
  </div>
  <button class="sub-head" data-sub="sub-tr"><span class="ms">open_with</span><span class="lbl">Transform &amp; Sizing</span><span class="ms chev">keyboard_arrow_right</span></button>
  <div class="submenu" id="sub-tr">
    <button class="act active" id="ct-autofill"><span class="ms">fit_screen</span><span>Auto Fill (100% Canvas)</span></button>
    <button class="act" id="ct-fitfr"><span class="ms">aspect_ratio</span><span>Fit Inside Frame (90%)</span></button>
    <button class="act" id="ct-center"><span class="ms">filter_center_focus</span><span>Center on Canvas</span></button>
    <button class="act" id="ct-sxw"><span class="ms">straighten</span><span>Stretch to 100% Width</span></button>
    <button class="act" id="ct-sxh"><span class="ms">height</span><span>Stretch to 100% Height</span></button>
    <div class="mini-div"></div>
    <div class="ctl"><div class="lab"><span>Width Scale</span><b id="ct-w-lbl">${Math.round(l.t.w*100)}%</b></div>
      <input type="range" id="ct-w" min="10" max="150" value="${Math.round(l.t.w*100)}"></div>
    <div class="ctl"><div class="lab"><span>Height Scale</span><b id="ct-h-lbl">${Math.round(l.t.h*100)}%</b></div>
      <input type="range" id="ct-h" min="10" max="150" value="${Math.round(l.t.h*100)}"></div>
    <div class="mini-div"></div>
    <button class="act" data-corner="tl"><span class="ms">north_west</span><span>Corner: Top-Left</span></button>
    <button class="act" data-corner="tr"><span class="ms">north_east</span><span>Corner: Top-Right</span></button>
    <button class="act" data-corner="bl"><span class="ms">south_west</span><span>Corner: Bottom-Left</span></button>
    <button class="act" data-corner="br"><span class="ms">south_east</span><span>Corner: Bottom-Right</span></button>
    <div class="mini-div"></div>
    <div class="ctl amber"><div class="lab"><span class="ttl"><span class="ms">rotate_right</span>Rotation Angle</span><b id="ct-r-lbl">${Math.round(l.t.rot)}°</b></div>
      <input type="range" id="ct-r" min="-180" max="180" value="${Math.round(l.t.rot)}">
      <div class="rotbtns"><button id="ct-rm">↺ −90°</button><button id="ct-rp">↻ +90°</button><button class="amber" id="ct-r0">Reset 0°</button></div></div>
  </div>
  <button class="act" id="ct-bg"><span class="ms">wallpaper</span><span>Set as Background</span></button>
  <button class="act" data-dlg="dlg-props"><span class="ms">settings_suggest</span><span>Advanced Properties…</span></button>`;
  const on=(id,fn)=>{const e=$(id); if(e) e.addEventListener('click',fn);};
  on('#ct-pick',()=>toast('System file picker would open (video/* or image/*)'));
  on('#ct-vis',()=>{l.visible=!l.visible;touch();renderAll();});
  on('#ct-lock',()=>{l.locked=!l.locked;touch();renderAll();toast(l.locked?'Layer locked':'Layer unlocked');});
  on('#ct-play',()=>{l.playing=!l.playing;touch(false);renderAll();});
  on('#ct-torch',()=>{l.torch=!l.torch;touch();renderAll();toast(`${l.name} torch ${l.torch?'ON':'OFF'}`);});
  on('#ct-fill',()=>{l.fit='FILL';touch();renderAll();}); on('#ct-fit',()=>{l.fit='FIT';touch();renderAll();});
  on('#ct-autofill',()=>{l.t={cx:.5,cy:.5,w:1,h:1,rot:0};touch();renderAll();toast('Auto-filled entire canvas (100%)');});
  on('#ct-fitfr',()=>{l.t={cx:.5,cy:.5,w:.9,h:.9,rot:l.t.rot};touch();renderAll();toast('Fitted within canvas frame');});
  on('#ct-center',()=>{l.t.cx=.5;l.t.cy=.5;touch();renderAll();toast('Centered in frame');});
  on('#ct-sxw',()=>{l.t.w=1;l.t.cx=.5;touch();renderAll();toast('Stretched to canvas width');});
  on('#ct-sxh',()=>{l.t.h=1;l.t.cy=.5;touch();renderAll();toast('Stretched to canvas height');});
  const w=$('#ct-w'),h=$('#ct-h'),r=$('#ct-r');
  if(w) w.addEventListener('input',()=>{l.t.w=w.value/100;$('#ct-w-lbl').textContent=w.value+'%';touch();renderCanvas();});
  if(h) h.addEventListener('input',()=>{l.t.h=h.value/100;$('#ct-h-lbl').textContent=h.value+'%';touch();renderCanvas();});
  if(r) r.addEventListener('input',()=>{l.t.rot=+r.value;$('#ct-r-lbl').textContent=r.value+'°';touch();renderCanvas();});
  on('#ct-rm',()=>{l.t.rot-=90;touch();renderAll();}); on('#ct-rp',()=>{l.t.rot+=90;touch();renderAll();});
  on('#ct-r0',()=>{l.t.rot=0;touch();renderAll();toast('Rotation reset to 0°');});
  $$('#sec-controls [data-corner]').forEach(b=>b.addEventListener('click',()=>{
    const c=b.dataset.corner; l.t.cx=(c==='tl'||c==='bl')?l.t.w/2:1-l.t.w/2; l.t.cy=(c==='tl'||c==='tr')?l.t.h/2:1-l.t.h/2;
    touch();renderAll();toast('Corner set');}));
  on('#ct-bg',()=>{S.layers.splice(S.layers.indexOf(l),1);S.layers.unshift(l);touch();renderAll();toast('Set as background layer');});
}

/* ---------- canvas ---------- */
function waveSVG(){ let d='M -20 30 '; for(let x=-20;x<=340;x+=6){ d+=`L ${x} ${30+Math.sin(x*0.09)*14} `; }
  return `<svg class="wave" viewBox="0 0 320 60" preserveAspectRatio="none"><g class="wrow"><path d="${d}" stroke="#38BDF8" stroke-opacity=".4" fill="none" stroke-width="1.4"/></g></svg>`; }
function layerHTML(l){
  const t=l.t, st=`left:${(t.cx-t.w/2)*100}%;top:${(t.cy-t.h/2)*100}%;width:${t.w*100}%;height:${t.h*100}%;transform:rotate(${t.rot}deg);opacity:${l.opacity}`;
  if(!l.visible) return '';
  let inner='';
  const active=S.playing&&l.playing;
  if(l.type==='video') inner=`<div class="vdemo" style="position:absolute;inset:0">${active?waveSVG():''}</div>
    <div class="vbadge ${active?'':'idle'}"><span class="ms">${active?'play_arrow':'pause'}</span>${l.name}</div>`;
  if(l.type==='camera') inner=`<div class="camv ${l.facing==='back'?'back':''} ${active&&!l.muted?'playing':''}"><div class="face"></div>
    <div class="bars"><i></i><i></i><i></i><i></i><i></i></div></div>
    <div class="cam-pill ${l.facing==='front'?'front':'back'}"><i></i>${l.facing==='front'?'FRONT':'BACK'}</div>${l.torch?'<div class="torchdot"></div>':''}`;
  if(l.type==='image') inner=`<div class="imgv"><span class="ms">stars</span><b>REACTION</b></div>`;
  if(l.type==='screen') inner=`<div class="scrv"><span class="ms">computer</span><b>Display Screen Stream</b></div>`;
  if(l.type==='text'){ const x=l.text||{s:'Text',c:'#fff',size:22};
    inner=`<div class="txtv" style="color:${x.c};font-size:${Math.max(10,Math.min(42,x.size))}px">${x.s}</div>`; }
  return `<div class="layer" data-id="${l.id}" style="${st}">${inner}</div>`;
}
function renderCanvas(){
  const c=$('#canvas'); c.style.background=BGS[S.bg]; c.innerHTML='';
  if(!S.layers.some(l=>l.visible)){
    c.innerHTML=`<div class="empty-st"><div class="tile"><span class="ms">movie</span></div><h3>Your canvas is ready</h3><p>Add a camera, video, image or text to begin.</p></div>`;
  }
  S.layers.forEach(l=>{ c.insertAdjacentHTML('beforeend',layerHTML(l)); });
  // guides
  const l=sel();
  if(l&&!l.locked&&l.visible){
    const nearX=Math.abs(l.t.cx-.5)*c.clientWidth<8, nearY=Math.abs(l.t.cy-.5)*c.clientHeight<8;
    if(nearX) c.insertAdjacentHTML('beforeend','<div class="guide" style="left:50%;top:0;bottom:0;width:1.5px"></div>');
    if(nearY) c.insertAdjacentHTML('beforeend','<div class="guide" style="top:50%;left:0;right:0;height:1.5px;background:repeating-linear-gradient(0deg,rgba(56,189,248,.6) 0 8px,transparent 8px 16px)"></div>');
  }
  renderChrome();
  // canvas layer events
  $$('#canvas .layer').forEach(el=>{
    el.addEventListener('pointerdown',e=>startDrag(e,el.dataset.id,'move'));
    el.addEventListener('dblclick',()=>{ const L=S.layers.find(x=>x.id===el.dataset.id);
      if(L&&L.type==='text'){S.sel=L.id;renderAll();openDlg('dlg-text');} });
  });
  sizeCanvas();
}
function renderChrome(){
  $('.chrome')?.remove(); $('.qpill')?.remove();
  const l=sel(), c=$('#canvas');
  $('#qbar').style.display=l?'flex':'none';
  if(!l||!l.visible) return;
  const t=l.t, L=(t.cx-t.w/2)*100, T=(t.cy-t.h/2)*100, W=t.w*100, H=t.h*100;
  const ch=document.createElement('div');
  ch.className='chrome'+(l.locked?' locked':'');
  ch.style.cssText=`left:${L}%;top:${T}%;width:${W}%;height:${H}%;transform:rotate(${t.rot}deg)`;
  ch.innerHTML=`<div class="tag">${l.locked?'🔒 ':''}${l.type.toUpperCase()} • ${l.name.slice(0,14)}</div>
   ${l.locked?'<div class="lockchip"><span class="ms">lock</span></div>':`
    <div class="mv" data-g="move"><span class="ms">open_with</span></div>
    <div class="cdot" data-g="c-tl" style="left:-8px;top:-8px"></div><div class="cdot" data-g="c-tr" style="right:-8px;top:-8px;cursor:nesw-resize"></div>
    <div class="cdot" data-g="c-bl" style="left:-8px;bottom:-8px;cursor:nesw-resize"></div><div class="cdot" data-g="c-br" style="right:-8px;bottom:-8px"></div>
    <div class="epill" data-g="e-l" style="left:-4px;top:50%;transform:translateY(-50%);width:6px;height:18px;cursor:ew-resize"></div>
    <div class="epill" data-g="e-r" style="right:-4px;top:50%;transform:translateY(-50%);width:6px;height:18px;cursor:ew-resize"></div>
    <div class="epill" data-g="e-t" style="top:-4px;left:50%;transform:translateX(-50%);width:18px;height:6px;cursor:ns-resize"></div>
    <div class="epill" data-g="e-b" style="bottom:-4px;left:50%;transform:translateX(-50%);width:18px;height:6px;cursor:ns-resize"></div>
    <div class="knob" data-g="rot"><span class="deg">${Math.round(((t.rot%360)+360)%360)}°</span><span class="kdot"></span></div>`}`;
  c.appendChild(ch);
  // quick pill above or docked
  const qp=document.createElement('div'); qp.className='qpill';
  const playable=['video','camera','screen'].includes(l.type);
  qp.innerHTML=`<button data-q="vis" title="${l.visible?'Hide source':'Show source'}"><span class="ms">${l.visible?'visibility':'visibility_off'}</span></button>
    ${playable?`<button class="cy" data-q="pp" title="${l.playing?'Pause source':'Play source'}"><span class="ms">${l.playing?'pause':'play_arrow'}</span></button>`:''}`;
  const roomPx=T/100*c.clientHeight;
  qp.style.cssText = roomPx>=44 ? `left:calc(${L+W}% - 66px);top:calc(${T}% - 38px)` : `left:calc(${L+W}% - 72px);top:calc(${T}% + 6px)`;
  qp.querySelector('[data-q=vis]').addEventListener('click',e=>{e.stopPropagation();l.visible=!l.visible;touch();renderAll();});
  const pp=qp.querySelector('[data-q=pp]'); if(pp) pp.addEventListener('click',e=>{e.stopPropagation();l.playing=!l.playing;touch(false);renderAll();});
  c.appendChild(qp);
  ch.querySelectorAll('[data-g]').forEach(h=>h.addEventListener('pointerdown',e=>{e.stopPropagation();startDrag(e,l.id,h.dataset.g);}));
}
/* drag / resize / rotate */
let drag=null;
function startDrag(e,id,mode){
  const l=S.layers.find(x=>x.id===id); if(!l||l.locked) { if(l){S.sel=id;renderAll();} return; }
  S.sel=id; renderAll();
  const c=$('#canvas'), r=c.getBoundingClientRect();
  drag={l,mode,sx:e.clientX,sy:e.clientY,t:{...l.t},r};
  if(mode.startsWith('c-')){ const sx=(mode==='c-tl'||mode==='c-bl')?-1:1, sy=(mode==='c-tl'||mode==='c-tr')?-1:1;
    drag.ax=l.t.cx-sx*l.t.w/2; drag.ay=l.t.cy-sy*l.t.h/2; drag.sx=sx; drag.sy=sy;
    drag.nx=l.t.cx+sx*l.t.w/2; drag.ny=l.t.cy+sy*l.t.h/2; }
  window.addEventListener('pointermove',onDrag); window.addEventListener('pointerup',endDrag,{once:true});
  e.preventDefault();
}
function onDrag(e){
  if(!drag) return; const {l,mode,t}=drag, r=$('#canvas').getBoundingClientRect();
  const dx=(e.clientX-drag.sx)/r.width, dy=(e.clientY-drag.sy)/r.height;
  if(mode==='move'){ l.t.cx=Math.min(1,Math.max(0,t.cx+dx)); l.t.cy=Math.min(1,Math.max(0,t.cy+dy)); }
  else if(mode==='rot'){ l.t.rot=(t.rot+(e.clientX-drag.sx)*0.9); }
  else if(mode.startsWith('c-')){
    let nx=drag.nx+dx, ny=drag.ny+dy; const MIN=.08;
    nx=drag.sx>0?Math.max(drag.ax+MIN,nx):Math.min(drag.ax-MIN,nx);
    ny=drag.sy>0?Math.max(drag.ay+MIN,ny):Math.min(drag.ay-MIN,ny);
    nx=Math.min(1,Math.max(0,nx)); ny=Math.min(1,Math.max(0,ny));
    l.t.w=Math.abs(nx-drag.ax); l.t.h=Math.abs(ny-drag.ay); l.t.cx=(nx+drag.ax)/2; l.t.cy=(ny+drag.ay)/2;
  }
  else if(mode==='e-r'||mode==='e-l'){ const d=(mode==='e-r'?dx:-dx); l.t.w=Math.min(2,Math.max(.08,t.w+d)); }
  else if(mode==='e-b'||mode==='e-t'){ const d=(mode==='e-b'?dy:-dy); l.t.h=Math.min(2,Math.max(.08,t.h+d)); }
  touch(); renderCanvas();
}
function endDrag(){ drag=null; window.removeEventListener('pointermove',onDrag); renderControls(); renderLayerList(); }
function sizeCanvas(){
  const st=$('#stage'), c=$('#canvas'), R=ASPECTS[S.aspect].r;
  const imm=S.fc&&!S.chrome;
  let aw=st.clientWidth-(imm?0:8), ah=st.clientHeight-(imm?0:48);
  let w,h; if(aw/ah>R){h=ah;w=h*R;}else{w=aw;h=w/R;}
  c.style.width=Math.max(100,w)+'px'; c.style.height=Math.max(100,h)+'px';
}

/* ---------- transport / timeline ---------- */
function setPlaying(p){
  if(p&&!dur()){ toast('Add a video to the timeline before playing'); return; }
  S.playing=p; syncTransport();
}
function syncTransport(){
  $('#tl-pp').textContent=S.playing?'pause':'play_arrow';
  $('#tl-pp').classList.toggle('on',S.playing);
  $('#t-pp').querySelector('.ms').textContent=S.playing?'pause':'play_arrow';
  $('#act-play').querySelector('span:last-child').textContent=S.playing?'Pause Playback':'Play';
  $('#act-play').querySelector('.ms').textContent=S.playing?'pause':'play_arrow';
  const d=dur(), sk=$('#tl-seek'); sk.disabled=d<=0;
  $('#tl-time').textContent=`${fmt(S.posMs)} / ${fmt(d)}`;
  if(d>0) sk.value=Math.round(S.posMs/d*1000);
  const rc=S.recording;
  $('#t-rec').style.display=rc?'flex':'none'; $('#tl-rec').style.display=rc?'':'none';
  $('#bd-rec').style.display=rc?'':'none';
  $('#t-rec-tx').textContent='REC '+fmt(S.recMs); $('#recpill-tx').textContent='REC '+fmt(S.recMs);
  const rb=$('#t-recbtn'); rb.classList.toggle('live',rc);
  rb.querySelector('.ms').textContent=rc?'stop':'fiber_manual_record';
  $('#act-record').querySelector('span:last-child').textContent=rc?'Stop & Save Recording':'Start Recording';
  $('#act-record').querySelector('.ms').textContent=rc?'stop_circle':'fiber_manual_record';
  $('#act-record').classList.toggle('active',rc);
  $('#recpill').style.display=(S.fc&&!S.chrome&&rc)?'flex':'none';
  renderCanvas();
}
setInterval(()=>{ if(S.playing&&dur()>0){ S.posMs+=250; if(S.posMs>=dur()){S.posMs=0;S.playing=false;} syncTransport(); } },250);
setInterval(()=>{ if(S.recording){ S.recMs+=500; syncTransport(); } },500);
function toggleRec(){
  S.recording=!S.recording;
  if(S.recording){S.recMs=0;toast('Recording started');} else toast(`Take saved: ${Math.floor(S.recMs/1000)}s reaction clip`);
  touch(false);syncTransport();
}

/* ---------- modes ---------- */
function setFC(f){ S.fc=f; if(f) S.sidebarOpen=false; applyModes(); }
function applyModes(){
  $('#app').classList.toggle('fc',S.fc);
  $('#app').classList.toggle('no-chrome',!S.chrome);
  $('#sidebar').classList.toggle('hidden',!S.sidebar||S.fc||!S.chrome);
  const imm=S.fc&&!S.chrome, w=$('#wpill');
  if(imm){ w.className='wpill mini';
    w.innerHTML='<span class="eye" id="wpill-eye"><span class="ms">visibility</span></span><span class="eye" id="wpill-exit"><span class="ms">fullscreen_exit</span></span>';
    $('#wpill-exit').addEventListener('click',e=>{e.stopPropagation();setFC(false);});
  } else { w.className='wpill'+(S.fc?' fc':'');
    w.innerHTML=`<span class="ms">${S.fc?'fullscreen_exit':'fullscreen'}</span><span>${S.fc?'Exit Full':'Full Canvas'}</span><span class="dv"></span><span class="eye" id="wpill-eye" title="Hide studio controls"><span class="ms">visibility_off</span></span>`;
    w.onclick=()=>setFC(!S.fc);
  }
  $('#wpill-eye').addEventListener('click',e=>{e.stopPropagation();S.chrome=!S.chrome;applyModes();syncTransport();});
  $('#act-fc').querySelector('span:last-child').textContent=S.fc?'Exit Full Canvas':'Full Canvas Mode';
  $('#act-fc').classList.toggle('active',S.fc);
  syncTop(); syncTransport(); sizeCanvas();
}

/* ---------- dialogs ---------- */
function openDlg(id){ $$('.scrim').forEach(s=>s.classList.remove('on')); $('#'+id).classList.add('on');
  if(id==='dlg-mixer') renderMixer(); if(id==='dlg-progress') runExport(false); }
function closeDlgs(){ $$('.scrim').forEach(s=>s.classList.remove('on')); }
let expTimer=null;
function runExport(fail){
  ['exp-busy','exp-done','exp-fail'].forEach(i=>$('#'+i).style.display='none');
  $('#exp-ok').style.display='none'; $('#exp-close').style.display='none';
  $('#exp-busy').style.display=''; $('#exp-title').textContent='Rendering Video…';
  let p=0; clearInterval(expTimer);
  expTimer=setInterval(()=>{ p+=4;
    if(p>=100){ clearInterval(expTimer);
      if(fail){ $('#exp-busy').style.display='none'; $('#exp-fail').style.display='';
        $('#exp-title').textContent='Export Failed'; $('#exp-close').style.display=''; }
      else { $('#exp-busy').style.display='none'; $('#exp-done').style.display='';
        $('#exp-title').textContent='Export Completed!'; $('#exp-ok').style.display='';
        toast('Export complete! Video saved.'); } return; }
    $('#exp-bar').style.width=p+'%'; $('#exp-pct').textContent=`Encoding frames: ${p}%`; },120);
}
function startExportFlow(){
  if(!dur()){ toast('Add a video to the timeline before exporting'); return; }
  closeDlgs(); openDlg('dlg-progress');
}
function renderMixer(){
  $('#mix-master-lbl').textContent=`Master Volume: ${Math.round(S.master*100)}%`;
  $('#mix-mic-lbl').textContent=`Microphone Gain: ${Math.round(S.mic*100)}%`;
  const t=$('#mix-tracks'); t.innerHTML='';
  S.layers.forEach(l=>{ const r=document.createElement('div'); r.className='mixrow';
    r.innerHTML=`<span class="nm">${l.name.slice(0,12)}</span>
      <input type="range" min="0" max="100" value="${Math.round(l.volume*100)}" style="accent-color:var(--purple)">
      <button class="mini ${l.muted?'warn':''}"><span class="ms">${l.muted?'volume_off':'volume_up'}</span></button>
      <button class="mini ${S.solo===l.id?'cy':'dim'}"><span class="ms">stars</span></button>`;
    r.querySelector('input').addEventListener('input',e=>{l.volume=e.target.value/100;});
    r.querySelectorAll('button')[0].addEventListener('click',()=>{l.muted=!l.muted;renderMixer();renderAll();});
    r.querySelectorAll('button')[1].addEventListener('click',()=>{S.solo=S.solo===l.id?null:l.id;
      toast(S.solo?'Solo active for track':'Solo disabled');renderMixer();renderAll();});
    t.appendChild(r); });
}
function renderAll(){ renderLayerList(); renderControls(); renderCanvas(); syncTransport(); }

/* ---------- events ---------- */
document.addEventListener('click',e=>{
  const sh=e.target.closest('.sec-head'); if(sh){ const id=sh.dataset.sec, b=$('#sec-'+id), open=b.classList.toggle('open');
    sh.classList.toggle('open',open); sh.querySelector('.chev').textContent=open?'keyboard_arrow_down':'keyboard_arrow_right'; return; }
  const uh=e.target.closest('.sub-head'); if(uh){ const m=$('#'+uh.dataset.sub); if(!m) return;
    const open=m.classList.toggle('open'); uh.classList.toggle('open',open);
    uh.querySelector('.chev').textContent=open?'keyboard_arrow_down':'keyboard_arrow_right'; return; }
  const dd=e.target.closest('[data-dlg]'); if(dd){ openDlg(dd.dataset.dlg); return; }
  if(e.target.closest('[data-close]')){ closeDlgs(); return; }
  if(e.target.classList&&e.target.classList.contains('scrim')){ if(e.target.id!=='dlg-progress') closeDlgs(); return; }
  const ch=e.target.closest('.chips .chip'); if(ch){ [...ch.parentElement.children].forEach(c=>c.classList.remove('on')); ch.classList.add('on'); return; }
  const oc=e.target.closest('.optcard'); if(oc){ setAspect(oc.dataset.ar); return; }
  const tb=e.target.closest('[data-toast]'); if(tb){ toast(tb.dataset.toast); return; }
  const ad=e.target.closest('[data-add]'); if(ad){ addSample(ad.dataset.add); return; }
});
$('#stage').addEventListener('pointerdown',e=>{ if(e.target.id==='stage'||e.target.id==='canvas'){ if(S.sel){S.sel=null;renderAll();} } });
$('#btn-hamb').addEventListener('click',()=>{S.sidebar=!S.sidebar;applyModes();});
$('#btn-title').addEventListener('click',()=>openDlg('dlg-rename'));
$('#btn-aspect').addEventListener('click',()=>{ const k=Object.keys(ASPECTS), n=k[(k.indexOf(S.aspect)+1)%3]; setAspect(n); });
$('#btn-undo').addEventListener('click',()=>{S.canUndo=false;S.canRedo=true;syncTop();toast('Undone');});
$('#btn-redo').addEventListener('click',()=>{S.canRedo=false;syncTop();toast('Redone');});
$('#btn-save').addEventListener('click',doSave);
$('#act-save').addEventListener('click',doSave);
function doSave(){ S.dirty=false;syncTop();toast('Project saved to local store'); }
$('#btn-export').addEventListener('click',startExportFlow);
$('#act-quickexp').addEventListener('click',startExportFlow);
$('#btn-startexp').addEventListener('click',startExportFlow);
$('#btn-fc').addEventListener('click',()=>setFC(!S.fc));
$('#act-fc').addEventListener('click',()=>setFC(!S.fc));
$('#m-fc').addEventListener('click',()=>{setFC(!S.fc);$('#overflow-menu').style.display='none';});
$('#btn-overflow').addEventListener('click',e=>{e.stopPropagation(); const m=$('#overflow-menu');
  m.style.display=m.style.display==='none'?'':'none';});
document.addEventListener('click',e=>{ if(!e.target.closest('.overflow-wrap')) $('#overflow-menu').style.display='none'; });
$('#m-guide').addEventListener('click',()=>{openDlg('dlg-d9');$('#overflow-menu').style.display='none';});
$('#m-diag').addEventListener('click',()=>{openDlg('dlg-diag');$('#overflow-menu').style.display='none';});
function setHUD(h){ S.hud=h; $('#hud').style.display=h?'':'none';
  $('#m-hud span:last-child').textContent=h?'Hide Stats Overlay':'Show Stats Overlay';
  $('#act-hud').querySelector('span:last-child').textContent=h?'Hide Stats Overlay':'Show Stats Overlay';
  $('#act-hud').classList.toggle('active',h); $('#hk-hud').checked=h; }
$('#m-hud').addEventListener('click',()=>{setHUD(!S.hud);$('#overflow-menu').style.display='none';});
$('#act-hud').addEventListener('click',()=>setHUD(!S.hud));
$('#hk-hud').addEventListener('change',e=>setHUD(e.target.checked));
/* transport + record */
$('#t-pp').addEventListener('click',()=>setPlaying(!S.playing));
$('#tl-pp').addEventListener('click',()=>setPlaying(!S.playing));
$('#act-play').addEventListener('click',()=>setPlaying(!S.playing));
$('#t-stop').addEventListener('click',()=>{S.playing=false;S.posMs=0;syncTransport();});
$('#act-stop').addEventListener('click',()=>{S.playing=false;S.posMs=0;syncTransport();});
$('#act-restart').addEventListener('click',()=>{S.posMs=0;syncTransport();});
$('#t-recbtn').addEventListener('click',toggleRec);
$('#act-record').addEventListener('click',toggleRec);
$('#tl-seek').addEventListener('input',e=>{ if(dur()>0){S.posMs=e.target.value/1000*dur();syncTransport();} });
$('#act-snap').addEventListener('click',()=>toast('Snapshot saved to Gallery at '+fmt(S.posMs)));
$('#act-snap2').addEventListener('click',()=>toast('Snapshot saved to Gallery at '+fmt(S.posMs)));
$('#act-undo').addEventListener('click',()=>toast('Undone')); $('#act-redo').addEventListener('click',()=>toast('Redone'));
$('#act-remove').addEventListener('click',()=>{ const i=S.layers.findIndex(l=>l.id===S.sel);
  if(i>=0){S.layers.splice(i,1);S.sel=null;touch();renderAll();toast('Layer deleted');} });
$('#act-dup').addEventListener('click',()=>{ const l=sel(); if(!l) return;
  const c=JSON.parse(JSON.stringify(l)); c.id=nid('L'); c.name=l.name+' copy'; S.layers.push(c); S.sel=c.id;
  touch();renderAll();toast('Layer duplicated'); });
$('#act-fitall').addEventListener('click',()=>toast('Aligned all sources'));
$('#act-tobg').addEventListener('click',()=>{ const l=sel(); if(!l) return;
  S.layers.splice(S.layers.indexOf(l),1);S.layers.unshift(l);touch();renderAll();toast('Set as background layer'); });
$('#act-folder').addEventListener('click',()=>toast('System folder picker would open (SAF)'));
$('#btn-folder2').addEventListener('click',()=>toast('System folder picker would open (SAF)'));
/* mic + persource */
function micLbl(){$('#mic-lbl').textContent=`Mic Gain (${Math.round(S.mic*100)}%)`;}
$('#mic-plus').addEventListener('click',()=>{S.m=Math.min(2,S.m+.1);micLbl();toast(`Mic Gain: ${Math.round(S.m*100)}%`);});
$('#mic-minus').addEventListener('click',()=>{S.m=Math.max(0,S.m-.1);micLbl();toast(`Mic Gain: ${Math.round(S.m*100)}%`);});
$('#mic-reset').addEventListener('click',()=>{S.m=1;micLbl();toast('Mic Gain: 100%');});
$('#ps-volup').addEventListener('click',()=>{const l=sel();if(l){l.volume=Math.min(1.5,l.volume+.1);toast(`Volume: ${Math.round(l.volume*100)}%`);}});
$('#ps-voldn').addEventListener('click',()=>{const l=sel();if(l){l.volume=Math.max(0,l.volume-.1);toast(`Volume: ${Math.round(l.volume*100)}%`);}});
$('#ps-mute').addEventListener('click',()=>{const l=sel();if(l){l.muted=!l.muted;renderAll();}});
$('#ps-solo').addEventListener('click',()=>{const l=sel();if(!l)return;
  S.solo=S.solo===l.id?null:l.id; $('#ps-solo').classList.toggle('active',!!S.solo);
  $('#ps-solo').querySelector('span:last-child').textContent=S.solo?'Un-Solo Track':'Solo Track';
  toast(S.solo?'Solo active for track':'Solo disabled');});
/* light */
$$('#sub-light .light').forEach(b=>b.addEventListener('click',()=>{
  $$('#sub-light .light').forEach(x=>x.classList.remove('active')); b.classList.add('active');
  S.light=b.dataset.light; $('#light-lbl').textContent='Light ('+S.light+')';
  $('#screenlight').classList.toggle('on',S.light==='Screen Light');
  $('#hk-light').checked=S.light==='Screen Light'; toast('Lighting: '+S.light);}));
$('#hk-light').addEventListener('change',e=>{ $('#screenlight').classList.toggle('on',e.target.checked); });
/* backgrounds */
Object.entries(BGS).forEach(([n,c])=>{ const b=document.createElement('button');
  b.className='act bg'+(n==='Dark'?' active':'');
  b.innerHTML=`<span class="ms" style="color:${c==='#FFFFFF'?'#e5e7eb':c};${n==='Black'?';text-shadow:0 0 2px #888':''}">circle</span><span>${n}</span>`;
  b.addEventListener('click',()=>{ S.bg=n; $$('#sub-bg .bg').forEach(x=>x.classList.remove('active')); b.classList.add('active');
    $('#bg-sub').textContent='Background ('+n+')'; touch(); renderCanvas(); toast('Background: '+n); });
  $('#sub-bg').appendChild(b); });
/* aspects */
function setAspect(a){ S.aspect=a; syncTop(); renderCanvas();
  $('#hud-l1').textContent=`FPS: 60 · Res: ${ASPECTS[a].res}`; toast('Aspect: '+ASPECTS[a].label); }
$$('#sub-aspect .ar').forEach(b=>b.addEventListener('click',()=>setAspect(b.dataset.ar)));
/* quick bar */
const q=id=>$(id);
q('#q-autofill').addEventListener('click',()=>{const l=sel();if(!l)return;l.t={cx:.5,cy:.5,w:1,h:1,rot:0};touch();renderAll();toast('Auto-filled entire canvas (100%)');});
q('#q-fit').addEventListener('click',()=>{const l=sel();if(!l)return;l.t={cx:.5,cy:.5,w:.9,h:.9,rot:l.t.rot};touch();renderAll();toast('Fitted within canvas frame');});
q('#q-center').addEventListener('click',()=>{const l=sel();if(!l)return;l.t.cx=.5;l.t.cy=.5;touch();renderAll();toast('Centered in frame');});
q('#q-sw').addEventListener('click',()=>{const l=sel();if(!l)return;l.t.w=1;l.t.cx=.5;touch();renderAll();toast('Stretched to canvas width');});
q('#q-sh').addEventListener('click',()=>{const l=sel();if(!l)return;l.t.h=1;l.t.cy=.5;touch();renderAll();toast('Stretched to canvas height');});
q('#q-rot').addEventListener('click',()=>{const l=sel();if(!l)return;l.t.rot=0;touch();renderAll();toast('Rotation reset to 0°');});
q('#q-del').addEventListener('click',()=>{const i=S.layers.findIndex(l=>l.id===S.sel);if(i>=0){S.layers.splice(i,1);S.sel=null;touch();renderAll();toast('Layer deleted');}});
/* add sample */
function addSample(k){
  const n=S.layers.length+1; let l;
  if(k==='video') l=mkLayer({name:'Video Clip #'+n,type:'video',accent:'#38BDF8',t:{cx:.5,cy:.5,w:.8,h:.8,rot:0}});
  if(k==='image') l=mkLayer({name:'Sticker Overlay',type:'image',accent:'#10B981',t:{cx:.5,cy:.5,w:.25,h:.25,rot:0}});
  if(k==='frontcam') l=mkLayer({name:'Front Camera',type:'camera',facing:'front',accent:'#F59E0B',t:{cx:.78,cy:.28,w:.35,h:.38,rot:0}});
  if(k==='backcam') l=mkLayer({name:'Back Camera',type:'camera',facing:'back',accent:'#EF4444',t:{cx:.22,cy:.28,w:.35,h:.38,rot:0}});
  if(k==='screen') l=mkLayer({name:'Screen Record Stream',type:'screen',accent:'#818CF8',t:{cx:.5,cy:.5,w:.9,h:.9,rot:0}});
  if(!l) return; S.layers.push(l); S.sel=l.id; touch(); renderAll(); toast('Added '+l.name);
}
/* dialogs wiring */
$('#mix-master').addEventListener('input',e=>{S.master=e.target.value/100;$('#mix-master-lbl').textContent=`Master Volume: ${e.target.value}%`;});
$('#mix-mic').addEventListener('input',e=>{S.m=e.target.value/100;$('#mix-mic-lbl').textContent=`Microphone Gain: ${e.target.value}%`;micLbl();});
$('#pp-op').addEventListener('input',e=>$('#pp-op-lbl').textContent=`Opacity: ${e.target.value}%`);
$('#pp-vol').addEventListener('input',e=>$('#pp-vol-lbl').textContent=`Volume: ${e.target.value}%`);
$('#ch-spd').addEventListener('click',e=>{const c=e.target.closest('.chip');if(c)$('#pp-spd-lbl').textContent=c.textContent;});
$('#pp-save').addEventListener('click',()=>{const l=sel();if(!l)return;
  l.name=$('#pp-name').value||l.name; l.opacity=$('#pp-op').value/100; l.volume=$('#pp-vol').value/100;
  touch();renderAll();toast('Properties saved');});
const PALETTE=[['#FFD600','Yellow'],['#FFFFFF','White'],['#38BDF8','Cyan'],['#EF4444','Red'],['#10B981','Green'],['#F59E0B','Orange'],['#818CF8','Purple']];
let txColor='#FFD600';
PALETTE.forEach(([c],i)=>{ const d=document.createElement('button'); d.className='dotc'+(i===0?' on':''); d.style.background=c;
  d.addEventListener('click',()=>{txColor=c;$$('#tx-dots .dotc').forEach(x=>x.classList.remove('on'));d.classList.add('on');});
  $('#tx-dots').appendChild(d); });
$('#tx-size').addEventListener('input',e=>$('#tx-size-lbl').textContent=`Font Size: ${e.target.value}sp`);
$('#tx-shadow').addEventListener('click',e=>e.target.closest('.switch').classList.toggle('on'));
$('#tx-apply').addEventListener('click',()=>{ const t=$('#tx-content').value||'Reaction Text';
  const l=sel();
  if(l&&l.type==='text'){ l.text={s:t,c:txColor,size:+$('#tx-size').value}; l.name='Text: '+t.slice(0,12); }
  else { const nl=mkLayer({name:'Text: '+t.slice(0,12),type:'text',accent:'#EF4444',text:{s:t,c:txColor,size:+$('#tx-size').value},t:{cx:.5,cy:.82,w:.75,h:.12,rot:0}});
    S.layers.push(nl); S.sel=nl.id; }
  touch();renderAll();closeDlgs();toast('Added Text Overlay');});
$('#rn-ok').addEventListener('click',()=>{ const v=$('#rn-field').value.trim(); if(!v)return;
  $('#proj-name').textContent=v; touch(); closeDlgs(); toast('Renamed to '+v); });
$('#d9-save').addEventListener('click',()=>{closeDlgs();doSave();});
$('#d9-exp').addEventListener('click',()=>startExportFlow());
/* harness */
$$('#harness [data-view]').forEach(b=>b.addEventListener('click',()=>{
  $$('#harness [data-view]').forEach(x=>x.classList.remove('on')); b.classList.add('on');
  closeDlgs(); const v=b.dataset.view;
  if(v==='splash'){ playSplash(); return; }
  if(v==='studio'){ S.fc=false;S.chrome=true;S.sidebar=true; }
  if(v==='fullcanvas'){ S.fc=true;S.chrome=true; }
  if(v==='immersive'){ S.fc=true;S.chrome=false; }
  applyModes();
}));
$$('#harness [data-dlg]').forEach(b=>b.addEventListener('click',()=>openDlg(b.dataset.dlg)));
$('#hk-sample').addEventListener('change',e=>{ if(e.target.checked){sampleLayers();} else {S.layers=[];S.sel=null;S.playing=false;S.posMs=0;} touch(false); renderAll(); });
$('#hk-toast').addEventListener('click',()=>toast('Project saved to local store'));
/* splash */
function playSplash(){ const s=$('#splash'); s.classList.remove('on','play'); void s.offsetWidth;
  s.classList.add('on','play');
  setTimeout(()=>{ s.classList.remove('on','play'); },1300); }
/* boot */
sampleLayers(); renderAll(); syncTop(); applyModes(); sizeCanvas();
window.addEventListener('resize',sizeCanvas);
playSplash();
setTimeout(()=>openDlg('dlg-startup'),1400);
