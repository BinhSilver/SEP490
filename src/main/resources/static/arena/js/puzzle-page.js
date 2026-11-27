import { normalizeAnswer } from '/flashcard/js/speed-recall/rules.js';
import { romajiToHiragana } from '/flashcard/js/speed-recall/romaji.js';
import { slots } from '/flashcard/js/speed-recall/view.js';
import { mountPuzzleAudio } from './puzzle-audio.js?v=2';
import { mountDrawing } from './puzzle-drawing.js?v=2';

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

const clock = seconds => Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
// Length of the current countdown, learned the first time a deadline shows up, so the bar can shrink from full.
let timerSpan = { deadline: null, total: 0 };

function updateTimer(now = Date.now() + clockOffset) {
    const deadline = state?.deadline;
    find('timer').hidden = deadline == null;
    if (deadline != null) {
        if (timerSpan.deadline !== deadline) {
            timerSpan = { deadline, total: state.roundSeconds ? state.roundSeconds * 1000 : Math.max(1000, deadline - now) };
        }
        const left = Math.max(0, deadline - now);
        const seconds = Math.ceil(left / 1000);
        find('timer-label').textContent = 'Còn lại';
        find('timer-value').textContent = clock(seconds);
        find('timer-bar').style.width = Math.min(100, left / timerSpan.total * 100) + '%';
        find('timer').classList.toggle('is-urgent', seconds <= 5);
        if (seconds > 0 && seconds <= 3 && countdownPlayedFor !== deadline) {
            countdownPlayedFor = deadline;
            audio.play('countdown');
        }
    }
    const hint = state?.nextHintAt;
    find('hint-timer').hidden = hint == null;
    if (hint != null) find('hint-timer').textContent = 'Gợi ý tiếp theo sau ' + clock(Math.max(0, Math.ceil((hint - now) / 1000)));
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

function initials(name) {
    return name.trim().split(/\s+/u).slice(-2).map(part => part[0]).join('').toUpperCase();
}

/** Avatar image, or the player's initials when there is none or it fails to load. */
function avatar(player) {
    const fallback = document.createElement('span');
    fallback.className = 'puzzle-avatar';
    fallback.setAttribute('aria-hidden', 'true');
    fallback.textContent = initials(player.displayName);
    if (!player.avatarUrl) return fallback;
    const image = document.createElement('img');
    image.className = 'puzzle-avatar';
    image.alt = '';
    image.addEventListener('error', () => image.replaceWith(fallback), { once: true });
    image.src = player.avatarUrl;
    return image;
}

function renderPlayers(view, drawingGame) {
    const players = find('players');
    players.replaceChildren(...view.players.map(player => {
        const line = document.createElement('li');
        line.className = 'puzzle-player';
        const own = player.userId === userId;
        const drawer = drawingGame && view.phase === 'PLAYING' && view.drawerUserId === player.userId;
        line.append(avatar(player));
        const info = document.createElement('div');
        info.className = 'puzzle-player__info';
        const name = document.createElement('strong');
        name.textContent = player.displayName + (own ? ' (bạn)' : '');
        const detail = document.createElement('span');
        detail.className = 'puzzle-player-detail';
        detail.textContent = !player.online ? 'Mất kết nối'
            : drawingGame ? (view.phase === 'PLAYING' ? (drawer ? 'Đang vẽ' : 'Đang đoán') : '')
            : player.solved && view.phase === 'PLAYING' ? 'Đã giải xong' : '';
        info.append(name);
        if (detail.textContent) info.append(detail);
        line.append(info);
        const badges = document.createElement('span');
        badges.className = 'puzzle-player__badges';
        if (drawer) badges.append(badge('fa-solid fa-pencil', 'Đang vẽ', 'draw'));
        if (player.solved) badges.append(badge('fa-solid fa-check', 'Đã giải', 'solved'));
        line.append(badges);
        if (!drawingGame) {
            const score = document.createElement('span');
            score.className = 'puzzle-player__score';
            score.innerHTML = '<b></b><small>điểm</small>';
            score.querySelector('b').textContent = player.score;
            line.append(score);
        }
        if (drawer) line.dataset.activeDrawer = 'true';
        if (!player.online) line.dataset.offline = 'true';
        return line;
    }));
}

function badge(icon, label, kind) {
    const mark = document.createElement('span');
    mark.className = 'puzzle-badge puzzle-badge--' + kind;
    mark.title = label;
    mark.innerHTML = '<i aria-hidden="true"></i><span class="sr-only"></span>';
    mark.querySelector('i').className = icon;
    mark.querySelector('.sr-only').textContent = label;
    return mark;
}

/** One dot per round: solved, missed, current or still to come. */
function renderDots(view) {
    const dots = find('round-dots');
    const results = new Map(view.rounds.map(round => [round.round, round]));
    dots.replaceChildren(...Array.from({ length: view.totalRounds }, (_, index) => {
        const number = index + 1;
        const dot = document.createElement('li');
        const done = results.get(number);
        const mine = done && done.solvedBy.includes(userId);
        const drew = done && done.drawerUserId === userId;
        dot.className = 'puzzle-dot ' + (done ? (drew ? 'is-drawn' : mine ? 'is-solved' : 'is-missed')
            : number === view.round && view.phase === 'PLAYING' ? 'is-current' : 'is-ahead');
        dot.textContent = number;
        dot.setAttribute('aria-label', 'Lượt ' + number + (done ? (drew ? ': bạn vẽ' : mine ? ': đúng' : ': chưa giải được')
            : number === view.round ? ': đang chơi' : ''));
        return dot;
    }));
}

/** Draw & Guess: every guess shows up like a chat line, so the drawer sees what the other player is thinking. */
function renderGuesses(view) {
    const panel = find('guess-panel');
    panel.hidden = view.gameType !== 'DRAW_GUESS' || ['FINISHED', 'ABORTED'].includes(view.phase);
    if (panel.hidden) return;
    const names = new Map(view.players.map(player => [player.userId, player.userId === userId ? 'Bạn' : player.displayName]));
    const log = find('guess-log');
    log.replaceChildren(...view.guesses.map(guess => {
        const line = document.createElement('li');
        line.className = guess.correct ? 'is-correct' : '';
        const who = document.createElement('strong');
        who.textContent = names.get(guess.userId) || 'Người chơi';
        const text = document.createElement('span');
        text.lang = 'ja';
        text.textContent = guess.correct ? guess.text + ' · đoán đúng!' : guess.text;
        line.append(who, text);
        return line;
    }));
    find('guess-empty').hidden = view.guesses.length > 0;
    log.scrollTop = log.scrollHeight;
}

function renderStreak(view) {
    const streak = find('streak');
    streak.hidden = versus || view.gameType === 'DRAW_GUESS';
    if (streak.hidden) return;
    find('streak-value').textContent = view.streak;
    find('streak-best').textContent = view.bestStreak > 0 ? '· Kỷ lục ' + view.bestStreak : '';
    streak.classList.toggle('is-hot', view.streak >= 3);
}

/** Result screen: podium (or solo summary), then every word of the match with each player's result. */
function renderFinish(view) {
    const drawingGame = view.gameType === 'DRAW_GUESS';
    const result = view.result;
    const players = view.players;
    const podium = find('podium');
    const solo = find('solo-summary');
    const subtitle = find('finish-subtitle');
    const solvedCount = id => view.rounds.filter(round => round.solvedBy.includes(id) && round.drawerUserId !== id).length;
    const drawnCount = id => view.rounds.filter(round => round.drawerUserId === id && round.solvedBy.length > 0).length;
    find('finish-title').textContent = view.phase === 'ABORTED' ? 'Phiên đã đóng'
        : drawingGame ? 'Đã kết thúc phiên vẽ'
        : !versus ? 'Hoàn thành'
        : result?.draw ? 'Hai bạn hòa'
        : result?.winnerUserId === userId ? 'Bạn thắng' : 'Đối thủ thắng';
    podium.hidden = !versus;
    solo.hidden = versus;
    if (versus) {
        const ranked = [...players].sort((a, b) => drawingGame ? 0 : b.score - a.score);
        podium.replaceChildren(...ranked.map((player, index) => {
            const place = drawingGame || result?.draw ? 1 : index + 1;
            const item = document.createElement('li');
            item.className = 'puzzle-podium__place puzzle-podium__place--' + place + (player.userId === userId ? ' is-me' : '');
            const medal = document.createElement('span');
            medal.className = 'puzzle-podium__rank';
            medal.textContent = drawingGame ? '✏️' : place === 1 ? '1' : '2';
            const name = document.createElement('strong');
            name.textContent = player.displayName + (player.userId === userId ? ' (bạn)' : '');
            const stat = document.createElement('span');
            stat.className = 'puzzle-podium__score';
            stat.textContent = drawingGame
                ? 'Đoán đúng ' + solvedCount(player.userId) + ' · Vẽ được đoán ra ' + drawnCount(player.userId)
                : player.score + ' điểm';
            item.append(medal, avatar(player), name, stat);
            return item;
        }));
        subtitle.textContent = drawingGame ? 'Cảm ơn hai bạn đã cùng vẽ và đoán.' : '';
    } else {
        const me = players.find(player => player.userId === userId);
        const rows = [['Điểm', me ? me.score : 0], ['Câu đúng', solvedCount(userId) + '/' + view.rounds.length],
            ['Chuỗi đúng dài nhất', view.bestStreak]];
        solo.replaceChildren(...rows.flatMap(([label, value]) => {
            const term = document.createElement('dt');
            term.textContent = label;
            const detail = document.createElement('dd');
            detail.textContent = value;
            const box = document.createElement('div');
            box.append(term, detail);
            return [box];
        }));
        subtitle.textContent = view.roundSeconds ? 'Mỗi câu ' + view.roundSeconds + ' giây.' : '';
        const replay = find('replay');
        if (replay && view.roundSeconds) {
            const url = new URL(replay.href, window.location.origin);
            url.searchParams.set('seconds', String(view.roundSeconds));
            replay.href = url.pathname + url.search;
        }
    }
    const head = document.createElement('tr');
    const columns = versus ? players : players.filter(player => player.userId === userId);
    head.append(...['Lượt', 'Từ', ...columns.map(player => player.userId === userId ? 'Bạn' : player.displayName)].map(label => {
        const cell = document.createElement('th');
        cell.scope = 'col';
        cell.textContent = label;
        return cell;
    }));
    find('rounds-head').replaceChildren(head);
    find('rounds-body').replaceChildren(...view.rounds.map(round => {
        const row = document.createElement('tr');
        const number = document.createElement('td');
        number.textContent = round.round;
        const word = document.createElement('td');
        word.className = 'puzzle-rounds__word';
        word.innerHTML = '<strong lang="ja"></strong> <span lang="ja"></span><small></small>';
        word.querySelector('strong').textContent = round.answer;
        word.querySelector('span').textContent = round.answer === round.reading ? '' : round.reading;
        word.querySelector('small').textContent = round.meaning;
        row.append(number, word, ...columns.map(player => {
            const cell = document.createElement('td');
            const points = round.points.find(point => point.userId === player.userId)?.points ?? 0;
            const solved = round.solvedBy.includes(player.userId);
            cell.className = round.drawerUserId === player.userId ? 'is-drawer' : solved ? 'is-solved' : 'is-missed';
            cell.textContent = round.drawerUserId === player.userId ? '✏️ Vẽ'
                : drawingGame ? (solved ? '✓ Đoán đúng' : '✗')
                : solved ? '✓ +' + points : '✗';
            return cell;
        }));
        return row;
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
    if (!previous || previous.version !== view.version) {
        renderPlayers(view, drawingGame);
        renderDots(view);
        renderGuesses(view);
        renderStreak(view);
    }
    find('slot-count').textContent = view.answerSlots + ' chữ ·';
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
    // Draw & Guess: only the player who just drew moves on to the next round.
    const waitsForDrawer = drawingGame && view.phase === 'REVEALED' && view.drawerUserId !== userId;
    find('next').hidden = view.phase !== 'REVEALED' || waitsForDrawer;
    find('actions').hidden = !view.canSkip && (view.phase !== 'REVEALED' || waitsForDrawer);
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
    if (waitsForDrawer) feedback('Đang chờ người vẽ chuyển sang lượt tiếp theo.');
    if (revealed && view.roundWinner != null && versus) {
        const winner = view.players.find(player => player.userId === view.roundWinner);
        feedback(view.roundWinner === userId ? 'Bạn trả lời đúng đầu tiên.' : winner.displayName + ' trả lời đúng đầu tiên.', 'correct');
    }
    find('finish').hidden = !done;
    find('layout').hidden = done;
    if (done) {
        if (!previous || phaseChanged) audio.finish();
        renderFinish(view);
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
