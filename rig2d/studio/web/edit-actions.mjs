function canvas(width,height){const c=document.createElement('canvas');c.width=width;c.height=height;return c;}
export function alpha(c) {
  if(!c)return null;
  const rgba=c.getContext('2d').getImageData(0,0,c.width,c.height).data,result=new Uint8Array(c.width*c.height);
  for(let i=0;i<result.length;i++)result[i]=rgba[i*4+3];return result;
}
function restoreAlpha(editor,values) {
  const c=canvas(editor.width,editor.height),ctx=c.getContext('2d'),pixels=ctx.createImageData(c.width,c.height);
  for(let i=0;i<values.length;i++){pixels.data[i*4]=255;pixels.data[i*4+1]=255;pixels.data[i*4+2]=255;pixels.data[i*4+3]=values[i];}
  ctx.putImageData(pixels,0,0);return c;
}
export function maskState(editor,id) {
  const c=id==='subject'?editor.subject:editor.masks.get(id);
  return {pixels:alpha(c),points:id==='subject'?structuredClone(editor.subjectPolygon):null};
}
export function applyMask(editor,id,state) {
  const c=state.pixels===null?null:restoreAlpha(editor,state.pixels);
  if(id==='subject'){editor.subject=c;editor.subjectPolygon=structuredClone(state.points);}
  else if(c)editor.masks.set(id,c);else editor.masks.delete(id);
}
export function partState(editor,id) {
  const index=editor.definition.parts.findIndex(p=>p.id===id);
  return {index,part:index<0?null:structuredClone(editor.definition.parts[index]),
    mask:maskState(editor,id),selected:editor.selected};
}
export function applyPart(editor,id,state) {
  editor.definition.parts=editor.definition.parts.filter(p=>p.id!==id);
  if(state.part)editor.definition.parts.splice(state.index,0,structuredClone(state.part));
  applyMask(editor,id,state.mask);editor.selected=state.selected;
}
