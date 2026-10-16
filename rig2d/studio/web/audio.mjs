import {rmsMouth, LoadEpoch} from './runtime.mjs';

export class LocalAudio {
  constructor(element, changed=()=>{}) {
    this.element=element;this.changed=changed;this.loads=new LoadEpoch();this.context=null;this.url=null;
    this.onPlay=()=>this.context?.resume().catch(error=>this.changed(error.message));
    this.onError=()=>this.changed('这个音频暂时无法播放，请换一个音频文件');
    element.addEventListener('play',this.onPlay);element.addEventListener('error',this.onError);
  }
  async load(file) {
    if(!file || file.size>50*1024*1024)throw Error('请选择小于 50 MB 的音频文件');
    this.loads.begin();this.clearFile();
    const Context=window.AudioContext||window.webkitAudioContext;
    if(!Context)throw Error('这个浏览器暂不支持声音口型');
    if(!this.context) {
      const context=new Context();
      try {
        const analyser=context.createAnalyser();analyser.fftSize=1024;
        const source=context.createMediaElementSource(this.element);source.connect(analyser);analyser.connect(context.destination);
        this.context=context;this.analyser=analyser;this.source=source;this.samples=new Float32Array(analyser.fftSize);
      } catch(error) {await context.close();throw error;}
    }
    this.url=URL.createObjectURL(file);this.element.src=this.url;this.element.hidden=false;
    this.changed(`已选 ${file.name}，播放后口型会随音量变化`);
  }
  mouth() {
    if(!this.context||this.element.paused||this.element.ended||this.context.state!=='running')return 0;
    this.analyser.getFloatTimeDomainData(this.samples);return rmsMouth(this.samples);
  }
  clearFile() {
    this.element.pause();this.element.removeAttribute('src');this.element.load();this.element.hidden=true;
    if(this.url){URL.revokeObjectURL(this.url);this.url=null;}
  }
  async release() {
    this.clearFile();
    this.source?.disconnect();this.analyser?.disconnect();this.source=null;this.analyser=null;
    if(this.context){const previous=this.context;this.context=null;await previous.close();}
  }
  dispose() {this.loads.dispose();this.element.removeEventListener('play',this.onPlay);
    this.element.removeEventListener('error',this.onError);return this.release();}
}
