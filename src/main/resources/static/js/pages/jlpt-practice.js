(function(){'use strict';
  var levels=['N5','N4','N3','N2','N1'];
  var sectionLabels={vocabulary:'Từ vựng',grammar:'Ngữ pháp',reading:'Đọc hiểu',listening:'Nghe hiểu'};
  var state={level:'N3',section:null,type:null,types:[],questions:[],index:0,answers:{},marked:{},paletteFilter:'all',score:0};
  function $(id){return document.getElementById(id);}
  function esc(value){return String(value==null?'':value).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function safeHtml(value){var template=document.createElement('template');template.innerHTML=String(value||'');template.content.querySelectorAll('script,style,iframe,object,embed').forEach(function(node){node.remove();});template.content.querySelectorAll('*').forEach(function(node){Array.from(node.attributes).forEach(function(attribute){if(/^on/i.test(attribute.name)||/^javascript:/i.test(attribute.value))node.removeAttribute(attribute.name);});});return template.innerHTML;}
  function transcriptWithoutSpeaker(value) {
    return String(value || '').replace(/^\s*(?:narrator|man|woman|người dẫn|nam|nữ|ナレーター|男|女)\s*\d*\s*[:：]\s*/i, '');
  }

  function transcriptTextHtml(value) {
    return String(value || '').split(/\r?\n/).map(function (line) {
      return safeHtml(transcriptWithoutSpeaker(line));
    }).join('<br>');
  }

  function transcriptTurns(value){var speaker=/^\s*(?:narrator|man|woman|người dẫn|nam|nữ|ナレーター|男|女)\s*\d*\s*[:：]/i;return String(value||'').split(/\r?\n/).reduce(function(turns,line){var clean=line.trim();if(!clean)return turns;if(!turns.length||speaker.test(clean))turns.push(clean);else turns[turns.length-1]+=' '+clean;return turns;},[]);}
  function transcriptBilingualHtml(japanese,vietnamese,showTranslation){if(!showTranslation||!String(vietnamese||'').trim())return transcriptTextHtml(japanese);var ja=transcriptTurns(japanese),vi=transcriptTurns(vietnamese),rows=[];for(var index=0;index<Math.max(ja.length,vi.length);index++)rows.push('<span class="jlpt-practice-transcript-line">'+(ja[index]?transcriptTextHtml(ja[index]):'')+(vi[index]?'<small lang="vi">'+esc(transcriptWithoutSpeaker(vi[index]))+'</small>':'')+'</span>');return rows.join('');}
  function usageTarget(question){if(String(question.typeCode||'').toUpperCase()!=='USAGE')return '';var box=document.createElement('div');box.innerHTML=safeHtml(question.html||question.text||'');return box.textContent.replace(/^\s*\d+\s*[.)．。]\s*/,'').trim();}
  function highlightUsageTarget(question,html){var word=usageTarget(question);if(!word||word.length<2)return html;var escaped=word.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'),pattern=word.endsWith('する')?new RegExp(escaped.slice(0,-2)+'(?:する|した|して|しない|します|された|される)'):word.endsWith('る')?new RegExp(escaped.slice(0,-1)+'(?:る|った|って|らない|られ|り|れ|ろ|ります|れば)'):new RegExp(escaped),box=document.createElement('div');box.innerHTML=html;var walker=document.createTreeWalker(box,NodeFilter.SHOW_TEXT),node;while(node=walker.nextNode()){if(node.parentElement&&node.parentElement.closest('u'))continue;var match=node.nodeValue.match(pattern);if(!match)continue;var fragment=document.createDocumentFragment(),term=document.createElement('u');fragment.append(document.createTextNode(node.nodeValue.slice(0,match.index)));term.className='jlpt-usage-term';term.textContent=match[0];fragment.append(term,document.createTextNode(node.nodeValue.slice(match.index+match[0].length)));node.replaceWith(fragment);break;}return box.innerHTML;}
  function fetchData(url,options){return fetch(url,options).then(function(response){return response.json().then(function(body){if(!response.ok||body.success===false)throw new Error(body.message||'Không tải được dữ liệu.');return body.data;});});}
  function updateSummary(){var type=state.types.find(function(t){return t.code===state.type&&t.section===state.section;});$('practiceSummaryLevel').textContent=state.level;$('practiceSummaryType').textContent=type?type.nameVi:'Chọn dạng câu hỏi';$('practiceSummaryCount').textContent=document.querySelector('#practiceLimit input:checked').value+' câu';}
  function pauseAudio(){var player=$('practiceAudioPlayer');if(player)player.pause();}
  function setFurigana(enabled){$('practiceRunner').classList.toggle('is-furigana-on',enabled);var button=$('practiceFurigana');button.classList.toggle('is-active',enabled);button.setAttribute('aria-pressed',String(enabled));button.setAttribute('aria-label',enabled?'Tắt Furigana':'Bật Furigana');}
  function renderLevels(){$('practiceLevels').innerHTML=levels.map(function(level){return '<button type="button" data-level="'+level+'" aria-pressed="'+(level===state.level)+'" class="'+(level===state.level?'is-active':'')+'">'+level+'</button>';}).join('');}
  function availableSections(){return Object.keys(sectionLabels).filter(function(section){return state.types.some(function(type){return type.section===section;});});}
  function renderSections(){var sections=availableSections();if(!sections.includes(state.section))state.section=sections[0]||null;$('practiceSections').innerHTML=sections.map(function(section){return '<button type="button" data-section="'+section+'" aria-pressed="'+(section===state.section)+'" class="'+(section===state.section?'is-active':'')+'">'+'<i class="jp-skill-icon fa-solid fa-'+({vocabulary:'book-open',grammar:'puzzle-piece',reading:'file-lines',listening:'headphones'}[section])+'" aria-hidden="true"></i>'+sectionLabels[section]+'</button>';}).join('');renderTypes();}
  function renderTypes(){var visible=state.types.filter(function(type){return type.section===state.section;});if(!visible.some(function(type){return type.code===state.type;}))state.type=visible.length?visible[0].code:null;$('practiceTypes').innerHTML=visible.map(function(type){return '<button type="button" class="jlpt-practice-type '+(type.code===state.type?'is-active':'')+'" data-type="'+esc(type.code)+'" aria-pressed="'+(type.code===state.type)+'"><strong>'+esc(type.nameVi)+'</strong><small>'+(type.nameJa?esc(type.nameJa):esc(type.code))+'</small></button>';}).join('')||'<p>Chưa có dạng câu hỏi cho nhóm này.</p>';$('practiceTypeCount').textContent=visible.length?visible.length+' dạng':'';$('practiceStart').disabled=!state.type;updateSummary();}
  // Metadata is shared within this page, including requests already being prefetched.
  var typeCatalogs=Object.create(null),typeRequests=Object.create(null),typeLoadVersion=0;
  function hasTypeCatalog(level){return Object.prototype.hasOwnProperty.call(typeCatalogs,level);}
  function requestTypes(level){
    if(hasTypeCatalog(level))return Promise.resolve(typeCatalogs[level]);
    if(typeRequests[level])return typeRequests[level];
    typeRequests[level]=fetchData('/api/jlpt/practice/types?level='+level).then(function(types){
      if(!Array.isArray(types))throw new Error('Không tải được danh sách dạng câu hỏi. Hãy chọn lại cấp độ để thử.');
      typeCatalogs[level]=types;
      return types;
    }).finally(function(){delete typeRequests[level];});
    return typeRequests[level];
  }
  function setTypesBusy(busy){
    state.typesLoading=busy;
    $('practiceTypes').setAttribute('aria-busy',String(busy));
    $('practiceSections').inert=busy;
    $('practiceTypes').inert=busy;
    $('practiceStart').disabled=busy||!state.type;
  }
  function applyTypes(level,types){
    state.types=types;
    state.typesLevel=level;
    setTypesBusy(false);
    renderSections();
  }
  function loadTypes(){
    var requestedLevel=state.level,version=++typeLoadVersion;
    $('practiceSetupNotice').hidden=true;
    if(hasTypeCatalog(requestedLevel)){applyTypes(requestedLevel,typeCatalogs[requestedLevel]);return;}
    setTypesBusy(true);
    // Only the initial visit needs placeholders; level changes keep the rendered choices stable.
    if(!state.typesLevel){
      $('practiceTypeCount').textContent='';
      $('practiceSections').innerHTML='<span class="jp-skill-placeholder" aria-hidden="true"></span>'.repeat(4);
      $('practiceTypes').innerHTML='<div class="jp-type-placeholder" aria-hidden="true"><i></i><i></i></div>'.repeat(4);
    }
    requestTypes(requestedLevel).then(function(types){
      if(version!==typeLoadVersion)return;
      applyTypes(requestedLevel,types);
    }).catch(function(error){
      if(version!==typeLoadVersion)return;
      // Restore the level matching the still-visible choices if the new request fails.
      if(state.typesLevel){state.level=state.typesLevel;renderLevels();updateSummary();}
      else{$('practiceSections').innerHTML='';$('practiceTypes').innerHTML='';}
      setTypesBusy(false);
      showNotice(error.message);
    });
  }
  function prefetchTypes(){levels.forEach(function(level){requestTypes(level).catch(function(){});});}
  function showNotice(message){$('practiceSetupNotice').textContent=message;$('practiceSetupNotice').hidden=false;}
  function start(){if(!state.type||state.starting||state.typesLoading)return;state.starting=true;$('practiceSetup').inert=true;$('practiceRetry').disabled=true;$('practiceSetupNotice').hidden=true;$('practiceStart').disabled=true;$('practiceStart').textContent='Đang tạo bộ câu…';var url='/api/jlpt/practice/questions?level='+state.level+'&section='+state.section+'&type='+encodeURIComponent(state.type)+'&limit='+document.querySelector('#practiceLimit input:checked').value;fetchData(url).then(function(questions){if(!questions||!questions.length)throw new Error('Không tìm thấy câu hỏi phù hợp.');pauseAudio();$('practiceAudio').innerHTML='';delete $('practiceAudio').dataset.audioKey;state.questions=questions;state.index=0;state.answers={};state.marked={};state.paletteFilter='all';state.score=0;setFurigana(false);$('practiceSetup').hidden=true;$('practiceResult').hidden=true;$('practiceRunner').hidden=false;renderQuestion();window.scrollTo({top:0,behavior:'smooth'});}).catch(function(error){leaveRunner();showNotice(error.message);}).finally(function(){state.starting=false;$('practiceSetup').inert=false;$('practiceRetry').disabled=false;$('practiceStart').disabled=!state.type;$('practiceStart').innerHTML='Bắt đầu luyện →';});}
  function current(){return state.questions[state.index];}
  function questionTranscript(question){var clip=question.audioClip;if(clip&&clip.transcript&&clip.transcript.length)return clip.transcript;return question.transcript||[];}
  function renderAudio(question) {
    var box=$('practiceAudio'),clip=question.audioClip,url=clip&&clip.audioUrl||question.mondaiAudioUrl;
    if(!url){box.hidden=true;box.innerHTML='';delete box.dataset.audioKey;return;}
    var transcript=questionTranscript(question),aligned=!!clip&&clip.transcriptAligned!==false;
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
          '<div class="jlpt-practice-segment__text"><p lang="ja" data-ja="'+esc(segment.ja||'')+'" data-vi="'+esc(segment.vi||'')+'">'+transcriptBilingualHtml(segment.ja||'',segment.vi||'',false)+'</p></div></div>';
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
      panel.querySelectorAll('.jlpt-practice-segment__text p').forEach(function(row){row.innerHTML=transcriptBilingualHtml(row.dataset.ja||'',row.dataset.vi||'',translationOpen);});setScript(true);
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
  function renderPracticePalette(){
    var checked=Object.values(state.answers).filter(function(answer){return answer.checked;}).length;
    $('practiceAnswered').textContent=checked+' / '+state.questions.length;
    $('practicePaletteFilters').querySelectorAll('[data-practice-filter]').forEach(function(button){
      button.setAttribute('aria-pressed',String(button.dataset.practiceFilter===state.paletteFilter));
    });
    var buttons=state.questions.map(function(question,index){
      var answered=state.answers[question.id]&&state.answers[question.id].checked;
      var marked=!!state.marked[question.id];
      if(state.paletteFilter==='unanswered'&&answered||state.paletteFilter==='marked'&&!marked)return '';
      return '<button type="button" data-practice-index="'+index+'" class="'+(answered?'is-answered ':'')+(marked?'is-marked ':'')+(index===state.index?'is-current':'')+'"'+(index===state.index?' aria-current="true"':'')+' aria-label="Câu '+(index+1)+(answered?', đã kiểm tra':'')+(marked?', đã đánh dấu':'')+'">'+(index+1)+'</button>';
    }).join('');
    $('practicePalette').innerHTML=buttons||'<p class="jp-palette-empty">'+(state.paletteFilter==='marked'?'Chưa có câu đánh dấu.':'Đã làm hết các câu.')+'</p>';
    var marked=!!state.marked[current().id];
    $('practiceMark').setAttribute('aria-pressed',String(marked));
    $('practiceMark').querySelector('span').textContent=marked?'Bỏ đánh dấu':'Đánh dấu';
  }
  function goToPracticeQuestion(index){
    if(!Number.isInteger(index)||index<0||index>=state.questions.length)return;
    state.index=index;renderQuestion();window.scrollTo({top:0,left:0,behavior:'instant'});
  }
  function renderQuestion(){var q=current(),answer=state.answers[q.id]||{},hideText=isAudioOnlyQuestion(q)&&!answer.checked;$('practiceTypeLabel').textContent=sectionLabels[q.section]+' · '+q.typeName;$('practiceProgress').textContent='Câu '+(state.index+1)+' / '+state.questions.length;$('practiceSource').textContent=q.examCode+' · Câu '+q.number;$('practiceClipBadge').hidden=!(q.audioClip&&q.audioClip.type==='INFERRED');$('practiceInstruction').innerHTML=safeHtml(q.instructionHtml||'');$('practiceInstruction').hidden=!q.instructionHtml;var passage=$('practicePassage');passage.hidden=!q.passage||isAudioOnlyQuestion(q);passage.innerHTML=!passage.hidden?safeHtml(q.passage.html||esc(q.passage.ja||'')):'';renderAudio(q);$('practicePrompt').innerHTML=(hideText?'':safeHtml(q.html||esc(q.text||'')))+(q.imageUrl?'<div><img src="'+esc(q.imageUrl)+'" alt="Hình câu hỏi"></div>':'');$('practiceChoices').innerHTML=(q.options||[]).map(function(option){var checked=Number(answer.selected)===Number(option.number),classes='jlpt-practice-choice';if(answer.checked&&Number(option.number)===Number(q.correctOption))classes+=' is-correct';if(answer.checked&&checked&&Number(option.number)!==Number(q.correctOption))classes+=' is-wrong';return '<label class="'+classes+'"><input type="radio" name="practiceChoice" value="'+option.number+'" '+(checked?'checked':'')+' '+(answer.checked?'disabled':'')+'><b>'+option.number+'</b><span>'+(hideText?'':highlightUsageTarget(q,safeHtml(option.html||esc(option.text||''))))+'</span></label>';}).join('');if(usesSentenceOrder(q))renderSentenceOrder(q,answer);$('practiceCheck').textContent=usesSentenceOrder(q)?'Đáp án':'Kiểm tra';$('practiceFeedback').textContent=answer.checked?(usesSentenceOrder(q)?'Đúng '+JlptSentenceOrder.check(q,answer.orderState).matches.filter(Boolean).length+'/4 vị trí.':(Number(answer.selected)===Number(q.correctOption)?'Chính xác!':'')):'';$('practiceCheck').hidden=!!answer.checked;$('practiceExplain').hidden=!answer.checked;$('practiceExplain').textContent='✦ Giải thích AI';$('practiceExplanation').hidden=!answer.explanation;$('practiceExplanationText').textContent=answer.explanation||'';$('practicePrevious').disabled=state.index===0;$('practiceNext').textContent=state.index===state.questions.length-1?'Xem kết quả':'Câu tiếp';renderPracticePalette();if(window.LearningReport){$('practiceReport').innerHTML=window.LearningReport.buttonHtml('practice-question',q.id,'Báo cáo câu hỏi');$('practiceAiReport').hidden=!(answer.explanation&&answer.aiGenerated);$('practiceAiReport').innerHTML=answer.explanation&&answer.aiGenerated?window.LearningReport.buttonHtml('practice-ai',q.id,'Báo cáo giải thích AI'):'';}}
  function check(){var q=current(),answer=state.answers[q.id]||{};
    if(answer.checked)return;
    if(usesSentenceOrder(q)){
      var order=answer.orderState||(answer.orderState=JlptSentenceOrder.create()),result=JlptSentenceOrder.check(q,order);
      if(result.error){$('practiceFeedback').textContent=result.error;return;}
      order.checked=true;answer.selected=result.selectedStar;answer.checked=true;answer.correct=result.correct;state.answers[q.id]=answer;
      if(result.correct)state.score++;renderQuestion();return;
    }
    var selected=document.querySelector('input[name="practiceChoice"]:checked');if(!selected){$('practiceFeedback').textContent='Hãy chọn một đáp án.';return;}var value=Number(selected.value),correct=value===Number(q.correctOption);state.answers[q.id]={selected:value,checked:true,correct:correct};if(correct)state.score++;else if(q.section==='vocabulary')fetchData('/api/jlpt/practice/wrong-vocabulary',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({questionId:q.id,selectedOption:value})}).catch(function(){});renderQuestion();}
  function explain(){var q=current(),answer=state.answers[q.id];$('practiceExplain').disabled=true;$('practiceExplain').textContent='Đang phân tích…';fetchData('/api/jlpt/explanations',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({questionId:q.id,selectedChoice:answer.selected})}).then(function(data){answer.aiGenerated=!!data.aiGenerated;answer.explanation=String(data.explanation||'').replace(/\*\*/g,'');renderQuestion();}).catch(function(error){answer.aiGenerated=false;answer.explanation=error.message;renderQuestion();}).finally(function(){$('practiceExplain').disabled=false;});}
  function finish(){pauseAudio();var checked=Object.values(state.answers).filter(function(a){return a.checked;}).length;$('practiceResultDetail').textContent='Đã kiểm tra '+checked+' câu · '+(state.questions.length-checked)+' câu chưa kiểm tra';$('practiceRunner').hidden=true;$('practiceResult').hidden=false;$('practiceScore').textContent=state.score+' / '+state.questions.length;var rate=Math.round(state.score/state.questions.length*100);$('practiceScore').parentElement.style.setProperty('--jp-score',rate+'%');$('practiceResultText').textContent=rate>=80?'Bạn đang nắm khá chắc dạng này.':rate>=50?'Hãy luyện thêm một lượt để củng cố.':'Nên xem lại giải thích và luyện lại dạng này.';window.scrollTo({top:0,behavior:'smooth'});}
  function leaveRunner(){pauseAudio();$('practiceRunner').hidden=true;$('practiceResult').hidden=true;$('practiceSetup').hidden=false;window.scrollTo({top:0,behavior:'smooth'});}
  $('practiceLevels').addEventListener('click',function(event){var button=event.target.closest('[data-level]');if(!button)return;state.level=button.dataset.level;renderLevels();loadTypes();});
  $('practiceSections').addEventListener('click',function(event){if(state.typesLoading)return;var button=event.target.closest('[data-section]');if(!button)return;state.section=button.dataset.section;renderSections();});
  $('practiceTypes').addEventListener('click',function(event){if(state.typesLoading)return;var button=event.target.closest('[data-type]');if(!button)return;state.type=button.dataset.type;renderTypes();});
  $('practiceStart').addEventListener('click',start);$('practiceCheck').addEventListener('click',check);$('practiceExplain').addEventListener('click',explain);$('practicePrevious').addEventListener('click',function(){if(state.index>0)goToPracticeQuestion(state.index-1);});$('practiceNext').addEventListener('click',function(){if(state.index===state.questions.length-1)finish();else goToPracticeQuestion(state.index+1);});$('practiceBackSetup').addEventListener('click',leaveRunner);$('practiceRetry').addEventListener('click',start);
  $('practicePaletteFilters').addEventListener('click',function(event){
    var button=event.target.closest('[data-practice-filter]');
    if(!button||!state.questions.length)return;
    state.paletteFilter=button.dataset.practiceFilter;renderPracticePalette();
  });
  $('practiceMark').addEventListener('click',function(){
    if(!state.questions.length)return;
    var id=current().id;state.marked[id]=!state.marked[id];renderPracticePalette();
  });
  $('practicePalette').addEventListener('click',function(event){var button=event.target.closest('[data-practice-index]');if(button)goToPracticeQuestion(Number(button.dataset.practiceIndex));});
  $('practiceLimit').addEventListener('change',updateSummary);
  $('practiceFurigana').addEventListener('click',function(){setFurigana(!$('practiceRunner').classList.contains('is-furigana-on'));});
  setFurigana(false);renderLevels();loadTypes();prefetchTypes();
  if(window.LearningReport){
    window.LearningReport.register('practice-question',function(id){var q=state.questions.find(function(item){return String(item.id)===id;});return q?{targetType:'JLPT_QUESTION',page:'JLPT_PRACTICE',questionId:String(q.id),label:q.examCode+' · Câu '+q.number}:null;});
    window.LearningReport.register('practice-ai',function(id){var q=state.questions.find(function(item){return String(item.id)===id;}),a=state.answers[id];return q&&a&&a.aiGenerated&&a.explanation?{targetType:'AI_EXPLANATION',page:'JLPT_PRACTICE',questionId:String(q.id),explanation:a.explanation,label:q.examCode+' · Câu '+q.number}:null;});
  }
})();
