import test from 'node:test';
import assert from 'node:assert/strict';
import {LocalAudio} from '../audio.mjs';

test('changing local audio reuses the one media source and releases URLs on dispose',async()=>{
  let created=0,closed=0,revoked=0;const bound=new WeakSet();
  class Context {
    constructor(){created++;this.state='suspended';}
    createAnalyser(){return {fftSize:0,connect(){},disconnect(){},getFloatTimeDomainData(){}};}
    createMediaElementSource(element){if(bound.has(element))throw Error('media element is already bound');bound.add(element);return {connect(){},disconnect(){}};}
    async close(){closed++;}async resume(){}
  }
  const element={addEventListener(){},removeEventListener(){},pause(){},removeAttribute(){},load(){},paused:true};
  const originalWindow=globalThis.window,originalCreate=URL.createObjectURL,originalRevoke=URL.revokeObjectURL;
  globalThis.window={AudioContext:Context};URL.createObjectURL=()=>`blob:test-${created}`;URL.revokeObjectURL=()=>revoked++;
  try {
    const audio=new LocalAudio(element);await audio.load({size:10,name:'a.wav'});await audio.load({size:10,name:'b.wav'});
    assert.equal(created,1);assert.equal(closed,0);assert.equal(revoked,1);
    await audio.dispose();assert.equal(closed,1);assert.equal(revoked,2);
  } finally {globalThis.window=originalWindow;URL.createObjectURL=originalCreate;URL.revokeObjectURL=originalRevoke;}
});
