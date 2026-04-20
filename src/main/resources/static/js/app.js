(function () {
  (function initEmbeddedShellTabs() {
    var qs = new URLSearchParams(window.location.search);
    var forceFull = qs.get('full') === '1';
    var forceMinimal = qs.get('minimal') === '1';
    var hasJavaBridge = typeof window.java !== 'undefined' && window.java;
    var embedded = (hasJavaBridge && !forceFull) || forceMinimal;
    if (embedded) document.body.classList.add('embedded-minsbot');
  })();

  const root = document.getElementById('root');
  const messagesEl = document.getElementById('messages');
  const inputEl = document.getElementById('input');
  const sendBtn = document.getElementById('send-btn');
  const stopBtn = document.getElementById('stop-btn');
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
    var titleBarRefresh = document.getElementById('title-bar-refresh');
    var titleBarBrowser = document.getElementById('title-bar-browser');
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

    if (titleBarRefresh) {
      titleBarRefresh.addEventListener('click', function () {
        // Hard reload: bypass any in-memory page state by appending a cache-buster
        // to the URL. Works in both the JavaFX WebView and a real browser.
        var sep = window.location.href.indexOf('?') >= 0 ? '&' : '?';
        var buster = sep + '_r=' + Date.now();
        // Strip any existing _r param so we don't keep appending.
        var clean = window.location.href.replace(/([?&])_r=\d+/g, '$1').replace(/[?&]$/, '');
        window.location.href = clean + buster;
      });
    }
    if (titleBarBrowser) {
      titleBarBrowser.addEventListener('click', function () {
        if (typeof window.java !== 'undefined' && window.java.openInBrowser) {
          window.java.openInBrowser();
        } else {
          // Fallback when not running inside the JavaFX shell (dev in a real browser)
          window.open(window.location.origin + '/?full=1', '_blank');
        }
      });
    }
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
  function doClearChat() {
    messagesEl.innerHTML = '';
    appendMessage('Chat cleared. How can I help?', false);
    var wfInner = document.getElementById('watch-feed-inner');
    var wfEl = document.getElementById('watch-feed');
    if (wfInner) wfInner.innerHTML = '';
    if (wfEl) wfEl.hidden = true;
    fetch('/api/chat/clear', { method: 'POST' }).catch(function () {});
  }
  if (clearBtn) clearBtn.addEventListener('click', doClearChat);
  var titleBarClearBtn = document.getElementById('title-bar-clear');
  if (titleBarClearBtn) titleBarClearBtn.addEventListener('click', doClearChat);

  // Let native click behavior place the caret where the user clicks.
  // Forcing focus on mousedown can move caret to end in WebView.

  // ═══ Global keyboard shortcuts (Ctrl+K, Ctrl+/, Ctrl+L) ═══
  document.addEventListener('keydown', function (e) {
    // Ctrl+K — Command Palette
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      e.stopPropagation();
      toggleCommandPalette();
      return;
    }
    // Ctrl+/ — Shortcuts Overlay
    if ((e.ctrlKey || e.metaKey) && e.key === '/') {
      e.preventDefault();
      e.stopPropagation();
      toggleShortcutsOverlay();
      return;
    }
    // Ctrl+L — Clear Chat
    if ((e.ctrlKey || e.metaKey) && e.key === 'l') {
      e.preventDefault();
      e.stopPropagation();
      if (clearBtn) clearBtn.click();
      return;
    }
    // Escape — close palette or overlay
    if (e.key === 'Escape') {
      var cmdPalette = document.getElementById('command-palette');
      var scOverlay = document.getElementById('shortcuts-overlay');
      if (cmdPalette && !cmdPalette.hidden) { cmdPalette.hidden = true; e.preventDefault(); return; }
      if (scOverlay && !scOverlay.hidden) { scOverlay.hidden = true; e.preventDefault(); return; }
    }
  }, true);

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

    // Action-button marker: [action:connect-spotify]
    // Strip the marker and prepend a live button to the message.
    if (text.indexOf('[action:connect-spotify]') !== -1) {
      text = text.replace(/\[action:connect-spotify\]\s*/g, '');
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'inline-action-btn inline-action-spotify';
      btn.innerHTML = '<i class="fa-brands fa-spotify"></i> Connect Spotify';
      btn.addEventListener('click', function () {
        var authUrl = location.origin + '/api/integrations/spotify/authorize';
        if (typeof startOAuthPopup === 'function') {
          startOAuthPopup(authUrl, 'spotify', 'Spotify',
            '/api/integrations/spotify/status',
            function (d) { return d && d.connected; },
            (typeof refreshSpotifyStatus === 'function') ? refreshSpotifyStatus : function(){});
        } else if (window.java && typeof window.java.openUrl === 'function') {
          window.java.openUrl(authUrl);
        } else {
          window.location.href = authUrl;
        }
      });
      el.appendChild(btn);
      el.appendChild(document.createElement('br'));
      // Fall through so the rest of the message text renders below the button
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

    // Only auto-scroll if user was already near the bottom; preserves manual scroll-up
    var wasAtBottom = isAtBottom(messagesEl);

    if (typewriter && !isUser && text) {
      // Typewriter animation: reveal character by character
      var i = 0;
      msg.textContent = '';
      var timer = setInterval(function () {
        if (i < text.length) {
          msg.textContent += text.charAt(i);
          i++;
          if (wasAtBottom && isAtBottom(messagesEl, 40)) {
            messagesEl.scrollTop = messagesEl.scrollHeight;
          }
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

    if (wasAtBottom) messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  /** True if the element is scrolled to (or very close to) the bottom. */
  function isAtBottom(el, slop) {
    if (!el) return true;
    var threshold = slop != null ? slop : 80;
    return (el.scrollHeight - el.scrollTop - el.clientHeight) < threshold;
  }

  function showThinking() {
    if (headerThinkingEl) {
      headerThinkingEl.hidden = false;
      headerThinkingEl.removeAttribute('aria-hidden');
    }
    sendBtn.hidden = true;
    stopBtn.hidden = false;
    var el = document.createElement('div');
    el.className = 'thinking';
    el.id = 'thinking-indicator';
    for (var i = 0; i < 3; i++) {
      var dot = document.createElement('div');
      dot.className = 'thinking-dot';
      el.appendChild(dot);
    }
    var wasAtBottom = isAtBottom(messagesEl);
    messagesEl.appendChild(el);
    if (wasAtBottom) messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function hideThinking() {
    if (headerThinkingEl) {
      headerThinkingEl.hidden = true;
      headerThinkingEl.setAttribute('aria-hidden', 'true');
    }
    stopBtn.hidden = true;
    sendBtn.hidden = false;
    var el = document.getElementById('thinking-indicator');
    if (el) el.remove();
  }

  function appendStatus(text) {
    var el = document.createElement('div');
    el.className = 'message-status';
    el.textContent = text;
    var wasAtBottom = isAtBottom(messagesEl);
    messagesEl.appendChild(el);
    if (wasAtBottom) messagesEl.scrollTop = messagesEl.scrollHeight;
    if (window._minsSound) window._minsSound.notification();
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
  var visionStatusEl = document.getElementById('vision-status');
  var visionStatusTimer = null;

  function showVisionStatus(text) {
    if (!visionStatusEl) return;
    visionStatusEl.textContent = text;
    visionStatusEl.hidden = false;
    if (visionStatusTimer) clearTimeout(visionStatusTimer);
    visionStatusTimer = setTimeout(function () {
      visionStatusEl.hidden = true;
      visionStatusEl.textContent = '';
      visionStatusTimer = null;
    }, 8000);
  }

  function startStatusPolling() {
    if (statusPollTimer) return;
    statusPollTimer = setInterval(async function () {
      try {
        var res = await fetch('/api/chat/status');
        var data = await res.json();
        if (data.messages && data.messages.length > 0) {
          hideThinking();
          for (var i = 0; i < data.messages.length; i++) {
            var msg = data.messages[i];
            if (msg.startsWith('__vision__')) {
              showVisionStatus(msg.substring(10));
            } else {
              appendStatus(msg);
            }
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
    if (window._minsSound) window._minsSound.sent();
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
      if (data.reply && window._minsSound) window._minsSound.received();
      // After the first bot reply, check if TTS had a Fish Audio fallback (once)
      if (!window._ttsStartupChecked) {
        window._ttsStartupChecked = true;
        setTimeout(async function () {
          try {
            var r = await fetch('/api/tts/startup-notice');
            var d = await r.json();
            if (d.notice) appendMessage(d.notice, false);
          } catch (e) { /* ignore */ }
        }, 5000);
      }
      // Empty reply means main loop is processing — response will arrive via async polling
      if (data.quitCountdownSeconds) {
        startQuitCountdown(data.quitCountdownSeconds);
      }
    } catch (e) {
      stopStatusPolling();
      hideThinking();
      appendMessage('Could not reach server.', false);
      if (window._minsSound) window._minsSound.error();
    } finally {
      sendingMessage = false;
    }
  }

  sendBtn.addEventListener('click', function () {
    sendMessage(inputEl.value);
    focusInputSoon();
  });

  stopBtn.addEventListener('click', async function () {
    try {
      await fetch('/api/chat/stop', { method: 'POST' });
    } catch (e) { /* ignore */ }
    stopStatusPolling();
    hideThinking();
    sendingMessage = false;
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
        // Add watch-comment styling for Jarvis watch mode messages (eye emoji prefix)
        if (data.reply.startsWith('\ud83d\udc41')) {
          var allMsgs = messagesEl.querySelectorAll('.message.bot');
          if (allMsgs.length > 0) {
            allMsgs[allMsgs.length - 1].classList.add('watch-comment');
          }
        }
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

  // ═══ Card-grid home view (browser only) ═══
  // Builds a card per tab button that isn't marked `hidden`. Clicking a card
  // triggers the same handler as clicking the underlying tab button (below),
  // so all the per-tab loaders fire uniformly.
  var tabMenuEl = document.getElementById('tab-menu');
  var tabMenuGridEl = document.getElementById('tab-menu-grid');
  var tabBackBtnEl = document.getElementById('tab-back-btn');
  var isBrowserView = !document.body.classList.contains('embedded-minsbot');

  function renderTabMenu() {
    if (!tabMenuGridEl) return;
    var html = '';
    Array.prototype.forEach.call(tabs, function (btn) {
      if (btn.hasAttribute('hidden')) return;
      var id = btn.dataset.tab;
      var title = btn.textContent.trim();
      var desc = btn.getAttribute('data-desc') || '';
      html += '<button type="button" class="tab-menu-card" data-tab-target="' + id + '">'
           + '<div class="tab-menu-card-title">' + title + '</div>'
           + '<div class="tab-menu-card-desc">' + desc + '</div>'
           + '</button>';
    });
    tabMenuGridEl.innerHTML = html;
    tabMenuGridEl.querySelectorAll('.tab-menu-card').forEach(function (card) {
      card.addEventListener('click', function () {
        var target = card.getAttribute('data-tab-target');
        var btn = document.querySelector('.tab[data-tab="' + target + '"]');
        if (btn) btn.click();
      });
    });
  }

  // Tag body with `on-tab-<name>` so CSS can hide chat-specific UI (panel-header
  // sense buttons, sound, clear) when the user is on a non-chat sub-page.
  function setActiveSurface(name) {
    document.body.className = document.body.className.replace(/\bon-tab-\S+/g, '').trim();
    document.body.classList.add('on-tab-' + name);
  }

  function showTabMenu() {
    if (!isBrowserView || !tabMenuEl) return;
    tabs.forEach(function (t) { t.classList.remove('active'); });
    tabContents.forEach(function (c) { c.classList.remove('active'); c.classList.remove('tab-visible'); });
    tabMenuEl.hidden = false;
    if (tabBackBtnEl) tabBackBtnEl.hidden = true;
    setActiveSurface('menu');
  }

  function hideTabMenu() {
    if (tabMenuEl) tabMenuEl.hidden = true;
    if (isBrowserView && tabBackBtnEl) tabBackBtnEl.hidden = false;
  }

  if (isBrowserView) {
    renderTabMenu();
    // Landing state: show the card grid instead of the default Chat tab.
    showTabMenu();
  } else {
    // JavaFX shell: chat is the only surface.
    setActiveSurface('chat');
  }
  if (tabBackBtnEl) {
    tabBackBtnEl.addEventListener('click', showTabMenu);
  }

  tabs.forEach(function (tab) {
    tab.addEventListener('click', function () {
      tabs.forEach(function (t) { t.classList.remove('active'); });
      tabContents.forEach(function (c) { c.classList.remove('active'); c.classList.remove('tab-visible'); });
      hideTabMenu();
      setActiveSurface(tab.dataset.tab);
      tab.classList.add('active');
      var newContent = document.getElementById('tab-' + tab.dataset.tab);
      newContent.classList.add('active');
      // Trigger reflow then animate in
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          newContent.classList.add('tab-visible');
        });
      });
      if (tab.dataset.tab === 'browser') startBrowserPolling();
      else stopBrowserPolling();
      if (tab.dataset.tab === 'agents') startAgentsPolling();
      else stopAgentsPolling();
      if (tab.dataset.tab === 'skills') { loadSkillsList(); loadPublishedList(); }
      if (tab.dataset.tab === 'schedules') loadSchedules();
      if (tab.dataset.tab === 'todos') loadTodos();
      if (tab.dataset.tab === 'directives') loadDirectives();
      if (tab.dataset.tab === 'setup') loadSetupTab();
      if (tab.dataset.tab === 'voice') loadTtsSettings();
      if (tab.dataset.tab === 'personality') loadPersonality();
      if (tab.dataset.tab === 'knowledge') loadKnowledgeBase();
      if (tab.dataset.tab === 'integrations') refreshGoogleIntegrationsPanel();
      if (tab.dataset.tab === 'workflows') loadWorkflows();
      if (tab.dataset.tab === 'templates') loadTemplates();
      if (tab.dataset.tab === 'marketplace') loadMarketplace();
      if (tab.dataset.tab === 'dashboard') loadDashboard();
      if (tab.dataset.tab === 'multiagent') loadMultiAgent();
      if (tab.dataset.tab === 'automations') loadAutomations();
    });
  });

  // Integrations sidebar filter — click a category to show only that section
  (function initIntegrationsSidebar() {
    var sidebar = document.getElementById('integrations-sidebar');
    if (!sidebar) return;
    sidebar.querySelectorAll('.int-side-item').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var filter = btn.getAttribute('data-filter');
        sidebar.querySelectorAll('.int-side-item').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        var sections = document.querySelectorAll('#tab-integrations .integrations-section-block');
        sections.forEach(function (s) {
          if (filter === 'all') { s.hidden = false; return; }
          var titleEl = s.querySelector('.integrations-section-title');
          var title = titleEl ? titleEl.textContent.trim() : '';
          s.hidden = !title.includes(filter);
        });
      });
    });
  })();

  function refreshGoogleIntegrationsPanel() {
    var hintEl = document.getElementById('integrations-redirect-hint');
    fetch('/api/integrations/google/status').then(function (r) { return r.json(); }).then(function (data) {
      if (hintEl && data.redirectUriHint) {
        hintEl.textContent = 'Authorized redirect URI (Google Cloud Console → OAuth client): ' + data.redirectUriHint;
        hintEl.hidden = false;
      }
      var configured = !!data.configured;
      var map = data.integrations || {};
      document.querySelectorAll('#tab-integrations .integration-card[data-oauth-provider="google"][data-integration]').forEach(function (card) {
        var id = card.getAttribute('data-integration');
        var info = map[id] || {};
        var accounts = Array.isArray(info.accounts) ? info.accounts : [];
        var connected = !!info.connected && accounts.length > 0;
        var anyDegraded = accounts.some(function (a) { return a.healthy === false; });
        var allDegraded = connected && accounts.every(function (a) { return a.healthy === false; });

        var badge = card.querySelector('[data-role="badge"]');
        var hint = card.querySelector('[data-role="hint"]');
        var btnC = card.querySelector('.integration-oauth-connect');
        var btnD = card.querySelector('.integration-oauth-disconnect');

        if (badge) {
          if (allDegraded) {
            badge.textContent = 'Needs reconnect';
          } else if (connected) {
            badge.textContent = accounts.length > 1
              ? (accounts.length + ' accounts')
              : 'Connected';
          } else {
            badge.textContent = configured ? 'Not connected' : 'Setup required';
          }
          badge.classList.toggle('is-connected', connected && !allDegraded);
          badge.classList.toggle('is-degraded', anyDegraded);
        }

        if (hint) {
          if (!configured) {
            hint.textContent = 'Add Google OAuth in Setup: spring.security.oauth2.client.registration.google client ID & secret (same as TelliChat), or app.integrations.google.* as fallback.';
          } else if (!connected) {
            hint.textContent = 'Click Sign in to grant API scopes for this service only.';
          } else {
            // Render per-account rows with per-account disconnect
            var html = '<div class="int-account-list">';
            accounts.forEach(function (a) {
              var degraded = a.healthy === false;
              var label = a.name ? (escapeHtml(a.name) + ' · ' + escapeHtml(a.email)) : escapeHtml(a.email || '(unknown account)');
              html += '<div class="int-account-row' + (degraded ? ' is-degraded' : '') + '">';
              html += '<span class="int-account-email"><i class="fa-solid fa-user-circle" aria-hidden="true"></i> ' + label + '</span>';
              if (degraded) {
                html += '<span class="int-account-warn" title="' + escapeHtml(a.healthReason || 'Needs reconnect') + '">⚠ stale</span>';
              }
              html += '<button type="button" class="int-account-remove" data-integration="' + escapeHtml(id) + '" data-email="' + escapeHtml(a.email || '') + '" title="Disconnect this account">×</button>';
              html += '</div>';
            });
            html += '</div>';
            hint.innerHTML = html;
          }
        }

        // Sign-in button: ALWAYS enabled when configured — users can add more accounts anytime
        if (btnC) {
          btnC.disabled = !configured;
          btnC.hidden = false;
          btnC.textContent = connected ? 'Add another account' : 'Sign in with Google';
        }
        // Legacy "disconnect all" button — hide it; per-account × buttons handle disconnect now
        if (btnD) {
          btnD.hidden = true;
        }
      });
    }).catch(function () {
      if (hintEl) hintEl.hidden = true;
    });
  }

  // Delegated click handler for per-account × disconnect buttons
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.classList || !t.classList.contains('int-account-remove')) return;
    var integration = t.getAttribute('data-integration');
    var email = t.getAttribute('data-email');
    if (!integration || !email) return;
    if (!window.confirm('Disconnect ' + email + ' from ' + integration + '?')) return;
    fetch('/api/integrations/google/disconnect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ integration: integration, email: email })
    }).then(function () { refreshGoogleIntegrationsPanel(); })
      .catch(function () { refreshGoogleIntegrationsPanel(); });
  });

  (function wireGoogleIntegrationsTab() {
    var tabPanel = document.getElementById('tab-integrations');
    if (!tabPanel) return;
    tabPanel.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      var connect = t.closest('.integration-oauth-connect');
      if (connect && !connect.disabled) {
        var provider = connect.getAttribute('data-provider');
        if (provider === 'spotify') {
          startOAuthPopup(location.origin + '/api/integrations/spotify/authorize',
                          'spotify', 'Spotify',
                          '/api/integrations/spotify/status',
                          function (data) { return data && data.connected; },
                          refreshSpotifyStatus);
          return;
        }
        var id = connect.getAttribute('data-integration');
        if (id) {
          var authUrl = location.origin + '/api/integrations/google/authorize?integration=' + encodeURIComponent(id);
          // Prefer the system browser so the user has back/forward/close controls
          // (the JavaFX WebView has none of those).
          var openedExternally = false;
          try {
            if (window.java && typeof window.java.openUrl === 'function') {
              openedExternally = window.java.openUrl(authUrl);
            }
          } catch (e) { openedExternally = false; }
          if (openedExternally) {
            // Poll for connection so the UI updates once OAuth completes in the external browser.
            var banner = document.getElementById('integrations-oauth-banner');
            if (banner) {
              banner.hidden = false;
              banner.className = 'integrations-oauth-banner is-info';
              banner.textContent = 'Google sign-in opened in your browser — complete it there. This page will update automatically.';
            }
            var deadline = Date.now() + 5 * 60 * 1000;
            var poll = setInterval(function () {
              if (Date.now() > deadline) { clearInterval(poll); return; }
              fetch('/api/integrations/google/status')
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (data) {
                  if (!data) return;
                  var entry = data[id] || (data.integrations && data.integrations[id]);
                  if (entry && entry.connected) {
                    clearInterval(poll);
                    if (banner) {
                      banner.className = 'integrations-oauth-banner is-success';
                      banner.textContent = 'Connected: ' + id + '.';
                    }
                    if (typeof refreshGoogleIntegrationsPanel === 'function') {
                      refreshGoogleIntegrationsPanel();
                    }
                  }
                })
                .catch(function () {});
            }, 2000);
          } else {
            // Fallback: navigate in the WebView (older behavior)
            window.location.href = authUrl;
          }
        }
        return;
      }
      var disc = t.closest('.integration-oauth-disconnect');
      if (disc && !disc.hidden) {
        if (disc.getAttribute('data-provider') === 'spotify') {
          fetch('/api/integrations/spotify/disconnect', { method: 'POST' })
            .then(function () { refreshSpotifyStatus(); })
            .catch(function () { refreshSpotifyStatus(); });
          return;
        }
        var id2 = disc.getAttribute('data-integration');
        if (id2) {
          fetch('/api/integrations/google/disconnect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ integration: id2 })
          }).then(function () { refreshGoogleIntegrationsPanel(); }).catch(function () { refreshGoogleIntegrationsPanel(); });
        }
      }
    });
  })();

  /* ─── Spotify integration helpers ─── */
  function refreshSpotifyStatus() {
    fetch('/api/integrations/spotify/status')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        var card = document.querySelector('.integration-card[data-oauth-provider="spotify"]');
        if (!card || !data) return;
        var badge = card.querySelector('[data-role="spotify-badge"]');
        var hint = card.querySelector('[data-role="spotify-hint"]');
        var connect = card.querySelector('.integration-oauth-connect');
        var disc = card.querySelector('.integration-oauth-disconnect');
        if (!data.configured) {
          if (badge) { badge.textContent = 'Not configured'; badge.className = 'integration-badge integration-badge-status'; }
          if (hint) hint.textContent = 'Set app.spotify.client-id and app.spotify.client-secret in properties, then restart.';
          if (connect) connect.disabled = true;
          if (disc) disc.hidden = true;
        } else if (data.connected) {
          if (badge) { badge.textContent = 'Connected'; badge.className = 'integration-badge integration-badge-status is-connected'; }
          if (hint) hint.textContent = 'Spotify is connected — the bot can search and control playback.';
          if (connect) { connect.hidden = true; connect.disabled = false; }
          if (disc) disc.hidden = false;
        } else {
          if (badge) { badge.textContent = 'Not connected'; badge.className = 'integration-badge integration-badge-status'; }
          if (hint) hint.textContent = 'Click Sign in to grant Spotify API scopes.';
          if (connect) { connect.hidden = false; connect.disabled = false; }
          if (disc) disc.hidden = true;
        }
      })
      .catch(function () {});
  }

  /* Generic OAuth popup helper — opens auth URL in system browser, polls status until connected */
  function startOAuthPopup(authUrl, providerKey, label, statusUrl, isConnectedFn, refreshFn) {
    var openedExternally = false;
    try {
      if (window.java && typeof window.java.openUrl === 'function') {
        openedExternally = window.java.openUrl(authUrl);
      }
    } catch (e) { openedExternally = false; }
    if (!openedExternally) { window.location.href = authUrl; return; }
    var banner = document.getElementById('integrations-oauth-banner');
    if (banner) {
      banner.hidden = false;
      banner.className = 'integrations-oauth-banner is-info';
      banner.textContent = label + ' sign-in opened in your browser — complete it there. This page will update automatically.';
    }
    var deadline = Date.now() + 5 * 60 * 1000;
    var poll = setInterval(function () {
      if (Date.now() > deadline) { clearInterval(poll); return; }
      fetch(statusUrl)
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          if (!data) return;
          if (isConnectedFn(data)) {
            clearInterval(poll);
            if (banner) {
              banner.className = 'integrations-oauth-banner is-success';
              banner.textContent = label + ' connected.';
            }
            if (typeof refreshFn === 'function') refreshFn();
          }
        })
        .catch(function () {});
    }, 2000);
  }

  (function handleSpotifyOAuthReturn() {
    var qs = new URLSearchParams(window.location.search);
    var sp = qs.get('spotify_oauth');
    if (!sp) return;
    var reason = qs.get('reason') || '';
    var path = window.location.pathname || '/';
    var full = qs.get('full');
    var newSearch = full ? '?full=' + encodeURIComponent(full) : '';
    history.replaceState({}, '', path + newSearch);
    setTimeout(function () {
      var tab = document.querySelector('.tab[data-tab="integrations"]');
      if (tab) tab.click();
      var banner = document.getElementById('integrations-oauth-banner');
      if (banner) {
        banner.hidden = false;
        if (sp === 'ok') {
          banner.className = 'integrations-oauth-banner is-success';
          banner.textContent = 'Spotify connected.';
        } else {
          banner.className = 'integrations-oauth-banner is-error';
          var msgs = {
            not_configured: 'Set app.spotify.client-id and app.spotify.client-secret in properties.',
            bad_state: 'Spotify sign-in expired; try again.',
            missing_params: 'Spotify OAuth callback incomplete.',
            token_exchange: 'Could not complete Spotify sign-in.'
          };
          banner.textContent = msgs[reason] || ('Spotify sign-in error: ' + reason);
        }
      }
      refreshSpotifyStatus();
    }, 100);
  })();

  /* Refresh Spotify status when Integrations tab becomes active */
  document.addEventListener('DOMContentLoaded', function () {
    var intTab = document.querySelector('.tab[data-tab="integrations"]');
    if (intTab) intTab.addEventListener('click', refreshSpotifyStatus);
    setTimeout(refreshSpotifyStatus, 500);
  });

  (function handleGoogleOAuthReturn() {
    var qs = new URLSearchParams(window.location.search);
    var go = qs.get('google_oauth');
    if (!go) return;
    var reason = qs.get('reason') || '';
    var integration = qs.get('integration') || '';
    var path = window.location.pathname || '/';
    var full = qs.get('full');
    var newSearch = full ? '?full=' + encodeURIComponent(full) : '';
    history.replaceState({}, '', path + newSearch);
    setTimeout(function () {
      var tab = document.querySelector('.tab[data-tab="integrations"]');
      if (tab) tab.click();
      var banner = document.getElementById('integrations-oauth-banner');
      if (banner) {
        banner.hidden = false;
        if (go === 'ok') {
          banner.className = 'integrations-oauth-banner is-success';
          banner.textContent = integration
            ? ('Connected: ' + integration + '.')
            : 'Google sign-in completed.';
        } else if (go === 'info') {
          banner.className = 'integrations-oauth-banner is-info';
          banner.textContent = reason === 'already_connected'
            ? 'That integration is already connected.'
            : 'Done.';
        } else {
          banner.className = 'integrations-oauth-banner is-error';
          var msgs = {
            not_configured: 'Add OAuth client ID and secret in Setup.',
            invalid_integration: 'Invalid integration.',
            bad_state: 'Sign-in expired; try Sign in again.',
            missing_params: 'OAuth callback incomplete.',
            token_exchange: 'Could not complete sign-in.',
            access_denied: 'Access was denied.'
          };
          banner.textContent = msgs[reason] || ('Sign-in failed: ' + reason);
        }
      }
      refreshGoogleIntegrationsPanel();
    }, 0);
  })();

  function startBrowserPolling() {
    if (browserPollTimer) return;
    refreshBrowserView();
    browserPollTimer = setInterval(refreshBrowserView, 1000);
  }

  function stopBrowserPolling() {
    if (browserPollTimer) { clearInterval(browserPollTimer); browserPollTimer = null; }
  }

  var agentsPollTimer = null;

  function stopAgentsPolling() {
    if (agentsPollTimer) { clearInterval(agentsPollTimer); agentsPollTimer = null; }
  }

  function startAgentsPolling() {
    stopAgentsPolling();
    refreshAgentsList();
    agentsPollTimer = setInterval(refreshAgentsList, 1500);
  }

  var agentsCache = [];
  function refreshAgentsList() {
    var listEl = document.getElementById('agents-list');
    var capEl = document.getElementById('agents-capacity');
    fetch('/api/agents').then(function (r) { return r.json(); }).then(function (data) {
      if (capEl) {
        capEl.textContent = 'Parallel slots in use: ' + (data.running != null ? data.running : 0)
          + ' / ' + (data.maxConcurrent != null ? data.maxConcurrent : '?');
      }
      if (!listEl) return;
      var agents = data.agents || [];
      agentsCache = agents;
      if (agents.length === 0) {
        listEl.innerHTML = '<p class="tab-empty">No agents yet. Start one above.</p>';
        return;
      }

      // Index children by parent id so orchestrators can be rendered with their crew nested.
      var childrenByParent = {};
      var topLevel = [];
      agents.forEach(function (a) {
        var parent = a.parentJobId || '';
        if (parent) {
          (childrenByParent[parent] = childrenByParent[parent] || []).push(a);
        } else {
          topLevel.push(a);
        }
      });

      function renderCard(a, depth) {
        var st = a.status || 'UNKNOWN';
        var logLines = a.log || [];
        var logJoin = Array.isArray(logLines) ? logLines.join('\n') : '';
        var pct = Number(a.progressPercent);
        if (!Number.isFinite(pct)) pct = 0;
        pct = Math.max(0, Math.min(100, pct));
        var fillMod = '';
        if (st === 'COMPLETED') fillMod = ' agent-progress-fill-done';
        else if (st === 'FAILED') fillMod = ' agent-progress-fill-failed';
        else if (st === 'CANCELLED') fillMod = ' agent-progress-fill-cancelled';

        var avatar = a.avatar || '\uD83E\uDD16';
        var name = a.name || a.id;
        var model = a.model || 'unknown';
        var tokens = a.tokenCount || 0;
        var tokenStr = tokens > 1000 ? (tokens / 1000).toFixed(1) + 'k' : String(tokens);
        var isOrchestrator = depth === 0 && (childrenByParent[a.id] || []).length > 0;
        var cardClass = 'agent-card'
          + (depth > 0 ? ' agent-card-sub' : '')
          + (isOrchestrator ? ' agent-card-orchestrator' : '');

        var h = '<div class="' + cardClass + '" data-agent-depth="' + depth + '">';

        // Header with avatar, name, model, status
        h += '<div class="agent-card-header">';
        h += '<div class="agent-avatar">' + avatar + '</div>';
        h += '<div class="agent-card-info">';
        var roleBadge = isOrchestrator
          ? '<span class="agent-role-badge agent-role-orchestrator">Orchestrator</span>'
          : (depth > 0 ? '<span class="agent-role-badge agent-role-sub">Sub-agent</span>' : '');
        h += '<div class="agent-card-name">' + escapeHtml(name) + ' '
          + roleBadge
          + ' <span class="agent-status agent-status-' + escapeHtml(st) + '">' + escapeHtml(st) + '</span></div>';
        h += '<div class="agent-card-meta">';
        h += '<span class="agent-model-badge">' + escapeHtml(model) + '</span>';
        h += '<span class="agent-tokens">' + tokenStr + ' tokens</span>';
        h += '<span class="agent-card-id">' + escapeHtml(a.id) + '</span>';
        h += '</div></div></div>';

        // Mission
        h += '<div class="agent-mission">' + escapeHtml(a.mission || '') + '</div>';

        // Progress bar
        h += '<div class="agent-progress-track" role="progressbar" aria-valuenow="' + pct + '" aria-valuemin="0" aria-valuemax="100">';
        h += '<div class="agent-progress-fill' + fillMod + '" style="width:' + pct + '%"></div></div>';

        // Plan, progress, log, error, result
        if (a.plan) {
          h += '<div class="agent-plan"><div class="agent-plan-label">Plan</div>';
          h += '<pre class="agent-plan-body">' + escapeHtml(a.plan) + '</pre></div>';
        }
        h += '<div class="agent-progress">' + escapeHtml(a.progress || '') + '</div>';
        if (logJoin) h += '<div class="agent-log">' + escapeHtml(logJoin) + '</div>';
        if (a.error) h += '<div class="agent-progress" style="color:#fca5a5">' + escapeHtml(a.error) + '</div>';
        if (a.result) h += '<div class="agent-result">' + escapeHtml(a.result) + '</div>';

        // Actions
        h += '<div class="agent-actions">';
        if (st === 'QUEUED' || st === 'RUNNING') {
          h += '<button type="button" class="agent-btn agent-btn-danger" data-agent-cancel="' + escapeHtml(a.id) + '">Cancel</button>';
        }
        if (a.result) {
          h += '<button type="button" class="agent-btn agent-btn-download" data-agent-download="' + escapeHtml(a.id) + '">Download</button>';
        }
        h += '<button type="button" class="agent-btn" data-agent-remove="' + escapeHtml(a.id) + '">Dismiss</button>';
        h += '</div></div>';
        return h;
      }

      var html = '';
      topLevel.forEach(function (orch) {
        var kids = childrenByParent[orch.id] || [];
        if (kids.length > 0) {
          html += '<div class="agent-crew">';
          html += renderCard(orch, 0);
          html += '<div class="agent-crew-children">';
          kids.forEach(function (k) { html += renderCard(k, 1); });
          html += '</div></div>';
        } else {
          html += renderCard(orch, 0);
        }
      });
      // Orphan sub-agents (parent was removed) — render them flat at the bottom.
      Object.keys(childrenByParent).forEach(function (pid) {
        var parentExists = agents.some(function (a) { return a.id === pid; });
        if (!parentExists) {
          childrenByParent[pid].forEach(function (k) { html += renderCard(k, 0); });
        }
      });
      listEl.innerHTML = html;
      listEl.querySelectorAll('[data-agent-cancel]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var id = btn.getAttribute('data-agent-cancel');
          fetch('/api/agents/' + encodeURIComponent(id) + '/cancel', { method: 'POST' }).then(function () { refreshAgentsList(); });
        });
      });
      listEl.querySelectorAll('[data-agent-remove]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var id = btn.getAttribute('data-agent-remove');
          fetch('/api/agents/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () { refreshAgentsList(); });
        });
      });
      listEl.querySelectorAll('[data-agent-download]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var id = btn.getAttribute('data-agent-download');
          // Find this agent's data
          var agent = (agentsCache || []).find(function (a) { return a.id === id; });
          if (!agent || !agent.result) return;
          // Build a text file with mission + plan + result
          var text = '# Agent Report: ' + (agent.name || id) + '\n';
          text += 'Model: ' + (agent.model || 'default') + '\n';
          text += 'Date: ' + new Date().toISOString().split('T')[0] + '\n\n';
          text += '## Mission\n' + (agent.mission || '') + '\n\n';
          if (agent.plan) text += '## Plan\n' + agent.plan + '\n\n';
          text += '## Result\n' + agent.result + '\n';
          // Download
          var blob = new Blob([text], { type: 'text/markdown' });
          var url = URL.createObjectURL(blob);
          var a = document.createElement('a');
          a.href = url;
          var safeName = (agent.name || 'agent').replace(/[^a-zA-Z0-9]/g, '_').toLowerCase();
          a.download = safeName + '_' + id + '.md';
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
        });
      });
    }).catch(function () {
      if (listEl) listEl.innerHTML = '<p class="tab-empty">Could not load agents.</p>';
    });
  }

  var agentsStartBtn = document.getElementById('agents-start-btn');
  var agentsMissionInput = document.getElementById('agents-mission-input');
  var agentsStartStatus = document.getElementById('agents-start-status');
  var agentsModelSelect = document.getElementById('agents-model-select');
  if (agentsStartBtn && agentsMissionInput) {
    agentsStartBtn.addEventListener('click', function () {
      var mission = agentsMissionInput.value != null ? agentsMissionInput.value.trim() : '';
      if (agentsStartStatus) agentsStartStatus.textContent = '';
      if (!mission) {
        if (agentsStartStatus) agentsStartStatus.textContent = 'Enter a mission first.';
        return;
      }
      var payload = { mission: mission };
      if (agentsModelSelect && agentsModelSelect.value) payload.model = agentsModelSelect.value;
      fetch('/api/agents/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) {
        if (!r.ok) return r.text().then(function (t) { throw new Error(t || r.status); });
        return r.json();
      }).then(function () {
        agentsMissionInput.value = '';
        if (agentsStartStatus) agentsStartStatus.textContent = '';
        refreshAgentsList();
      }).catch(function (e) {
        if (agentsStartStatus) agentsStartStatus.textContent = String(e.message || e);
      });
    });
  }

  var agentsRandomBtn = document.getElementById('agents-random-btn');
  if (agentsRandomBtn) {
    agentsRandomBtn.addEventListener('click', function () {
      var payload = {};
      if (agentsModelSelect && agentsModelSelect.value) payload.model = agentsModelSelect.value;
      fetch('/api/agents/start-random', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) {
        if (!r.ok) return r.text().then(function (t) { throw new Error(t || r.status); });
        return r.json();
      }).then(function () {
        refreshAgentsList();
      }).catch(function (e) {
        if (agentsStartStatus) agentsStartStatus.textContent = String(e.message || e);
      });
    });
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
        // Don't overwrite the URL input while the user is typing in it
        if (browserUrl && document.activeElement !== browserUrl) {
          browserUrl.value = data.url || '';
        }
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
  var skillDetailEl = document.getElementById('skill-detail');
  var skillDetailName = document.getElementById('skill-detail-name');
  var skillDetailMeta = document.getElementById('skill-detail-meta');
  var skillDetailActions = document.getElementById('skill-detail-actions');
  var skillEditorTitle = document.getElementById('skills-editor-title');
  var skillRandomBtn = document.getElementById('skill-random-btn');
  var skillRandomOutput = document.getElementById('skill-random-output');
  var allPluginsCache = [];
  var allPublishedCache = [];

  function loadSkillsList() {
    if (!skillsListEl) return;
    fetch('/api/skills/plugins').then(function (r) { return r.json(); }).then(function (plugins) {
      allPluginsCache = plugins || [];
      if (!plugins || plugins.length === 0) {
        skillsListEl.innerHTML = '<div class="skills-empty">No plugins uploaded.</div>';
        return;
      }
      var html = '';
      plugins.forEach(function (p) {
        var badge = p.loaded
          ? '<span class="skill-badge loaded">LOADED</span>'
          : '<span class="skill-badge unloaded">NOT LOADED</span>';
        html += '<div class="skill-item" data-skill-name="' + escapeHtml(p.name) + '" data-skill-type="plugin" onclick="window._selectSkill(\'' + escapeHtml(p.name) + '\',\'plugin\')">'
          + '<div class="skill-info">'
          + '<div class="skill-name">' + escapeHtml(p.name) + '</div>'
          + '<div class="skill-meta">' + p.sizeFormatted + (p.loaded ? ' &middot; ' + p.classCount + ' classes' : '') + '</div>'
          + '</div>'
          + badge
          + '</div>';
      });
      skillsListEl.innerHTML = html;
    }).catch(function () {
      skillsListEl.innerHTML = '<div class="skills-empty">Failed to load plugins.</div>';
    });
  }

  function escapeHtml(s) {
    var d = document.createElement('div');
    d.textContent = s == null ? '' : s;
    return d.innerHTML;
  }

  function loadSetupTab() {
    var formEl = document.getElementById('setup-form');
    var pathEl = document.getElementById('setup-file-path');
    var statusEl = document.getElementById('setup-status');
    if (!formEl) return;
    if (statusEl) statusEl.textContent = '';
    fetch('/api/setup/secrets').then(function (r) {
      if (!r.ok) {
        return r.text().then(function (t) {
          throw new Error(t || (r.status === 403 ? 'Setup is only available on this machine.' : 'Failed to load setup'));
        });
      }
      return r.json();
    }).then(function (data) {
      if (pathEl) pathEl.textContent = data.secretsFile ? 'File: ' + data.secretsFile : '';
      var html = '';
      (data.groups || []).forEach(function (g) {
        html += '<div class="setup-group"><h3 class="setup-group-title">' + escapeHtml(g.title) + '</h3>';
        (g.fields || []).forEach(function (f) {
          var safeKey = String(f.key).replace(/[^a-zA-Z0-9]/g, '_');
          var id = 'setup-f-' + safeKey;
          var hint = f.configured
            ? '<span class="setup-flag setup-flag-on">saved — enter a new value to replace</span>'
            : '<span class="setup-flag setup-flag-off">not set</span>';
          var forget = f.configured
            ? '<button type="button" class="setup-forget" data-key="' + escapeHtml(f.key) + '">Forget</button>'
            : '';
          html += '<div class="setup-field">';
          html += '<div class="setup-field-head"><label class="setup-label-text" for="' + id + '">' + escapeHtml(f.label) + '</label>' + forget + '</div>';
          html += '<div class="setup-field-meta">' + hint + '</div>';
          var inputType = f.mask ? 'password' : 'text';
          html += '<input class="tab-input setup-input" id="' + id + '" data-prop-key="' + escapeHtml(f.key) + '" type="' + inputType + '" autocomplete="off" spellcheck="false" placeholder="">';
          html += '</div>';
        });
        html += '</div>';
      });
      formEl.innerHTML = html;
      formEl.querySelectorAll('.setup-forget').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var key = btn.getAttribute('data-key');
          if (!key || !window.confirm('Remove this value from the secrets file?')) return;
          fetch('/api/setup/secrets', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ set: {}, unset: [key] })
          }).then(function (r) {
            if (!r.ok) return r.text().then(function (t) { throw new Error(t || String(r.status)); });
            return r.json();
          }).then(function () {
            if (statusEl) statusEl.textContent = 'Removed. Restart the app if services still use the old value.';
            loadSetupTab();
          }).catch(function (e) {
            if (statusEl) statusEl.textContent = String(e.message || e);
          });
        });
      });
    }).catch(function (e) {
      formEl.innerHTML = '<p class="tab-empty">' + escapeHtml(String(e.message || e)) + '</p>';
    });
  }

  var setupSaveBtn = document.getElementById('setup-save-btn');
  if (setupSaveBtn) {
    setupSaveBtn.addEventListener('click', function () {
      var statusEl = document.getElementById('setup-status');
      var inputs = document.querySelectorAll('#setup-form .setup-input');
      var set = {};
      inputs.forEach(function (inp) {
        var k = inp.getAttribute('data-prop-key');
        var v = inp.value != null ? inp.value.trim() : '';
        if (k && v) set[k] = v;
      });
      if (Object.keys(set).length === 0) {
        if (statusEl) statusEl.textContent = 'Nothing to save (all fields are empty).';
        return;
      }
      fetch('/api/setup/secrets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ set: set, unset: [] })
      }).then(function (r) {
        if (!r.ok) return r.text().then(function (t) { throw new Error(t || String(r.status)); });
        return r.json();
      }).then(function (data) {
        if (statusEl) statusEl.textContent = (data && data.message) ? data.message : 'Saved.';
        loadSetupTab();
      }).catch(function (e) {
        if (statusEl) statusEl.textContent = String(e.message || e);
      });
    });
  }

  var skillDetailContentEl = document.getElementById('skill-detail-content');
  var selectedSkillFile = null;
  var selectedSkillType = null;

  window._selectSkill = function (name, type) {
    selectedSkillFile = name;
    selectedSkillType = type;
    // Highlight selected item
    document.querySelectorAll('#tab-skills .skill-item').forEach(function (el) {
      el.classList.toggle('selected', el.getAttribute('data-skill-name') === name && el.getAttribute('data-skill-type') === type);
    });
    if (type === 'plugin') {
      var p = allPluginsCache.find(function (x) { return x.name === name; });
      if (!p) return;
      if (skillEditorTitle) skillEditorTitle.textContent = 'Plugin Details';
      if (skillDetailName) skillDetailName.value = p.name;
      if (skillDetailMeta) skillDetailMeta.innerHTML = 'Size: ' + escapeHtml(p.sizeFormatted)
        + (p.loaded ? '<br>Classes: ' + p.classCount : '')
        + '<br>Status: ' + (p.loaded ? '<span style="color:#4ade80">Loaded</span>' : '<span style="color:rgba(255,255,255,0.35)">Not loaded</span>');
      if (skillDetailContentEl) skillDetailContentEl.hidden = true;
      if (skillDetailActions) {
        var html = '<button class="action-btn publish" onclick="window._renamePlugin()">Rename</button>';
        if (p.loaded) {
          html += '<button class="action-btn" onclick="window._skillAction(\'unload\',\'' + escapeHtml(p.name) + '\')">Unload</button>';
        } else {
          html += '<button class="action-btn" onclick="window._skillAction(\'load\',\'' + escapeHtml(p.name) + '\')">Load</button>';
        }
        html += '<button class="action-btn" onclick="window._skillAction(\'delete\',\'' + escapeHtml(p.name) + '\')" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
        skillDetailActions.innerHTML = html;
      }
      if (skillDetailEl) skillDetailEl.hidden = false;
    } else if (type === 'published') {
      var pub = allPublishedCache.find(function (x) { return x.name === name; });
      if (!pub) return;
      if (skillEditorTitle) skillEditorTitle.textContent = pub.type === 'idea' ? 'Skill Idea' : 'Published Skill';
      if (skillDetailName) skillDetailName.value = pub.description || pub.name;
      if (pub.type === 'idea' && pub.content) {
        if (skillDetailMeta) skillDetailMeta.innerHTML = '';
        if (skillDetailContentEl) {
          skillDetailContentEl.value = pub.content;
          skillDetailContentEl.hidden = false;
        }
      } else {
        if (skillDetailMeta) skillDetailMeta.innerHTML = 'Author: ' + escapeHtml(pub.author || 'Unknown')
          + (pub.date ? '<br>Published: ' + escapeHtml(pub.date) : '');
        if (skillDetailContentEl) skillDetailContentEl.hidden = true;
      }
      if (skillDetailActions) {
        skillDetailActions.innerHTML = '<button class="action-btn publish" onclick="window._savePublishedSkill()">Save</button>'
          + '<button class="action-btn" onclick="window._deletePublished(\'' + escapeHtml(pub.name) + '\')" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
      }
      if (skillDetailEl) skillDetailEl.hidden = false;
    }
  };

  window._renamePlugin = function () {
    if (!selectedSkillFile || selectedSkillType !== 'plugin') return;
    var newName = skillDetailName ? skillDetailName.value.trim() : '';
    if (!newName) return;
    fetch('/api/skills/' + encodeURIComponent(selectedSkillFile) + '/rename', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newName: newName })
    }).then(function () {
      loadSkillsList();
      if (skillDetailEl) skillDetailEl.hidden = true;
      if (skillEditorTitle) skillEditorTitle.textContent = 'Upload / Create';
      selectedSkillFile = null;
    });
  };

  window._savePublishedSkill = function () {
    if (!selectedSkillFile || selectedSkillType !== 'published') return;
    var newName = skillDetailName ? skillDetailName.value.trim() : '';
    var content = skillDetailContentEl && !skillDetailContentEl.hidden ? skillDetailContentEl.value : null;
    fetch('/api/skills/published/' + encodeURIComponent(selectedSkillFile), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newName: newName || null, content: content })
    }).then(function () {
      loadPublishedList();
      if (skillDetailEl) skillDetailEl.hidden = true;
      if (skillEditorTitle) skillEditorTitle.textContent = 'Upload / Create';
      selectedSkillFile = null;
    });
  };

  window._skillAction = function (action, name) {
    var url, method;
    if (action === 'load') { url = '/api/skills/' + encodeURIComponent(name) + '/load'; method = 'POST'; }
    else if (action === 'unload') { url = '/api/skills/' + encodeURIComponent(name) + '/unload'; method = 'POST'; }
    else if (action === 'delete') { url = '/api/skills/' + encodeURIComponent(name); method = 'DELETE'; }
    else return;
    fetch(url, { method: method }).then(function () {
      loadSkillsList();
      if (skillDetailEl) skillDetailEl.hidden = true;
      if (skillEditorTitle) skillEditorTitle.textContent = 'Upload / Create';
    });
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

  // ═══ Random skill generator ═══

  var RANDOM_SKILL_IDEAS = [
    { name: 'Clipboard History Manager', desc: '## Clipboard History Manager\n\nA persistent clipboard history tracker that captures every text, image, and file path copied to the system clipboard.\n\n### Features\n- **Searchable history** — full-text search across all clipboard entries with instant results\n- **Categories** — auto-tags entries as code, URLs, file paths, plain text, or images\n- **Pinned items** — star frequently-used snippets for quick access\n- **Timestamp tracking** — every entry logged with date/time for easy recall\n- **Size limit** — configurable max entries (default 500) with oldest auto-pruned\n\n### Endpoints\n- `GET /api/skills/clipboard-history` — list recent entries\n- `GET /api/skills/clipboard-history/search?q=` — search entries\n- `POST /api/skills/clipboard-history/pin/{id}` — pin/unpin an entry\n- `DELETE /api/skills/clipboard-history/{id}` — remove an entry\n\n### Storage\nEntries persisted as JSON in `~/mins_bot_data/clipboard_history.json`. Images stored as base64 thumbnails.' },
    { name: 'Smart Meeting Summarizer', desc: '## Smart Meeting Summarizer\n\nCaptures system audio during meetings, transcribes speech in real-time, and produces structured summaries with action items.\n\n### Features\n- **Auto-detect meetings** — recognizes Zoom, Teams, and Google Meet windows and starts recording\n- **Live transcription** — uses Whisper or Gemini to transcribe audio in 30-second chunks\n- **AI summary** — generates bullet-point summary with key decisions, action items, and owners\n- **Speaker detection** — labels different speakers as Speaker 1, Speaker 2, etc.\n- **Export formats** — save as Markdown, PDF, or copy to clipboard\n\n### Endpoints\n- `POST /api/skills/meeting/start` — begin recording\n- `POST /api/skills/meeting/stop` — stop and generate summary\n- `GET /api/skills/meeting/history` — list past meeting summaries\n- `GET /api/skills/meeting/{id}` — full transcript + summary\n\n### Storage\nTranscripts in `~/mins_bot_data/meetings/`. Each meeting gets a timestamped folder with audio clips and the final summary.' },
    { name: 'Desktop Widget Dashboard', desc: '## Desktop Widget Dashboard\n\nA floating overlay of mini-widgets providing at-a-glance information without switching windows.\n\n### Features\n- **Weather widget** — current conditions + 3-day forecast for configured location\n- **Calendar widget** — next 5 upcoming events from Google Calendar\n- **System monitor** — live CPU, RAM, disk usage bars with color-coded thresholds\n- **Quick notes** — sticky-note style text area that persists across restarts\n- **Custom feeds** — add any REST API endpoint as a data widget with JSON path extraction\n- **Drag & arrange** — reposition widgets freely, layout saved automatically\n\n### Endpoints\n- `GET /api/skills/widgets` — list active widgets and positions\n- `POST /api/skills/widgets` — add a new widget\n- `PUT /api/skills/widgets/{id}/position` — update widget position\n- `DELETE /api/skills/widgets/{id}` — remove a widget\n\n### Configuration\n`app.skills.widgets.refresh-interval=60` (seconds). Widget data cached to reduce API calls. Layout stored in `~/mins_bot_data/widget_layout.json`.' },
    { name: 'Auto Email Categorizer', desc: '## Auto Email Categorizer\n\nScans incoming emails via Gmail API and auto-applies labels based on AI-powered content classification.\n\n### Features\n- **Smart categories** — Work, Personal, Finance, Shopping, Newsletters, Urgent, Spam-suspect\n- **Custom rules** — define sender patterns or keyword rules for specific labels\n- **Priority scoring** — assigns 1-5 priority based on sender importance and content urgency\n- **Daily digest** — generates a morning summary of overnight emails grouped by category\n- **Learning** — adapts to manual re-categorizations over time\n\n### Endpoints\n- `POST /api/skills/email-cat/scan` — scan and categorize unread emails\n- `GET /api/skills/email-cat/rules` — list custom rules\n- `POST /api/skills/email-cat/rules` — add a new rule\n- `GET /api/skills/email-cat/digest` — get today\'s email digest\n\n### Requirements\nRequires Gmail API credentials configured in Setup tab. Categories stored in `~/mins_bot_data/email_categories.json`. Respects Gmail rate limits with exponential backoff.' },
    { name: 'Screen Time Tracker', desc: '## Screen Time Tracker\n\nPassively monitors application and website usage to generate detailed productivity reports.\n\n### Features\n- **App tracking** — logs active window title and process name every 5 seconds\n- **Website tracking** — extracts domain from browser title bars\n- **Categories** — auto-classifies apps as Productive, Neutral, or Distracting\n- **Daily/weekly reports** — pie charts and bar graphs of time spent per category\n- **Idle detection** — pauses tracking when no mouse/keyboard activity for 2+ minutes\n- **Goals** — set daily limits for distracting apps with optional notifications\n\n### Endpoints\n- `GET /api/skills/screentime/today` — today\'s usage breakdown\n- `GET /api/skills/screentime/week` — weekly summary\n- `GET /api/skills/screentime/categories` — app-to-category mappings\n- `PUT /api/skills/screentime/categories` — update category for an app\n\n### Storage\nUsage data in `~/mins_bot_data/screentime/`. One JSON file per day. Reports generated on-demand from raw data. No sensitive content captured — only window titles and durations.' },
    { name: 'Quick Note Capture Tool', desc: '## Quick Note Capture Tool\n\nInstant note capture via global hotkey — text, screenshots, or voice memos saved into organized notebooks.\n\n### Features\n- **Global hotkey** — Ctrl+Shift+N opens a floating capture window from any app\n- **Text notes** — quick text entry with auto-timestamp\n- **Screenshot notes** — captures current screen region and attaches to note\n- **Voice memos** — hold-to-record short audio clips, auto-transcribed\n- **Notebooks** — organize notes into named collections (Work, Ideas, TODO)\n- **Tags** — add #tags for cross-notebook search\n- **Search** — full-text search across all notes and transcriptions\n\n### Endpoints\n- `POST /api/skills/notes` — create a note (text, screenshot, or audio)\n- `GET /api/skills/notes?q=&notebook=&tag=` — search/filter notes\n- `GET /api/skills/notes/notebooks` — list notebooks\n- `DELETE /api/skills/notes/{id}` — delete a note\n\n### Storage\nNotes in `~/mins_bot_data/notes/`. Text as `.md`, audio as `.wav`, screenshots as `.png`. Index maintained in `notes_index.json`.' },
    { name: 'File Duplicate Finder', desc: '## File Duplicate Finder\n\nScans selected directories for duplicate files using content-based hashing, showing exact space wasted and offering safe cleanup.\n\n### Features\n- **Fast scanning** — two-pass approach: first groups by file size, then SHA-256 hashes only size-matched files\n- **Smart ignore** — skips system folders, hidden files, and configurable exclusion patterns\n- **Space report** — shows total duplicates found, space reclaimable, breakdown by file type\n- **Safe delete** — moves duplicates to Recycle Bin (never permanent delete), keeps the oldest copy\n- **Preview** — view duplicate groups side-by-side before taking action\n- **Export** — save scan results as CSV for manual review\n\n### Endpoints\n- `POST /api/skills/dupes/scan` — start scan with target directories\n- `GET /api/skills/dupes/results` — get scan results\n- `POST /api/skills/dupes/delete` — delete selected duplicates (to Recycle Bin)\n- `GET /api/skills/dupes/stats` — summary statistics\n\n### Configuration\nMax scan depth: 10 levels. Blocklist: System32, $Recycle.Bin, node_modules. Min file size: 1KB (skip tiny files).' },
    { name: 'Password Strength Auditor', desc: '## Password Strength Auditor\n\nAnalyzes password strength using entropy calculation, pattern detection, and breach database lookups.\n\n### Features\n- **Entropy scoring** — calculates bits of entropy and rates as Weak/Fair/Strong/Excellent\n- **Pattern detection** — flags dictionary words, keyboard patterns (qwerty), dates, and repeated characters\n- **Breach check** — queries Have I Been Pwned API using k-anonymity (only first 5 chars of hash sent)\n- **Suggestions** — generates strong alternatives using configurable rules (length, symbols, no ambiguous chars)\n- **Bulk audit** — paste multiple passwords for batch analysis\n- **Password generator** — create random passwords with customizable length and character sets\n\n### Endpoints\n- `POST /api/skills/password/check` — analyze a single password\n- `POST /api/skills/password/bulk` — analyze multiple passwords\n- `POST /api/skills/password/generate` — generate a strong password\n- `GET /api/skills/password/rules` — get current strength rules\n\n### Privacy\nPasswords are NEVER stored or logged. All analysis happens in-memory. Breach checks use k-anonymity — the full password never leaves the machine.' },
    { name: 'Automated Report Generator', desc: '## Automated Report Generator\n\nPulls data from multiple configured sources and produces formatted Excel spreadsheets and PDF reports on a schedule.\n\n### Features\n- **Data sources** — REST APIs, local CSV/JSON files, Excel workbooks, database queries (JDBC)\n- **Templates** — define report layouts with headers, data tables, charts, and summary sections\n- **Scheduling** — run reports daily, weekly, or monthly via cron expressions\n- **Formatting** — auto-styled Excel with headers, borders, number formats; PDF with logo and pagination\n- **Variables** — use {{today}}, {{week_start}}, {{month}} in queries and titles\n- **Delivery** — save to folder, email as attachment, or both\n\n### Endpoints\n- `GET /api/skills/reports/templates` — list report templates\n- `POST /api/skills/reports/templates` — create/update a template\n- `POST /api/skills/reports/run/{id}` — execute a report now\n- `GET /api/skills/reports/history` — list generated reports\n\n### Storage\nTemplates in `~/mins_bot_data/report_templates/`. Generated reports in `~/mins_bot_data/reports/` with timestamp-named files.' },
    { name: 'Smart Download Organizer', desc: '## Smart Download Organizer\n\nWatches the Downloads folder and automatically sorts new files into categorized subfolders based on file type and content.\n\n### Features\n- **Auto-sort rules** — `.pdf` to Documents, `.jpg/.png` to Images, `.mp3/.mp4` to Media, `.zip` to Archives, `.exe/.msi` to Installers\n- **Custom rules** — define patterns like `invoice*.pdf` goes to Finance folder\n- **Smart naming** — option to prepend date (2026-04-06_filename.pdf) for chronological sorting\n- **Conflict handling** — auto-rename with (1), (2) suffix if file exists in destination\n- **Undo** — keeps a move log so recent sorts can be reversed\n- **Folder watch** — uses Java WatchService for instant detection, no polling\n\n### Endpoints\n- `POST /api/skills/downloads/start` — start watching\n- `POST /api/skills/downloads/stop` — stop watching\n- `GET /api/skills/downloads/rules` — list sort rules\n- `POST /api/skills/downloads/rules` — add a custom rule\n- `GET /api/skills/downloads/log` — recent move history\n\n### Configuration\nWatch path: `~/Downloads` (configurable). Target base: `~/Downloads/Sorted/`. Rules in `~/mins_bot_data/download_rules.json`.' },
    { name: 'Voice Command Shortcuts', desc: '## Voice Command Shortcuts\n\nDefine custom voice trigger phrases that execute complex multi-step PC actions with a single spoken command.\n\n### Features\n- **Custom triggers** — map any phrase to a sequence of actions (e.g., "start my day" opens email, calendar, and Slack)\n- **Action types** — open app, open URL, run command, type text, send keys, click element, wait\n- **Chaining** — string multiple actions with configurable delays between steps\n- **Variables** — use {clipboard}, {date}, {time} in action parameters\n- **Confirmation** — optional "Did you say X?" confirmation before executing destructive commands\n- **Always listening** — low-power wake-word detection runs continuously in background\n\n### Endpoints\n- `GET /api/skills/voice-cmds` — list all voice commands\n- `POST /api/skills/voice-cmds` — create a new voice command\n- `PUT /api/skills/voice-cmds/{id}` — update a command\n- `DELETE /api/skills/voice-cmds/{id}` — delete a command\n- `POST /api/skills/voice-cmds/{id}/test` — execute without voice trigger\n\n### Storage\nCommands in `~/mins_bot_data/voice_commands.json`. Wake-word detection uses system mic with configurable sensitivity threshold.' },
    { name: 'Browser Tab Saver', desc: '## Browser Tab Saver\n\nSaves all open browser tabs as named sessions that can be restored with one click — never lose your research context again.\n\n### Features\n- **Save session** — captures all open tab URLs and titles from Chrome/Edge/Firefox\n- **Named sessions** — give sessions descriptive names like "Tax Research" or "Project Alpha"\n- **Restore** — reopen all tabs from a saved session in a new window\n- **Auto-save** — optionally saves current tabs every hour as a rolling backup\n- **Merge** — combine multiple sessions into one\n- **Export/Import** — share sessions as JSON files with teammates\n- **Tab count** — shows how many tabs in each saved session\n\n### Endpoints\n- `POST /api/skills/tabs/save` — save current browser tabs as a session\n- `GET /api/skills/tabs/sessions` — list all saved sessions\n- `POST /api/skills/tabs/restore/{id}` — restore a session\n- `DELETE /api/skills/tabs/sessions/{id}` — delete a session\n\n### Implementation\nUses Chrome CDP to enumerate open tabs. Sessions stored in `~/mins_bot_data/tab_sessions.json`. Auto-save creates sessions named `autosave_YYYY-MM-DD_HH` with max 24 rolling backups.' },
    { name: 'Daily Standup Compiler', desc: '## Daily Standup Compiler\n\nAuto-generates a daily standup report by gathering data from git commits, calendar events, and todo lists.\n\n### Features\n- **Git integration** — pulls yesterday\'s commits from configured repos with commit messages\n- **Calendar sync** — lists today\'s meetings and events from Google Calendar\n- **Todo items** — includes open tasks from the bot\'s todo list\n- **Blockers** — optionally prompts for manual blocker input\n- **Format** — outputs in standard standup format: Done / Doing / Blockers\n- **Delivery** — copies to clipboard, sends to Slack channel, or posts to email\n\n### Endpoints\n- `POST /api/skills/standup/generate` — generate today\'s standup\n- `GET /api/skills/standup/history` — list past standups\n- `GET /api/skills/standup/config` — get source configurations\n- `PUT /api/skills/standup/config` — update sources (repos, calendar, Slack webhook)\n\n### Configuration\nGit repos: list of local repo paths to scan. Calendar: uses Google Calendar integration. Slack: optional webhook URL for auto-posting. Generated standups saved in `~/mins_bot_data/standups/` as dated Markdown files.' },
    { name: 'Batch Image Resizer', desc: '## Batch Image Resizer\n\nBulk image resizing and format conversion with presets for web, social media, and print.\n\n### Features\n- **Drag-and-drop** — drop multiple images onto the upload area for batch processing\n- **Presets** — built-in sizes for Instagram (1080x1080), Twitter header (1500x500), YouTube thumbnail (1280x720), web (800px wide), print (300 DPI)\n- **Custom size** — specify exact width/height or percentage scale\n- **Format conversion** — convert between PNG, JPEG, WebP, BMP, and TIFF\n- **Quality control** — adjustable JPEG/WebP quality slider (1-100)\n- **Watermark** — optionally overlay text or image watermark on all outputs\n- **Preserve metadata** — option to keep or strip EXIF data\n\n### Endpoints\n- `POST /api/skills/resize` — resize uploaded images with specified preset or dimensions\n- `GET /api/skills/resize/presets` — list available presets\n- `POST /api/skills/resize/presets` — add a custom preset\n\n### Implementation\nUses Java `BufferedImage` and `ImageIO` for processing. Output saved to `~/Desktop/resized/` by default. Supports parallel processing for batches over 10 images.' },
    { name: 'Network Speed Monitor', desc: '## Network Speed Monitor\n\nContinuous internet speed tracking with historical data, alerts, and ISP performance reports.\n\n### Features\n- **Scheduled tests** — runs speed tests every 30 minutes (configurable) using speedtest-cli\n- **Metrics** — download speed, upload speed, ping latency, jitter\n- **Alerts** — notification when speed drops below configured threshold (e.g., < 50 Mbps)\n- **History** — stores all results for trend analysis over days/weeks/months\n- **ISP report** — generates a report card comparing actual speeds vs. advertised plan\n- **Chart data** — provides data points for time-series visualization\n\n### Endpoints\n- `POST /api/skills/speedtest/run` — run a speed test now\n- `GET /api/skills/speedtest/latest` — get most recent result\n- `GET /api/skills/speedtest/history?days=7` — historical results\n- `GET /api/skills/speedtest/stats` — average/min/max over time period\n- `PUT /api/skills/speedtest/config` — update interval and thresholds\n\n### Configuration\nTest interval: 30 minutes. Alert threshold: 50 Mbps download. Results in `~/mins_bot_data/speedtest_history.json`. Uses `speedtest-cli` or fallback HTTP download test.' },
    { name: 'Code Snippet Library', desc: '## Code Snippet Library\n\nA personal, searchable library of reusable code snippets organized by language, tags, and purpose.\n\n### Features\n- **Multi-language** — supports syntax highlighting for 30+ languages (Java, Python, JS, SQL, etc.)\n- **Tags** — categorize snippets with custom tags (e.g., #sorting, #api, #regex)\n- **Search** — full-text search across snippet titles, descriptions, code, and tags\n- **Favorites** — star frequently-used snippets for quick access\n- **Copy to clipboard** — one-click copy of any snippet\n- **Import/Export** — bulk import from GitHub Gists or export as JSON\n- **Version history** — tracks edits to each snippet with diff view\n\n### Endpoints\n- `GET /api/skills/snippets?q=&lang=&tag=` — search/filter snippets\n- `POST /api/skills/snippets` — create a new snippet\n- `PUT /api/skills/snippets/{id}` — update a snippet\n- `DELETE /api/skills/snippets/{id}` — delete a snippet\n- `GET /api/skills/snippets/tags` — list all tags with counts\n\n### Storage\nSnippets in `~/mins_bot_data/snippets.json`. Each snippet has: id, title, language, code, description, tags[], createdAt, updatedAt.' },
    { name: 'System Health Dashboard', desc: '## System Health Dashboard\n\nReal-time system monitoring with threshold-based alerts and historical performance tracking.\n\n### Features\n- **CPU monitoring** — current usage %, per-core breakdown, temperature (if available)\n- **Memory** — used/available RAM, swap usage, top memory-consuming processes\n- **Disk** — space used/free per drive, read/write speeds, SMART health status\n- **Network** — active connections, bandwidth usage, top talkers by process\n- **Alerts** — configurable thresholds (e.g., alert when CPU > 90% for 5+ minutes)\n- **History** — stores metrics every minute for 24-hour trend analysis\n- **Top processes** — ranked list of resource-hungry processes with kill option\n\n### Endpoints\n- `GET /api/skills/health/snapshot` — current system state\n- `GET /api/skills/health/history?hours=24` — historical metrics\n- `GET /api/skills/health/alerts` — active and recent alerts\n- `PUT /api/skills/health/thresholds` — configure alert thresholds\n- `POST /api/skills/health/kill/{pid}` — terminate a process\n\n### Implementation\nUses PowerShell WMI queries on Windows. Metrics cached every 60 seconds. History in `~/mins_bot_data/health_metrics.json` (rolling 7-day window).' },
    { name: 'Expense Receipt Scanner', desc: '## Expense Receipt Scanner\n\nPhotograph or screenshot receipts, extract amounts and line items using OCR, and export to a categorized expense spreadsheet.\n\n### Features\n- **OCR extraction** — uses Gemini Vision or Windows OCR to read receipt text\n- **Smart parsing** — identifies store name, date, total amount, tax, and individual line items\n- **Categories** — auto-classifies as Food, Transport, Office, Entertainment, etc.\n- **Currency detection** — recognizes USD, EUR, GBP, PHP, JPY and more\n- **Monthly report** — generates Excel spreadsheet grouped by category with totals\n- **Receipt archive** — stores original images alongside extracted data\n\n### Endpoints\n- `POST /api/skills/receipts/scan` — upload and scan a receipt image\n- `GET /api/skills/receipts?month=2026-04` — list receipts for a month\n- `GET /api/skills/receipts/report?month=2026-04` — generate monthly expense report\n- `DELETE /api/skills/receipts/{id}` — delete a receipt\n\n### Storage\nReceipt images in `~/mins_bot_data/receipts/images/`. Extracted data in `receipts.json`. Monthly reports exported as `.xlsx` to Desktop.' },
    { name: 'Pomodoro Focus Timer', desc: '## Pomodoro Focus Timer\n\nStructured work/break intervals with app blocking during focus sessions and detailed productivity statistics.\n\n### Features\n- **Classic Pomodoro** — 25 min work + 5 min break, with 15 min long break every 4 cycles\n- **Custom intervals** — adjustable work/break/long-break durations\n- **App blocking** — optionally blocks distracting apps (games, social media) during focus periods\n- **Notifications** — desktop notification + optional sound when a period ends\n- **Session log** — tracks completed pomodoros with timestamps and optional task labels\n- **Statistics** — daily/weekly pomodoro counts, average focus time, streak tracking\n- **Auto-start** — option to auto-begin next work period after break ends\n\n### Endpoints\n- `POST /api/skills/pomodoro/start` — start a focus session\n- `POST /api/skills/pomodoro/pause` — pause current session\n- `POST /api/skills/pomodoro/stop` — stop and log session\n- `GET /api/skills/pomodoro/status` — current timer state\n- `GET /api/skills/pomodoro/stats?days=7` — productivity statistics\n\n### Configuration\nDurations, blocked apps, and notification sounds configured in `~/mins_bot_data/pomodoro_config.json`. Session history in `pomodoro_log.json`.' },
    { name: 'Git Changelog Generator', desc: '## Git Changelog Generator\n\nAnalyzes git history and produces human-readable changelogs grouped by type (features, fixes, refactors).\n\n### Features\n- **Conventional commits** — parses `feat:`, `fix:`, `refactor:`, `docs:`, `chore:` prefixes\n- **Version grouping** — groups changes between tags/releases\n- **Auto-categorize** — classifies commits without conventional prefixes using keyword analysis\n- **Author attribution** — lists contributors per version with commit counts\n- **Breaking changes** — highlights commits marked with `BREAKING CHANGE` or `!`\n- **Output formats** — Markdown, HTML, or JSON\n- **Date ranges** — generate changelog for specific date range or between two commits\n\n### Endpoints\n- `POST /api/skills/changelog/generate` — generate changelog for a repo\n- `GET /api/skills/changelog/history` — list previously generated changelogs\n\n### Parameters\n- `repoPath` — local git repository path\n- `from` / `to` — commit hashes, tags, or dates\n- `format` — md, html, or json\n\n### Implementation\nUses JGit library for git log parsing. Changelog saved to repo root as `CHANGELOG.md` or specified output path. Supports monorepos with path-scoped filtering.' },
    { name: 'RSS Feed Aggregator', desc: '## RSS Feed Aggregator\n\nCollects articles from configured RSS/Atom feeds, generates AI-powered summaries, and highlights must-read content.\n\n### Features\n- **Feed management** — add/remove RSS and Atom feed URLs with custom labels\n- **Auto-refresh** — checks feeds every 30 minutes for new articles\n- **AI summaries** — generates 2-3 sentence summaries of each article using the configured AI model\n- **Priority scoring** — rates articles by relevance to your configured interests\n- **Read/unread tracking** — mark articles as read, filter by status\n- **Daily digest** — compiles top 10 articles into a morning briefing\n- **Search** — full-text search across article titles and summaries\n\n### Endpoints\n- `GET /api/skills/feeds` — list configured feeds\n- `POST /api/skills/feeds` — add a new feed\n- `DELETE /api/skills/feeds/{id}` — remove a feed\n- `GET /api/skills/feeds/articles?unread=true` — list articles\n- `GET /api/skills/feeds/digest` — today\'s digest\n\n### Storage\nFeed configs in `~/mins_bot_data/rss_feeds.json`. Articles cached in `rss_articles.json` with 30-day rolling window. Max 1000 articles cached.' },
    { name: 'Startup App Manager', desc: '## Startup App Manager\n\nAudit and manage all applications that launch at Windows startup with performance impact ratings.\n\n### Features\n- **Full inventory** — scans Registry Run keys, Startup folder, Scheduled Tasks, and Services\n- **Impact rating** — measures each app\'s boot time impact: Low, Medium, High, Critical\n- **One-click toggle** — enable/disable startup items without deleting them\n- **Details** — shows file path, publisher, description, and resource usage per item\n- **Recommendations** — AI-powered suggestions on what to disable based on usage patterns\n- **Before/after** — shows estimated boot time improvement from disabling selected items\n- **History** — tracks changes you\'ve made for easy rollback\n\n### Endpoints\n- `GET /api/skills/startup` — list all startup items with impact ratings\n- `POST /api/skills/startup/{id}/disable` — disable a startup item\n- `POST /api/skills/startup/{id}/enable` — re-enable a startup item\n- `GET /api/skills/startup/recommendations` — get AI suggestions\n\n### Implementation\nUses PowerShell to query `HKCU\\...\\Run`, `HKLM\\...\\Run`, shell:startup folder, and `Get-ScheduledTask`. Impact measured by process CPU/memory in first 60 seconds after boot.' },
    { name: 'Window Layout Saver', desc: '## Window Layout Saver\n\nSave and restore multi-monitor window arrangements for different work contexts like coding, design, or meetings.\n\n### Features\n- **Save layout** — captures position, size, and monitor assignment of all open windows\n- **Named profiles** — create profiles like "Coding", "Design Review", "Video Call"\n- **One-click restore** — restores all windows to saved positions, launching closed apps if needed\n- **Monitor-aware** — handles multi-monitor setups; adapts if a monitor is disconnected\n- **Auto-profiles** — optionally auto-save layout when switching between monitor configurations\n- **Selective restore** — choose which apps to restore from a profile\n\n### Endpoints\n- `POST /api/skills/layouts/save` — save current window layout\n- `GET /api/skills/layouts` — list saved profiles\n- `POST /api/skills/layouts/{id}/restore` — restore a layout\n- `DELETE /api/skills/layouts/{id}` — delete a profile\n- `GET /api/skills/layouts/current` — snapshot of current window positions\n\n### Implementation\nUses PowerShell `Get-Process | Where MainWindowHandle` and Win32 `SetWindowPos` via JNA. Profiles stored in `~/mins_bot_data/window_layouts.json`. Handles DPI-scaled coordinates.' },
    { name: 'API Health Checker', desc: '## API Health Checker\n\nMonitors REST API endpoints with scheduled health checks, response time tracking, and failure alerts.\n\n### Features\n- **Endpoint registry** — configure URLs, expected status codes, and timeout thresholds\n- **Scheduled pings** — checks endpoints at configurable intervals (1 min to 1 hour)\n- **Response validation** — verify status code, response body contains expected string, and latency < threshold\n- **Alert channels** — desktop notification, email, or Slack webhook on failure\n- **Uptime tracking** — calculates availability percentage over 24h, 7d, and 30d windows\n- **Response time history** — tracks p50, p95, p99 latency over time\n- **Dashboard data** — provides JSON for building status page visualizations\n\n### Endpoints\n- `GET /api/skills/health-check/endpoints` — list monitored endpoints\n- `POST /api/skills/health-check/endpoints` — add an endpoint\n- `DELETE /api/skills/health-check/endpoints/{id}` — remove monitoring\n- `GET /api/skills/health-check/status` — current status of all endpoints\n- `GET /api/skills/health-check/history/{id}` — response time history\n\n### Storage\nEndpoint configs in `~/mins_bot_data/api_monitors.json`. Check results in `api_health_log.json` with 30-day rolling window.' },
    { name: 'Local AI Image Generator', desc: '## Local AI Image Generator\n\nGenerate images from text prompts using locally-running Stable Diffusion — no cloud APIs or subscriptions needed.\n\n### Features\n- **Text-to-image** — describe what you want and get a generated image in seconds\n- **Style presets** — photorealistic, anime, oil painting, watercolor, pixel art, sketch\n- **Resolution options** — 512x512, 768x768, 1024x1024 with aspect ratio variants\n- **Negative prompts** — specify what to exclude (e.g., "no blur, no watermark")\n- **Batch generation** — generate 1-4 variations per prompt\n- **Seed control** — save and reuse seeds for reproducible results\n- **Gallery** — browse all generated images with prompt history\n\n### Endpoints\n- `POST /api/skills/imagegen/generate` — generate image from prompt\n- `GET /api/skills/imagegen/gallery` — list generated images\n- `GET /api/skills/imagegen/presets` — list style presets\n- `DELETE /api/skills/imagegen/{id}` — delete a generated image\n\n### Requirements\nRequires Stable Diffusion WebUI (automatic1111) running locally on port 7860. GPU with 6GB+ VRAM recommended. Generated images saved to `~/mins_bot_data/generated_images/` with prompt metadata in filename.' }
  ];

  function renderSkillMarkdown(md) {
    // Lightweight markdown renderer for skill descriptions
    return md
      .replace(/^### (.+)$/gm, '<div class="skill-md-h3">$1</div>')
      .replace(/^## (.+)$/gm, '<div class="skill-md-h2">$1</div>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code class="skill-md-code">$1</code>')
      .replace(/^- (.+)$/gm, '<div class="skill-md-li">&bull; $1</div>')
      .replace(/\n\n/g, '<div class="skill-md-gap"></div>')
      .replace(/\n/g, '');
  }

  if (skillRandomBtn) {
    skillRandomBtn.addEventListener('click', function () {
      var idea = RANDOM_SKILL_IDEAS[Math.floor(Math.random() * RANDOM_SKILL_IDEAS.length)];
      if (skillRandomOutput) {
        skillRandomOutput.innerHTML = '<div class="skill-random-md">' + renderSkillMarkdown(idea.desc) + '</div>'
          + '<div class="skill-random-actions">'
          + '<button class="action-btn publish" onclick="window._saveRandomSkill()">Save</button>'
          + '<button class="action-btn" onclick="window._useRandomSkill()">Use in Form</button>'
          + '<button class="action-btn" onclick="document.getElementById(\'skill-random-btn\').click()">Another One</button>'
          + '</div>';
        skillRandomOutput.hidden = false;
        skillRandomOutput._idea = idea;
      }
    });
  }

  window._saveRandomSkill = function () {
    if (!skillRandomOutput || !skillRandomOutput._idea) return;
    var idea = skillRandomOutput._idea;
    fetch('/api/skills/published/save-idea', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: idea.name, content: idea.desc })
    }).then(function () {
      loadPublishedList();
      skillRandomOutput.hidden = true;
    });
  };

  window._useRandomSkill = function () {
    if (!skillRandomOutput || !skillRandomOutput._idea) return;
    var idea = skillRandomOutput._idea;
    var nameInput = document.getElementById('publish-skill-name');
    var descInput = document.getElementById('publish-desc');
    if (nameInput) nameInput.value = idea.name;
    if (descInput) descInput.value = idea.desc;
    skillRandomOutput.hidden = true;
  };

  // ═══ Publish skills ═══

  var publishBtn = document.getElementById('publish-btn');
  var publishAuthor = document.getElementById('publish-author');
  var publishDesc = document.getElementById('publish-desc');
  var publishFile = document.getElementById('publish-file');
  var publishedListEl = document.getElementById('published-list');

  function loadPublishedList() {
    if (!publishedListEl) return;
    fetch('/api/skills/published').then(function (r) { return r.json(); }).then(function (items) {
      allPublishedCache = items || [];
      if (!items || items.length === 0) {
        publishedListEl.innerHTML = '';
        return;
      }
      var html = '<div class="section-title" style="margin-top:4px">Published</div>';
      items.forEach(function (p) {
        var badge = p.type === 'idea'
          ? '<span class="skill-badge" style="background:rgba(99,102,241,0.15);color:#a5b4fc">IDEA</span>'
          : '';
        var displayName = p.description || p.name;
        html += '<div class="skill-item" data-skill-name="' + escapeHtml(p.name) + '" data-skill-type="published" onclick="window._selectSkill(\'' + escapeHtml(p.name) + '\',\'published\')">'
          + '<div class="skill-info">'
          + '<div class="skill-name">' + escapeHtml(displayName) + '</div>'
          + '<div class="skill-meta">' + escapeHtml(p.author || '') + (p.date ? ' &middot; ' + p.date : '') + '</div>'
          + '</div>' + badge + '</div>';
      });
      publishedListEl.innerHTML = html;
    }).catch(function () { publishedListEl.innerHTML = ''; });
  }

  window._deletePublished = function (name) {
    fetch('/api/skills/published/' + encodeURIComponent(name), { method: 'DELETE' })
      .then(function () {
        loadPublishedList();
        if (skillDetailEl) skillDetailEl.hidden = true;
        if (skillEditorTitle) skillEditorTitle.textContent = 'Upload / Create';
      });
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

  var schedListEl = document.getElementById('sched-list');
  var schedEditorTitle = document.getElementById('sched-editor-title');
  var schedEditor = document.getElementById('sched-editor');
  var schedSectionSelect = document.getElementById('sched-section-select');
  var schedEntryInput = document.getElementById('sched-entry-input');
  var schedSaveBtn = document.getElementById('sched-save-btn');
  var schedDetail = document.getElementById('sched-detail');
  var schedDetailSection = document.getElementById('sched-detail-section');
  var schedDetailEntry = document.getElementById('sched-detail-entry');
  var schedUpdateBtn = document.getElementById('sched-update-btn');
  var schedDeleteBtn = document.getElementById('sched-delete-btn');
  var schedDetailCancelBtn = document.getElementById('sched-detail-cancel-btn');
  var schedRandomBtn = document.getElementById('sched-random-btn');
  var schedRandomOutput = document.getElementById('sched-random-output');
  var schedEditingSection = null;
  var schedEditingEntry = null;

  var RANDOM_SCHEDULES = [
    { section: 'Daily checks', entry: 'Check email inbox at 9:00 AM' },
    { section: 'Daily checks', entry: 'Review calendar for today at 8:30 AM' },
    { section: 'Daily checks', entry: 'Check system disk space at noon' },
    { section: 'Daily checks', entry: 'Backup important files at 6:00 PM' },
    { section: 'Daily checks', entry: 'Run morning briefing at 8:00 AM' },
    { section: 'Daily checks', entry: 'Check weather forecast at 7:00 AM' },
    { section: 'Daily checks', entry: 'Monitor server status at 10:00 AM' },
    { section: 'Daily checks', entry: 'Scan downloads folder for new files at noon' },
    { section: 'Weekly checks', entry: 'Review budget vs spending every Monday' },
    { section: 'Weekly checks', entry: 'Clean up Downloads folder every Friday' },
    { section: 'Weekly checks', entry: 'Generate weekly productivity report every Sunday' },
    { section: 'Weekly checks', entry: 'Check for software updates every Wednesday' },
    { section: 'Weekly checks', entry: 'Review and archive old emails every Saturday' },
    { section: 'Weekly checks', entry: 'Backup photos to external drive every Sunday' },
    { section: 'Reminders', entry: 'Drink water every 2 hours' },
    { section: 'Reminders', entry: 'Take a 5-minute break every hour' },
    { section: 'Reminders', entry: 'Stand up and stretch every 45 minutes' },
    { section: 'Reminders', entry: 'Review daily goals at 4:00 PM' },
    { section: 'Reminders', entry: 'Prepare tomorrow\'s to-do list at 5:30 PM' },
    { section: 'Other schedule', entry: 'Check OpenAI status page every 30 minutes' },
    { section: 'Other schedule', entry: 'Monitor portfolio prices every hour during market hours' },
    { section: 'Other schedule', entry: 'Sync notes to cloud every 4 hours' },
    { section: 'Other schedule', entry: 'Check for new GitHub notifications every 2 hours' },
    { section: 'Other schedule', entry: 'Run antivirus scan every Sunday at 2:00 AM' }
  ];

  function loadSchedules() {
    if (!schedListEl) return;
    fetch('/api/tabs/schedules').then(function (r) { return r.json(); }).then(function (sections) {
      if (!sections || sections.length === 0) {
        schedListEl.innerHTML = '<div class="tab-empty">No schedules yet. Add one or click Random.</div>';
        return;
      }
      var html = '';
      sections.forEach(function (s) {
        html += '<div class="schedule-card">'
          + '<div class="schedule-title">' + escapeHtml(s.section) + '</div>'
          + '<ul class="schedule-entries">';
        s.entries.forEach(function (e) {
          html += '<li onclick="window._schedSelect(\'' + escapeHtml(s.section).replace(/'/g, "\\'") + '\',\'' + escapeHtml(e).replace(/'/g, "\\'") + '\')">' + escapeHtml(e) + '</li>';
        });
        html += '</ul></div>';
      });
      schedListEl.innerHTML = html;
    }).catch(function () {
      schedListEl.innerHTML = '<div class="tab-empty">Failed to load schedules.</div>';
    });
  }

  window._schedSelect = function (section, entry) {
    document.querySelectorAll('.schedule-entries li').forEach(function (li) { li.classList.remove('selected'); });
    document.querySelectorAll('.schedule-entries li').forEach(function (li) {
      if (li.textContent === entry) li.classList.add('selected');
    });
    schedEditingSection = section;
    schedEditingEntry = entry;
    if (schedEditorTitle) schedEditorTitle.textContent = 'Edit Schedule';
    if (schedDetailSection) schedDetailSection.textContent = section;
    if (schedDetailEntry) schedDetailEntry.value = entry;
    if (schedDetail) schedDetail.hidden = false;
    if (schedEditor) schedEditor.style.display = 'none';
    if (schedRandomOutput) schedRandomOutput.hidden = true;
  };

  if (schedSaveBtn) {
    schedSaveBtn.addEventListener('click', function () {
      var section = schedSectionSelect ? schedSectionSelect.value : '';
      var entry = schedEntryInput ? schedEntryInput.value.trim() : '';
      if (!entry) return;
      fetch('/api/tabs/schedules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ section: section, entry: entry })
      }).then(function () {
        if (schedEntryInput) schedEntryInput.value = '';
        loadSchedules();
      });
    });
  }

  if (schedUpdateBtn) {
    schedUpdateBtn.addEventListener('click', function () {
      if (!schedEditingSection || !schedEditingEntry) return;
      var newEntry = schedDetailEntry ? schedDetailEntry.value.trim() : '';
      if (!newEntry) return;
      fetch('/api/tabs/schedules', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ section: schedEditingSection, oldEntry: schedEditingEntry, newEntry: newEntry })
      }).then(function () {
        schedEditingSection = null;
        schedEditingEntry = null;
        if (schedDetail) schedDetail.hidden = true;
        if (schedEditor) schedEditor.style.display = '';
        if (schedEditorTitle) schedEditorTitle.textContent = 'Add / Edit';
        loadSchedules();
      });
    });
  }

  if (schedDeleteBtn) {
    schedDeleteBtn.addEventListener('click', function () {
      if (!schedEditingSection || !schedEditingEntry) return;
      fetch('/api/tabs/schedules', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ section: schedEditingSection, entry: schedEditingEntry })
      }).then(function () {
        schedEditingSection = null;
        schedEditingEntry = null;
        if (schedDetail) schedDetail.hidden = true;
        if (schedEditor) schedEditor.style.display = '';
        if (schedEditorTitle) schedEditorTitle.textContent = 'Add / Edit';
        loadSchedules();
      });
    });
  }

  if (schedDetailCancelBtn) {
    schedDetailCancelBtn.addEventListener('click', function () {
      schedEditingSection = null;
      schedEditingEntry = null;
      if (schedDetail) schedDetail.hidden = true;
      if (schedEditor) schedEditor.style.display = '';
      if (schedEditorTitle) schedEditorTitle.textContent = 'Add / Edit';
      document.querySelectorAll('.schedule-entries li').forEach(function (li) { li.classList.remove('selected'); });
    });
  }

  if (schedRandomBtn) {
    schedRandomBtn.addEventListener('click', function () {
      var idea = RANDOM_SCHEDULES[Math.floor(Math.random() * RANDOM_SCHEDULES.length)];
      if (schedRandomOutput) {
        schedRandomOutput.innerHTML = '<div class="skill-random-name">' + escapeHtml(idea.entry) + '</div>'
          + '<div style="color:rgba(255,255,255,0.35);font-size:9px;margin:2px 0 6px">Section: ' + escapeHtml(idea.section) + '</div>'
          + '<div class="skill-random-actions">'
          + '<button class="action-btn publish" onclick="window._schedSaveRandom()">Save</button>'
          + '<button class="action-btn" onclick="window._schedUseRandom()">Use in Form</button>'
          + '<button class="action-btn" onclick="document.getElementById(\'sched-random-btn\').click()">Another One</button>'
          + '</div>';
        schedRandomOutput.hidden = false;
        schedRandomOutput._idea = idea;
      }
    });
  }

  window._schedSaveRandom = function () {
    if (!schedRandomOutput || !schedRandomOutput._idea) return;
    var idea = schedRandomOutput._idea;
    fetch('/api/tabs/schedules', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ section: idea.section, entry: idea.entry })
    }).then(function () {
      schedRandomOutput.hidden = true;
      loadSchedules();
    });
  };

  window._schedUseRandom = function () {
    if (!schedRandomOutput || !schedRandomOutput._idea) return;
    var idea = schedRandomOutput._idea;
    if (schedSectionSelect) {
      for (var i = 0; i < schedSectionSelect.options.length; i++) {
        if (schedSectionSelect.options[i].value === idea.section) { schedSectionSelect.selectedIndex = i; break; }
      }
    }
    if (schedEntryInput) schedEntryInput.value = idea.entry;
    schedRandomOutput.hidden = true;
    if (schedDetail) schedDetail.hidden = true;
    if (schedEditor) schedEditor.style.display = '';
  };

  // ═══ Generic confirm modal ═══

  var _confirmModal = document.getElementById('confirm-modal');
  var _confirmTitle = document.getElementById('confirm-modal-title');
  var _confirmMessage = document.getElementById('confirm-modal-message');
  var _confirmOk = document.getElementById('confirm-modal-ok');
  var _confirmCancel = document.getElementById('confirm-modal-cancel');
  var _confirmResolver = null;
  var _confirmKeyHandler = null;

  function _closeConfirm(result) {
    if (!_confirmModal) return;
    _confirmModal.hidden = true;
    if (_confirmKeyHandler) {
      document.removeEventListener('keydown', _confirmKeyHandler);
      _confirmKeyHandler = null;
    }
    var r = _confirmResolver;
    _confirmResolver = null;
    if (r) r(result);
  }

  function showConfirm(opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      if (!_confirmModal) { resolve(window.confirm(opts.message || 'Continue?')); return; }
      if (_confirmResolver) _closeConfirm(false);

      _confirmTitle.textContent = opts.title || 'Confirm';
      _confirmMessage.textContent = opts.message || '';
      _confirmOk.textContent = opts.okText || 'OK';
      _confirmCancel.textContent = opts.cancelText || 'Cancel';

      _confirmOk.classList.remove('confirm-modal-btn-primary', 'confirm-modal-btn-danger');
      _confirmOk.classList.add(opts.danger ? 'confirm-modal-btn-danger' : 'confirm-modal-btn-primary');

      _confirmResolver = resolve;
      _confirmModal.hidden = false;
      setTimeout(function () { try { _confirmOk.focus(); } catch (_) {} }, 0);

      _confirmKeyHandler = function (e) {
        if (e.key === 'Escape') { e.preventDefault(); _closeConfirm(false); }
        else if (e.key === 'Enter') { e.preventDefault(); _closeConfirm(true); }
      };
      document.addEventListener('keydown', _confirmKeyHandler);
    });
  }

  if (_confirmModal) {
    _confirmOk.addEventListener('click', function () { _closeConfirm(true); });
    _confirmCancel.addEventListener('click', function () { _closeConfirm(false); });
    _confirmModal.querySelectorAll('[data-confirm-close]').forEach(function (el) {
      el.addEventListener('click', function () { _closeConfirm(false); });
    });
  }

  // ═══ Todo tab ═══

  var todosContainer = document.getElementById('todos-container');
  var todosSearchInput = document.getElementById('todos-search');
  var todosClearAllBtn = document.getElementById('todos-clear-all-btn');
  var todosDownloadAllBtn = document.getElementById('todos-download-all-btn');

  var _allTodos = [];
  var _todosFilter = '';

  function renderTodos() {
    if (!todosContainer) return;
    var q = (_todosFilter || '').toLowerCase().trim();
    var tasks = _allTodos;
    if (q) {
      tasks = tasks.filter(function (t) {
        if ((t.title || '').toLowerCase().indexOf(q) >= 0) return true;
        if (t.steps && t.steps.some(function (s) {
          return (s.text || '').toLowerCase().indexOf(q) >= 0;
        })) return true;
        return false;
      });
    }
    if (!tasks || tasks.length === 0) {
      todosContainer.innerHTML = '<div class="tab-empty">'
        + (q ? 'No tasks match "' + escapeHtml(q) + '".' : 'No tasks. Ask the bot to create a plan.')
        + '</div>';
      return;
    }
    var html = '';
    tasks.forEach(function (t) {
      var idx = t._uiIndex;
      html += '<div class="todo-card" data-idx="' + idx + '">'
        + '<div class="todo-card-header">'
        + '<div class="todo-card-header-text">'
        + '<div class="todo-title">' + escapeHtml(t.title) + '</div>'
        + '<div class="todo-time">' + escapeHtml(t.timestamp) + '</div>'
        + '</div>'
        + '<div class="todo-card-actions">'
        + '<button class="todo-action-btn" data-action="skill" data-idx="' + idx + '" title="Save as skill">Skill</button>'
        + '<button class="todo-action-btn" data-action="download" data-idx="' + idx + '" title="Download task">&#8681;</button>'
        + '<button class="todo-action-btn todo-action-delete" data-action="delete" data-idx="' + idx + '" title="Delete task">\u2715</button>'
        + '</div>'
        + '</div>'
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

    todosContainer.querySelectorAll('.todo-action-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var action = btn.getAttribute('data-action');
        var idx = parseInt(btn.getAttribute('data-idx'), 10);
        if (action === 'delete') deleteTodo(idx);
        else if (action === 'download') downloadTodo(idx);
        else if (action === 'skill') saveTodoAsSkill(idx);
      });
    });
  }

  function loadTodos() {
    if (!todosContainer) return;
    fetch('/api/tabs/todos').then(function (r) { return r.json(); }).then(function (tasks) {
      _allTodos = (tasks || []).map(function (t, i) {
        t._uiIndex = i;
        return t;
      });
      renderTodos();
    }).catch(function () {
      todosContainer.innerHTML = '<div class="tab-empty">Failed to load tasks.</div>';
    });
  }

  function deleteTodo(idx) {
    var t = _allTodos.find(function (x) { return x._uiIndex === idx; });
    var label = t ? t.title : 'this task';
    showConfirm({
      title: 'Delete task',
      message: 'Delete task "' + label + '"? This cannot be undone.',
      okText: 'Delete',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      fetch('/api/tabs/todos/' + idx, { method: 'DELETE' })
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res && res.error) alert(res.error);
          loadTodos();
        });
    });
  }

  function clearAllTodos() {
    if (!_allTodos.length) return;
    showConfirm({
      title: 'Clear all tasks',
      message: 'Clear ALL ' + _allTodos.length + ' tasks? This cannot be undone.',
      okText: 'Clear all',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      fetch('/api/tabs/todos', { method: 'DELETE' })
        .then(function () { loadTodos(); });
    });
  }

  function downloadTodo(idx) {
    window.location.href = '/api/tabs/todos/' + idx + '/download';
  }

  function downloadAllTodos() {
    window.location.href = '/api/tabs/todos/download';
  }

  function saveTodoAsSkill(idx) {
    var t = _allTodos.find(function (x) { return x._uiIndex === idx; });
    if (!t) return;
    var defaultName = (t.title || 'skill').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
    var name = prompt('Skill file name (without .md):', defaultName);
    if (name === null) return;
    name = (name || '').trim();
    if (!name) { alert('Skill name is required.'); return; }
    var description = prompt('One-line skill description (optional):', '') || '';
    fetch('/api/tabs/todos/' + idx + '/as-skill', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name, description: description })
    }).then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.error) alert(res.error);
        else alert(res.result || 'Skill saved.');
      })
      .catch(function () { alert('Failed to save skill.'); });
  }

  if (todosSearchInput) {
    todosSearchInput.addEventListener('input', function () {
      _todosFilter = todosSearchInput.value || '';
      renderTodos();
    });
  }
  if (todosClearAllBtn) todosClearAllBtn.addEventListener('click', clearAllTodos);
  if (todosDownloadAllBtn) todosDownloadAllBtn.addEventListener('click', downloadAllTodos);

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

  // ── Generate sample directives ──
  var directiveGenerateBtn = document.getElementById('directive-generate-btn');
  var directiveSamplesEl = document.getElementById('directive-samples');
  var DIRECTIVE_SAMPLES = [
    'Always verify the screen state before clicking or typing.',
    'Keep responses concise — under 100 words unless asked for more detail.',
    'Ask before running destructive commands (delete, overwrite, reset).',
    'Save significant decisions and facts to episodic memory.',
    'Prefer local tools and files over external APIs when possible.',
    'Use skill files for recurring multi-step workflows.'
  ];

  function renderDirectiveSamples() {
    if (!directiveSamplesEl) return;
    var html = '<div class="directive-samples-title">Sample directives — click to add</div>';
    DIRECTIVE_SAMPLES.forEach(function (text, i) {
      html += '<button type="button" class="directive-sample" data-idx="' + i + '">'
        + '<span class="directive-sample-plus">+</span>'
        + '<span class="directive-sample-text">' + escapeHtml(text) + '</span>'
        + '</button>';
    });
    directiveSamplesEl.innerHTML = html;
    directiveSamplesEl.querySelectorAll('.directive-sample').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var idx = parseInt(btn.getAttribute('data-idx'), 10);
        var text = DIRECTIVE_SAMPLES[idx];
        if (!text) return;
        btn.disabled = true;
        btn.classList.add('added');
        fetch('/api/tabs/directives', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ directive: text })
        }).then(function () { loadDirectives(); });
      });
    });
  }

  if (directiveGenerateBtn && directiveSamplesEl) {
    directiveGenerateBtn.addEventListener('click', function () {
      if (directiveSamplesEl.hidden) {
        renderDirectiveSamples();
        directiveSamplesEl.hidden = false;
      } else {
        directiveSamplesEl.hidden = true;
      }
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
          var inOut = pos.insideBot ? ' <span style="color:#fca5a5">(IN)</span>' : ' <span style="color:#86efac">(OUT)</span>';
          mouseCoordsEl.innerHTML = 'X: ' + pos.x + ' &nbsp; Y: ' + pos.y + inOut;
        }
      } catch (_) {}
    }, 100);
  }

  // ── Module stats (vision/audio) in status bar ──
  var statusChatLabel = document.getElementById('status-chat-label');
  var statusVisionLabel = document.getElementById('status-vision-label');
  var statusAudioLabel = document.getElementById('status-audio-label');
  if (statusChatLabel || statusVisionLabel || statusAudioLabel) {
    setInterval(async function () {
      try {
        var res = await fetch('/api/status/modules');
        if (!res.ok) return;
        var d = await res.json();
        if (statusChatLabel) {
          var cModel = d.chatModel || '—';
          cModel = cModel.replace('gemini-', 'Gemini ').replace('gpt-', 'GPT-').replace('claude-', 'Claude ');
          statusChatLabel.textContent = cModel + ' : ' + (d.chatTotal || 0);
        }
        if (statusVisionLabel) {
          var vModel = d.visionModel || '—';
          vModel = vModel.replace('gemini-', 'Gemini ').replace('gpt-', 'GPT-');
          statusVisionLabel.textContent = vModel + ' : ' + (d.visionTotal || 0);
        }
        if (statusAudioLabel) {
          var aModel = d.audioModel || '—';
          aModel = aModel.replace('gemini-', 'Gemini ').replace('whisper-gpt', 'Whisper+GPT');
          statusAudioLabel.textContent = aModel + ' : ' + (d.audioTotal || 0);
        }
      } catch (_) {}
    }, 3000);
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

  // ═══ TTS Settings Tab ═══

  var ttsDefs = [
    { key: 'fishaudio', label: 'Fish Audio', color: '#06b6d4', icon: '🐟' },
    { key: 'elevenlabs', label: 'ElevenLabs', color: '#8b5cf6', icon: '🔊' },
    { key: 'openai', label: 'OpenAI TTS', color: '#10b981', icon: '🤖' }
  ];

  // ═══ Knowledge Base tab ═══

  function loadKnowledgeBase() {
    fetch('/api/kb/list').then(function (r) { return r.json(); }).then(function (docs) {
      renderKbDocList(docs);
    }).catch(function () {});
  }

  function renderKbDocList(docs) {
    var list = document.getElementById('kb-doc-list');
    var countEl = document.getElementById('kb-doc-count');
    if (countEl) countEl.textContent = docs.length > 0 ? '(' + docs.length + ')' : '';
    if (!docs || docs.length === 0) {
      list.innerHTML = '<div class="kb-empty">No documents uploaded yet.</div>';
      return;
    }
    list.innerHTML = '';
    for (var i = 0; i < docs.length; i++) {
      (function (doc) {
        var item = document.createElement('div');
        item.className = 'kb-doc-item';

        // Icon with file extension
        var ext = doc.name.includes('.') ? doc.name.substring(doc.name.lastIndexOf('.') + 1) : '?';
        var icon = document.createElement('div');
        icon.className = 'kb-doc-icon';
        icon.textContent = ext.substring(0, 3);
        item.appendChild(icon);

        // Info
        var info = document.createElement('div');
        info.className = 'kb-doc-info';
        var nameEl = document.createElement('div');
        nameEl.className = 'kb-doc-name';
        nameEl.textContent = doc.name;
        nameEl.title = doc.name;
        info.appendChild(nameEl);
        var meta = document.createElement('div');
        meta.className = 'kb-doc-meta';
        meta.textContent = doc.sizeLabel + ' \u2022 ' + doc.modified;
        info.appendChild(meta);
        item.appendChild(info);

        // Delete
        var del = document.createElement('button');
        del.className = 'kb-doc-delete';
        del.textContent = '\u00d7';
        del.title = 'Delete';
        del.addEventListener('click', function (e) {
          e.stopPropagation();
          fetch('/api/kb/' + encodeURIComponent(doc.name), { method: 'DELETE' })
            .then(function () { loadKnowledgeBase(); })
            .catch(function () {});
        });
        item.appendChild(del);

        list.appendChild(item);
      })(docs[i]);
    }
  }

  // Upload: click and drag-and-drop
  var kbDropZone = document.getElementById('kb-drop-zone');
  var kbFileInput = document.getElementById('kb-file-input');

  if (kbDropZone && kbFileInput) {
    kbDropZone.addEventListener('click', function () { kbFileInput.click(); });

    kbFileInput.addEventListener('change', function () {
      if (kbFileInput.files.length > 0) uploadKbFiles(kbFileInput.files);
      kbFileInput.value = '';
    });

    kbDropZone.addEventListener('dragover', function (e) {
      e.preventDefault();
      kbDropZone.classList.add('dragover');
    });
    kbDropZone.addEventListener('dragleave', function () {
      kbDropZone.classList.remove('dragover');
    });
    kbDropZone.addEventListener('drop', function (e) {
      e.preventDefault();
      kbDropZone.classList.remove('dragover');
      if (e.dataTransfer.files.length > 0) uploadKbFiles(e.dataTransfer.files);
    });
  }

  function uploadKbFiles(files) {
    var statusEl = document.getElementById('kb-upload-status');
    if (statusEl) statusEl.textContent = 'Uploading ' + files.length + ' file(s)...';
    var formData = new FormData();
    for (var i = 0; i < files.length; i++) {
      formData.append('files', files[i]);
    }
    fetch('/api/kb/upload', { method: 'POST', body: formData })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var msg = '';
        if (data.saved && data.saved.length > 0) msg += 'Uploaded: ' + data.saved.join(', ');
        if (data.rejected && data.rejected.length > 0) msg += (msg ? ' | ' : '') + 'Rejected: ' + data.rejected.join(', ');
        if (statusEl) {
          statusEl.textContent = msg || 'Done.';
          setTimeout(function () { statusEl.textContent = ''; }, 5000);
        }
        loadKnowledgeBase();
      }).catch(function () {
        if (statusEl) statusEl.textContent = 'Upload failed.';
      });
  }

  // ═══ Personality tab ═══

  var personalityFields = [
    'name', 'role', 'domain', 'backstory', 'catchphrase', 'signatureBehavior',
    'formality', 'verbosity', 'demeanor', 'humor', 'emojis',
    'decisionApproach', 'riskStyle', 'reasoning', 'exploration',
    'proactivity', 'followUpQuestions', 'unpromptedSuggestions', 'challengesUser',
    'expertiseLevel', 'explanationStyle', 'admitsUncertainty', 'scopeBoundaries',
    'sentenceLength', 'jargonLevel', 'structure', 'repetitionTolerance',
    'emotionalTone', 'crisisHandling', 'excitement',
    'avoidsSpeculation', 'avoidsSensitiveTopics', 'truthVsPoliteness',
    'assertiveness', 'empathy', 'curiosity', 'patience', 'creativity'
  ];

  var persActiveId = '';
  var persCurrentId = ''; // id of the personality currently loaded in the form

  function loadPersonality() {
    // Load active personality into form
    fetch('/api/personality').then(function (r) { return r.json(); }).then(function (data) {
      populatePersonalityForm(data);
      persCurrentId = data.id || '';
    }).catch(function () {});
    // Load sidebar list
    loadPersonalitySidebar();
  }

  function loadPersonalitySidebar() {
    fetch('/api/personality/list').then(function (r) { return r.json(); }).then(function (data) {
      persActiveId = data.activeId || '';
      renderPersonalitySidebar(data.personalities || []);
    }).catch(function () {});
  }

  function renderPersonalitySidebar(list) {
    var container = document.getElementById('pers-sidebar-list');
    container.innerHTML = '';
    if (list.length === 0) {
      container.innerHTML = '<div style="padding:12px;font-size:10px;color:rgba(255,255,255,0.3);text-align:center;">No personalities yet.<br>Click Randomize to generate one.</div>';
      return;
    }
    for (var i = 0; i < list.length; i++) {
      (function (p) {
        var id = p.id || '';
        var isActive = id === persActiveId;

        var item = document.createElement('div');
        item.className = 'pers-sidebar-item' + (isActive ? ' active' : '');
        item.dataset.id = id;

        // Check circle
        var check = document.createElement('div');
        check.className = 'pers-sidebar-check';
        var checkIcon = document.createElement('svg');
        checkIcon.className = 'pers-sidebar-check-icon';
        checkIcon.setAttribute('viewBox', '0 0 24 24');
        checkIcon.innerHTML = '<path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>';
        check.appendChild(checkIcon);
        item.appendChild(check);

        // Info
        var info = document.createElement('div');
        info.className = 'pers-sidebar-info';
        var nameEl = document.createElement('div');
        nameEl.className = 'pers-sidebar-name';
        nameEl.textContent = p.name || 'Unnamed';
        info.appendChild(nameEl);
        var roleEl = document.createElement('div');
        roleEl.className = 'pers-sidebar-role';
        roleEl.textContent = p.role || '';
        info.appendChild(roleEl);
        item.appendChild(info);

        // Delete button
        var del = document.createElement('button');
        del.className = 'pers-sidebar-delete';
        del.textContent = '\u00d7';
        del.title = 'Delete';
        del.addEventListener('click', function (e) {
          e.stopPropagation();
          fetch('/api/personality/' + id, { method: 'DELETE' })
            .then(function () { loadPersonalitySidebar(); })
            .catch(function () {});
        });
        item.appendChild(del);

        // Click to load + activate
        item.addEventListener('click', function () {
          populatePersonalityForm(p);
          persCurrentId = id;
          // Activate this personality
          fetch('/api/personality/activate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: id })
          }).then(function () {
            persActiveId = id;
            renderPersonalitySidebar(list);
            showPersonalityStatus('Loaded & activated: ' + (p.name || 'Unnamed'));
          }).catch(function () {});
        });

        container.appendChild(item);
      })(list[i]);
    }
  }

  function populatePersonalityForm(data) {
    for (var i = 0; i < personalityFields.length; i++) {
      var key = personalityFields[i];
      var el = document.getElementById('pers-' + key);
      if (el && data[key] !== undefined && data[key] !== null) {
        el.value = data[key];
      }
    }
    persCurrentId = data.id || '';
  }

  function collectPersonalityForm() {
    var data = {};
    for (var i = 0; i < personalityFields.length; i++) {
      var key = personalityFields[i];
      var el = document.getElementById('pers-' + key);
      if (!el) continue;
      if (el.type === 'range') {
        data[key] = parseInt(el.value, 10);
      } else {
        data[key] = el.value;
      }
    }
    // Preserve the current id if editing an existing personality
    if (persCurrentId) data.id = persCurrentId;
    return data;
  }

  function showPersonalityStatus(msg) {
    var el = document.getElementById('personality-status');
    if (el) {
      el.textContent = msg;
      setTimeout(function () { el.textContent = ''; }, 3000);
    }
  }

  document.getElementById('personality-save-btn').addEventListener('click', function () {
    var data = collectPersonalityForm();
    fetch('/api/personality', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(function (r) { return r.json(); }).then(function (res) {
      if (res.success) {
        persCurrentId = res.id;
        showPersonalityStatus('Saved!');
        loadPersonalitySidebar();
      } else {
        showPersonalityStatus('Failed: ' + (res.message || ''));
      }
    }).catch(function () {
      showPersonalityStatus('Failed to save.');
    });
  });

  document.getElementById('personality-randomize-btn').addEventListener('click', function () {
    fetch('/api/personality/randomize', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        populatePersonalityForm(data);
        persCurrentId = data.id || '';
        showPersonalityStatus('Generated: ' + (data.name || 'Unnamed'));
        loadPersonalitySidebar();
      }).catch(function () {});
  });

  // ═══ TTS settings ═══

  var openaiVoices = ['alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'nova', 'onyx', 'sage', 'shimmer'];

  var ttsConfig = null;
  var ttsPriorityList = document.getElementById('tts-priority-list');

  function loadTtsSettings() {
    fetch('/api/tts/config').then(function (r) { return r.json(); }).then(function (data) {
      ttsConfig = data;
      renderTtsPriority(data.priority || ['fishaudio', 'elevenlabs', 'openai']);
      renderTtsVoicePanels(data);

      var cb = document.getElementById('tts-autospeak-cb');
      if (cb) cb.checked = data.autoSpeak !== false;
    }).catch(function () {
      renderTtsPriority(['fishaudio', 'elevenlabs', 'openai']);
    });
  }

  function renderTtsPriority(order) {
    if (!ttsPriorityList) return;
    ttsPriorityList.innerHTML = '';
    order.forEach(function (key, idx) {
      var def = ttsDefs.find(function (d) { return d.key === key; }) || { key: key, label: key, color: '#aaa', icon: '' };
      var isEnabled = ttsConfig && ttsConfig.enabled ? ttsConfig.enabled[key] !== false : true;
      var isAvailable = ttsConfig && ttsConfig.available ? ttsConfig.available[key] : false;

      var li = document.createElement('li');
      li.className = 'tts-priority-item' + (isEnabled ? '' : ' tts-engine-disabled');
      li.dataset.engine = key;
      li.draggable = true;

      li.innerHTML = '<span class="tts-priority-rank">' + (idx + 1) + '</span>'
        + '<label class="tts-toggle"><input type="checkbox" class="tts-enabled-cb" data-engine="' + key + '"'
        + (isEnabled ? ' checked' : '') + '>'
        + '<span class="tts-priority-icon">' + def.icon + '</span>'
        + '<span class="tts-priority-name" style="color:' + (isEnabled ? def.color : '#555') + '">' + def.label + '</span></label>'
        + '<span class="tts-avail-badge ' + (isAvailable ? 'tts-avail-ready' : 'tts-avail-none') + '">'
        + (isAvailable ? 'Ready' : 'No key') + '</span>'
        + '<span class="tts-priority-arrows">'
        + '<button class="tp-up" title="Move up">&#9650;</button>'
        + '<button class="tp-down" title="Move down">&#9660;</button>'
        + '</span>'
        + '<span class="tts-drag-handle" title="Drag to reorder">&#8942;&#8942;</span>';

      // Enable/disable toggle
      li.querySelector('.tts-enabled-cb').addEventListener('change', function (e) {
        if (ttsConfig && ttsConfig.enabled) ttsConfig.enabled[key] = e.target.checked;
        fetch('/api/tts/enabled', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: ttsConfig ? ttsConfig.enabled : {} })
        });
        renderTtsPriority(getTtsPriorityOrder());
      });

      // Arrow buttons
      li.querySelector('.tp-up').addEventListener('click', function () { moveTtsEngine(idx, -1); });
      li.querySelector('.tp-down').addEventListener('click', function () { moveTtsEngine(idx, 1); });

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
          var current = getTtsPriorityOrder();
          var moved = current.splice(fromIdx, 1)[0];
          current.splice(toIdx, 0, moved);
          renderTtsPriority(current);
          saveTtsPriority(current);
        }
      });

      ttsPriorityList.appendChild(li);
    });
  }

  function getTtsPriorityOrder() {
    return Array.from(ttsPriorityList.querySelectorAll('.tts-priority-item')).map(function (li) { return li.dataset.engine; });
  }

  function moveTtsEngine(idx, dir) {
    var order = getTtsPriorityOrder();
    var newIdx = idx + dir;
    if (newIdx < 0 || newIdx >= order.length) return;
    var tmp = order[idx];
    order[idx] = order[newIdx];
    order[newIdx] = tmp;
    renderTtsPriority(order);
    saveTtsPriority(order);
  }

  function saveTtsPriority(order) {
    fetch('/api/tts/priority', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ priority: order })
    });
  }

  function renderTtsVoicePanels(data) {
    var container = document.getElementById('tts-voice-panels');
    if (!container) return;
    container.innerHTML = '';

    var voices = data.voices || {};

    // Fish Audio panel
    var fishVoice = voices.fishaudio || {};
    container.innerHTML += '<div class="tts-voice-panel">'
      + '<div class="tts-voice-panel-title" style="color:#06b6d4">🐟 Fish Audio</div>'
      + '<div class="tts-voice-field">'
      + '<label>Reference ID <span class="tts-field-hint">(from fish.audio)</span></label>'
      + '<input type="text" id="tts-fish-ref" class="tab-input" placeholder="e.g. a0e9...' + '" value="' + (fishVoice.referenceId || '') + '">'
      + '</div>'
      + '<div class="tts-voice-field">'
      + '<label>Model</label>'
      + '<select id="tts-fish-model" class="tab-input">'
      + '<option value="s1"' + (fishVoice.model === 's1' ? ' selected' : '') + '>S1</option>'
      + '<option value="s2-pro"' + (fishVoice.model === 's2-pro' || !fishVoice.model ? ' selected' : '') + '>S2 Pro</option>'
      + '</select>'
      + '</div>'
      + '<button class="action-btn tts-voice-save" data-engine="fishaudio">Save</button>'
      + '</div>';

    // ElevenLabs panel
    var elevenVoice = voices.elevenlabs || {};
    container.innerHTML += '<div class="tts-voice-panel">'
      + '<div class="tts-voice-panel-title" style="color:#8b5cf6">🔊 ElevenLabs</div>'
      + '<div class="tts-voice-field">'
      + '<label>Voice ID <span class="tts-field-hint">(from elevenlabs.io)</span></label>'
      + '<input type="text" id="tts-eleven-vid" class="tab-input" placeholder="e.g. EXAVITQu4vr4xnSDxMaL" value="' + (elevenVoice.voiceId || '') + '">'
      + '</div>'
      + '<div class="tts-voice-field">'
      + '<label>Model</label>'
      + '<input type="text" id="tts-eleven-model" class="tab-input" value="' + (elevenVoice.modelId || 'eleven_multilingual_v2') + '">'
      + '</div>'
      + '<button class="action-btn tts-voice-save" data-engine="elevenlabs">Save</button>'
      + '</div>';

    // OpenAI panel
    var openaiVoice = voices.openai || {};
    var voiceOptions = openaiVoices.map(function (v) {
      return '<option value="' + v + '"' + (openaiVoice.voice === v ? ' selected' : '') + '>' + v.charAt(0).toUpperCase() + v.slice(1) + '</option>';
    }).join('');
    container.innerHTML += '<div class="tts-voice-panel">'
      + '<div class="tts-voice-panel-title" style="color:#10b981">🤖 OpenAI TTS</div>'
      + '<div class="tts-voice-field">'
      + '<label>Voice</label>'
      + '<select id="tts-openai-voice" class="tab-input">' + voiceOptions + '</select>'
      + '</div>'
      + '<div class="tts-voice-field">'
      + '<label>Speed <span class="tts-field-hint">(0.25 – 4.0)</span></label>'
      + '<input type="number" id="tts-openai-speed" class="tab-input" min="0.25" max="4" step="0.05" value="' + (openaiVoice.speed || 1.0) + '">'
      + '</div>'
      + '<button class="action-btn tts-voice-save" data-engine="openai">Save</button>'
      + '</div>';

    // Attach save handlers
    container.querySelectorAll('.tts-voice-save').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var engine = btn.dataset.engine;
        var payload = { engine: engine };
        if (engine === 'fishaudio') {
          payload.referenceId = document.getElementById('tts-fish-ref').value.trim();
        } else if (engine === 'elevenlabs') {
          payload.voiceId = document.getElementById('tts-eleven-vid').value.trim();
        } else if (engine === 'openai') {
          payload.voice = document.getElementById('tts-openai-voice').value;
          payload.speed = document.getElementById('tts-openai-speed').value;
        }
        fetch('/api/tts/voice', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        }).then(function () {
          btn.textContent = 'Saved!';
          setTimeout(function () { btn.textContent = 'Save'; }, 1500);
        });
      });
    });
  }

  // Auto-speak toggle
  var autoSpeakCb = document.getElementById('tts-autospeak-cb');
  if (autoSpeakCb) {
    autoSpeakCb.addEventListener('change', function () {
      fetch('/api/tts/auto-speak', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: autoSpeakCb.checked })
      });
    });
  }

  // Test voice button
  var ttsTestBtn = document.getElementById('tts-test-btn');
  if (ttsTestBtn) {
    ttsTestBtn.addEventListener('click', function () {
      var text = document.getElementById('tts-test-text').value.trim();
      if (!text) return;
      ttsTestBtn.textContent = 'Playing...';
      ttsTestBtn.disabled = true;
      // Use the first enabled engine in priority order
      var order = getTtsPriorityOrder();
      var engine = 'fishaudio';
      for (var i = 0; i < order.length; i++) {
        if (ttsConfig && ttsConfig.enabled && ttsConfig.enabled[order[i]] !== false) {
          engine = order[i];
          break;
        }
      }
      fetch('/api/tts/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ engine: engine, text: text })
      }).then(function (r) { return r.json(); }).then(function (data) {
        ttsTestBtn.textContent = data.success ? 'Test Voice' : 'Failed';
        ttsTestBtn.disabled = false;
        if (!data.success) setTimeout(function () { ttsTestBtn.textContent = 'Test Voice'; }, 2000);
      }).catch(function () {
        ttsTestBtn.textContent = 'Error';
        ttsTestBtn.disabled = false;
        setTimeout(function () { ttsTestBtn.textContent = 'Test Voice'; }, 2000);
      });
    });
  }

  // ═══ Workflow Builder tab ═══

  var wfNewBtn = document.getElementById('wf-new-btn');
  var wfEditor = document.getElementById('wf-editor');
  var wfNameInput = document.getElementById('wf-name');
  var wfTriggerInput = document.getElementById('wf-trigger');
  var wfStepsEl = document.getElementById('wf-steps');
  var wfAddStepBtn = document.getElementById('wf-add-step');
  var wfSaveBtn = document.getElementById('wf-save-btn');
  var wfCancelBtn = document.getElementById('wf-cancel-btn');
  var wfListEl = document.getElementById('wf-list');
  var wfEditingId = null;

  function loadWorkflows() {
    if (!wfListEl) return;
    fetch('/api/tabs/workflows').then(function (r) { return r.json(); }).then(function (workflows) {
      if (!workflows || workflows.length === 0) {
        wfListEl.innerHTML = '<div class="tab-empty">No workflows yet. Create one to chain tools together.</div>';
        return;
      }
      var html = '';
      workflows.forEach(function (w) {
        var stepCount = (w.steps || []).length;
        html += '<div class="wf-card">'
          + '<div class="wf-card-header">'
          + '<div class="wf-card-info">'
          + '<div class="wf-card-name">' + escapeHtml(w.name || 'Untitled') + '</div>'
          + '<div class="wf-card-meta">' + escapeHtml(w.trigger || 'manual') + ' &middot; ' + stepCount + ' step' + (stepCount !== 1 ? 's' : '') + '</div>'
          + '</div>'
          + '<div class="wf-card-actions">'
          + '<button class="action-btn publish" onclick="window._wfRun(\'' + w.id + '\')" title="Run now">&#9654; Run</button>'
          + '<button class="action-btn" onclick="window._wfEdit(\'' + w.id + '\')" title="Edit">Edit</button>'
          + '<button class="action-btn" onclick="window._wfDelete(\'' + w.id + '\')" title="Delete">&times;</button>'
          + '</div></div>';
        if (w.steps && w.steps.length > 0) {
          html += '<div class="wf-card-steps">';
          w.steps.forEach(function (s, i) {
            html += '<div class="wf-step-preview">'
              + '<span class="wf-step-num">' + (i + 1) + '</span>'
              + '<span class="wf-step-action">' + escapeHtml(s.action || '') + '</span>'
              + '<span class="wf-step-detail">' + escapeHtml(s.detail || '') + '</span>'
              + '</div>';
          });
          html += '</div>';
        }
        html += '</div>';
      });
      wfListEl.innerHTML = html;
    }).catch(function () {
      wfListEl.innerHTML = '<div class="tab-empty">Failed to load workflows.</div>';
    });
  }

  function wfShowEditor(workflow) {
    wfEditingId = workflow ? workflow.id : null;
    if (wfNameInput) wfNameInput.value = workflow ? (workflow.name || '') : '';
    if (wfTriggerInput) wfTriggerInput.value = workflow ? (workflow.trigger || '') : '';
    if (wfStepsEl) wfStepsEl.innerHTML = '';
    if (workflow && workflow.steps) {
      workflow.steps.forEach(function (s) { wfAddStepRow(s.action, s.detail); });
    } else {
      wfAddStepRow('', '');
    }
    if (wfEditor) wfEditor.hidden = false;
  }

  function wfAddStepRow(action, detail) {
    if (!wfStepsEl) return;
    var idx = wfStepsEl.children.length + 1;
    var row = document.createElement('div');
    row.className = 'wf-step-row';
    row.innerHTML = '<span class="wf-step-num">' + idx + '</span>'
      + '<select class="wf-step-action-select">'
      + '<option value="check email"' + (action === 'check email' ? ' selected' : '') + '>Check email</option>'
      + '<option value="web search"' + (action === 'web search' ? ' selected' : '') + '>Web search</option>'
      + '<option value="summarize"' + (action === 'summarize' ? ' selected' : '') + '>Summarize</option>'
      + '<option value="translate"' + (action === 'translate' ? ' selected' : '') + '>Translate</option>'
      + '<option value="speak aloud"' + (action === 'speak aloud' ? ' selected' : '') + '>Speak aloud</option>'
      + '<option value="send message"' + (action === 'send message' ? ' selected' : '') + '>Send message</option>'
      + '<option value="take screenshot"' + (action === 'take screenshot' ? ' selected' : '') + '>Take screenshot</option>'
      + '<option value="run command"' + (action === 'run command' ? ' selected' : '') + '>Run command</option>'
      + '<option value="wait"' + (action === 'wait' ? ' selected' : '') + '>Wait</option>'
      + '<option value="custom"' + (action === 'custom' ? ' selected' : '') + '>Custom</option>'
      + '</select>'
      + '<input type="text" class="tab-input wf-step-detail-input" placeholder="Details..." value="' + escapeHtml(detail || '') + '">'
      + '<button class="wf-step-remove" onclick="this.parentElement.remove();window._wfReindex()" title="Remove">&times;</button>';
    wfStepsEl.appendChild(row);
  }

  window._wfReindex = function () {
    if (!wfStepsEl) return;
    Array.from(wfStepsEl.children).forEach(function (row, i) {
      var num = row.querySelector('.wf-step-num');
      if (num) num.textContent = i + 1;
    });
  };

  if (wfNewBtn) wfNewBtn.addEventListener('click', function () { wfShowEditor(null); });
  if (wfAddStepBtn) wfAddStepBtn.addEventListener('click', function () { wfAddStepRow('', ''); });
  if (wfCancelBtn) wfCancelBtn.addEventListener('click', function () {
    if (wfEditor) wfEditor.hidden = true;
    wfEditingId = null;
  });

  if (wfSaveBtn) wfSaveBtn.addEventListener('click', function () {
    var steps = [];
    if (wfStepsEl) {
      Array.from(wfStepsEl.children).forEach(function (row) {
        var sel = row.querySelector('.wf-step-action-select');
        var inp = row.querySelector('.wf-step-detail-input');
        if (sel && inp) steps.push({ action: sel.value, detail: inp.value });
      });
    }
    var wf = {
      name: wfNameInput ? wfNameInput.value : '',
      trigger: wfTriggerInput ? wfTriggerInput.value : '',
      steps: steps
    };
    if (wfEditingId) wf.id = wfEditingId;
    fetch('/api/tabs/workflows', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(wf)
    }).then(function () {
      if (wfEditor) wfEditor.hidden = true;
      wfEditingId = null;
      loadWorkflows();
    });
  });

  window._wfRun = function (id) {
    fetch('/api/tabs/workflows/' + id + '/run', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        // Switch to chat tab to show the workflow execution
        var chatTab = document.querySelector('[data-tab="chat"]');
        if (chatTab) chatTab.click();
      });
  };

  window._wfEdit = function (id) {
    fetch('/api/tabs/workflows').then(function (r) { return r.json(); }).then(function (list) {
      var wf = list.find(function (w) { return w.id === id; });
      if (wf) wfShowEditor(wf);
    });
  };

  window._wfDelete = function (id) {
    fetch('/api/tabs/workflows/' + id, { method: 'DELETE' }).then(function () { loadWorkflows(); });
  };

  // ═══ Prompt Templates tab ═══

  var ptNewBtn = document.getElementById('pt-new-btn');
  var ptEditor = document.getElementById('pt-editor');
  var ptNameInput = document.getElementById('pt-name');
  var ptCategoryInput = document.getElementById('pt-category');
  var ptBodyInput = document.getElementById('pt-body');
  var ptSaveBtn = document.getElementById('pt-save-btn');
  var ptCancelBtn = document.getElementById('pt-cancel-btn');
  var ptListEl = document.getElementById('pt-list');
  var ptEditingId = null;

  function loadTemplates() {
    if (!ptListEl) return;
    fetch('/api/tabs/templates').then(function (r) { return r.json(); }).then(function (templates) {
      if (!templates || templates.length === 0) {
        ptListEl.innerHTML = '<div class="tab-empty">No templates yet. Save reusable prompts with variables.</div>';
        return;
      }
      var html = '';
      templates.forEach(function (t) {
        var vars = (t.body || '').match(/\{\{(\w+)\}\}/g) || [];
        var uniqueVars = [];
        vars.forEach(function (v) { var name = v.replace(/\{|\}/g, ''); if (uniqueVars.indexOf(name) === -1) uniqueVars.push(name); });
        html += '<div class="pt-card">'
          + '<div class="pt-card-header">'
          + '<div class="pt-card-info">'
          + '<div class="pt-card-name">' + escapeHtml(t.name || 'Untitled') + '</div>'
          + '<div class="pt-card-meta">' + escapeHtml(t.category || 'General') + (uniqueVars.length ? ' &middot; ' + uniqueVars.length + ' variable' + (uniqueVars.length > 1 ? 's' : '') : '') + '</div>'
          + '</div>'
          + '<div class="pt-card-actions">'
          + '<button class="action-btn publish" onclick="window._ptUse(\'' + t.id + '\')" title="Use">Use</button>'
          + '<button class="action-btn" onclick="window._ptEdit(\'' + t.id + '\')" title="Edit">Edit</button>'
          + '<button class="action-btn" onclick="window._ptDelete(\'' + t.id + '\')" title="Delete">&times;</button>'
          + '</div></div>'
          + '<div class="pt-card-body">' + escapeHtml(t.body || '') + '</div>';
        if (uniqueVars.length > 0) {
          html += '<div class="pt-card-vars" id="pt-vars-' + t.id + '">';
          uniqueVars.forEach(function (v) {
            html += '<div class="pt-var-row">'
              + '<label class="pt-var-label">' + escapeHtml(v) + '</label>'
              + '<input type="text" class="tab-input pt-var-input" data-var="' + escapeHtml(v) + '" placeholder="Enter ' + escapeHtml(v) + '...">'
              + '</div>';
          });
          html += '</div>';
        }
        html += '</div>';
      });
      ptListEl.innerHTML = html;
    }).catch(function () {
      ptListEl.innerHTML = '<div class="tab-empty">Failed to load templates.</div>';
    });
  }

  if (ptNewBtn) ptNewBtn.addEventListener('click', function () {
    ptEditingId = null;
    if (ptNameInput) ptNameInput.value = '';
    if (ptCategoryInput) ptCategoryInput.value = '';
    if (ptBodyInput) ptBodyInput.value = '';
    if (ptEditor) ptEditor.hidden = false;
  });

  if (ptCancelBtn) ptCancelBtn.addEventListener('click', function () {
    if (ptEditor) ptEditor.hidden = true;
    ptEditingId = null;
  });

  if (ptSaveBtn) ptSaveBtn.addEventListener('click', function () {
    var tmpl = {
      name: ptNameInput ? ptNameInput.value : '',
      category: ptCategoryInput ? ptCategoryInput.value : '',
      body: ptBodyInput ? ptBodyInput.value : ''
    };
    if (ptEditingId) tmpl.id = ptEditingId;
    fetch('/api/tabs/templates', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(tmpl)
    }).then(function () {
      if (ptEditor) ptEditor.hidden = true;
      ptEditingId = null;
      loadTemplates();
    });
  });

  window._ptUse = function (id) {
    var varsContainer = document.getElementById('pt-vars-' + id);
    var variables = {};
    if (varsContainer) {
      varsContainer.querySelectorAll('.pt-var-input').forEach(function (inp) {
        variables[inp.getAttribute('data-var')] = inp.value;
      });
    }
    fetch('/api/tabs/templates/' + id + '/use', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(variables)
    }).then(function (r) { return r.json(); }).then(function (data) {
      if (data.result && inputEl) {
        inputEl.value = data.result;
        var chatTab = document.querySelector('[data-tab="chat"]');
        if (chatTab) chatTab.click();
        focusInput();
      }
    });
  };

  window._ptEdit = function (id) {
    fetch('/api/tabs/templates').then(function (r) { return r.json(); }).then(function (list) {
      var t = list.find(function (x) { return x.id === id; });
      if (t) {
        ptEditingId = t.id;
        if (ptNameInput) ptNameInput.value = t.name || '';
        if (ptCategoryInput) ptCategoryInput.value = t.category || '';
        if (ptBodyInput) ptBodyInput.value = t.body || '';
        if (ptEditor) ptEditor.hidden = false;
      }
    });
  };

  window._ptDelete = function (id) {
    fetch('/api/tabs/templates/' + id, { method: 'DELETE' }).then(function () { loadTemplates(); });
  };

  // ═══ Plugin Marketplace tab ═══

  var mpGrid = document.getElementById('mp-grid');
  var mpCategoryFilter = document.getElementById('mp-category-filter');
  var mpSourceFilter = document.getElementById('mp-source-filter');
  var mpPubBtn = document.getElementById('mp-pub-btn');
  var mpAllPlugins = [];

  function loadMarketplace() {
    if (!mpGrid) return;
    fetch('/api/tabs/marketplace').then(function (r) { return r.json(); }).then(function (plugins) {
      mpAllPlugins = plugins || [];
      renderMarketplace();
    }).catch(function () {
      mpGrid.innerHTML = '<div class="tab-empty">Failed to load marketplace.</div>';
    });
  }

  function renderMarketplace() {
    var cat = mpCategoryFilter ? mpCategoryFilter.value : 'all';
    var src = mpSourceFilter ? mpSourceFilter.value : 'all';
    var filtered = mpAllPlugins.filter(function (p) {
      if (cat !== 'all' && p.category !== cat) return false;
      if (src !== 'all' && p.source !== src) return false;
      return true;
    });
    if (filtered.length === 0) {
      mpGrid.innerHTML = '<div class="tab-empty">No plugins match the current filters.</div>';
      return;
    }
    var html = '';
    filtered.forEach(function (p) {
      var isInstalled = !!p.installed;
      var sourceLabel = p.source === 'builtin' ? 'Built-in' : (p.source === 'community' ? 'Community' : 'Catalog');
      var sourceCls = 'mp-source-' + (p.source || 'catalog');
      var categoryIcon = mpCategoryIcon(p.category);
      html += '<div class="mp-card ' + (isInstalled ? 'mp-installed' : '') + '">'
        + '<div class="mp-card-icon">' + categoryIcon + '</div>'
        + '<div class="mp-card-body">'
        + '<div class="mp-card-name">' + escapeHtml(p.name || '') + '</div>'
        + '<div class="mp-card-desc">' + escapeHtml(p.description || '') + '</div>'
        + '<div class="mp-card-meta">'
        + '<span class="mp-badge ' + sourceCls + '">' + sourceLabel + '</span>'
        + '<span class="mp-category">' + escapeHtml(p.category || '') + '</span>'
        + '<span class="mp-author">by ' + escapeHtml(p.author || 'Unknown') + '</span>'
        + '</div>'
        + '</div>'
        + '<div class="mp-card-action">';
      if (p.source === 'builtin') {
        html += '<span class="mp-installed-label">Included</span>';
      } else if (isInstalled) {
        html += '<button class="action-btn mp-uninstall-btn" onclick="window._mpUninstall(\'' + p.id + '\')">Uninstall</button>';
      } else {
        html += '<button class="action-btn publish" onclick="window._mpInstall(\'' + p.id + '\')">Install</button>';
      }
      html += '</div></div>';
    });
    mpGrid.innerHTML = html;
  }

  function mpCategoryIcon(cat) {
    var icons = {
      'Utilities': '<i class="fa-solid fa-wrench"></i>',
      'Search': '<i class="fa-solid fa-magnifying-glass"></i>',
      'Vision': '<i class="fa-solid fa-eye"></i>',
      'Automation': '<i class="fa-solid fa-robot"></i>',
      'Communication': '<i class="fa-solid fa-envelope"></i>',
      'Finance': '<i class="fa-solid fa-chart-line"></i>',
      'Language': '<i class="fa-solid fa-language"></i>',
      'Development': '<i class="fa-solid fa-code"></i>',
      'Productivity': '<i class="fa-solid fa-bolt"></i>'
    };
    return icons[cat] || '<i class="fa-solid fa-puzzle-piece"></i>';
  }

  if (mpCategoryFilter) mpCategoryFilter.addEventListener('change', renderMarketplace);
  if (mpSourceFilter) mpSourceFilter.addEventListener('change', renderMarketplace);

  window._mpInstall = function (id) {
    fetch('/api/tabs/marketplace/' + id + '/install', { method: 'POST' }).then(function () { loadMarketplace(); });
  };

  window._mpUninstall = function (id) {
    fetch('/api/tabs/marketplace/' + id + '/uninstall', { method: 'POST' }).then(function () { loadMarketplace(); });
  };

  if (mpPubBtn) mpPubBtn.addEventListener('click', function () {
    var name = document.getElementById('mp-pub-name');
    var desc = document.getElementById('mp-pub-desc');
    var author = document.getElementById('mp-pub-author');
    var category = document.getElementById('mp-pub-category');
    if (!name || !name.value.trim()) return;
    fetch('/api/tabs/marketplace/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: name.value.trim(),
        description: desc ? desc.value.trim() : '',
        author: author ? author.value.trim() : '',
        category: category ? category.value.trim() : 'Utilities'
      })
    }).then(function () {
      if (name) name.value = '';
      if (desc) desc.value = '';
      if (author) author.value = '';
      if (category) category.value = '';
      loadMarketplace();
    });
  });

  // ═══ Dashboard / Analytics tab ═══

  function loadDashboard() {
    fetch('/api/analytics').then(function (r) { return r.json(); }).then(function (d) {
      var el = function(id) { return document.getElementById(id); };
      var fmtMs = function (ms) {
        if (!ms || ms <= 0) return '—';
        if (ms < 1000) return Math.round(ms) + ' ms';
        return (ms / 1000).toFixed(1) + ' s';
      };

      // Hero stats
      if (el('dash-requests')) el('dash-requests').textContent = (d.totalRequests || 0).toLocaleString();
      if (el('dash-uptime')) el('dash-uptime').textContent = d.uptime || '—';
      if (el('dash-avg-time')) el('dash-avg-time').textContent = fmtMs(d.avgResponseTimeMs);
      if (el('dash-tokens')) el('dash-tokens').textContent = ((d.totalInputTokens || 0) + (d.totalOutputTokens || 0)).toLocaleString();
      if (el('dash-cost')) el('dash-cost').textContent = '$' + (d.estimatedCost || 0).toFixed(4);
      if (el('dash-token-split')) el('dash-token-split').textContent = (d.totalInputTokens || 0).toLocaleString() + ' in · ' + (d.totalOutputTokens || 0).toLocaleString() + ' out';

      // Response-time stat pills in the chart header
      if (el('dash-avg-label')) el('dash-avg-label').textContent = fmtMs(d.avgResponseTimeMs);
      if (el('dash-min-label')) el('dash-min-label').textContent = fmtMs(d.minResponseTimeMs);
      if (el('dash-max-label')) el('dash-max-label').textContent = fmtMs(d.maxResponseTimeMs);

      // Top tools — horizontal bar chart
      var toolsEl = el('dash-tools');
      if (toolsEl && d.toolUsage) {
        var entries = Object.entries(d.toolUsage).sort(function (a, b) { return b[1] - a[1]; });
        if (entries.length === 0) {
          toolsEl.innerHTML = '<div class="tab-empty">No tool calls yet this session.</div>';
        } else {
          var html = '';
          var max = entries[0][1];
          entries.slice(0, 15).forEach(function (e) {
            var pct = Math.max(4, (e[1] / max) * 100);
            html += '<div class="dash-tool-row">'
              + '<span class="dash-tool-name" title="' + escapeHtml(e[0]) + '">' + escapeHtml(e[0]) + '</span>'
              + '<div class="dash-tool-bar-bg"><div class="dash-tool-bar" style="width:' + pct + '%"></div></div>'
              + '<span class="dash-tool-count">' + e[1].toLocaleString() + '</span>'
              + '</div>';
          });
          toolsEl.innerHTML = html;
        }
      }

      // Response-time chart — HiDPI-aware, resized to actual canvas width,
      // gradient fill below a smooth line, light baseline grid.
      var canvas = el('dash-chart');
      if (canvas && d.recentResponseTimesMs) {
        var dpr = window.devicePixelRatio || 1;
        var cssW = canvas.clientWidth || 800;
        var cssH = canvas.clientHeight || 140;
        canvas.width = Math.floor(cssW * dpr);
        canvas.height = Math.floor(cssH * dpr);
        var ctx = canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssW, cssH);

        var pts = d.recentResponseTimesMs.slice(-20);

        // Baseline grid (3 horizontal lines at 25/50/75%)
        ctx.strokeStyle = 'rgba(255,255,255,0.05)';
        ctx.lineWidth = 1;
        [0.25, 0.5, 0.75].forEach(function (fr) {
          var y = cssH * fr;
          ctx.beginPath();
          ctx.moveTo(0, y); ctx.lineTo(cssW, y); ctx.stroke();
        });

        if (pts.length > 1) {
          var maxVal = Math.max.apply(null, pts) || 1;
          var padY = 10;
          var sx = function (i) { return (i / (pts.length - 1)) * cssW; };
          var sy = function (v) { return cssH - (v / maxVal) * (cssH - padY * 2) - padY; };

          // Filled area below the line (gradient)
          var grad = ctx.createLinearGradient(0, 0, 0, cssH);
          grad.addColorStop(0, 'rgba(99,102,241,0.35)');
          grad.addColorStop(1, 'rgba(99,102,241,0)');
          ctx.beginPath();
          ctx.moveTo(sx(0), sy(pts[0]));
          for (var i = 1; i < pts.length; i++) ctx.lineTo(sx(i), sy(pts[i]));
          ctx.lineTo(cssW, cssH); ctx.lineTo(0, cssH); ctx.closePath();
          ctx.fillStyle = grad;
          ctx.fill();

          // Line on top
          ctx.strokeStyle = '#818cf8';
          ctx.lineWidth = 2;
          ctx.lineJoin = 'round';
          ctx.beginPath();
          ctx.moveTo(sx(0), sy(pts[0]));
          for (var j = 1; j < pts.length; j++) ctx.lineTo(sx(j), sy(pts[j]));
          ctx.stroke();

          // Last-point marker
          var lastX = sx(pts.length - 1), lastY = sy(pts[pts.length - 1]);
          ctx.fillStyle = '#a78bfa';
          ctx.beginPath();
          ctx.arc(lastX, lastY, 3.5, 0, Math.PI * 2);
          ctx.fill();
        } else {
          ctx.fillStyle = 'rgba(255,255,255,0.25)';
          ctx.font = '11px system-ui';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText('No response-time data yet.', cssW / 2, cssH / 2);
        }
      }

      // Last-updated timestamp
      var updEl = el('dash-last-updated');
      if (updEl) {
        var now = new Date();
        updEl.textContent = 'Updated ' + now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      }
    }).catch(function () {});
  }

  var dashRefresh = document.getElementById('dashboard-refresh');
  if (dashRefresh) dashRefresh.addEventListener('click', loadDashboard);

  // ═══ Auto-pilot mode ═══

  var headerAutopilot = document.getElementById('header-autopilot');

  function refreshAutopilotStatus() {
    fetch('/api/status/autopilot').then(function (r) { return r.json(); }).then(function (d) {
      if (headerAutopilot) {
        headerAutopilot.classList.toggle('autopilot-active', !!d.enabled);
        headerAutopilot.title = d.enabled ? 'Auto-pilot ON (click to disable)' : 'Auto-pilot OFF (click to enable)';
      }
    }).catch(function () {});
  }

  if (headerAutopilot) {
    headerAutopilot.addEventListener('click', function () {
      fetch('/api/autopilot/toggle', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (d) {
        headerAutopilot.classList.toggle('autopilot-active', !!d.enabled);
        appendMessage(d.message || (d.enabled ? 'Auto-pilot enabled.' : 'Auto-pilot disabled.'), false);
      }).catch(function () {});
    });
  }

  refreshAutopilotStatus();

  // ═══ Proactive Action Mode ═══

  var headerProactive = document.getElementById('header-proactive');

  function refreshProactiveStatus() {
    fetch('/api/status/proactive-action').then(function (r) { return r.json(); }).then(function (d) {
      if (headerProactive) {
        headerProactive.classList.toggle('active', !!d.active);
        headerProactive.title = d.active ? 'Proactive mode ON — auto-acting on screen, tasks & directives (click to disable)' : 'Proactive mode OFF (click to enable)';
      }
    }).catch(function () {});
  }

  if (headerProactive) {
    headerProactive.addEventListener('click', function () {
      fetch('/api/proactive-action/toggle', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (d) {
        headerProactive.classList.toggle('active', !!d.active);
        appendMessage(d.message || (d.active ? 'Proactive action mode enabled.' : 'Proactive action mode disabled.'), false);
        if (window._minsSound) window._minsSound.notification();
      }).catch(function () {});
    });
  }

  refreshProactiveStatus();

  // ═══ Multi-Agent Chat tab ═══

  var maConvId = null;
  var maSelectedAgents = new Set();

  function loadMultiAgent() {
    fetch('/api/multi-agent/personas').then(function (r) { return r.json(); }).then(function (personas) {
      var chips = document.getElementById('ma-persona-chips');
      if (!chips) return;
      var html = '';
      personas.forEach(function (p) {
        var selected = maSelectedAgents.has(p.name);
        html += '<button class="ma-chip' + (selected ? ' ma-chip-active' : '') + '" data-agent="' + escapeHtml(p.name) + '" style="--agent-color:' + p.color + '">'
          + '<span class="ma-chip-dot" style="background:' + p.color + '"></span>'
          + escapeHtml(p.name)
          + '</button>';
      });
      chips.innerHTML = html;
      chips.querySelectorAll('.ma-chip').forEach(function (chip) {
        chip.addEventListener('click', function () {
          var name = this.dataset.agent;
          if (maSelectedAgents.has(name)) {
            maSelectedAgents.delete(name);
            this.classList.remove('ma-chip-active');
          } else {
            maSelectedAgents.add(name);
            this.classList.add('ma-chip-active');
          }
        });
      });
    });
  }

  var maInput = document.getElementById('ma-input');
  var maSendBtn = document.getElementById('ma-send-btn');
  var maMessages = document.getElementById('ma-messages');
  var maAddBtn = document.getElementById('ma-add-btn');

  function maAppendMessage(role, content, agent, color) {
    if (!maMessages) return;
    var div = document.createElement('div');
    div.className = 'ma-msg ma-msg-' + role;
    if (role === 'assistant') {
      var header = document.createElement('div');
      header.className = 'ma-msg-agent';
      header.style.color = color || '#888';
      header.textContent = agent || 'Agent';
      div.appendChild(header);
    }
    var body = document.createElement('div');
    body.className = 'ma-msg-body';
    body.textContent = content;
    div.appendChild(body);
    maMessages.appendChild(div);
    maMessages.scrollTop = maMessages.scrollHeight;
  }

  function maSend() {
    if (!maInput || !maInput.value.trim()) return;
    if (maSelectedAgents.size === 0) { alert('Select at least one agent.'); return; }
    var msg = maInput.value.trim();
    maInput.value = '';
    maAppendMessage('user', msg);

    var startPromise = maConvId
      ? Promise.resolve(maConvId)
      : fetch('/api/multi-agent/conversations', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}'
        }).then(function (r) { return r.json(); }).then(function (d) { maConvId = d.conversationId; return maConvId; });

    startPromise.then(function (convId) {
      var thinking = document.createElement('div');
      thinking.className = 'ma-thinking';
      thinking.textContent = 'Agents are thinking...';
      maMessages.appendChild(thinking);
      maMessages.scrollTop = maMessages.scrollHeight;

      fetch('/api/multi-agent/conversations/' + convId + '/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg, agents: Array.from(maSelectedAgents) })
      }).then(function (r) { return r.json(); }).then(function (data) {
        thinking.remove();
        (data.responses || []).forEach(function (r) {
          maAppendMessage('assistant', r.content, r.agent, r.color);
        });
      }).catch(function () {
        thinking.remove();
        maAppendMessage('assistant', '(Failed to get responses)', 'System', '#666');
      });
    });
  }

  if (maSendBtn) maSendBtn.addEventListener('click', maSend);
  if (maInput) maInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); maSend(); }
  });

  if (maAddBtn) maAddBtn.addEventListener('click', function () {
    var name = document.getElementById('ma-new-name');
    var prompt = document.getElementById('ma-new-prompt');
    var color = document.getElementById('ma-new-color');
    if (!name || !name.value.trim()) return;
    fetch('/api/multi-agent/personas', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: name.value.trim(),
        systemPrompt: prompt ? prompt.value.trim() : 'You are a helpful assistant.',
        color: color ? color.value : '#8b5cf6'
      })
    }).then(function () {
      if (name) name.value = '';
      if (prompt) prompt.value = '';
      loadMultiAgent();
    });
  });

  // ═══ Automations tab ═══

  function loadAutomations() {
    var list = document.getElementById('auto-rules-list');
    if (!list) return;
    fetch('/api/automations').then(function (r) { return r.json(); }).then(function (rules) {
      if (!rules || rules.length === 0) {
        list.innerHTML = '<div class="tab-empty">No automations yet. Add a rule above.</div>';
        return;
      }
      var html = '';
      rules.forEach(function (r) {
        var enabled = r.enabled !== false;
        var triggerLabel = { message_contains: 'contains', message_equals: 'equals', message_starts_with: 'starts with', message_regex: 'regex' }[r.trigger] || r.trigger;
        html += '<div class="auto-rule-card' + (enabled ? '' : ' auto-rule-disabled') + '">'
          + '<div class="auto-rule-header">'
          + '<div class="auto-rule-trigger"><span class="auto-kw">When</span> message <strong>' + escapeHtml(triggerLabel) + '</strong> "' + escapeHtml(r.condition || '') + '"</div>'
          + '<div class="auto-rule-controls">'
          + '<button class="auto-toggle-btn" onclick="window._autoToggle(' + r.id + ')" title="' + (enabled ? 'Disable' : 'Enable') + '">' + (enabled ? 'ON' : 'OFF') + '</button>'
          + '<button class="auto-delete-btn" onclick="window._autoDelete(' + r.id + ')" title="Delete">\u2715</button>'
          + '</div></div>'
          + '<div class="auto-rule-action"><span class="auto-kw">Do</span> ' + escapeHtml(r.action || '') + '</div>'
          + (r.description ? '<div class="auto-rule-desc">' + escapeHtml(r.description) + '</div>' : '')
          + '<div class="auto-rule-meta">Triggered ' + (r.hitCount || 0) + ' times</div>'
          + '</div>';
      });
      list.innerHTML = html;
    }).catch(function () {
      list.innerHTML = '<div class="tab-empty">Failed to load automations.</div>';
    });
  }

  window._autoToggle = function (id) {
    fetch('/api/automations/' + id + '/toggle', { method: 'POST' }).then(function () { loadAutomations(); });
  };

  window._autoDelete = function (id) {
    fetch('/api/automations/' + id, { method: 'DELETE' }).then(function () { loadAutomations(); });
  };

  var autoAddBtn = document.getElementById('auto-add-btn');
  if (autoAddBtn) autoAddBtn.addEventListener('click', function () {
    var trigger = document.getElementById('auto-trigger');
    var condition = document.getElementById('auto-condition');
    var action = document.getElementById('auto-action');
    var desc = document.getElementById('auto-desc');
    if (!action || !action.value.trim()) return;
    fetch('/api/automations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        trigger: trigger ? trigger.value : 'message_contains',
        condition: condition ? condition.value.trim() : '',
        action: action.value.trim(),
        description: desc ? desc.value.trim() : ''
      })
    }).then(function () {
      if (condition) condition.value = '';
      if (action) action.value = '';
      if (desc) desc.value = '';
      loadAutomations();
    });
  });

  // On startup: show clear chat with greeting (same as Clear button)
  messagesEl.innerHTML = '';
  appendMessage('Chat cleared. How can I help?', false);

  // ═══ Chat Search (Ctrl+F) ═══
  (function () {
    var searchEl = document.getElementById('chat-search');
    var searchInput = document.getElementById('chat-search-input');
    var searchCount = document.getElementById('chat-search-count');
    var searchPrev = document.getElementById('chat-search-prev');
    var searchNext = document.getElementById('chat-search-next');
    var searchClose = document.getElementById('chat-search-close');
    var matches = [];
    var currentIdx = -1;

    function openSearch() { searchEl.hidden = false; searchInput.value = ''; searchInput.focus(); clearHighlights(); }
    function closeSearch() { searchEl.hidden = true; clearHighlights(); }

    function clearHighlights() {
      matches = []; currentIdx = -1;
      document.querySelectorAll('.search-match, .search-current').forEach(function(el) {
        el.classList.remove('search-match', 'search-current');
      });
      searchCount.textContent = '';
    }

    function doSearch() {
      clearHighlights();
      var q = searchInput.value.trim().toLowerCase();
      if (!q) return;
      document.querySelectorAll('#messages .message-wrapper').forEach(function(w) {
        var msg = w.querySelector('.message');
        if (msg && msg.textContent.toLowerCase().indexOf(q) !== -1) {
          w.classList.add('search-match');
          matches.push(w);
        }
      });
      if (matches.length > 0) { currentIdx = 0; goToCurrent(); }
      updateCount();
    }

    function updateCount() {
      if (matches.length === 0) { searchCount.textContent = searchInput.value.trim() ? 'No results' : ''; }
      else { searchCount.textContent = (currentIdx + 1) + ' of ' + matches.length; }
    }

    function goToCurrent() {
      document.querySelectorAll('.search-current').forEach(function(el) { el.classList.remove('search-current'); });
      if (currentIdx >= 0 && currentIdx < matches.length) {
        matches[currentIdx].classList.add('search-current');
        matches[currentIdx].scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
      updateCount();
    }

    function nextMatch() { if (matches.length === 0) return; currentIdx = (currentIdx + 1) % matches.length; goToCurrent(); }
    function prevMatch() { if (matches.length === 0) return; currentIdx = (currentIdx - 1 + matches.length) % matches.length; goToCurrent(); }

    searchInput.addEventListener('input', doSearch);
    searchInput.addEventListener('keydown', function(e) {
      if (e.key === 'Enter') { e.preventDefault(); if (e.shiftKey) prevMatch(); else nextMatch(); }
      if (e.key === 'Escape') { e.preventDefault(); closeSearch(); }
    });
    searchPrev.addEventListener('click', prevMatch);
    searchNext.addEventListener('click', nextMatch);
    searchClose.addEventListener('click', closeSearch);

    // Ctrl+F handler — needs to be registered to intercept browser default
    document.addEventListener('keydown', function(e) {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        // Only intercept if chat tab is active
        var chatTab = document.getElementById('tab-chat');
        if (chatTab && chatTab.classList.contains('active')) {
          e.preventDefault();
          openSearch();
        }
      }
    }, true);
  })();

  // ═══ Sound Effects ═══
  (function () {
    var audioCtx = null;
    var soundEnabled = localStorage.getItem('minsbot-sound') !== 'off';
    var soundBtn = document.getElementById('sound-toggle');

    function getCtx() {
      if (!audioCtx) {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (audioCtx.state === 'suspended') audioCtx.resume();
      return audioCtx;
    }

    function playTone(freq, duration, type, volume, ramp) {
      if (!soundEnabled) return;
      try {
        var ctx = getCtx();
        var osc = ctx.createOscillator();
        var gain = ctx.createGain();
        osc.type = type || 'sine';
        osc.frequency.setValueAtTime(freq, ctx.currentTime);
        if (ramp) osc.frequency.linearRampToValueAtTime(ramp, ctx.currentTime + duration);
        gain.gain.setValueAtTime(volume || 0.08, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(ctx.currentTime);
        osc.stop(ctx.currentTime + duration);
      } catch (e) { /* ignore audio errors */ }
    }

    window._minsSound = {
      sent: function () { playTone(480, 0.12, 'sine', 0.06, 640); },
      received: function () { playTone(640, 0.15, 'sine', 0.06, 480); },
      notification: function () {
        playTone(660, 0.1, 'sine', 0.07);
        setTimeout(function () { playTone(880, 0.15, 'sine', 0.07); }, 120);
      },
      error: function () { playTone(200, 0.2, 'triangle', 0.05); }
    };

    function updateBtn() {
      if (soundBtn) {
        soundBtn.classList.toggle('muted', !soundEnabled);
        soundBtn.title = soundEnabled ? 'Sound effects on' : 'Sound effects off';
        var icon = soundBtn.querySelector('.sound-icon');
        if (icon) {
          icon.className = 'fa-solid sound-icon ' + (soundEnabled ? 'fa-volume-high' : 'fa-volume-xmark');
        }
      }
    }

    if (soundBtn) {
      soundBtn.addEventListener('click', function () {
        soundEnabled = !soundEnabled;
        localStorage.setItem('minsbot-sound', soundEnabled ? 'on' : 'off');
        updateBtn();
        // Also tell the backend to mute/unmute all TTS speech (proactive, autopilot,
        // health alerts, etc.) — when the speaker icon is off, the bot should stay silent.
        try {
          fetch('/api/tts/auto-speak', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: soundEnabled })
          }).catch(function () {});
        } catch (e) { /* ignore */ }
      });
    }
    // On load, take the BACKEND TTS state as the source of truth (it persists in
    // minsbot_config.txt across restarts). Don't overwrite it — just sync the UI
    // icon to match. Otherwise a page reload would silently re-enable TTS.
    try {
      fetch('/api/tts/config')
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          if (data && typeof data.autoSpeak === 'boolean') {
            soundEnabled = data.autoSpeak;
            localStorage.setItem('minsbot-sound', soundEnabled ? 'on' : 'off');
            updateBtn();
          }
        })
        .catch(function () {});
    } catch (e) { /* ignore */ }
    updateBtn();
  })();

  // ═══ Command Palette (Ctrl+K) ═══
  (function () {
    var paletteEl = document.getElementById('command-palette');
    var paletteInput = document.getElementById('cmd-palette-input');
    var paletteList = document.getElementById('cmd-palette-list');
    if (!paletteEl || !paletteInput || !paletteList) return;

    var commands = [
      { label: 'Go to Chat', action: function () { clickTab('chat'); } },
      { label: 'Go to Browser', action: function () { clickTab('browser'); } },
      { label: 'Go to Agents', action: function () { clickTab('agents'); } },
      { label: 'Go to Setup', action: function () { clickTab('setup'); } },
      { label: 'Go to Skills', action: function () { clickTab('skills'); } },
      { label: 'Go to Schedules', action: function () { clickTab('schedules'); } },
      { label: 'Go to Todo', action: function () { clickTab('todos'); } },
      { label: 'Go to Directives', action: function () { clickTab('directives'); } },
      { label: 'Go to Personality', action: function () { clickTab('personality'); } },
      { label: 'Go to Knowledge', action: function () { clickTab('knowledge'); } },
      { label: 'Go to Voice', action: function () { clickTab('voice'); } },
      { label: 'Go to Calibration', action: function () { clickTab('calibration'); } },
      { label: 'Go to Workflows', action: function () { clickTab('workflows'); } },
      { label: 'Go to Templates', action: function () { clickTab('templates'); } },
      { label: 'Go to Marketplace', action: function () { clickTab('marketplace'); } },
      { label: 'Go to Dashboard', action: function () { clickTab('dashboard'); } },
      { label: 'Go to Multi-Agent', action: function () { clickTab('multiagent'); } },
      { label: 'Go to Automations', action: function () { clickTab('automations'); } },
      { label: 'Go to Integrations', action: function () { clickTab('integrations'); } },
      { label: 'Clear Chat', shortcut: 'Ctrl+L', action: function () { if (clearBtn) clearBtn.click(); } },
      { label: 'Toggle Voice', action: function () { if (voiceBtn) voiceBtn.click(); } },
      { label: 'Toggle Watch Mode', action: function () { if (headerWatchEl) headerWatchEl.click(); } },
      { label: 'Toggle Keyboard Control', action: function () { if (headerControlEl) headerControlEl.click(); } },
      { label: 'Toggle Audio Listen', action: function () { if (headerListenEl) headerListenEl.click(); } },
      { label: 'Toggle Auto-pilot', action: function () { var ap = document.getElementById('header-autopilot'); if (ap) ap.click(); } },
      { label: 'Focus Input', action: function () { focusInput(); } },
      { label: 'Start Random Agent', action: function () { clickTab('agents'); setTimeout(function () { var rb = document.getElementById('agents-random-btn'); if (rb) rb.click(); }, 200); } }
    ];

    function clickTab(tabId) {
      var tab = document.querySelector('.tab[data-tab="' + tabId + '"]');
      if (tab) tab.click();
    }

    var activeIdx = 0;
    var filteredCmds = commands.slice();

    function render() {
      paletteList.innerHTML = '';
      if (filteredCmds.length === 0) {
        paletteList.innerHTML = '<div class="cmd-palette-empty">No matching commands</div>';
        return;
      }
      filteredCmds.forEach(function (cmd, i) {
        var div = document.createElement('div');
        div.className = 'cmd-palette-item' + (i === activeIdx ? ' active' : '');
        var span = document.createElement('span');
        span.textContent = cmd.label;
        div.appendChild(span);
        if (cmd.shortcut) {
          var sc = document.createElement('span');
          sc.className = 'cmd-shortcut';
          sc.textContent = cmd.shortcut;
          div.appendChild(sc);
        }
        div.addEventListener('click', function () { executeCommand(cmd); });
        div.addEventListener('mouseenter', function () {
          activeIdx = i;
          updateActive();
        });
        paletteList.appendChild(div);
      });
    }

    function updateActive() {
      var items = paletteList.querySelectorAll('.cmd-palette-item');
      items.forEach(function (el, i) {
        el.classList.toggle('active', i === activeIdx);
      });
      if (items[activeIdx]) items[activeIdx].scrollIntoView({ block: 'nearest' });
    }

    function executeCommand(cmd) {
      paletteEl.hidden = true;
      paletteInput.value = '';
      cmd.action();
    }

    function openPalette() {
      paletteEl.hidden = false;
      paletteInput.value = '';
      activeIdx = 0;
      filteredCmds = commands.slice();
      render();
      setTimeout(function () { paletteInput.focus(); }, 30);
    }

    function closePalette() {
      paletteEl.hidden = true;
      paletteInput.value = '';
    }

    // Expose toggle for the global keydown handler
    window.toggleCommandPalette = function () {
      if (paletteEl.hidden) openPalette();
      else closePalette();
    };

    paletteInput.addEventListener('input', function () {
      var q = paletteInput.value.toLowerCase().trim();
      filteredCmds = q ? commands.filter(function (c) { return c.label.toLowerCase().indexOf(q) !== -1; }) : commands.slice();
      activeIdx = 0;
      render();
    });

    paletteInput.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (filteredCmds.length) { activeIdx = (activeIdx + 1) % filteredCmds.length; updateActive(); }
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (filteredCmds.length) { activeIdx = (activeIdx - 1 + filteredCmds.length) % filteredCmds.length; updateActive(); }
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (filteredCmds[activeIdx]) executeCommand(filteredCmds[activeIdx]);
      }
    });

    // Close on backdrop click
    paletteEl.querySelector('.cmd-palette-backdrop').addEventListener('click', closePalette);
  })();

  // ═══ Keyboard Shortcuts Overlay (Ctrl+/) ═══
  (function () {
    var overlayEl = document.getElementById('shortcuts-overlay');
    if (!overlayEl) return;

    window.toggleShortcutsOverlay = function () {
      overlayEl.hidden = !overlayEl.hidden;
    };

    overlayEl.querySelector('.shortcuts-backdrop').addEventListener('click', function () {
      overlayEl.hidden = true;
    });
  })();
})();
