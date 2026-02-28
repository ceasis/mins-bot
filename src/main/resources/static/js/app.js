(function () {
  const root = document.getElementById('root');
  const messagesEl = document.getElementById('messages');
  const inputEl = document.getElementById('input');
  const sendBtn = document.getElementById('send-btn');
  const voiceBtn = document.getElementById('voice-btn');
  const voiceStatus = document.getElementById('voice-status');
  const clearBtn = document.getElementById('clear-btn');
  const headerThinkingEl = document.getElementById('header-thinking');
  const quitCountdownEl = document.getElementById('quit-countdown');
  const quitCountdownLabel = document.getElementById('quit-countdown-label');
  const quitCountdownFill = document.getElementById('quit-countdown-fill');

  let quitCountdownInterval = null;

  function stopQuitCountdown() {
    if (quitCountdownInterval) {
      clearInterval(quitCountdownInterval);
      quitCountdownInterval = null;
    }
    if (quitCountdownEl) quitCountdownEl.hidden = true;
  }

  function startQuitCountdown(seconds) {
    stopQuitCountdown();
    if (!quitCountdownEl || !quitCountdownLabel || !quitCountdownFill) return;
    var total = seconds;
    var remaining = total;
    quitCountdownEl.hidden = false;
    function tick() {
      remaining -= 1;
      var pct = Math.max(0, (remaining / total) * 100);
      quitCountdownLabel.textContent = 'Quitting in ' + remaining + 's';
      quitCountdownFill.style.width = pct + '%';
      if (remaining <= 0) {
        clearInterval(quitCountdownInterval);
        quitCountdownInterval = null;
      }
    }
    quitCountdownLabel.textContent = 'Quitting in ' + remaining + 's';
    quitCountdownFill.style.width = '100%';
    tick();
    quitCountdownInterval = setInterval(tick, 1000);
  }

  // ═══ Window expand/collapse ═══

  function focusInput() {
    if (!inputEl || typeof inputEl.focus !== 'function') return;
    try { inputEl.focus({ preventScroll: true }); } catch (err) { inputEl.focus(); }
  }

  function focusInputSoon() {
    setTimeout(function () {
      if (!root.classList.contains('expanded')) return;
      focusInput();
    }, 60);
  }

  function ensureInputFocus() {
    let tries = 0;
    const timer = setInterval(function () {
      tries += 1;
      if (!root.classList.contains('expanded')) { clearInterval(timer); return; }
      if (document.activeElement === inputEl) { clearInterval(timer); return; }
      focusInput();
      if (tries >= 8) clearInterval(timer);
    }, 50);
  }

  function expand() {
    root.classList.add('expanded');
    if (typeof window.java !== 'undefined' && window.java.expand) window.java.expand();
    focusInputSoon();
    ensureInputFocus();
  }

  // ═══ Anti-drag (prevent "Copy" ghost on Windows) ═══

  function noDrag(e) { e.preventDefault(); e.stopPropagation(); return false; }
  root.addEventListener('dragstart', noDrag, true);
  document.addEventListener('dragover', noDrag, true);
  document.addEventListener('drop', noDrag, true);
  document.addEventListener('dragenter', noDrag, true);
  document.addEventListener('dragleave', noDrag, true);

  // Clear chat — also clears AI memory so the bot starts fresh
  if (clearBtn) {
    clearBtn.addEventListener('click', function () {
      messagesEl.innerHTML = '';
      appendMessage('Chat cleared. How can I help?', false);
      fetch('/api/chat/clear', { method: 'POST' }).catch(function () {});
    });
  }

  inputEl.addEventListener('mousedown', function () { focusInput(); });

  // Auto-focus input when typing
  document.addEventListener('keydown', function (e) {
    if (!root.classList.contains('expanded')) return;
    if (document.activeElement === inputEl) return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key && e.key.length === 1) focusInput();
  }, true);

  // ═══ Message rendering ═══

  function getTimeStr() {
    var d = new Date();
    var h = d.getHours();
    var m = d.getMinutes();
    var s = d.getSeconds();
    return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  // Detect Windows file paths and make them clickable
  var pathRegex = /([A-Za-z]:\\[^\s<>"',:;!?\])(]+(?:\\[^\s<>"',:;!?\])(]+)*)/g;

  function linkifyPaths(text) {
    var parts = [];
    var lastIndex = 0;
    var match;
    pathRegex.lastIndex = 0;
    while ((match = pathRegex.exec(text)) !== null) {
      if (match.index > lastIndex) {
        parts.push({ type: 'text', value: text.substring(lastIndex, match.index) });
      }
      parts.push({ type: 'path', value: match[0] });
      lastIndex = pathRegex.lastIndex;
    }
    if (lastIndex < text.length) {
      parts.push({ type: 'text', value: text.substring(lastIndex) });
    }
    return parts;
  }

  function buildMessageContent(el, text) {
    // Extract [img:URL] markers and split text around them
    var imgRegex = /\[img:(\/api\/screenshot\?file=[^\]]+)\]/g;
    var segments = [];
    var lastIdx = 0;
    var match;
    while ((match = imgRegex.exec(text)) !== null) {
      if (match.index > lastIdx) {
        segments.push({ type: 'text', value: text.substring(lastIdx, match.index) });
      }
      segments.push({ type: 'img', value: match[1] });
      lastIdx = imgRegex.lastIndex;
    }
    if (lastIdx < text.length) {
      segments.push({ type: 'text', value: text.substring(lastIdx) });
    }

    // If no images, fall back to original text+path rendering
    if (segments.length === 0 || (segments.length === 1 && segments[0].type === 'text')) {
      var parts = linkifyPaths(text);
      if (parts.length <= 1 && (parts.length === 0 || parts[0].type === 'text')) {
        el.textContent = text;
        return;
      }
      for (var i = 0; i < parts.length; i++) {
        if (parts[i].type === 'text') {
          el.appendChild(document.createTextNode(parts[i].value));
        } else {
          var link = document.createElement('span');
          link.className = 'path-link';
          link.textContent = parts[i].value;
          link.dataset.path = parts[i].value;
          link.addEventListener('click', function (e) {
            e.stopPropagation();
            var p = this.dataset.path;
            fetch('/api/open-path', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ path: p })
            });
          });
          el.appendChild(link);
        }
      }
      return;
    }

    // Render segments with embedded images
    for (var j = 0; j < segments.length; j++) {
      if (segments[j].type === 'text') {
        var textParts = linkifyPaths(segments[j].value);
        for (var k = 0; k < textParts.length; k++) {
          if (textParts[k].type === 'text') {
            el.appendChild(document.createTextNode(textParts[k].value));
          } else {
            var plink = document.createElement('span');
            plink.className = 'path-link';
            plink.textContent = textParts[k].value;
            plink.dataset.path = textParts[k].value;
            plink.addEventListener('click', function (e) {
              e.stopPropagation();
              fetch('/api/open-path', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path: this.dataset.path })
              });
            });
            el.appendChild(plink);
          }
        }
      } else {
        var img = document.createElement('img');
        img.src = segments[j].value;
        img.className = 'chat-screenshot';
        img.alt = 'Screenshot';
        img.addEventListener('click', function (e) {
          e.stopPropagation();
          window.open(this.src, '_blank');
        });
        el.appendChild(img);
      }
    }
  }

  function addCopyListener(msg) {
    msg.addEventListener('click', function () {
      navigator.clipboard.writeText(msg.textContent).then(function () {
        msg.classList.add('copied');
        setTimeout(function () { msg.classList.remove('copied'); }, 600);
      });
    });
  }

  function appendMessage(text, isUser) {
    var wrapper = document.createElement('div');
    wrapper.className = 'message-wrapper ' + (isUser ? 'user' : 'bot');

    var msg = document.createElement('div');
    msg.className = 'message ' + (isUser ? 'user' : 'bot');
    buildMessageContent(msg, text);

    var time = document.createElement('div');
    time.className = 'message-time';
    time.textContent = getTimeStr();

    addCopyListener(msg);

    wrapper.appendChild(msg);
    wrapper.appendChild(time);
    messagesEl.appendChild(wrapper);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function showThinking() {
    if (headerThinkingEl) {
      headerThinkingEl.hidden = false;
      headerThinkingEl.removeAttribute('aria-hidden');
    }
    var el = document.createElement('div');
    el.className = 'thinking';
    el.id = 'thinking-indicator';
    for (var i = 0; i < 3; i++) {
      var dot = document.createElement('div');
      dot.className = 'thinking-dot';
      el.appendChild(dot);
    }
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function hideThinking() {
    if (headerThinkingEl) {
      headerThinkingEl.hidden = true;
      headerThinkingEl.setAttribute('aria-hidden', 'true');
    }
    var el = document.getElementById('thinking-indicator');
    if (el) el.remove();
  }

  function appendStatus(text) {
    var el = document.createElement('div');
    el.className = 'message-status';
    el.textContent = text;
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function clearStatusMessages() {
    var items = messagesEl.querySelectorAll('.message-status');
    for (var i = 0; i < items.length; i++) items[i].remove();
  }

  function setVoiceStatus(text, show) {
    voiceStatus.textContent = text || '';
    voiceStatus.hidden = !show;
  }

  // ═══ Input history (arrow up/down) ═══

  var inputHistory = [];
  var historyIndex = -1;
  var savedInput = '';

  function addToInputHistory(text) {
    if (!text || !text.trim()) return;
    var trimmed = text.trim();
    // Avoid consecutive duplicates
    if (inputHistory.length > 0 && inputHistory[inputHistory.length - 1] === trimmed) return;
    inputHistory.push(trimmed);
    // Cap at 200 entries
    if (inputHistory.length > 200) inputHistory.shift();
  }

  // ═══ Send message ═══

  let sendingMessage = false;
  let statusPollTimer = null;

  function startStatusPolling() {
    if (statusPollTimer) return;
    statusPollTimer = setInterval(async function () {
      try {
        var res = await fetch('/api/chat/status');
        var data = await res.json();
        if (data.messages && data.messages.length > 0) {
          hideThinking();
          for (var i = 0; i < data.messages.length; i++) {
            appendStatus(data.messages[i]);
          }
        }
      } catch (e) { /* ignore */ }
    }, 500);
  }

  function stopStatusPolling() {
    if (statusPollTimer) {
      clearInterval(statusPollTimer);
      statusPollTimer = null;
    }
  }

  async function sendMessage(text) {
    if (!text || !text.trim()) return;
    if (sendingMessage) return;
    stopQuitCountdown();
    sendingMessage = true;
    const msg = text.trim();
    addToInputHistory(msg);
    historyIndex = -1;
    savedInput = '';
    inputEl.value = '';
    appendMessage(msg, true);
    showThinking();
    startStatusPolling();

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg })
      });
      const data = await res.json();
      stopStatusPolling();
      clearStatusMessages();
      hideThinking();
      appendMessage(data.reply || 'No reply.', false);
      if (data.quitCountdownSeconds) {
        startQuitCountdown(data.quitCountdownSeconds);
      }
    } catch (e) {
      stopStatusPolling();
      clearStatusMessages();
      hideThinking();
      appendMessage('Could not reach server.', false);
    } finally {
      sendingMessage = false;
    }
  }

  sendBtn.addEventListener('click', function () {
    sendMessage(inputEl.value);
    focusInputSoon();
  });

  // Arrow up/down — cycle through input history
  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowUp') {
      if (inputHistory.length === 0) return;
      e.preventDefault();
      if (historyIndex === -1) savedInput = inputEl.value || '';
      if (historyIndex < inputHistory.length - 1) {
        historyIndex++;
        inputEl.value = inputHistory[inputHistory.length - 1 - historyIndex];
      }
      return;
    }
    if (e.key === 'ArrowDown') {
      if (historyIndex < 0) return;
      e.preventDefault();
      historyIndex--;
      if (historyIndex < 0) {
        inputEl.value = savedInput;
      } else {
        inputEl.value = inputHistory[inputHistory.length - 1 - historyIndex];
      }
      return;
    }
  });

  // Enter key — multiple fallbacks for JavaFX WebView
  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage(inputEl.value);
      focusInputSoon();
    }
  });

  document.addEventListener('keydown', function (e) {
    if (!root.classList.contains('expanded')) return;
    if (document.activeElement !== inputEl) return;
    if (e.key !== 'Enter' || e.shiftKey) return;
    e.preventDefault();
    sendMessage(inputEl.value);
    focusInputSoon();
  }, true);

  inputEl.addEventListener('keyup', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (inputEl.value && inputEl.value.trim()) {
        sendMessage(inputEl.value);
        focusInputSoon();
      }
    }
  });

  document.addEventListener('keyup', function (e) {
    if (!root.classList.contains('expanded')) return;
    if (e.keyCode !== 13 || e.shiftKey) return;
    if (inputEl.value && inputEl.value.trim()) {
      sendMessage(inputEl.value);
      focusInputSoon();
    }
  }, true);

  // ═══ Voice ═══

  let recognition = null;
  let isListening = false;
  let voiceEnabled = false;
  let nativeVoicePollTimer = null;

  function hasNativeVoice() {
    return typeof window.java !== 'undefined'
      && typeof window.java.isNativeVoiceAvailable === 'function'
      && window.java.isNativeVoiceAvailable();
  }

  function setListeningUi(listening) {
    isListening = !!listening;
    voiceBtn.classList.toggle('listening', isListening);
    if (isListening) {
      setVoiceStatus('Listening...', true);
    } else if (!voiceEnabled) {
      setVoiceStatus('', false);
    }
  }

  function startNativeVoicePolling() {
    if (nativeVoicePollTimer) { clearInterval(nativeVoicePollTimer); nativeVoicePollTimer = null; }
    nativeVoicePollTimer = setInterval(function () {
      if (typeof window.java === 'undefined') return;
      var err = window.java.consumeNativeVoiceError();
      if (err) {
        setListeningUi(false);
        setVoiceStatus(err, true);
        clearInterval(nativeVoicePollTimer);
        nativeVoicePollTimer = null;
        return;
      }

      var text = window.java.consumeNativeVoiceTranscript();
      if (text) {
        var prefix = '__AUDIO_RESULT__';
        if (text.indexOf(prefix) === 0) {
          try {
            var payload = JSON.parse(text.substring(prefix.length));
            if (payload.transcript) appendMessage(payload.transcript, true);
            if (payload.reply) appendMessage(payload.reply, false);
          } catch (parseErr) {
            appendMessage(text, false);
          }
        } else {
          appendMessage(text, false);
        }
        clearInterval(nativeVoicePollTimer);
        nativeVoicePollTimer = null;
        if (voiceEnabled) {
          setTimeout(function () {
            if (!voiceEnabled) return;
            setListeningUi(true);
            if (window.java.startNativeVoice()) { startNativeVoicePolling(); }
            else { setListeningUi(false); }
          }, 300);
        } else {
          setListeningUi(false);
          setVoiceStatus('', false);
        }
        return;
      }

      if (!window.java.isNativeVoiceListening()) {
        clearInterval(nativeVoicePollTimer);
        nativeVoicePollTimer = null;
        if (voiceEnabled) {
          setTimeout(function () {
            if (!voiceEnabled) return;
            setListeningUi(true);
            if (window.java.startNativeVoice()) { startNativeVoicePolling(); }
            else { setListeningUi(false); }
          }, 300);
        } else {
          setListeningUi(false);
        }
      }
    }, 180);
  }

  function startListening() {
    if (isListening) return;
    setListeningUi(true);
    if (hasNativeVoice()) {
      if (window.java.startNativeVoice()) { startNativeVoicePolling(); }
      else { setListeningUi(false); }
      return;
    }
    if (recognition) {
      try { recognition.start(); } catch (e) { /* already started */ }
    }
  }

  function setVoiceEnabled(enabled) {
    voiceEnabled = !!enabled;
    if (!voiceEnabled && isListening) {
      if (hasNativeVoice() && typeof window.java.stopNativeVoice === 'function') {
        window.java.stopNativeVoice();
      } else if (recognition) {
        recognition.stop();
      }
      setListeningUi(false);
    }
    voiceBtn.classList.toggle('off', !voiceEnabled);
    voiceBtn.title = voiceEnabled ? 'Listening' : 'Voice is off';
    voiceBtn.setAttribute('aria-label', voiceEnabled ? 'Listening' : 'Voice is off');
    if (!voiceEnabled) {
      setVoiceStatus('', false);
    }
    if (voiceEnabled && !isListening) {
      setTimeout(startListening, 200);
    }
  }

  if (!hasNativeVoice() && ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window)) {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = 'en-US';

    recognition.onresult = function (e) {
      const last = e.results.length - 1;
      const transcript = e.results[last][0].transcript;
      if (e.results[last].isFinal) {
        inputEl.value = transcript;
        setVoiceStatus('', false);
      } else {
        setVoiceStatus('Listening: ' + transcript, true);
      }
    };

    recognition.onend = function () {
      if (voiceEnabled) {
        setTimeout(function () {
          if (!voiceEnabled) return;
          setListeningUi(true);
          try { recognition.start(); } catch (e) { /* already started */ }
        }, 300);
      } else {
        setListeningUi(false);
        setVoiceStatus('', false);
      }
    };

    recognition.onerror = function (ev) {
      if (voiceEnabled && ev.error !== 'not-allowed' && ev.error !== 'service-not-allowed') {
        setTimeout(function () {
          if (!voiceEnabled) return;
          setListeningUi(true);
          try { recognition.start(); } catch (e) { /* already started */ }
        }, 500);
      } else {
        setListeningUi(false);
        setVoiceStatus('Voice error. Try again.', true);
      }
    };
  }

  voiceBtn.addEventListener('click', function () {
    if (!recognition && !hasNativeVoice()) {
      setVoiceStatus('Voice not supported.', true);
      return;
    }
    setVoiceEnabled(!voiceEnabled);
  });

  // Don't auto-start voice — user clicks mic to enable

  // ═══ Async agent results polling ═══

  setInterval(async function () {
    try {
      var res = await fetch('/api/chat/async');
      var data = await res.json();
      if (data.hasResult && data.reply) {
        appendMessage(data.reply, false);
      }
    } catch (e) { /* ignore */ }
  }, 2000);

  // ═══ Browser tab ═══

  const tabs = document.querySelectorAll('.tab');
  const tabContents = document.querySelectorAll('.tab-content');
  const browserFrame = document.getElementById('browser-frame');
  const browserEmpty = document.getElementById('browser-empty');
  const browserUrl = document.getElementById('browser-url');
  const browserGo = document.getElementById('browser-go');
  const browserBack = document.getElementById('browser-back');
  const browserForward = document.getElementById('browser-forward');
  const browserRefresh = document.getElementById('browser-refresh');

  let browserPollTimer = null;
  let lastBlobUrl = null;

  tabs.forEach(function (tab) {
    tab.addEventListener('click', function () {
      tabs.forEach(function (t) { t.classList.remove('active'); });
      tabContents.forEach(function (c) { c.classList.remove('active'); });
      tab.classList.add('active');
      document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
      if (tab.dataset.tab === 'browser') startBrowserPolling();
      else stopBrowserPolling();
      if (tab.dataset.tab === 'skills') { loadSkillsList(); loadPublishedList(); }
      if (tab.dataset.tab === 'schedules') loadSchedules();
      if (tab.dataset.tab === 'todos') loadTodos();
      if (tab.dataset.tab === 'directives') loadDirectives();
    });
  });

  function startBrowserPolling() {
    if (browserPollTimer) return;
    refreshBrowserView();
    browserPollTimer = setInterval(refreshBrowserView, 1000);
  }

  function stopBrowserPolling() {
    if (browserPollTimer) { clearInterval(browserPollTimer); browserPollTimer = null; }
  }

  async function refreshBrowserView() {
    try {
      var res = await fetch('/api/browser/screenshot');
      if (res.ok && res.status !== 204) {
        var blob = await res.blob();
        if (lastBlobUrl) URL.revokeObjectURL(lastBlobUrl);
        lastBlobUrl = URL.createObjectURL(blob);
        browserFrame.src = lastBlobUrl;
        browserFrame.style.display = 'block';
        browserEmpty.style.display = 'none';
      }
      var info = await fetch('/api/browser/info');
      if (info.ok) {
        var data = await info.json();
        browserUrl.value = data.url || '';
      }
    } catch (e) { /* ignore */ }
  }

  function browserNavigate(url) {
    if (!url) return;
    if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
    fetch('/api/browser/navigate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: url })
    }).then(function () { setTimeout(refreshBrowserView, 500); });
  }

  if (browserGo) browserGo.addEventListener('click', function () { browserNavigate(browserUrl.value); });
  if (browserUrl) browserUrl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); browserNavigate(browserUrl.value); }
  });
  if (browserBack) browserBack.addEventListener('click', function () {
    fetch('/api/browser/back', { method: 'POST' }).then(function () { setTimeout(refreshBrowserView, 500); });
  });
  if (browserForward) browserForward.addEventListener('click', function () {
    fetch('/api/browser/forward', { method: 'POST' }).then(function () { setTimeout(refreshBrowserView, 500); });
  });
  if (browserRefresh) browserRefresh.addEventListener('click', function () {
    fetch('/api/browser/refresh', { method: 'POST' }).then(function () { setTimeout(refreshBrowserView, 500); });
  });

  // ═══ Start with clear chat (no history loaded) ═══

  function appendHistoryMessage(text, isUser, time) {
    var wrapper = document.createElement('div');
    wrapper.className = 'message-wrapper ' + (isUser ? 'user' : 'bot');

    var msg = document.createElement('div');
    msg.className = 'message ' + (isUser ? 'user' : 'bot');
    buildMessageContent(msg, text);

    var timeEl = document.createElement('div');
    timeEl.className = 'message-time';
    timeEl.textContent = time;

    addCopyListener(msg);

    wrapper.appendChild(msg);
    wrapper.appendChild(timeEl);
    messagesEl.appendChild(wrapper);
  }

  // ═══ Skills tab ═══

  var uploadArea = document.getElementById('upload-area');
  var skillFileInput = document.getElementById('skill-file-input');
  var skillsListEl = document.getElementById('skills-list');

  function loadSkillsList() {
    if (!skillsListEl) return;
    fetch('/api/skills/plugins').then(function (r) { return r.json(); }).then(function (plugins) {
      if (!plugins || plugins.length === 0) {
        skillsListEl.innerHTML = '<div class="skills-empty">No plugins found. Upload a .jar file to get started.</div>';
        return;
      }
      var html = '';
      plugins.forEach(function (p) {
        var badge = p.loaded
          ? '<span class="skill-badge loaded">LOADED</span>'
          : '<span class="skill-badge unloaded">NOT LOADED</span>';
        var toggleBtn = p.loaded
          ? '<button class="skill-btn unload" onclick="window._skillAction(\'unload\',\'' + p.name + '\')">Unload</button>'
          : '<button class="skill-btn load" onclick="window._skillAction(\'load\',\'' + p.name + '\')">Load</button>';
        html += '<div class="skill-item">'
          + '<div class="skill-info">'
          + '<div class="skill-name">' + escapeHtml(p.name) + '</div>'
          + '<div class="skill-meta">' + p.sizeFormatted + (p.loaded ? ' &middot; ' + p.classCount + ' classes' : '') + '</div>'
          + '</div>'
          + badge
          + '<div class="skill-actions">'
          + toggleBtn
          + '<button class="skill-btn delete" onclick="window._skillAction(\'delete\',\'' + p.name + '\')">Delete</button>'
          + '</div></div>';
      });
      skillsListEl.innerHTML = html;
    }).catch(function () {
      skillsListEl.innerHTML = '<div class="skills-empty">Failed to load plugins.</div>';
    });
  }

  function escapeHtml(s) {
    var d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  window._skillAction = function (action, name) {
    var url, method;
    if (action === 'load') { url = '/api/skills/' + encodeURIComponent(name) + '/load'; method = 'POST'; }
    else if (action === 'unload') { url = '/api/skills/' + encodeURIComponent(name) + '/unload'; method = 'POST'; }
    else if (action === 'delete') { url = '/api/skills/' + encodeURIComponent(name); method = 'DELETE'; }
    else return;
    fetch(url, { method: method }).then(function () { loadSkillsList(); });
  };

  function uploadSkillFile(file) {
    if (!file || !file.name.toLowerCase().endsWith('.jar')) return;
    var formData = new FormData();
    formData.append('file', file);
    fetch('/api/skills/upload', { method: 'POST', body: formData })
      .then(function () { loadSkillsList(); });
  }

  if (uploadArea) {
    uploadArea.addEventListener('click', function () { skillFileInput.click(); });
    uploadArea.addEventListener('dragover', function (e) {
      e.preventDefault(); uploadArea.classList.add('drag-over');
    });
    uploadArea.addEventListener('dragleave', function () {
      uploadArea.classList.remove('drag-over');
    });
    uploadArea.addEventListener('drop', function (e) {
      e.preventDefault(); uploadArea.classList.remove('drag-over');
      if (e.dataTransfer.files.length > 0) uploadSkillFile(e.dataTransfer.files[0]);
    });
  }
  if (skillFileInput) {
    skillFileInput.addEventListener('change', function () {
      if (skillFileInput.files.length > 0) uploadSkillFile(skillFileInput.files[0]);
      skillFileInput.value = '';
    });
  }

  // ═══ Publish skills ═══

  var publishBtn = document.getElementById('publish-btn');
  var publishAuthor = document.getElementById('publish-author');
  var publishDesc = document.getElementById('publish-desc');
  var publishFile = document.getElementById('publish-file');
  var publishedListEl = document.getElementById('published-list');

  function loadPublishedList() {
    if (!publishedListEl) return;
    fetch('/api/skills/published').then(function (r) { return r.json(); }).then(function (items) {
      if (!items || items.length === 0) {
        publishedListEl.innerHTML = '';
        return;
      }
      var html = '<div class="section-title" style="margin-top:4px">Published Skills</div>';
      items.forEach(function (p) {
        html += '<div class="skill-item">'
          + '<div class="skill-info">'
          + '<div class="skill-name">' + escapeHtml(p.name) + '</div>'
          + '<div class="skill-meta">' + escapeHtml(p.author || '') + (p.date ? ' &middot; ' + p.date : '') + '</div>'
          + (p.description ? '<div class="skill-meta">' + escapeHtml(p.description) + '</div>' : '')
          + '</div>'
          + '<div class="skill-actions">'
          + '<button class="skill-btn delete" onclick="window._deletePublished(\'' + p.name + '\')">Delete</button>'
          + '</div></div>';
      });
      publishedListEl.innerHTML = html;
    }).catch(function () { publishedListEl.innerHTML = ''; });
  }

  window._deletePublished = function (name) {
    fetch('/api/skills/published/' + encodeURIComponent(name), { method: 'DELETE' })
      .then(function () { loadPublishedList(); });
  };

  if (publishBtn) {
    publishBtn.addEventListener('click', function () {
      if (!publishFile.files.length) return;
      if (!publishAuthor.value.trim()) return;
      var fd = new FormData();
      fd.append('file', publishFile.files[0]);
      fd.append('author', publishAuthor.value.trim());
      fd.append('description', publishDesc.value.trim());
      fetch('/api/skills/publish', { method: 'POST', body: fd })
        .then(function () {
          publishAuthor.value = '';
          publishDesc.value = '';
          publishFile.value = '';
          loadPublishedList();
        });
    });
  }

  // ═══ Schedules tab ═══

  var schedulesContainer = document.getElementById('schedules-container');

  function loadSchedules() {
    if (!schedulesContainer) return;
    fetch('/api/tabs/schedules').then(function (r) { return r.json(); }).then(function (sections) {
      if (!sections || sections.length === 0) {
        schedulesContainer.innerHTML = '<div class="tab-empty">No schedules configured. Ask the bot to set up reminders.</div>';
        return;
      }
      var html = '';
      sections.forEach(function (s) {
        html += '<div class="schedule-card">'
          + '<div class="schedule-title">' + escapeHtml(s.section) + '</div>'
          + '<ul class="schedule-entries">';
        s.entries.forEach(function (e) {
          html += '<li>' + escapeHtml(e) + '</li>';
        });
        html += '</ul></div>';
      });
      schedulesContainer.innerHTML = html;
    }).catch(function () {
      schedulesContainer.innerHTML = '<div class="tab-empty">Failed to load schedules.</div>';
    });
  }

  // ═══ Todo tab ═══

  var todosContainer = document.getElementById('todos-container');

  function loadTodos() {
    if (!todosContainer) return;
    fetch('/api/tabs/todos').then(function (r) { return r.json(); }).then(function (tasks) {
      if (!tasks || tasks.length === 0) {
        todosContainer.innerHTML = '<div class="tab-empty">No tasks. Ask the bot to create a plan.</div>';
        return;
      }
      var html = '';
      tasks.forEach(function (t) {
        html += '<div class="todo-card">'
          + '<div class="todo-title">' + escapeHtml(t.title) + '</div>'
          + '<div class="todo-time">' + escapeHtml(t.timestamp) + '</div>'
          + '<div class="todo-steps">';
        if (t.steps) {
          t.steps.forEach(function (s) {
            var isDone = s.status === 'DONE';
            html += '<div class="todo-step ' + (isDone ? 'done' : 'pending') + '">'
              + '<span class="step-icon">' + (isDone ? '\u2713' : '\u25CB') + '</span>'
              + escapeHtml(s.num + '. ' + s.text)
              + '</div>';
          });
        }
        html += '</div></div>';
      });
      todosContainer.innerHTML = html;
    }).catch(function () {
      todosContainer.innerHTML = '<div class="tab-empty">Failed to load tasks.</div>';
    });
  }

  // ═══ Directives tab ═══

  var directivesListEl = document.getElementById('directives-list');
  var directiveInput = document.getElementById('directive-input');
  var directiveAddBtn = document.getElementById('directive-add-btn');

  function loadDirectives() {
    if (!directivesListEl) return;
    fetch('/api/tabs/directives').then(function (r) { return r.json(); }).then(function (items) {
      if (!items || items.length === 0) {
        directivesListEl.innerHTML = '<div class="tab-empty">No directives. Add permanent objectives for the bot.</div>';
        return;
      }
      var html = '';
      items.forEach(function (text, i) {
        html += '<div class="directive-item">'
          + '<span class="directive-num">' + (i + 1) + '.</span>'
          + '<span class="directive-text">' + escapeHtml(text) + '</span>'
          + '<button class="directive-delete" onclick="window._removeDirective(' + (i + 1) + ')" title="Remove">\u2715</button>'
          + '</div>';
      });
      directivesListEl.innerHTML = html;
    }).catch(function () {
      directivesListEl.innerHTML = '<div class="tab-empty">Failed to load directives.</div>';
    });
  }

  window._removeDirective = function (pos) {
    fetch('/api/tabs/directives/' + pos, { method: 'DELETE' })
      .then(function () { loadDirectives(); });
  };

  function addDirective() {
    if (!directiveInput || !directiveInput.value.trim()) return;
    fetch('/api/tabs/directives', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ directive: directiveInput.value.trim() })
    }).then(function () {
      directiveInput.value = '';
      loadDirectives();
    });
  }

  if (directiveAddBtn) {
    directiveAddBtn.addEventListener('click', addDirective);
  }
  if (directiveInput) {
    directiveInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); addDirective(); }
    });
  }

  // On startup: show clear chat with greeting (same as Clear button)
  messagesEl.innerHTML = '';
  appendMessage('Chat cleared. How can I help?', false);
})();
