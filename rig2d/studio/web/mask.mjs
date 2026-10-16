export function binaryMaskRgba(pixels) {
  if(!(pixels instanceof Uint8ClampedArray)||pixels.length%4||pixels.length>4*1536*1536)throw Error('保留范围的像素数据无效');
  const result=new Uint8ClampedArray(pixels.length);
  for(let i=0;i<pixels.length;i+=4) {
    const value=pixels[i+3]>0?255:0;
    result[i]=value;result[i+1]=value;result[i+2]=value;result[i+3]=255;
  }
  return result;
}
