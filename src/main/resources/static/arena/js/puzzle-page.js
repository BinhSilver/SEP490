import { normalizeAnswer } from '/flashcard/js/speed-recall/rules.js';
import { romajiToHiragana } from '/flashcard/js/speed-recall/romaji.js';
import { slots } from '/flashcard/js/speed-recall/view.js';
import { mountPuzzleAudio } from './puzzle-audio.js';
import { mountDrawing } from './puzzle-drawing.js';

const arena = window.JlasArena;
const root = document.querySelector('.puzzle-page');
const sessionId = root.dataset.sessionId;
const userId = Number(root.dataset.meId);
const versus = root.dataset.mode === 'VERSUS';
const api = '/api/games/puzzles/' + encodeURIComponent(sessionId);
const find = name => root.querySelector('[data-' + name + ']');
const input = document.getElementById('typing-input');
const typingSlots = document.getElementById('typing-slots');
const audio = mountPuzzleAudio(find('sound-toggle'));
const drawing = mountDrawing(root, action => arena.api.post(api + '/ink', action), refresh,
    message => feedback(message, 'incorrect'));

let state;
let clockOffset = 0;
let busy = false;
let refreshing = false;
let refreshAgain = false;
let connection = 'idle';
let composing = false;
let pendingEnter = 0;
let compositionEnded = -Infinity;
let confirmedText = '';
let pendingText = '';
let answerStatus = '';
let countdownPlayedFor;

function feedback(message, tone = '') {
    const output = find('feedback');
    output.hidden = !message;
    output.dataset.tone = tone;
    output.textContent = message;
}

function showError(message) {
    find('error').hidden = !message;
    find('error-message').textContent = message || '';
}

function updateTimer(now = Date.now() + clockOffset) {
    const deadline = state?.nextHintAt ?? state?.deadline;
    const hasMore = deadline != null;
    find('timer').hidden = !hasMore;
    if (!hasMore) return;
    const seconds = Math.max(0, Math.ceil((deadline - now) / 1000));
    find('timer-label').textContent = state.nextHintAt ? 'Gợi ý sau' : 'Còn lại';
    find('timer-value').textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
    if (seconds > 0 && seconds <= 3 && countdownPlayedFor !== deadline) {
        countdownPlayedFor = deadline;
        audio.play('countdown');
    }
}

function renderClues(view, newRound) {
    const gallery = find('images');
    if (newRound) gallery.replaceChildren();
    const revealedCount = gallery.childElementCount;
    for (const [offset, name] of view.images.slice(revealedCount).entries()) {
        const tile = document.createElement('li');
        const img = document.createElement('img');
        img.alt = 'Manh mối ' + (revealedCount + offset + 1);
        img.src = api + '/images/' + view.round + '/' + encodeURIComponent(name);
        img.width = 182;
        img.height = 182;
        tile.append(img);
        gallery.append(tile);
    }
    gallery.hidden = view.images.length === 0;
    const hints = find('hints');
    if (newRound || hints.childElementCount !== view.hints.length) {
        hints.replaceChildren(...view.hints.map((value, index) => {
            const hint = document.createElement('p');
            const text = document.createElement('span');
            text.lang = 'ja';
            text.textContent = value;
            hint.append('Gợi ý ' + (index + 1) + ' · ', text);
            return hint;
        }));
    }
    hints.hidden = view.gameType !== 'PICTURE_REBUS';

    const question = find('question');
    question.hidden = !view.prompt;
    if (newRound) question.textContent = view.prompt || '';
}

function renderPlayers(view, drawingGame) {
    const players = find('players');
    players.hidden = !versus;
    if (!versus) return;
    players.replaceChildren(...view.players.map(player => {
        const line = document.createElement('li');
        const own = player.userId === userId;
        const fallback = document.createElement('span');
        fallback.className = 'puzzle-avatar';
        fallback.setAttribute('aria-hidden', 'true');
        fallback.textContent = player.displayName.trim().split(/\s+/u).slice(-2).map(part => part[0]).join('').toUpperCase();
        if (player.avatarUrl) {
            const avatar = document.createElement('img');
            avatar.alt = '';
            avatar.addEventListener('error', () => avatar.replaceWith(fallback), { once: true });
            avatar.src = player.avatarUrl;
            line.append(avatar);
        } else {
            line.append(fallback);
        }
        const name = document.createElement('strong');
        name.textContent = player.displayName + (own ? ' (bạn)' : '');
        const detail = document.createElement('span');
        detail.className = 'puzzle-player-detail';
        const drawing = drawingGame && view.phase === 'PLAYING' && view.canDraw === own;
        const status = player.online ? '' : 'Mất kết nối';
        detail.textContent = (drawingGame
            ? [view.phase === 'PLAYING' ? (drawing ? 'Đang vẽ' : 'Đang đoán') : '', status]
            : [player.score + ' điểm', status]).filter(Boolean).join(' · ');
        if (drawing) line.dataset.activeDrawer = 'true';
        const info = document.createElement('div');
        info.append(name);
        if (detail.textContent) info.append(detail);
        line.append(info);
        return line;
    }));
}

function render(view) {
    if (state && view.version < state.version) return;
    const previous = state;
    const newRound = !previous || previous.round !== view.round;
    const phaseChanged = previous && previous.phase !== view.phase;
    state = view;
    const drawingGame = view.gameType === 'DRAW_GUESS';
    const active = view.phase === 'PLAYING';
    const done = ['FINISHED', 'ABORTED'].includes(view.phase);
    const activeInput = document.activeElement === input;
    if (newRound) {
        input.value = '';
        pendingEnter = 0;
        composing = false;
        compositionEnded = -Infinity;
        confirmedText = pendingText = answerStatus = '';
        feedback('');
        if (previous) audio.play('round');
    } else if (view.images.length > previous.images.length || view.hints.length > previous.hints.length) {
        audio.play('hint');
    }

    find('loading').hidden = true;
    find('game').hidden = false;
    find('level').textContent = view.jlpt;
    find('progress').textContent = 'Lượt ' + view.round + '/' + view.totalRounds;
    if (!previous || previous.version !== view.version) renderPlayers(view, drawingGame);
    if (view.map) root.dataset.scene = view.map;
    renderClues(view, newRound);

    find('drawing').hidden = !drawingGame;
    if (drawingGame) {
        find('role').textContent = active ? (view.canDraw ? 'Bạn đang vẽ' : 'Bạn đang đoán') : 'Bảng vẽ của lượt này';
        find('secret').hidden = !view.canDraw || !view.word;
        find('secret-word').textContent = view.canDraw ? view.word?.answer || '' : '';
        find('secret-detail').textContent = view.canDraw && view.word ? [view.word.reading, view.word.meaning].join(' · ') : '';
        find('tools').hidden = !view.canDraw;
        drawing.restore(view);
    }

    find('answer-form').hidden = !active || view.canDraw;
    input.disabled = !view.canAnswer;
    input.readOnly = busy;
    typingSlots.tabIndex = view.canAnswer ? 0 : -1;
    typingSlots.setAttribute('aria-disabled', String(!view.canAnswer));
    find('clear-answer').disabled = !view.canAnswer || busy || composing || !input.value;
    renderTypingSlots();
    find('skip').hidden = !view.canSkip;
    find('next').hidden = view.phase !== 'REVEALED';
    find('actions').hidden = !view.canSkip && view.phase !== 'REVEALED';
    find('next').disabled = view.readyForNext || busy;
    find('skip').disabled = busy;
    find('next').textContent = view.readyForNext ? 'Đang chờ bạn chơi'
        : view.round === view.totalRounds ? 'Kết thúc' : 'Lượt tiếp theo';
    if (phaseChanged) {
        feedback('');
        if (view.phase === 'REVEALED' && !view.players.some(player => player.userId === userId && player.solved)) {
            audio.play('round');
        }
    }
    if (active && !view.canAnswer && !view.canDraw) feedback('Đã có đáp án đúng. Đang chờ người chơi còn lại.', 'correct');

    const revealed = !active && !!view.word;
    find('reveal').hidden = !revealed;
    if (revealed) {
        find('reveal-word').textContent = view.word.answer;
        find('reveal-reading').textContent = view.word.answer === view.word.reading ? '' : view.word.reading;
        find('reveal-meaning').textContent = view.word.meaning;
        find('reveal-explanation').hidden = !view.word.explanation;
        find('reveal-explanation').textContent = view.word.explanation || '';
    }
    if (revealed && view.roundWinner != null && versus) {
        const winner = view.players.find(player => player.userId === view.roundWinner);
        feedback(view.roundWinner === userId ? 'Bạn trả lời đúng đầu tiên.' : winner.displayName + ' trả lời đúng đầu tiên.', 'correct');
    }
    find('finish').hidden = !done;
    if (done) {
        if (!previous || phaseChanged) audio.finish();
        const result = view.result;
        const score = result?.lines.find(line => line.userId === userId)?.score ?? 0;
        find('finish-title').textContent = view.phase === 'ABORTED' ? 'Phiên đã đóng'
            : drawingGame ? 'Đã kết thúc phiên vẽ'
            : !versus ? 'Hoàn thành · ' + score + ' điểm'
            : result.draw ? 'Hai bạn hòa'
            : result.winnerUserId === userId ? 'Bạn thắng' : 'Đối thủ thắng';
    }
    if (view.canAnswer && newRound && !arena.phoneMedia.matches) input.focus({ preventScroll: true });
    if (phaseChanged && view.phase === 'REVEALED' && activeInput) find('next').focus({ preventScroll: true });
    updateTimer();
}

async function refresh() {
    if (refreshing) {
        refreshAgain = true;
        return;
    }
    refreshing = true;
    try {
        const view = await arena.api.get(api);
        clockOffset = view.serverTime - Date.now();
        showError(null);
        render(view);
    } catch (error) {
        find('loading').hidden = true;
        showError(error.message || 'Không tải được lượt chơi.');
    } finally {
        refreshing = false;
        if (refreshAgain) {
            refreshAgain = false;
            void refresh();
        }
    }
}

async function roundAction(action, answer) {
    if (!state || busy) return;
    const round = state.round;
    busy = true;
    render(state);
    try {
        const reply = await arena.api.post(api + '/' + action, answer === undefined ? { round } : { round, answer });
        if (action === 'answer' && reply.round === state.round) {
            answerStatus = reply.correct ? 'correct' : 'wrong';
            audio.play(answerStatus);
            if (!reply.correct) feedback('Chưa đúng. Sửa đáp án rồi nhấn Enter để thử lại.', 'incorrect');
        }
    } catch (error) {
        if (state.round === round) feedback(error.message, 'incorrect');
    } finally {
        busy = false;
        render(state);
        void refresh();
    }
}

function renderTypingSlots() {
    const chars = Array.from(confirmedText);
    const count = Math.max(state.answerSlots || 4, chars.length);
    const active = Math.min(chars.length, count - 1);
    const values = Array.from({ length: count }, (_, index) => ({
        char: chars[index],
        status: (chars[index] && answerStatus) || (index === active ? 'active' : chars[index] ? 'filled' : 'empty')
    }));
    values.pending = pendingText;
    slots(typingSlots, values);
    typingSlots.classList.toggle('is-mistake', answerStatus === 'wrong');
}

function syncTyping(commit = false) {
    if (composing) {
        pendingText = input.value;
    } else {
        // Keep the native field in Romaji so Backspace edits the raw spelling, including an unfinished n.
        const kana = romajiToHiragana(normalizeAnswer(input.value).replace(/\s+/gu, ''), { commit });
        confirmedText = kana.match(/^[^a-z']*/i)[0];
        pendingText = kana.slice(confirmedText.length);
    }
    find('clear-answer').disabled = !state.canAnswer || busy || composing || !input.value;
    renderTypingSlots();
}

function updateTyping(event) {
    if (!state?.canAnswer || busy) return;
    if (event?.isComposing) composing = true;
    answerStatus = '';
    feedback('');
    syncTyping();
}

function submitAnswer() {
    if (!state?.canAnswer || composing || busy) return;
    syncTyping(true);
    if (pendingText) {
        feedback('Âm Romaji chưa hoàn tất. Gõ tiếp hoặc sửa rồi nhấn Enter.', 'incorrect');
        return;
    }
    if (confirmedText) void roundAction('answer', confirmedText);
}

input.addEventListener('input', updateTyping);
input.addEventListener('compositionstart', () => { composing = true; if (state?.canAnswer) syncTyping(); });
input.addEventListener('compositionend', () => {
    composing = false;
    compositionEnded = performance.now();
    updateTyping();
    if (pendingEnter) queueCommittedAnswer(pendingEnter);
});
function caretToEnd(event) {
    if (composing || event?.isComposing || event?.ctrlKey || event?.metaKey) return;
    if (input.selectionStart === input.selectionEnd && input.selectionEnd !== input.value.length) {
        input.setSelectionRange(input.value.length, input.value.length);
    }
}
input.addEventListener('focus', caretToEnd);
input.addEventListener('click', caretToEnd);
input.addEventListener('keydown', event => {
    caretToEnd(event);
    if (event.key !== 'Enter' && event.code !== 'Enter' && event.code !== 'NumpadEnter') return;
    if (event.repeat || busy || !state?.canAnswer) {
        event.preventDefault();
        return;
    }
    const ime = event.isComposing || composing || event.keyCode === 229;
    if (ime || performance.now() - compositionEnded <= 80) {
        if (!ime) event.preventDefault();
        pendingEnter = state.round;
        queueCommittedAnswer(pendingEnter);
    } else {
        event.preventDefault();
        submitAnswer();
    }
});
function queueCommittedAnswer(round) {
    requestAnimationFrame(() => {
        if (!round || pendingEnter !== round || state?.round !== round || composing) return;
        pendingEnter = 0;
        submitAnswer();
    });
}
find('answer-form').addEventListener('submit', event => {
    event.preventDefault();
    if (!pendingEnter) submitAnswer();
});
function focusTyping() {
    if (state?.canAnswer && !busy) input.focus({ preventScroll: true });
}
typingSlots.addEventListener('click', focusTyping);
typingSlots.addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    focusTyping();
});
find('clear-answer').addEventListener('click', () => {
    input.value = '';
    pendingEnter = 0;
    updateTyping();
    focusTyping();
});
find('skip').addEventListener('click', () => void roundAction('skip'));
find('next').addEventListener('click', () => void roundAction('next'));
find('retry').addEventListener('click', () => void refresh());

arena.live.on('PUZZLE_STATE', (payload, envelope) => {
    if (envelope.sessionId === sessionId && (!state || payload.version > state.version)) void refresh();
});
arena.live.on('PUZZLE_INK', (payload, envelope) => {
    if (envelope.sessionId === sessionId && state) drawing.receive(payload);
});
arena.live.on('CONNECTED', () => { void refresh(); });
arena.live.onStatus(status => {
    connection = status;
    find('connection').hidden = status === 'online';
    find('connection').textContent = status === 'offline' ? 'Mất kết nối trực tiếp, đang kết nối lại…' : 'Đang kết nối…';
});
const tick = window.setInterval(() => updateTimer(), 250);
const recovery = window.setInterval(() => {
    if (connection !== 'online' && !['FINISHED', 'ABORTED'].includes(state?.phase)) void refresh();
}, 5000);
window.addEventListener('pagehide', event => {
    if (event.persisted) return;
    [tick, recovery].forEach(id => window.clearInterval(id));
    drawing.stop();
});
arena.live.connect();
void refresh();
