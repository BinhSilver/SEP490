(function(){'use strict';
  var levels=['N5','N4','N3','N2','N1'];
  var sectionLabels={vocabulary:'Từ vựng',grammar:'Ngữ pháp',reading:'Đọc hiểu',listening:'Nghe hiểu'};
  var state={level:'N3',section:null,type:null,types:[],questions:[],index:0,answers:{},score:0};
  function $(id){return document.getElementById(id);}
  function esc(value){return String(value==null?'':value).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function safeHtml(value){var template=document.createElement('template');template.innerHTML=String(value||'');template.content.querySelectorAll('script,style,iframe,object,embed').forEach(function(node){node.remove();});template.content.querySelectorAll('*').forEach(function(node){Array.from(node.attributes).forEach(function(attribute){if(/^on/i.test(attribute.name)||/^javascript:/i.test(attribute.value))node.removeAttribute(attribute.name);});});return template.innerHTML;}
  function fetchData(url,options){return fetch(url,options).then(function(response){return response.json().then(function(body){if(!response.ok||body.success===false)throw new Error(body.message||'Không tải được dữ liệu.');return body.data;});});}
  function updateSummary(){var type=state.types.find(function(t){return t.code===state.type&&t.section===state.section;});$('practiceSummaryLevel').textContent=state.level;$('practiceSummaryType').textContent=type?type.nameVi:'Chọn dạng câu hỏi';$('practiceSummarySection').textContent=sectionLabels[state.section]||'Một chút tập trung, một bước tiến bộ.';}
  function pauseAudio(){var player=$('practiceAudioPlayer');if(player)player.pause();}
  function renderLevels(){$('practiceLevels').innerHTML=levels.map(function(level){return '<button type="button" data-level="'+level+'" aria-pressed="'+(level===state.level)+'" class="'+(level===state.level?'is-active':'')+'">'+level+'</button>';}).join('');}
  function availableSections(){return Object.keys(sectionLabels).filter(function(section){return state.types.some(function(type){return type.section===section;});});}
  function renderSections(){var sections=availableSections();if(!sections.includes(state.section))state.section=sections[0]||null;$('practiceSections').innerHTML=sections.map(function(section){return '<button type="button" data-section="'+section+'" aria-pressed="'+(section===state.section)+'" class="'+(section===state.section?'is-active':'')+'">'+'<span class="jp-skill-icon" aria-hidden="true">'+({vocabulary:'語',grammar:'文',reading:'読',listening:'聴'}[section])+'</span>'+sectionLabels[section]+'</button>';}).join('');renderTypes();}
  function renderTypes(){var visible=state.types.filter(function(type){return type.section===state.section;});if(!visible.some(function(type){return type.code===state.type;}))state.type=visible.length?visible[0].code:null;$('practiceTypes').innerHTML=visible.map(function(type){return '<button type="button" class="jlpt-practice-type '+(type.code===state.type?'is-active':'')+'" data-type="'+esc(type.code)+'" aria-pressed="'+(type.code===state.type)+'"><strong>'+esc(type.nameVi)+'</strong><small>'+(type.nameJa?esc(type.nameJa):esc(type.code))+'</small></button>';}).join('')||'<p>Chưa có dạng câu hỏi cho nhóm này.</p>';$('practiceStart').disabled=!state.type;updateSummary();}
  function loadTypes(){var requestedLevel=state.level;state.type=null;state.types=[];$('practiceSections').innerHTML='';$('practiceSetupNotice').hidden=true;updateSummary();$('practiceTypes').innerHTML='<p>Đang tải các dạng câu hỏi…</p>';$('practiceStart').disabled=true;fetchData('/api/jlpt/practice/types?level='+state.level).then(function(types){if(requestedLevel!==state.level)return;state.types=types||[];renderSections();}).catch(function(error){if(requestedLevel!==state.level)return;state.types=[];$('practiceTypes').innerHTML='';showNotice(error.message);});}
  function showNotice(message){$('practiceSetupNotice').textContent=message;$('practiceSetupNotice').hidden=false;}
  function start(){if(!state.type||state.starting)return;state.starting=true;$('practiceSetup').inert=true;$('practiceRetry').disabled=true;$('practiceSetupNotice').hidden=true;$('practiceStart').disabled=true;$('practiceStart').textContent='Đang tạo bộ câu…';var url='/api/jlpt/practice/questions?level='+state.level+'&section='+state.section+'&type='+encodeURIComponent(state.type)+'&limit='+$('practiceLimit').value;fetchData(url).then(function(questions){if(!questions||!questions.length)throw new Error('Không tìm thấy câu hỏi phù hợp.');pauseAudio();$('practiceAudio').innerHTML='';delete $('practiceAudio').dataset.audioKey;state.questions=questions;state.index=0;state.answers={};state.score=0;$('practiceSetup').hidden=true;$('practiceResult').hidden=true;$('practiceRunner').hidden=false;renderQuestion();window.scrollTo({top:0,behavior:'smooth'});}).catch(function(error){leaveRunner();showNotice(error.message);}).finally(function(){state.starting=false;$('practiceSetup').inert=false;$('practiceRetry').disabled=false;$('practiceStart').disabled=!state.type;$('practiceStart').innerHTML='Bắt đầu luyện →';});}
  function current(){return state.questions[state.index];}
  function questionTranscript(question){var clip=question.audioClip;if(clip&&clip.transcript&&clip.transcript.length)return clip.transcript;if(!question.transcript)return[];if(!clip)return question.transcript;return question.transcript.filter(function(segment){return segment.start>=clip.start&&segment.start<clip.end;});}
  function renderAudio(question) {
    var box=$('practiceAudio'),clip=question.audioClip,url=clip&&clip.audioUrl||question.mondaiAudioUrl;
    if(!url){box.hidden=true;box.innerHTML='';delete box.dataset.audioKey;return;}
    var transcript=questionTranscript(question),aligned=!clip||clip.transcriptAligned!==false;
    var start=clip?Number(clip.start)||0:0,end=clip&&clip.end!=null?Number(clip.end):null;
    var duration=end!=null&&end>start?end-start:null;
    var key=JSON.stringify([question.id,url,start,end,transcript,aligned]);
    if(box.dataset.audioKey===key&&box.querySelector('audio'))return;
    pauseAudio();box.dataset.audioKey=key;box.hidden=false;
    var hasTranslation=transcript.some(function(s){return !!String(s.vi||'').trim();});
    function symbol(name){var paths={play_arrow:'<path d="m8 5 11 7-11 7Z"/>',pause:'<path d="M8 5v14M16 5v14"/>',article:'<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',translate:'<path d="M3 5h12M9 3v2M5 5c1 5 4 8 8 10M13 5c-1 5-4 8-9 11M13 21l4-10 4 10M15 17h4"/>'};return '<svg class="jp-audio-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+paths[name]+'</svg>';}
    box.innerHTML='<div class="jlpt-practice-audio__bar"><audio id="practiceAudioPlayer" preload="metadata" src="'+esc(url)+'"></audio>'+
      '<button class="jlpt-practice-audio__play" type="button" id="practiceAudioPlay" aria-label="Phát đoạn nghe">'+symbol('play_arrow')+'</button>'+
      '<input id="practiceAudioRange" type="range" min="0" max="'+(duration||0)+'" step="0.1" value="0" aria-label="Tiến trình đoạn nghe">'+
      '<time id="practiceAudioTime">00:00 / '+(duration==null?'--:--':formatTime(duration))+'</time>'+
      (transcript.length?'<button type="button" id="practiceTranscriptToggle" aria-expanded="false" aria-controls="practiceTranscript">'+symbol('article')+'<span>Script</span></button>':'')+
      (hasTranslation?'<button type="button" id="practiceTranslationToggle" aria-pressed="false" aria-controls="practiceTranscript">'+symbol('translate')+'<span>Dịch</span></button>':'')+'</div>'+
      (transcript.length?'<div id="practiceTranscript" class="jlpt-practice-transcript" hidden>'+transcript.map(function(segment,index){
        var time=Number(segment.start),timed=aligned&&Number.isFinite(time);
        return '<div class="jlpt-practice-segment'+(timed?'':' jlpt-practice-segment--plain')+'"'+(timed?' data-time="'+time+'"':'')+'>'+
          (timed?'<button type="button" class="jlpt-practice-segment__time" data-segment="'+index+'" aria-label="Nghe từ '+formatTime(Math.max(0,time-start))+'">'+formatTime(Math.max(0,time-start))+'</button>':'')+
          '<div class="jlpt-practice-segment__text"><p lang="ja">'+safeHtml(segment.ja||'')+'</p>'+(segment.vi?'<small lang="vi" hidden>'+esc(segment.vi)+'</small>':'')+'</div></div>';
      }).join('')+'</div>':'');
    var player=$('practiceAudioPlayer'),range=$('practiceAudioRange'),timeLabel=$('practiceAudioTime'),play=$('practiceAudioPlay');
    var panel=$('practiceTranscript'),scriptButton=$('practiceTranscriptToggle'),translationButton=$('practiceTranslationToggle');
    var translationOpen=false;
    function update(){
      var total=duration==null&&Number.isFinite(player.duration)?Math.max(0,player.duration-start):duration;
      var elapsed=Math.max(0,player.currentTime-start);
      if(total!=null){range.max=String(total);range.value=String(Math.min(elapsed,total));timeLabel.textContent=formatTime(Math.min(elapsed,total))+' / '+formatTime(total);}
      if(panel&&aligned)Array.from(panel.children).forEach(function(row,index){
        var from=Number(row.dataset.time),next=transcript[index+1],until=next?Number(next.start):(end==null?Infinity:end);
        row.classList.toggle('is-active',row.hasAttribute('data-time')&&player.currentTime>=from&&player.currentTime<until);
      });
    }
    function setScript(open){panel.hidden=!open;scriptButton.setAttribute('aria-expanded',String(open));scriptButton.lastElementChild.textContent=open?'Ẩn script':'Script';update();}
    function place(){if(player.currentTime<start||(end!=null&&player.currentTime>=end))player.currentTime=start;}
    player.addEventListener('loadedmetadata',function(){place();update();},{once:true});
    player.addEventListener('play',function(){place();play.innerHTML=symbol('pause');play.setAttribute('aria-label','Tạm dừng đoạn nghe');});
    player.addEventListener('pause',function(){play.innerHTML=symbol('play_arrow');play.setAttribute('aria-label','Phát đoạn nghe');});
    player.addEventListener('timeupdate',function(){if(end!=null&&player.currentTime>=end){player.pause();player.currentTime=end;}update();});
    play.addEventListener('click',function(){if(player.paused){place();player.play().catch(function(){timeLabel.textContent='Không tải được âm thanh';});}else player.pause();});
    range.addEventListener('input',function(){player.currentTime=start+Number(range.value);update();});
    if(scriptButton)scriptButton.addEventListener('click',function(){setScript(panel.hidden);});
    if(translationButton)translationButton.addEventListener('click',function(){
      translationOpen=!translationOpen;translationButton.setAttribute('aria-pressed',String(translationOpen));translationButton.lastElementChild.textContent=translationOpen?'Ẩn dịch':'Dịch';
      panel.querySelectorAll('small[lang="vi"]').forEach(function(row){row.hidden=!translationOpen;});setScript(true);
    });
    if(panel)panel.addEventListener('click',function(event){var button=event.target.closest('[data-segment]');if(!button)return;var target=Number(transcript[Number(button.dataset.segment)].start);player.currentTime=Math.max(start,end==null?target:Math.min(end,target));update();});
  }

  function formatTime(value){var seconds=Math.max(0,Math.floor(Number(value)||0));return String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');}
  function usesSentenceOrder(question){return window.JlptSentenceOrder&&JlptSentenceOrder.enabled(question);}
  function renderSentenceOrder(question,answer){
    var order=answer.orderState||(answer.orderState=JlptSentenceOrder.create());
    state.answers[question.id]=answer;
    JlptSentenceOrder.render($('practiceChoices'),question,order,{
      content:function(option){return safeHtml(option.html||esc(option.text||'')).replace(/^\s*\d+\s*[.)．。]\s*/,'');},
      change:function(){renderQuestion();}
    });
  }
  function isAudioOnlyQuestion(question){return ['LISTENING_GENERAL_OUTLINE','LISTENING_VERBAL_EXPRESSION','LISTENING_QUICK_RESPONSE'].includes(question.typeCode);}
  function renderQuestion(){var q=current(),answer=state.answers[q.id]||{},hideText=isAudioOnlyQuestion(q)&&!answer.checked;$('practiceTypeLabel').textContent=sectionLabels[q.section]+' · '+q.typeName;$('practiceProgress').textContent='Câu '+(state.index+1)+' / '+state.questions.length;$('practiceProgressTrack').setAttribute('aria-valuenow',Math.round((state.index+1)/state.questions.length*100));$('practiceProgressBar').style.width=((state.index+1)/state.questions.length*100)+'%';$('practiceSource').textContent=q.examCode+' · Câu '+q.number;$('practiceClipBadge').hidden=!(q.audioClip&&q.audioClip.type==='INFERRED');$('practiceInstruction').innerHTML=safeHtml(q.instructionHtml||'');$('practiceInstruction').hidden=!q.instructionHtml;var passage=$('practicePassage');passage.hidden=!q.passage||isAudioOnlyQuestion(q);passage.innerHTML=!passage.hidden?safeHtml(q.passage.html||esc(q.passage.ja||'')):'';renderAudio(q);$('practicePrompt').innerHTML=(hideText?'':safeHtml(q.html||esc(q.text||'')))+(q.imageUrl?'<div><img src="'+esc(q.imageUrl)+'" alt="Hình câu hỏi"></div>':'');$('practiceChoices').innerHTML=(q.options||[]).map(function(option){var checked=Number(answer.selected)===Number(option.number),classes='jlpt-practice-choice';if(answer.checked&&Number(option.number)===Number(q.correctOption))classes+=' is-correct';if(answer.checked&&checked&&Number(option.number)!==Number(q.correctOption))classes+=' is-wrong';return '<label class="'+classes+'"><input type="radio" name="practiceChoice" value="'+option.number+'" '+(checked?'checked':'')+' '+(answer.checked?'disabled':'')+'><b>'+option.number+'</b><span>'+(hideText?'':safeHtml(option.html||esc(option.text||'')))+'</span></label>';}).join('');if(usesSentenceOrder(q))renderSentenceOrder(q,answer);$('practiceCheck').textContent=usesSentenceOrder(q)?'Đáp án':'Kiểm tra';$('practiceFeedback').textContent=answer.checked?(usesSentenceOrder(q)?'Đúng '+JlptSentenceOrder.check(q,answer.orderState).matches.filter(Boolean).length+'/4 vị trí.':(Number(answer.selected)===Number(q.correctOption)?'Chính xác!':'Đáp án đúng: '+q.correctOption)):'';$('practiceCheck').hidden=!!answer.checked;$('practiceExplain').hidden=!answer.checked;$('practiceExplain').textContent='✦ Giải thích AI';$('practiceExplanation').hidden=!answer.explanation;$('practiceExplanation').textContent=answer.explanation||'';$('practicePrevious').disabled=state.index===0;$('practiceNext').textContent=state.index===state.questions.length-1?'Xem kết quả':'Câu tiếp';}
  function check(){var q=current(),answer=state.answers[q.id]||{};
    if(answer.checked)return;
    if(usesSentenceOrder(q)){
      var order=answer.orderState||(answer.orderState=JlptSentenceOrder.create()),result=JlptSentenceOrder.check(q,order);
      if(result.error){$('practiceFeedback').textContent=result.error;return;}
      order.checked=true;answer.selected=result.selectedStar;answer.checked=true;answer.correct=result.correct;state.answers[q.id]=answer;
      if(result.correct)state.score++;renderQuestion();return;
    }
    var selected=document.querySelector('input[name="practiceChoice"]:checked');if(!selected){$('practiceFeedback').textContent='Hãy chọn một đáp án.';return;}var value=Number(selected.value),correct=value===Number(q.correctOption);state.answers[q.id]={selected:value,checked:true,correct:correct};if(correct)state.score++;renderQuestion();}
  function explain(){var q=current(),answer=state.answers[q.id];$('practiceExplain').disabled=true;$('practiceExplain').textContent='Đang phân tích…';fetchData('/api/jlpt/explanations',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({questionId:q.id,selectedChoice:answer.selected})}).then(function(data){answer.explanation=String(data.explanation||'').replace(/\*\*/g,'');renderQuestion();}).catch(function(error){answer.explanation=error.message;renderQuestion();}).finally(function(){$('practiceExplain').disabled=false;});}
  function finish(){pauseAudio();var checked=Object.values(state.answers).filter(function(a){return a.checked;}).length;$('practiceResultDetail').textContent='Đã kiểm tra '+checked+' câu · '+(state.questions.length-checked)+' câu chưa kiểm tra';$('practiceRunner').hidden=true;$('practiceResult').hidden=false;$('practiceScore').textContent=state.score+' / '+state.questions.length;var rate=Math.round(state.score/state.questions.length*100);$('practiceScore').parentElement.style.setProperty('--jp-score',rate+'%');$('practiceResultText').textContent=rate>=80?'Bạn đang nắm khá chắc dạng này.':rate>=50?'Hãy luyện thêm một lượt để củng cố.':'Nên xem lại giải thích và luyện lại dạng này.';window.scrollTo({top:0,behavior:'smooth'});}
  function leaveRunner(){pauseAudio();$('practiceRunner').hidden=true;$('practiceResult').hidden=true;$('practiceSetup').hidden=false;window.scrollTo({top:0,behavior:'smooth'});}
  $('practiceLevels').addEventListener('click',function(event){var button=event.target.closest('[data-level]');if(!button)return;state.level=button.dataset.level;renderLevels();loadTypes();});
  $('practiceSections').addEventListener('click',function(event){var button=event.target.closest('[data-section]');if(!button)return;state.section=button.dataset.section;renderSections();});
  $('practiceTypes').addEventListener('click',function(event){var button=event.target.closest('[data-type]');if(!button)return;state.type=button.dataset.type;renderTypes();});
  $('practiceStart').addEventListener('click',start);$('practiceCheck').addEventListener('click',check);$('practiceExplain').addEventListener('click',explain);$('practicePrevious').addEventListener('click',function(){if(state.index>0){state.index--;renderQuestion();}});$('practiceNext').addEventListener('click',function(){if(state.index===state.questions.length-1)finish();else{state.index++;renderQuestion();}});$('practiceExit').addEventListener('click',leaveRunner);$('practiceBackSetup').addEventListener('click',leaveRunner);$('practiceRetry').addEventListener('click',start);
  $('practiceLimit').addEventListener('change',updateSummary);
  renderLevels();loadTypes();
})();
