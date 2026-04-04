(function () {
  const root = document.getElementById('root');
  const messagesEl = document.getElementById('messages');
  const inputEl = document.getElementById('input');
  const sendBtn = document.getElementById('send-btn');
  const voiceBtn = document.getElementById('voice-btn');
  const voiceStatus = document.getElementById('voice-status');
  const clearBtn = document.getElementById('clear-btn');
  const headerThinkingEl = document.getElementById('header-thinking');
  const headerWatchEl = document.getElementById('header-watch');
  const headerControlEl = document.getElementById('header-control');
  const headerListenEl = document.getElementById('header-listen');
  const headerMouthEl = document.getElementById('header-mouth');
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

  // ═══ Custom title bar: drag, minimize, close ═══
  (function () {
    var titleBarDrag = document.querySelector('.title-bar-drag');
    var titleBarMinimize = document.getElementById('title-bar-minimize');
    var titleBarClose = document.getElementById('title-bar-close');
    var dragging = false;
    var startStageX, startStageY, startClientX, startClientY;

    function onTitleBarMouseDown(e) {
      if (typeof window.java === 'undefined' || !window.java.getX) return;
      if (e.button !== 0) return;
      dragging = true;
      startClientX = e.clientX;
      startClientY = e.clientY;
      startStageX = window.java.getX();
      startStageY = window.java.getY();
    }

    function onMouseMove(e) {
      if (!dragging || typeof window.java === 'undefined' || !window.java.setPosition) return;
      var x = startStageX + startClientX - e.clientX;
      var y = startStageY + startClientY - e.clientY;
      window.java.setPosition(x, y);
    }

    function onMouseUp() {
      dragging = false;
    }

    if (titleBarDrag) {
      titleBarDrag.addEventListener('mousedown', onTitleBarMouseDown);
    }
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);

    if (titleBarMinimize) {
      titleBarMinimize.addEventListener('click', function () {
        if (typeof window.java !== 'undefined' && window.java.minimize) window.java.minimize();
      });
    }
    if (titleBarClose) {
      titleBarClose.addEventListener('click', function () {
        if (typeof window.java !== 'undefined' && window.java.close) window.java.close();
      });
    }
  })();

  // Clear chat — also clears AI memory so the bot starts fresh
  if (clearBtn) {
    clearBtn.addEventListener('click', function () {
      messagesEl.innerHTML = '';
      appendMessage('Chat cleared. How can I help?', false);
      // Clear watch feed panel
      var wfInner = document.getElementById('watch-feed-inner');
      var wfEl = document.getElementById('watch-feed');
      if (wfInner) wfInner.innerHTML = '';
      if (wfEl) wfEl.hidden = true;
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
    // Translation format: <small>original</small>\ntranslation — render as HTML
    if (text.indexOf('<small>') !== -1 && text.indexOf('</small>') !== -1) {
      el.innerHTML = text.replace(/\n/g, '<br>');
      return;
    }

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

  function appendMessage(text, isUser, typewriter) {
    var wrapper = document.createElement('div');
    wrapper.className = 'message-wrapper ' + (isUser ? 'user' : 'bot');

    var msg = document.createElement('div');
    msg.className = 'message ' + (isUser ? 'user' : 'bot');

    var time = document.createElement('div');
    time.className = 'message-time';
    time.textContent = getTimeStr();

    wrapper.appendChild(msg);
    wrapper.appendChild(time);
    messagesEl.appendChild(wrapper);

    if (typewriter && !isUser && text) {
      // Typewriter animation: reveal character by character
      var i = 0;
      msg.textContent = '';
      var timer = setInterval(function () {
        if (i < text.length) {
          msg.textContent += text.charAt(i);
          i++;
          messagesEl.scrollTop = messagesEl.scrollHeight;
        } else {
          clearInterval(timer);
          // Rebuild with proper formatting after animation completes
          msg.textContent = '';
          buildMessageContent(msg, text);
          addCopyListener(msg);
        }
      }, 25);
    } else {
      buildMessageContent(msg, text);
      addCopyListener(msg);
    }

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
      hideThinking();
      if (data.reply) appendMessage(data.reply, false);
      // Empty reply means main loop is processing — response will arrive via async polling
      if (data.quitCountdownSeconds) {
        startQuitCountdown(data.quitCountdownSeconds);
      }
    } catch (e) {
      stopStatusPolling();
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
        appendMessage(data.reply, false, true);
      }
    } catch (e) { /* ignore */ }
  }, 2000);

  // ═══ Watch mode toggle button + live feed polling ═══

  const watchFeedEl = document.getElementById('watch-feed');
  const watchFeedInner = document.getElementById('watch-feed-inner');

  // Eye button click → toggle watch mode via API
  if (headerWatchEl) {
    headerWatchEl.addEventListener('click', async function () {
      try {
        var res = await fetch('/api/watch-mode/toggle', { method: 'POST' });
        var data = await res.json();
        headerWatchEl.classList.toggle('active', !!data.watching);
      } catch (e) { /* ignore */ }
    });
  }

  // Poll watch mode state to keep eye button in sync
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/watch-mode');
      var data = await res.json();
      if (headerWatchEl) {
        headerWatchEl.classList.toggle('active', !!data.watching);
      }
      if (!data.watching) {
        // Hide feed panel when watch mode stops
        if (watchFeedEl) {
          watchFeedEl.hidden = true;
          if (watchFeedInner) watchFeedInner.textContent = '';
        }
      }
    } catch (e) { /* ignore */ }
  }, 1000);

  // Poll watch feed observations every 800ms — only show the LATEST one
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/watch-feed');
      var data = await res.json();
      if (data.observations && data.observations.length > 0) {
        if (watchFeedEl) watchFeedEl.hidden = false;
        // Only show the most recent observation, replacing the previous
        var latest = data.observations[data.observations.length - 1];
        watchFeedInner.textContent = latest;
      }
    } catch (e) { /* ignore */ }
  }, 800);

  // ═══ Keyboard/mouse control toggle ═══

  if (headerControlEl) {
    headerControlEl.addEventListener('click', async function () {
      try {
        var res = await fetch('/api/control-mode/toggle', { method: 'POST' });
        var data = await res.json();
        headerControlEl.classList.toggle('active', !!data.controlEnabled);
      } catch (e) { /* ignore */ }
    });
  }

  // Poll control mode state to keep button in sync
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/control-mode');
      var data = await res.json();
      if (headerControlEl) {
        headerControlEl.classList.toggle('active', !!data.controlEnabled);
      }
    } catch (e) { /* ignore */ }
  }, 1000);

  // ═══ Audio listen mode toggle button + live feed polling ═══

  const listenFeedEl = document.getElementById('listen-feed');
  const listenFeedInner = document.getElementById('listen-feed-inner');
  var listenDurLabel = document.getElementById('listen-dur-label');
  var listenDurDown = document.getElementById('listen-dur-down');
  var listenDurUp = document.getElementById('listen-dur-up');
  var listenModelSelect = document.getElementById('listen-model');
  var listenDuration = 4;

  // Ear button click → toggle listen mode directly (sends current duration)
  if (headerListenEl) {
    headerListenEl.addEventListener('click', async function () {
      try {
        var res = await fetch('/api/listen-mode/toggle', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ duration: listenDuration })
        });
        var data = await res.json();
        headerListenEl.classList.toggle('active', !!data.listening);
      } catch (e) { /* ignore */ }
    });
  }

  // − button decreases duration (min 1)
  if (listenDurDown) {
    listenDurDown.addEventListener('click', function () {
      if (listenDuration > 1) {
        listenDuration--;
        if (listenDurLabel) listenDurLabel.textContent = listenDuration + 's';
      }
    });
  }

  // + button increases duration (max 8)
  if (listenDurUp) {
    listenDurUp.addEventListener('click', function () {
      if (listenDuration < 8) {
        listenDuration++;
        if (listenDurLabel) listenDurLabel.textContent = listenDuration + 's';
      }
    });
  }

  // Model dropdown — send to backend on change
  if (listenModelSelect) {
    listenModelSelect.addEventListener('change', function () {
      fetch('/api/listen-mode/model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: listenModelSelect.value })
      }).catch(function () {});
    });
  }

  // Poll listen mode state to keep ear button + popup in sync
  var listenEngineLabel = document.getElementById('listen-engine-label');
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/listen-mode');
      var data = await res.json();
      if (headerListenEl) {
        headerListenEl.classList.toggle('active', !!data.listening);
      }
      if (data.engine && listenModelSelect && listenModelSelect.value !== data.engine) {
        listenModelSelect.value = data.engine;
      }
      if (listenEngineLabel) {
        listenEngineLabel.textContent = data.listening && data.activeEngine ? data.activeEngine : '';
      }
      if (!data.listening) {
        if (listenFeedEl) {
          listenFeedEl.hidden = true;
          if (listenFeedInner) listenFeedInner.textContent = '';
        }
      }
    } catch (e) { /* ignore */ }
  }, 1000);

  // Poll listen feed transcriptions every 800ms — only show the LATEST one
  var feedTypewriteTimer = null;
  var lastFeedText = '';
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/listen-feed');
      var data = await res.json();
      if (data.transcriptions && data.transcriptions.length > 0) {
        if (listenFeedEl) listenFeedEl.hidden = false;
        var latest = data.transcriptions[data.transcriptions.length - 1];
        if (latest !== lastFeedText) {
          lastFeedText = latest;
          // Cancel any running typewriter and start new one
          if (feedTypewriteTimer) { clearInterval(feedTypewriteTimer); feedTypewriteTimer = null; }

          // Check if message has <small> tag (translation format: <small>original</small>\ntranslation)
          var hasSmallTag = latest.indexOf('<small>') !== -1;
          if (hasSmallTag) {
            // Render HTML directly (original language small + translation regular)
            listenFeedInner.innerHTML = latest.replace(/\n/g, '<br>');
          } else {
            // Plain text: typewriter animation
            listenFeedInner.textContent = '';
            var fi = 0;
            feedTypewriteTimer = setInterval(function () {
              if (fi < latest.length) {
                listenFeedInner.textContent += latest.charAt(fi);
                fi++;
              } else {
                clearInterval(feedTypewriteTimer);
                feedTypewriteTimer = null;
              }
            }, 25);
          }
        }
      }
    } catch (e) { /* ignore */ }
  }, 800);

  // ═══ Mouth button (vocal mode) toggle + status polling ═══

  if (headerMouthEl) {
    headerMouthEl.addEventListener('click', async function () {
      try {
        var res = await fetch('/api/mouth-mode/toggle', { method: 'POST' });
        var data = await res.json();
        headerMouthEl.classList.toggle('active', !!data.active);
      } catch (e) { /* ignore */ }
    });
  }

  // Poll mouth mode status every 1s (syncs with listen mode status)
  setInterval(async function () {
    try {
      var res = await fetch('/api/status/mouth-mode');
      var data = await res.json();
      if (headerMouthEl) {
        headerMouthEl.classList.toggle('active', !!data.active);
      }
    } catch (e) { /* ignore */ }
  }, 1000);

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

  // ── Live mouse coordinates in status bar ──
  const mouseCoordsEl = document.getElementById('mouse-coords');
  if (mouseCoordsEl) {
    setInterval(async () => {
      try {
        const res = await fetch('/api/mouse');
        if (res.ok) {
          const pos = await res.json();
          mouseCoordsEl.innerHTML = 'X: ' + pos.x + ' &nbsp; Y: ' + pos.y;
        }
      } catch (_) {}
    }, 100);
  }

  // ═══ Calibration tab ═══

  const calArena = document.getElementById('cal-arena');
  const startCalibrationBtn = document.getElementById('start-calibration-btn');
  const calibrationStatus = document.getElementById('calibration-status');
  const calResultsTable = document.getElementById('cal-results-table');
  const calResultsBody = document.getElementById('cal-results-body');
  const calResultsAvg = document.getElementById('cal-results-avg');
  const calComparisonDiv = document.getElementById('cal-comparison');
  const calComparisonImg = document.getElementById('cal-comparison-img');

  // ── Engine priority list ──
  var engineDefs = [
    { key: 'gpt', label: 'GPT-5.4', color: '#00ff7f' },
    { key: 'gemini', label: 'Gemini 2.5', color: '#ef4444' },
    { key: 'docai', label: 'Document AI', color: '#ffa500' },
    { key: 'textract', label: 'Textract', color: '#8a2be2' },
    { key: 'rek', label: 'Rekognition', color: '#ffd700' },
    { key: 'ocr', label: 'Windows OCR', color: '#00bfff' },
    { key: 'gemini3', label: 'Gemini 3.1', color: '#ff69b4' },
    { key: 'claude', label: 'Claude Opus 4.6', color: '#d97706' }
  ];
  var priorityList = document.getElementById('engine-priority-list');
  var savePriorityBtn = document.getElementById('save-engine-priority');

  // Engine enabled state (loaded from server)
  var engineEnabledState = {};

  function renderPriorityList(order) {
    priorityList.innerHTML = '';
    order.forEach(function (key, idx) {
      var def = engineDefs.find(function (d) { return d.key === key; }) || { key: key, label: key, color: '#aaa' };
      var isEnabled = engineEnabledState[key] !== false; // default true
      var li = document.createElement('li');
      li.className = 'engine-priority-item' + (isEnabled ? '' : ' engine-disabled');
      li.dataset.engine = key;
      li.draggable = true;
      li.innerHTML = '<span class="engine-priority-rank">' + (idx + 1) + '</span>'
        + '<label class="engine-toggle" title="Enable/disable"><input type="checkbox" class="engine-enabled-cb" data-engine="' + key + '"'
        + (isEnabled ? ' checked' : '') + '>'
        + '<span class="engine-priority-name" style="color:' + (isEnabled ? def.color : '#555') + '">' + def.label + '</span></label>'
        + '<span class="engine-priority-arrows">'
        + '<button class="ep-up" title="Move up">\u25B2</button>'
        + '<button class="ep-down" title="Move down">\u25BC</button>'
        + '</span>';
      // Toggle enabled
      li.querySelector('.engine-enabled-cb').addEventListener('change', function (e) {
        engineEnabledState[key] = e.target.checked;
        renderPriorityList(getCurrentOrder());
        // Auto-save
        fetch('/api/engine-enabled', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: engineEnabledState })
        });
      });
      // Arrow buttons
      li.querySelector('.ep-up').addEventListener('click', function () { moveEngine(idx, -1); });
      li.querySelector('.ep-down').addEventListener('click', function () { moveEngine(idx, 1); });
      // Drag events
      li.addEventListener('dragstart', function (e) { e.dataTransfer.setData('text/plain', idx); li.classList.add('dragging'); });
      li.addEventListener('dragend', function () { li.classList.remove('dragging'); });
      li.addEventListener('dragover', function (e) { e.preventDefault(); li.classList.add('drag-over'); });
      li.addEventListener('dragleave', function () { li.classList.remove('drag-over'); });
      li.addEventListener('drop', function (e) {
        e.preventDefault();
        li.classList.remove('drag-over');
        var fromIdx = parseInt(e.dataTransfer.getData('text/plain'));
        var toIdx = idx;
        if (fromIdx !== toIdx) {
          var current = getCurrentOrder();
          var moved = current.splice(fromIdx, 1)[0];
          current.splice(toIdx, 0, moved);
          renderPriorityList(current);
        }
      });
      priorityList.appendChild(li);
    });
  }

  function getCurrentOrder() {
    return Array.from(priorityList.querySelectorAll('.engine-priority-item')).map(function (li) { return li.dataset.engine; });
  }

  function moveEngine(idx, dir) {
    var order = getCurrentOrder();
    var newIdx = idx + dir;
    if (newIdx < 0 || newIdx >= order.length) return;
    var tmp = order[idx];
    order[idx] = order[newIdx];
    order[newIdx] = tmp;
    renderPriorityList(order);
  }

  // Load engine enabled state + priority from server
  Promise.all([
    fetch('/api/engine-enabled').then(function (r) { return r.json(); }).catch(function () { return {}; }),
    fetch('/api/engine-priority').then(function (r) { return r.json(); }).catch(function () { return {}; })
  ]).then(function (results) {
    if (results[0].enabled) engineEnabledState = results[0].enabled;
    var order = results[1].priority || engineDefs.map(function (d) { return d.key; });
    renderPriorityList(order);
  });

  savePriorityBtn.addEventListener('click', function () {
    var order = getCurrentOrder();
    fetch('/api/engine-priority', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ priority: order })
    }).then(function (r) { return r.json(); }).then(function (data) {
      savePriorityBtn.textContent = 'Saved!';
      setTimeout(function () { savePriorityBtn.textContent = 'Save Priority'; }, 1500);
    });
  });

  // ── Load saved calibration engine checkboxes ──
  fetch('/api/calibration-engines').then(function (r) { return r.json(); }).then(function (data) {
    if (data.engines) {
      document.querySelectorAll('.cal-engine-cb').forEach(function (cb) {
        cb.checked = data.engines.indexOf(cb.value) >= 0;
      });
    }
  }).catch(function () {});

  // Save calibration engine checkboxes when toggled
  document.querySelectorAll('.cal-engine-cb').forEach(function (cb) {
    cb.addEventListener('change', function () {
      var selected = Array.from(document.querySelectorAll('.cal-engine-cb:checked')).map(function (c) { return c.value; });
      fetch('/api/calibration-engines', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ engines: selected })
      });
    });
  });

  const calColors = ['Blue','Red','Green','Gold','Purple'];
  const calAnimals = ['Wolf','Eagle','Fox','Bear','Hawk'];
  const chessPieces = [
    { emoji: '\u2654', name: 'King' },
    { emoji: '\u2655', name: 'Queen' },
    { emoji: '\u2656', name: 'Rook' },
    { emoji: '\u2657', name: 'Bishop' },
    { emoji: '\u2658', name: 'Knight' },
    { emoji: '\u2659', name: 'Pawn' }
  ];

  function shuffleArray(arr) {
    for (var i = arr.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
    return arr;
  }

  function generate5Labels() {
    var all = [];
    for (var c = 0; c < calColors.length; c++) {
      for (var a = 0; a < calAnimals.length; a++) {
        all.push(calColors[c] + ' ' + calAnimals[a]);
      }
    }
    return shuffleArray(all).slice(0, 5);
  }

  function generate5ChessPairs() {
    var pairs = [];
    var pieces = chessPieces.slice();
    shuffleArray(pieces);
    for (var i = 0; i < 5; i++) {
      var target = pieces[i % pieces.length];
      var decoyIdx = (i + 1 + Math.floor(Math.random() * (pieces.length - 1))) % pieces.length;
      var decoy = pieces[decoyIdx];
      if (decoy.name === target.name) decoy = pieces[(decoyIdx + 1) % pieces.length];
      pairs.push({ target: target, decoy: decoy });
    }
    return pairs;
  }

  function randomPosition(arenaW, arenaH, btnW, btnH) {
    var x = Math.floor(Math.random() * Math.max(1, arenaW - btnW));
    var y = Math.floor(Math.random() * Math.max(1, arenaH - btnH));
    return { x: x, y: y };
  }

  function nonOverlappingPositions(arenaW, arenaH, btnW, btnH) {
    var pos1 = randomPosition(arenaW, arenaH, btnW, btnH);
    var pos2;
    for (var tries = 0; tries < 50; tries++) {
      pos2 = randomPosition(arenaW, arenaH, btnW, btnH);
      if (Math.abs(pos2.x - pos1.x) > btnW + 20 || Math.abs(pos2.y - pos1.y) > btnH + 20) break;
    }
    return [pos1, pos2];
  }

  function multiNonOverlapping(arenaW, arenaH, btnW, btnH, count) {
    var placed = [];
    for (var i = 0; i < count; i++) {
      var pos, ok;
      for (var tries = 0; tries < 100; tries++) {
        pos = randomPosition(arenaW, arenaH, btnW, btnH);
        ok = true;
        for (var j = 0; j < placed.length; j++) {
          if (Math.abs(pos.x - placed[j].x) < btnW + 10 && Math.abs(pos.y - placed[j].y) < btnH + 10) {
            ok = false; break;
          }
        }
        if (ok) break;
      }
      placed.push(pos);
    }
    return placed;
  }

  async function captureCalScreenshot() {
    try {
      var ssRes = await fetch('/api/calibrate/screenshot', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}'
      });
      var ssData = await ssRes.json();
      return ssData.success ? ssData.screenshotPath : null;
    } catch (_) { return null; }
  }

  startCalibrationBtn.addEventListener('click', async function () {
    startCalibrationBtn.disabled = true;
    calResultsBody.innerHTML = '';
    calResultsTable.hidden = true;
    calComparisonDiv.hidden = true;
    calArena.hidden = false;
    calArena.innerHTML = '';

    var results = [];
    var screenshotPaths = [];

    // ── Mixed: 3 text buttons + 3 chess pieces, all at once ──
    var labels = generate5Labels().slice(0, 3);
    var pieces = shuffleArray(chessPieces.slice()).slice(0, 3);
    calArena.innerHTML = '';
    var arenaRect = calArena.getBoundingClientRect();

    // Measure text button size
    var tmpBtn = document.createElement('button');
    tmpBtn.className = 'cal-arena-btn';
    tmpBtn.textContent = 'Green Eagle';
    tmpBtn.style.visibility = 'hidden';
    calArena.appendChild(tmpBtn);
    var textW = tmpBtn.getBoundingClientRect().width + 10;
    var textH = tmpBtn.getBoundingClientRect().height + 10;
    calArena.removeChild(tmpBtn);

    // Use the larger dimension for placement to avoid overlaps between mixed sizes
    var cellW = Math.max(textW, 85);
    var cellH = Math.max(textH, 93) + 18;
    var positions = multiNonOverlapping(arenaRect.width, arenaRect.height, cellW, cellH, 6);

    var allButtons = [];

    // Create 3 text buttons (numbered 1-3)
    for (var i = 0; i < labels.length; i++) {
      var wrap = document.createElement('div');
      wrap.className = 'cal-btn-wrap';
      wrap.style.left = positions[i].x + 'px';
      wrap.style.top = positions[i].y + 'px';
      var btn = document.createElement('button');
      btn.className = 'cal-arena-btn';
      btn.textContent = labels[i];
      var num = document.createElement('span');
      num.className = 'cal-btn-num';
      num.textContent = (i + 1);
      wrap.appendChild(btn);
      wrap.appendChild(num);
      calArena.appendChild(wrap);
      allButtons.push({ btn: btn, wrap: wrap, label: labels[i], searchLabel: null });
    }

    // Create 3 chess buttons (numbered 4-6)
    for (var i = 0; i < pieces.length; i++) {
      var wrap = document.createElement('div');
      wrap.className = 'cal-btn-wrap';
      wrap.style.left = positions[3 + i].x + 'px';
      wrap.style.top = positions[3 + i].y + 'px';
      var btn = document.createElement('button');
      btn.className = 'cal-arena-btn cal-chess-btn';
      btn.textContent = pieces[i].emoji;
      btn.title = pieces[i].name;
      var num = document.createElement('span');
      num.className = 'cal-btn-num';
      num.textContent = (4 + i);
      wrap.appendChild(btn);
      wrap.appendChild(num);
      calArena.appendChild(wrap);
      allButtons.push({ btn: btn, wrap: wrap, label: pieces[i].emoji + ' ' + pieces[i].name, searchLabel: pieces[i].name });
    }

    calibrationStatus.textContent = 'Click all 6 buttons (1-6). Taking screenshot...';
    await new Promise(function (r) { setTimeout(r, 300); });
    var screenshot = await captureCalScreenshot();

    calibrationStatus.textContent = 'Click all 6 buttons in order (0/6)';
    var clicked = 0;
    await new Promise(function (resolveAll) {
      allButtons.forEach(function (item) {
        item.btn.addEventListener('click', function (e) {
          var entry = { label: item.label, userX: e.screenX, userY: e.screenY, screenshotPath: screenshot };
          if (item.searchLabel) entry.searchLabel = item.searchLabel;
          results.push(entry);
          item.btn.disabled = true;
          item.wrap.style.opacity = '0.4';
          clicked++;
          calibrationStatus.textContent = 'Click all 6 buttons in order (' + clicked + '/6)';
          if (clicked >= allButtons.length) resolveAll();
        }, { once: true });
      });
    });

    // All done
    calibrationStatus.textContent = 'All 6 buttons clicked. Analyzing with all engines...';
    calArena.innerHTML = '';

    var items = results.map(function (r) {
      var item = { label: r.label, screenshotPath: r.screenshotPath };
      if (r.searchLabel) item.searchLabel = r.searchLabel;
      return item;
    });
    try {
      var selectedEngines = Array.from(document.querySelectorAll('.cal-engine-cb:checked')).map(function(cb) { return cb.value; });
      var res = await fetch('/api/calibrate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: items, engines: selectedEngines })
      });
      var data = await res.json();
      if (data.screenshotPath) screenshotPaths.push(data.screenshotPath);
      if (data.engines) {
        calibrationStatus.textContent = 'Engines: ' + data.engines.join(', ') + '. Processing...';
      }
      if (data.success && data.results) {
        for (var ri = 0; ri < data.results.length; ri++) {
          var r = data.results[ri];
          var entry = results[ri];
          if (r.geminiX != null) { entry.geminiX = r.geminiX; entry.geminiY = r.geminiY; }
          if (r.geminiMs != null) entry.geminiMs = r.geminiMs;
          if (r.docaiX != null) { entry.docaiX = r.docaiX; entry.docaiY = r.docaiY; }
          if (r.docaiMs != null) entry.docaiMs = r.docaiMs;
          if (r.textractX != null) { entry.textractX = r.textractX; entry.textractY = r.textractY; }
          if (r.textractMs != null) entry.textractMs = r.textractMs;
          if (r.rekX != null) { entry.rekX = r.rekX; entry.rekY = r.rekY; }
          if (r.rekMs != null) entry.rekMs = r.rekMs;
          if (r.ocrX != null) { entry.ocrX = r.ocrX; entry.ocrY = r.ocrY; }
          if (r.ocrMs != null) entry.ocrMs = r.ocrMs;
          if (r.gptX != null) { entry.gptX = r.gptX; entry.gptY = r.gptY; }
          if (r.gptMs != null) entry.gptMs = r.gptMs;
          if (r.gemini3X != null) { entry.gemini3X = r.gemini3X; entry.gemini3Y = r.gemini3Y; }
          if (r.gemini3Ms != null) entry.gemini3Ms = r.gemini3Ms;
          if (r.claudeX != null) { entry.claudeX = r.claudeX; entry.claudeY = r.claudeY; }
          if (r.claudeMs != null) entry.claudeMs = r.claudeMs;
        }
      }
    } catch (_) {}

    // Done — hide arena, show results table
    calArena.hidden = true;
    calResultsTable.hidden = false;
    calResultsBody.innerHTML = '';

    var engineKeys = ['gemini', 'docai', 'textract', 'rek', 'ocr', 'gpt', 'gemini3', 'claude'];

    function calcDist(entry, eng) {
      var xk = eng + 'X', yk = eng + 'Y';
      if (entry[xk] == null) return null;
      return Math.round(Math.sqrt(Math.pow(entry[xk] - entry.userX, 2) + Math.pow(entry[yk] - entry.userY, 2)));
    }

    function distCell(dist) {
      if (dist == null) return '<td>—</td>';
      var cls = dist <= 5 ? 'cal-dist-good' : 'cal-dist-bad';
      return '<td class="' + cls + '">' + dist + 'px</td>';
    }

    for (var r = 0; r < results.length; r++) {
      var entry = results[r];
      var html = '<td>' + (r + 1) + '</td>'
        + '<td>' + entry.label + '</td>'
        + '<td>' + entry.userX + ', ' + entry.userY + '</td>';
      for (var e = 0; e < engineKeys.length; e++) {
        var ek = engineKeys[e];
        var xk = ek + 'X', yk = ek + 'Y', mk = ek + 'Ms';
        html += '<td>' + (entry[xk] != null ? entry[xk] + ', ' + entry[yk] : '—') + '</td>';
        html += distCell(calcDist(entry, ek));
        html += '<td>' + (entry[mk] != null ? (entry[mk] / 1000).toFixed(1) + 's' : '—') + '</td>';
      }
      var tr = document.createElement('tr');
      tr.innerHTML = html;
      calResultsBody.appendChild(tr);
    }

    // Summary row
    var avgHtml = '<td colspan="3"><strong>AVERAGE</strong></td>';
    for (var e = 0; e < engineKeys.length; e++) {
      var ek = engineKeys[e], mk = ek + 'Ms';
      var total = 0, count = 0, totalMs = 0, countMs = 0;
      for (var r = 0; r < results.length; r++) {
        var d = calcDist(results[r], ek);
        if (d != null) { total += d; count++; }
        if (results[r][mk] != null) { totalMs += results[r][mk]; countMs++; }
      }
      var avg = count > 0 ? Math.round(total / count) : null;
      var avgMs = countMs > 0 ? (totalMs / countMs / 1000).toFixed(1) : null;
      avgHtml += '<td>' + (count > 0 ? count + '/' + results.length + ' found' : '—') + '</td>';
      avgHtml += '<td><strong>' + (avg != null ? avg + 'px' : '—') + '</strong></td>';
      avgHtml += '<td>' + (avgMs != null ? avgMs + 's avg' : '—') + '</td>';
    }
    calResultsAvg.innerHTML = avgHtml;

    // Generate comparison image + Excel
    var comparisons = results.map(function (r) {
      var c = { label: r.label, userX: r.userX, userY: r.userY };
      engineKeys.forEach(function (ek) {
        if (r[ek + 'X'] != null) { c[ek + 'X'] = r[ek + 'X']; c[ek + 'Y'] = r[ek + 'Y']; }
        if (r[ek + 'Ms'] != null) { c[ek + 'Ms'] = r[ek + 'Ms']; }
      });
      return c;
    });
    if (screenshotPaths.length > 0) {
      calibrationStatus.textContent = 'Generating comparison image & Excel...';
      try {
        var cmpRes = await fetch('/api/calibrate/compare', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            screenshotPath: screenshotPaths[screenshotPaths.length - 1],
            comparisons: comparisons,
            threshold: 5
          })
        });
        var cmpData = await cmpRes.json();
        if (cmpData.image) {
          calComparisonImg.src = cmpData.image;
          calComparisonDiv.hidden = false;
        }
        var excelMsg = cmpData.excelPath ? ' Excel saved to: ' + cmpData.excelPath : '';
        calibrationStatus.textContent = 'Done. Red=Gemini2.5, Orange=DocAI, Purple=Textract, Gold=Rek, Cyan=WinOCR, SpringGreen=GPT5.4, Pink=Gemini3.1, Amber=Claude, Green=You.' + excelMsg;
      } catch (_) {
        calibrationStatus.textContent = 'Calibration done (comparison image failed).';
      }
    } else {
      calibrationStatus.textContent = 'Calibration done but no screenshots were captured.';
    }

    startCalibrationBtn.disabled = false;
  });

  // On startup: show clear chat with greeting (same as Clear button)
  messagesEl.innerHTML = '';
  appendMessage('Chat cleared. How can I help?', false);
})();
