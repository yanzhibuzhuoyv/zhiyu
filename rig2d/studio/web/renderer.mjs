import {blendVertices, validateAsset, fitViewport, LoadEpoch} from './runtime.mjs';

const vertexSource = `attribute vec2 a_position;attribute vec2 a_uv;
uniform vec2 u_viewport;uniform vec2 u_offset;uniform float u_scale;
varying vec2 v_uv;void main(){vec2 p=(a_position*u_scale+u_offset)/u_viewport;
gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.0,1.0);v_uv=a_uv;}`;
const fragmentSource = `precision mediump float;varying vec2 v_uv;
uniform sampler2D u_texture;void main(){gl_FragColor=texture2D(u_texture,v_uv);}`;

function shader(gl, type, source) {
  const result=gl.createShader(type);
  if (!result) throw Error('无法创建角色渲染器');
  gl.shaderSource(result,source);gl.compileShader(result);
  if (!gl.getShaderParameter(result,gl.COMPILE_STATUS)) {
    gl.deleteShader(result);throw Error('此浏览器暂不能编译角色着色器');
  }
  return result;
}

function program(gl) {
  let vertex=null, fragment=null, result=null;
  try {
    vertex=shader(gl,gl.VERTEX_SHADER,vertexSource);
    fragment=shader(gl,gl.FRAGMENT_SHADER,fragmentSource);
    result=gl.createProgram();
    if (!result) throw Error('无法创建角色渲染程序');
    gl.attachShader(result,vertex);gl.attachShader(result,fragment);gl.linkProgram(result);
    if (!gl.getProgramParameter(result,gl.LINK_STATUS)) throw Error('角色渲染程序初始化失败');
    return result;
  } catch (error) { if (result) gl.deleteProgram(result);throw error; }
  finally { if(vertex)gl.deleteShader(vertex);if(fragment)gl.deleteShader(fragment); }
}

function image(url, signal) {
  return new Promise((resolve,reject)=>{
    const result=new Image();
    const clear=()=>{result.onload=null;result.onerror=null;signal.removeEventListener('abort',abort);};
    const abort=()=>{clear();result.src='';reject(new DOMException('角色加载已取消','AbortError'));};
    result.onload=()=>{clear();resolve(result);};
    result.onerror=()=>{clear();reject(Error('角色纹理没有成功加载，请重新保存角色'));};
    signal.addEventListener('abort',abort,{once:true});
    if (signal.aborted) {abort();return;}
    result.src=url;
  });
}

function textureUrl(snapshot, layer) {
  const expected=`/api/projects/${encodeURIComponent(snapshot.id)}/files/${layer.texture}`;
  const parsed=new URL(layer.url || layer.textureUrl || expected,location.href);
  if (parsed.origin!==location.origin || parsed.pathname!==expected || parsed.search || parsed.hash) throw Error('角色纹理地址无效');
  return parsed.href;
}

export class MeshRenderer {
  constructor(canvas, fallback, contract, changed=()=>{}) {
    this.canvas=canvas;this.fallback=fallback;this.contract=contract;this.changed=changed;
    this.loads=new LoadEpoch();this.resources=[];this.ready=false;this.disposed=false;
    this.frames=0;this.lastParameters={};this.lastVertices=[];
    this.lost=event=>{event.preventDefault();this.ready=false;this.loads.begin();this.abort?.abort();
      // The lost context already destroyed these GPU objects. Deleting them after
      // restore contaminates the new generation with INVALID_OPERATION errors.
      this.resources=[];this.program=null;this.locations=null;
      this.fallback.hidden=false;this.canvas.hidden=true;this.changed('loss','预览暂时中断，角色原图已保留。恢复后会继续。');};
    this.restored=()=>{if(this.snapshot&&!this.disposed)this.mount(this.snapshot).catch(error=>this.changed('error',error.message));};
    canvas.addEventListener('webglcontextlost',this.lost);
    canvas.addEventListener('webglcontextrestored',this.restored);
  }
  initialize() {
    const gl=this.canvas.getContext('webgl',{alpha:true,antialias:false,premultipliedAlpha:true,preserveDrawingBuffer:true});
    if (!gl || gl.isContextLost()) throw Error('当前浏览器暂不支持动态网格，已为你保留角色原图');
    this.gl=gl;this.program=program(gl);
    this.locations={position:gl.getAttribLocation(this.program,'a_position'),uv:gl.getAttribLocation(this.program,'a_uv'),
      viewport:gl.getUniformLocation(this.program,'u_viewport'),offset:gl.getUniformLocation(this.program,'u_offset'),
      scale:gl.getUniformLocation(this.program,'u_scale'),texture:gl.getUniformLocation(this.program,'u_texture')};
    gl.disable(gl.DEPTH_TEST);gl.enable(gl.BLEND);
    gl.blendFuncSeparate(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);
  }
  release() {
    if (!this.gl) return;
    for (const resource of this.resources) {
      this.gl.deleteTexture(resource.texture);
      for (const buffer of [resource.position,resource.uv,resource.indices])this.gl.deleteBuffer(buffer);
    }
    this.resources=[];
    if (this.program)this.gl.deleteProgram(this.program);
    this.program=null;
  }
  createResource(layer, image) {
    const gl=this.gl, allocated=[];
    let texture=null;
    try {
      texture=gl.createTexture();if(!texture)throw Error('角色纹理显存不足');
      gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,image);
      const buffer=(target,data,usage)=>{const value=gl.createBuffer();if(!value)throw Error('角色网格显存不足');allocated.push(value);gl.bindBuffer(target,value);gl.bufferData(target,data,usage);return value;};
      const position=buffer(gl.ARRAY_BUFFER,new Float32Array(layer.vertices),gl.DYNAMIC_DRAW);
      const uv=buffer(gl.ARRAY_BUFFER,new Float32Array(layer.uv),gl.STATIC_DRAW);
      const indices=buffer(gl.ELEMENT_ARRAY_BUFFER,new Uint16Array(layer.indices),gl.STATIC_DRAW);
      if(gl.getError()!==gl.NO_ERROR)throw Error('角色纹理或网格未能上传，请尝试较小的图片');
      return {layer,texture,position,uv,indices};
    } catch (error) {if(texture)gl.deleteTexture(texture);for(const buffer of allocated)gl.deleteBuffer(buffer);throw error;}
  }
  async mount(snapshot) {
    if (this.disposed)throw Error('角色预览已关闭');
    const epoch=this.loads.begin();this.abort?.abort();this.abort=new AbortController();
    const signal=this.abort.signal;this.snapshot=snapshot;this.asset=null;this.ready=false;
    this.fallback.src=snapshot.sourceUrl || `/api/projects/${encodeURIComponent(snapshot.id)}/files/source.png`;
    this.fallback.hidden=false;this.canvas.hidden=true;this.release();
    try {
      const asset=validateAsset(snapshot.asset,this.contract);this.asset=asset;
      this.initialize();
      const ordered=[...asset.layers].sort((a,b)=>a.z-b.z);
      for (const layer of ordered) {
        const loaded=await image(textureUrl(snapshot,layer),signal);
        if (!this.loads.current(epoch))return false;
        this.resources.push(this.createResource(layer,loaded));
      }
      if (!this.loads.current(epoch))return false;
      this.ready=true;this.canvas.hidden=false;this.fallback.hidden=true;this.changed('ready','动态网格已准备好');
      this.draw({});return true;
    } catch (error) {
      if(!this.loads.current(epoch))return false;
      this.release();this.fallback.hidden=false;this.canvas.hidden=true;
      if(error.name==='AbortError')return false;
      this.changed('error',error.message);return false;
    }
  }
  draw(parameters) {
    if (!this.ready || this.disposed || this.gl.isContextLost())return;
    let prepared;
    try {prepared=this.resources.map(resource=>({resource,vertices:blendVertices(resource.layer,parameters,this.contract)}));}
    catch {this.ready=false;this.fallback.hidden=false;this.canvas.hidden=true;
      this.changed('error','这个动作暂时不能预览，已保留角色原图。重新保存角色后可以再试。');return;}
    const gl=this.gl, rect=this.canvas.getBoundingClientRect();
    if(!rect.width||!rect.height)return;
    const dpr=Math.min(window.devicePixelRatio||1,2);
    const width=Math.max(1,Math.round(rect.width*dpr)),height=Math.max(1,Math.round(rect.height*dpr));
    if(this.canvas.width!==width)this.canvas.width=width;if(this.canvas.height!==height)this.canvas.height=height;
    gl.viewport(0,0,this.canvas.width,this.canvas.height);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.useProgram(this.program);
    const fit=fitViewport(this.asset.width,this.asset.height,this.canvas.width,this.canvas.height);
    gl.uniform2f(this.locations.viewport,this.canvas.width,this.canvas.height);gl.uniform2f(this.locations.offset,fit.x,fit.y);
    gl.uniform1f(this.locations.scale,fit.scale);gl.uniform1i(this.locations.texture,0);gl.activeTexture(gl.TEXTURE0);
    this.lastVertices=[];
    for (const {resource,vertices} of prepared) {
      this.lastVertices.push({id:resource.layer.id,vertices:[...vertices]});
      gl.bindBuffer(gl.ARRAY_BUFFER,resource.position);gl.bufferSubData(gl.ARRAY_BUFFER,0,vertices);
      gl.enableVertexAttribArray(this.locations.position);gl.vertexAttribPointer(this.locations.position,2,gl.FLOAT,false,0,0);
      gl.bindBuffer(gl.ARRAY_BUFFER,resource.uv);gl.enableVertexAttribArray(this.locations.uv);gl.vertexAttribPointer(this.locations.uv,2,gl.FLOAT,false,0,0);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,resource.indices);gl.bindTexture(gl.TEXTURE_2D,resource.texture);
      gl.drawElements(gl.TRIANGLES,resource.layer.indices.length,gl.UNSIGNED_SHORT,0);
    }
    this.frames++;this.lastParameters={...parameters};
  }
  dispose() {
    if(this.disposed)return;
    this.disposed=true;this.loads.dispose();this.abort?.abort();this.release();
    this.canvas.removeEventListener('webglcontextlost',this.lost);this.canvas.removeEventListener('webglcontextrestored',this.restored);
  }
}
