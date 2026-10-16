import test from 'node:test';
import assert from 'node:assert/strict';
import {binaryMaskRgba} from '../mask.mjs';

test('transparent white background encodes black while even alpha-one foreground is retained',()=>{
  const pixels=new Uint8ClampedArray([255,255,255,0,1,2,3,1,255,255,255,128]);
  const result=binaryMaskRgba(pixels);
  assert.deepEqual([...result],[0,0,0,255,255,255,255,255,255,255,255,255]);
  assert.equal(pixels[3],0);
});
test('partial brush edges encode only binary grayscale RGB and opaque PNG alpha',()=>{
  const result=binaryMaskRgba(new Uint8ClampedArray([255,255,255,64,255,255,255,192]));
  assert.ok([...result].every(value=>value===0||value===255));
});
test('invalid mask pixel shape rejects explicitly',()=>{
  assert.throws(()=>binaryMaskRgba(new Uint8ClampedArray(3)));
  assert.throws(()=>binaryMaskRgba([0,0,0,0]));
});
