(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && module.exports) module.exports = factory();
    else root.KanaConnect = factory();
}(typeof window === 'object' ? window : globalThis, function () {
    'use strict';
    function adjacent(a, b) { return !!a && !!b && Math.abs(a.row - b.row) + Math.abs(a.col - b.col) === 1; }
    function normalizeCells(cells) {
        if (!Array.isArray(cells)) throw new Error('Dữ liệu ô chữ không hợp lệ. Hãy tải lại thử thách.');
        return cells.map(function (cell, index) {
            if (!cell || cell.id !== index || !Number.isInteger(cell.row) || !Number.isInteger(cell.col) ||
                    (cell.kana != null && (typeof cell.kana !== 'string' || !cell.kana.length))) {
                throw new Error('Dữ liệu ô chữ không hợp lệ. Hãy tải lại thử thách.');
            }
            // Spring's NON_NULL JSON omits kana on walls. Keep a single wall representation
            // in the renderer and state so omitted and explicit-null values behave identically.
            return Object.assign({}, cell, { kana: cell.kana == null ? null : cell.kana });
        });
    }
    function normalizeChallenge(challenge) {
        if (!challenge || !Array.isArray(challenge.words) || !Array.isArray(challenge.cells) ||
                !Number.isInteger(challenge.gridSize) || challenge.gridSize < 4 || challenge.gridSize > 8) {
            throw new Error('Dữ liệu bàn chơi chưa đúng phiên bản nhiều từ. Hãy khởi động lại backend và tải lại trang.');
        }
        var cells = normalizeCells(challenge.cells);
        var solvedWordIds = challenge.solvedWordIds == null ? [] : challenge.solvedWordIds;
        var solvedPaths = challenge.solvedPaths == null ? [] : challenge.solvedPaths;
        // Revealed hint kana; older servers and finished boards send none.
        var hints = Array.isArray(challenge.hints) ? challenge.hints.filter(function (hint) {
            return hint && Number.isInteger(hint.wordId) && Number.isInteger(hint.index) && typeof hint.kana === 'string';
        }) : [];
        if (cells.length !== challenge.gridSize * challenge.gridSize || !Array.isArray(solvedWordIds) || !Array.isArray(solvedPaths) ||
                challenge.words.some(function (word) { return !word || !Number.isInteger(word.id) || !Number.isInteger(word.kanaCount) || word.kanaCount < 2 || word.kanaCount > 12; }) ||
                solvedPaths.some(function (solution) {
                    return !solution || !Number.isInteger(solution.wordId) || !Array.isArray(solution.path) ||
                        solution.path.some(function (id) { return !Number.isInteger(id) || !cells[id] || cells[id].kana === null; });
                })) {
            throw new Error('Dữ liệu bàn chơi không hợp lệ. Hãy tải lại thử thách.');
        }
        return Object.assign({}, challenge, { cells: cells, solvedWordIds: solvedWordIds, solvedPaths: solvedPaths, hints: hints,
            nextHintAt: challenge.nextHintAt == null ? null : challenge.nextHintAt,
            blockedCells: cells.filter(function (cell) { return cell.kana === null; }).map(function (cell) { return cell.id; }) });
    }
    function GameState(cells) {
        this.cells = normalizeCells(cells); this.path = []; this.words = []; this.paths = [];
        this.solvedWordIds = []; this.locked = new Set(); this.completed = false;
    }
    GameState.prototype.sync = function (challenge) {
        this.words = challenge.words;
        this.paths = challenge.solvedPaths;
        this.solvedWordIds = challenge.solvedWordIds;
        this.locked = new Set(this.paths.flatMap(function (solution) { return solution.path; }));
        this.path = [];
        this.completed = this.words.length > 1 && this.solvedWordIds.length === this.words.length &&
            this.locked.size === this.cells.filter(function (cell) { return cell.kana !== null; }).length;
    };
    GameState.prototype.select = function (id) {
        if (this.completed || !Number.isInteger(id) || !this.cells[id] || this.cells[id].kana === null || this.locked.has(id)) return false;
        if (this.path.length > 1 && this.path[this.path.length - 2] === id) { this.path.pop(); return true; }
        if (this.path.length >= 12) return false;
        if (this.path.includes(id)) return false;
        if (this.path.length && !adjacent(this.cells[this.path[this.path.length - 1]], this.cells[id])) return false;
        this.path.push(id); return true;
    };
    GameState.prototype.undo = function () { if (!this.completed) this.path.pop(); };
    GameState.prototype.reset = function () { if (!this.completed) this.path = []; };
    GameState.prototype.text = function () { return this.path.map(function (id) { return this.cells[id].kana; }, this).join(''); };
    GameState.prototype.restore = function (path) {
        this.reset();
        if (!Array.isArray(path) || path.length > 12 || this.completed) return false;
        for (var i = 0; i < path.length; i++) {
            if (this.path.includes(path[i]) || !this.select(path[i])) { this.reset(); return false; }
        }
        return true;
    };
    return { GameState: GameState, adjacent: adjacent, normalizeChallenge: normalizeChallenge };
}));
