export function previewPolicy(operation) {
  if(!['import','save','restore','open','demo'].includes(operation))throw Error('预览来源无效');
  return Object.freeze({idle:operation!=='import',parameters:Object.freeze({}),message:operation==='import'?
    '请先确认建议眼口位置，再保存角色。勾选“自然呼吸与眨眼”可试看片段动作。':
    '角色已准备好。确认眼口与附件后，保存并下载属于你的角色。'});
}
