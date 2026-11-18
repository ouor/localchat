/*
 * Search result extractors (S-01, architecture 7.4, R8). Evaluated by browser.script.PageScript as
 *   (this function)(engine, max, cfg)
 * on a loaded result page; returns {results: [{title, url, snippet}], blocked}. blocked = a bot check or consent page
 * (cfg.bot is SearchEngines.BOT_PATTERN); then nothing is extracted. Engine markup changes are fixed here only.
 * The file must stay a single function expression.
 */
(function (engine, max, cfg) {
  'use strict';
  function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function txt(root, sel) { var n = sel && root ? root.querySelector(sel) : null; return n ? norm(n.innerText || n.textContent) : ''; }

  function unwrap(href) {
    try {
      var u = new URL(href, location.href);
      if (/duckduckgo\.com$/.test(u.hostname) && u.pathname === '/l/') return u.searchParams.get('uddg') || href;
      if (/bing\.com$/.test(u.hostname) && u.pathname.indexOf('/ck/') === 0) {
        var b = u.searchParams.get('u');
        if (b && b.indexOf('a1') === 0) return atob(b.slice(2).replace(/-/g, '+').replace(/_/g, '/'));
      }
      if (/google\./.test(u.hostname) && (u.pathname === '/url' || u.pathname === '/goto')) {
        // /goto's url is opaque since 2026-11 (measured): the absolute redirect then, which lands on the site when opened.
        var g = u.searchParams.get('q') || u.searchParams.get('url');
        return g && /^https?:/i.test(g) ? g : u.href;
      }
      return u.href;
    } catch (e) {
      return href;
    }
  }

  // Each result is a card: the title link is `link` (or the first texted link outside `strip`), the snippet
  // is `snippet` (or the card text minus title and `strip`). Checked live 2026-11 (mobile WebView UA).
  var RULES = {
    // html.duckduckgo.com/html: no JS, stable markup
    DUCKDUCKGO: { card: '.result:not(.result--ad)', link: 'a.result__a', snippet: '.result__snippet' },
    GOOGLE: { card: '#rso div.MjjYud, #search div.MjjYud, #rso div.g', link: 'a:has(h3), a:has([role=heading])', title: 'h3, [role=heading]', snippet: '.VwiC3b, [data-sncf]' },
    // Some layouts put the site attribution (.tilk/.tptt/cite) inside the title link or h2.
    BING: { card: 'li.b_algo', link: 'h2 a:not(.tilk), a:has(h2)', strip: '.b_tpcn, .tilk, .tptt, .b_attribution, cite, h2', snippet: '.b_caption p, .b_rcGenCap p, .b_algoSlug, .b_lineclamp2, .b_lineclamp3, .b_lineclamp4' },
    // m.search.naver.com: hashed sds-comps classes; profile = site name/url/date row
    // (fds-default-mode skips ad boxes; help/keep/search links are Naver chrome)
    NAVER: { card: '.api_subject_bx.fds-default-mode, .total_wrap', strip: '.sds-comps-profile, .thumb-area, .source_box', skipHost: /(^|\.)(help|keep|ader|search)\.naver\.com$/ }
  };

  var body = document.body ? (document.body.innerText || '') : '';
  if (new RegExp(cfg.bot, 'i').test(body.slice(0, 3000))) return { results: [], blocked: true };

  var r = RULES[engine] || {};
  var out = [], seen = {};
  function add(title, href, snippet) {
    if (!href || !title) return;
    var url = unwrap(href);
    if (!/^https?:/i.test(url) || seen[url]) return;
    if (skipped(url)) return;
    seen[url] = 1;
    out.push({ title: norm(title).slice(0, 200), url: url, snippet: norm(snippet).slice(0, 300) || null });
  }
  function skipped(url) {
    try { return !!r.skipHost && r.skipHost.test(new URL(url, location.href).hostname); } catch (e) { return true; }
  }
  function inStrip(n, card, strip) { return strip && n.closest(strip) && card.contains(n.closest(strip)); }
  function titleLink(card, r) {
    if (r.link) return card.querySelector(r.link);
    var as = card.querySelectorAll('a[href]');
    for (var k = 0; k < as.length; k++) {
      var t = norm(as[k].innerText);
      var h = as[k].getAttribute('href') || '#';
      if (h.charAt(0) === '#' || skipped(unwrap(h)) || inStrip(as[k], card, r.strip)) continue;
      if (t.length >= 4 && !/^[\d:.\s]+$/.test(t)) return as[k];
    }
    return null;
  }
  function titleText(a, r) {
    var c = a.cloneNode(true), t = '';
    if (r.strip) c.querySelectorAll('.tilk, .tptt, cite, .b_attribution').forEach(function (n) { n.remove(); });
    t = norm(c.textContent);
    return t || norm(a.innerText);
  }
  function rest(card, a, r) {
    var c = card.cloneNode(true), strip = (r.strip ? r.strip + ', ' : '') + 'script, style';
    c.querySelectorAll(strip).forEach(function (n) { n.remove(); });
    c.querySelectorAll('a[href]').forEach(function (n) { var h = n.getAttribute('href'); if (h.charAt(0) === '#' || skipped(unwrap(h))) n.remove(); });
    var t = norm(c.textContent), title = norm(a.textContent);
    return title && t.indexOf(title) >= 0 ? t.replace(title, '') : t;
  }

  var cards = r.card ? document.querySelectorAll(r.card) : [];
  for (var i = 0; i < cards.length && out.length < max; i++) {
    var card = cards[i], a = titleLink(card, r);
    if (!a) continue;
    var title = (r.title && txt(a, r.title)) || titleText(a, r);
    add(title, a.getAttribute('href'), txt(card, r.snippet) || rest(card, a, r));
  }
  // Fallback when the markup changed: external links with real titles.
  if (!out.length) {
    // Registered domain: three labels for co.kr, or.kr, co.uk, com.au ... (2-3 letters then 2), else two.
    var labels = location.hostname.split('.');
    var two = labels.slice(-2);
    var host = labels.slice(two[0].length <= 3 && two[1].length === 2 && labels.length > 2 ? -3 : -2).join('.');
    var links = document.querySelectorAll('main a[href], #main a[href], body a[href]');
    for (var j = 0; j < links.length && out.length < max; j++) {
      var l = links[j], t = norm(l.innerText);
      var u = unwrap(l.getAttribute('href'));
      if (t.length < 15 || !/^https?:/i.test(u)) continue;
      try { if (new URL(u).hostname.slice(-host.length) === host) continue; } catch (e) { continue; }
      add(t, u, norm((l.closest('li, article, div') || l).innerText).slice(t.length, t.length + 300));
    }
  }
  return { results: out, blocked: false };
})
