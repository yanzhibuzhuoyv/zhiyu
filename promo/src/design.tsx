import React, {CSSProperties, ReactNode} from 'react';
import {AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

export const C = {paper:'#f8f3e5', ink:'#3e493f', sage:'#768a72', rose:'#9c5e54', light:'#e8e6d7', line:'#b0b69f'};
export const serif = '"Zhiyu Serif", "KaiTi", serif';
export const sans = '"Zhiyu Sans", "Microsoft YaHei", sans-serif';
export const clamp = {extrapolateLeft:'clamp',extrapolateRight:'clamp'} as const;
export const ramp = (f:number, a:number, b:number) => interpolate(f,[a,b],[0,1],clamp);
export const usePortrait = () => {const {width,height}=useVideoConfig();return height>width;};

export const Reveal:React.FC<{at?:number; children:ReactNode; style?:CSSProperties}> = ({at=0,children,style}) => {
  const f=useCurrentFrame();const t=ramp(f,at,at+25);const p=1-Math.pow(1-t,3);
  return <div style={{...style,opacity:t,transform:`translateY(${(1-p)*32}px)`}}>{children}</div>;
};

export const Flower:React.FC<{style?:CSSProperties}> = ({style}) =>
  <Img src={staticFile('bouquet.png')} style={{objectFit:'contain',...style}}/>;

export const Paper:React.FC = () => {
  const f=useCurrentFrame();
  return <AbsoluteFill style={{background:C.paper}}>
    <AbsoluteFill style={{background:'radial-gradient(ellipse at 12% 25%, #e6e8cf90, transparent 45%), radial-gradient(ellipse at 90% 80%, #ecd4c580, transparent 45%)'}}/>
    <AbsoluteFill style={{opacity:0.20,backgroundImage:'url("data:image/svg+xml,%3Csvg viewBox=%270 0 180 180%27 xmlns=%27http://www.w3.org/2000/svg%27%3E%3Cfilter id=%27n%27%3E%3CfeTurbulence type=%27fractalNoise%27 baseFrequency=%27.72%27 numOctaves=%273%27 stitchTiles=%27stitch%27/%3E%3C/filter%3E%3Crect width=%27100%25%27 height=%27100%25%27 opacity=%27.2%27 filter=%27url(%23n)%27/%3E%3C/svg%3E")'}}/>
    {Array.from({length:13},(_,i)=><div key={i} style={{position:'absolute',left:`${(i*37+11)%100}%`,top:`${(i*19+13)%100}%`,width:5+i%4,height:5+i%4,borderRadius:'50%',background:C.sage,opacity:.08+.045*Math.sin(f/75+i),transform:`translateY(${Math.sin(f/90+i)*20}px)`}}/>)}
  </AbsoluteFill>;
};

export const Mark:React.FC<{size?:number}> = ({size=54}) => <svg width={size} height={size} viewBox="0 0 80 80">
  <circle cx="40" cy="40" r="37" fill="none" stroke={C.sage} strokeWidth="1.5"/>
  <path d="M40 62V32M40 45C14 44 18 20 35 29C42 33 40 45 40 45M40 35C40 13 62 16 58 30C54 40 40 35 40 35M40 55C66 55 63 35 49 42C43 45 40 55 40 55" stroke={C.sage} fill="none" strokeWidth="2"/>
</svg>;

export const Copy:React.FC<{number:string;kicker:string;title:ReactNode;body:ReactNode;note?:string}> = p => {
  const vertical=usePortrait();
  return <div style={{width:vertical?'100%':790}}>
    <Reveal at={12}><div style={{fontSize:19,letterSpacing:5,color:C.rose,marginBottom:28}}>{p.number} / {p.kicker}</div></Reveal>
    <Reveal at={22}><h1 style={{fontFamily:serif,fontSize:vertical?79:86,fontWeight:500,lineHeight:1.42,letterSpacing:2,margin:'0 0 30px'}}>{p.title}</h1></Reveal>
    <Reveal at={42}><div style={{fontSize:vertical?30:28,lineHeight:1.9,color:'#6a7265'}}>{p.body}</div></Reveal>
    {p.note&&<Reveal at={60}><div style={{marginTop:26,fontSize:19,lineHeight:1.8,color:'#787b6c',maxWidth:700}}>{p.note}</div></Reveal>}
  </div>;
};

export const Layout:React.FC<{copy:ReactNode;children:ReactNode}> = ({copy,children}) => {
  const vertical=usePortrait();
  return <div style={{position:'absolute',inset:vertical?'225px 90px 175px':'158px 130px 125px',display:'flex',flexDirection:vertical?'column':'row',justifyContent:'space-between',alignItems:vertical?'stretch':'center',gap:vertical?55:35}}>
    {copy}<div style={{flex:1,display:'flex',alignItems:'center',justifyContent:'center',minWidth:0,minHeight:0}}>{children}</div>
  </div>;
};

export const Frame:React.FC<{children:ReactNode;duration:number;index:number;caption?:string}> = ({children,duration,index,caption}) => {
  const f=useCurrentFrame();const vertical=usePortrait();
  const opacity=Math.min(ramp(f,0,24),index===5?1:1-ramp(f,duration-30,duration));
  return <AbsoluteFill style={{opacity,color:C.ink,fontFamily:sans}}>
    <Paper/>
    <div style={{position:'absolute',left:vertical?82:100,top:vertical?92:66,display:'flex',alignItems:'center',gap:17}}>
      <Mark size={46}/><span style={{fontFamily:serif,fontSize:30,letterSpacing:5}}>知语</span>
      <span style={{height:25,borderLeft:`1px solid ${C.line}`,margin:'0 8px'}}/><span style={{fontSize:15,letterSpacing:4,color:'#7c8475'}}>ZHIYU</span>
    </div>
    <div style={{position:'absolute',right:vertical?88:104,top:vertical?106:79,fontSize:14,letterSpacing:3,color:'#8c9180'}}>A LITTLE CLOSER</div>
    {children}
    <div style={{position:'absolute',bottom:vertical?80:54,left:vertical?90:105,fontSize:vertical?18:16,color:'#818675',letterSpacing:1}}>
      {caption??'原生 Android · 自备 API Key'}
    </div>
    <div style={{position:'absolute',bottom:vertical?85:60,right:vertical?92:106,display:'flex',gap:13}}>
      {[0,1,2,3,4,5].map(i=><div key={i} style={{width:i===index?35:7,height:7,borderRadius:10,background:i===index?C.rose:'#c6c9b8'}}/>)}
    </div>
  </AbsoluteFill>;
};
