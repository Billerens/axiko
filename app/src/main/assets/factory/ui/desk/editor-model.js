// Open documents are independent: delayed reads and saves never change another tab.
(function (root, make) {
  var api = make();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.AxikoEditorModel = api;
})(typeof window === 'object' ? window : this, function () {
  'use strict';

  function address(path, draft) {
    path = String(path || '').replace(/^\/+/, '');
    var match = /^~([a-z0-9]+)\/(.+)$/.exec(path);
    if (match) { draft = match[1]; path = match[2]; }
    if (!path || path.split('/').some(function (part) { return !part || part === '.' || part === '..'; }) ||
        /[\\\u0000-\u001f]/.test(path)) throw new Error('Нужен относительный путь к файлу приложения');
    return { path: path, draft: draft || null, key: (draft ? '~' + draft + '/' : '') + path };
  }

  function create(rpc, events) {
    events = events || {};
    var docs = new Map();

    function changed(doc) { if (events.changed) events.changed(doc); }
    function dirty(doc) { return doc.text !== doc.saved || doc.isNew; }
    function request(doc, op, extra) {
      return rpc(Object.assign({ op: op, path: doc.path, draft: doc.draft || null }, extra));
    }
    async function load(doc, allowNew) {
      var ticket = ++doc.generation;
      doc.loading = true;
      doc.error = null;
      changed(doc);
      try {
        var value = await request(doc, 'desk.read');
        if (ticket !== doc.generation) return doc;
        doc.text = value.text;
        doc.saved = value.text;
        doc.revision = value.revision;
        doc.isNew = false;
      } catch (error) {
        if (ticket !== doc.generation) return doc;
        if (allowNew && error.code === 'not_found') {
          doc.text = '';
          doc.saved = '';
          doc.revision = null;
          doc.isNew = true;
        } else {
          doc.error = error;
        }
      } finally {
        if (ticket === doc.generation) { doc.loading = false; changed(doc); }
      }
      return doc;
    }
    function open(path, options) {
      options = options || {};
      var a = address(path, options.draft);
      if (docs.has(a.key)) return Promise.resolve(docs.get(a.key));
      var doc = Object.assign(a, { text: '', saved: '', revision: null, generation: 0,
        loading: false, saving: false, isNew: false, error: null, pending: null, saveRequested: false });
      docs.set(doc.key, doc);
      return load(doc, options.create === true);
    }
    function update(doc, text) {
      doc.text = String(text);
      if (doc.error && doc.error.code !== 'conflict') doc.error = null;
      changed(doc);
    }
    function save(doc) {
      if (doc.loading || (doc.error && doc.revision === null && !doc.isNew)) {
        return Promise.reject(new Error('Файл ещё не загружен'));
      }
      if (!dirty(doc) && !doc.saving) return Promise.resolve(doc);
      doc.saveRequested = true;
      if (doc.pending) return doc.pending;
      doc.saving = true;
      doc.error = null;
      changed(doc);
      doc.pending = Promise.resolve().then(async function () {
        try {
          do {
            doc.saveRequested = false;
            var text = doc.text;
            var result = await request(doc, 'desk.write', { text: text, expectedRevision: doc.revision });
            doc.saved = text;
            doc.revision = result.revision;
            doc.isNew = false;
            if (events.saved) events.saved(doc);
            changed(doc);
          } while (doc.saveRequested && dirty(doc));
          return doc;
        } catch (error) {
          doc.error = error;
          throw error;
        } finally {
          doc.saving = false;
          doc.saveRequested = false;
          doc.pending = null;
          changed(doc);
        }
      });
      return doc.pending;
    }
    function reload(doc) {
      if (doc.saving) return Promise.reject(new Error('Дождитесь завершения сохранения'));
      return load(doc, false);
    }
    async function remote(doc) { return request(doc, 'desk.read'); }
    function adopt(doc, value, keepText) {
      if (doc.saving) throw new Error('Дождитесь завершения сохранения');
      doc.generation++;
      doc.saved = value.text;
      if (!keepText) doc.text = value.text;
      doc.revision = value.revision;
      doc.error = null;
      doc.isNew = false;
      changed(doc);
    }
    function close(doc) {
      if (doc.saving) throw new Error('Дождитесь завершения сохранения');
      doc.generation++;
      docs.delete(doc.key);
    }
    return { docs: docs, open: open, update: update, save: save, reload: reload, remote: remote,
      adopt: adopt, close: close, dirty: dirty, address: address };
  }
  return { create: create, address: address };
});
