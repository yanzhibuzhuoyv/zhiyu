const ID = /^[A-Za-z0-9_-]{1,80}$/;
const HASH = /^[a-f0-9]{64}$/;
const finite = value => typeof value === 'number' && Number.isFinite(value);
const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
const fail = message => { throw new Error(message); };
function triangleArea(vertices, indices, offset) {
  const a=indices[offset]*2,b=indices[offset+1]*2,c=indices[offset+2]*2;
  return (vertices[b]-vertices[a])*(vertices[c+1]-vertices[a+1])-(vertices[b+1]-vertices[a+1])*(vertices[c]-vertices[a]);
}

function finiteArray(value, size, name) {
  if (!Array.isArray(value) || value.length !== size || !value.every(finite)) fail(`${name} 数据无效`);
  return Object.freeze([...value]);
}

function checkedLayer(layer, asset, contract, ids) {
  if (!layer || !ID.test(layer.id) || ids.has(layer.id)) fail('图层标识重复或无效');
  ids.add(layer.id);
  if (!contract.roles.includes(layer.role) || !finite(layer.z)) fail('图层类型或顺序无效');
  if (typeof layer.texture !== 'string' || !/^textures\/[A-Za-z0-9_-]+\.png$/.test(layer.texture)) fail('纹理路径无效');
  const vertices = layer.vertices;
  if (!Array.isArray(vertices) || vertices.length < 6 || vertices.length % 2 || vertices.length > contract.maxVertices * 2) fail('网格顶点数量无效');
  const fixed = finiteArray(vertices, vertices.length, '网格顶点');
  if (fixed.some(v => Math.abs(v) > contract.maxSide * 4)) fail('网格顶点超出范围');
  const uv = finiteArray(layer.uv, fixed.length, '纹理坐标');
  if (uv.some(v => v < 0 || v > 1)) fail('纹理坐标超出范围');
  if (!Array.isArray(layer.indices) || !layer.indices.length || layer.indices.length % 3 || layer.indices.length > contract.maxTriangles * 3) fail('三角形数量无效');
  if (!layer.indices.every(v => Number.isInteger(v) && v >= 0 && v < fixed.length / 2)) fail('三角形索引无效');
  for(let i=0;i<layer.indices.length;i+=3)if(Math.abs(triangleArea(fixed,layer.indices,i))<=1e-6)fail('原始网格存在退化三角形');
  const bounds = finiteArray(layer.bounds, 4, '纹理范围');
  if (!bounds.every(Number.isInteger) || bounds[0] < 0 || bounds[1] < 0 || bounds[2] <= 0 || bounds[3] <= 0 || bounds[0] + bounds[2] > asset.width || bounds[1] + bounds[3] > asset.height) fail('纹理范围超出图片');
  if (!layer.deltas || typeof layer.deltas !== 'object' || Array.isArray(layer.deltas)) fail('动作增量无效');
  const deltas = {};
  for (const [key, value] of Object.entries(layer.deltas)) {
    if (!Object.hasOwn(contract.parameters, key)) fail('未知动作参数');
    deltas[key] = finiteArray(value, fixed.length, '动作增量');
  }
  if (!finite(layer.phase) || !finite(layer.strength) || layer.strength < 0 || layer.strength > 1) fail('图层随动参数无效');
  return Object.freeze({...layer, vertices:fixed, uv, bounds,
    indices:Object.freeze([...layer.indices]), deltas:Object.freeze(deltas)});
}

export function validateAsset(asset, contract) {
  if (!asset || asset.format !== contract.format) fail('不支持的角色格式');
  for (const value of [asset.width, asset.height]) {
    if (!Number.isInteger(value) || value <= 0 || value > contract.maxSide) fail('角色图片尺寸无效');
  }
  if (!HASH.test(asset.sourceSha) || !HASH.test(asset.pixelSha)) fail('角色来源校验值无效');
  if (!asset.parameters || Object.keys(asset.parameters).length !== Object.keys(contract.parameters).length) fail('动作参数契约不一致');
  for (const [key, range] of Object.entries(contract.parameters)) {
    if (JSON.stringify(asset.parameters[key]) !== JSON.stringify(range)) fail('动作参数契约不一致');
  }
  if (!Array.isArray(asset.layers) || !asset.layers.length || asset.layers.length > contract.maxLayers) fail('角色图层数量无效');
  const ids = new Set();
  const layers = asset.layers.map(layer => checkedLayer(layer, asset, contract, ids));
  if(![contract.maxTexturePixels,contract.maxTextureBytes].every(value=>Number.isSafeInteger(value)&&value>0))fail('角色预览配置不完整，请刷新页面后再试');
  const texturePixels=layers.reduce((sum,layer)=>sum+layer.bounds[2]*layer.bounds[3],0);
  if(texturePixels>contract.maxTexturePixels||texturePixels*4>contract.maxTextureBytes)fail('这份角色的图片层过大，请减少图层范围后再试');
  return Object.freeze({...asset, layers:Object.freeze(layers),
    parameters:Object.freeze(Object.fromEntries(Object.entries(contract.parameters).map(([key, values]) => [key,Object.freeze([...values])])))});
}

export function blendVertices(layer, parameters, contract) {
  const sum = new Float64Array(layer.vertices);
  for (const [name, value] of Object.entries(parameters)) {
    const range = contract.parameters[name];
    if (!Object.hasOwn(contract.parameters,name) || !range || !finite(value) || value < range[0] || value > range[1]) fail('动作参数无效');
  }
  for(const [name,delta]of Object.entries(layer.deltas)) {
    if(!Object.hasOwn(contract.parameters,name)||delta.length!==sum.length)fail('动作增量无效');
    const weight=Math.fround(Object.hasOwn(parameters,name)?parameters[name]:contract.parameters[name][2]);
    for (let i = 0; i < sum.length; i++) sum[i] += weight * delta[i];
  }
  const vertices=new Float32Array(sum);
  if(!vertices.every(Number.isFinite))fail('动作增量超出数值范围');
  for(let i=0;i<layer.indices.length;i+=3) {
    const original=triangleArea(layer.vertices,layer.indices,i),deformed=triangleArea(vertices,layer.indices,i);
    if(Math.abs(original)<=1e-6||Math.abs(deformed)<=1e-6||(original>0)!==(deformed>0))fail('这个动作会让网格折叠，请减小幅度');
  }
  return vertices;
}

export function idleParameters(seconds, gaze, mouth, contract) {
  if (!finite(seconds) || seconds < 0 || !finite(gaze.x) || !finite(gaze.y) || !finite(mouth)) fail('动画输入无效');
  const time=seconds%60;
  const phase = seconds % 4.8;
  const blink = clamp(1 - Math.abs(phase - 4.24) / .12, 0, 1);
  const values = {headX:gaze.x * .25 + Math.sin(time * .43) * .04,
    headY:gaze.y * .20 + Math.sin(time * .31) * .025,
    tilt:Math.sin(time * .37) * .035, blinkLeft:blink, blinkRight:blink,
    mouth, breath:(Math.sin(time * 1.45 - Math.PI / 2) + 1) / 2,
    sway:Math.sin(time * .8) * .12};
  return Object.fromEntries(Object.entries(contract.parameters).map(([key,[low,high]]) => [key,clamp(values[key] ?? 0,low,high)]));
}

export function rmsMouth(samples) {
  if (!(samples instanceof Float32Array) || samples.length > 65536) fail('音频采样无效');
  if (!samples.length) return 0;
  let total = 0;
  for (const sample of samples) {
    if (!finite(sample)) fail('音频采样无效');
    total += sample * sample;
  }
  return clamp((Math.sqrt(total / samples.length) - .015) * 4, 0, 1);
}

export function fitViewport(sourceWidth, sourceHeight, viewportWidth, viewportHeight) {
  if (![sourceWidth,sourceHeight,viewportWidth,viewportHeight].every(v => finite(v) && v > 0)) fail('预览尺寸无效');
  const scale = Math.min(viewportWidth / sourceWidth, viewportHeight / sourceHeight);
  const width = sourceWidth * scale, height = sourceHeight * scale;
  return {scale,x:(viewportWidth-width)/2,y:(viewportHeight-height)/2,width,height};
}

export function sourcePoint(x, y, fit, width, height) {
  if (![x,y,fit.scale,fit.x,fit.y,width,height].every(finite) || fit.scale <= 0) fail('标注坐标无效');
  return [clamp((x-fit.x)/fit.scale,0,width),clamp((y-fit.y)/fit.scale,0,height)];
}

export class FrameLoop {
  #frames; #render; #error; #id = null; #generation = 0; #disposed = false;
  constructor(frames, render, error = () => {}) {
    this.#frames=frames; this.#render=render; this.#error=error;
  }
  start() {
    if (this.#disposed) fail('动画预览已关闭');
    if (this.#id !== null) return;
    const generation = ++this.#generation;
    const tick = time => {
      if (this.#disposed || generation !== this.#generation) return;
      this.#id = null;
      try { this.#render(time); } catch (error) { this.stop();this.#error(error);return; }
      if (!this.#disposed && generation === this.#generation) this.#id=this.#frames.request(tick);
    };
    this.#id=this.#frames.request(tick);
  }
  stop() {
    ++this.#generation;
    if (this.#id !== null) this.#frames.cancel(this.#id);
    this.#id=null;
  }
  dispose() { this.stop();this.#disposed=true; }
}

export class LoadEpoch {
  #epoch=0; #disposed=false;
  begin() { if (this.#disposed) fail('角色预览已关闭');return ++this.#epoch; }
  current(epoch) { return !this.#disposed && epoch === this.#epoch; }
  dispose() { ++this.#epoch;this.#disposed=true; }
}
