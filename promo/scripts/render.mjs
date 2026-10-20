import {bundle} from '@remotion/bundler';
import {selectComposition, renderMedia, renderStill} from '@remotion/renderer';
import {mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';

const mode = process.argv[2] ?? 'stills';
if (!['stills', 'video'].includes(mode)) throw new Error('Use stills or video');
const out = path.resolve('out');
await mkdir(out, {recursive:true});
const serveUrl = await bundle({entryPoint:path.resolve('src/index.ts')});
const renders = [];
for (const id of ['ZhiyuWide', 'ZhiyuVertical']) {
  const composition = await selectComposition({serveUrl, id});
  if (mode === 'stills') {
    for (const frame of [140, 409, 620, 860, 1100, 1360]) {
      const output = path.join(out, `${id}-${frame}.png`);
      await renderStill({serveUrl, composition, output, frame, imageFormat:'png'});
      console.log(output);
    }
  } else {
    const outputLocation = path.join(out, `${id}.mp4`);
    let last = -1;
    await renderMedia({serveUrl, composition, outputLocation,
      codec:'h264', crf:18, pixelFormat:'yuv420p', audioCodec:'aac', audioBitrate:'192k',
      concurrency:4, onProgress:({progress}) => {
        const step = Math.floor(progress * 10);
        if (step > last) {last = step; console.log(`${id} ${step * 10}%`);}
      }});
    renders.push({id, path:outputLocation, width:composition.width, height:composition.height,
      fps:composition.fps, frames:composition.durationInFrames});
    console.log(outputLocation);
  }
}
if (mode === 'video') await writeFile(path.join(out, 'render.json'), JSON.stringify(renders,null,2));
