/* Appearance adapters for the owned feature pages. No requests or business state changes. */
(function () {
  'use strict';
  if (!document.body.classList.contains('jlas-ui-owned')) return;
  var roots = 'main, dialog, .km-dialog, .notebook-dialog, .verb-dialog, .jlpt-mode-dialog';
  var icons = {
    search:'magnifying-glass',close:'xmark',add:'plus',remove:'minus',
    tune:'sliders',filter_list:'filter',filter_alt:'filter',menu_book:'book-open',
    book:'book',layers:'layer-group',style:'layer-group',translate:'language',
    timer:'stopwatch',schedule:'clock',history:'clock-rotate-left',calendar_month:'calendar',
    format_list_numbered:'list-ol',format_list_bulleted:'list-ul',list_alt:'list',
    play_arrow:'play',pause:'pause',replay:'rotate-left',refresh:'rotate-right',restart_alt:'rotate-left',
    volume_up:'volume-high',volume_off:'volume-xmark',mic:'microphone',
    edit:'pen',edit_note:'pen-to-square',delete:'trash',delete_outline:'trash',
    check:'check',check_circle:'circle-check',done:'check',done_all:'check-double',
    error:'circle-exclamation',warning:'triangle-exclamation',info:'circle-info',help:'circle-question',
    lock:'lock',lock_open:'unlock',visibility:'eye',visibility_off:'eye-slash',
    expand_more:'chevron-down',expand_less:'chevron-up',keyboard_arrow_down:'chevron-down',
    unfold_more:'sort',sort:'arrow-down-wide-short',swap_vert:'sort',
    star:'star',favorite:'heart',bookmark:'bookmark',bookmark_add:'bookmark',
    school:'graduation-cap',quiz:'list-check',task_alt:'circle-check',assessment:'chart-simple',
    bar_chart:'chart-simple',trending_up:'chart-line',insights:'chart-line',
    upload:'upload',upload_file:'file-arrow-up',file_upload:'file-arrow-up',download:'download',
    save:'floppy-disk',content_copy:'copy',content_paste:'paste',folder:'folder',folder_open:'folder-open',
    lightbulb:'lightbulb',auto_awesome:'wand-magic-sparkles',auto_stories:'book-open',
    fact_check:'list-check',description:'file-lines',article:'file-lines',note_alt:'note-sticky',
    library_books:'book',library_add:'plus',collections_bookmark:'book-bookmark',
    terminal:'keyboard',keyboard:'keyboard',speed:'gauge-high',bolt:'bolt',verified:'circle-check',
    error_outline:'circle-exclamation',cancel:'circle-xmark',check_circle_outline:'circle-check',
    keyboard_return:'turn-down',manage_search:'magnifying-glass',shuffle:'shuffle',
    ios_share:'share-nodes',topic:'folder',folder_copy:'folder',expand_circle_down:'circle-chevron-down',
    local_fire_department:'fire',emoji_events:'trophy',workspace_premium:'award',
    public:'globe',person:'user',group:'users',notifications:'bell',settings:'gear',
    more_horiz:'ellipsis',more_vert:'ellipsis-vertical',open_in_new:'up-right-from-square',
    fullscreen:'expand',fullscreen_exit:'compress',zoom_in:'magnifying-glass-plus',zoom_out:'magnifying-glass-minus',
    straighten:'ruler',draw:'pen',ink_pen:'pen',touch_app:'hand-pointer',format_size:'text-height'
  };
  var arrows = /^(arrow_(back|forward)(?:_ios)?|chevron_(left|right)|keyboard_arrow_(left|right)|navigate_(before|next)|[←→‹›«»])$/;
  var primary = '.vp-button.primary,.km-primary,.notebook-primary,.notebook-study-launch,.jlpt-admin-button.is-primary,.verb-button.is-primary,.jpta-button.is-primary,.kma-primary,.jlpt-exam-card__start,#practiceStart,#practiceCheck,#submitExam,#vpStart';
  var secondary = '.vp-button.secondary,.km-button,.kma-button,.jlpt-admin-button.is-secondary,.verb-button.is-secondary,.jpta-button.is-secondary';
  var danger = '.jlpt-admin-button.is-danger,.verb-button.is-danger,.kma-danger,#notebookDeleteConfirm';
  function decorate(element) {
    if (!element.closest(roots)) return;
    if (element.closest('.km-world,.km-mini-diagram')) return;
    if (element.matches('button,a')) {
      // Replace navigation glyphs only; Japanese explanations and diagram edges are content.
      var navIcon=element.querySelector('.fa-arrow-left,.fa-arrow-right,.fa-chevron-left,.fa-chevron-right,.fa-angle-left,.fa-angle-right');
      if(navIcon){navIcon.hidden=true;navIcon.style.display='none';}
      element.querySelectorAll('svg path[d="M4 12h16M14 6l6 6-6 6"]').forEach(function(path){var icon=path.closest('.vp-svg-icon');if(icon){icon.hidden=true;icon.style.display='none';}});
      var textNodes=document.createTreeWalker(element,NodeFilter.SHOW_TEXT),node;
      while((node=textNodes.nextNode())) {
        if(node.parentElement.closest('[lang=ja],.km-node,.km-world'))continue;
        var clean=node.nodeValue.replace(/[←→‹›«»↗]/g,'').trim();
        if(clean!==node.nodeValue.trim()) {
          var other=element.textContent.replace(/[←→‹›«»↗]/g,'').trim();
          var label=element.getAttribute('aria-label')||'';
          if(!other)element.classList.add('jl-nav-button');
          node.nodeValue=clean || (other?'':/quay|về|danh sách/i.test(label)?'Về':/trước/i.test(label)||/[←‹«]/.test(node.nodeValue)?'Trước':'Sau');
        }
      }
    }
    if (element.matches('.material-symbols-outlined')) {
      var name = element.textContent.trim();
      if (!name) return;
      if (arrows.test(name)) {
        var owner = element.closest('button,a');
        var text = owner && owner.textContent.replace(name,'').trim();
        if (text) { element.textContent=''; element.hidden=true; }
        else {
          element.textContent = /back/.test(name) ? 'Về' : /left|before|[←‹«]/.test(name) ? 'Trước' : 'Sau';
          element.classList.add('jl-nav-word');
        }
      } else if (icons[name]) {
        element.className = element.className.replace(/\bfa-[\w-]+\b/g,'').trim() + ' jl-fa fa-solid fa-' + icons[name];
        element.textContent='';
        element.setAttribute('aria-hidden','true');
      }
    }
    if (element.closest('.km-world,.km-mini-diagram'))return;
    if (element.matches(danger)) element.classList.add('btn','btn--danger');
    else if (element.matches(primary)) element.classList.add('btn','btn--primary');
    else if (element.matches(secondary)) element.classList.add('btn','btn--secondary');
    if (element.matches('input:not([type=checkbox],[type=radio],[type=range],[type=file],[type=hidden])')&&!element.closest('.jlpt-admin-search,.jpta__search,.verb-admin__search,.kma-search,.notebook-search')) element.classList.add('input');
    if (element.matches('textarea')) element.classList.add('textarea');
    if (element.matches('button') && element.disabled && /^Đang\s/.test(element.textContent.trim())) element.setAttribute('aria-busy','true');
    else if (element.matches('button[aria-busy=true]') && !element.disabled) element.removeAttribute('aria-busy');
  }
  function scan(root) {
    if (root.nodeType!==1) return;
    decorate(root);
    root.querySelectorAll('.material-symbols-outlined,button,input,textarea,a').forEach(decorate);
  }
  scan(document.body);
  // Existing renderers replace their content and update play/pause icon names in place.
  var pending = new Set(), scheduled = false;
  new MutationObserver(function(records) {
    records.forEach(function(record) {
      if (record.type==='attributes') pending.add(record.target);
      else {
        if (record.target.nodeType===1 && record.target.matches('.material-symbols-outlined,button,a')) pending.add(record.target);
        record.addedNodes.forEach(function(node){if(node.nodeType===1)pending.add(node);});
      }
    });
    if (scheduled || !pending.size) return;
    scheduled=true;
    requestAnimationFrame(function(){scheduled=false;var nodes=Array.from(pending);pending.clear();nodes.forEach(scan);});
  }).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled']});
})();
