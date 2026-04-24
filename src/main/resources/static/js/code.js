(() => {
  const $ = (id) => document.getElementById(id);
  const modes = $('modes');
  const go = $('go');
  const status = $('status');
  const results = $('results');
  let mode = 'primary';

  modes.addEventListener('click', (e) => {
    const btn = e.target.closest('.mode-btn');
    if (!btn) return;
    modes.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    mode = btn.dataset.mode;
  });

  go.addEventListener('click', async () => {
    const task = $('task').value.trim();
    const workingDir = $('workingDir').value.trim();
    if (!task) { status.textContent = 'Enter a task first.'; return; }
    if (!workingDir) { status.textContent = 'Enter a working directory.'; return; }

    go.disabled = true;
    status.textContent = `Running (${mode})…`;
    results.innerHTML = '';
    const started = Date.now();

    try {
      const resp = await fetch('/api/code/generate', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ task, workingDir, mode })
      });
      const data = await resp.json();

      if (data.primary !== undefined) render('Primary · Claude Code CLI', data.primary);
      if (data.special !== undefined) render('Special · Anthropic SDK', data.special);
      if (data.error) render('Error', data.error, true);

      const took = ((data.elapsedMs ?? (Date.now() - started)) / 1000).toFixed(1);
      status.textContent = `Done in ${took}s.`;
    } catch (err) {
      render('Request failed', String(err), true);
      status.textContent = '';
    } finally {
      go.disabled = false;
    }
  });

  function render(title, text, isError = false) {
    const div = document.createElement('div');
    div.className = 'result' + (isError ? ' err' : '');
    const isErr = isError || (typeof text === 'string' && /^Error|not configured|not found/i.test(text));
    div.innerHTML = `
      <h3>${escapeHtml(title)} <span class="badge">${isErr ? 'error' : 'ok'}</span></h3>
      <pre></pre>`;
    div.querySelector('pre').textContent = text || '(empty)';
    results.appendChild(div);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    }[c]));
  }
})();
