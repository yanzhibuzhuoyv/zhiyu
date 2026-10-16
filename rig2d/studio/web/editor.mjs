import {fitViewport, sourcePoint, LoadEpoch} from './runtime.mjs';
import {binaryMaskRgba} from './mask.mjs';
import {EditHistory} from './history.mjs';
import {maskState,applyMask,partState,applyPart} from './edit-actions.mjs';

export const anchorNames={head:'头部中心',neck:'颈部支点',eyeLeft:'左眼',eyeRight:'右眼',mouth:'嘴巴'};
const colors={head:'#8ca884',neck:'#a69883',eyeLeft:'#ba7773',eyeRight:'#ba7773',mouth:'#b99070'};
const rawBytes=base64=>Uint8Array.from(atob(base64),c=>c.charCodeAt(0));
function base64(bytes) {
  let result='';
  for(let start=0;start<bytes.length;start+=32768)result+=String.fromCharCode(...bytes.subarray(start,start+32768));
  return btoa(result);
}
function loadImage(url) {
  return new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve(image);
    image.onerror=()=>reject(Error('标注图片未能加载，请重新打开角色'));image.src=url;});
}
function maskCanvas(width,height) {const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;return canvas;}
function maskPng(canvas) {
  const result=maskCanvas(canvas.width,canvas.height),ctx=result.getContext('2d');
  const pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height);
  pixels.data.set(binaryMaskRgba(pixels.data));ctx.putImageData(pixels,0,0);
  return result.toDataURL('image/png').split(',')[1];
}

export class CharacterEditor {
  constructor(canvas, changed=()=>{}, reportError=error=>console.error(error)) {
    this.canvas=canvas;this.changed=changed;this.context=canvas.getContext('2d');this.loads=new LoadEpoch();
    this.mode='anchors';this.anchor='eyeLeft';this.tool='polygon';this.selected=null;this.brush=24;this.masks=new Map();
    this.enabled=true;this.history=new EditHistory();
    const handle=action=>event=>{try{action(event);}catch(error){if(this.drag)this.pointerCancel(event);reportError(error);}};
    this.down=handle(event=>this.pointerDown(event));this.move=handle(event=>this.pointerMove(event));this.up=handle(event=>this.pointerUp(event));
    this.cancel=handle(event=>this.pointerCancel(event));
    canvas.addEventListener('pointerdown',this.down);canvas.addEventListener('pointermove',this.move);
    canvas.addEventListener('pointerup',this.up);canvas.addEventListener('pointercancel',this.cancel);
  }
  async set(snapshot) {
    const epoch=this.loads.begin(),definition=structuredClone(snapshot.definition);
    definition.parts=definition.parts.map(part=>({...part,polygon:part.polygon||[]}));
    const source=await loadImage(snapshot.sourceUrl||`/api/projects/${encodeURIComponent(snapshot.id)}/files/source.png`);
    if(!this.loads.current(epoch))return false;
    const masks=new Map();
    for(const part of definition.parts) {
      if(!part.mask)continue;
      const canvas=maskCanvas(snapshot.width,snapshot.height),image=await loadImage(`data:image/png;base64,${part.mask}`);
      if(!this.loads.current(epoch))return false;
      const ctx=canvas.getContext('2d');ctx.drawImage(image,0,0);const pixels=ctx.getImageData(0,0,snapshot.width,snapshot.height);
      for(let i=0;i<pixels.data.length;i+=4){const keep=pixels.data[i]>0&&pixels.data[i+3]>0;
        pixels.data[i]=255;pixels.data[i+1]=255;pixels.data[i+2]=255;pixels.data[i+3]=keep?255:0;}
      ctx.putImageData(pixels,0,0);masks.set(part.id,canvas);
    }
    this.snapshot=snapshot;this.definition=definition;this.suggested=structuredClone(definition.anchors);
    this.width=snapshot.width;this.height=snapshot.height;this.source=source;this.masks=masks;this.subject=null;
    this.subjectPolygon=[];this.selected=definition.parts[0]?.id||null;this.drag=null;
    this.history.clear();
    this.draw();return true;
  }
  acceptSaved(snapshot){this.snapshot=snapshot;}
  record(before,after,apply,group=null) {
    try{return this.history.record({before,after,apply,group});}
    catch(error){apply(before);this.changed(false);this.draw();throw error;}
  }
  undo(){if(!this.enabled||this.drag)return false;const ok=this.history.undo();if(ok){this.changed();this.draw();}return ok;}
  redo(){if(!this.enabled||this.drag)return false;const ok=this.history.redo();if(ok){this.changed();this.draw();}return ok;}
  resetAnchors(){const before=structuredClone(this.definition.anchors);this.definition.anchors=structuredClone(this.suggested);
    const modified=this.record(before,this.definition.anchors,value=>{this.definition.anchors=value;});this.changed(modified);this.draw();}
  updatePartProperties(values,group=null) {
    const id=this.selected,part=this.part();if(!part)return;
    const keys=['name','role','z','strength'],before=Object.fromEntries(keys.map(k=>[k,part[k]]));
    for(const key of keys)if(Object.hasOwn(values,key))part[key]=values[key];
    const after=Object.fromEntries(keys.map(k=>[k,part[k]]));
    const modified=this.record(before,after,state=>{const target=this.definition.parts.find(p=>p.id===id);Object.assign(target,state);},group);
    this.changed(modified);
  }
  get fit() {const rect=this.canvas.getBoundingClientRect();return fitViewport(this.width,this.height,rect.width,rect.height);}
  point(event) {const rect=this.canvas.getBoundingClientRect(),point=sourcePoint(event.clientX-rect.left,event.clientY-rect.top,this.fit,this.width,this.height);
    return this.mode==='anchors'&&!['head','neck'].includes(this.anchor)?point:[Math.min(point[0],this.width-1),Math.min(point[1],this.height-1)];}
  part() {return this.definition?.parts.find(part=>part.id===this.selected);}
  setMode(mode) {if(this.drag)this.pointerCancel({pointerId:this.drag.pointer});this.history.breakGroup();
    this.mode=mode;this.canvas.classList.toggle('viewing',mode==='motion');this.draw();}
  getMask() {
    if(this.selected==='subject') {
      if(this.subject)return this.subject;
      const canvas=maskCanvas(this.width,this.height),ctx=canvas.getContext('2d');
      if(this.definition.subjectMask) {
        const mask=rawBytes(this.definition.subjectMask),pixels=ctx.createImageData(this.width,this.height);
        for(let n=0;n<mask.length;n++){pixels.data[n*4]=255;pixels.data[n*4+1]=255;pixels.data[n*4+2]=255;pixels.data[n*4+3]=mask[n]?255:0;}
        ctx.putImageData(pixels,0,0);
      } else {ctx.drawImage(this.source,0,0);ctx.globalCompositeOperation='source-in';ctx.fillStyle='white';ctx.fillRect(0,0,this.width,this.height);}
      this.subject=canvas;return canvas;
    }
    const part=this.part();if(!part)return null;
    if(!this.masks.has(part.id)) {
      const canvas=maskCanvas(this.width,this.height),ctx=canvas.getContext('2d');
      if(part.polygon.length>=3){ctx.fillStyle='white';ctx.beginPath();part.polygon.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));ctx.closePath();ctx.fill();}
      this.masks.set(part.id,canvas);
    }
    return this.masks.get(part.id);
  }
  stroke(start,end) {
    const mask=this.getMask();if(!mask)return;
    const ctx=mask.getContext('2d');ctx.globalCompositeOperation=this.tool==='erase'?'destination-out':'source-over';
    ctx.strokeStyle='white';ctx.fillStyle='white';ctx.lineWidth=this.brush;ctx.lineCap='round';ctx.lineJoin='round';
    ctx.beginPath();ctx.moveTo(...start);ctx.lineTo(...end);ctx.stroke();
    ctx.beginPath();ctx.arc(end[0],end[1],this.brush/2,0,Math.PI*2);ctx.fill();
  }
  pointerDown(event) {
    if(!this.enabled||!this.definition||this.mode==='motion'||this.drag)return;
    if(this.mode==='layers'&&this.selected!=='subject'&&!this.part())return;
    event.preventDefault();this.canvas.setPointerCapture(event.pointerId);const point=this.point(event);
    if(this.mode==='anchors') {
      this.drag={start:point,last:point,pointer:event.pointerId,key:this.anchor,previous:[...this.definition.anchors[this.anchor]]};
      this.definition.anchors[this.anchor]=['head','neck'].includes(this.anchor)?point:[...point,...point];
    } else if(this.tool==='polygon') {
      const target=this.selected==='subject'?this.subjectPolygon:this.part()?.polygon;
      if(!target)return;const before=structuredClone(target),id=this.selected;target.push(point);
      this.drag={polygon:true,pointer:event.pointerId,id,before};
    } else {this.drag={start:point,last:point,pointer:event.pointerId,id:this.selected,before:maskState(this,this.selected)};this.stroke(point,point);}
    this.changed(false);this.draw();
  }
  pointerMove(event) {
    if(!this.enabled||!this.drag||this.drag.pointer!==event.pointerId)return;
    if(this.drag.polygon)return;
    const point=this.point(event);
    if(this.mode==='anchors') {
      const start=this.drag.start;
      this.definition.anchors[this.drag.key]=['head','neck'].includes(this.drag.key)?point:
        [Math.min(start[0],point[0]),Math.min(start[1],point[1]),Math.max(start[0],point[0]),Math.max(start[1],point[1])];
    } else this.stroke(this.drag.last,point);
    this.drag.last=point;this.changed(false);this.draw();
  }
  pointerUp(event) {
    if(this.drag?.pointer!==event.pointerId)return;
    let modified=false;
    try {
      const gesture=this.drag,id=gesture.id,key=gesture.key;
      if(gesture.polygon) {
        const target=id==='subject'?this.subjectPolygon:this.definition.parts.find(p=>p.id===id).polygon;
        modified=this.record(gesture.before,target,value=>{if(id==='subject')this.subjectPolygon=value;else this.definition.parts.find(p=>p.id===id).polygon=value;});
      } else if(gesture.key) {
        const region=this.definition.anchors[gesture.key];
        if(region.length===4&&(region[2]-region[0]<1||region[3]-region[1]<1)) {
          this.definition.anchors[gesture.key]=gesture.previous;
        }
        modified=this.record(gesture.previous,this.definition.anchors[key],value=>{this.definition.anchors[key]=value;});
      } else {
        modified=this.record(gesture.before,maskState(this,id),value=>applyMask(this,id,value));
      }
    } finally {
      this.drag=null;this.changed(modified);this.draw();
      if(this.canvas.hasPointerCapture(event.pointerId))this.canvas.releasePointerCapture(event.pointerId);
    }
  }
  pointerCancel(event) {
    if(this.drag?.pointer!==event.pointerId)return;
    if(this.drag.polygon){if(this.drag.id==='subject')this.subjectPolygon=this.drag.before;else this.definition.parts.find(p=>p.id===this.drag.id).polygon=this.drag.before;}
    else if(this.drag.key)this.definition.anchors[this.drag.key]=this.drag.previous;
    else applyMask(this,this.drag.id,this.drag.before);
    this.drag=null;this.changed(false);this.draw();
    if(this.canvas.hasPointerCapture(event.pointerId))this.canvas.releasePointerCapture(event.pointerId);
  }
  finishPolygon() {
    const part=this.part(),polygon=this.selected==='subject'?this.subjectPolygon:part?.polygon;
    if(!polygon||polygon.length<3)throw Error('请至少点选三个点，围住想保留的地方');
    const id=this.selected,before=id==='subject'?maskState(this,id):partState(this,id);
    if(this.selected==='subject') {
      this.subject=maskCanvas(this.width,this.height);const ctx=this.subject.getContext('2d');ctx.fillStyle='white';ctx.beginPath();
      polygon.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));ctx.closePath();ctx.fill();this.subjectPolygon=[];
    } else {this.masks.delete(part.id);part.mask=undefined;
      part.pivot=[polygon.reduce((sum,p)=>sum+p[0],0)/polygon.length,polygon.reduce((sum,p)=>sum+p[1],0)/polygon.length];}
    const after=id==='subject'?maskState(this,id):partState(this,id);
    this.record(before,after,value=>id==='subject'?applyMask(this,id,value):applyPart(this,id,value));this.changed();this.draw();
  }
  addLayer() {
    if(!this.definition)throw Error('请先导入角色图片');
    if(this.definition.parts.length>=60)throw Error('附件层数量已到上限，请先整理现有图层');
    const id=`part_${crypto.randomUUID().replaceAll('-','').slice(0,16)}`;
    const before=partState(this,id);
    this.definition.parts.push({id,name:`附件 ${this.definition.parts.length+1}`,role:'accessory',z:20+this.definition.parts.length,
      polygon:[],pivot:[this.width/2,this.height/2],strength:.5});
    this.selected=id;this.record(before,partState(this,id),value=>applyPart(this,id,value));this.changed();this.draw();return id;
  }
  removeLayer() {
    const part=this.part();if(!part)return;
    const id=part.id,before=partState(this,id);
    this.definition.parts=this.definition.parts.filter(value=>value.id!==part.id);this.masks.delete(part.id);
    this.selected=this.definition.parts[0]?.id||null;
    this.record(before,partState(this,id),value=>applyPart(this,id,value));this.changed();this.draw();
  }
  undoPoint() {
    const polygon=this.selected==='subject'?this.subjectPolygon:this.part()?.polygon;
    if(polygon?.length){const id=this.selected,before=structuredClone(polygon);polygon.pop();
      this.record(before,polygon,value=>{if(id==='subject')this.subjectPolygon=value;else this.definition.parts.find(p=>p.id===id).polygon=value;});this.changed();this.draw();}
  }
  definitionForSave() {
    if(this.subjectPolygon?.length)throw Error('整体保留范围还没完成，请先点“完成轮廓”，或撤回这些点再保存');
    const result=structuredClone(this.definition);
    for(const part of result.parts) {
      if(this.masks.has(part.id))part.mask=maskPng(this.masks.get(part.id));
      if(!part.mask&&part.polygon.length<3)throw Error(`“${part.name}”尚未画好轮廓，请点选至少三个点或用画笔保留`);
    }
    if(this.subject) {
      const pixels=this.subject.getContext('2d').getImageData(0,0,this.width,this.height).data;
      const bytes=new Uint8Array(this.width*this.height);for(let n=0;n<bytes.length;n++)bytes[n]=pixels[n*4+3]?1:0;
      result.subjectMask=base64(bytes);
    }
    return result;
  }
  draw(meshes=null) {
    const rect=this.canvas.getBoundingClientRect(),dpr=Math.min(window.devicePixelRatio||1,2);
    this.canvas.width=Math.max(1,Math.round(rect.width*dpr));this.canvas.height=Math.max(1,Math.round(rect.height*dpr));
    const ctx=this.context;ctx.clearRect(0,0,this.canvas.width,this.canvas.height);
    if(!this.definition||!rect.width||!rect.height)return;
    const fit=this.fit;ctx.setTransform(dpr*fit.scale,0,0,dpr*fit.scale,dpr*fit.x,dpr*fit.y);ctx.lineWidth=1.4/fit.scale;
    if(meshes) {ctx.strokeStyle='#83947777';for(const layer of meshes){ctx.beginPath();for(let n=0;n<layer.indices.length;n+=3){
      for(let j=0;j<3;j++){const i=layer.indices[n+j]*2;j?ctx.lineTo(layer.vertices[i],layer.vertices[i+1]):ctx.moveTo(layer.vertices[i],layer.vertices[i+1]);}ctx.closePath();}ctx.stroke();}}
    if(this.mode==='anchors') {
      for(const [key,points]of Object.entries(this.definition.anchors)) {
        ctx.strokeStyle=colors[key];ctx.fillStyle=colors[key];ctx.lineWidth=(key===this.anchor?2:1)/fit.scale;
        if(points.length===2){ctx.beginPath();ctx.arc(points[0],points[1],4/fit.scale,0,Math.PI*2);ctx.fill();}
        else {ctx.strokeRect(points[0],points[1],points[2]-points[0],points[3]-points[1]);
          for(const [x,y]of [[points[0],points[1]],[points[2],points[3]]]){ctx.beginPath();ctx.arc(x,y,3/fit.scale,0,Math.PI*2);ctx.fill();}}
      }
    }
    if(this.mode==='layers')this.drawLayer(ctx,fit);
    ctx.setTransform(1,0,0,1,0,0);
  }
  drawLayer(ctx,fit) {
    const part=this.part(),polygon=this.selected==='subject'?this.subjectPolygon:part?.polygon;
    const mask=this.selected==='subject'?this.subject:this.masks.get(this.selected);
    if(mask){ctx.globalAlpha=.25;ctx.drawImage(mask,0,0);ctx.globalAlpha=1;}
    if(polygon?.length){ctx.strokeStyle='#ae6763';ctx.fillStyle='#c6876f22';ctx.beginPath();
      polygon.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));if(polygon.length>=3){ctx.closePath();ctx.fill();}ctx.stroke();
      ctx.fillStyle='#ad655f';for(const [x,y]of polygon){ctx.beginPath();ctx.arc(x,y,3/fit.scale,0,Math.PI*2);ctx.fill();}}
  }
  dispose() {this.loads.dispose();this.history.clear();this.masks.clear();this.subject=null;
    for(const [type,fn]of [['pointerdown',this.down],['pointermove',this.move],['pointerup',this.up],['pointercancel',this.cancel]])this.canvas.removeEventListener(type,fn);}
}
