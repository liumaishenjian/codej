import readline from 'node:readline';
let sequence = 0;
let requestId;
const mode = process.argv[2];
const emit = (type, payload, runId) => process.stdout.write(JSON.stringify({version:0,type,requestId:requestId??'init',sessionId:'session',...(runId?{runId}:{}),sequence:++sequence,payload})+'\n');
readline.createInterface({input:process.stdin}).on('line', line => {
  const c=JSON.parse(line); requestId=c.requestId;
  if(c.type==='initialize') emit('initialized',{protocolVersion:0,...(mode==='old'?{}:{questionnaireV1:true})});
  else if(c.type==='run.start') {
    emit('run.command.result',{commandType:'run.start',disposition:'accepted',code:'ACCEPTED'});
    emit('run.started',{},'run');
    emit('question.requested',{callId:'call',questions:[{id:'q',title:'题签',question:'请回答',multiSelect:false,allowFreeText:true,options:[]}]},'run');
  } else if(c.type==='question.resolve') {
    emit('model.text.delta',{text:JSON.stringify(c.payload)},'run');
    emit('run.completed',{stopReason:'completed'},'run');
  } else if(c.type==='shutdown') process.exit(0);
});
