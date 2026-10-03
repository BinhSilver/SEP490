(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var code = params.get('code') || 'N3_2025_12';
  var mode = params.get('mode') === 'exam' ? 'exam' : 'practice';
  var isExamMode = mode === 'exam';
  var exam = null;
  var partIndex = 0;
  var pageIndex = 0;
  var answers = {};
  var revealed = {};
  var sentenceOrders = {};
  var explanations = {};
  var furiganaCache = {};
  var marked = loadMarks();
  var explaining = {};
  var filterMode = 'all';
  var scriptOpen = false;
  var translationOpen = false;
  var furiganaOn = false;
  var pendingFocus = null;
  var completedParts = {};
  var deadline = null;
  var timerHandle = null;
  var examFinished = false;
  var submitIntent = 'part';
  var sessionKey = 'jlpt-exam-session:' + code;
  var $ = function (id) { return document.getElementById(id); };

  fetch('/api/jlpt/exams/' + encodeURIComponent(code))
    .then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || body.success === false) throw new Error(body.message || 'Không tải được đề.');
        return body.data;
      });
    })
    .then(function (data) { exam = data; init(); })
    .catch(function (error) { $('jlptNotice').textContent = error.message; });

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (char) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char];
    });
  }

  function safeHtml(value) {
    var template = document.createElement('template');
    var allowed = ['RUBY', 'RT', 'RP', 'U', 'BR', 'B', 'STRONG', 'EM', 'SPAN', 'P', 'DIV', 'H3', 'H4',
      'CENTER', 'HR', 'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TH', 'TD', 'CAPTION', 'COLGROUP', 'COL', 'IMG'];
    template.innerHTML = String(value || '');
    Array.from(template.content.querySelectorAll('*')).forEach(function (node) {
      if (allowed.indexOf(node.tagName) < 0) {
        node.replaceWith(document.createTextNode(node.textContent || ''));
        return;
      }
      Array.from(node.attributes).forEach(function (attribute) {
        var name = attribute.name.toLowerCase();
        if (node.tagName === 'IMG' && name === 'src' && /^(https?:\/\/|\/)/i.test(attribute.value)) return;
        if (node.tagName === 'IMG' && name === 'alt') return;
        if ((node.tagName === 'TH' || node.tagName === 'TD') && (name === 'colspan' || name === 'rowspan') && /^\d+$/.test(attribute.value)) return;
        node.removeAttribute(attribute.name);
      });
    });
    return template.innerHTML;
  }

  function displayQuestionHtml(question) {
    var wrapper = document.createElement('div');
    wrapper.innerHTML = safeHtml(question.html || esc(question.text));
    var walker = document.createTreeWalker(wrapper, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      if (!node.nodeValue.trim()) continue;
      node.nodeValue = node.nodeValue.replace(/^\s*\d+\s*[.)．。]\s*/, '');
      break;
    }
    return wrapper.innerHTML;
  }

  function currentPart() { return exam.parts[partIndex]; }

  function questionRows() {
    var list = [];
    currentPart().mondai.forEach(function (mondai) {
      mondai.questions.forEach(function (question) { list.push({ mondai: mondai, question: question }); });
    });
    return list;
  }

  function pages() {
    var result = [];
    var passagePages = {};
    currentPart().mondai.forEach(function (mondai) {
      mondai.questions.forEach(function (question) {
        var passageId = question.passage && question.passage.sourceId;
        if (passageId != null && currentPart().code !== 'listening') {
          var key = mondai.id + ':' + passageId;
          if (!passagePages[key]) {
            passagePages[key] = { mondai: mondai, passage: question.passage, questions: [] };
            result.push(passagePages[key]);
          }
          passagePages[key].questions.push(question);
        } else {
          result.push({ mondai: mondai, passage: isAudioOnlyQuestion(question) ? null : question.passage, questions: [question] });
        }
      });
    });
    return result;
  }

  function currentPage() { return pages()[pageIndex]; }

  function pageIndexForQuestion(questionId) {
    return pages().findIndex(function (page) {
      return page.questions.some(function (question) { return String(question.id) === String(questionId); });
    });
  }

  function questionPosition(questionId) {
    return questionRows().findIndex(function (row) { return String(row.question.id) === String(questionId); });
  }

  function isQuestionAnswered(question) {
    if (usesSentenceOrder(question)) return !!sentenceOrders[question.id] && JlptSentenceOrder.complete(sentenceOrders[question.id]);
    return answers[question.id] != null;
  }

  function isQuestionCorrect(question) {
    if (usesSentenceOrder(question)) {
      var state = sentenceOrders[question.id];
      return !!state && JlptSentenceOrder.check(question, state).correct === true;
    }
    return Number(answers[question.id]) === Number(question.correctOption);
  }

  function explanationPayload(question) {
    return {
      questionId: question.id,
      selectedChoice: answers[question.id] == null ? null : Number(answers[question.id])
    };
  }

  function init() {
    if (isExamMode) restoreExamSession();
    $('jlptNotice').hidden = true;
    $('jlptWorkspace').hidden = false;
    document.body.classList.toggle('is-jlpt-test-mode', isExamMode);
    $('jlptLevel').textContent = exam.year == null || exam.month == null
      ? exam.level + ' · Đề thi thử'
      : exam.level + ' · ' + String(exam.month).padStart(2, '0') + '/' + exam.year;
    $('jlptTitle').textContent = exam.title;
    $('jlptMode').textContent = isExamMode ? 'LUYỆN THI' : 'LUYỆN TẬP';
    $('jlptExamStep').hidden = !isExamMode;
    $('jlptTimerWrap').hidden = !isExamMode;
    $('jlptSubmitMondai').hidden = false;
    $('jlptFurigana').hidden = isExamMode;
    $('jlptReview').textContent = exam.reviewStatus === 'CHUA_DUYET' ? 'Dữ liệu chưa duyệt' : exam.reviewStatus;
    bind();
    if (isExamMode && examFinished) { finishExam(true); return; }
    renderTabs();
    render();
    if (isExamMode) startTimer();
  }

  function bind() {
    $('jlptPartTabs').addEventListener('click', function (event) {
      var button = event.target.closest('[data-part]');
      if (!button || (isExamMode && button.disabled)) return;
      partIndex = Number(button.dataset.part);
      pageIndex = 0;
      scriptOpen = false;
      renderTabs();
      render();
    });
    $('jlptPalette').addEventListener('click', function (event) {
      var button = event.target.closest('[data-page-index]');
      if (!button || button.disabled) return;
      pageIndex = Number(button.dataset.pageIndex);
      pendingFocus = button.dataset.questionId;
      scriptOpen = false;
      render();
    });
    $('jlptQuestionGroup').addEventListener('change', function (event) {
      if (!event.target.matches('input[type=radio]')) return;
      var questionId = event.target.dataset.question;
      answers[questionId] = Number(event.target.value);
      if (isExamMode) saveExamSession();
      delete revealed[questionId];
      delete explanations[questionId];
      renderChoices(questionById(questionId));
      renderExplanation(questionById(questionId));
      renderPalette();
      if (furiganaOn) applyFurigana(currentPage());
    });
    $('jlptQuestionGroup').addEventListener('click', function (event) {
      var action = event.target.closest('[data-action]');
      if (!action) return;
      var question = questionById(action.dataset.questionId);
      if (!question) return;
      if (action.dataset.action === 'bookmark') toggleBookmark(question);
      if (!isExamMode && action.dataset.action === 'check') showAnswer(question);
      if (!isExamMode && action.dataset.action === 'explain') requestExplanation(question);
    });
    $('jlptPrevious').onclick = function () { if (pageIndex > 0) { pageIndex--; scriptOpen = false; render(); } };
    $('jlptNext').onclick = function () { if (pageIndex < pages().length - 1) { pageIndex++; scriptOpen = false; render(); } };
    $('jlptFurigana').addEventListener('click', function () {
      furiganaOn = !furiganaOn;
      this.classList.toggle('is-active', furiganaOn);
      this.setAttribute('aria-pressed', String(furiganaOn));
      $('jlptApp').classList.toggle('is-furigana-on', furiganaOn);
      render();
    });
    $('jlptFilters').addEventListener('click', function (event) {
      var button = event.target.closest('[data-filter]');
      if (!button) return;
      filterMode = button.dataset.filter;
      Array.from(this.querySelectorAll('[data-filter]')).forEach(function (item) { item.classList.toggle('is-active', item === button); });
      renderPalette();
    });
    $('jlptAudio').addEventListener('click', handleAudioClick);
    $('jlptSubmitMondai').addEventListener('click', function () {
      if (isExamMode) submitCurrentPart(false, false);
      else openPracticeSubmitDialog();
    });
    $('jlptSubmitCancel').addEventListener('click', function () { $('jlptSubmitDialog').close(); });
    $('jlptSubmitConfirm').addEventListener('click', function () {
      $('jlptSubmitDialog').close();
      if (submitIntent === 'practice') finishExam(false);
      else submitCurrentPart(false, true);
    });
    $('jlptRestartExam').addEventListener('click', restartExam);
    $('jlptResultReview').addEventListener('click', handleReviewAction);
  }

  function questionById(questionId) {
    var found = questionRows().find(function (row) { return String(row.question.id) === String(questionId); });
    return found && found.question;
  }

  function renderTabs() {
    $('jlptPartTabs').innerHTML = exam.parts.map(function (part, index) {
      var locked = isExamMode && index !== partIndex;
      return '<button type="button" data-part="' + index + '" class="' + (index === partIndex ? 'is-active' : '') + '"' + (locked ? ' disabled title="Hãy nộp phần hiện tại để mở phần này"' : '') + '>' + esc(partShortName(part)) + '</button>';
    }).join('');
  }

  function render() {
    var allPages = pages();
    if (!allPages.length) return;
    if (pageIndex >= allPages.length) pageIndex = allPages.length - 1;
    var page = allPages[pageIndex];
    var positions = page.questions.map(function (question) { return questionPosition(question.id) + 1; });
    var type = page.questions.length === 1 ? questionTypeLabel(page.questions[0].questionType) : '';
    $('jlptMondai').textContent = partShortName(currentPart()) + ' · 問題' + page.mondai.number + (type ? ' · ' + type : '');
    $('jlptCounter').textContent = positions.length === 1 ? 'Câu ' + positions[0] + ' / ' + questionRows().length : 'Câu ' + positions[0] + '–' + positions[positions.length - 1] + ' / ' + questionRows().length;
    $('jlptInstruction').innerHTML = safeHtml(page.mondai.instructionHtml);
    renderPassage(page.passage);
    renderAudio(page.mondai, page.questions.length === 1 ? page.questions[0].audioClip : null);
    renderQuestionGroup(page);
    renderPalette();
    if (furiganaOn) applyFurigana(page);
    $('jlptPrevious').disabled = pageIndex === 0;
    $('jlptNext').disabled = pageIndex === allPages.length - 1;
    if (isExamMode) {
      $('jlptSubmitMondai').textContent = partIndex === exam.parts.length - 1 ? 'Nộp bài thi' : 'Nộp phần ' + partShortName(currentPart());
      $('jlptExamStep').textContent = 'PHẦN ' + (partIndex + 1) + '/' + exam.parts.length;
    } else $('jlptSubmitMondai').textContent = 'Nộp bài luyện tập';
    window.scrollTo({ top: 0, behavior: 'smooth' });
    if (pendingFocus) {
      var focusId = pendingFocus;
      pendingFocus = null;
      requestAnimationFrame(function () {
        var target = document.querySelector('[data-question-id="' + focusId + '"]');
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
    }
  }

  function passageHtml(passage, override) {
    if (!passage) return '';
    var original = passage.html || passage.ja || '';
    var value = override != null ? override : original;
    var html = safeHtml((passage.html || override != null) ? value : esc(value));
    return html.replace(/<p>\s*<\/p>/gi, '').replace(/(?:\s*<br\s*\/?>(?:\s|&nbsp;)*){3,}/gi, '<br><br>').replace(/\n\s*\n\s*\n+/g, '\n\n');
  }

  function renderPassage(passage) {
    var element = $('jlptPassage');
    if (!passage) { element.hidden = true; element.innerHTML = ''; return; }
    element.hidden = false;
    element.innerHTML = passageHtml(passage);
  }

  function renderQuestionGroup(page) {
    $('jlptQuestionGroup').innerHTML = page.questions.map(function (question) {
      var position = questionPosition(question.id) + 1;
      var image = question.imageUrl ? '<div class="jlpt-exam-image"><img src="' + esc(question.imageUrl) + '" alt="Hình minh họa câu ' + position + '"></div>' : '';
      return '<section class="jlpt-exam-question-block" data-question-id="' + question.id + '"><header><strong>Câu ' + position + '</strong>' +
        '<button type="button" data-action="bookmark" data-question-id="' + question.id + '" aria-pressed="false"></button></header>' + image +
        '<div class="jlpt-exam-question jlpt-exam-question-text" lang="ja"' + (hideSpokenText(question) ? ' hidden' : '') + '>' + (hideSpokenText(question) ? '' : displayQuestionHtml(question)) + '</div>' +
        '<form><div class="jlpt-exam-choices"></div></form>' + (isExamMode ? '' : '<div class="jlpt-exam-check"><span class="jlpt-exam-feedback"></span>' +
        '<button type="button" data-action="check" data-question-id="' + question.id + '"><span class="material-symbols-outlined">fact_check</span>Đáp án</button>' +
        '<button class="jlpt-exam-explain-button" type="button" data-action="explain" data-question-id="' + question.id + '"><span class="material-symbols-outlined">lightbulb</span>Giải thích AI</button></div>') +
        '<section class="jlpt-exam-explanation" hidden></section></section>';
    }).join('');
    page.questions.forEach(function (question) { renderChoices(question); renderBookmark(question); renderExplanation(question); });
  }

  function questionBlock(question) { return $('jlptQuestionGroup').querySelector('[data-question-id="' + question.id + '"]'); }

  function isAudioOnlyQuestion(question) {
    return ['LISTENING_GENERAL_OUTLINE', 'LISTENING_VERBAL_EXPRESSION', 'LISTENING_QUICK_RESPONSE'].indexOf(question.questionType) >= 0;
  }

  function hideSpokenText(question) { return isAudioOnlyQuestion(question) && !revealed[question.id]; }

  function usesSentenceOrder(question) { return !isExamMode && window.JlptSentenceOrder && JlptSentenceOrder.enabled(question); }

  function renderChoices(question) {
    if (!question) return;
    var block = questionBlock(question);
    if (!block) return;
    var selected = answers[question.id];
    var show = !!revealed[question.id];
    var hideText = hideSpokenText(question);
    var prompt = block.querySelector('.jlpt-exam-question-text');
    prompt.hidden = hideText;
    prompt.innerHTML = hideText ? '' : displayQuestionHtml(question);
    if (usesSentenceOrder(question)) {
      var state = sentenceOrders[question.id] || (sentenceOrders[question.id] = JlptSentenceOrder.create());
      JlptSentenceOrder.render(block.querySelector('.jlpt-exam-choices'), question, state, {
        content: function (option) {
          var text = option.html || option.text || '';
          return displayQuestionHtml({ html: furiganaOn ? furiganaCache[text] || option.html : option.html, text: option.text });
        },
        change: function () {
          if (!state.checked) {
            delete revealed[question.id]; delete explanations[question.id];
            if (JlptSentenceOrder.complete(state)) answers[question.id] = state.order[JlptSentenceOrder.starPosition(question)];
            else delete answers[question.id];
          }
          renderChoices(question); renderExplanation(question); renderPalette();
          block.querySelector('.jlpt-exam-feedback').textContent = '';
          if (furiganaOn) applyFurigana(currentPage());
        }
      });
      return;
    }
    block.querySelector('.jlpt-exam-choices').innerHTML = question.options.map(function (option) {
      var correct = show && Number(option.number) === Number(question.correctOption);
      var wrong = show && Number(option.number) === Number(selected) && !correct;
      return '<label class="jlpt-exam-choice ' + (correct ? 'is-correct ' : wrong ? 'is-wrong ' : '') + '"><input type="radio" name="jlpt-choice-' + question.id +
        '" data-question="' + question.id + '" value="' + option.number + '"' + (Number(selected) === Number(option.number) ? ' checked' : '') + '><b>' + option.number + '</b><span>' +
        (hideText ? '' : displayQuestionHtml(option)) + '</span></label>';
    }).join('');
  }

  function renderPalette() {
    var list = questionRows();
    var answered = allQuestionRows().filter(function (row) { return isQuestionAnswered(row.question); }).length;
    var total = exam.parts.reduce(function (partTotal, part) { return partTotal + part.mondai.reduce(function (sum, mondai) { return sum + mondai.questions.length; }, 0); }, 0);
    $('jlptProgress').textContent = answered + '/' + total;
    var visible = list.map(function (row) { return { row: row, originalIndex: questionPosition(row.question.id) }; }).filter(function (item) {
      var question = item.row.question;
      var isAnswered = isQuestionAnswered(question);
      var isMarked = !!marked[question.sourceId];
      if (filterMode === 'unanswered') return !isAnswered;
      if (filterMode === 'answered') return isAnswered;
      if (filterMode === 'marked') return isMarked;
      return true;
    });
    $('jlptPalette').innerHTML = visible.length ? visible.map(function (item) {
      var question = item.row.question;
      var targetPage = pageIndexForQuestion(question.id);
      var classes = (targetPage === pageIndex ? 'is-current ' : '') + (isQuestionAnswered(question) ? 'is-answered ' : '') + (marked[question.sourceId] ? 'is-marked ' : '');
      var tooltip = marked[question.sourceId] ? 'Câu đã đánh dấu' : 'Câu ' + (item.originalIndex + 1);
      return '<button type="button" data-page-index="' + targetPage + '" data-question-id="' + question.id + '" class="' + classes + '" title="' + tooltip + '">' + (item.originalIndex + 1) + '</button>';
    }).join('') : '<span class="jlpt-exam-filter-empty">Không có câu phù hợp</span>';
  }

  function showAnswer(question) {
    if (isExamMode) return;
    if (usesSentenceOrder(question)) {
      var state = sentenceOrders[question.id] || (sentenceOrders[question.id] = JlptSentenceOrder.create());
      var result = JlptSentenceOrder.check(question, state);
      if (result.error) { questionBlock(question).querySelector('.jlpt-exam-feedback').textContent = result.error; return; }
      state.checked = true;
      answers[question.id] = result.selectedStar;
    }
    revealed[question.id] = true;
    renderChoices(question);
    if (furiganaOn) applyFurigana(currentPage());
    questionBlock(question).querySelector('.jlpt-exam-feedback').textContent = usesSentenceOrder(question)
      ? 'Đúng ' + JlptSentenceOrder.check(question, sentenceOrders[question.id]).matches.filter(Boolean).length + '/4 vị trí.' : '';
  }

  function toggleBookmark(question) {
    var key = String(question.sourceId);
    if (marked[key]) delete marked[key]; else marked[key] = true;
    saveMarks();
    renderBookmark(question);
    renderPalette();
  }

  function renderBookmark(question) {
    var block = questionBlock(question);
    if (!block) return;
    var active = !!marked[String(question.sourceId)];
    var button = block.querySelector('[data-action="bookmark"]');
    button.classList.toggle('is-active', active);
    button.setAttribute('aria-pressed', String(active));
    button.innerHTML = '<span class="material-symbols-outlined">' + (active ? 'bookmark_added' : 'bookmark_add') + '</span>' + (active ? 'Đã đánh dấu' : 'Đánh dấu');
  }

  function loadMarks() { try { return JSON.parse(localStorage.getItem('jlpt-exam-marks:' + code) || '{}') || {}; } catch (error) { return {}; } }
  function saveMarks() { try { localStorage.setItem('jlpt-exam-marks:' + code, JSON.stringify(marked)); } catch (error) { /* Private browsing. */ } }

  function handleAudioClick(event) {
    var segment = event.target.closest('[data-time]');
    var audio = $('jlptAudio').querySelector('audio');
    if (segment && audio) { audio.currentTime = Number(segment.dataset.time); audio.play(); return; }
    var action = event.target.closest('[data-action]');
    if (!action) return;
    if (action.dataset.action === 'play') {
      if (audio) {
        var clipEnd = audio.dataset.clipEnd === '' ? null : Number(audio.dataset.clipEnd);
        if (audio.paused) { if (clipEnd != null && audio.currentTime >= clipEnd - .05) audio.currentTime = Number(audio.dataset.clipStart) || 0; audio.play(); } else audio.pause();
      }
      return;
    }
    var currentTime = audio ? audio.currentTime : null;
    var wasPlaying = audio ? !audio.paused : false;
    if (action.dataset.action === 'script') scriptOpen = !scriptOpen;
    if (action.dataset.action === 'translation') { translationOpen = !translationOpen; scriptOpen = true; }
    var page = currentPage();
    renderAudio(page.mondai, page.questions[0].audioClip, currentTime, wasPlaying);
  }

  function renderAudio(mondai, clip, resumeTime, resumePlay) {
    var element = $('jlptAudio');
    var data = clip || { audioUrl: mondai.audioUrl, start: 0, end: null, transcript: mondai.transcript || [] };
    var segments = data.transcript || [];
    var aligned = data.transcriptAligned !== false;
    var audioUrl = data.audioUrl || mondai.audioUrl;
    if (!audioUrl) { element.hidden = true; element.innerHTML = ''; return; }
    var start = Number(data.start) || 0;
    var end = data.end == null ? null : Number(data.end);
    var duration = end != null && end > start ? end - start : null;
    var hasTranslation = segments.some(function (item) { return !!item.vi; });
    element.hidden = false;
    var controls = '<div class="jlpt-exam-audio__bar"><audio preload="metadata" data-clip-start="' + start + '" data-clip-end="' + (end == null ? '' : end) + '" src="' + esc(audioUrl) + '"></audio>' +
      '<button class="jlpt-exam-audio__play" type="button" data-action="play" aria-label="Phát đoạn nghe"><span class="material-symbols-outlined">play_arrow</span></button>' +
      '<input class="jlpt-exam-audio__range" type="range" min="0" max="' + (duration == null ? 0 : duration) + '" step="0.1" value="0" aria-label="Tiến trình đoạn nghe">' +
      '<time class="jlpt-exam-audio__time">00:00 / ' + (duration == null ? '--:--' : formatTime(duration)) + '</time>' +
      (!isExamMode && segments.length ? '<button type="button" data-action="script"><span class="material-symbols-outlined">article</span>' + (scriptOpen ? 'Ẩn script' : 'Script') + '</button>' : '') +
      (!isExamMode && hasTranslation ? '<button type="button" data-action="translation"><span class="material-symbols-outlined">translate</span>' + (translationOpen ? 'Ẩn dịch' : 'Dịch') + '</button>' : '') + '</div>';
    var script = !isExamMode && scriptOpen ? '<div class="jlpt-exam-transcript">' + segments.map(function (item) {
      return '<div class="jlpt-exam-segment' + (aligned ? '' : ' jlpt-exam-segment--plain') + '"' + (aligned ? ' data-time="' + item.start + '"' : '') + '>' + (aligned ? '<time>' + formatTime(Math.max(0, Number(item.start) - start)) + '</time>' : '') + '<p>' + safeHtml(item.ja) +
        (translationOpen && item.vi ? '<small>' + esc(item.vi) + '</small>' : '') + '</p></div>';
    }).join('') + '</div>' : '';
    element.innerHTML = controls + script;
    var audio = element.querySelector('audio');
    var range = element.querySelector('.jlpt-exam-audio__range');
    var timeLabel = element.querySelector('.jlpt-exam-audio__time');
    var playIcon = element.querySelector('[data-action="play"] .material-symbols-outlined');
    var target = resumeTime != null && Number(resumeTime) >= start && (end == null || Number(resumeTime) < end) ? Number(resumeTime) : start;
    function updatePlayer() {
      var elapsed = Math.max(0, audio.currentTime - start);
      var total = duration == null && Number.isFinite(audio.duration) ? Math.max(0, audio.duration - start) : duration;
      if (total != null) { range.max = String(total); range.value = String(Math.min(elapsed, total)); timeLabel.textContent = formatTime(Math.min(elapsed, total)) + ' / ' + formatTime(total); }
      if (scriptOpen && aligned) {
        var current = audio.currentTime;
        Array.from(element.querySelectorAll('.jlpt-exam-segment')).forEach(function (item) {
          var segmentStart = Number(item.dataset.time);
          var next = item.nextElementSibling && item.nextElementSibling.matches('[data-time]') ? Number(item.nextElementSibling.dataset.time) : (end == null ? Infinity : end);
          item.classList.toggle('is-active', current >= segmentStart && current < next);
        });
      }
    }
    audio.addEventListener('loadedmetadata', function () { audio.currentTime = target; updatePlayer(); if (resumePlay) audio.play(); }, { once: true });
    audio.addEventListener('play', function () { playIcon.textContent = 'pause'; });
    audio.addEventListener('pause', function () { playIcon.textContent = 'play_arrow'; });
    audio.addEventListener('timeupdate', function () { if (end != null && audio.currentTime >= end) { audio.pause(); audio.currentTime = end; } updatePlayer(); });
    range.addEventListener('input', function () { audio.currentTime = start + Number(range.value); updatePlayer(); });
  }

  function requestExplanation(question) {
    if (usesSentenceOrder(question) && !(sentenceOrders[question.id] && sentenceOrders[question.id].checked)) {
      showAnswer(question);
      if (!sentenceOrders[question.id].checked) return;
    }
    if (explanations[question.id]) { renderExplanation(question); return; }
    if (explaining[question.id]) return;
    explaining[question.id] = true;
    revealed[question.id] = true;
    renderChoices(question);
    var block = questionBlock(question);
    var button = block.querySelector('[data-action="explain"]');
    button.disabled = true;
    button.innerHTML = '<span class="material-symbols-outlined">progress_activity</span>Đang phân tích…';
    fetch('/api/jlpt/explanations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(explanationPayload(question)) })
      .then(function (response) { return response.json().then(function (body) { if (!response.ok || body.success === false) throw new Error(body.message || 'Không tạo được giải thích.'); return body.data; }); })
      .then(function (data) { explanations[question.id] = data; renderExplanation(question); })
      .catch(function (error) { var currentBlock = questionBlock(question); if (currentBlock) currentBlock.querySelector('.jlpt-exam-feedback').textContent = error.message; })
      .finally(function () {
        delete explaining[question.id];
        var currentBlock = questionBlock(question);
        if (!currentBlock) return;
        var currentButton = currentBlock.querySelector('[data-action="explain"]');
        currentButton.disabled = false;
        currentButton.innerHTML = '<span class="material-symbols-outlined">lightbulb</span>Giải thích AI';
      });
  }

  function renderExplanation(question) {
    var block = questionBlock(question);
    if (!block) return;
    var element = block.querySelector('.jlpt-exam-explanation');
    var data = explanations[question.id];
    if (!data) { element.hidden = true; element.innerHTML = ''; return; }
    var text = String(data.explanation || '').replace(/\*\*/g, '');
    var labels = ['Đáp án đúng:', 'Từ vựng / Ngữ pháp:', 'Dịch câu:', 'Tham khảo:'];
    var items = [];
    labels.forEach(function (label, index) {
      var start = text.indexOf(label);
      if (start < 0) return;
      var contentStart = start + label.length;
      var end = text.length;
      for (var nextIndex = index + 1; nextIndex < labels.length; nextIndex++) { var found = text.indexOf(labels[nextIndex], contentStart); if (found >= 0) { end = found; break; } }
      var sectionText = text.slice(contentStart, end).trim();
      if (label === 'Tham khảo:') {
        sectionText = sectionText
          .replace(/[ \t]+(?=[1-4][.)]\s)/g, '\n')
          .replace(/[ \t]+(?=Mẹo\s*:)/gi, '\n');
      }
      items.push('<div><strong>' + esc(label.replace(':', '')) + '</strong><p>' + esc(sectionText).replace(/\n/g, '<br>') + '</p></div>');
    });
    element.innerHTML = '<header><span class="material-symbols-outlined">school</span><div><b>Giải thích đáp án</b><small>' +
      (data.aiGenerated ? 'Giáo viên AI phân tích theo dữ liệu câu hỏi' : 'Giải thích dự phòng') + '</small></div></header>' +
      (items.length === 4 ? items.join('') : '<p>' + esc(text).replace(/\n/g, '<br>') + '</p>');
    element.hidden = false;
  }

  function applyFurigana(page) {
    var texts = [page.mondai.instructionHtml || ''];
    if (page.passage) texts.push(page.passage.html || page.passage.ja || '');
    page.questions.forEach(function (question) {
      if (hideSpokenText(question)) return;
      texts.push(question.html || question.text || '');
      question.options.forEach(function (option) { texts.push(option.html || option.text || ''); });
    });
    var segments = page.questions[0].audioClip && page.questions[0].audioClip.transcript ? page.questions[0].audioClip.transcript : page.mondai.transcript || [];
    if (scriptOpen) segments.forEach(function (segment) { texts.push(segment.ja || ''); });
    var signature = page.questions.map(function (question) { return question.id; }).join(',');
    loadFurigana(texts).then(function () {
      var active = currentPage();
      if (!furiganaOn || active.questions.map(function (question) { return question.id; }).join(',') !== signature) return;
      var instruction = page.mondai.instructionHtml || '';
      $('jlptInstruction').innerHTML = safeHtml(furiganaCache[instruction] || instruction);
      if (page.passage) {
        var passageText = page.passage.html || page.passage.ja || '';
        $('jlptPassage').innerHTML = passageHtml(page.passage, furiganaCache[passageText] || passageText);
      }
      page.questions.forEach(function (question) {
        if (hideSpokenText(question)) return;
        var block = questionBlock(question);
        if (!block) return;
        var questionText = question.html || question.text || '';
        if (usesSentenceOrder(question)) renderChoices(question);
        else Array.from(block.querySelectorAll('.jlpt-exam-choice span')).forEach(function (element, index) {
          var optionText = question.options[index].html || question.options[index].text || '';
          element.innerHTML = displayQuestionHtml({ html: furiganaCache[optionText] || optionText });
        });
        block.querySelector('.jlpt-exam-question-text').innerHTML = displayQuestionHtml({ html: furiganaCache[questionText] || questionText });
      });
      if (scriptOpen) Array.from($('jlptAudio').querySelectorAll('.jlpt-exam-segment p')).forEach(function (element, index) {
        var segment = segments[index];
        var translation = translationOpen && segment.vi ? '<small>' + esc(segment.vi) + '</small>' : '';
        element.innerHTML = safeHtml(furiganaCache[segment.ja || ''] || segment.ja || '') + translation;
      });
    }).catch(function () {
      var first = questionBlock(page.questions[0]);
      if (first) first.querySelector('.jlpt-exam-feedback').textContent = 'Chưa thể tạo Furigana cho nội dung này.';
    });
  }

  function questionTypeLabel(type) {
    return { LISTENING_TASK_BASED: 'Nghe hiểu nhiệm vụ', LISTENING_KEY_POINT: 'Nghe hiểu điểm chính', LISTENING_GENERAL_OUTLINE: 'Nghe hiểu khái quát', LISTENING_VERBAL_EXPRESSION: 'Nghe phản xạ tình huống', LISTENING_QUICK_RESPONSE: 'Nghe phản xạ nhanh' }[type] || '';
  }

  function loadFurigana(texts) {
    var missing = [];
    texts.forEach(function (text) { if (text && !Object.prototype.hasOwnProperty.call(furiganaCache, text) && missing.indexOf(text) < 0) missing.push(text); });
    if (!missing.length) return Promise.resolve();
    return fetch('/api/jlpt/furigana', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ texts: missing }) })
      .then(function (response) { return response.json().then(function (body) { if (!response.ok || body.success === false) throw new Error(body.message || 'Không tạo được Furigana.'); return body.data.html; }); })
      .then(function (html) { missing.forEach(function (text, index) { furiganaCache[text] = html[index] || text; }); });
  }

  function restoreExamSession() {
    try {
      var saved = JSON.parse(localStorage.getItem(sessionKey) || 'null');
      if (!saved || saved.mode !== 'exam' || saved.flow !== 'parts-v1') return;
      answers = saved.answers || {};
      completedParts = saved.completedParts || {};
      deadline = Number(saved.deadline) || null;
      examFinished = !!saved.finished;
      partIndex = Math.max(0, Math.min(Number(saved.partIndex) || 0, exam.parts.length - 1));
    } catch (error) { /* Bắt đầu phiên mới nếu dữ liệu lưu bị hỏng. */ }
  }

  function saveExamSession() {
    if (!isExamMode) return;
    try {
      localStorage.setItem(sessionKey, JSON.stringify({
        mode: 'exam', flow: 'parts-v1', answers: answers, completedParts: completedParts,
        deadline: deadline, partIndex: partIndex, finished: examFinished
      }));
    } catch (error) { /* Trình duyệt riêng tư có thể chặn localStorage. */ }
  }

  function startTimer() {
    if (!isExamMode || examFinished) return;
    clearInterval(timerHandle);
    if (!deadline) {
      deadline = Date.now() + Math.max(1, Number(currentPart().durationMinutes || 30)) * 60 * 1000;
      saveExamSession();
    }
    updateTimer();
    timerHandle = setInterval(updateTimer, 1000);
  }

  function updateTimer() {
    var remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
    $('jlptTimer').textContent = formatTime(remaining);
    $('jlptTimerWrap').classList.toggle('is-warning', remaining <= 60);
    if (remaining <= 0) submitCurrentPart(true);
  }

  function submitCurrentPart(automatic, confirmed) {
    if (!isExamMode || examFinished) return;
    var unanswered = questionRows().filter(function (row) { return !isQuestionAnswered(row.question); }).length;
    if (!automatic && !confirmed) { openSubmitDialog(unanswered); return; }
    clearInterval(timerHandle);
    completedParts[currentPart().id] = true;
    deadline = null;
    if (partIndex >= exam.parts.length - 1) { finishExam(false); return; }
    partIndex += 1;
    pageIndex = 0;
    scriptOpen = false;
    translationOpen = false;
    saveExamSession();
    renderTabs();
    render();
    startTimer();
    if (automatic) announce('Hết giờ. Hệ thống đã tự nộp phần thi và chuyển sang phần tiếp theo.');
  }

  function openSubmitDialog(unanswered) {
    submitIntent = 'part';
    var lastPart = partIndex >= exam.parts.length - 1;
    $('jlptSubmitDialogTitle').textContent = lastPart ? 'Nộp bài thi?' : 'Nộp phần ' + partShortName(currentPart()) + '?';
    $('jlptSubmitDialogText').innerHTML = unanswered > 0
      ? 'Bạn còn <strong>' + unanswered + ' câu chưa làm</strong> trong phần này. Sau khi nộp, bạn không thể quay lại sửa đáp án.'
      : 'Bạn đã trả lời tất cả câu trong phần này. Sau khi nộp, bạn không thể quay lại sửa đáp án.';
    $('jlptSubmitConfirm').textContent = lastPart ? 'Nộp bài thi' : 'Nộp và sang phần tiếp';
    $('jlptSubmitDialog').showModal();
  }

  function openPracticeSubmitDialog() {
    submitIntent = 'practice';
    var questions = allQuestionRows().map(function (row) { return row.question; });
    var unanswered = questions.filter(function (question) { return !isQuestionAnswered(question); }).length;
    $('jlptSubmitDialogTitle').textContent = 'Nộp bài luyện tập?';
    $('jlptSubmitDialogText').innerHTML = unanswered > 0
      ? 'Bạn còn <strong>' + unanswered + ' câu chưa làm</strong> trong toàn bộ đề. Bạn vẫn có thể nộp để xem kết quả và đáp án.'
      : 'Bạn đã trả lời tất cả câu trong đề. Hãy nộp bài để xem kết quả và đáp án.';
    $('jlptSubmitConfirm').textContent = 'Nộp bài luyện tập';
    $('jlptSubmitDialog').showModal();
  }

  function finishExam(restored) {
    clearInterval(timerHandle);
    examFinished = true;
    deadline = null;
    saveExamSession();
    var rows = allQuestionRows();
    var questions = rows.map(function (row) { return row.question; });
    var answered = questions.filter(isQuestionAnswered).length;
    var correct = questions.filter(isQuestionCorrect).length;
    var ratios = exam.parts.map(function (part) {
      var partQuestions = [];
      part.mondai.forEach(function (mondai) { partQuestions = partQuestions.concat(mondai.questions); });
      var partCorrect = partQuestions.filter(isQuestionCorrect).length;
      return { part: part, correct: partCorrect, total: partQuestions.length, ratio: partQuestions.length ? partCorrect / partQuestions.length : 0 };
    });
    var totalThreshold = { N1: .556, N2: .5, N3: .528, N4: .5, N5: .444 }[exam.level] || .5;
    var passed = questions.length > 0 && correct / questions.length >= totalThreshold && ratios.every(function (item) { return item.ratio >= .317; });
    $('jlptWorkspace').hidden = true;
    $('jlptResult').hidden = false;
    $('jlptResultEyebrow').textContent = isExamMode ? 'LUYỆN THI ĐÃ HOÀN THÀNH' : 'LUYỆN TẬP ĐÃ HOÀN THÀNH';
    $('jlptRestartExam').textContent = isExamMode ? 'Thi lại đề này' : 'Luyện lại đề này';
    $('jlptResultStatus').textContent = passed ? 'ĐẠT ƯỚC TÍNH' : 'CHƯA ĐẠT';
    $('jlptResultStatus').className = 'jlpt-exam-result__status ' + (passed ? 'is-pass' : 'is-fail');
    $('jlptResultScore').textContent = correct + ' / ' + questions.length;
    $('jlptResultDetail').textContent = 'Đã trả lời ' + answered + '/' + questions.length + ' câu · Đúng ' + correct + ' câu.';
    $('jlptResultBreakdown').innerHTML = ratios.map(function (item) {
      return '<div><span>' + esc(partShortName(item.part)) + '</span><strong>' + item.correct + '/' + item.total + '</strong><small>' + Math.round(item.ratio * 100) + '%</small></div>';
    }).join('');
    renderResultReview(rows);
    if (!restored) window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function allQuestionRows() {
    var result = [];
    exam.parts.forEach(function (part, pIndex) {
      part.mondai.forEach(function (mondai) {
        mondai.questions.forEach(function (question) { result.push({ part: part, partIndex: pIndex, mondai: mondai, question: question }); });
      });
    });
    return result;
  }

  function partShortName(part) {
    return { vocabulary: 'GOI', grammar_reading: 'NGỮ PHÁP & DOKKAI', listening: 'CHOUKAI' }[part.code] || part.title;
  }

  function renderResultReview(rows) {
    var groups = exam.parts.map(function (part) {
      return { part: part, rows: rows.filter(function (row) { return String(row.part.id) === String(part.id); }) };
    });
    var sequence = 0;
    $('jlptResultReview').innerHTML = groups.map(function (group) {
      var questions = group.rows.map(function (row) {
        sequence += 1;
        var question = row.question;
        var selected = answers[question.id];
        var correct = isQuestionCorrect(question);
        var answered = isQuestionAnswered(question);
        var state = !answered ? 'is-missing' : (correct ? 'is-correct' : 'is-wrong');
        var stateLabel = !answered ? 'Chưa trả lời' : (correct ? 'Đúng' : 'Sai');
        var passage = question.passage ? '<div class="jlpt-review-passage">' + passageHtml(question.passage) + '</div>' : '';
        var image = question.imageUrl ? '<img class="jlpt-review-image" src="' + esc(question.imageUrl) + '" alt="Hình câu ' + sequence + '">' : '';
        var options = question.options.map(function (option) {
          var isCorrect = Number(option.number) === Number(question.correctOption);
          var isSelectedWrong = Number(option.number) === Number(selected) && !isCorrect;
          var optionClass = isCorrect ? 'is-correct' : (isSelectedWrong ? 'is-wrong' : '');
          return '<div class="jlpt-review-option ' + optionClass + '"><b>' + option.number + '</b><span>' + safeHtml(option.html || esc(option.text)) + '</span></div>';
        }).join('');
        var prompt = displayQuestionHtml(question);
        return '<article class="jlpt-review-item ' + state + '"><header><span>Câu ' + sequence + ' · 問題' + row.mondai.number + '</span><strong>' + stateLabel + '</strong></header>' +
          passage + image + (wrapperText(prompt) ? '<h4>' + prompt + '</h4>' : '') + '<div class="jlpt-review-options">' + options + '</div>' +
          '<button type="button" data-review-explain="' + question.id + '"><span class="material-symbols-outlined">lightbulb</span>Giải thích AI</button><div class="jlpt-review-explanation" hidden></div></article>';
      }).join('');
      return '<section class="jlpt-review-part"><h3>' + esc(partShortName(group.part)) + '<span>' + group.rows.length + ' câu</span></h3>' + questions + '</section>';
    }).join('');
  }

  function wrapperText(html) {
    var wrapper = document.createElement('div');
    wrapper.innerHTML = html;
    return wrapper.textContent.trim();
  }

  function handleReviewAction(event) {
    var button = event.target.closest('[data-review-explain]');
    if (!button || button.disabled) return;
    var questionId = button.dataset.reviewExplain;
    var row = allQuestionRows().find(function (item) { return String(item.question.id) === String(questionId); });
    if (!row) return;
    var panel = button.nextElementSibling;
    if (explanations[questionId]) { showReviewExplanation(panel, explanations[questionId]); return; }
    button.disabled = true;
    button.innerHTML = '<span class="material-symbols-outlined">progress_activity</span>Đang phân tích…';
    fetch('/api/jlpt/explanations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(explanationPayload(row.question)) })
      .then(function (response) { return response.json().then(function (body) { if (!response.ok || body.success === false) throw new Error(body.message || 'Không tạo được giải thích.'); return body.data; }); })
      .then(function (data) { explanations[questionId] = data; showReviewExplanation(panel, data); })
      .catch(function (error) { panel.hidden = false; panel.textContent = error.message; })
      .finally(function () { button.disabled = false; button.innerHTML = '<span class="material-symbols-outlined">lightbulb</span>Xem lại giải thích'; });
  }

  function showReviewExplanation(panel, data) {
    panel.innerHTML = '<strong>Giải thích đáp án</strong><p>' + esc(String(data.explanation || '').replace(/\*\*/g, '')).replace(/\n/g, '<br>') + '</p>';
    panel.hidden = false;
  }

  function restartExam() {
    try { localStorage.removeItem(sessionKey); } catch (error) { /* Không cần xử lý. */ }
    location.href = '/jlpt/exam?code=' + encodeURIComponent(code) + '&mode=' + mode;
  }

  function announce(message) {
    $('jlptExamAnnouncement').textContent = message;
    $('jlptExamAnnouncement').hidden = false;
    window.setTimeout(function () { $('jlptExamAnnouncement').hidden = true; }, 6000);
  }

  function formatTime(seconds) {
    var minute = Math.floor(seconds / 60);
    var second = Math.floor(seconds % 60);
    return String(minute).padStart(2, '0') + ':' + String(second).padStart(2, '0');
  }
})();
