(function(){
  'use strict';
  var state={page:0,pages:0,selected:null,revision:0,saving:false};
  var types={JLPT_QUESTION:'Câu hỏi JLPT',VERB_QUESTION:'Luyện chia thể',KANJI_MAP:'Lược đồ Kanji',AI_EXPLANATION:'Giải thích AI'};
  var reasons={CONTENT:'Nội dung chưa chính xác',ANSWER:'Đáp án / cách chia / cấu tạo',AUDIO:'Âm thanh',AI:'Giải thích AI',OTHER:'Lý do khác'};
  var statuses={OPEN:'Chờ xử lý',RESOLVED:'Đã xử lý',REJECTED:'Đã từ chối'};
  function $(id){return document.getElementById(id);}
  function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function notice(text){$('lcrNotice').textContent=text||'';$('lcrNotice').hidden=!text;}
  async function request(path,body){
    var headers=Object.assign({},window.JlasAuth.authHeaders());if(body)headers['Content-Type']='application/json';
    var response=await fetch('/api/admin/learning-content-reports'+path,{method:body?'PATCH':'GET',headers:headers,body:body?JSON.stringify(body):undefined});
    var result=await response.json();if(!response.ok||result.success===false)throw Error(result.message||'Không tải được báo cáo.');return result.data;
  }
  function url(){return '?status='+encodeURIComponent($('lcrStatus').value)+'&page='+state.page+($('lcrType').value?'&type='+encodeURIComponent($('lcrType').value):'');}
  async function load(){
    var revision=++state.revision;notice('');$('lcrList').setAttribute('aria-busy','true');
    try{
      var data=await request(url());if(revision!==state.revision)return;
      state.pages=data.totalPages;$('lcrCount').textContent=data.totalElements+' báo cáo';
      $('lcrList').innerHTML=data.content.length?data.content.map(function(row){return'<button type="button" class="lcr-item" data-report-id="'+row.id+'" aria-pressed="'+(state.selected===row.id)+'"><small>'+esc(types[row.targetType])+' · #'+row.id+'</small><strong>'+esc(row.label)+'</strong><small>'+esc(reasons[row.reason])+' · '+esc(new Date(row.createdAt).toLocaleString('vi-VN'))+'</small></button>';}).join(''):'<p class="lcr-muted">Không có báo cáo ở bộ lọc này.</p>';
      $('lcrPrevious').disabled=state.page===0;$('lcrNext').disabled=state.page+1>=state.pages;$('lcrPage').textContent='Trang '+(state.page+1)+' / '+Math.max(1,state.pages);
    }catch(error){if(revision===state.revision)notice(error.message);}
    finally{if(revision===state.revision)$('lcrList').setAttribute('aria-busy','false');}
  }
  async function detail(id){
    state.selected=id;var revision=state.revision;
    try{
      var row=await request('/'+id);if(state.selected!==id||revision!==state.revision)return;
      var source=typeof row.sourceUrl==='string'&&/^\/(?!\/)/.test(row.sourceUrl)?row.sourceUrl:null;
      $('lcrDetail').innerHTML='<span class="lcr-muted">'+esc(types[row.targetType])+' · '+esc(statuses[row.status])+'</span><h2>'+esc(row.label)+'</h2><p>'+esc(reasons[row.reason])+' · Người gửi #'+row.reporterUserId+'</p>'+
        '<p class="lcr-note">'+esc(row.note||'Không có mô tả thêm.')+'</p>'+(source?'<a href="'+esc(source)+'" target="_blank" rel="noopener">Mở trang nội dung ↗</a>':'')+
        '<h3>Nội dung khi gửi báo cáo</h3><pre id="lcrSnapshot"></pre>'+
        (row.status==='OPEN'?'<label for="lcrReviewNote">Ghi chú xử lý</label><textarea id="lcrReviewNote" maxlength="1000"></textarea><div class="lcr-actions"><button type="button" class="lcr-resolve" data-resolve="RESOLVED">Đã xử lý</button><button type="button" data-resolve="REJECTED">Từ chối báo cáo</button></div>':'<p class="lcr-note">'+esc(row.reviewNote||'')+'</p>');
      $('lcrSnapshot').textContent=JSON.stringify(row.context,null,2);
      $('lcrList').querySelectorAll('[data-report-id]').forEach(function(button){button.setAttribute('aria-pressed',String(Number(button.dataset.reportId)===id));});
    }catch(error){if(state.selected===id)notice(error.message);}
  }
  async function resolve(status){
    if(state.saving||!state.selected)return;
    var id=state.selected,note=$('lcrReviewNote').value.trim();state.saving=true;
    $('lcrDetail').querySelectorAll('button,textarea').forEach(function(el){el.disabled=true;});
    try{await request('/'+id,{status:status,note:note||null});await load();if(state.selected===id)await detail(id);if(window.JlasUI)window.JlasUI.toast('Đã cập nhật báo cáo.','success');}
    catch(error){notice(error.message);}
    finally{state.saving=false;$('lcrDetail').querySelectorAll('button,textarea').forEach(function(el){el.disabled=false;});}
  }
  $('lcrFilters').addEventListener('submit',function(event){event.preventDefault();state.page=0;state.selected=null;load();});
  $('lcrPrevious').addEventListener('click',function(){if(state.page>0){state.page--;load();}});
  $('lcrNext').addEventListener('click',function(){if(state.page+1<state.pages){state.page++;load();}});
  $('lcrList').addEventListener('click',function(event){var button=event.target.closest('[data-report-id]');if(button)detail(Number(button.dataset.reportId));});
  $('lcrDetail').addEventListener('click',function(event){var button=event.target.closest('[data-resolve]');if(button)resolve(button.dataset.resolve);});
  Promise.resolve(window.AdminShell.ready).then(function(ready){if(ready){$('jlabBaseContent').hidden=false;load();}}).catch(function(error){notice(error.message);});
})();
