(function () {
    'use strict';

    if (!window.JlasAuth || !window.JlasAuth.requireLogin('/credit-history')) return;

    var transactions = [];
    var activeFilter = 'all';
    var labels = {
        TARGET_COMPLETE: 'Hoàn thành mục tiêu',
        QUIZ_COMPLETE: 'Hoàn thành Quiz',
        ITEM_PURCHASE: 'Mua vật phẩm: Streak Freeze',
        WEEKLY_REWARD: 'Thưởng duy trì tuần',
        WELCOME_REWARD: 'Thưởng bắt đầu học'
    };

    function el(id) { return document.getElementById(id); }
    function text(id, value) { if (el(id)) el(id).textContent = value; }
    function money(value) { return Number(value || 0).toLocaleString('en-US'); }
    function reason(value) { return labels[value] || value || 'Hoạt động học tập'; }
    function date(value) {
        if (!value) return 'Gần đây';
        var parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? String(value) : new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(parsed);
    }

    async function load() {
        try {
            var response = await fetch('/api/credits/me', { headers: window.JlasAuth.authHeaders() });
            var payload = await response.json().catch(function () { return {}; });
            if (!response.ok || payload.success === false) throw new Error(payload.message || 'Không thể tải lịch sử Credit.');
            transactions = payload.data && Array.isArray(payload.data.transactions) ? payload.data.transactions : [];
            renderSummary(payload.data || {});
            renderList();
        } catch (exception) {
            el('creditHistoryError').hidden = false;
            if (window.JlasUI && typeof window.JlasUI.toast === 'function') window.JlasUI.toast(exception.message, 'error');
        }
    }

    function renderSummary(data) {
        var earned = transactions.reduce(function (total, item) { return total + Math.max(0, Number(item.amount || 0)); }, 0);
        var spent = transactions.reduce(function (total, item) { return total + Math.min(0, Number(item.amount || 0)); }, 0);
        text('creditHistoryBalance', money(data.balance));
        text('creditHistoryEarned', '+' + money(earned));
        text('creditHistorySpent', money(spent));
        text('creditHistoryCount', transactions.length);
    }

    function renderList() {
        var list = el('creditHistoryList');
        var empty = el('creditHistoryEmpty');
        list.replaceChildren();
        var visible = transactions.filter(function (item) {
            var amount = Number(item.amount || 0);
            return activeFilter === 'earned' ? amount > 0 : activeFilter === 'spent' ? amount < 0 : true;
        });
        empty.hidden = visible.length > 0;
        visible.forEach(function (item) {
            var amount = Number(item.amount || 0);
            var row = document.createElement('article');
            row.className = 'credit-history-page__row' + (amount < 0 ? ' is-negative' : '');
            var icon = document.createElement('span');
            icon.className = 'credit-history-page__row-icon';
            var iconGlyph = document.createElement('i');
            iconGlyph.className = amount < 0 ? 'fa-solid fa-store' : 'fa-solid fa-award';
            iconGlyph.setAttribute('aria-hidden', 'true');
            icon.appendChild(iconGlyph);
            var copy = document.createElement('div');
            var title = document.createElement('strong');
            title.className = 'credit-history-page__row-title';
            title.textContent = reason(item.reason);
            var note = document.createElement('small');
            note.className = 'credit-history-page__row-note';
            note.textContent = item.note && item.note.indexOf('DEMO_STITCH_') === 0 ? 'Hoạt động học tập' : (item.note || 'Giao dịch Credit');
            copy.append(title, note);
            var created = document.createElement('time');
            created.className = 'credit-history-page__row-date';
            created.dateTime = item.createdAt || '';
            created.textContent = date(item.createdAt);
            var value = document.createElement('b');
            value.className = 'credit-history-page__row-amount';
            value.textContent = (amount >= 0 ? '+' : '') + money(amount) + ' CR';
            row.append(icon, copy, created, value);
            list.appendChild(row);
        });
    }

    document.querySelectorAll('[data-credit-filter]').forEach(function (button) {
        button.addEventListener('click', function () {
            activeFilter = button.dataset.creditFilter;
            document.querySelectorAll('[data-credit-filter]').forEach(function (item) { item.classList.toggle('is-active', item === button); });
            renderList();
        });
    });

    load();
}());
