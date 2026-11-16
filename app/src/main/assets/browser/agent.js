/*
 * localchat page agent (architecture 7.3). Evaluated by browser.script.PageScript as
 *   (this function)(K, CFG)
 * once per document; it installs a non-enumerable window[K] whose call(fn, args) runs one function below.
 * Nothing is written to the DOM: element refs live in JS memory (refs/refOf), so a new document = stale refs.
 * The file must stay a single function expression (no trailing semicolon or call).
 */
(function (K, CFG) {
  'use strict';
  if (window[K]) return;

  var refs = [];               // ref "e<n>" -> refs[n - 1] = WeakRef(element)
  var refOf = new WeakMap();   // element -> "e<n>": an element keeps its ref across snapshots
  var pend = {};               // execute promises: id -> null (running) | {ok} | {error}
  var pendSeq = 0;
  var patched = 0;
  var sensRe = new RegExp(CFG.pattern, 'i');
  var inputValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  var textareaValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');

  // ---------- text helpers

  function norm(s) { return (s == null ? '' : String(s)).replace(/\s+/g, ' ').trim(); }
  function cut(s, n) { return s.length > n ? s.slice(0, n - 1) + '…' : s; }
  function q(s) { return JSON.stringify(s); }

  // ---------- roles and names

  var HEADINGS = { h1: 1, h2: 2, h3: 3, h4: 4, h5: 5, h6: 6 };
  var TAG_ROLES = {
    button: 'button', summary: 'button', textarea: 'textbox', nav: 'navigation', main: 'main',
    aside: 'complementary', form: 'form', dialog: 'dialog', table: 'table', tr: 'row', th: 'columnheader',
    td: 'cell', ul: 'list', ol: 'list', li: 'listitem', iframe: 'iframe', frame: 'iframe', header: 'banner',
    footer: 'contentinfo', option: 'option', progress: 'progressbar', meter: 'meter', menu: 'list'
  };
  var INPUT_ROLES = {
    button: 'button', submit: 'button', reset: 'button', image: 'button', file: 'button', checkbox: 'checkbox',
    radio: 'radio', range: 'slider', number: 'spinbutton', search: 'searchbox'
  };
  var INTERACTIVE = {
    link: 1, button: 1, checkbox: 1, radio: 1, textbox: 1, searchbox: 1, combobox: 1, listbox: 1, slider: 1,
    spinbutton: 1, 'switch': 1, tab: 1, menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, option: 1, treeitem: 1
  };
  // Roles whose accessible name comes from their content; their own text is not repeated as child lines.
  var FROM_CONTENT = {
    link: 1, button: 1, heading: 1, cell: 1, columnheader: 1, rowheader: 1, option: 1, tab: 1, menuitem: 1,
    menuitemcheckbox: 1, menuitemradio: 1, checkbox: 1, radio: 1, 'switch': 1, treeitem: 1, tooltip: 1
  };
  var SKIP_TAGS = { script: 1, style: 1, template: 1, noscript: 1, head: 1, meta: 1, link: 1, title: 1 };
  var ROLE_SEL = 'a[href],button,input,select,textarea,h1,h2,h3,h4,h5,h6,img,iframe,frame,table,ul,ol,li,nav,' +
    'main,form,dialog,summary,aside,header,footer,slot,[role],[contenteditable],[tabindex],[aria-label]';

  function implicitRole(el) {
    var t = el.localName;
    if (HEADINGS[t]) return 'heading';
    if (t === 'a' || t === 'area') return el.hasAttribute('href') ? 'link' : null;
    if (t === 'input') {
      var ty = (el.getAttribute('type') || 'text').toLowerCase();
      if (ty === 'hidden') return null;
      return INPUT_ROLES[ty] || 'textbox';
    }
    if (t === 'select') return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
    if (t === 'img') return el.getAttribute('alt') === '' ? null : 'img';
    if (TAG_ROLES[t]) return TAG_ROLES[t];
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
    return null;
  }

  function roleOf(el) {
    var r = el.getAttribute('role');
    if (r && r.trim()) {
      r = r.trim().split(/\s+/)[0].toLowerCase();
      return (r === 'presentation' || r === 'none' || r === 'generic') ? null : r;
    }
    return implicitRole(el);
  }

  function nameOf(el, role) {
    var s = el.getAttribute('aria-label');
    if (norm(s)) return cut(norm(s), 100);
    var ids = el.getAttribute('aria-labelledby');
    if (ids) {
      var doc = el.ownerDocument;
      s = ids.split(/\s+/).map(function (id) { var n = doc.getElementById(id); return n ? n.textContent : ''; }).join(' ');
      if (norm(s)) return cut(norm(s), 100);
    }
    var t = el.localName;
    if (t === 'input') {
      var ty = (el.getAttribute('type') || '').toLowerCase();
      if (ty === 'submit' || ty === 'reset' || ty === 'button') {
        return cut(norm(el.value || (ty === 'submit' ? 'Submit' : ty === 'reset' ? 'Reset' : '')), 100);
      }
      if (ty === 'image') return cut(norm(el.alt || el.value || 'Submit'), 100);
    }
    if ((t === 'input' || t === 'textarea' || t === 'select' || t === 'meter' || t === 'progress') && el.labels && el.labels.length) {
      s = Array.prototype.map.call(el.labels, function (l) { return l.innerText; }).join(' ');
      if (norm(s)) return cut(norm(s), 100);
    }
    if ((t === 'img' || t === 'area') && norm(el.getAttribute('alt'))) return cut(norm(el.getAttribute('alt')), 100);
    if (norm(el.getAttribute('title'))) return cut(norm(el.getAttribute('title')), 100);
    if (norm(el.getAttribute('placeholder'))) return cut(norm(el.getAttribute('placeholder')), 100);
    if (FROM_CONTENT[role] || t === 'summary') {
      s = norm(el.innerText);
      if (s) return cut(s, 100);
      var img = el.querySelector('img[alt]');
      if (img && norm(img.alt)) return cut(norm(img.alt), 100);
    }
    return '';
  }

  // ---------- B-S1 sensitive fields (rule from browser.safety.SensitiveFields via CFG)

  function isField(el) {
    return el.localName === 'input' || el.localName === 'textarea' || el.isContentEditable;
  }

  function isSensitive(el) {
    if (!isField(el)) return false;
    if ((el.getAttribute('type') || '').toLowerCase() === 'password') return true;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase().split(/\s+/);
    for (var i = 0; i < ac.length; i++) {
      if (CFG.autocomplete.indexOf(ac[i]) >= 0 || (ac[i] && ac[i].indexOf(CFG.prefix) === 0)) return true;
    }
    var c = [el.getAttribute('name'), el.id, nameOf(el, roleOf(el)), el.getAttribute('placeholder')];
    for (i = 0; i < c.length; i++) if (c[i] && sensRe.test(c[i])) return true;
    return false;
  }

  var NOT_TEXT = { checkbox: 1, radio: 1, button: 1, submit: 1, reset: 1, image: 1, file: 1, range: 1, color: 1, hidden: 1 };
  function isEditable(el) {
    if (el.localName === 'textarea') return true;
    if (el.localName === 'input') return !NOT_TEXT[(el.getAttribute('type') || 'text').toLowerCase()];
    return el.isContentEditable;
  }

  // ---------- visibility and geometry

  function styleOf(el) { return el.ownerDocument.defaultView.getComputedStyle(el); }

  /** Subtree is never rendered (or hidden from assistive tech): skip it and its children. */
  function skipSubtree(el) {
    if (SKIP_TAGS[el.localName]) return true;
    if (el.getAttribute('aria-hidden') === 'true') return true;
    if (el.localName === 'input' && (el.getAttribute('type') || '').toLowerCase() === 'hidden') return true;
    return styleOf(el).display === 'none';
  }

  function visibleBox(el) {
    var cs = styleOf(el);
    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function visible(el) {
    if (el.checkVisibility && !el.checkVisibility({ visibilityProperty: true })) return false;
    return visibleBox(el);
  }

  /** Rect in top-level viewport CSS px (adds same-origin iframe offsets). */
  function rectInTop(el) {
    var r = el.getBoundingClientRect();
    var x = r.left, y = r.top;
    var w = el.ownerDocument.defaultView;
    while (w && w !== window && w.frameElement) {
      var fe = w.frameElement, fr = fe.getBoundingClientRect();
      x += fr.left + fe.clientLeft;
      y += fr.top + fe.clientTop;
      w = w.parent;
    }
    return { left: x, top: y, width: r.width, height: r.height };
  }

  function deepHit(x, y) {
    var el = document.elementFromPoint(x, y), ox = 0, oy = 0, guard = 0;
    while (el && guard++ < 20) {
      if (el.shadowRoot) {
        var s = el.shadowRoot.elementFromPoint(x - ox, y - oy);
        if (s && s !== el) { el = s; continue; }
      }
      if ((el.localName === 'iframe' || el.localName === 'frame') && el.contentDocument) {
        var fr = el.getBoundingClientRect();
        ox += fr.left + el.clientLeft;
        oy += fr.top + el.clientTop;
        var f = el.contentDocument.elementFromPoint(x - ox, y - oy);
        if (f) { el = f; continue; }
      }
      break;
    }
    return el;
  }

  /** ancestor contains node, crossing shadow roots and same-origin frames. */
  function within(ancestor, node) {
    while (node) {
      if (node === ancestor) return true;
      node = node.parentNode || node.host || (node.defaultView && node.defaultView.frameElement) || null;
    }
    return false;
  }

  // ---------- refs and target resolution

  function refFor(el) {
    var r = refOf.get(el);
    if (!r) {
      refs.push(new WeakRef(el));
      r = 'e' + refs.length;
      refOf.set(el, r);
    }
    return r;
  }

  function deepQuery(root, sel) {
    var found = root.querySelector(sel);
    if (found) return found;
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var sub = all[i].shadowRoot;
      if (!sub && (all[i].localName === 'iframe' || all[i].localName === 'frame')) sub = all[i].contentDocument;
      if (sub) { found = deepQuery(sub, sel); if (found) return found; }
    }
    return null;
  }

  var REF_RE = /^\s*\[?\s*(?:ref\s*=\s*)?e(\d+)\s*\]?\s*$/;

  function resolve(target) {
    if (!target) throw new Error('missing target: pass a ref from snapshot (e.g. e12) or a CSS selector');
    var m = REF_RE.exec(target);
    if (m) {
      var w = refs[+m[1] - 1];
      var el = w && w.deref();
      if (!el || !el.isConnected) throw new Error('stale ref ' + target.trim() + ': take a new snapshot');
      return el;
    }
    var found = deepQuery(document, target);
    if (!found) throw new Error('no element matches selector ' + target);
    return found;
  }

  function info(el) {
    var form = el.form || (el.closest && el.closest('form'));
    var role = roleOf(el);
    return {
      tag: el.localName,
      role: role,
      type: (el.getAttribute('type') || '').toLowerCase() || null,
      name: nameOf(el, role) || null,
      nameAttr: el.getAttribute('name'),
      id: el.id || null,
      autocomplete: (el.getAttribute('autocomplete') || '').toLowerCase() || null,
      placeholder: el.getAttribute('placeholder'),
      formMethod: form ? (el.getAttribute('formmethod') || form.getAttribute('method') || 'get').toLowerCase() : null,
      formAction: form ? (el.getAttribute('formaction') || form.getAttribute('action') || '') : null,
      href: el.getAttribute('href')
    };
  }

  function locate(el, scroll) {
    if (scroll) el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    var r = rectInTop(el);
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var inView = r.width > 0 && r.height > 0 && cx >= 0 && cy >= 0 && cx < innerWidth && cy < innerHeight;
    var hitEl = inView ? deepHit(cx, cy) : null;
    var hit = !!hitEl && (within(el, hitEl) ||
      (el.labels ? Array.prototype.some.call(el.labels, function (l) { return within(l, hitEl); }) : false));
    return {
      info: info(el), rect: r, innerWidth: innerWidth, cx: cx, cy: cy, hit: hit,
      sensitive: isSensitive(el), editable: isEditable(el), disabled: !!(el.disabled || el.getAttribute('aria-disabled') === 'true')
    };
  }

  // ---------- snapshot

  function textLeaf(el) {
    if (el.shadowRoot) return false;
    var all = el.getElementsByTagName('*');
    if (all.length > 50) return false;
    if (el.querySelector(ROLE_SEL)) return false;
    for (var i = 0; i < all.length; i++) if (all[i].shadowRoot) return false;
    return true;
  }

  function describeLine(el, role, name) {
    var s = role;
    if (name) s += ' ' + q(name);
    if (role === 'heading') s += ' [level=' + (el.getAttribute('aria-level') || HEADINGS[el.localName] || 2) + ']';
    var checked = (el.localName === 'input' && (el.type === 'checkbox' || el.type === 'radio')) ? el.checked
      : el.getAttribute('aria-checked') === 'true';
    if (checked) s += ' [checked]';
    if (el.getAttribute('aria-checked') === 'mixed' || el.indeterminate) s += ' [mixed]';
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') s += ' [disabled]';
    var ex = el.getAttribute('aria-expanded');
    if (ex === 'true' || ex === 'false') s += ' [expanded=' + ex + ']';
    if (el.getAttribute('aria-selected') === 'true') s += ' [selected]';
    if (el.getAttribute('aria-pressed') === 'true') s += ' [pressed]';
    var tabindex = el.getAttribute('tabindex');
    if (INTERACTIVE[role] || (tabindex !== null && +tabindex >= 0) || el.hasAttribute('onclick')) s += ' [ref=' + refFor(el) + ']';
    if (isField(el) && isSensitive(el)) {
      s += ' [sensitive]';
      if ((el.isContentEditable ? el.textContent : el.value)) s += ' value=••••';
    } else if (el.localName === 'select') {
      var opts = Array.prototype.slice.call(el.options);
      var sel = opts.filter(function (o) { return o.selected; }).map(function (o) { return norm(o.text); });
      if (sel.length) s += ' value=' + q(cut(sel.join(', '), 100));
      var names = opts.slice(0, 10).map(function (o) { return cut(norm(o.text), 40); });
      s += ' options=[' + names.join(', ') + (opts.length > 10 ? ', …' + (opts.length - 10) + ' more' : '') + ']';
    } else if (el.localName === 'textarea' || (el.localName === 'input' && isEditable(el)) || role === 'slider' || role === 'spinbutton') {
      if (el.value) s += ' value=' + q(cut(el.value, 100));
    } else if (el.isContentEditable && role === 'textbox') {
      var v = norm(el.innerText);
      if (v) s += ' value=' + q(cut(v, 100));
    }
    if (role === 'link') {
      var href = el.getAttribute('href') || '';
      if (href && !/^javascript:/i.test(href)) s += ' -> ' + cut(href, 150);
    }
    return s;
  }

  function snapshot(a) {
    var maxNodes = a.maxNodes || 500, maxChars = a.maxChars || 20000;
    var out = ['page: ' + norm(document.title) + ' — ' + location.href];
    var count = 0, chars = 0, truncated = false;

    function emit(depth, line) {
      if (truncated) return;
      var s = new Array(depth + 1).join('  ') + '- ' + line;
      if (count >= maxNodes || chars + s.length > maxChars) { truncated = true; return; }
      out.push(s); count++; chars += s.length + 1;
    }

    function children(parent, depth, skipText) {
      var kids = parent.localName === 'slot' ? parent.assignedNodes({ flatten: true }) : parent.childNodes;
      for (var i = 0; i < kids.length && !truncated; i++) {
        var n = kids[i];
        if (n.nodeType === 3) {
          if (!skipText) { var t = norm(n.data); if (t) emit(depth, 'text: ' + q(cut(t, 200))); }
        } else if (n.nodeType === 1) {
          element(n, depth);
        }
      }
    }

    function frame(el, depth) {
      if (!visibleBox(el)) return;
      var title = norm(el.getAttribute('title') || el.getAttribute('name'));
      var doc = null;
      try { doc = el.contentDocument; } catch (e) { doc = null; }
      if (!doc || !doc.documentElement) {
        emit(depth, 'iframe' + (title ? ' ' + q(title) : '') + ' (cross-origin, not accessible)');
        return;
      }
      emit(depth, 'iframe' + (title ? ' ' + q(title) : ''));
      children(doc.body || doc.documentElement, depth + 1, false);
    }

    function element(el, depth) {
      if (skipSubtree(el)) return;
      if (el.localName === 'iframe' || el.localName === 'frame') return frame(el, depth);
      if (el.localName === 'option' && el.closest('select')) return;
      if (el.localName === 'slot') return children(el, depth, false); // no box of its own; walk assigned nodes
      var role = roleOf(el);
      var inner = el.shadowRoot || el;
      var field = isField(el) || el.localName === 'select';
      if (!role) {
        if (!textLeaf(el)) return children(inner, depth, false);
        var t = norm(el.innerText);
        if (t && visibleBox(el)) emit(depth, 'text: ' + q(cut(t, 200)));
        return;
      }
      if (!visibleBox(el)) return field ? undefined : children(inner, depth, false);
      var name = nameOf(el, role);
      var line = describeLine(el, role, name);
      if (!FROM_CONTENT[role] && !field && role !== 'img' && textLeaf(el)) {
        var lt = norm(el.innerText);
        if (lt && lt !== name) line += ': ' + q(cut(lt, 200));
        return emit(depth, line);
      }
      emit(depth, line);
      if (el.localName === 'select' || el.localName === 'textarea') return;
      children(inner, depth + 1, !!FROM_CONTENT[role]);
    }

    children(document.body || document.documentElement, 0, false);
    if (truncated) {
      out.push('[snapshot truncated at ' + count + ' nodes / ' + chars + ' chars: use page_text for content, ' +
        'or scroll and snapshot again]');
    }
    return { text: out.join('\n'), nodes: count, truncated: truncated };
  }

  // ---------- page text

  function pageText(a) {
    var body = document.body || document.documentElement;
    var root = document.querySelector('main, [role=main], article') || body;
    var clean = function (s) { return (s || '').replace(/[ \t ]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim(); };
    var t = clean(root.innerText);
    if (root !== body && t.length < 200) t = clean(body.innerText);
    var size = a.size || 20000;
    var pages = Math.max(1, Math.ceil(t.length / size));
    var p = Math.min(Math.max(1, a.page || 1), pages);
    return { title: norm(document.title), url: location.href, page: p, pages: pages, text: t.slice((p - 1) * size, p * size) };
  }

  // ---------- actions

  function events(el, names) {
    names.forEach(function (n) { el.dispatchEvent(new Event(n, { bubbles: true, composed: true })); });
  }

  function typeText(a) {
    var el = resolve(a.target);
    if (isSensitive(el)) throw new Error('refused: password, OTP and card fields are filled by the user only (use request_handoff)');
    if (!isEditable(el)) throw new Error('element is not a text field (' + el.localName + (roleOf(el) ? ', ' + roleOf(el) : '') + ')');
    if (el.disabled || el.readOnly) throw new Error('text field is disabled or read-only');
    el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    el.focus();
    var clear = a.clear !== false;
    if (el.isContentEditable) {
      var doc = el.ownerDocument;
      if (clear) doc.execCommand('selectAll', false, null);
      if (!doc.execCommand('insertText', false, a.text)) el.textContent = (clear ? '' : el.textContent) + a.text;
      return { value: cut(norm(el.innerText), 200) };
    }
    var desc = el.localName === 'textarea' ? textareaValue : inputValue;
    desc.set.call(el, clear ? a.text : el.value + a.text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: a.text }));
    events(el, ['change']);
    return { value: cut(el.value, 200) };
  }

  function click(a) {
    var el = resolve(a.target);
    if (el.focus) el.focus();
    el.click();
    return {};
  }

  function scroll(a) {
    var el = a.target ? resolve(a.target) : null;
    var se = document.scrollingElement || document.documentElement;
    if (el && !a.dx && !a.dy) el.scrollIntoView({ block: 'center', behavior: 'instant' });
    else (el || se).scrollBy({ left: a.dx || 0, top: a.dy || 0, behavior: 'instant' });
    var res = { x: Math.round(scrollX), y: Math.round(scrollY), maxY: Math.max(0, Math.round(se.scrollHeight - innerHeight)) };
    if (el) { res.elementY = Math.round(el.scrollTop); res.elementMaxY = Math.max(0, el.scrollHeight - el.clientHeight); }
    return res;
  }

  function hover(a) {
    var el = resolve(a.target);
    el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    var r = el.getBoundingClientRect();
    var o = { bubbles: true, cancelable: true, composed: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, view: el.ownerDocument.defaultView };
    ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'pointermove', 'mousemove'].forEach(function (t) {
      el.dispatchEvent(t.indexOf('pointer') === 0 ? new PointerEvent(t, o) : new MouseEvent(t, o));
    });
    return {};
  }

  function selectOption(a) {
    var el = resolve(a.target);
    if (el.localName !== 'select') throw new Error('element is not a <select>; for custom dropdowns click the option instead');
    var opts = Array.prototype.slice.call(el.options);
    var want = a.values.map(function (w) { return norm(w).toLowerCase(); });
    var picked = opts.filter(function (o) { return want.indexOf(o.value.toLowerCase()) >= 0 || want.indexOf(norm(o.text).toLowerCase()) >= 0; });
    if (!picked.length) {
      throw new Error('no option matches ' + q(a.values) + '. Options: ' + opts.slice(0, 30).map(function (o) { return norm(o.text); }).join(', '));
    }
    if (!el.multiple) picked = picked.slice(0, 1);
    opts.forEach(function (o) { o.selected = picked.indexOf(o) >= 0; });
    events(el, ['input', 'change']);
    return { selected: picked.map(function (o) { return norm(o.text); }) };
  }

  function query(a) {
    var el;
    try { el = resolve(a.target); } catch (e) {
      if (/^stale ref/.test(e.message)) return { attached: false, visible: false, stale: true };
      if (/^no element/.test(e.message)) return { attached: false, visible: false };
      throw e;
    }
    return { attached: true, visible: visible(el) };
  }

  function focusInfo() {
    var el = document.activeElement, guard = 0;
    while (el && guard++ < 20) {
      if (el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
      else if ((el.localName === 'iframe' || el.localName === 'frame') && el.contentDocument && el.contentDocument.activeElement) el = el.contentDocument.activeElement;
      else break;
    }
    if (!el || el.localName === 'body' || el.localName === 'html') return { info: null };
    return { info: info(el) };
  }

  function state() {
    return { url: location.href, title: norm(document.title), readyState: document.readyState };
  }

  // ---------- execute (architecture 7.4): sensitive value setter refused while user code runs

  function execBegin() {
    if (patched++ > 0) return {};
    Object.defineProperty(HTMLInputElement.prototype, 'value', {
      configurable: true, enumerable: inputValue.enumerable, get: inputValue.get,
      set: function (v) {
        if (isSensitive(this)) throw new Error('refused: execute may not set password, OTP or card fields');
        return inputValue.set.call(this, v);
      }
    });
    return {};
  }

  function execEnd(a) {
    if (a && a.force) patched = 1;
    if (patched === 0 || --patched > 0) return {};
    Object.defineProperty(HTMLInputElement.prototype, 'value', inputValue);
    return {};
  }

  function ser(v) {
    if (v === undefined) return 'undefined';
    var seen = new Set();
    try {
      var s = JSON.stringify(v, function (k, x) {
        if (typeof x === 'function') return '[function]';
        if (typeof x === 'bigint') return String(x);
        if (x && typeof x === 'object') {
          if (x.nodeType === 1) return cut(x.outerHTML, 2000);
          if (x.nodeType) return cut(norm(x.textContent), 2000);
          if (x === window || x.window === x) return '[window]';
          if (seen.has(x)) return '[circular]';
          seen.add(x);
          if (x instanceof Map) return Array.from(x.entries());
          if (x instanceof Set || (typeof x.length === 'number' && typeof x.item === 'function')) return Array.from(x);
        }
        return x;
      });
      return s === undefined ? String(v) : s;
    } catch (e) {
      return String(v);
    }
  }

  function execResult(a) {
    var r = a.value;
    if (r && typeof r.then === 'function') {
      var id = ++pendSeq;
      pend[id] = null;
      Promise.resolve(r).then(
        function (v) { pend[id] = { ok: ser(v) }; },
        function (e) { pend[id] = { error: String((e && e.message) || e) }; }
      ).then(function () { execEnd(); });
      return { pending: id };
    }
    execEnd();
    return { ok: ser(r) };
  }

  function execPoll(a) {
    var p = pend[a.id];
    if (p === undefined) return { error: 'unknown execution' };
    if (p === null) return { pending: a.id };
    delete pend[a.id];
    return p;
  }

  var FNS = {
    snapshot: snapshot, pageText: pageText, locate: function (a) { return locate(resolve(a.target), a.scroll !== false); },
    click: click, type: typeText, scroll: scroll, hover: hover, select: selectOption, query: query,
    focusInfo: focusInfo, state: state, execBegin: execBegin, execEnd: execEnd, execResult: execResult, execPoll: execPoll
  };

  var api = {
    call: function (fn, args) {
      var f = FNS[fn];
      if (!f) throw new Error('unknown page function ' + fn);
      return f(args || {});
    }
  };
  Object.defineProperty(window, K, { value: api, enumerable: false, configurable: false, writable: false });
})
