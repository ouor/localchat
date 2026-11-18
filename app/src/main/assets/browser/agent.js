/*
 * localchat page agent (architecture 7.3). Evaluated by browser.script.PageScript as
 *   (this function)(K, CFG)
 * once per document; it installs a non-enumerable window[K] whose call(fn, args) runs one function below.
 * Nothing is written to the DOM: element refs live in JS memory (refs/refOf), so a new document = stale refs. A ref
 * is "{tab}e{n}" (CFG.tab, browser-layers S4); n starts at CFG.refBase, which the app raises past every ref the tab
 * handed out, and every call passes the tab's next number again (a document back from the back/forward cache), so a
 * ref never repeats in the app.
 * The file must stay a single function expression (no trailing semicolon or call).
 */
(function (K, CFG) {
  'use strict';
  if (window[K]) return;

  var base = CFG.refBase || 1; // first ref number of this document
  var nextNum = base;          // number of the next new ref
  var tabNo = CFG.tab || 0;    // the tab's number, the first part of every ref
  var refs = new Map();        // n -> WeakRef(element) for ref "<tab>e<n>"
  var refOf = new WeakMap();   // element -> "<tab>e<n>": an element keeps its ref across snapshots
  var pend = {};               // execute promises: id -> null (running) | {ok} | {error}
  var pendSeq = 0;
  var inputValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  var textareaValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
  var botRe = new RegExp(CFG.bot, 'i');

  // ---------- readiness (tools-redesign 2-1): installed with the agent, so the first check of a document is real
  var docId = Math.random().toString(36).slice(2);
  var changes = 0, lastChange = 0, resCount = -1;
  try { performance.setResourceTimingBufferSize(1000); } catch (e) { /* older engines */ }
  function resources() {
    var es = performance.getEntriesByType('resource'), end = 0;
    for (var i = 0; i < es.length; i++) if (es[i].responseEnd > end) end = es[i].responseEnd;
    return { n: es.length, end: end };
  }
  (function () {
    var nav = performance.getEntriesByType('navigation')[0];
    lastChange = Math.max(nav ? (nav.loadEventEnd || nav.domContentLoadedEventEnd || nav.responseEnd) : 0, resources().end);
    // Attribute-only changes (animations, focus rings) do not count as the page changing.
    new MutationObserver(function (recs) { changes += recs.length; lastChange = performance.now(); })
      .observe(document, { subtree: true, childList: true, characterData: true });
  })();

  function mark() {
    var r = resources();
    if (r.n !== resCount) { resCount = r.n; if (r.end > lastChange) lastChange = r.end; }
    return {
      doc: docId, n: changes, quietMs: Math.max(0, Math.round(performance.now() - lastChange)),
      textLen: textLen(), readyState: document.readyState, url: location.href, title: norm(document.title)
    };
  }

  // Whether the page has SPARSE characters of text, without layout (no innerText): text nodes and open shadow
  // roots, scripts and styles left out. A document that once had enough is not measured again.
  var SPARSE = 200, enoughText = false;
  function textLen() {
    if (enoughText) return SPARSE;
    var n = countText(document.body, 0);
    if (n >= SPARSE) enoughText = true;
    return n;
  }
  function countText(root, n) {
    if (!root) return n;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (x) { return x.nodeType === 1 && SKIP_TAGS[x.localName] ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }
    });
    for (var x = w.nextNode(); x && n < SPARSE; x = w.nextNode()) {
      if (x.nodeType === 3) n += x.data.trim().length;
      else if (x.shadowRoot) n = countText(x.shadowRoot, n);
    }
    return n;
  }

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
    if (INTERACTIVE[role] || t === 'summary') {
      // An icon control: the label of its icon (HN's upvote arrow is a div with title="upvote").
      var d = el.querySelector('[aria-label],[title],img[alt],svg title');
      s = d && (d.localName === 'title' ? d.textContent : d.getAttribute('aria-label') || d.getAttribute('title') || d.getAttribute('alt'));
      if (norm(s)) return cut(norm(s), 100);
      if (role === 'link') return hrefName(el);
    }
    return '';
  }

  /** The last part of a link's path and its query ("vote?id=1&how=up"): what the link does, not a guess. */
  function hrefName(el) {
    try {
      var u = new URL(el.href);
      if (!/^https?:$/.test(u.protocol)) return '';
      var segs = u.pathname.split('/').filter(Boolean);
      return cut(decodeURIComponent(segs.length ? segs[segs.length - 1] : u.hostname) + u.search, 60);
    } catch (e) { return ''; }
  }

  // ---------- fields

  function isField(el) {
    return el.localName === 'input' || el.localName === 'textarea' || el.isContentEditable;
  }

  var SECRET_AC = { 'current-password': 1, 'new-password': 1, 'one-time-code': 1 };
  /** A password, one-time code or card field: a fact the list marks [secret]; whether to hide it is a policy's call. */
  function isSecret(el) {
    if (!isField(el)) return false;
    if ((el.getAttribute('type') || '').toLowerCase() === 'password') return true;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase().split(/\s+/);
    for (var i = 0; i < ac.length; i++) if (SECRET_AC[ac[i]] || ac[i].indexOf('cc-') === 0) return true;
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
      refs.set(nextNum, new WeakRef(el));
      r = tabNo + 'e' + nextNum++;
      refOf.set(el, r);
    }
    return r;
  }

  // "4e12", "@4e12", "#4e12", "[ref=4e12]"
  var REF_RE = /^\s*\[?\s*(?:ref\s*=\s*)?[@#]?(\d+)e(\d+)\s*\]?\s*$/i;

  /** The element of a ref; anything else is refused with a "REF:" message (the app then shows the current list). */
  function resolve(target) {
    var m = REF_RE.exec(target == null ? '' : String(target));
    if (!m) throw new Error('REF:' + q(cut(String(target == null ? '' : target), 40)) + ' is not a ref. Pass a ref from the element list, e.g. ' + tabNo + 'e12.');
    var t = +m[1], n = +m[2], name = t + 'e' + n;
    if (t !== tabNo) throw new Error('REF:Ref ' + name + ' is from tab ' + t + ', not tab ' + tabNo + '.');
    if (n < base) throw new Error('REF:Ref ' + name + ' is from an earlier page of this tab, not this one.');
    var w = refs.get(n), el = w && w.deref();
    if (!w) throw new Error('REF:Ref ' + name + ' is not on this page.');
    if (!el || !el.isConnected) throw new Error('REF:Ref ' + name + ' is gone; the page changed.');
    return el;
  }

  // ---------- search boxes (type presses Enter in them by itself; the list shows them as searchbox)

  var SEARCH_NAMES = { q: 1, query: 1, search: 1, keyword: 1, keywords: 1, s: 1, search_query: 1, wd: 1 };
  var SEARCH_WORD = /search|검색/i;

  /** A one-line search field. A combobox is left out: Enter may pick one of its suggestions. */
  function isSearchBox(el) {
    if (el.localName !== 'input' || !isEditable(el)) return false;
    var role = (el.getAttribute('role') || '').trim().toLowerCase();
    if (role === 'combobox') return false;
    if ((el.getAttribute('type') || '').toLowerCase() === 'search' || role === 'searchbox') return true;
    if (el.closest('[role=search]')) return true;
    if (SEARCH_NAMES[(el.getAttribute('name') || '').toLowerCase()]) return true;
    return SEARCH_WORD.test(nameOf(el, 'textbox')) || SEARCH_WORD.test(el.getAttribute('placeholder') || '');
  }

  function roleShown(el) {
    var r = roleOf(el);
    return r === 'textbox' && isSearchBox(el) ? 'searchbox' : r;
  }

  /**
   * Suggestions of the field a.target (G-m2), which type compares before and after typing: `linked` = the field's own
   * signals (aria-expanded, a visible list its aria-controls / aria-owns names, a <datalist> option for its value),
   * `near` = visible ARIA options and listboxes just around and below it. Lists elsewhere on the page do not count.
   */
  function suggestions(a) {
    var el = resolve(a.target), linked = el.getAttribute('aria-expanded') === 'true';
    var ids = ((el.getAttribute('aria-controls') || '') + ' ' + (el.getAttribute('aria-owns') || '')).split(/\s+/);
    ids.forEach(function (id) {
      var t = id && el.ownerDocument.getElementById(id);
      if (t && visibleBox(t) && t.querySelector('[role=option], li, option')) linked = true;
    });
    var v = (el.value || '').toLowerCase(), list = el.list;
    if (list && v) {
      for (var i = 0; i < list.options.length && !linked; i++) if (String(list.options[i].value).toLowerCase().indexOf(v) >= 0) linked = true;
    }
    var r = el.getBoundingClientRect(), near = 0, all = document.querySelectorAll('[role=option], [role=listbox]');
    for (var j = 0; j < all.length; j++) {
      if (!visibleBox(all[j])) continue;
      var b = all[j].getBoundingClientRect();
      if (b.top >= r.top - 40 && b.top <= r.bottom + 400 && b.left < r.right + 200 && b.right > r.left - 200) near++;
    }
    return { linked: linked, near: near };
  }

  function info(el) {
    var form = el.form || (el.closest && el.closest('form'));
    var role = roleShown(el);
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
      href: el.getAttribute('href'),
      search: isSearchBox(el)
    };
  }

  /** Label of the button that submits el's form (a money or account check reads it before a click or Enter). */
  function submitLabel(el) {
    var form = el.form || (el.closest && el.closest('form'));
    var sub = form && form.querySelector('button:not([type]), button[type=submit], input[type=submit], input[type=image]');
    return sub ? (nameOf(sub, 'button') || null) : null;
  }

  /** Where the element's page comes from when it is not this document: an iframe's src, a frame document's URL. */
  function frameOf(el) {
    if (el.localName === 'iframe' || el.localName === 'frame') return el.src || null;
    return el.ownerDocument !== document ? el.ownerDocument.location.href : null;
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
      editable: isEditable(el), disabled: !!(el.disabled || el.getAttribute('aria-disabled') === 'true'),
      formSubmitLabel: submitLabel(el), frame: frameOf(el)
    };
  }

  // ---------- element list (open, look and action results)

  function textLeaf(el) {
    if (el.shadowRoot) return false;
    var all = el.getElementsByTagName('*');
    if (all.length > 50) return false;
    if (el.querySelector(ROLE_SEL)) return false;
    for (var i = 0; i < all.length; i++) if (all[i].shadowRoot) return false;
    // Text longer than one line held in blocks (a feed's posts, an article's paragraphs): each block gets its line,
    // or one cut line would hide all but the first few.
    if (all.length && norm(el.innerText).length > 100) {
      for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
        var d = styleOf(c).display;
        if (d && d.indexOf('inline') !== 0 && d !== 'contents' && d !== 'none') return false;
      }
    }
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
    var tabindex = el.getAttribute('tabindex'), box = scrollBox(el);
    if (box) s += ' [scrollable]';
    if (isSecret(el)) s += ' [secret]';
    if (box || INTERACTIVE[role] || (tabindex !== null && +tabindex >= 0) || el.hasAttribute('onclick')) s += ' [ref=' + refFor(el) + ']';
    if (el.localName === 'select') {
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
      // Absolute, or a path on this site, so open() and read() can take it as it is (G-m5).
      var href = typeof el.href === 'string' ? el.href : (el.getAttribute('href') || '');
      if (href && !/^javascript:/i.test(href)) {
        try { var u = new URL(href); if (u.origin === location.origin) href = u.pathname + u.search + u.hash; } catch (e) { /* as it is */ }
        s += ' -> ' + cut(href, 100);
      }
    }
    return s;
  }

  /** An element that scrolls inside the page (a chat pane, a map's result list); the document's own scroller is not one. */
  function scrollBox(el) {
    if (el.scrollHeight <= el.clientHeight + 20) return false;
    var se = document.scrollingElement || document.documentElement;
    if (el === se || el === document.body || el === document.documentElement) return false;
    var o = styleOf(el).overflowY;
    return o === 'auto' || o === 'scroll' || o === 'overlay';
  }

  /**
   * Lines of the page's elements within budget characters. When everything does not fit, interactive elements and
   * headings on screen come first, then those off screen (nearest first), then text on screen, then other text; with
   * a.screen (after a scroll) every line on screen comes first, text included (S12). The chosen lines are printed in
   * document order.
   */

  function pageList(a) {
    var budget = a.budget || 16000, vh = innerHeight;
    var items = [];
    // A safety bound for huge pages only: every element below it competes for the budget by nearness (B-M4).
    var MAX_ITEMS = 20000, beyond = 0, WORDY = /[\p{L}\p{N}]/u, hrefs = new Set();

    function emit(depth, line, el, important) {
      var r = rectInTop(el), side = 0, dist = 0;
      if (r.top + r.height <= 0) { side = -1; dist = -(r.top + r.height); }
      else if (r.top >= vh) { side = 1; dist = r.top - vh; }
      // Past the bound only what is on screen and actionable is kept; no list can reach the rest, so it is counted apart.
      if (items.length >= MAX_ITEMS && !(important && side === 0)) { beyond++; return; }
      var pri = a.screen ? (side === 0 ? 0 : important ? 1 : 2) : (important ? 0 : 2) + (side ? 1 : 0);
      items.push({ s: new Array(depth + 1).join('  ') + '- ' + line, pri: pri, dist: dist, side: side });
    }

    // A table without headers or caption lays out the page (HN); its rows and cells are not data.
    function layoutTable(t) {
      var r = t && t.getAttribute('role');
      return !!t && (!r || r === 'presentation' || r === 'none') &&
        !t.querySelector(':scope > caption, :scope > thead, :scope > tr > th, :scope > tbody > tr > th');
    }

    function container(el) {
      if (el.hasAttribute('role') || el.hasAttribute('tabindex') || el.hasAttribute('onclick') || el.hasAttribute('aria-label')) return false;
      var t = el.localName;
      return t === 'li' || ((t === 'table' || t === 'tr' || t === 'td') && layoutTable(el.closest('table')));
    }

    function children(parent, depth, skipText) {
      var kids = parent.localName === 'slot' ? parent.assignedNodes({ flatten: true }) : parent.childNodes;
      for (var i = 0; i < kids.length; i++) {
        var n = kids[i];
        if (n.nodeType === 3) {
          if (!skipText && n.parentElement) { var t = norm(n.data); if (WORDY.test(t)) emit(depth, 'text: ' + q(cut(t, 100)), n.parentElement, false); }
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
        // A ref, so a click on it reaches the frame approval (the page inside cannot be listed).
        emit(depth, 'iframe' + (title ? ' ' + q(title) : '') + ' (cross-origin, not accessible) [ref=' + refFor(el) + ']', el, true);
        return;
      }
      emit(depth, 'iframe' + (title ? ' ' + q(title) : ''), el, true);
      children(doc.body || doc.documentElement, depth + 1, false);
    }

    function element(el, depth) {
      if (skipSubtree(el)) return;
      if (el.localName === 'iframe' || el.localName === 'frame') return frame(el, depth);
      if (el.localName === 'option' && el.closest('select')) return;
      if (el.localName === 'slot') return children(el, depth, false); // no box of its own; walk assigned nodes
      if (container(el)) return children(el, depth, false);
      var role = roleShown(el);
      var inner = el.shadowRoot || el;
      var field = isField(el) || el.localName === 'select';
      if (!role && scrollBox(el) && visibleBox(el)) {
        // A scrolling pane gets a line of its own, so scroll(ref) can reach it, and is never left out (W-m1).
        emit(depth, 'generic [scrollable] [ref=' + refFor(el) + ']', el, true);
        return children(inner, depth + 1, false);
      }
      if (!role) {
        if (!textLeaf(el)) return children(inner, depth, false);
        var t = norm(el.innerText);
        if (WORDY.test(t) && visibleBox(el)) emit(depth, 'text: ' + q(cut(t, 100)), el, false);
        return;
      }
      if (!visibleBox(el)) return field ? undefined : children(inner, depth, false);
      var name = nameOf(el, role);
      var line = describeLine(el, role, name);
      var arrow = role === 'link' ? line.lastIndexOf(' -> ') : -1;
      if (arrow > 0) {
        // A link to a place listed before ("3 hours ago" and "45 comments" on HN): its address is not repeated.
        if (hrefs.has(line.slice(arrow))) line = line.slice(0, arrow); else hrefs.add(line.slice(arrow));
      }
      var important = role === 'heading' || line.indexOf('[ref=') >= 0;
      if (!FROM_CONTENT[role] && !field && role !== 'img' && textLeaf(el)) {
        var lt = norm(el.innerText);
        if (lt && lt !== name) line += ': ' + q(cut(lt, 100));
        return emit(depth, line, el, important);
      }
      emit(depth, line, el, important);
      if (el.localName === 'select' || el.localName === 'textarea') return;
      children(inner, depth + 1, !!FROM_CONTENT[role]);
    }

    children(document.body || document.documentElement, 0, false);

    var cand = items.map(function (it, i) { return i; });
    var total = 0;
    cand.forEach(function (i) { total += items[i].s.length + 1; });
    var keep = new Set(cand);
    if (total > budget) {
      cand.sort(function (x, y) { return (items[x].pri - items[y].pri) || (items[x].dist - items[y].dist) || (x - y); });
      var used = 0;
      keep = new Set();
      cand.forEach(function (i) {
        var len = items[i].s.length + 1;
        if (used + len <= budget) { keep.add(i); used += len; }
      });
    }
    var out = [], above = 0, below = 0;
    items.forEach(function (it, i) {
      if (keep.has(i)) out.push(it.s);
      else if (it.side < 0) above++; else below++;
    });
    return { text: out.join('\n'), listed: out.length, above: above, below: below, beyond: beyond };
  }

  // ---------- main text (read)

  var BLOCK = {
    p: 1, div: 1, section: 1, article: 1, main: 1, header: 1, footer: 1, aside: 1, nav: 1, ul: 1, ol: 1, li: 1, dl: 1,
    dt: 1, dd: 1, table: 1, tr: 1, blockquote: 1, pre: 1, figure: 1, figcaption: 1, details: 1, summary: 1, form: 1,
    fieldset: 1, address: 1, hr: 1, br: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, caption: 1
  };
  var CHROME = 'nav, footer, aside, [role=navigation], [role=banner], [role=contentinfo], [role=complementary]';

  /**
   * Text of the page's main part: open shadow roots, same-origin frames, closed <details> and hidden=until-found
   * included; scripts, aria-hidden and display:none left out (and site chrome when the root is the body).
   */
  function readText() {
    var body = document.body || document.documentElement;
    var cands = document.querySelectorAll('main, [role=main]'), root = null;
    for (var i = 0; i < cands.length && !root; i++) if (norm(cands[i].textContent).length >= 500) root = cands[i];
    // One article is the page's text; several (posts, comments, a feed) are all read from the body (B-m11).
    var arts = root ? [] : document.querySelectorAll('article');
    if (arts.length === 1 && norm(arts[0].textContent).length >= 500) root = arts[0];
    var text = textOf(root || body, !root);
    if (root && text.length < 200) text = textOf(body, true);
    // The first text block on screen carries a mark through the normalising; its place is where the screen is (W-m2).
    var at = text.indexOf(MARK);
    text = text.split(MARK).join('');
    return { title: norm(document.title), url: location.href, text: text, flags: flags(text), screenAt: at >= 0 ? at : null };
  }

  var MARK = '\u0001';

  /** A block with its own text, on screen and not in a fixed or sticky bar. */
  function onScreenLeaf(n) {
    var own = false;
    for (var c = n.firstChild; c && !own; c = c.nextSibling) own = c.nodeType === 3 && /\S/.test(c.data);
    if (!own) return false;
    var r = rectInTop(n);
    if (!(r.height > 0 && r.top < innerHeight && r.top + r.height > 0)) return false;
    for (var p = n; p && p.nodeType === 1; p = p.parentElement) {
      var pos = styleOf(p).position;
      if (pos === 'fixed' || pos === 'sticky') return false;
    }
    return true;
  }

  function textOf(root, skipChrome) {
    var lines = [], cur = '', prefix = '', marked = false;
    function flush() {
      var t = cur.replace(/\s+/g, ' ').trim();
      if (t) lines.push(prefix + t);
      cur = ''; prefix = '';
    }
    function kids(n) {
      var list = n.localName === 'slot' ? n.assignedNodes({ flatten: true }) : n.childNodes;
      if (n.localName === 'slot' && !list.length) list = n.childNodes;
      for (var i = 0; i < list.length; i++) walk(list[i]);
    }
    function walk(n) {
      if (n.nodeType === 3) { cur += n.data; return; }
      if (n.nodeType !== 1) return;
      var t = n.localName;
      if (SKIP_TAGS[t] || n.getAttribute('aria-hidden') === 'true') return;
      if (t === 'input' && (n.getAttribute('type') || '').toLowerCase() === 'hidden') return;
      if (skipChrome && n.matches(CHROME)) return;
      if (n.getAttribute('hidden') !== 'until-found' && styleOf(n).display === 'none') return;
      if (t === 'iframe' || t === 'frame') {
        var doc = null;
        try { doc = n.contentDocument; } catch (e) { doc = null; }
        if (doc && doc.body) { flush(); kids(doc.body); flush(); }
        return;
      }
      if (t === 'br') { flush(); return; }
      if (t === 'td' || t === 'th') {
        if (norm(cur)) cur += ' | ';
        kids(n.shadowRoot || n);
        return;
      }
      if (!BLOCK[t]) { kids(n.shadowRoot || n); return; }
      flush();
      if (HEADINGS[t]) { lines.push(''); prefix = '## '; }
      else if (t === 'li') prefix = '- ';
      if (!marked && onScreenLeaf(n)) { cur += MARK; marked = true; }
      kids(n.shadowRoot || n);
      flush();
      if (HEADINGS[t] || t === 'p' || t === 'table' || t === 'pre' || t === 'blockquote') lines.push('');
    }
    walk(root);
    flush();
    return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  /**
   * On a short page only (a long one is an article that may mention them, B-M3): "bot" = bot check or consent
   * wording; "login" = a visible password field.
   */
  function flags(text) {
    var f = [];
    if (text.length >= 1500) return f;
    if (botRe.test(text)) f.push('bot');
    var pw = document.querySelector('input[type=password]');
    if (pw && visible(pw)) f.push('login');
    return f;
  }

  // ---------- actions

  function events(el, names) {
    names.forEach(function (n) { el.dispatchEvent(new Event(n, { bubbles: true, composed: true })); });
  }

  function typeText(a) {
    var el = resolve(a.target);
    if (el.localName === 'select') return chooseOption(el, a.text);
    if (!isEditable(el)) throw new Error('The element is not a text field (' + el.localName + (roleOf(el) ? ', ' + roleOf(el) : '') + ').');
    if (el.disabled || el.readOnly) throw new Error('text field is disabled or read-only');
    el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    el.focus();
    if (el.isContentEditable) {
      var doc = el.ownerDocument;
      doc.execCommand('selectAll', false, null);
      if (!doc.execCommand('insertText', false, a.text)) el.textContent = a.text;
      return { value: cut(norm(el.innerText), 200) };
    }
    var desc = el.localName === 'textarea' ? textareaValue : inputValue;
    desc.set.call(el, a.text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: a.text }));
    events(el, ['change']);
    var res = { value: cut(el.value, 200) };
    var sub = el.form && el.form.querySelector('button:not([type]), button[type=submit], input[type=submit], input[type=image]');
    if (sub && visibleBox(sub)) res.submitRef = refFor(sub);
    return res;
  }

  function chooseOption(el, text) {
    var opts = Array.prototype.slice.call(el.options), want = norm(text).toLowerCase();
    var o = opts.filter(function (x) { return norm(x.text).toLowerCase() === want; })[0] ||
      opts.filter(function (x) { return x.value.toLowerCase() === want; })[0];
    if (!o) {
      throw new Error('No option matches ' + q(text) + '. Options: ' + opts.slice(0, 30).map(function (x) { return norm(x.text); }).join(', ') +
        (opts.length > 30 ? ' …' : ''));
    }
    opts.forEach(function (x) { x.selected = x === o; });
    events(el, ['input', 'change']);
    return { value: norm(o.text), selected: norm(o.text) };
  }

  function click(a) {
    var el = resolve(a.target);
    if (el.focus) el.focus();
    el.click();
    return {};
  }

  // ---------- scroll (browser-layers 2-7, S16)

  /** The scroll box with the most area on screen, for a page whose document does not scroll. */
  function largestBox() {
    var best = null, area = 0, all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!scrollBox(el)) continue;
      var r = el.getBoundingClientRect();
      var w = Math.min(r.right, innerWidth) - Math.max(r.left, 0), h = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
      if (w > 0 && h > 0 && w * h > area) { area = w * h; best = el; }
    }
    return best;
  }

  function docScrolls(se) {
    if (se.scrollHeight <= se.clientHeight + 1) return false;
    var h = styleOf(document.documentElement).overflowY, b = document.body ? styleOf(document.body).overflowY : '';
    return h !== 'hidden' && h !== 'clip' && b !== 'hidden' && b !== 'clip';
  }

  /**
   * One screen (0.85 of it) down or up, at once (a page's smooth scrolling would delay reading the place): the element
   * a.target, else the document, else the biggest pane on screen. "Reached the end" is the app's call (moved, docHeight
   * and count staying the same after a wait).
   */
  function scroll(a) {
    var se = document.scrollingElement || document.documentElement, el, box = null;
    if (a.target) { el = resolve(a.target); box = el; }
    else if (docScrolls(se)) el = se;
    else { el = largestBox() || se; if (el !== se) box = el; }
    var step = (el === se ? innerHeight : el.clientHeight) * 0.85 * (a.dir === 'up' ? -1 : 1);
    var before = el.scrollTop;
    el.scrollBy({ top: step, behavior: 'instant' });
    var y = el.scrollTop;
    return {
      y: Math.round(y), max: Math.max(0, Math.round(el.scrollHeight - el.clientHeight)), moved: Math.abs(y - before) >= 1,
      docHeight: el.scrollHeight, count: document.getElementsByTagName('*').length, box: box ? refFor(box) : null
    };
  }

  // ---------- execute (architecture 7.4)

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
      );
      return { pending: id };
    }
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
    mark: mark, pageList: pageList, readText: readText, locate: function (a) { return locate(resolve(a.target), a.scroll !== false); },
    click: click, type: typeText, suggestions: suggestions, scroll: scroll, execResult: execResult, execPoll: execPoll
  };

  var api = {
    call: function (fn, args) {
      var f = FNS[fn];
      if (!f) throw new Error('unknown page function ' + fn);
      return f(args || {});
    },
    /** The ref number after the last one this document handed out. */
    next: function () { return nextNum; },
    /** The tab's next number: a document restored from the back/forward cache numbers on from there (F7). */
    floor: function (n) { if (n > nextNum) nextNum = n; }
  };
  Object.defineProperty(window, K, { value: api, enumerable: false, configurable: false, writable: false });
})
