import test from 'node:test';
import assert from 'node:assert/strict';
import {previewPolicy} from '../preview.mjs';

test('new user photo starts neutral until eye and mouth suggestions are confirmed',()=>{
  const policy=previewPolicy('import');
  assert.equal(policy.idle,false);assert.deepEqual(policy.parameters,{});
  assert.match(policy.message,/确认.*眼口/);
});
test('saved or restored authored character can immediately preview natural animation',()=>{
  for(const operation of ['save','restore','open','demo'])assert.equal(previewPolicy(operation).idle,true);
});
test('unknown operation cannot silently enable movement on an unconfirmed photo',()=>{
  assert.throws(()=>previewPolicy('guess'));
});
