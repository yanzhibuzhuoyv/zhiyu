function bytes(value) {
  if(value==null)return 0;
  if(value instanceof Uint8Array)return value.byteLength;
  if(typeof value==='string')return value.length*2;
  if(typeof value==='number'||typeof value==='boolean')return 8;
  if(Array.isArray(value))return value.reduce((sum,item)=>sum+bytes(item),0);
  if(typeof value==='object')return Object.entries(value).reduce((sum,[key,item])=>sum+key.length*2+bytes(item),0);
  throw Error('撤销数据无效');
}
function same(a,b) {
  if(a===b)return true;
  if(!a||!b||typeof a!=='object'||typeof b!=='object')return false;
  if(a instanceof Uint8Array||b instanceof Uint8Array)return a instanceof Uint8Array&&b instanceof Uint8Array&&a.length===b.length&&a.every((v,i)=>v===b[i]);
  const keys=Object.keys(a);return keys.length===Object.keys(b).length&&keys.every(key=>Object.hasOwn(b,key)&&same(a[key],b[key]));
}

/** Retains only affected target payloads. Source photos and whole editor masks never enter history. */
export class EditHistory {
  constructor({maxSteps=40,maxBytes=32*1024*1024}={}) {
    if(!Number.isInteger(maxSteps)||maxSteps<1||maxSteps>200||!Number.isInteger(maxBytes)||maxBytes<1||maxBytes>128*1024*1024)throw Error('撤销预算无效');
    this.maxSteps=maxSteps;this.maxBytes=maxBytes;this.clear();
  }
  get canUndo(){return this.index>0;}
  get canRedo(){return this.index<this.entries.length;}
  get steps(){return this.entries.length;}
  breakGroup(){this.group=null;}
  clear(){this.entries=[];this.index=0;this.usedBytes=0;this.group=null;}
  record({before,after,apply,label='',group=null}) {
    if(typeof apply!=='function')throw Error('撤销动作无效');
    if(same(before,after))return false;
    const merge=group!==null&&group===this.group&&this.index===this.entries.length&&this.index>0;
    const first=merge?this.entries[this.index-1].before:before;
    const size=bytes(first)+bytes(after);
    if(size>this.maxBytes)throw Error('这次修改超出撤销预算，请缩小编辑范围');
    const entry={before:structuredClone(first),after:structuredClone(after),apply,label,group,bytes:size};
    this.entries=this.entries.slice(0,this.index);
    if(merge)this.entries.pop();
    this.entries.push(entry);this.usedBytes=this.entries.reduce((sum,e)=>sum+e.bytes,0);
    while(this.entries.length>this.maxSteps||this.usedBytes>this.maxBytes)this.usedBytes-=this.entries.shift().bytes;
    this.index=this.entries.length;this.group=group;return true;
  }
  undo() {
    if(!this.canUndo)return false;
    const entry=this.entries[this.index-1];entry.apply(structuredClone(entry.before));this.index--;this.breakGroup();return true;
  }
  redo() {
    if(!this.canRedo)return false;
    const entry=this.entries[this.index];entry.apply(structuredClone(entry.after));this.index++;this.breakGroup();return true;
  }
}
