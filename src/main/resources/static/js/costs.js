/* Costs tab — polls /api/cost/current every 3s while tab is active,
   renders live session totals + per-model breakdown + daily history. */
(function () {
  'use strict';

  var pollTimer = null;
  var isActive = false;

  function el(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c]; }); }

  function fmtUsd(n) {
    if (!n) return '$0.00';
    if (n < 0.01) return '$' + n.toFixed(5);
    if (n < 1)    return '$' + n.toFixed(4);
    if (n < 100)  return '$' + n.toFixed(3);
    return '$' + n.toFixed(2);
  }

  function fmtTokens(n) {
    if (!n) return '0';
    if (n < 1000)     return String(n);
    if (n < 1_000_000) return (n / 1000).toFixed(1) + 'K';
    return (n / 1_000_000).toFixed(2) + 'M';
  }

  function fmtElapsed(ms) {
    var s = Math.floor(ms / 1000);
    if (s < 60) return s + ' sec';
    var m = Math.floor(s / 60);
    if (m < 60) return m + ' min';
    var h = Math.floor(m / 60);
    return h + 'h ' + (m % 60) + 'm';
  }

  async function refresh() {
    try {
      var [cur, hist] = await Promise.all([
        fetch('/api/cost/current').then(function (r) { return r.json(); }),
        fetch('/api/cost/history?days=30').then(function (r) { return r.json(); })
      ]);
      renderCurrent(cur);
      renderHistory(hist);
    } catch (e) { /* swallow; retry next tick */ }
  }

  function renderCurrent(d) {
    if (!d) return;
    el('costs-title').textContent = d.totalUsd > 0
        ? 'Spending live — ' + fmtUsd(d.totalUsd) + ' so far'
        : 'No cloud LLM calls yet this session';
    el('costs-sub').textContent = d.calls + ' call' + (d.calls === 1 ? '' : 's')
        + ' · ' + fmtTokens(d.promptTokens) + ' in, ' + fmtTokens(d.completionTokens) + ' out';

    el('costs-session-usd').textContent = fmtUsd(d.totalUsd);
    el('costs-session-hint').textContent = d.calls + ' call' + (d.calls === 1 ? '' : 's') + ' · '
        + fmtTokens(d.promptTokens + d.completionTokens) + ' tokens total';

    el('costs-savings-usd').textContent = fmtUsd(d.savingsIfLocalUsd);

    el('costs-session-elapsed').textContent = fmtElapsed(d.elapsedMs);
    var perCallUsd = d.calls > 0 ? (d.totalUsd / d.calls) : 0;
    el('costs-rate-hint').textContent = d.calls > 0
        ? '~' + fmtUsd(perCallUsd) + ' per call'
        : '—';

    var byModelEl = el('costs-by-model');
    var models = d.byModel || [];
    if (!models.length) {
      byModelEl.innerHTML = '<div class="costs-loading">No LLM calls yet this session. Send a chat message to start tracking.</div>';
      return;
    }
    models.sort(function (a, b) { return (b.usd || 0) - (a.usd || 0); });
    byModelEl.innerHTML =
        '<div class="mhead"><span>Model</span><span>Calls</span><span>Prompt / Out</span><span>Local equiv</span><span>Cost</span></div>'
        + models.map(function (m) {
            return '<div class="mrow">'
              + '<div class="mname" title="' + esc(m.model) + '">' + esc(m.model) + '</div>'
              + '<div class="mnum">' + m.calls + '</div>'
              + '<div class="mnum">' + fmtTokens(m.prompt) + ' / ' + fmtTokens(m.completion) + '</div>'
              + '<div class="mnum" title="Same work on Ollama would be $0">$0.00</div>'
              + '<div class="mcost">' + fmtUsd(m.usd) + '</div>'
              + '</div>';
          }).join('');
  }

  function renderHistory(h) {
    var wrap = el('costs-daily');
    var days = (h && h.daily) || [];
    if (!days.length) {
      wrap.innerHTML = '<div class="costs-loading">No historical usage yet — check back after a few days of chats.</div>';
      return;
    }
    var max = Math.max.apply(null, days.map(function (d) { return d.usd || 0; }).concat([0.0001]));
    wrap.innerHTML = days.map(function (d) {
      var pct = Math.min(100, Math.max(2, Math.round((d.usd / max) * 100)));
      return '<div class="drow">'
        + '<div class="ddate">' + esc(d.date) + '</div>'
        + '<div class="dbar"><div class="dbar-fill" style="width:' + pct + '%"></div></div>'
        + '<div class="dnum">' + d.calls + ' calls</div>'
        + '<div class="dnum">' + fmtTokens((d.prompt || 0) + (d.completion || 0)) + '</div>'
        + '<div class="dcost">' + fmtUsd(d.usd) + '</div>'
        + '</div>';
    }).join('');
  }

  async function resetSession() {
    try {
      await fetch('/api/cost/reset', { method: 'POST' });
    } catch (e) {}
    refresh();
  }

  function startPolling() {
    if (pollTimer) return;
    isActive = true;
    refresh();
    pollTimer = setInterval(function () {
      // Only poll while Costs tab is active (save cycles)
      var active = document.querySelector('#tab-costs.active, #tab-costs.tab-visible');
      if (active) refresh();
    }, 3000);
  }

  window.MinsBotCostsInit = function () {
    if (!el('costs-title')) return;
    var resetBtn = el('costs-reset');
    if (resetBtn && !resetBtn.__wired) {
      resetBtn.__wired = true;
      resetBtn.addEventListener('click', resetSession);
    }
    startPolling();
  };
})();
