// Language models for modules: var ai = require('lib/ai');
// Which model answers is the owner's choice in Settings: every `role` is a line there
// ("Кто на какой модели"); a role without its own model uses the main agent's.
// A module lists its roles in module.json: "models": [{ "id": "notes.summary", "title": "Сводка заметок" }]

/**
 * One answer without tools.
 *   ai.ask('Сократи до одной фразы: ' + text)
 *   ai.ask({ role: 'notes.summary', system: 'Ты редактор.', prompt: text, json: true })
 * Options: role, system, prompt or messages, json (parse the answer as JSON),
 * images (array of data: or http URLs), plus any field of the chat completions API.
 */
exports.ask = function (options) {
  if (typeof options === 'string') options = { prompt: options };
  var messages = options.messages ? options.messages.slice() : [];
  if (options.system) messages.unshift({ role: 'system', content: options.system });
  if (options.prompt !== undefined) {
    var content = options.prompt;
    if (options.images && options.images.length) {
      content = [{ type: 'text', text: String(options.prompt) }].concat(options.images.map(function (url) {
        return { type: 'image_url', image_url: { url: url } };
      }));
    }
    messages.push({ role: 'user', content: content });
  }
  var body = { messages: messages };
  Object.keys(options).forEach(function (key) {
    if (['role', 'system', 'prompt', 'messages', 'json', 'images'].indexOf(key) < 0) body[key] = options[key];
  });
  if (options.json && !body.response_format) body.response_format = { type: 'json_object' };
  var response = native('models.complete', options.role || 'main', body);
  var text = response.choices && response.choices[0] && response.choices[0].message
    ? response.choices[0].message.content : '';
  if (typeof text !== 'string') {
    text = (text || []).map(function (part) { return part.text || ''; }).join('');
  }
  if (!options.json) return text;
  var cleaned = text.replace(/^\s*```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '');
  return JSON.parse(cleaned);
};

/** The raw chat completions response, when the text alone is not enough. */
exports.complete = function (role, body) {
  return native('models.complete', role || 'main', body);
};

/**
 * Gives a task to an agent (agents/<id>/, or 'axiko') in a fresh conversation, with its tools,
 * and waits for the final answer. The agent knows nothing but the task: put everything in it.
 */
exports.run = function (agent, task) {
  return native('agent.run', agent, String(task));
};

/** The agents there are: [{ id, title, description, icon, role, model }]. */
exports.agents = function () {
  return native('agent.list');
};
