// Reads a web page for the agents: the text of its main part, without menus and scripts.
var assi = require('lib/assi');

exports.name = 'web_read';
exports.description = 'Прочитать веб-страницу: заголовок и основной текст. Длинную страницу читайте частями через offset.';
exports.parameters = {
  type: 'object',
  properties: {
    url: { type: 'string', description: 'Адрес страницы' },
    offset: { type: 'integer', description: 'С какого символа продолжить, по умолчанию 0' }
  },
  required: ['url']
};

var AGENT = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36';
var LIMIT = 20000;
// Marks where a block ended, so paragraphs survive the flattening to text.
var BREAK = ' ';

exports.run = function (args) {
  var page = assi.http.request({ url: args.url, headers: { 'User-Agent': AGENT, 'Accept-Language': 'ru,en;q=0.8' } });
  if (!page.ok) return 'Страница ответила ошибкой ' + page.status + '.';
  var type = page.headers['content-type'] || '';
  var text;
  var title = '';
  if (type.indexOf('html') < 0 && page.text.slice(0, 200).indexOf('<') < 0) {
    text = page.text;
  } else {
    var document = org.jsoup.Jsoup.parse(page.text, args.url);
    title = String(document.title());
    document.select('script, style, noscript, nav, header, footer, aside, form, svg, iframe').remove();
    var main = document.selectFirst('article, main, [role=main]');
    var body = main && String(main.text()).length > 500 ? main : document.body();
    body.select('p, li, h1, h2, h3, h4, tr, br').after(BREAK);
    text = String(body.text()).split(BREAK).map(function (line) { return line.trim(); })
      .filter(function (line) { return line; }).join('\n');
  }
  var from = args.offset || 0;
  var part = text.slice(from, from + LIMIT);
  var rest = text.length - from - part.length;
  return (title ? title + '\n\n' : '') + part +
    (rest > 0 ? '\n\n… ещё ' + rest + ' символов: продолжите с offset=' + (from + part.length) : '');
};
