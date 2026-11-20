(function(root,factory){
  if(typeof module==='object'&&module.exports)module.exports=factory(root);
  else root.LearningReport=factory(root);
})(typeof window!=='undefined'?window:globalThis,function(root){
  'use strict';
  var providers=Object.create(null),active=null;
  var flag='<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 21V4m0 0c5-4 9 4 15 0v10c-6 4-10-4-15 0"/></svg>';
  function esc(value){return String(value==null?'':value).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function register(name,provider){if(!/^[a-z0-9-]+$/.test(name)||typeof provider!=='function')throw Error('Invalid report provider');providers[name]=provider;}
  function buttonHtml(provider,id,label){
    label=label||'Báo cáo nội dung';
    return '<button type="button" class="learning-report-flag" data-lr-provider="'+esc(provider)+'" data-lr-id="'+esc(id)+'" title="'+esc(label)+'" aria-label="'+esc(label)+'">'+flag+'</button>';
  }
  function reasons(type){
    if(type==='AI_EXPLANATION')return[
      ['AI','Giải thích chưa chính xác','Lập luận, bản dịch hoặc thông tin trong phản hồi AI chưa đúng.'],
      ['CONTENT','Không liên quan đến câu hỏi','Phản hồi chưa giải đáp đúng nội dung đang học.'],
      ['OTHER','Lý do khác','Hãy mô tả ngắn bên dưới.']
    ];
    var rows=[['CONTENT','Nội dung chưa chính xác',type==='KANJI_MAP'?'Nghĩa, âm đọc hoặc thông tin của chữ chưa đúng.':'Đề bài, từ vựng hoặc bản dịch có điểm chưa đúng.'],
      ['ANSWER',type==='KANJI_MAP'?'Cấu tạo hoặc nét viết chưa đúng':'Đáp án hoặc cách giải chưa đúng',type==='KANJI_MAP'?'Các thành phần, liên kết hoặc thứ tự nét cần xem lại.':'Đáp án, cách chia hoặc phần giải thích cần xem lại.']];
    if(type==='JLPT_QUESTION')rows.push(['AUDIO','Âm thanh gặp lỗi','Không phát được, sai đoạn hoặc lời thoại chưa khớp.']);
    rows.push(['OTHER','Lý do khác','Hãy mô tả ngắn bên dưới.']);return rows;
  }
  function buildPayload(context,reason,note){
    var pages={JLPT_QUESTION:['JLPT_EXAM','JLPT_PRACTICE'],AI_EXPLANATION:['JLPT_EXAM','JLPT_PRACTICE'],VERB_QUESTION:['VERB_PRACTICE'],KANJI_MAP:['KANJI_MAP']};
    if(!context||!pages[context.targetType]||pages[context.targetType].indexOf(context.page)<0)throw Error('Không xác định được nội dung cần báo cáo.');
    if(!reasons(context.targetType).some(function(row){return row[0]===reason;}))throw Error('Chọn lý do báo cáo.');
    var text=String(note||'').trim();
    if(reason==='OTHER'&&!text)throw Error('Vui lòng mô tả lý do khác.');
    if(text.length>1000)throw Error('Mô tả tối đa 1000 ký tự.');
    var out={targetType:context.targetType,page:context.page,reason:reason,note:text||null};
    ['questionId','sessionId','character','explanation'].forEach(function(key){if(context[key]!=null)out[key]=String(context[key]);});
    if(context.targetType==='KANJI_MAP'&&!out.character||context.targetType!=='KANJI_MAP'&&!out.questionId||context.targetType==='VERB_QUESTION'&&!out.sessionId)throw Error('Không xác định được nội dung cần báo cáo.');
    if(context.targetType==='AI_EXPLANATION'&&(!out.explanation||out.explanation.length>20000))throw Error('Không nhận được phần giải thích cần báo cáo.');
    return out;
  }
  async function sendReport(payload){
    var auth=root.JlasAuth;
    if(auth&&auth.ready)await auth.ready;
    if(!auth||!auth.isLoggedIn||!auth.isLoggedIn()){var login=Error('Vui lòng đăng nhập để gửi báo cáo.');login.login=true;throw login;}
    var headers=Object.assign({},auth.authHeaders(),{'Content-Type':'application/json'});
    var controller=new root.AbortController();
    var timer=root.setTimeout(function(){controller.abort();},15000);
    try{
      var response=await root.fetch('/api/learning-content-reports',{method:'POST',headers:headers,body:JSON.stringify(payload),signal:controller.signal});
      var result=await response.json();
      if(!response.ok||result.success===false){var error=Error(result.message||'Chưa gửi được báo cáo. Vui lòng thử lại.');error.status=response.status;error.login=response.status===401;throw error;}
      if(!result.data||!Number.isSafeInteger(Number(result.data.reportId))||Number(result.data.reportId)<=0)throw Error('Chưa xác nhận được báo cáo. Vui lòng thử lại.');
      return result.data;
    }catch(error){if(error.name==='AbortError')throw Error('Kết nối quá lâu. Vui lòng thử lại.');throw error;}
    finally{root.clearTimeout(timer);}
  }
  function open(context,opener){
    if(!context)return null;
    if(active){if(active.busy)return active.dialog;active.close();}
    // Snapshot the visible item now: moving to another question cannot change a pending report's target.
    var captured=Object.freeze(Object.assign({},context)),doc=root.document;
    var dialog=doc.createElement('dialog');dialog.id='learningReportDialog';dialog.className='learning-report-dialog';
    dialog.setAttribute('aria-labelledby','learningReportTitle');dialog.setAttribute('aria-describedby','learningReportDescription');
    var title=context.targetType==='AI_EXPLANATION'?'Báo cáo giải thích AI':context.targetType==='KANJI_MAP'?'Báo cáo thông tin lược đồ':'Báo cáo câu hỏi';
    dialog.innerHTML='<div class="lr-panel"><div class="lr-symbol">'+flag+'</div><h2 id="learningReportTitle">'+title+'</h2>'+
      '<p id="learningReportDescription">Báo cáo sẽ được gửi cho người biên soạn xem xét và kiểm tra lại nội dung.</p>'+
      '<form novalidate><fieldset class="lr-reasons"><legend class="lr-sr">Lý do báo cáo</legend>'+reasons(context.targetType).map(function(row){return'<label class="lr-option"><input type="radio" name="learningReportReason" value="'+row[0]+'" required><span><strong>'+row[1]+'</strong><small>'+row[2]+'</small></span></label>';}).join('')+'</fieldset>'+
      '<label class="lr-note-label" for="learningReportNote">Mô tả thêm <span>(bắt buộc nếu chọn “Lý do khác”)</span></label>'+
      '<textarea id="learningReportNote" maxlength="1000" rows="3" placeholder="Mô tả phần chưa đúng để người biên soạn kiểm tra…"></textarea>'+
      '<p class="lr-status" role="status" aria-live="polite" hidden></p><div class="lr-actions"><button type="button" data-lr-cancel>Hủy</button><button type="submit" class="lr-send">Gửi báo cáo</button></div></form></div>';
    var form=dialog.querySelector('form'),status=dialog.querySelector('.lr-status'),note=dialog.querySelector('textarea'),submit=dialog.querySelector('[type=submit]');
    var handle={dialog:dialog,busy:false,close:close};active=handle;
    function close(){if(handle.busy)return;dialog.close();}
    function busy(value){handle.busy=value;Array.from(form.querySelectorAll('input,textarea,button')).forEach(function(el){el.disabled=value;});submit.textContent=value?'Đang gửi…':'Gửi báo cáo';}
    function message(error){
      status.textContent=error.message;status.hidden=false;
      if(error.login){var link=doc.createElement('a');link.href='/login?redirect='+encodeURIComponent(root.location.pathname+root.location.search);link.textContent=' Đăng nhập';status.appendChild(link);}
    }
    dialog.addEventListener('close',function(){if(active===handle)active=null;dialog.remove();if(opener&&opener.isConnected&&opener.focus)opener.focus({preventScroll:true});});
    dialog.addEventListener('cancel',function(event){if(handle.busy)event.preventDefault();});
    // Keep practice shortcuts on the page from handling keys typed in this modal.
    ['keydown','keyup','keypress'].forEach(function(type){dialog.addEventListener(type,function(event){event.stopPropagation();});});
    dialog.addEventListener('click',function(event){if(event.target===dialog||event.target.closest('[data-lr-cancel]'))close();});
    form.addEventListener('change',function(){var selected=form.querySelector('input:checked');note.required=!!selected&&selected.value==='OTHER';status.hidden=true;});
    form.addEventListener('submit',async function(event){
      event.preventDefault();if(handle.busy)return;
      var selected=form.querySelector('input:checked'),payload;
      try{payload=buildPayload(captured,selected&&selected.value,note.value);}catch(error){message(error);if(selected&&selected.value==='OTHER')note.focus();return;}
      busy(true);status.hidden=true;
      try{await sendReport(payload);busy(false);close();if(root.JlasUI&&root.JlasUI.toast){var toast=root.JlasUI.toast('Đã gửi báo cáo. Cảm ơn bạn!','success');if(doc.fullscreenElement&&toast&&toast.parentElement)doc.fullscreenElement.appendChild(toast.parentElement);}}
      catch(error){busy(false);message(error);if(error.status===409||error.status===429)submit.disabled=true;}
    });
    (doc.fullscreenElement||doc.body).appendChild(dialog);dialog.showModal();
    var first=form.querySelector('input');if(first)first.focus({preventScroll:true});
    return dialog;
  }
  if(root.document)root.document.addEventListener('click',function(event){
    var button=event.target.closest&&event.target.closest('[data-lr-provider]');if(!button)return;
    var provider=providers[button.dataset.lrProvider];if(!provider)return;
    var context=provider(button.dataset.lrId);if(context)open(context,button);
  });
  return{register:register,buttonHtml:buttonHtml,open:open,buildPayload:buildPayload,reasons:reasons,sendReport:sendReport};
});
