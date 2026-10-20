import React from 'react';
import {Composition} from 'remotion';
import {Film} from './Film';

export const Root: React.FC = () => <>
  <Composition id="ZhiyuWide" component={Film} width={1920} height={1080} fps={30} durationInFrames={1440}/>
  <Composition id="ZhiyuVertical" component={Film} width={1080} height={1920} fps={30} durationInFrames={1440}/>
</>;
