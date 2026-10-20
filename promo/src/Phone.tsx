import React, {ReactNode} from 'react';
import {Img, staticFile, useCurrentFrame} from 'remotion';
import {C, Mark, Reveal, serif} from './design';

export const Phone:React.FC<{children:ReactNode;label?:string;time?:string}> = ({children,label='与你相伴',time='18:42'}) =>
  <div style={{position:'relative',width:450,height:820,flexShrink:0,borderRadius:51,padding:12,background:'#606956',boxShadow:'0 35px 80px #53614425',transform:'rotate(-2deg)'}}>
    <div style={{height:'100%',overflow:'hidden',borderRadius:40,background:C.paper,position:'relative'}}>
      <div style={{height:42,display:'flex',justifyContent:'space-between',padding:'12px 28px',fontSize:15,boxSizing:'border-box'}}><b>{time}</b><span>▂ ▄ ▆　▰</span></div>
      <div style={{display:'flex',alignItems:'center',gap:12,padding:'20px 25px 23px',borderBottom:`1px solid ${C.light}`}}>
        <Mark size={47}/><div><div style={{fontFamily:serif,fontSize:27}}>知语</div><div style={{fontSize:12,color:C.sage,marginTop:3}}>{label}</div></div>
        <svg width="57" height="25" viewBox="0 0 57 25" style={{marginLeft:'auto'}} fill="none" stroke={C.sage} strokeWidth="1.7">
          <rect x="1" y="6" width="19" height="14" rx="3"/><path d="m20 10 7-4v14l-7-4"/>
          {[6,13,20].map(y=><circle key={y} cx="49" cy={y} r="1.3" fill={C.sage}/>)}
        </svg>
      </div>
      <div style={{padding:'20px 25px'}}>{children}</div>
      <div style={{position:'absolute',bottom:0,left:0,right:0,height:77,borderTop:`1px solid ${C.light}`,display:'flex',alignItems:'center',gap:13,padding:'0 23px',background:C.paper}}>
        <span style={{fontSize:22}}>＋</span><div style={{border:`1px solid ${C.line}`,borderRadius:22,flex:1,padding:'12px 17px',fontSize:15,color:'#929786'}}>想和你说…</div><span style={{color:C.sage}}>➤</span>
      </div>
    </div>
  </div>;

export const Bubble:React.FC<{at:number;mine?:boolean;children:ReactNode;time?:string}> = ({at,mine,children,time}) => <Reveal at={at} style={{marginBottom:15}}>
  <div style={{display:'flex',justifyContent:mine?'flex-end':'flex-start'}}>
    <div style={{maxWidth:'91%',border:`1.2px solid ${mine?'#b69a8d':'#a4b099'}`,borderRadius:mine?'23px 7px 23px 23px':'7px 23px 23px 23px',padding:'12px 19px',fontSize:22,lineHeight:1.55}}>{children}</div>
  </div>
  {time&&<div style={{fontSize:11,color:'#909582',marginTop:6,textAlign:mine?'right':'left'}}>{time}</div>}
</Reveal>;

export const PhotoMessage:React.FC<{at:number;src:string}> = ({at,src}) => <Reveal at={at}>
  <Img src={staticFile(src)} style={{width:280,height:225,objectFit:'cover',borderRadius:17,marginBottom:19}}/>
</Reveal>;

export const Wave:React.FC<{color?:string;height?:number}> = ({color=C.sage,height=74}) => {
  const f=useCurrentFrame();
  return <div style={{height,display:'flex',gap:6,alignItems:'center',justifyContent:'center'}}>
    {Array.from({length:24},(_,i)=><div key={i} style={{width:5,borderRadius:6,background:color,height:8+(height-13)*Math.abs(Math.sin(f*.065+i*.64))*Math.pow(Math.sin((i+1)/25*Math.PI),1.1)}}/>)}
  </div>;
};
