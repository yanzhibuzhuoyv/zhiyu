import test from 'node:test';
import assert from 'node:assert/strict';
import {EditHistory} from '../history.mjs';

test('undo and redo restore independent payloads, including alpha one and 64',()=>{
  const h=new EditHistory(),before={alpha:new Uint8Array([0,1,64,255])},after={alpha:new Uint8Array([255,0,0,0])};
  let result;h.record({before,after,apply:x=>{result=x;},label:'笔画'});
  before.alpha.fill(7);after.alpha.fill(8);h.undo();assert.deepEqual([...result.alpha],[0,1,64,255]);
  result.alpha.fill(9);h.redo();assert.deepEqual([...result.alpha],[255,0,0,0]);h.undo();assert.equal(result.alpha[1],1);
});
test('an edit after undo drops future redo and frees its bytes',()=>{
  const h=new EditHistory(),apply=()=>{};
  h.record({before:0,after:1,apply});h.record({before:1,after:2,apply});h.undo();
  h.record({before:1,after:3,apply});assert.equal(h.canRedo,false);assert.equal(h.steps,2);assert.equal(h.usedBytes,32);
});
test('same field input can coalesce without losing its initial value',()=>{
  const h=new EditHistory();let value;const apply=x=>{value=x;};
  h.record({before:0,after:.2,apply,group:'strength'});h.record({before:.2,after:.8,apply,group:'strength'});
  assert.equal(h.steps,1);h.undo();assert.equal(value,0);h.redo();assert.equal(value,.8);
  h.breakGroup();h.record({before:.8,after:.9,apply,group:'strength'});assert.equal(h.steps,2);
});
test('count and byte budgets evict oldest actions, retaining current undo',()=>{
  const h=new EditHistory({maxSteps:2,maxBytes:40}),apply=()=>{};
  for(let i=0;i<4;i++)h.record({before:i,after:i+1,apply});
  assert.equal(h.steps,2);assert.equal(h.usedBytes,32);assert.equal(h.undo(),true);assert.equal(h.undo(),true);assert.equal(h.undo(),false);
});
test('oversized action is rejected before replacing valid history; strings count',()=>{
  const h=new EditHistory({maxBytes:80}),apply=()=>{};h.record({before:0,after:1,apply});
  assert.throws(()=>h.record({before:{mask:'a'.repeat(41)},after:1,apply}),/预算/);
  assert.equal(h.steps,1);assert.equal(h.usedBytes,16);assert.equal(h.canUndo,true);
});
test('failed restoration does not consume undo and clear prevents cross-project replay',()=>{
  const h=new EditHistory();let broken=true;h.record({before:0,after:1,apply:()=>{if(broken)throw Error('restore');}});
  assert.throws(()=>h.undo(),/restore/);assert.equal(h.canUndo,true);broken=false;h.undo();assert.equal(h.canRedo,true);
  h.clear();assert.equal(h.canUndo,false);assert.equal(h.canRedo,false);assert.equal(h.usedBytes,0);
});
test('invalid history limits reject and identical values do not add a step',()=>{
  for(const options of [{maxSteps:0},{maxSteps:true},{maxBytes:NaN},{maxBytes:-1}])assert.throws(()=>new EditHistory(options));
  const h=new EditHistory();assert.equal(h.record({before:[1,2],after:[1,2],apply:()=>{}}),false);assert.equal(h.steps,0);
});
