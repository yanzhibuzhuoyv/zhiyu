import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateAsset, blendVertices, idleParameters, rmsMouth, fitViewport,
  sourcePoint, FrameLoop, LoadEpoch} from '../runtime.mjs';

const contract = JSON.parse(readFileSync(new URL('../../contract.json', import.meta.url)));
const asset = () => ({format:contract.format, width:100,height:80,
  sourceSha:'a'.repeat(64),pixelSha:'b'.repeat(64),name:'测试角色',
  parameters:contract.parameters,capabilities:{mode:'front_mesh',trueSideViews:false,cubismMoc3:false},
  layers:[{id:'base',name:'原图',role:'base',z:0,texture:'textures/base.png',
    bounds:[0,0,100,80],vertices:[0,0,100,0,0,80],uv:[0,0,1,0,0,1],
    indices:[0,1,2],deltas:{headX:[1,2,3,4,5,6],mouth:[0,0,0,0,0,-8]},phase:0,strength:1}]});

test('neutral blend preserves source vertices and never mutates input',()=>{
  const layer=asset().layers[0];
  assert.deepEqual([...blendVertices(layer,{},contract)],layer.vertices);
  assert.deepEqual(layer.vertices,[0,0,100,0,0,80]);
});
test('parameters add precomputed deltas within shared contract',()=>{
  const layer=asset().layers[0];
  assert.deepEqual([...blendVertices(layer,{headX:.5,mouth:.5},contract)],[.5,1,101.5,2,2.5,79]);
});
test('malformed and unknown parameters reject explicitly',()=>{
  for(const params of [{headX:NaN},{headX:Infinity},{surprise:1},{headX:'1'},{headX:2},{mouth:-1},JSON.parse('{"constructor":1}')])
    assert.throws(()=>blendVertices(asset().layers[0],params,contract));
});
test('deformation rejects flipped or collapsed triangles and float32 overflow',()=>{
  const layer={vertices:[0,0,10,0,0,10],indices:[0,1,2],deltas:{headX:[0,0,-20,0,0,0]}};
  assert.throws(()=>blendVertices(layer,{headX:1},contract));
  assert.throws(()=>blendVertices(layer,{headX:.5},contract));
  assert.throws(()=>blendVertices({...layer,deltas:{headX:Array(6).fill(1e308)}},{headX:1},contract));
});
test('mixer projects to float32 only once after all double-precision sums',()=>{
  const layer={vertices:[0,0,100,0,0,80],indices:[0,1,2],deltas:{headX:[.1,0,.1,0,.1,0],headY:[.2,0,.2,0,.2,0]}};
  const result=blendVertices(layer,{headX:1,headY:1},contract);
  assert.equal(result[0],Math.fround(.1+.2));
  assert.equal(result[2],Math.fround(100+.1+.2));
});
test('native parameter weights use the same float32 input as the portable mixer',()=>{
  const delta=Math.fround(.7),layer={vertices:[0,0,100,0,0,80],indices:[0,1,2],deltas:{headX:[delta,0,delta,0,delta,0]}};
  assert.equal(blendVertices(layer,{headX:.3},contract)[0],Math.fround(Math.fround(.3)*delta));
});
test('validated mesh snapshots mutable arrays and honors twelve wings',()=>{
  const input=asset();
  for(let n=1;n<=12;n++)input.layers.push({...input.layers[0],id:`wing_${n}`,role:'accessory',z:n});
  const result=validateAsset(input,contract);
  input.layers[0].vertices[0]=99;
  assert.equal(result.layers.length,13);
  assert.equal(result.layers[0].vertices[0],0);
});
test('invalid format and incompatible shared parameters reject',()=>{
  for(const input of [{...asset(),format:'moc3'},{...asset(),parameters:{headX:[-2,2,0]}}])
    assert.throws(()=>validateAsset(input,contract));
});
test('source dimensions, layers and malicious texture paths are bounded',()=>{
  for(const change of [a=>a.width=0,a=>a.height=1600,a=>a.layers=[],
    a=>a.layers=Array.from({length:65},()=>asset().layers[0]),
    a=>a.layers[0].texture='../secret.png',a=>a.layers[0].texture='https://example.com/a.png']){
    const input=asset();change(input);assert.throws(()=>validateAsset(input,contract));
  }
});
test('many individually valid textures reject before a large decoded-memory allocation',()=>{
  const input=asset();input.width=1536;input.height=1536;
  input.layers=Array.from({length:12},(_,n)=>({...asset().layers[0],id:`layer_${n}`,bounds:[0,0,1536,1536]}));
  const limited={...contract,maxTexturePixels:24_000_000,maxTextureBytes:96_000_000};
  assert.throws(()=>validateAsset(input,limited));
  input.layers=input.layers.slice(0,9);
  assert.equal(validateAsset(input,limited).layers.length,9);
});
test('nonfinite geometry, duplicate ids, bad index and deltas reject',()=>{
  for(const change of [a=>a.layers[0].vertices[0]=NaN,a=>a.layers.push(a.layers[0]),
    a=>a.layers[0].indices[2]=8,a=>a.layers[0].uv=[0,0],
    a=>a.layers[0].deltas.headX=[1],a=>a.layers[0].deltas.unknown=[0,0,0,0,0,0]]){
    const input=asset();change(input);assert.throws(()=>validateAsset(input,contract));
  }
});
test('idle animation keeps valid ranges and gaze affects head',()=>{
  const first=idleParameters(0,{x:0,y:0},0,contract);
  const next=idleParameters(2.3,{x:1,y:-1},.7,contract);
  for(const [key,[low,high]]of Object.entries(contract.parameters)){
    assert.ok(Number.isFinite(next[key])&&next[key]>=low&&next[key]<=high,key);
  }
  assert.equal(next.mouth,.7);
  assert.ok(next.headX>first.headX);
});
test('default idle keeps attachment and head motion conservative without narrowing slider ranges',()=>{
  for(let time=0;time<60;time+=.25){const value=idleParameters(time,{x:1,y:1},0,contract);
    assert.ok(Math.abs(value.sway)<=.12);assert.ok(Math.abs(value.tilt)<=.035);
    assert.ok(Math.abs(value.headX)<=.29);assert.ok(Math.abs(value.headY)<=.225);}
  assert.deepEqual(contract.parameters.sway,[-1,1,0]);assert.deepEqual(contract.parameters.headX,[-1,1,0]);
});
test('blink closes periodically but neutral time is open',()=>{
  assert.equal(idleParameters(0,{x:0,y:0},0,contract).blinkLeft,0);
  assert.ok(idleParameters(4.24,{x:0,y:0},0,contract).blinkLeft>.9);
});
test('extreme finite animation time still produces bounded finite weights',()=>{
  const values=idleParameters(Number.MAX_VALUE,{x:0,y:0},0,contract);
  for(const [key,[low,high]]of Object.entries(contract.parameters))assert.ok(Number.isFinite(values[key])&&values[key]>=low&&values[key]<=high,key);
});
test('RMS mouth ignores silence, remains bounded and rejects malformed input',()=>{
  assert.equal(rmsMouth(new Float32Array(128)),0);
  assert.equal(rmsMouth(Float32Array.from({length:128},()=>.5)),1);
  assert.ok(rmsMouth(new Float32Array([.1,-.1]))>.1);
  assert.throws(()=>rmsMouth(new Float32Array([NaN])));
});
test('viewport fit preserves aspect ratio and reverses pointer position',()=>{
  const fit=fitViewport(100,80,500,500);
  assert.deepEqual(fit,{scale:5,x:0,y:50,width:500,height:400});
  assert.deepEqual(sourcePoint(250,250,fit,100,80),[50,40]);
  assert.deepEqual(sourcePoint(-50,600,fit,100,80),[0,80]);
});
test('invalid fit rejects instead of NaN reaching shader',()=>{
  assert.throws(()=>fitViewport(0,80,500,500));
  assert.throws(()=>fitViewport(100,80,NaN,500));
});
function fakeFrames(){
  let next=0;const pending=new Map(), cancelled=[];
  return {pending,cancelled,request:fn=>{const id=++next;pending.set(id,fn);return id;},
    cancel:id=>{cancelled.push(id);pending.delete(id);},
    fire:time=>{const [id,fn]=pending.entries().next().value;pending.delete(id);fn(time);}};
}
test('frame loop schedules once and cancels pending work on stop',()=>{
  const frames=fakeFrames(),seen=[];const loop=new FrameLoop(frames,t=>seen.push(t));
  loop.start();loop.start();assert.equal(frames.pending.size,1);
  frames.fire(100);assert.deepEqual(seen,[100]);assert.equal(frames.pending.size,1);
  loop.stop();assert.equal(frames.pending.size,0);loop.stop();assert.equal(frames.cancelled.length,1);
});
test('late callbacks cannot resurrect disposed or restarted animation',()=>{
  const frames=fakeFrames(),seen=[];const loop=new FrameLoop(frames,t=>seen.push(t));
  loop.start();const old=[...frames.pending.values()][0];loop.stop();loop.start();old(100);
  assert.equal(frames.pending.size,1);assert.deepEqual(seen,[]);
  loop.dispose();assert.equal(frames.pending.size,0);assert.throws(()=>loop.start());
});
test('frame callback error stops and reports rather than leaking RAF',()=>{
  const frames=fakeFrames(),errors=[];
  const loop=new FrameLoop(frames,()=>{throw Error('render failed');},e=>errors.push(e.message));
  loop.start();frames.fire(10);assert.deepEqual(errors,['render failed']);assert.equal(frames.pending.size,0);
});
test('load epochs prevent stale image resources replacing current character',()=>{
  const loads=new LoadEpoch();const first=loads.begin(),second=loads.begin();
  assert.equal(loads.current(first),false);assert.equal(loads.current(second),true);
  loads.dispose();assert.equal(loads.current(second),false);assert.throws(()=>loads.begin());
});
