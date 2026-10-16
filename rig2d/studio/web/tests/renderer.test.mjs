import test from 'node:test';
import assert from 'node:assert/strict';
import {MeshRenderer} from '../renderer.mjs';
import {readFileSync} from 'node:fs';

function harness() {
  const calls=[];
  const gl={VERTEX_SHADER:1,FRAGMENT_SHADER:2,COMPILE_STATUS:3,LINK_STATUS:4,
    DEPTH_TEST:5,BLEND:6,SRC_ALPHA:7,ONE_MINUS_SRC_ALPHA:8,ONE:9,
    UNPACK_FLIP_Y_WEBGL:10,UNPACK_PREMULTIPLY_ALPHA_WEBGL:11,
    isContextLost:()=>false,createShader:()=>({}),shaderSource:()=>{},compileShader:()=>{},
    getShaderParameter:()=>true,deleteShader:()=>{},createProgram:()=>({}),attachShader:()=>{},
    linkProgram:()=>{},getProgramParameter:()=>true,getAttribLocation:()=>1,getUniformLocation:()=>({}),
    disable:()=>{},enable:()=>{},pixelStorei:()=>{},
    viewport:()=>{},clearColor:()=>{},clear:()=>{},useProgram:()=>{},uniform2f:()=>{},
    uniform1f:()=>{},uniform1i:()=>{},activeTexture:()=>{},
    blendFunc:(...args)=>calls.push(['blendFunc',...args]),
    blendFuncSeparate:(...args)=>calls.push(['blendFuncSeparate',...args]),deleteProgram:()=>{}};
  let options;
  const listeners=new Map();
  const canvas={getContext:(_name,input)=>{options=input;return gl;},
    getBoundingClientRect:()=>({width:100,height:80}),
    addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:name=>listeners.delete(name)};
  const renderer=new MeshRenderer(canvas,{},{});
  return {renderer,gl,calls,listeners,options:()=>options};
}
test('unsafe frame keeps the original image instead of clearing into a partial character',()=>{
  const state=harness(),messages=[];globalThis.window={devicePixelRatio:1};state.renderer.initialize();
  state.renderer.asset={width:10,height:10};state.renderer.ready=true;
  state.renderer.resources=[{layer:{vertices:[0,0,10,0,0,10],indices:[0,1,2],deltas:{headX:[0,0,-20,0,0,0]}}}];
  state.renderer.contract={parameters:{headX:[-1,1,0]}};
  state.renderer.changed=(status,message)=>messages.push({status,message});
  assert.doesNotThrow(()=>state.renderer.draw({headX:1}));
  assert.equal(state.renderer.ready,false);assert.equal(state.renderer.fallback.hidden,false);
  assert.equal(state.renderer.canvas.hidden,true);assert.equal(messages[0].status,'error');
});
test('framebuffer preserves partial alpha and declares premultiplied composition',()=>{
  const state=harness();state.renderer.initialize();
  assert.equal(state.options().premultipliedAlpha,true);
  assert.deepEqual(state.calls,[['blendFuncSeparate',7,8,9,8]]);
  // Source alpha=1/255 survives ONE source-alpha weighting; color is multiplied once.
  const sourceAlpha=1/255,destinationAlpha=0;
  assert.equal(sourceAlpha+destinationAlpha*(1-sourceAlpha),sourceAlpha);
});
test('disposal removes context listeners and releases exactly once',()=>{
  const state=harness();state.renderer.initialize();
  let deletes=0;state.gl.deleteProgram=()=>deletes++;
  state.renderer.dispose();state.renderer.dispose();
  assert.equal(state.listeners.size,0);assert.equal(deletes,1);
});
test('lost context discards destroyed handles instead of deleting them in the restored generation',()=>{
  const state=harness();state.renderer.initialize();let deleted=0;
  state.gl.deleteTexture=()=>deleted++;state.gl.deleteBuffer=()=>deleted++;state.gl.deleteProgram=()=>deleted++;
  state.renderer.resources=[{texture:{},position:{},uv:{},indices:{}}];
  state.listeners.get('webglcontextlost')({preventDefault(){}});state.renderer.release();
  assert.equal(deleted,0);assert.deepEqual(state.renderer.resources,[]);assert.equal(state.renderer.program,null);
});
test('invalid asset budget retains its source preview without starting texture allocation',async()=>{
  const state=harness(),contract=JSON.parse(readFileSync(new URL('../../contract.json',import.meta.url)));
  state.renderer.contract={...contract,maxTexturePixels:24_000_000,maxTextureBytes:96_000_000};
  let allocations=0;state.renderer.initialize=()=>allocations++;
  const snapshot={id:'a'.repeat(32),sourceUrl:'/api/projects/'+ 'a'.repeat(32)+'/files/source.png',
    asset:{format:contract.format,width:1536,height:1536,sourceSha:'a'.repeat(64),pixelSha:'b'.repeat(64),parameters:contract.parameters,
      layers:Array.from({length:12},(_,n)=>({id:`layer_${n}`,role:'base',z:0,texture:'textures/base.png',bounds:[0,0,1536,1536],
        vertices:[0,0,1536,0,0,1536],uv:[0,0,1,0,0,1],indices:[0,1,2],deltas:{},phase:0,strength:1}))}};
  assert.equal(await state.renderer.mount(snapshot),false);assert.equal(allocations,0);
  assert.equal(state.renderer.fallback.src,snapshot.sourceUrl);assert.equal(state.renderer.fallback.hidden,false);
  assert.equal(state.renderer.canvas.hidden,true);
});
