/*
 * Search result extractors (S-01, architecture 7.4, R8). Evaluated by browser.script.PageScript as
 *   (this function)(engine, max)
 * on a loaded result page; returns [{title, url, snippet}]. Engine markup changes are fixed here only.
 * The file must stay a single function expression.
 */
(function (engine, max) {
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
      if (/google\./.test(u.hostname) && u.pathname === '/url') return u.searchParams.get('q') || u.searchParams.get('url') || href;
      return u.href;
    } catch (e) {
      return href;
    }
  }

  var RULES = {
    // html.duckduckgo.com/html: no JS, stable markup
    DUCKDUCKGO: { item: '.result:not(.result--ad)', link: 'a.result__a', snippet: '.result__snippet' },
    GOOGLE: { item: '#search a:has(h3), #rso a:has(h3), #main a:has(h3)', title: 'h3', container: 'div.g, div[data-hveid], div.MjjYud', snippet: '.VwiC3b, [data-sncf], [style*="line-clamp"]' },
    BING: { item: '#b_results > li.b_algo', link: 'h2 a', snippet: '.b_caption p, .b_lineclamp2, .b_lineclamp3, p' },
    NAVER: { item: 'a.link_tit, .total_tit a, a.total_tit, a.title_link, .fds-comps-right-image-text-title', container: 'li, .total_wrap, .api_subject_bx, .fds-ugc-block-mod', snippet: '.total_dsc, .dsc_txt, .api_txt_lines.dsc_txt, .fds-comps-text, .dsc_link' }
  };

  var out = [], seen = {};
  function add(title, href, snippet) {
    if (!href || !title) return;
    var url = unwrap(href);
    if (!/^https?:/i.test(url) || seen[url]) return;
    seen[url] = 1;
    out.push({ title: norm(title).slice(0, 200), url: url, snippet: norm(snippet).slice(0, 300) || null });
  }

  var r = RULES[engine] || {};
  var items = r.item ? document.querySelectorAll(r.item) : [];
  for (var i = 0; i < items.length && out.length < max; i++) {
    var it = items[i];
    if (r.link) {
      var a = it.querySelector(r.link);
      if (a) add(a.innerText, a.getAttribute('href'), txt(it, r.snippet));
    } else {
      var box = r.container ? (it.closest(r.container) || it.parentElement) : it.parentElement;
      add(r.title ? txt(it, r.title) || it.innerText : it.innerText, it.getAttribute('href'), txt(box, r.snippet));
    }
  }

  // Fallback when the markup changed: external links with real titles.
  if (!out.length) {
    var host = location.hostname.split('.').slice(-2).join('.');
    var links = document.querySelectorAll('main a[href], #main a[href], body a[href]');
    for (var j = 0; j < links.length && out.length < max; j++) {
      var l = links[j], t = norm(l.innerText);
      var u = unwrap(l.getAttribute('href'));
      if (t.length < 15 || !/^https?:/i.test(u)) continue;
      try { if (new URL(u).hostname.slice(-host.length) === host) continue; } catch (e) { continue; }
      add(t, u, norm((l.closest('li, article, div') || l).innerText).slice(t.length, t.length + 300));
    }
  }
  return out;
})
