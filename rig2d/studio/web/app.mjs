import {FrameLoop, LoadEpoch, idleParameters, blendVertices} from './runtime.mjs';
import {MeshRenderer} from './renderer.mjs';
import {CharacterEditor,anchorNames} from './editor.mjs';
import {LocalAudio} from './audio.mjs';
import {previewPolicy} from './preview.mjs';

const $=id=>document.getElementById(id);
const labels={headX:'左右转头',headY:'抬头低头',tilt:'轻轻歪头',blinkLeft:'左眼眨眼',blinkRight:'右眼眨眼',mouth:'开口说话',breath:'自然呼吸',sway:'发丝与附件随动'};
const storageKey='zhiyu-rig2d-studio-project-v1';
function fileBase64(file) {
  return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);
    reader.onerror=()=>reject(Error('文件读取失败，请重新选择'));reader.readAsDataURL(file);});
}
async function request(path, body, signal) {
  const response=await fetch(path,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:undefined,
    body:body?JSON.stringify(body):undefined,signal,cache:'no-store'});
  const data=await response.json().catch(()=>null);
  if(!response.ok){const detail=data?.error?.message||data?.detail;throw Error(typeof detail==='string'?detail:`操作没有成功（${response.status}），请再试一次`);}
  return data;
}

class Studio {
  constructor(contract) {
    this.contract=contract;this.snapshot=null;this.playing=true;this.panel='anchors';this.weights={};this.gaze={x:0,y:0};
    this.requests=new LoadEpoch();this.renderer=new MeshRenderer($('character'),$('fallback'),contract,(state,message)=>this.renderState(state,message));
    this.editor=new CharacterEditor($('edit-overlay'),modified=>this.edited(modified),error=>this.status(error.message,'error'));
    this.audio=new LocalAudio($('voice-audio'),message=>{$('audio-status').textContent=message;});
    this.loop=new FrameLoop({request:fn=>requestAnimationFrame(fn),cancel:id=>cancelAnimationFrame(id)},time=>this.frame(time),error=>this.renderState('error',error.message));
    this.observer=new ResizeObserver(()=>this.draw());this.observer.observe($('stage'));
    this.bind();this.bindCorrections();this.parameterControls();this.syncEditControls();this.loop.start();
    window.rig2dStudio={debug:()=>this.debug(),dispose:()=>this.dispose()};
  }
  status(message,type='') {$('status').textContent=message;$('status').className=`status ${type}`;}
  busy(value) {
    this.isBusy=value;
    for(const id of ['demo','save','download','play','neutral','add-layer','subject-select','reset-anchors'])$(id).disabled=value || (id!=='demo'&&!this.snapshot);
    $('save').textContent=value?'正在保存角色…':'保存并更新预览';
    $('image-file').disabled=value;$('package-file').disabled=value;
    this.syncEditControls();
  }
  syncEditControls() {
    const locked=!!this.isBusy||!!this.comparing||!this.snapshot,gesture=!!this.editor.drag;
    this.editor.enabled=!locked;$('edit-overlay').classList.toggle('edit-locked',locked);
    document.querySelectorAll('.inspector button,.inspector input,.inspector select').forEach(control=>{control.disabled=locked||gesture;});
    $('remove-layer').disabled=locked||gesture||!this.editor.part();
    $('undo-edit').disabled=locked||gesture||!this.editor.history.canUndo;$('redo-edit').disabled=locked||gesture||!this.editor.history.canRedo;
    $('compare-original').disabled=!!this.isBusy||!this.snapshot||gesture;
    $('save').disabled=locked||gesture;$('download').disabled=!!this.isBusy||!this.snapshot||gesture||!!this.dirty;
    $('download').title=this.dirty?'请先保存修改，再下载角色包':'';
    for(const id of ['demo','image-file','package-file'])$(id).disabled=!!this.isBusy||gesture;
    for(const id of ['play','neutral','idle','follow','mesh'])$(id).disabled=locked;
  }
  async operation(action,origin='open') {
    const epoch=this.requests.begin();this.abort?.abort();this.abort=new AbortController();this.busy(true);
    try {
      const snapshot=await action(this.abort.signal);
      if(!this.requests.current(epoch))return;
      await this.mount(snapshot,epoch,origin);
    } catch(error) {
      if(this.requests.current(epoch)&&error.name!=='AbortError')this.status(error.message,'error');
    } finally {if(this.requests.current(epoch))this.busy(false);}
  }
  async mount(snapshot,epoch,origin) {
    if(!snapshot?.id||!snapshot.definition)throw Error('角色信息不完整，请重新导入');
    if(origin==='save'&&this.snapshot?.id===snapshot.id)this.editor.acceptSaved(snapshot);
    else await this.editor.set(snapshot);
    if(!this.requests.current(epoch))return;
    this.comparing=false;$('stage').classList.remove('comparing');$('original-compare').hidden=true;
    $('compare-original').textContent='查看原图';$('compare-original').setAttribute('aria-pressed','false');
    $('original-compare').src=snapshot.sourceUrl;
    const policy=previewPolicy(origin);this.snapshot=snapshot;this.dirty=false;this.weights={};this.playing=true;$('idle').checked=policy.idle;
    $('project-name').textContent=snapshot.name||'我的角色';$('empty-state').hidden=true;$('play').textContent='暂停动画';$('play').setAttribute('aria-pressed','true');
    $('stage-note').hidden=this.panel==='motion';$('project-meta').textContent=`${snapshot.width} × ${snapshot.height} · 第 ${snapshot.revision} 版`;
    try{localStorage.setItem(storageKey,JSON.stringify({id:snapshot.id,origin:origin==='import'?'import':'open'}));}catch{this.status('浏览器未允许保存最近角色；仍可下载角色包');}
    this.refreshEditor();this.syncParameters();
    if(snapshot.asset)await this.renderer.mount(snapshot);
    else {$('fallback').src=snapshot.sourceUrl;$('fallback').hidden=false;this.status('请确认眼口位置，保存后会生成动态角色');}
    if(!this.requests.current(epoch))return;
    if(this.renderer.ready)this.status(policy.message,origin==='import'?'':'success');
    this.draw();
  }
  renderState(state,message) {
    const names={ready:'动态网格',loss:'原图安全保留',error:'原图预览'};$('render-badge').textContent=names[state]||message;
    if(state!=='ready')this.status(message,state==='error'?'error':'');
    if(state==='loss'||state==='error')this.loop.stop();
    if(state==='ready'&&this.playing&&!this.comparing)this.loop.start();
  }
  async importImage(file) {
    if(!file)return;
    if(file.size>this.contract.maxSourceBytes){this.status('图片大于 20 MB，请选择较小的图片','error');return;}
    this.status('正在准备角色原图与建议位置…');
    await this.operation(async signal=>request('/api/import',{image:await fileBase64(file),name:file.name},signal),'import');
  }
  async restore(file) {
    if(!file)return;
    if(file.size>64*1024*1024){this.status('角色包过大，请使用工坊导出的角色包','error');return;}
    this.status('正在打开角色包…');
    await this.operation(async signal=>request('/api/restore',{package:await fileBase64(file)},signal),'restore');
  }
  async save() {
    if(!this.snapshot||this.isBusy||this.comparing||this.editor.drag)return;
    let definition;
    try{definition=this.editor.definitionForSave();}catch(error){this.status(error.message,'error');return;}
    const {id,revision}=this.snapshot;
    this.status('正在把标注做成可动的角色…');
    await this.operation(signal=>request(`/api/projects/${encodeURIComponent(id)}/build`,{revision,definition},signal),'save');
  }
  async download() {
    if(!this.snapshot||this.isBusy||this.editor.drag)return;
    if(this.dirty){this.status('请先保存你的修改，再下载角色包。');return;}
    try {
      const response=await fetch(`/api/projects/${encodeURIComponent(this.snapshot.id)}/export`);
      if(!response.ok)throw Error('角色包暂时不能下载，请先保存');
      const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');link.href=url;
      link.download=`${(this.snapshot.name||'我的角色').replace(/[\\/:*?"<>|]/g,'_')}.rig2d.zip`;link.click();
      setTimeout(()=>URL.revokeObjectURL(url),1000);this.status('角色包已下载。下次可以直接打开，继续调整。','success');
    }catch(error){this.status(error.message,'error');}
  }
  bind() {
    $('image-file').addEventListener('change',event=>{this.importImage(event.target.files[0]);event.target.value='';});
    $('empty-import').onclick=()=>$('image-file').click();$('package-file').onchange=event=>{this.restore(event.target.files[0]);event.target.value='';};
    $('demo').onclick=()=>{this.status('正在打开十二翼示例…');this.operation(async signal=>{
      const data=await request('/api/demo',undefined,signal);
      const created=await request('/api/import',{image:data.image,name:data.name},signal);
      return request(`/api/projects/${encodeURIComponent(created.id)}/build`,{revision:created.revision,definition:data.definition},signal);
    },'demo');};
    $('save').onclick=()=>this.save();$('download').onclick=()=>this.download();
    $('play').onclick=()=>this.togglePlay();$('neutral').onclick=()=>this.neutral();
    $('idle').onchange=()=>{this.weights={};this.draw();};$('mesh').onchange=()=>this.draw();
    $('follow').onchange=()=>{this.gaze={x:0,y:0};this.draw();};
    document.querySelectorAll('.tab').forEach(button=>button.onclick=()=>this.switchPanel(button.dataset.panel));
    document.querySelectorAll('.anchor').forEach(button=>button.onclick=()=>{this.editor.anchor=button.dataset.anchor;
      document.querySelectorAll('.anchor').forEach(item=>item.classList.toggle('active',item===button));this.editor.draw();});
    $('reset-anchors').onclick=()=>this.safeEdit(()=>this.editor.resetAnchors());
    $('add-layer').onclick=()=>this.safeEdit(()=>{this.editor.addLayer();this.refreshEditor();});
    $('subject-select').onclick=()=>{this.editor.selected='subject';this.refreshEditor();this.editor.draw();};
    document.querySelectorAll('.tool').forEach(button=>button.onclick=()=>{this.editor.tool=button.dataset.tool;
      document.querySelectorAll('.tool').forEach(item=>item.classList.toggle('active',item===button));});
    $('finish-polygon').onclick=()=>this.safeEdit(()=>this.editor.finishPolygon());$('undo-point').onclick=()=>this.safeEdit(()=>this.editor.undoPoint());
    $('remove-layer').onclick=()=>this.safeEdit(()=>{this.editor.removeLayer();this.refreshEditor();});
    $('brush-size').oninput=event=>{this.editor.brush=Number(event.target.value);$('brush-value').value=event.target.value;};
    for(const id of ['layer-name','layer-role','layer-z','layer-strength']){
      $(id).oninput=()=>this.layerProperty(id);$(id).onchange=()=>this.editor.history.breakGroup();}
    $('audio-file').onchange=event=>{const file=event.target.files[0];if(file)this.audio.load(file).catch(error=>{$('audio-status').textContent=error.message;});event.target.value='';};
    $('stage').addEventListener('pointermove',event=>this.follow(event));$('stage').addEventListener('pointerleave',()=>{this.gaze={x:0,y:0};});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)this.loop.stop();else if(this.playing&&!this.comparing)this.loop.start();});
    window.addEventListener('pagehide',()=>this.dispose(),{once:true});
  }
  safeEdit(action) {try{action();}catch(error){this.status(error.message,'error');}}
  bindCorrections() {
    $('undo-edit').onclick=()=>this.safeEdit(()=>{this.editor.undo();this.refreshEditor();});
    $('redo-edit').onclick=()=>this.safeEdit(()=>{this.editor.redo();this.refreshEditor();});
    $('compare-original').onclick=()=>this.compareOriginal();
    this.historyKey=event=>{
      if(!(event.ctrlKey||event.metaKey)||event.altKey||this.isBusy||this.comparing||this.editor.drag)return;
      const target=event.target;if(target.isContentEditable||target.closest('input,textarea,select'))return;
      const key=event.key.toLowerCase();if(key!=='z'&&key!=='y')return;event.preventDefault();
      this.safeEdit(()=>{if(key==='y'||event.shiftKey)this.editor.redo();else this.editor.undo();this.refreshEditor();});
    };
    document.addEventListener('keydown',this.historyKey);
  }
  compareOriginal() {
    if(this.isBusy||!this.snapshot||this.editor.drag)return;
    this.comparing=!this.comparing;$('stage').classList.toggle('comparing',this.comparing);
    $('original-compare').hidden=!this.comparing;$('compare-original').setAttribute('aria-pressed',String(this.comparing));
    $('stage-note').hidden=this.comparing||this.panel==='motion';
    $('compare-original').textContent=this.comparing?'返回编辑':'查看原图';
    if(this.comparing){this.loop.stop();this.status('正在查看导入原图。点击“返回编辑”继续修正。');}
    else {if(this.playing&&this.renderer.ready)this.loop.start();this.draw();this.status('已返回编辑，你的标注和修改都保留。');}
    this.syncEditControls();
  }
  layerProperty(group) {
    const part=this.editor.part();if(!part)return;
    this.safeEdit(()=>this.editor.updatePartProperties({name:$('layer-name').value,role:$('layer-role').value,
      z:Number($('layer-z').value),strength:Number($('layer-strength').value)},`${part.id}:${group}`));this.refreshLayers();
  }
  edited(modified=true) {
    if(this.snapshot&&!this.isBusy){if(modified)this.dirty=true;
      this.status(this.dirty?'调整尚未保存。确认后点“保存并更新预览”，再下载角色包。':
        this.editor.drag?'正在调整，松开后可撤销。':'当前修改已保存，可以下载角色包。');
      $('idle').checked=false;this.weights={};this.renderer.draw({});this.anchorValues();this.syncEditControls();}
  }
  switchPanel(panel) {
    this.panel=panel;this.editor.setMode(panel);
    document.querySelectorAll('.tab').forEach(button=>{const active=button.dataset.panel===panel;button.classList.toggle('active',active);button.setAttribute('aria-selected',active);});
    for(const name of ['anchors','layers','motion'])$(`panel-${name}`).hidden=name!==panel;
    $('stage-note').hidden=!this.snapshot||this.comparing||panel==='motion';
    $('stage-note').textContent=panel==='layers'?'逐层保留头发、翅膀和武器，改错了可撤销。':'拖动调整眼口位置，再看看她动起来的样子。';this.draw();
  }
  anchorValues() {
    const target=$('anchor-values');target.replaceChildren();if(!this.editor.definition)return;
    for(const [key,points]of Object.entries(this.editor.definition.anchors)){
      const row=document.createElement('div'),label=document.createElement('span'),value=document.createElement('span');
      label.textContent=anchorNames[key];value.textContent=points.map(n=>Math.round(n)).join(' / ');row.append(label,value);target.append(row);
    }
  }
  refreshLayers() {
    const target=$('layer-list');target.replaceChildren();if(!this.editor.definition)return;
    for(const part of this.editor.definition.parts){const button=document.createElement('button');button.className=`layer-row ${part.id===this.editor.selected?'selected':''}`;
      const name=document.createElement('span'),tag=document.createElement('small');name.textContent=part.name;tag.textContent=part.role==='accessory'?'附件':part.role==='hair'?'头发':'图层';
      button.append(name,tag);button.onclick=()=>{this.editor.selected=part.id;this.refreshEditor();this.editor.draw();};target.append(button);}
  }
  refreshEditor() {
    this.anchorValues();this.refreshLayers();const part=this.editor.part();$('layer-editor').hidden=!part;
    if(part){$('layer-name').value=part.name;$('layer-role').value=part.role;$('layer-z').value=part.z;$('layer-strength').value=part.strength;}
    $('remove-layer').disabled=!part;$('layer-hint').textContent=this.editor.selected==='subject'?'正在调整整体保留范围。画轮廓完成后，范围外的内容会被移除。':part?`正在调整“${part.name}”。每个附件都可独立保留。`:'先添加或选择一个附件层。';
    this.syncEditControls();
  }
  parameterControls() {
    const target=$('parameter-list');target.replaceChildren();
    for(const [key,[low,high,initial]]of Object.entries(this.contract.parameters)){
      const row=document.createElement('div');row.className='parameter-row';const label=document.createElement('label'),span=document.createElement('span'),output=document.createElement('output');
      const input=document.createElement('input');input.type='range';input.min=low;input.max=high;input.step=.01;input.value=initial;input.dataset.parameter=key;input.setAttribute('aria-label',labels[key]);
      span.textContent=labels[key];output.value=Number(initial).toFixed(2);label.append(span,output);row.append(label,input);target.append(row);
      input.oninput=()=>{$('idle').checked=false;this.weights[key]=Number(input.value);output.value=Number(input.value).toFixed(2);this.draw();};
    }
  }
  syncParameters(values={}) {
    document.querySelectorAll('[data-parameter]').forEach(input=>{const value=values[input.dataset.parameter]??this.contract.parameters[input.dataset.parameter][2];
      input.value=value;input.previousElementSibling.lastElementChild.value=Number(value).toFixed(2);});
  }
  follow(event) {
    if(!this.snapshot||!$('follow').checked||this.panel!=='motion')return;
    const rect=$('stage').getBoundingClientRect();this.gaze={x:Math.max(-1,Math.min(1,(event.clientX-rect.left)/rect.width*2-1)),
      y:Math.max(-1,Math.min(1,(event.clientY-rect.top)/rect.height*2-1))};
  }
  togglePlay() {this.playing=!this.playing;$('play').textContent=this.playing?'暂停动画':'播放动画';$('play').setAttribute('aria-pressed',this.playing);
    if(this.playing)this.loop.start();else this.loop.stop();}
  neutral() {$('idle').checked=false;this.weights={};this.gaze={x:0,y:0};this.syncParameters();this.draw();this.status('现在是原始姿态。勾选自然呼吸与眨眼可恢复动作。');}
  parameters(time=performance.now()) {
    if($('idle').checked)return idleParameters(time/1000,$('follow').checked?this.gaze:{x:0,y:0},this.audio.mouth(),this.contract);
    return {...this.weights,...(!this.audio.element.paused?{mouth:this.audio.mouth()}:{})};
  }
  frame(time) {this.renderer.draw(this.parameters(time));if($('mesh').checked)this.drawOverlay();}
  drawOverlay() {
    const meshes=$('mesh').checked&&this.renderer.asset?this.renderer.asset.layers.map(layer=>({...layer,vertices:blendVertices(layer,this.renderer.lastParameters,this.contract)})):null;
    this.editor.draw(meshes);
  }
  draw() {this.renderer.draw(this.parameters());this.drawOverlay();}
  debug() {return {id:this.snapshot?.id,revision:this.snapshot?.revision,ready:this.renderer.ready,playing:this.playing,
    frames:this.renderer.frames,parameters:{...this.renderer.lastParameters},vertices:structuredClone(this.renderer.lastVertices),
    layers:this.renderer.asset?.layers.map(layer=>({id:layer.id,role:layer.role,vertices:layer.vertices.length/2})),panel:this.panel,
    edits:{busy:!!this.isBusy,dirty:!!this.dirty,comparing:!!this.comparing,undo:this.editor.history.canUndo,redo:this.editor.history.canRedo,
      historyBytes:this.editor.history.usedBytes,anchors:structuredClone(this.editor.definition?.anchors)}};}
  dispose() {this.requests.dispose();this.abort?.abort();this.loop.dispose();this.renderer.dispose();this.editor.dispose();
    this.audio.dispose().catch(error=>console.error('音频资源释放失败',error));this.observer.disconnect();document.removeEventListener('keydown',this.historyKey);}
}

try {
  const contract=await request('/api/contract');const studio=new Studio(contract);
  let previous=null;try{const raw=localStorage.getItem(storageKey);if(raw){try{previous=JSON.parse(raw);}catch{previous={id:raw,origin:'open'};}}}catch{/* Recent project is optional. */}
  if(previous?.id)await studio.operation(signal=>request(`/api/projects/${encodeURIComponent(previous.id)}`,undefined,signal),previous.origin==='import'?'import':'open');
} catch(error) {$('status').textContent=`工坊暂时没有连上：${error.message}`;$('status').classList.add('error');}
