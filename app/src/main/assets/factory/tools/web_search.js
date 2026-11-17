// Web search for the agents. The default needs no key (DuckDuckGo's plain HTML results);
// with a key in the vault it uses a search API instead:
//   assi.pref('search.provider', 'tavily' | 'brave')  and  assi.secret.ask('search.key', 'Ключ поиска')
// The key is named in the request and put in by the shell: this file never holds it.
var assi = require('lib/assi');

exports.name = 'web_search';
exports.description = 'Поиск в интернете: заголовки, адреса и выдержки. Нужные страницы затем читайте через web_read.';
exports.parameters = {
  type: 'object',
  properties: {
    query: { type: 'string', description: 'Что искать' },
    count: { type: 'integer', description: 'Сколько результатов, по умолчанию 8' }
  },
  required: ['query']
};

var AGENT = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36';

function duckduckgo(query, count) {
  var page = assi.http.request({
    url: 'https://html.duckduckgo.com/html/?q=' + encodeURIComponent(query),
    headers: { 'User-Agent': AGENT, 'Accept-Language': 'ru,en;q=0.8' }
  });
  var found = [];
  var results = org.jsoup.Jsoup.parse(page.text).select('.result');
  for (var i = 0; i < results.size() && found.length < count; i++) {
    var link = results.get(i).selectFirst('.result__a');
    if (!link) continue;
    var address = String(link.attr('href'));
    var wrapped = /[?&]uddg=([^&]+)/.exec(address);
    if (wrapped) address = decodeURIComponent(wrapped[1]);
    if (address.indexOf('http') !== 0) continue;
    var snippet = results.get(i).selectFirst('.result__snippet');
    found.push({ title: String(link.text()), url: address, text: snippet ? String(snippet.text()) : '' });
  }
  return found;
}

var KEY = '{{secret:search.key}}';

function tavily(query, count) {
  var answer = assi.http.post('https://api.tavily.com/search', { api_key: KEY, query: query, max_results: count }).json();
  return (answer.results || []).map(function (item) { return { title: item.title, url: item.url, text: item.content }; });
}

function brave(query, count) {
  var answer = assi.http.request({
    url: 'https://api.search.brave.com/res/v1/web/search?count=' + count + '&q=' + encodeURIComponent(query),
    headers: { 'X-Subscription-Token': KEY, Accept: 'application/json' }
  }).json();
  return ((answer.web || {}).results || []).map(function (item) { return { title: item.title, url: item.url, text: item.description }; });
}

exports.run = function (args) {
  var count = Math.min(args.count || 8, 20);
  var provider = assi.pref('search.provider') || 'duckduckgo';
  var key = assi.secret && assi.secret.has ? assi.secret.has('search.key') : false;
  var found = provider === 'tavily' && key ? tavily(args.query, count)
    : provider === 'brave' && key ? brave(args.query, count)
      : duckduckgo(args.query, count);
  if (!found.length) return 'Ничего не найдено.';
  return found.map(function (item, i) {
    return (i + 1) + '. ' + item.title + '\n   ' + item.url + (item.text ? '\n   ' + item.text : '');
  }).join('\n');
};
