/* Reaction Studio — sidebar studio study (current app palette).
   Chrome/panel study: layout, states, fit ladder. Canvas gestures are out of scope
   (already built in the real StageView). */
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const fmt=ms=>{const t=Math.max(0,Math.floor(ms/1000));return String(Math.floor(t/60)).padStart(2,'0')+':'+String(t%60).padStart(2,'0')};
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
let _id=0; const nid=p=>p+(++_id);

const ASPECTS={'16:9':16/9,'9:16':9/16,'1:1':1};
const BGS={Dark:'#101218',Black:'#000000',White:'#FFFFFF',Orange:'#E65100',Navy:'#0D1B2A',Green:'#1B4332',Purple:'#3A0CA3'};
const ACCENT={video:'#FF5A2C',frontcam:'#FFA02C',backcam:'#EB5A5A',image:'#46D282',screen:'#7C93C4',text:'#FFD166'};
const TLBL={video:'Video',frontcam:'Front Camera',backcam:'Back Camera',image:'Image',screen:'Screen',text:'Text'};
const TYPE_ICON={video:'movie',frontcam:'camera_front',backcam:'photo_camera',image:'image',screen:'screen_share',text:'text_fields'};
const TEXT_COLORS=['#FFD600','#FFFFFF','#FF5A2C','#EB5A5A','#46D282','#7C93C4','#B18CF8'];

const S={
  orient:'landscape', view:'studio', aspect:'16:9', bg:'Dark',
  playing:false, pos:0, recording:false, recT:0, lastRec:0,
  hud:false, light:'Off', dirty:false, sel:null, sideOpen:true,
  expanded:{layers:true,source:false,audio:false,record:true,canvas:false,export:false,project:false},
  subs:{}, layers:[]
};
let undoStack=[], redoStack=[], expTimer=null, scrubbing=false;

function mkLayer(o){return Object.assign({id:nid('L'),visible:true,locked:false,muted:false,solo:false,loop:true,
  playing:true,fit:'FILL',vol:100,op:100,speed:1,rot:0,cx:.5,cy:.5,w:.8,h:.45},o)}
function sampleLayers(){
  S.layers=[
    mkLayer({type:'video',name:'Video Clip #1',w:.94,h:.53,cy:.44}),
    mkLayer({type:'frontcam',name:'Front Camera',w:.34,h:.19,cx:.83,cy:.22}),
    mkLayer({type:'text',name:'Reaction Text',w:.86,h:.14,cy:.84,text:'😱 UNBELIEVABLE TWIST!',tcolor:'#FFD600',tsize:30,shadow:true})
  ];
}
const sel=()=>S.layers.find(l=>l.id===S.sel)||null;
const dur=()=>S.layers.some(l=>l.type==='video')?96000:0;
const hasAudio=l=>l.type!=='text'&&l.type!=='image';
const anyCam=()=>S.layers.some(l=>l.type==='frontcam'||l.type==='backcam');

/* ---------- undo (mock: snapshots of layers+aspect+bg) ---------- */
const ser=()=>JSON.stringify({layers:S.layers,aspect:S.aspect,bg:S.bg});
function snapshot(){undoStack.push(ser()); if(undoStack.length>40)undoStack.shift(); redoStack=[]; syncUndoRedo();}
function restore(json){const d=JSON.parse(json); S.layers=d.layers; S.aspect=d.aspect; S.bg=d.bg; if(!sel())S.sel=null;}
function doUndo(){if(!undoStack.length)return; redoStack.push(ser()); restore(undoStack.pop()); syncUndoRedo(); sizeCanvas(); renderAll(); toast('Undone');}
function doRedo(){if(!redoStack.length)return; undoStack.push(ser()); restore(redoStack.pop()); syncUndoRedo(); sizeCanvas(); renderAll(); toast('Redone');}
function syncUndoRedo(){$('#btn-undo').disabled=!undoStack.length; $('#btn-redo').disabled=!redoStack.length;}
function touch(){S.dirty=true; syncTop();}
function after(){touch(); renderAll();}

/* ---------- top strip ---------- */
function syncTop(){
  const b=$('#btn-save'); b.classList.toggle('dirty',S.dirty);
  $('#save-lbl').textContent=S.dirty?'Save':'Saved';
  $('#aspect-lbl').textContent=S.aspect;
  $('#fc-ic').textContent=(S.view==='studio')?'fullscreen':'fullscreen_exit';
  $('#btn-fc').classList.toggle('on',S.view!=='studio');
  syncUndoRedo();
}

/* ---------- sidebar: layers ---------- */
function renderLayerList(){
  const box=$('#layer-list'); box.innerHTML='';
  if(!S.layers.length) box.innerHTML='<div class="hint">No layers yet.<br>Use <b>Add</b> below to start.</div>';
  [...S.layers].reverse().forEach(l=>{
    const r=document.createElement('div'); r.className='layer-row'+(l.id===S.sel?' sel':'');
    r.innerHTML=`<span class="ms type" style="color:${ACCENT[l.type]}">${TYPE_ICON[l.type]}</span>
      <span class="nm">${esc(l.name)}</span>
      <button class="mini ${l.visible?'':'off'}" data-a="eye" title="Show / hide"><span class="ms">${l.visible?'visibility':'visibility_off'}</span></button>
      ${hasAudio(l)?`<button class="mini ${l.muted?'warn':'off'}" data-a="mute" title="Mute / unmute"><span class="ms">${l.muted?'volume_off':'volume_up'}</span></button>`:''}
      <button class="mini sm" data-a="up" title="Bring forward"><span class="ms">arrow_upward</span></button>
      <button class="mini sm" data-a="down" title="Send backward"><span class="ms">arrow_downward</span></button>`;
    r.addEventListener('click',ev=>{
      const b=ev.target.closest('button');
      if(b){const a=b.dataset.a;
        if(a==='eye'){snapshot(); l.visible=!l.visible; after();}
        else if(a==='mute'){snapshot(); l.muted=!l.muted; after();}
        else if(a==='up')moveZ(l,1); else if(a==='down')moveZ(l,-1);
        return;}
      S.sel=l.id; renderAll();
    });
    box.appendChild(r);
  });
  $('#bd-layers').textContent=S.layers.length;
  $('#act-del').disabled=!sel(); $('#act-dup').disabled=!sel();
}
function moveZ(l,dir){
  const i=S.layers.indexOf(l), j=i+dir;
  if(j<0||j>=S.layers.length)return;
  snapshot(); [S.layers[i],S.layers[j]]=[S.layers[j],S.layers[i]]; after();
}

/* ---------- sidebar: SOURCE section (re-renders per selection) ---------- */
function renderSource(){
  const box=$('#sec-source'), l=sel();
  $('#bd-source').textContent=l?l.name.slice(0,12):'';
  if(!l){box.innerHTML='<div class="hint">Select a layer to adjust its controls.</div>'; return;}
  const fitOpen=!!S.subs['src-fit'], trOpen=!!S.subs['src-transform'];
  const media=l.type!=='text'&&l.type!=='image';
  box.innerHTML=`
    <button class="act ${l.visible?'active':''}"><span class="ms">${l.visible?'visibility':'visibility_off'}</span><span>${l.visible?'Hide Layer':'Show Layer'}</span></button>
    <button class="act ${l.locked?'active':''}"><span class="ms">${l.locked?'lock':'lock_open'}</span><span>${l.locked?'Unlock Position':'Lock Position'}</span></button>
    ${media?`<button class="act ${(!l.playing)?'active':''}"><span class="ms">${l.playing?'pause_circle':'play_circle'}</span><span>${l.playing?'Pause Layer':'Resume Layer'}</span></button>`:''}
    <button class="sub-head ${fitOpen?'open':''}" data-sub="src-fit"><span class="ms">crop_free</span><span class="lbl">Fit Mode (${l.fit==='FILL'?'Fill':'Fit'})</span><span class="ms chev">chevron_right</span></button>
    <div class="submenu ${fitOpen?'open':''}">
      <button class="act ${l.fit==='FILL'?'active':''}"><span class="ms">fullscreen</span><span>Fill (Cover / Crop)</span></button>
      <button class="act ${l.fit==='FIT'?'active':''}"><span class="ms">fit_screen</span><span>Fit (Letterbox)</span></button>
    </div>
    <button class="sub-head ${trOpen?'open':''}" data-sub="src-transform"><span class="ms">open_with</span><span class="lbl">Transform &amp; Sizing</span><span class="ms chev">chevron_right</span></button>
    <div class="submenu ${trOpen?'open':''}">
      <button class="act" data-t="autofill"><span class="ms">fit_screen</span><span>Auto Fill (100% Canvas)</span></button>
      <button class="act" data-t="fitinside"><span class="ms">aspect_ratio</span><span>Fit Inside Frame (90%)</span></button>
      <button class="act" data-t="center"><span class="ms">filter_center_focus</span><span>Center on Canvas</span></button>
      <button class="act" data-t="sw"><span class="ms">straighten</span><span>Stretch 100% Width</span></button>
      <button class="act" data-t="sh"><span class="ms">height</span><span>Stretch 100% Height</span></button>
      <div class="mini-div"></div>
      <div class="ctl"><div class="lab"><span class="ttl"><span class="ms">width</span>Width Scale</span><b id="w-lbl">${Math.round(l.w*100)}%</b></div>
        <input type="range" id="w-range" min="10" max="150" value="${Math.round(l.w*100)}"></div>
      <div class="ctl"><div class="lab"><span class="ttl"><span class="ms">height</span>Height Scale</span><b id="h-lbl">${Math.round(l.h*100)}%</b></div>
        <input type="range" id="h-range" min="10" max="150" value="${Math.round(l.h*100)}"></div>
      <div class="mini-div"></div>
      <button class="act" data-t="tl"><span class="ms">north_west</span><span>Corner · Top-Left</span></button>
      <button class="act" data-t="tr"><span class="ms">north_east</span><span>Corner · Top-Right</span></button>
      <button class="act" data-t="bl"><span class="ms">south_west</span><span>Corner · Bottom-Left</span></button>
      <button class="act" data-t="br"><span class="ms">south_east</span><span>Corner · Bottom-Right</span></button>
      <div class="mini-div"></div>
      <div class="ctl amber"><div class="lab"><span class="ttl"><span class="ms">rotate_right</span>Rotation Angle</span><b id="rot-lbl">${Math.round(l.rot)}°</b></div>
        <input type="range" id="rot-range" min="-180" max="180" value="${Math.round(l.rot)}">
        <div class="rotbtns"><button data-t="rm90">↺ −90°</button><button data-t="rp90">↻ +90°</button><button class="amber" data-t="rreset">Reset 0°</button></div></div>
    </div>
    <div class="ctl"><div class="lab"><span class="ttl"><span class="ms">transparency</span>Opacity</span><b id="op-lbl">${l.op}%</b></div>
      <input type="range" id="op-range" min="0" max="100" value="${l.op}"></div>
    <button class="act" data-t="setbg"><span class="ms">wallpaper</span><span>Set as Background</span></button>
    <button class="act" id="act-adv"><span class="ms">settings</span><span>Advanced Properties…</span></button>
    ${l.type==='text'?`<button class="act" id="act-tx"><span class="ms">text_fields</span><span>Text…</span></button>`:''}`;
  // bindings
  const acts=box.querySelectorAll('.act[data-t]');
  acts.forEach(b=>b.addEventListener('click',()=>{snapshot(); doTransform(l,b.dataset.t); after();}));
  box.querySelectorAll('.act').forEach((b,i)=>{
    if(b.dataset.t||b.id)return;
    const label=b.textContent;
    if(label.startsWith('Hide')||label.startsWith('Show'))b.onclick=()=>{snapshot(); l.visible=!l.visible; after();};
    else if(label.startsWith('Lock')||label.startsWith('Unlock'))b.onclick=()=>{snapshot(); l.locked=!l.locked; after();};
    else if(label.startsWith('Pause')||label.startsWith('Resume'))b.onclick=()=>{snapshot(); l.playing=!l.playing; after();};
  });
  box.querySelectorAll('[data-sub="src-fit"] .act').forEach(b=>b.addEventListener('click',()=>{
    snapshot(); l.fit=b.textContent.trim().startsWith('Fill')?'FILL':'FIT'; after();}));
  const w=$('#w-range'), h=$('#h-range'), ro=$('#rot-range'), op=$('#op-range');
  w.oninput=()=>{l.w=w.value/100; $('#w-lbl').textContent=w.value+'%'; renderCanvas();};
  w.onchange=()=>{snapshot(); touch();};
  h.oninput=()=>{l.h=h.value/100; $('#h-lbl').textContent=h.value+'%'; renderCanvas();};
  h.onchange=()=>{snapshot(); touch();};
  ro.oninput=()=>{l.rot=+ro.value; $('#rot-lbl').textContent=ro.value+'°'; renderCanvas();};
  ro.onchange=()=>{snapshot(); touch();};
  op.oninput=()=>{l.op=+op.value; $('#op-lbl').textContent=op.value+'%'; renderCanvas();};
  op.onchange=()=>{snapshot(); touch();};
  const adv=$('#act-adv'); if(adv)adv.onclick=()=>{prefillProps(l.id); openDlg('dlg-props');};
  const tx=$('#act-tx'); if(tx)tx.onclick=()=>{prefillText(l.id); openDlg('dlg-text');};
}
function doTransform(l,k){
  switch(k){
    case 'autofill': l.cx=.5;l.cy=.5;l.w=1;l.h=1;l.rot=0; break;
    case 'fitinside': l.cx=.5;l.cy=.5;l.w=.9;l.h=.9; break;
    case 'center': l.cx=.5;l.cy=.5; break;
    case 'sw': l.w=1;l.cx=.5; break;
    case 'sh': l.h=1;l.cy=.5; break;
    case 'tl': l.cx=l.w/2;l.cy=l.h/2; break;
    case 'tr': l.cx=1-l.w/2;l.cy=l.h/2; break;
    case 'bl': l.cx=l.w/2;l.cy=1-l.h/2; break;
    case 'br': l.cx=1-l.w/2;l.cy=1-l.h/2; break;
    case 'rm90': l.rot=(l.rot-90+180)%360-180; break;
    case 'rp90': l.rot=(l.rot+90+180)%360-180; break;
    case 'rreset': l.rot=0; break;
    case 'setbg': l.w=1;l.h=1;l.cx=.5;l.cy=.5;l.rot=0;l.fit='FILL'; break;
  }
}

/* ---------- sidebar: AUDIO section ---------- */
function renderAudio(){
  const box=$('#sec-audio'), l=sel();
  box.innerHTML=`<button class="act" id="act-mixer"><span class="ms">equalizer</span><span>Mixer Panel…</span></button>
    ${l?`<div class="mini-div"></div>
    <div class="ctl"><div class="lab"><span class="ttl"><span class="ms">volume_up</span>Volume · ${esc(l.name.slice(0,14))}</span><b id="vol-lbl">${l.vol}%</b></div>
      <input type="range" id="vol-range" min="0" max="100" value="${l.vol}"></div>
    <button class="act ${l.muted?'active':''}" id="a-mute"><span class="ms">${l.muted?'volume_off':'volume_up'}</span><span>${l.muted?'Unmute Layer':'Mute Layer'}</span></button>
    <button class="act ${l.solo?'active':''}" id="a-solo"><span class="ms">stars</span><span>${l.solo?'Un-solo Track':'Solo Track'}</span></button>
    ${l.type==='video'?`<button class="act ${l.loop?'active':''}" id="a-loop"><span class="ms">repeat</span><span>${l.loop?'Loop: On':'Loop: Off'}</span></button>`:''}`:''}`;
  $('#act-mixer').onclick=openMixer;
  const v=$('#vol-range');
  if(v){v.oninput=()=>{l.vol=+v.value; $('#vol-lbl').textContent=v.value+'%';}; v.onchange=()=>{snapshot(); touch();};}
  const m=$('#a-mute'); if(m)m.onclick=()=>{snapshot(); l.muted=!l.muted; after();};
  const so=$('#a-solo'); if(so)so.onclick=()=>{snapshot(); l.solo=!l.solo; after();};
  const lp=$('#a-loop'); if(lp)lp.onclick=()=>{snapshot(); l.loop=!l.loop; after();};
}

/* ---------- sidebar: RECORD section ---------- */
function renderRecord(){
  $('#rec-lbl').textContent=S.recording?'Stop & Save Recording':'Start Recording';
  $('#act-record').classList.toggle('active',S.recording);
  $('#bd-rec').style.display=S.recording?'':'none';
  $('#light-lbl').textContent='Light ('+S.light+')';
  const cam=anyCam();
  $$('#sub-light .light').forEach(b=>{
    const v=b.dataset.light, needsCam=(v!=='Off'&&v!=='Screen Light');
    b.classList.toggle('active',v===S.light);
    b.classList.toggle('off',needsCam&&!cam);
    b.disabled=needsCam&&!cam;
    b.title=needsCam&&!cam?'Add a live camera first (disabled with reason)':'';
  });
}

/* ---------- canvas ---------- */
function torchOn(l){
  if(l.type==='frontcam')return S.light==='Front Torch'||S.light==='Both Torches';
  if(l.type==='backcam')return S.light==='Back Torch'||S.light==='Both Torches';
  return false;
}
function visual(l){
  const bars='<i></i><i></i><i></i><i></i><i></i>';
  switch(l.type){
    case 'video': return `<div class="vdemo${l.fit==='FIT'?' lb':''}"></div><div class="vbars">${bars}</div>
      <div class="vbadge${l.playing&&S.playing?'':' idle'}"><span class="ms">${l.playing?'play_arrow':'pause'}</span><span class="nm">${esc(l.name)}</span></div>`;
    case 'frontcam': case 'backcam': return `<div class="camv ${l.type==='backcam'?'back':''} ${(l.playing&&!l.muted)?'live':''}">
      <div class="face"></div><div class="cam-pill"><i></i>${l.type==='frontcam'?'FRONT':'BACK'}</div>
      ${torchOn(l)?'<div class="torchdot"></div>':''}<div class="bars">${bars}</div></div>`;
    case 'image': return '<div class="imgv"><span class="ms">add_photo_alternate</span><b>REACTION</b></div>';
    case 'screen': return '<div class="scrv"><span class="ms">desktop_windows</span><b>DISPLAY SCREEN</b></div>';
    case 'text': return `<div class="txtv" style="color:${l.tcolor};font-size:${l.tsize}px;text-shadow:${l.shadow?'3px 3px 6px #000':'none'}">${esc(l.text||'')}</div>`;
  }
  return '';
}
function renderCanvas(){
  const box=$('#layers'); box.innerHTML='';
  $('#empty').style.display=S.layers.length?'none':'flex';
  S.layers.forEach(l=>{
    const el=document.createElement('div');
    el.className='layer'+((S.playing&&l.playing)?' playing':'');
    el.style.left=((l.cx-l.w/2)*100)+'%'; el.style.top=((l.cy-l.h/2)*100)+'%';
    el.style.width=(l.w*100)+'%'; el.style.height=(l.h*100)+'%';
    if(l.rot)el.style.transform=`rotate(${l.rot}deg)`;
    if(l.op<100)el.style.opacity=l.op/100;
    if(!l.visible)el.style.display='none';
    el.innerHTML=visual(l);
    box.appendChild(el);
    if(l.id===S.sel&&l.visible){
      const c=document.createElement('div'); c.className='chrome'+(l.locked?' locked':'');
      c.style.left=el.style.left; c.style.top=el.style.top; c.style.width=el.style.width; c.style.height=el.style.height;
      if(l.rot)c.style.transform=`rotate(${l.rot}deg)`;
      c.innerHTML=`<div class="tag">${esc((TLBL[l.type]||'').toUpperCase())} · ${esc(l.name)}</div>
        <div class="cdot tl"></div><div class="cdot tr"></div><div class="cdot bl"></div><div class="cdot br"></div>`;
      box.appendChild(c);
    }
  });
  const hn=S.layers.filter(l=>!l.visible).length;
  $('#hpill').style.display=hn?'flex':'none'; $('#hpill-n').textContent=hn+' hidden';
}

/* ---------- quick bar ---------- */
function setQ(sel2,on,icon,cls){
  const b=$(sel2); b.className='ic'+(on?' on':'')+(cls?' '+cls:''); b.querySelector('.ms').textContent=icon;
}
function renderQbar(){
  const l=sel(), qb=$('#qbar');
  if(!l){qb.style.display='none'; return;}
  qb.style.display='flex';
  setQ('#q-eye',!l.visible,l.visible?'visibility':'visibility_off',l.visible?'':'on');
  setQ('#q-mute',l.muted,l.muted?'volume_off':'volume_up',l.muted?'warn':'');
  setQ('#q-play',S.playing&&l.playing,l.playing?'pause':'play_arrow',S.playing&&l.playing?'':'');
  setQ('#q-lock',l.locked,l.locked?'lock':'lock_open',l.locked?'warn':'');
  setQ('#q-fit',l.fit==='FILL',l.fit==='FILL'?'fullscreen':'fit_screen',l.fit==='FILL'?'on':'');
  $('#q-cam').style.display=(l.type==='frontcam'||l.type==='backcam')?'flex':'none';
}
function quick(a){
  const l=sel(); if(!l)return;
  switch(a){
    case 'eye': snapshot(); l.visible=!l.visible; after(); toast(l.visible?'Layer shown':'Layer hidden'); break;
    case 'mute': snapshot(); l.muted=!l.muted; after(); toast(l.muted?'Muted':'Unmuted'); break;
    case 'play': l.playing=!l.playing; renderAll(); break;
    case 'lock': snapshot(); l.locked=!l.locked; after(); toast(l.locked?'Layer locked':'Layer unlocked'); break;
    case 'fit': snapshot(); l.fit=l.fit==='FILL'?'FIT':'FILL'; after(); toast(l.fit==='FILL'?'Fill (cover)':'Fit (letterbox)'); break;
    case 'del': removeSel(); break;
    case 'take': toast('Take recorded: 4s clip added as Video'); addLayer('video'); break;
    case 'flip': toast('Switched to '+(l.type==='frontcam'?'Back':'Front')+' Camera');
      snapshot(); l.type=l.type==='frontcam'?'backcam':'frontcam'; l.name=TLBL[l.type]; after(); break;
  }
}

/* ---------- add / remove / duplicate ---------- */
const ADD_GEO={video:{cx:.5,cy:.45,w:.94,h:.53},frontcam:{cx:.83,cy:.22,w:.34,h:.19},
  backcam:{cx:.17,cy:.22,w:.34,h:.19},image:{cx:.5,cy:.5,w:.3,h:.34},screen:{cx:.5,cy:.45,w:.9,h:.5},
  text:{cx:.5,cy:.84,w:.86,h:.14}};
function addLayer(type){
  snapshot();
  const name={video:`Video Clip #${S.layers.filter(x=>x.type==='video').length+1}`,frontcam:'Front Camera',
    backcam:'Back Camera',image:'Sticker Overlay',screen:'Display Screen',text:'Reaction Text'}[type];
  const l=mkLayer(Object.assign({type,name},ADD_GEO[type]));
  if(type==='text'){l.text='😱 UNBELIEVABLE TWIST!'; l.tcolor='#FFD600'; l.tsize=30; l.shadow=true;}
  S.layers.push(l); S.sel=l.id; S.expanded.source=true;
  document.querySelector('[data-sec="source"]').classList.add('open');
  $('#sec-source').classList.add('open');
  touch(); renderAll(); toast('Added '+name);
  if(type==='text'){prefillText(l.id); openDlg('dlg-text');}
}
function removeSel(){
  const l=sel(); if(!l)return;
  snapshot(); S.layers=S.layers.filter(x=>x.id!==l.id); S.sel=null; after(); toast('Layer deleted');
}
function dupSel(){
  const l=sel(); if(!l)return;
  if(l.type==='frontcam'||l.type==='backcam'){toast('Cannot duplicate a live camera (disabled with reason)');return;}
  snapshot(); const c=JSON.parse(JSON.stringify(l)); c.id=nid('L'); c.name=l.name+' copy'; c.rot=0;
  S.layers.push(c); S.sel=c.id; after(); toast('Layer duplicated');
}

/* ---------- sizing + fit ladder ---------- */
function sizeCanvas(){
  const st=$('#stage'); const w=st.clientWidth, h=st.clientHeight;
  const im=S.view==='studio'?{t:12,r:12,b:72,l:12}:{t:8,r:8,b:8,l:8};
  const availW=Math.max(60,w-im.l-im.r), availH=Math.max(60,h-im.t-im.b);
  const ar=ASPECTS[S.aspect];
  let cw=Math.min(availW,availH*ar), ch=cw/ar;
  if(ch>availH){ch=availH; cw=ch*ar;}
  const cv=$('#canvas'); cv.style.width=cw+'px'; cv.style.height=ch+'px'; cv.style.background=BGS[S.bg];
  return {w,h,cw,ch};
}
function layoutPills(){
  const st=$('#stage'); const w=st.clientWidth;
  document.body.classList.toggle('narrow',w<700);
  document.body.classList.toggle('xnarrow',w<560);
  const {cw}=sizeCanvas();
  const inline=S.view==='studio'&&((w-cw)/2)<110;
  $('#tl-inline').style.display=(S.view==='studio'&&inline)?'flex':'none';
  $('#transport').style.display=(S.view==='studio'&&!inline)?'flex':'none';
  const wp=$('#wpill');
  wp.classList.toggle('mini',S.view==='imm'||(S.view==='studio'&&w<520));
  const qb=$('#qbar');
  qb.style.maxWidth=(S.view==='studio'&&w>=520)?'calc(100% - 250px)':'calc(100% - 110px)';
}

/* ---------- views / modes ---------- */
function setView(v){
  S.view=v;
  const app=$('#app');
  app.classList.toggle('fc',v==='fc'||v==='imm');
  app.classList.toggle('imm',v==='imm');
  const wp=$('#wpill');
  if(v==='studio'){
    wp.classList.remove('fc','mini');
    $('#wpill-ic').textContent='fullscreen'; $('#wpill-tx').textContent='Full Canvas';
    $('#wpill-tx').style.display=''; $('#wpill-dv').style.display=''; $('#wpill-eye').style.display='none';
  }else if(v==='fc'){
    wp.classList.add('fc'); wp.classList.remove('mini');
    $('#wpill-ic').textContent='fullscreen_exit'; $('#wpill-tx').textContent='Exit';
    $('#wpill-tx').style.display=''; $('#wpill-dv').style.display=''; $('#wpill-eye').style.display='';
  }else{
    wp.classList.add('fc','mini');
    $('#wpill-ic').textContent='visibility'; $('#wpill-tx').style.display='none';
    $('#wpill-dv').style.display='none'; $('#wpill-eye').style.display='';
  }
  $('#recpill').style.display=(S.recording&&v!=='studio')?'flex':'none';
  $$('.htab[data-view]').forEach(b=>b.classList.toggle('on',b.dataset.view===v));
  $('#act-fc').classList.toggle('active',v!=='studio');
  sizeCanvas(); renderCanvas(); layoutPills(); syncTop();
}
function toggleSidebar(){
  S.sideOpen=!S.sideOpen;
  $('#sidebar').classList.toggle('closed',!S.sideOpen);
  $('#btn-hamb').classList.toggle('on',S.sideOpen);
  $('#scrim-side').classList.toggle('on',S.sideOpen&&S.orient==='portrait');
}
function setOrient(o){
  S.orient=o;
  $('#app').classList.toggle('portrait',o==='portrait');
  $$('.htab[data-orient]').forEach(b=>b.classList.toggle('on',b.dataset.orient===o));
  if(o==='portrait'){S.sideOpen=false; $('#sidebar').classList.add('closed'); $('#btn-hamb').classList.remove('on'); $('#scrim-side').classList.remove('on');}
  else{S.sideOpen=true; $('#sidebar').classList.remove('closed'); $('#btn-hamb').classList.add('on'); $('#scrim-side').classList.remove('on');}
  sizeCanvas(); renderCanvas(); layoutPills();
}

/* ---------- transport / timeline ---------- */
function syncTime(){
  const d=dur();
  $('#tl-time').textContent=fmt(S.pos)+' / '+fmt(d);
  const sk=$('#tl-seek'); sk.disabled=d===0;
  if(!scrubbing)sk.value=d?Math.round(S.pos/d*1000):0;
  $('#recpill-tx').textContent='REC '+fmt(S.recT);
  $('#tl-recdot').style.display=S.recording?'':'none';
  $('#recpill').style.display=(S.recording&&S.view!=='studio')?'flex':'none';
  $('#t-recbtn').className='tbtn rc'+(S.recording?' live':'');
  $('#tl-rec').className='rc'+(S.recording?' live':'');
  $('#hud-l3').textContent=`Layers: ${S.layers.filter(l=>l.visible).length} active · Latency: 12ms`;
}
function togglePlay(){
  if(dur()===0){toast('Add a video to the timeline before playing');return;}
  S.playing=!S.playing;
  $('#tl-pp').classList.toggle('on',S.playing);
  $('#tl-pp .ms').textContent=S.playing?'pause':'play_arrow';
  renderCanvas(); renderQbar();
}
function stopTransport(){S.playing=false; S.pos=0; $('#tl-pp').classList.remove('on'); $('#tl-pp .ms').textContent='play_arrow'; renderCanvas(); syncTime();}
function toggleRec(){
  if(S.recording){
    S.recording=false; S.lastRec=S.recT; S.recT=0;
    toast(`Take saved: ${Math.max(1,Math.round(S.lastRec/1000))}s reaction clip`);
  }else{
    if(!S.layers.length){toast('Add a layer before recording');return;}
    S.recording=true; S.recT=0; toast('Recording started');
  }
  renderRecord(); syncTime();
}
setInterval(()=>{
  if(S.playing&&dur()>0){S.pos+=250; if(S.pos>=dur())S.pos=0;}
  if(S.recording)S.recT+=250;
  syncTime();
},250);

/* ---------- export ---------- */
function runExport(label){
  openDlg('dlg-progress');
  $('#exp-title').textContent='Rendering Video…';
  $('#exp-busy').style.display='block'; $('#exp-done').style.display='none'; $('#exp-fail').style.display='none';
  $('#exp-ok').style.display='none'; $('#exp-close').style.display='none';
  $('#exp-bar').style.width='0%'; $('#exp-pct').textContent='Encoding frames: 0%';
  clearInterval(expTimer); let p=0;
  expTimer=setInterval(()=>{
    p+=3+Math.random()*7;
    if(p>=100){p=100; clearInterval(expTimer);
      $('#exp-title').textContent='Export Completed!';
      $('#exp-busy').style.display='none'; $('#exp-done').style.display='block';
      $('#exp-ok').style.display='';
      toast('Export complete! Video saved.');
    }
    $('#exp-bar').style.width=p+'%';
    $('#exp-pct').textContent='Encoding frames: '+Math.floor(p)+'%';
  },170);
}

/* ---------- dialogs ---------- */
function openDlg(id){
  $$('.scrim.on').forEach(d=>d.classList.remove('on'));
  if(id==='dlg-mixer')buildMixerRows();
  if(id==='dlg-props')prefillProps();
  if(id==='dlg-text')prefillText();
  if(id==='dlg-rename')$('#rn-field').value=$('#proj-name').textContent;
  document.getElementById(id).classList.add('on');
}
function closeDlg(){$$('.scrim.on').forEach(d=>d.classList.remove('on')); clearInterval(expTimer);}
function prefillProps(id){
  const l=(id?S.layers.find(x=>x.id===id):null)||sel();
  if(!l)return;
  $('#pp-name').value=l.name;
  $$('#ch-spd .chip').forEach(c=>c.classList.toggle('on',c.textContent.trim()===l.speed+'x'));
  $('#pp-spd-lbl').textContent=l.speed+'x';
}
function prefillText(id){
  const l=(id?S.layers.find(x=>x.id===id):null)||sel();
  if(!l)return;
  $('#tx-content').value=l.text||'';
  $('#tx-size').value=l.tsize; $('#tx-size-lbl').textContent='Font Size: '+l.tsize+'sp';
  const sh=$('#tx-shadow'); sh.classList.toggle('on',!!l.shadow);
  $$('#tx-dots .dotc').forEach(d=>d.classList.toggle('on',d.dataset.c===l.tcolor));
}
function buildMixerRows(){
  const box=$('#mix-tracks'); box.innerHTML='';
  if(!S.layers.length){box.innerHTML='<div class="hint">No layers yet — add one from LAYERS.</div>';return;}
  S.layers.forEach(l=>{
    const row=document.createElement('div'); row.className='mixrow';
    row.innerHTML=`<span class="nm" style="color:${ACCENT[l.type]}">${esc(l.name)}</span>
      <input type="range" min="0" max="100" value="${l.vol}">
      <span class="vu"><i class="g"></i><i class="g"></i><i class="g"></i><i class="g"></i><i class="g"></i><i class="a"></i><i class="a"></i><i class="r"></i></span>
      <button class="mini ${l.muted?'warn':'off'}" data-m="mute"><span class="ms">${l.muted?'volume_off':'volume_up'}</span></button>
      <button class="mini ${l.solo?'cy':'off'}" data-m="solo"><span class="ms">stars</span></button>`;
    const r=row.querySelector('input');
    r.oninput=()=>{l.vol=+r.value;};
    r.onchange=()=>{snapshot(); touch();};
    row.querySelector('[data-m="mute"]').onclick=()=>{snapshot(); l.muted=!l.muted; buildMixerRows(); after();};
    row.querySelector('[data-m="solo"]').onclick=()=>{snapshot(); l.solo=!l.solo; buildMixerRows(); after();};
    box.appendChild(row);
  });
}
function openMixer(){buildMixerRows(); openDlg('dlg-mixer');}
setInterval(()=>{
  if(!$('#dlg-mixer').classList.contains('on'))return;
  const rows=$$('#mix-tracks .mixrow');
  S.layers.forEach((l,i)=>{
    const row=rows[i]; if(!row)return;
    const segs=row.querySelectorAll('.vu i');
    const active=(l.playing&&(S.playing||l.type==='frontcam'||l.type==='backcam'||l.type==='screen'))&&!l.muted;
    const lvl=active?Math.max(2,Math.round(l.vol/100*8*(0.7+Math.random()*0.3))):0;
    segs.forEach((s,j)=>s.classList.toggle('on',j<lvl));
  });
},200);

/* ---------- aspect / background ---------- */
function setAspect(a){
  if(S.aspect===a)return;
  snapshot(); S.aspect=a; touch();
  $('#bd-canvas').textContent=a; $('#aspect-sub').textContent='Aspect Ratio ('+a+')';
  $$('#sub-aspect .ar').forEach(b=>b.classList.toggle('active',b.dataset.ar===a));
  $$('#dlg-aspect .optcard').forEach(c=>c.classList.toggle('on',c.dataset.ar===a));
  sizeCanvas(); renderCanvas(); layoutPills(); syncTop(); toast('Aspect: '+a);
}
function setBg(name){
  if(S.bg===name)return;
  snapshot(); S.bg=name; touch();
  $('#bg-sub').textContent='Background ('+name+')';
  $$('#sub-bg .bg').forEach(b=>b.classList.toggle('active',b.dataset.bg===name));
  sizeCanvas(); toast('Background: '+name);
}

/* ---------- toasts ---------- */
function toast(msg){
  const d=document.createElement('div'); d.className='toast'; d.textContent=msg;
  $('#toasts').appendChild(d);
  setTimeout(()=>{d.style.opacity='0'; d.style.transition='.3s'; setTimeout(()=>d.remove(),320);},2100);
}

/* ---------- render all ---------- */
function renderAll(){
  renderLayerList(); renderSource(); renderAudio(); renderCanvas(); renderQbar(); renderRecord(); syncTop();
}

/* ================= wiring ================= */
function wire(){
  // section heads
  $$('.sec-head').forEach(h=>h.addEventListener('click',()=>{
    const k=h.dataset.sec; const open=!S.expanded[k]; S.expanded[k]=open;
    h.classList.toggle('open',open);
    document.getElementById('sec-'+k).classList.toggle('open',open);
  }));
  // submenu heads (static + dynamic)
  document.addEventListener('click',e=>{
    const sh=e.target.closest('.sub-head'); if(!sh)return;
    const sub=sh.nextElementSibling; if(!sub||!sub.classList.contains('submenu'))return;
    const open=!sub.classList.contains('open');
    sub.classList.toggle('open',open); sh.classList.toggle('open',open);
    S.subs[sh.dataset.sub]=open;
  });
  // top strip
  $('#btn-hamb').onclick=toggleSidebar;
  $('#scrim-side').onclick=toggleSidebar;
  $('#btn-title').onclick=()=>openDlg('dlg-rename');
  $('#btn-aspect').onclick=()=>openDlg('dlg-aspect');
  $('#btn-undo').onclick=doUndo;
  $('#btn-redo').onclick=doRedo;
  $('#btn-save').onclick=()=>{S.dirty=false; syncTop(); toast('Project saved');};
  $('#btn-export').onclick=()=>runExport();
  $('#btn-fc').onclick=()=>setView(S.view==='studio'?'fc':'studio');
  $('#btn-overflow').onclick=e=>{e.stopPropagation(); const m=$('#overflow-menu'); m.style.display=m.style.display==='none'?'block':'none';};
  document.addEventListener('click',()=>{$('#overflow-menu').style.display='none';});
  $('#m-fc').onclick=()=>setView(S.view==='studio'?'fc':'studio');
  $('#m-hud').onclick=()=>{S.hud=!S.hud; $('#hud').style.display=S.hud?'block':'none'; $('#hk-hud').checked=S.hud;};
  $('#m-diag').onclick=()=>openDlg('dlg-diag');
  // sidebar static actions
  $$('#sub-add [data-add]').forEach(b=>b.onclick=()=>addLayer(b.dataset.add));
  $('#act-del').onclick=removeSel;
  $('#act-dup').onclick=dupSel;
  $('#act-record').onclick=toggleRec;
  $('#act-snap').onclick=()=>toast('Snapshot saved to Gallery at '+fmt(S.pos));
  $('#act-restart').onclick=()=>{S.pos=0; syncTime();};
  $$('#sub-light .light').forEach(b=>b.addEventListener('click',()=>{
    if(b.disabled){toast('Add a live camera first');return;}
    S.light=b.dataset.light; after(); toast('Lighting: '+S.light); syncLightOverlay();
  }));
  $$('#sub-aspect .ar').forEach(b=>b.onclick=()=>setAspect(b.dataset.ar));
  $$('#sub-bg .bg').forEach(b=>b.onclick=()=>setBg(b.dataset.bg));
  $('#act-fc').onclick=()=>setView(S.view==='studio'?'fc':'studio');
  $('#act-fitall').onclick=()=>{snapshot(); S.layers.forEach((l,i)=>{const g=ADD_GEO[l.type]; if(g){l.cx=g.cx;l.cy=g.cy;l.w=g.w;l.h=g.h;l.rot=0;}}); after(); toast('Aligned all sources');};
  $('#act-quickexp').onclick=()=>runExport();
  $('#act-expsettings').onclick=()=>openDlg('dlg-export');
  $('#act-repeatexp').onclick=()=>{toast('Repeating last export'); runExport();};
  $('#act-rename').onclick=()=>openDlg('dlg-rename');
  $('#act-save').onclick=()=>{S.dirty=false; syncTop(); toast('Project saved');};
  $('#act-diag').onclick=()=>openDlg('dlg-diag');
  // stage
  $('#q-eye').onclick=()=>quick('eye');
  $('#q-mute').onclick=()=>quick('mute');
  $('#q-play').onclick=()=>quick('play');
  $('#q-lock').onclick=()=>quick('lock');
  $('#q-fit').onclick=()=>quick('fit');
  $('#q-del').onclick=()=>quick('del');
  $('#q-take').onclick=()=>quick('take');
  $('#q-flip').onclick=()=>quick('flip');
  $('#hpill').onclick=()=>{snapshot(); S.layers.forEach(l=>l.visible=true); after(); toast('All layers shown');};
  $('#wpill').onclick=e=>{
    if(e.target.closest('#wpill-eye')){setView(S.view==='imm'?'fc':'imm'); return;}
    setView(S.view==='studio'?'fc':'studio');
  };
  $('#tl-pp').onclick=togglePlay;
  $('#t-stop').onclick=stopTransport;
  $('#tl-stop').onclick=stopTransport;
  $('#t-recbtn').onclick=toggleRec;
  $('#tl-rec').onclick=toggleRec;
  const sk=$('#tl-seek');
  sk.addEventListener('input',()=>{const d=dur(); if(!d)return; S.pos=sk.value/1000*d; $('#tl-time').textContent=fmt(S.pos)+' / '+fmt(d);});
  sk.addEventListener('pointerdown',()=>scrubbing=true);
  window.addEventListener('pointerup',()=>scrubbing=false);
  // hidden pill & empty-state tap
  $('#stage').addEventListener('click',e=>{
    if(e.target.id==='stage'||e.target.id==='canvas'||e.target.closest('#empty')){S.sel=null; renderAll();}
  });
  // dialogs
  $$('.scrim').forEach(sc=>sc.addEventListener('click',e=>{
    if(e.target===sc&&sc.id!=='dlg-progress')closeDlg();
  }));
  $$('[data-close]').forEach(b=>b.addEventListener('click',closeDlg));
  $$('#dlg-aspect .optcard').forEach(c=>c.onclick=()=>setAspect(c.dataset.ar));
  // chip groups
  ['#ch-res','#ch-fps','#ch-codec','#ch-spd'].forEach(g=>{
    $$(g+' .chip').forEach(c=>c.onclick=()=>{
      $$(g+' .chip').forEach(x=>x.classList.remove('on')); c.classList.add('on');
      if(g==='#ch-spd')$('#pp-spd-lbl').textContent=c.textContent.trim();
    });
  });
  $('#btn-startexp').onclick=()=>{closeDlg(); runExport();};
  $('#pp-save').onclick=()=>{const l=sel(); if(l){snapshot(); l.name=$('#pp-name').value; l.speed=parseFloat($('#pp-spd-lbl').textContent); after(); toast('Layer updated');} closeDlg();};
  $('#tx-size').oninput=e=>{$('#tx-size-lbl').textContent='Font Size: '+e.target.value+'sp';
    const l=sel(); if(l&&l.type==='text'){l.tsize=+e.target.value; renderCanvas();}};
  $('#tx-shadow').onclick=()=>{$('#tx-shadow').classList.toggle('on');
    const l=sel(); if(l&&l.type==='text'){l.shadow=$('#tx-shadow').classList.contains('on'); renderCanvas();}};
  $('#tx-apply').onclick=()=>{const l=sel(); if(l&&l.type==='text'){snapshot();
    l.text=$('#tx-content').value; l.tsize=+$('#tx-size').value; l.shadow=$('#tx-shadow').classList.contains('on');
    const on=$('#tx-dots .dotc.on'); if(on)l.tcolor=on.dataset.c;
    after(); toast('Text updated');} closeDlg();};
  $('#rn-ok').onclick=()=>{const v=$('#rn-field').value.trim(); if(v){$('#proj-name').textContent=v; toast('Renamed to '+v);} closeDlg();};
  $('#exp-ok').onclick=closeDlg; $('#exp-close').onclick=closeDlg;
  $('#btn-folder').onclick=()=>toast('System folder picker would open (SAF tree)');
  // harness
  $$('.htab[data-orient]').forEach(b=>b.onclick=()=>setOrient(b.dataset.orient));
  $$('.htab[data-view]').forEach(b=>b.onclick=()=>setView(b.dataset.view));
  $$('.htab[data-dlg]').forEach(b=>b.onclick=()=>openDlg(b.dataset.dlg));
  $('#h-progress').onclick=()=>runExport();
  $('#hk-sample').onchange=e=>{
    if(e.target.checked){sampleLayers(); S.sel=S.layers[0].id;}
    else{S.layers=[]; S.sel=null;}
    renderAll(); sizeCanvas();
  };
  $('#hk-hud').onchange=e=>{S.hud=e.target.checked; $('#hud').style.display=S.hud?'block':'none';};
  $('#hk-light').onchange=syncLightOverlay;
  $('#hk-toast').onclick=()=>toast('This is how a toast looks');
  window.addEventListener('resize',()=>{sizeCanvas(); renderCanvas(); layoutPills();});
}
function syncLightOverlay(){
  $('#screenlight').classList.toggle('on',S.light==='Screen Light'||$('#hk-light').checked);
}
/* dynamic submenu toggle for SOURCE section lives inside renderSource via the
   document-level .sub-head handler; initial open-state classes are set there too. */

/* ---------- init ---------- */
function buildBgRows(){
  const box=$('#sub-bg');
  Object.keys(BGS).forEach(k=>{
    const b=document.createElement('button');
    b.className='act bg'+(k===S.bg?' active':''); b.dataset.bg=k;
    b.innerHTML=`<span class="dot-ic" style="background:${BGS[k]}"></span><span>${k}</span>`;
    box.appendChild(b);
  });
}
function buildDots(){
  const box=$('#tx-dots');
  TEXT_COLORS.forEach(c=>{
    const d=document.createElement('button');
    d.className='dotc'+(c==='#FFD600'?' on':''); d.dataset.c=c; d.style.background=c;
    d.onclick=()=>{$$('#tx-dots .dotc').forEach(x=>x.classList.remove('on')); d.classList.add('on');
      const l=sel(); if(l&&l.type==='text'){l.tcolor=c; renderCanvas();}};
    box.appendChild(d);
  });
}
sampleLayers();
S.sel=S.layers[0].id;
buildBgRows(); buildDots(); wire();
$('#btn-hamb').classList.add('on');
renderAll(); sizeCanvas(); layoutPills(); syncLightOverlay(); syncTime();
/* automation hook (same convention as the reference pack's test tags) */
window.__mock={get S(){return S;}, sel:()=>sel()};
