import React from 'react';
import {AbsoluteFill, Img, Sequence, staticFile, useCurrentFrame} from 'remotion';
import {Audio} from '@remotion/media';
import {loadFont} from '@remotion/fonts';
import {C, Copy, Flower, Frame, Layout, Mark, Reveal, ramp, serif, usePortrait} from './design';
import {Bubble, Phone, PhotoMessage, Wave} from './Phone';

loadFont({family:'Zhiyu Serif',url:staticFile('fonts/serif.woff2')});
loadFont({family:'Zhiyu Sans',url:staticFile('fonts/sans.woff2')});

const Opening:React.FC = () => {
  const v=usePortrait();const f=useCurrentFrame();
  return <Frame duration={210} index={0}>
    <div style={{position:'absolute',left:v?90:140,top:v?290:290,zIndex:2}}>
      <Reveal at={10}><div style={{fontSize:20,letterSpacing:7,color:C.rose,marginBottom:27}}>给日常，留一个位置</div></Reveal>
      <Reveal at={25}><div style={{fontFamily:serif,fontSize:v?170:180,lineHeight:1.3,letterSpacing:26}}>知语</div></Reveal>
      <Reveal at={45}><div style={{fontSize:v?43:42,letterSpacing:4,marginTop:28}}>把日常，说给知语。</div></Reveal>
      <Reveal at={75}><div style={{fontSize:22,letterSpacing:3,color:'#78836d',marginTop:46}}>你的 OC　·　声音　·　记忆　·　陪伴</div></Reveal>
    </div>
    <Flower style={{position:'absolute',width:v?1110:1080,right:v?-90:-50,bottom:v?210:100,transform:`translateY(${Math.sin(f/110)*12}px) rotate(-3deg)`,opacity:.95}}/>
    <div style={{position:'absolute',border:`1px solid ${C.line}`,width:v?760:700,height:v?850:700,borderRadius:'50%',right:v?140:80,bottom:v?200:110,opacity:.25}}/>
  </Frame>;
};

const Chat:React.FC = () => <Frame duration={270} index={1} caption="界面演绎 · 示例对话">
  <Layout copy={<Copy number="01" kicker="日常里的回应" title={<>不用想好开场，<br/>想说就说。</>} body={<>一句碎碎念，一点小情绪。<br/>在同一段聊天里，继续靠近。</>}/>}>
    <Reveal at={15}><Phone>
      <div style={{fontSize:12,textAlign:'center',color:'#929785',margin:'5px 0 25px'}}>今天 18:42</div>
      <Bubble at={32} mine time="18:42:08">今天有点累。</Bubble>
      <Bubble at={76} time="18:42:13">回来啦。</Bubble>
      <Bubble at={116} time="18:42:15">先坐会儿，我陪你。</Bubble>
      <Bubble at={170} mine time="18:42:21">嗯，想和你待一会儿。</Bubble>
      <Bubble at={214} time="18:42:24">那我不走。</Bubble>
    </Phone></Reveal>
  </Layout>
</Frame>;

const Memory:React.FC = () => {
  const v=usePortrait();
  const cards=[{at:24,tag:'那一天',text:'“奶茶还是不要冰的。”',angle:-5,left:0,top:15},
    {at:67,tag:'后来',text:'“下雨的时候，喜欢待在窗边。”',angle:4,left:45,top:195},
    {at:112,tag:'今天',text:'“给你点了热的，少糖。”',angle:-2,left:8,top:385}];
  return <Frame duration={270} index={2} caption="记忆检索示意 · 召回效果受检索与模型影响">
    <Layout copy={<Copy number="02" kicker="一起记住的小事" title={<>让过去的话，<br/>接回今天。</>} body={<>偏好、约定，还有那些随口一提。<br/>从原文中，找回有关你的细节。</>}/>}>
      <div style={{position:'relative',width:v?740:680,height:650}}>
        <div style={{position:'absolute',top:0,bottom:20,left:34,borderLeft:`1px dashed ${C.line}`}}/>
        {cards.map((card,i)=><Reveal key={card.tag} at={card.at} style={{position:'absolute',left:card.left,top:card.top,width:'94%'}}>
          <div style={{transform:`rotate(${card.angle}deg)`,padding:'33px 40px 39px',background:i===2?'#e8eddd':'#fffaf0',border:'1px solid #d0d2bf',boxShadow:'0 17px 35px #5b603412',borderRadius:3}}>
            <div style={{fontSize:17,letterSpacing:4,color:C.rose,marginBottom:18}}>{card.tag}　/　{i===2?'想起你':'一段原话'}</div>
            <div style={{fontFamily:serif,fontSize:33,lineHeight:1.7}}>{card.text}</div>
          </div>
        </Reveal>)}
      </div>
    </Layout>
  </Frame>;
};

const Daily:React.FC = () => {
  const f=useCurrentFrame();
  return <Frame duration={270} index={3} caption="虚构生活场景 · 图像生成需要相应服务配置">
    <Layout copy={<Copy number="03" kicker="想分享给你的瞬间" title={<>不止等你开口，<br/>也有日常想分享。</>} body={<>窗边的猫，雨后的散步。<br/>让一句“想你”，有了具体的样子。</>} note="主动联系与日常分享需通知及后台运行权限。"/>}>
      <div style={{position:'relative',width:680,height:740}}>
        <Reveal at={15} style={{position:'absolute',left:165,top:10}}>
          <div style={{transform:`rotate(${7+Math.sin(f/100)}deg)`,background:'#fffcf4',padding:'16px 16px 55px',boxShadow:'0 16px 30px #2f3b2420'}}>
            <Img src={staticFile('park.png')} style={{width:410,height:325,objectFit:'cover'}}/>
            <div style={{fontFamily:serif,fontSize:21,position:'absolute',bottom:18,left:24}}>雨停了，出去走走。</div>
          </div>
        </Reveal>
        <Reveal at={55} style={{position:'absolute',left:0,top:250}}>
          <div style={{transform:`rotate(${-7+Math.sin(f/90)}deg)`,background:'#fffcf4',padding:'17px 17px 63px',boxShadow:'0 20px 45px #2f3b2428'}}>
            <Img src={staticFile('cat.png')} style={{width:445,height:330,objectFit:'cover'}}/>
            <div style={{fontFamily:serif,fontSize:25,position:'absolute',bottom:19,left:25}}>这只猫，像不像在等你？</div>
          </div>
        </Reveal>
        <Reveal at={116} style={{position:'absolute',right:0,bottom:5}}><div style={{background:C.sage,color:'#fffdf0',padding:'19px 29px',borderRadius:'25px 25px 25px 3px',fontSize:24,boxShadow:'0 8px 25px #52614320'}}>刚刚看到的，想给你看看。</div></Reveal>
      </div>
    </Layout>
  </Frame>;
};

const Calling:React.FC = () => {
  const v=usePortrait();const f=useCurrentFrame();
  return <Frame duration={300} index={4} caption="通话演绎 · 需麦克风 / 摄像头授权及视觉模型">
    <Layout copy={<Copy number="04" kicker="再靠近一点" title={<>听你说，<br/>也看看你的此刻。</>} body={<>从打字，到声音。<br/>把眼前的一刻，也带进对话。</>} note="开启摄像头后，语音可携带当前单帧。响应速度受设备、网络和模型影响。"/>}>
      <Reveal at={15}><div style={{width:v?685:650,height:700,border:'1px solid #b7c0a7',borderRadius:42,position:'relative',background:'linear-gradient(145deg,#edf0df,#faf6e8)',overflow:'hidden',boxShadow:'0 30px 70px #53614418'}}>
        <Flower style={{position:'absolute',width:570,top:80,left:55,opacity:.36,transform:`translateY(${Math.sin(f/50)*4}px)`}}/>
        <div style={{position:'absolute',top:45,left:42,fontSize:20,letterSpacing:3}}>知语</div>
        <div style={{position:'absolute',top:46,right:38,fontSize:16,color:C.sage}}>语音相伴</div>
        <div style={{position:'absolute',top:175,left:0,right:0,textAlign:'center'}}><Mark size={100}/><div style={{fontFamily:serif,fontSize:42,marginTop:30}}>“我听着呢。”</div></div>
        <div style={{position:'absolute',bottom:174,left:50,right:50}}><Wave height={80}/></div>
        <div style={{position:'absolute',bottom:75,left:0,right:0,display:'flex',justifyContent:'center',gap:39}}>
          {['麦克风','挂断','摄像头'].map((item,i)=><div key={item} style={{width:78,height:78,borderRadius:'50%',background:i===1?C.rose:'#dde5d2',color:i===1?'#fff8e9':C.ink,display:'flex',alignItems:'center',justifyContent:'center',fontSize:17}}>{item}</div>)}
        </div>
        <div style={{position:'absolute',bottom:25,left:0,right:0,textAlign:'center',fontSize:14,color:'#7c876f'}}>单帧看见此刻 · 摄像头由你开启</div>
      </div></Reveal>
    </Layout>
  </Frame>;
};

const Closing:React.FC = () => {
  const v=usePortrait();const f=useCurrentFrame();
  return <Frame duration={270} index={5} caption="知语 · 持续开发中的开源 Android AI 伴侣">
    <Flower style={{position:'absolute',width:v?1280:1200,left:v?-900:-620,bottom:v?100:-150,opacity:v?.38:.65,transform:`rotate(-12deg) translateY(${Math.sin(f/110)*10}px)`}}/>
    <Flower style={{position:'absolute',width:v?1100:1000,right:v?-800:-610,top:v?180:-210,opacity:v?.35:.5,transform:'rotate(160deg)'}}/>
    <div style={{position:'absolute',inset:v?'340px 70px 230px':'210px 100px 130px',display:'flex',flexDirection:'column',alignItems:'center',justifyContent:'center',textAlign:'center'}}>
      <Reveal at={5}><Mark size={80}/></Reveal>
      <Reveal at={20}><div style={{fontFamily:serif,fontSize:v?152:142,letterSpacing:22,marginTop:20}}>知语</div></Reveal>
      <Reveal at={42}><div style={{fontFamily:serif,fontSize:v?58:61,letterSpacing:3,marginTop:30}}>你的 OC，走进日常。</div></Reveal>
      <Reveal at={65}><div style={{fontSize:23,color:'#78836d',letterSpacing:3,marginTop:38}}>原生 Android　/　自备 API Key　/　本地存储</div></Reveal>
      <Reveal at={88}><div style={{borderTop:`1px solid ${C.line}`,marginTop:52,paddingTop:29,fontSize:24,letterSpacing:1}}>github.com/yanzhibuzhuoyv/zhiyu</div></Reveal>
      <Reveal at={105}><div style={{fontSize:17,color:'#8a8e7b',marginTop:22}}>云端聊天与图像服务需要联网 · 影片为界面演绎</div></Reveal>
    </div>
  </Frame>;
};

export const Film:React.FC = () => <AbsoluteFill style={{background:C.paper}}>
  <Audio src={staticFile('daylight.wav')} volume={.75}/>
  <Sequence from={0} durationInFrames={210}><Opening/></Sequence>
  <Sequence from={180} durationInFrames={270}><Chat/></Sequence>
  <Sequence from={420} durationInFrames={270}><Memory/></Sequence>
  <Sequence from={660} durationInFrames={270}><Daily/></Sequence>
  <Sequence from={900} durationInFrames={300}><Calling/></Sequence>
  <Sequence from={1170} durationInFrames={270}><Closing/></Sequence>
</AbsoluteFill>;
