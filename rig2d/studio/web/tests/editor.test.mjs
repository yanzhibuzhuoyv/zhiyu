import test from 'node:test';
import assert from 'node:assert/strict';
import {CharacterEditor} from '../editor.mjs';

function editor() {
  const context={clearRect(){},setTransform(){},beginPath(){},arc(){},fill(){},strokeRect(){}};
  const canvas={getContext:()=>context,getBoundingClientRect:()=>({left:0,top:0,width:100,height:80}),
    addEventListener(){},removeEventListener(){},setPointerCapture(){},hasPointerCapture:()=>false,
    classList:{toggle(){}}};
  const result=new CharacterEditor(canvas);result.width=100;result.height=80;
  result.definition={anchors:{head:[50,20],neck:[50,40],eyeLeft:[20,20,30,30],eyeRight:[60,20,70,30],mouth:[40,35,50,40]},parts:[]};
  return result;
}
const event=(x,y)=>({clientX:x,clientY:y,pointerId:1,preventDefault(){}});
globalThis.window={devicePixelRatio:1};
test('head and polygon coordinates stay inside valid source pixel indices',()=>{
  const state=editor();state.anchor='head';state.pointerDown(event(100,80));state.pointerUp(event(100,80));
  assert.deepEqual(state.definition.anchors.head,[99,79]);
});
test('clicking an eye without dragging preserves the previous nonempty rectangle',()=>{
  const state=editor();state.pointerDown(event(25,25));state.pointerUp(event(25,25));
  assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);
});
test('rectangle outer corners can reach image width and height',()=>{
  const state=editor();state.pointerDown(event(90,70));state.pointerMove(event(100,80));state.pointerUp(event(100,80));
  assert.deepEqual(state.definition.anchors.eyeLeft,[90,70,100,80]);
});
test('cancelled anchor gesture restores previous value without an undo step',()=>{
  const state=editor();state.pointerDown(event(90,70));state.pointerMove(event(100,80));state.pointerCancel(event(100,80));
  assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);assert.equal(state.history.canUndo,false);
});
test('whole anchor gesture is one reversible step',()=>{
  const state=editor();state.pointerDown(event(90,70));state.pointerMove(event(100,80));state.pointerUp(event(100,80));
  state.undo();assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);state.redo();assert.deepEqual(state.definition.anchors.eyeLeft,[90,70,100,80]);
});
test('locked editor ignores a pointer rather than modifying draft during save',()=>{
  const state=editor();state.enabled=false;state.pointerDown(event(90,70));state.pointerMove(event(100,80));state.pointerUp(event(100,80));
  assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);
});
test('oversized gesture rolls back and releases drag and capture',()=>{
  const state=editor();let released=0;state.canvas.hasPointerCapture=()=>true;state.canvas.releasePointerCapture=()=>released++;
  state.history.maxBytes=1;state.pointerDown(event(90,70));state.pointerMove(event(100,80));
  assert.throws(()=>state.pointerUp(event(100,80)));assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);
  assert.equal(state.drag,null);assert.equal(released,1);assert.equal(state.history.canUndo,false);
});
test('cancelled polygon click and switching modes restore its draft points',()=>{
  const state=editor();state.draw=()=>{};state.mode='layers';state.selected='subject';
  state.subjectPolygon=[[1,2]];state.pointerDown(event(20,30));state.pointerCancel(event(20,30));
  assert.deepEqual(state.subjectPolygon,[[1,2]]);assert.equal(state.history.canUndo,false);
  state.pointerDown(event(20,30));state.setMode('anchors');assert.deepEqual(state.subjectPolygon,[[1,2]]);
});
test('saved revision retains correction history and original anchor suggestion',()=>{
  const state=editor();state.suggested=structuredClone(state.definition.anchors);const suggested=structuredClone(state.suggested);
  state.pointerDown(event(90,70));state.pointerMove(event(100,80));state.pointerUp(event(100,80));
  state.acceptSaved({id:'test',revision:2});assert.equal(state.snapshot.revision,2);assert.deepEqual(state.suggested,suggested);
  state.undo();assert.deepEqual(state.definition.anchors.eyeLeft,[20,20,30,30]);
});
test('pending subject polygon cannot silently disappear from a successful save',()=>{
  const state=editor();state.subjectPolygon=[[1,2],[20,2],[20,30]];
  assert.throws(()=>state.definitionForSave(),/整体保留范围.*完成轮廓/);
  state.subjectPolygon=[];assert.deepEqual(state.definitionForSave(),state.definition);
});
test('empty or cancelled pointer gestures notify UI without marking a saved draft dirty',()=>{
  const state=editor(),notifications=[];state.changed=modified=>notifications.push(modified??true);
  state.pointerDown(event(25,25));state.pointerUp(event(25,25));assert.ok(notifications.every(value=>value===false));
  notifications.length=0;state.pointerDown(event(30,30));state.pointerMove(event(60,60));state.pointerCancel(event(60,60));
  assert.ok(notifications.every(value=>value===false));assert.equal(state.history.steps,0);
});
