# Как делать игру для Игротеки

Это руководство для агента. Устройство Игротеки целиком описано в `docs/modules/games.md`
(разделы «Движок: …»); здесь — что нужно, чтобы сделать игру и не сломать чужие сохранения.

Игротека — модуль `modules/games/`. Игра — папка `modules/games/<id>/`. Каталог сам находит
каждую папку с `game.json`: регистрировать игру где-то ещё не нужно. Сохранения, офлайн-доход,
престиж, ачивки, звук, связки с жизнью и запас контента даёт движок. Игра даёт логику
(`sim.js`), экран (`ui/`) и числа (`config.json`).

## Порядок работы

1. **Расспрос.** Один-два круга формой `ask_owner`: что делается за 10 секунд, за перегон и за
   поездку; нужен ли престиж и что он даёт; какие связки (поездка, шаги, ставка); вид
   (абстрактный, пиксельный, схемы). Свой вариант отметьте.
2. **План** через `plan_propose`.
3. **Копия заготовки.** Скопируйте `modules/games/engine/template/` в `modules/games/<id>/`. Пути
   `../engine/…` в заготовке верны именно оттуда.
   - `id`: латиница, цифры и дефис, до 24 знаков.
   - Нельзя: `engine`, `ui`, `links`, `daily`, `archive`, `template`.
   - Игра дня живёт как `daily/ГГГГ-ММ-ДД`.
4. **Заполнение.** Заполните `game.json`, `config.json`, `sim.js`, `ui/game.js`, `ui/game.css`,
   `icon.svg` (контур 24×24, только `stroke`, как у модулей) и `checks.js`.
   - Числа кладите в `config.json`.
   - Тексты и списки (рецепты, этажи, события) кладите в `content/*.json`.
5. **Проверки** до ответа владельцу: `module_check('games/<id>')`. Без косой черты `games`
   прогоняет проверки движка и всех игр. Смотрите оба вида экрана:
   `ui_check` с `path: 'modules/games/ui/preview.html?game=<id>&w=both'`. Если `ui_check` вам не дан —
   `ui_open` того же пути и `ui_screenshot`.
6. **Ответ владельцу** короткий: как играть, что даёт престиж, какие связки подключены.
7. **Правка игры, в которую уже играют.** Если меняется формат сохранения или баланс, работайте
   в черновике (`draft_begin`).
   - Поднимите `saveVersion` и допишите `sim.migrate`.
   - Положите сохранение прежней версии в `fixtures/v<N>.json`.
   - Новый контент по возможности давайте данными.

## Живые сцены и прогрессия

Для дополнительной сцены используйте `Game.scene({parent, game: g, label, width: 1000,
height: 620, draw(ctx, model, timeSeconds, effects)})`. Создайте её один раз в `init`, а в
`render` передавайте `scene.update(model)` — небольшую отдельную модель из текущего состояния.
Полные покупки, цены, события, деревья улучшений и управление остаются в настоящем интерфейсе.
В старой Игротеке без `Game.scene` экран должен сохранять прежние функции.

- `draw` получает контекст в заданных логических координатах. Рендерер сам учитывает размер,
  плотность экрана, ограничение кадров и пикселей, скрытую область/приложение и reduced-motion.
- `scene.pulse(kind, payload, durationSeconds)` отмечает подтверждённую покупку или событие.
  Эффекты `{kind, age, duration, payload, strength}` объединяются по виду, одновременно их не
  больше четырёх. После офлайна показывайте итог, не очередь из тысяч накопленных эффектов.
- Рисование не меняет save, игровой RNG, время или баланс. Не создавать будущие объекты для
  красоты: различайте открытое, купленное, активное и оставшееся после престижа. Большие
  количества агрегируйте; визуальный бюджет не растёт вместе с числом покупок.
- Начальный, развитый и поздний мир должны различаться. Проверяйте старое сохранение,
  настоящий результат покупки, нехватку ресурсов, событие и престиж. Новое оформление само
  по себе не требует нового saveVersion и не должно вызывать миграцию.
- При удалении сцены вызывайте `scene.dispose()`. Для диагностики есть `scene.inspect()`:
  число кадров/эффектов, видимость, режим движения и ошибка рисования; игровых данных там нет.

Для основного быстрого игрового поля сохраняйте существующий `animate` и управление.
Декорации должны использовать ту же камеру и реальные позиции сущностей.

## Правила

- **Общее подмножество JavaScript в `sim.js`.** Логика выполняется на экране, в движке (Rhino) и
  в node.
  - Нельзя: `class`, `async`/`await`, `f(...args)`, `for (const …)`, `for (let …)` (в Rhino
    замыкание в таком цикле видит последнее значение), `import`/`export`.
  - Можно: `var`, `function`, стрелки и шаблонные строки.
  - Обёртка — как в заготовке (UMD: `window.GameSim` на экране, `exports` в движке).
  - `Core.lint(code, false)` покажет нарушения.
- **Случайность** — только из `ctx.rng` (или `rng`, который дали). `Math.random` в логике делает
  офлайн-подсчёт и проверки невоспроизводимыми. На экране для украшений можно.
- **Числа — настройки.** Каждое число баланса (цены, рост, доход, пороги, длительности) — ключ
  `config.json`. В коде его берут из `ctx.config` / `cfg` / `g.config`. Владелец меняет числа
  на экране «Настройки игры».
- **Большие числа** — через `Big`. В состоянии они хранятся строкой `"1.5e12"` или числом.
  `Big.of(x)` принимает и то и другое; результат операций — объект `Big`, `JSON.stringify` сам
  делает из него строку. Никаких `Number` для валют, которые растут дальше 1e15.
- **Состояние** — только данные: числа, строки, массивы, объекты. Без функций, `Date`, `Map`,
  `NaN` и `Infinity`. Своё кладите в `state.run` (обнуляется престижем), `state.layers.<слой>.state`
  (обнуляется престижем старшего слоя) и `state.meta.<своё>` (не обнуляется).
- **Время** — `ctx.now` в логике. Состояние не хранит «сколько прошло»: движок досчитывает разрыв
  офлайн-прогрессом.
- **Без сети.** В `.html`, `.js` и `.css` игры не должно быть адресов `http(s)://`, кроме
  `https://axiko.local/`. Пространства имён `w3.org` в SVG можно.
  - Библиотеку скачайте один раз:
    `axiko.http.download(url, 'data/games/vendor/<имя>-<версия>.js')`. Подключайте её как
    `/data/games/vendor/…`.
  - Preact — `/ui/vendor/preact.js`.
  - Шрифты — системные.
  - Проверка «без сети» не пропустит адрес.
- **Вид** — правила владельца.
  - Никаких эмодзи: значки берите из `GameIcons` и кита.
  - Тёмная основа через переменные кита (`var(--bg)`, `var(--ink)`, `var(--card)`,
    `var(--accent)` …), серые карточки, без теней.
  - В CSS одно объявление на строку.
  - Всё нажимаемое — от 44 px (кнопки движка — от 56), шрифт — от 15 px.
  - Вёрстка — на ширину от 320 до 800 точек.
- **Одной рукой.** Главные действия кладите в `g.el.actions` (нижние 40 % экрана, под большим
  пальцем). Валюту и скорость — в `g.el.top`, поле — в `g.el.field`.
  - Жесты — дальше 24 px от боковых краёв.
  - Опасное — удержанием (`g.ui.hold`), не двойным касанием.
  - Ширины: внешний экран Fold5 — около 344 точек (`data-layout="narrow"`), развёрнутый — около
    690 (`wide`). Складывание присылает `resize`, без перезагрузки.
- **Темп** — проверяется ботом. Первая покупка не позже 10 с. Первый престиж — в поездках 3–6
  (три дня по две поездки по 25 минут, ночь и день между ними — офлайн). Каждый следующий
  престиж быстрее, пока растёт бонус.
  - Прирост престижа берите степенью от заработанного за забег: `floor((earned / threshold) ^ 0.5)`,
    или логарифмом. Тогда каждый сброс требует заметно больше.
  - Бонус слоя — линейный от валюты слоя (`1 + валюта × bonus`).
- **Офлайн.** Если у игры есть `sim.offline`, формула за 12 ч должна совпадать с шагами `tick` в
  пределах 1 %. Если формулы нет, движок считает шагами (до `offline.maxSteps`), и этого хватает.

## Файлы игры

```
modules/games/<id>/
  game.json        манифест (ниже)
  config.json      каждое число игры: {"ключ": {value, title, min, max, step, big, options, group}}
  sim.js           логика (общее подмножество)
  ui/index.html    страница: кит, файлы движка, ../sim.js, game.js (как в заготовке)
  ui/game.js       экран: Game.start(...)
  ui/game.css      свои стили (переменные кита, одно объявление на строку)
  icon.svg         значок каталога, контур 24×24
  checks.js        проверки: стандартные из engine/checks-kit.js и свои
  content/*.json   встроенный контент — данные, не код
  prompts/*.md     промпты запаса контента со схемой
  fixtures/*.json  сохранения прежних версий — для проверки миграций
```

Тяжёлое (звуки, картинки, библиотеки) кладите в `data/games/<id>/assets/` и
`data/games/vendor/`, а не в `modules/`: снимки рабочей папки делаются перед каждым ходом
агента. Адрес такого файла на экране — `/data/games/<id>/assets/…`.

## game.json

```json
{
  "id": "mine", "title": "Шахта", "about": "Копайте глубже, вагонетки возят золото",
  "genre": "idle", "engine": 1, "saveVersion": 1, "entry": "ui/index.html",
  "currencies": [
    {"id": "coins", "title": "Золото", "path": "run.coins", "icon": "coin", "bettable": true},
    {"id": "gems", "title": "Самоцветы", "path": "layers.depth.currency", "icon": "gem", "whole": true}
  ],
  "prestige": [
    {"id": "depth", "title": "Спуск глубже", "currency": "gems", "resets": ["run"], "minGain": "1",
     "hintRatio": 0.5, "lose": "золото, вагонетки", "keep": "самоцветы, ачивки и статистика"}
  ],
  "achievements": [
    {"id": "first-cart", "title": "Первая вагонетка", "text": "Поставить первую вагонетку",
     "life": true, "lifeText": "Поставил первую вагонетку в «Шахте»"},
    {"id": "rich", "title": "Золотая жила", "text": "Накопить 10 000 золота", "hidden": true}
  ],
  "links": {
    "passenger": {"income": true, "chest": true},
    "steps": {"spend": [
      {"id": "double-offline", "title": "Забрать ×2 в сводке «Пока вас не было»", "cost": 1},
      {"id": "cap", "title": "+2 часа к потолку следующего офлайна", "cost": 1},
      {"id": "rush", "title": "Час пик: доход ×3 на 10 минут", "cost": 1}
    ]},
    "bet": {"currency": "coins"}
  },
  "stock": [{"kind": "rumors", "title": "Слухи шахтёров", "min": 5, "target": 15, "batch": 5, "prompt": "prompts/rumors.md"}],
  "notify": [{"id": "cart", "title": "Хватает на вагонетку"}],
  "help": "Текст листа «Как играть», **markdown**.",
  "origin": {"from": "owner", "date": "2026-11-14"},
  "status": "active"
}
```

Поля:

- **Основные.**
  - `title`, `about`, `genre`: каталог.
  - `entry`: экран, по умолчанию `ui/index.html`.
  - `status`: `active` или `trial` (раздел «На пробу»; кнопка «Перенести в «Все игры»» ставит
    `active`).
  - `saveVersion`: версия формата сохранения. Поднимайте при любом несовместимом изменении
    состояния.
- **`currencies`** — валюты, которые движок двигает сам (ящик связок, сводка офлайна, ставка,
  удвоение).
  - `path`: точечный путь в состоянии.
  - `whole`: только целые.
  - `bettable`: можно ставить в «Ставке на день».
  - Валюта слоя живёт по пути `layers.<слой>.currency`.
- **`prestige`** — слои по возрастанию.
  - `resets`: что обнулить, по умолчанию `["run"]`.
  - `minGain`: меньше этого сброс не даётся.
  - `hintRatio`: метка «выгодно», иначе настройка `prestige.hintRatio`.
  - `lose` / `keep`: строки листа «Сбросится» / «Останется»; без них движок составит сам.
- **`achievements`**.
  - `hidden`: название не видно до получения.
  - `life`: уходит в «Ачивки за жизнь» (настройка `achievements.share`).
  - `lifeText`: как она звучит там.
- **`links`** — подключение связок.
  - `passenger.income: false`: поездка не умножает доход.
  - `passenger.chest`: сундук за поездку.
  - `steps.spend`: траты энергии. Цену можно сделать настройкой — ключ
    `steps.cost.<id трат>` в `config.json`. Особые `id`:
    - `double-offline`: кнопка «Забрать ×2» в сводке, делает движок.
    - `cap`: пункт меню «Потолок офлайна +N ч», делает движок.
    - Остальные применяет игра сама после `g.links.spend(id)`.
  - `bet.currency`: какую валюту ставить.
- **`stock`**: виды запаса контента. `min`: пополнять, когда неиспользованных меньше; `target`:
  до скольких; `batch`: сколько за раз; `prompt`: файл промпта.
- **`notify`**: события для уведомлений. Каждое — переключатель `notify.<id>` в настройках игры,
  по умолчанию выключен.
- **`help`**: лист «Как играть» в меню игры.

## config.json

```json
{
  "cart.cost": {"value": "15", "title": "Цена первой вагонетки", "big": true, "group": "Баланс"},
  "cart.growth": {"value": 1.12, "title": "Рост цены вагонетки", "min": 1.01, "max": 2, "step": 0.01, "group": "Баланс"},
  "offline.capHours": {"value": 8},
  "screen.keepOn": {"value": true}
}
```

- **Поля настройки.**
  - `value`: значение по умолчанию; его тип задаёт тип настройки.
  - `big: true`: большое число строкой.
  - `options: [["значение", "Подпись"], …]`: выбор из списка.
  - `min`, `max`, `step`: границы и шаг.
  - `group`: раздел экрана настроек.
- **Настройки движка в игре.** Ключ движка (полный список — `Core.SETTINGS`) в `config.json` игры
  перекрывает его значение для этой игры. Укажите только `value`, остальное возьмётся у движка.
- **Порядок значений:** движок → общие настройки Игротеки (если игра ключ не объявила) →
  `config.json` игры → значение владельца для игры (`data/games/<id>/config.json`). Настройки с
  `scope: 'hub'` (связки, запас, тихие часы) — только общие.
- **Ключи движка для игры:**
  - `tick.*`: такт.
  - `render.*`: кадры.
  - `offline.*`: потолок, доля, шаг.
  - `save.*`.
  - `prestige.holdMs`, `prestige.hintRatio`.
  - `layout.narrow`, `layout.wide`.
  - `screen.portrait`, `screen.keepOn`.
  - `fmt.mode`, `fmt.digits`, `fmt.sciFrom`.
  - `sound.*`, `vibro.*`.
  - `links.<связка>` и `notify.<событие>` (движок добавляет их сам).

## sim.js — логика

Экспортируйте объект функций (обязательные — без пометки):

| Функция | Что делает |
|---|---|
| `init(ctx)` → `{run, layers?, meta?}` | Новая игра или новый забег после престижа |
| `tick(state, dt, ctx)` | Шаг на `dt` секунд, меняет `state` на месте; доход умножайте на `ctx.mult` |
| `offline(state, sec, ctx)` → `{gains?, events?}` | Необяз.: офлайн формулой за `sec` секунд (уже с долей `offline.efficiency`), меняет `state`. `gains` — то, чего нет в валютах; `events` — строки сводки (или `ctx.report(text)`) |
| `prestigeGain(state, layerId, cfg)` → Big | Сколько валюты слоя даст сброс сейчас |
| `bonus(state, layerId, amount, cfg)` → число \| Big \| строка | Необяз.: бонус при `amount` валюты слоя — лист престижа покажет «×1,40 → ×1,52» |
| `onPrestige(state, layerId, before, ctx)` | Необяз.: перенести в новый забег что нужно (`before` — копия до сброса) |
| `migrate(state, fromVer)` → state | Перевод сохранения со старой версии (обязателен, если `saveVersion` > 1) |
| `validate(state)` → `null` \| текст | Необяз.: своя проверка целостности (движок проверит валюты и числа сам) |
| `summary(state, cfg)` → `{line}` | Необяз.: строка карточки каталога |
| `next(state, now, cfg)` → `[{id, at, title?, text}]` | Необяз.: будущие события для уведомлений (`id` из `game.json` `notify`) |
| `chest(state, rng, cfg)` → `{title, items: [{title, amount}]}` | Необяз.: сундук «Пассажира». Начислите сами внутри; без функции сундук — доход за `passenger.chestFallbackMin` минут |
| `procedural(kind, rng)` → элемент | Необяз.: замена запаса, когда он кончился |
| `validateStock(kind, item)` → `null` \| текст | Необяз.: своя проверка элемента запаса |
| `capBonusHours(state)` → число | Необяз.: часы к потолку офлайна от прогресса игры |

`ctx` — `{now, config, rng, mult, links, stock, offline, capBonusHours}`:

- `config`: значения настроек.
- `rng`: генератор `Rng`.
- `mult`: множитель связок на этот отрезок.
- `links`: состояние связок.
- `stock`: `{вид: сколько}`.
- `offline`: `true` при подсчёте отсутствия.
- `report(text)`: только в офлайне.

Хелперы для экрана и бота экспортируйте рядом: `income`, `cost`, `buy(state, cfg)` → bool, а
также `hammer` / `tap` / `click(state, cfg, mult)`. Бот по умолчанию бьёт `hammer|tap|click` три
раза в секунду и покупает `buy`, пока хватает.

Престиж делает движок (`Core.prestige`):

- `run` заново из `sim.init(ctx).run`;
- у слоёв ниже — валюта и `state` в ноль (`count` остаётся);
- валюта слоя растёт на прирост, `count + 1`, `best`, `lastAt`;
- `meta` не трогается;
- затем `sim.onPrestige`.

## Экран — ui/game.js

```js
(async function () {
  const sim = window.GameSim;
  const view = {};
  let g = null;
  function build(game) {
    g = game;
    view.money = ui.h('div', { class: 'g-big' });
    g.el.top.append(view.money);
    g.el.actions.append(ui.h('button', { class: 'g-btn primary', onclick: () => g.act(s => sim.buy(s, g.config)) }, 'Купить'));
    g.el.actions.append(g.ui.prestigeButton('depth'));
  }
  function render(state) { view.money.textContent = g.fmt.live(state.run.coins); }
  await Game.start({ sim, render, init: build });
})();
```

Экран пишется как угодно: DOM, canvas, Preact из `/ui/vendor/preact.js`. Игра обязана полностью
работать по-английски: либо тексты написаны по-английски, либо они по-русски и рядом с `game.json`
лежит `i18n-en.json` (русский текст → английский), покрывающий каждый текст экрана, подпись и
сообщение игры. Русский желателен, но не обязателен, как и любой другой язык: файл
`i18n-<код>.json` рядом с игрой; у игры на английском русский — `i18n-ru.json` (английский текст →
русский). Общие слова Игротеки переводит её собственный `i18n-en.json`, а слова игры — её файл. Состояние меняйте только внутри `g.act`.

### Game.start(opts) → Promise\<g\>

`opts`:

- `sim`: логика (`window.GameSim`).
- `render(state, g)`: рисует; вызывается при изменениях.
- `animate(state, g, dt)`: необяз.; если есть, движок крутит `requestAnimationFrame` с потолком
  `render.fps`.
- `init(g)`: необяз.; строит экран до первого `render`.
- `help`: необяз.; markdown листа «Как играть», иначе `game.help`.
- `root`: необяз.; элемент для рамки, по умолчанию `body`.
- `id`: необяз.; по умолчанию берётся из пути страницы.

Если сохранение новее игры (её откатили), `start` вернёт `{game, readonly: true, newer: true}`
и предложит вернуть копию.

### Объект g

**Данные.**

- `g.id`, `g.game` (манифест), `g.sim`.
- `g.state`: живое состояние. Читайте сколько угодно, меняйте в `act`.
- `g.config`: значения настроек; обновляются при смене настроек.
- `g.rng`: генератор игры, зерно сохраняется.
- `g.layout`: `'narrow' | 'mid' | 'wide'` (так же и `data-layout` на `<html>`).
- `g.readonly`: `true` в `preview.html` и когда сохранение новее игры.
- `g.fresh`: новая игра.
- `g.features`: возможности оболочки.
- `g.el`: зоны `{app, bar, top, field, actions}`.

**Действия.**

- `g.act(fn(state, g), o?)` → то, что вернула `fn`. Сохранение — через `save.debounceMs`, при
  частых нажатиях не реже `save.maxDelayMs`, и сразу пишется зеркало в `localStorage`.
  `o = {now: true, reason, keep}` — сохранить немедленно (трата, важное событие).
- `g.saveNow(reason?, keep?)` → Promise.
- `g.redraw()`.

**Числа.**

- `g.fmt(x, o?)`: «1,23 млн».
- `g.fmt.price(x)`: цена, округление вверх.
- `g.fmt.live(x)`: счётчик, без прыжков.
- `g.fmt.rate(x)`: «+1,2 млн/с».
- `g.fmt.mult(x)`: «×1,5».
- `g.fmt.pct(доля)`: «15 %».
- `g.fmt.time(сек)`.
- `g.fmt.ago(ms, now)`.
- `g.fmt.count(n, 'монета', 'монеты', 'монет')`.
- `g.fmt.plural(n, …)`.
- `g.fmt.say(x)`: для голоса.

**Престиж и ачивки.**

- `g.prestige(layerId, {confirm?})` → Promise `{ok, gain, info}` или `{ok: false, cancelled}`.
  Показывает лист с удержанием `prestige.holdMs`. Перед сбросом делает копию сохранения.
  `confirm: false` — без листа.
- `g.prestigeInfo(layerId)` → `{gain, have, after, can, hint, minGain, bonusBefore, bonusAfter, layer}`.
- `g.ui.prestigeButton(layerId, {label?, primary?})` → кнопка с приростом и меткой «выгодно».
  Движок обновляет её сам.
- `g.achieve(id)` → `true`, если ачивка новая. Показывает плашку, играет звук и сохраняет.
  Повторный вызов ничего не делает: зовите хоть в каждом `render`.

**Звук, вибрация, листы.**

- `g.sfx(name)`: звуки `tap`, `buy`, `deny`, `level`, `chest`, `achievement`, `prestige`,
  `alarm`, плюс свои через `Sfx.add`. Учитывает `sound.on`, наушники и беззвучный режим.
- `g.buzz(name | [мс, пауза, мс, …])`: узоры с теми же именами. Сила — `vibro.scale`, путь —
  `vibro.via`.
- `g.flash(icon, title, text?, ms?)`: плашка внизу на 3 с, нажатия проходят сквозь неё. `icon` — имя `GameIcons`, своя картинка игры строкой SVG или готовый элемент.
- `g.sheet(content, onClose?)` → `close()`: лист снизу. `content` — узел или массив узлов.
- `g.ui.hold(button, ms?, fn)`: срабатывает удержанием.
- `g.ui.menu()`: меню игры.
- `g.ui.achievements()`: лист ачивок.
- `g.ui.line(k, v)`: строка «ключ — значение».
- `g.log(text)`: журнал игры (`data/games/<id>/log.json`).

**Запас контента и связки.**

- `g.stock.take(kind, prefer?)` → Promise\<элемент | null\>. Берёт из запаса на телефоне без сети.
  Когда запас пуст, возвращает `sim.procedural(kind, g.rng)` или `null`. `prefer` — `{поле:
  значение}`: первым идёт элемент, совпавший по большему числу полей (имя для породы зверя),
  при равенстве — старший; не совпал ни один — любой.
- `g.stock.count(kind)`, `g.stock.preparing(kind)`.
- `g.links.mult()`: множитель дохода сейчас (поездка ×2, заряд прогулки ×1,5).
- `g.links.energy()`.
- `g.links.status()`: `{passenger, steps, bet, mult}`.
- `g.links.spend(id)` → Promise. Ответ `{ok: true, left}` или `{ok: false, need}`; при
  выключенной связке — `{ok: false, off: true}`. Эффект траты применяет игра в `act`.

**События и отладка.**

- `g.on(name, fn)` → `off()`. События:
  - `tick (dt)`;
  - `offline (report)` — `{gains, extra, events, multiplied, plan}`;
  - `inbox (r)`;
  - `chest (loot, entry)`;
  - `prestige ({layer, gain})`;
  - `achievement (decl)`;
  - `links (status)`;
  - `resize (layout)`;
  - `hidden`, `visible`.
- `g.debug`: для проверок.
  - `offline(hours)` → Promise `{gains, away, silent}`: сдвигает `lastTick` назад и считает
    отсутствие.
  - `inbox(entries)`.
  - `stale()`.
  - `saved()` → Promise\<seq\>.
- `window.__game === g`.

### Что движок делает сам

- **Такт.** Шаг `1 / tick.hz`, накопитель, не больше `tick.maxStepSec` за кадр. Время —
  настенные часы.
- **Отсутствие.** Разрыв больше `tick.gapSec` (экран спал) идёт офлайном. Часы назад дают ноль.
- **Офлайн.** Потолок `offline.capHours`, отрезки с множителями связок. Подсчёт идёт частями по
  `offline.budgetMs` с полосой.
- **Сводка «Пока вас не было».** Показывается после `offline.minSec` и включает начисления ящика.
  Кнопка «Забрать», при трате `double-offline` ещё «Забрать ×2».
- **Сохранения.** SQLite через `main.js` и зеркало в `localStorage`. При выходе, гашении и
  `pagehide` сохраняет сразу.
  - Устаревший `seq` (игра открыта в другом месте) — отказ и лист «Перезагрузить».
  - Испорченное сохранение — загрузка предыдущего с плашкой.
  - Копии перед престижем, переводом версии и долгим офлайном.
  - Лист «Сохранения» с возвратом.
- **Уведомления.** При уходе с экрана — по `sim.next`.
- **Ящик связок.** Применяет записи при загрузке и каждые `inbox.everySec`.
- **Сессии.** Пишет игровые сессии в общую базу.
- **Ачивки.** Отдаёт их в «Ачивки за жизнь».

### Остальное на экране

- **`Game.ui`** (для своих экранов, каталог берёт их же):
  - `hold`, `sheet`, `line`, `note`, `flash`;
  - `savesSheet(id, {beforeRestore, onRestore})`;
  - `linkSheet(name, id, {onChange})`;
  - `betSheet(id, o)`;
  - `reasonText(reason)`.
- **`GameIcons`.**
  - `el(name)` → `<svg>`; имена кита тоже работают.
  - `svg(name)`, `add(name, '<path …/>')`, `names`.
  - Свои: `coin`, `gem`, `gear`, `chest`, `trophy`, `train`, `steps`, `dice`, `crown`, `sword`,
    `shield`, `pick`, `leaf`, `flask`, `map`, `timer`, `sparkle`, `energy`, `rocket`,
    `factory`, `tree`, `fish`, `skull`, `exit`, `up`, `reset`, `hand`.
- **`Sfx`.**
  - `play(name, volume)`.
  - `add(name, [{type, f: [от, до], d, at, v, a}])` — свой синтез. Или
    `add(name, '/data/games/<id>/assets/x.ogg')`.
  - `names`.
- **Классы `engine/game.css`.**
  - Числа: `g-big` (главное число), `g-rate` (скорость), `g-num` (моноширинные цифры).
  - Кнопки: `g-btn` (от 56 px; варианты `primary` и `small`), `g-btns` (ряд), `g-sub` (вторая
    строка кнопки).
  - Строки и метки: `g-line` / `g-lines`, `g-note`, `g-mark` (метка «выгодно»).
  - Служебные: `g-pick`, `g-progress`, `g-error`.
  - Плашки связок рисует движок.

## Общие файлы движка

Подключаются на экране (`window.Big` …) и в движке
(`require('modules/games/engine/big.js')` или `../engine/…` из папки игры).

- **`Big`** — большие числа `{m, e}` до 10^(9·10^15).
  - Создание и чтение: `of(x, fallback?)`, `parse(text)`, `valid(x)`, `isBig(x)`, `str(x)`
    (строка «1.5e12»), `toNumber(x)`.
  - Арифметика: `add`, `sub`, `mul`, `div`, `pow(x, n)`, `pow10(n)`, `sqrt`, `log10`, `neg`,
    `abs`.
  - Сравнение: `cmp`, `gte`, `gt`, `lt`, `lte`, `eq`, `max`, `min`, `isZero`, `sign`.
  - Округление: `floor`, `ceil`, `round`.
  - Накопитель: `acc(x)`, `addTo(acc, b)` — для частых мелких прибавок.
  - Цены: `costGeom(base, r, owned, count)` (цена `count` штук при росте `r`),
    `maxBuy(budget, base, r, owned)`.
  - Константы: `ZERO`, `ONE`, `MAX_E`.
- **`Fmt`** — числа и время по-русски.
  - `setup({mode, digits, sciFrom, lang})`.
  - Числа: `num(x, {up, live, mode, digits, sciFrom, lang})`, `price`, `live`, `rate`, `mult`,
    `pct(доля)`.
  - Время: `time(сек)`, `ago(ms, now)`.
  - Слова: `plural(n, одна, две, пять)`, `count(n, …)`, `say(x)`, `cardinal(n, feminine?)`,
    `ordinal(n)`.
  - Справочник: `LOCALES`.
  - Режимы `fmt.mode`:
    - `words`: «1,23 млн», до трлн, дальше «4,5e42»;
    - `words-long`: слова до децлн;
    - `sci`;
    - `eng`: порядок кратен трём.
  - Группы разрядов — узкий неразрывный пробел, перед единицей — неразрывный.
- **`Rng`.**
  - `make(seed)` → `{next, int(a, b?), range(a, b), chance(p), pick(list), weighted(list,
    weight), shuffle(list), state(), fork(label)}`.
  - `hash(text)`, `seed()`.
  - mulberry32. `fork` — независимый генератор для побочного (сундук, день), основной не
    сдвигается.
- **`GameArt`** (`engine/art.js`) — картинки, все в SVG, поэтому чёткие на любом размере.
  Подключите `<script src="/modules/games/engine/art.js">` после `rng.js`.
  - **Спрайты по зерну.** `sprite({seed, mask, hue, sat, light, accent, colors, density, eyes,
    frames})` → `{w, h, frames, colors}`. Маска задаёт, где может быть тело. Зерно заполняет её,
    левая половина отражается вправо, вокруг идёт обводка, тона берутся из одного `hue`.
    Одно зерно даёт один и тот же спрайт, зёрна не кончаются.
    - Маски (`GameArt.masks`, описания в `GameArt.MASKS`): `invader`, `ship`, `beast`, `slime`,
      `bug`, `ghost`, `robot`, `plant`, `blade`, `potion`, `gem`. Своя маска — объект
      `{rows: ['..-+#', …], move}`: строки — левая половина и средний столбец; `.` пусто,
      `-` тело или пусто, `+` тело или обводка, `#` тело, `o` обводка, `e` глаз или деталь.
      Строки от `move` и ниже меняются между кадрами: лапы, хвосты, пламя.
    - `svg(sp, {frame, animate, period})` — разметка; с `animate` кадры сменяются сами.
    - `draw(ctx, sp, x, y, cell, frame)` — прямо на canvas, `cell` пикселей на клетку.
      Для частой отрисовки нарисуйте спрайт один раз в свой маленький canvas.
  - **Облик от модели** (гибрид): модель описывает, генератор рисует.
    `lookCheck(look)` → `null` или причина; `fromLook(look, seed)` → спрайт. Облик —
    `{mask, hue, accent?, light?: 'dark' | 'mid' | 'pale'}`; в `seed` дайте имя или id, чтобы
    два врага одного облика различались.
  - **SVG от модели.** `clean(markup, {size, palette, max, shapes})` → `{svg}` или `{error}`.
    Проходят только фигуры (`path`, `circle`, `ellipse`, `rect`, `line`, `polyline`, `polygon`,
    `g`) и градиенты. Скрипты, `style`, `image`, `use`, ссылки, `on*`, текст и цвета вне
    палитры отсекаются. Результат собран заново из прочитанного, а не передан как был.
  - **Показ.** `el(sprite | svg, {size, cls, alt, animate})` → `<img>`; `url(svg)` →
    `data:`-адрес для `<img>` или `Image` на canvas. Показывайте SVG от модели только так, не
    через `innerHTML`.
  - **Для промптов.** `lookRules()`, `svgRules({size, palette, shapes})`. Движок сам добавляет
    их в запрос, если в схеме запаса есть поле `look` или `svg`.
- **`Core`** — логика над данными; движок и проверки зовут её, игре обычно хватает `g`.
  - Настройки: `SETTINGS`, `schema(gameConfig, game)`, `config(schema, hub, owner)`,
    `coerce(item, v)`.
  - Состояние: `newState(game, sim, ctx)`, `complete(state, game)`, `validate(state, game)`,
    `pick(candidates, game, sim)`, `card(sim, state, cfg)`.
  - Офлайн: `plan(lastTick, now, cfg, capBonusHours)`, `cut(from, to, segments)`,
    `runner(sim, state, game, plan, pieces, makeCtx, cfg)` → `{step(budgetMs), progress(),
    report()}`, `runOffline(…)` → report.
  - Валюты: `currencies(state, game)`, `diff(before, after)`.
  - Престиж: `prestigeInfo(sim, game, state, layerId, cfg)`,
    `prestige(sim, game, state, layerId, ctx)`.
  - Ящик: `applyInbox(state, game, sim, entries, ctx)`, `inboxClean(entries, applied, now, days)`.
  - Ачивки: `achieve(state, game, id, now)`, `unreported(state, reported)`,
    `lifeItems(game, state, ids, share)`.
  - Запас: `stock.{count, take, needs, expire, norm, schema, check}`.
  - Сохранения и уведомления: `retention(rows, now, keep, dailyDays)`, `inQuiet`, `afterQuiet`,
    `nextNotify`.
  - Мелочи: `get(o, 'a.b')`, `set(o, 'a.b', v)`, `clone`, `find(list, id)`, `dayKey(ms)`,
    `minutes('ЧЧ:ММ')`, `validId(id)`.
  - Код: `strip(code)`, `lint(code, strict)` → `[{line, rule}]`, `offlineScan(files)`.
  - Константы: `VERSION`, `FORMAT`, `RESERVED`.

## Движок Игротеки — modules/games/main.js

Экран зовёт его через `axiko.call('modules/games/main.js', имя, …)`, агент — через
`js_eval` / `require`. Всё, что пишет, — только в `data/games/`.

- **Игры.** `games()`, `game(id)`, `catalog()`, `catalogSet('pin' | 'archive' | 'order' | 'trial', id, value)`,
  `remove(id)` (папка уходит в `modules/games/archive/`, сохранения остаются).
- **Сохранения.**
  - `boot(id)`.
  - `save(id, seq, json, reason, keep)` → `{ok}` или `{ok: false, stale}`.
  - `saves(id)`, `older(id, beforeSeq, limit)`, `restore(id, rowId)`, `reset(id)`.
  - `exportSave(id)`, `importSave(id, json)`.
  - `markBroken(id, seq, error)`, `prune(id)`.
- **Настройки.** `settings(id | null)`, `setSetting(id | null, key, value | null)`.
- **Связки.**
  - `links(id?)`, `segments(id, from, to)`, `spend(id, spendId)`.
  - `passenger(on)`: «Я еду» / «Я не еду».
  - `bet('offers' | 'place' | 'custom' | 'settle', arg)`.
  - `stepsPermission()`, `linkSheet(name, id)`.
- **Ящик.** `inboxAdd(id, {id, op: 'add' | 'take' | 'chest' | 'stat', currency, amount | percent | ofTake, mult, stat, reason})`,
  `inbox(id)`, `inboxClean(id, applied)`.
- **Запас.** `stockStatus(id)`, `stockTake(id, kind)`, `stockFill(force)`.
- **Прочее.**
  - `report(id)`: ачивки в «Ачивки за жизнь».
  - `planNotify()`.
  - `session(id, ms, from, to)`.
  - `log(id, text)`, `journal(id)`.
  - `offlineCheck(id)` → `{ok, hits}`.
  - `audio()`, `vibrate(pattern)`, `screenMode({portrait, keepOn})`, `features()`.
  - `start()`: задачи и подписки, автозапуск по `"autostart": true` в `module.json`.

## Связки с жизнью

Связки живут в `modules/games/links/` и работают без открытой игры. Подробности —
`docs/games/passenger.md`, `docs/games/steps-fuel.md` и `docs/games/daily-bet.md`.

- **«Пассажир».** Поездку замечает по отсутствию сети, тряске акселерометра, спуску под землю
  (барометр), Wi-Fi метро и кнопке «Я еду». В поездке доход ×`passenger.mult` — и на экране
  (`g.links.mult()`), и в офлайне (отрезки). За поездку от `passenger.chestMin` минут даёт
  сундук через ящик.
- **«Шаги — топливо».** Энергия — общая на все игры: 1 за `steps.perSteps` шагов, не больше
  `steps.dailyMax` в день. Заряд прогулки умножает доход. Траты объявляет `game.json`.
- **«Ставка на день».** Владелец ставит долю валюты (`bettable`) или энергию на условие дня
  (шаги, сон, экран). Расчёт вечером или утром; выигрыш приходит через ящик.

Движок не правит сохранение игры. Всё, что приходит от связок, лежит в
`data/games/<id>/inbox.json`, и экран применяет это сам ровно один раз (`meta.applied`).

## Запас контента на поездку

Объявите вид в `game.json` (`stock`) и напишите `prompts/<kind>.md`. В нём: задание (`{count}`
заменяется числом) и схема элемента в блоке ` ```schema `:

````
Придумай {count} слухов, которые шахтёры пересказывают в забое.

```schema
{"key": "title", "fields": {
  "title": {"type": "string", "min": 3, "max": 30},
  "text": {"type": "string", "min": 10, "max": 90}
}}
```
````

- **Схема.**
  - Типы полей: `string` (`min`, `max`, `oneOf`), `number` (`min`, `max`), `array` (`max`).
  - Картинки:
    - `look` — облик для генератора спрайтов (`GameArt.fromLook`). Дёшево: модель выбирает
      форму и цвета, рисует генератор.
    - `svg` (`size`, по умолчанию 64; `palette` — список `#rrggbb`; `shapes`; `max`; `style` —
      свои слова о стиле, чтобы значки модели совпадали с рисованными) — SVG,
      который модель рисует сама. Хранится уже очищенным (`GameArt.clean`). Если поле
      необязательное и картинка не прошла проверку, она просто выбрасывается, а элемент
      остаётся (так же с необязательным `look`). Значок от модели стоит около 1–2,5 тыс. знаков ответа, поэтому просите его
      только там, где картинка должна изображать именно названную вещь.
    - У `svg` ещё `animate: true` — можно SMIL-анимацию (мерцание, переливы, искры; не
      быстрее 0,3 с за цикл, до `animations` штук, по умолчанию 16) и `effects: true` — свечение
      (`filter` с `feGaussianBlur` и `feMerge`). Без них движок эти части молча выбрасывает.
      Анимация играет и в `<img>`; при «уменьшить движение» показывайте `GameArt.still(svg)`.
      Рисунок с градиентами и фильтрами вставляйте через `<img src=GameArt.url(svg)>`, а не
      `innerHTML`: у двух рисунков на странице совпадут `id`.
    - Правила рисования и описания облика движок сам добавляет в запрос.
  - `"promptFrom": "art.js#prompt"` в объявлении запаса: запрос собирает код игры (файл в её
    папке, функция получает `{kind, want, local, base}` — `base` это текст файла промпта) и
    возвращает текст в том же формате, с блоком `schema`. Так в запрос попадает то, что
    происходит сейчас: образцы для художника, следующие уровни, что уже нарисовано.
  - Взять строго подходящий: `main.stockTake(id, kind, {поле: значение}, true)` на стороне
    движка — только элемент, совпавший по всем полям, иначе `null` (рисунок ровно этого уровня).
  - **Модель-художник серии** — `"artist"` в объявлении запаса (`engine/series.js`): бесконечный
    ряд картинок по номерам (клинки слияния, ранги, этажи, существа эволюции). Своего кода
    почти не нужно:
    ```json
    {"kind": "blades", "title": "Рисунки клинков", "min": 3, "target": 6, "batch": 3,
     "prompt": "prompts/blades.md",
     "artist": {"key": "level", "base": "sim.js#artBase", "content": "content/blades.json"}}
    ```
    - `base` — функция игры `(n, content) → {svg, title?}`: простой рисунок номера (образец для
      художника) и готовое имя, если оно есть. `key` — числовое поле номера в схеме промпта.
      Ещё по желанию: `keep` (какие поля хранить, по умолчанию title, text, theme, svg),
      `previous` (сколько прежних рисунков идёт образцом, 2), `noun` («Уровень», «Ранг»).
    - Направление каждого номера художник придумывает сам, списка тем нет. Недавние темы (поле
      `theme` нарисованного) идут в запрос подсказкой для разнообразия; повторы не запрещены.
    - `nods: true` — отсылки к фильмам, играм, аниме разрешены (криперовский меч, клинок
      «Доктора Кто»): снимает с этого вида правило «только оригинальное». Владелец
      переключает это настройкой `art.<kind>.nods`.
    - Движок сам: добавляет настройки `art.<kind>.on / animate / ahead / recent` (группа
      «Рисунки»), собирает запрос (номера рядом с рекордом, их базы, рисунок предыдущего номера)
      и держит запас на `ahead` номеров выше рекорда.
    - Экран: `const r = await g.art.sync(kind, рекорд, [номера на экране])` →
      `{on, animate, items: {n: {title, text, theme, svg}}}`; нарисованные номера переходят из
      запаса в `data/games/<id>/art-<kind>.json`, вне сохранения. Показ:
      `g.art.img(svg, {cls, still: !r.animate})` → `<img>` (сам учитывает «уменьшить движение»).
      Номер без рисунка игра рисует своей базой. Подключите `engine/art.js` в `index.html`.
    - Промпт художника пишите так, чтобы темы менялись смело и не обязательно всерьёз
      (рыбный, деревянный, футуристичный…), а каждый номер был богаче предыдущего.
    - То же вне Игротеки — `require('lib/art').series(...)` (см. `agent/api.md`).
  - `required: false`: поле необязательное.
  - `key`: поле, по которому ловится повтор.
- **Что ещё отсекается:** эмодзи и всё, что не прошло `sim.validateStock`. Отвергнутое уходит
  в журнал игры.
- **Когда пополняется:** задача `games:stock` (каждые `stock.everyMin`), появление сети,
  зарядка, и за `stock.beforeTripMin` до обычной поездки.
- **Чем пополняется:** роль модели `games.stock`. Без сети, если владелец разрешил
  (`stock.local`), — `games.local` на телефоне.
- **Что делает игра:** берёт `await g.stock.take(kind)` и всегда умеет без запаса — через
  `sim.procedural` или встроенный `content/*.json`.

## Инструкция игры — guide.md

Необязательный `modules/games/<id>/guide.md`: подробная инструкция в разделах (`## …`). Когда файл есть, в меню игры появляется пункт «Инструкция»; она открывается поверх игры, с оглавлением, таблицами, заметками и схемами mermaid. У Игротеки своя — `modules/games/guide.md`, кнопка в шапке каталога.

- **Что внутри:** цель, управление, ресурсы и на что их тратить, престиж (когда выгоден, что теряется и что остаётся), связки с жизнью, неочевидные приёмы.
- **Подача:** таблицы цен и бонусов, схема `flowchart` «откуда что берётся». Цифры — из `config.json`: владелец может их поменять, поэтому пишите «по умолчанию».
- **Отличие от `help`:** короткая справка `help` из `game.json` остаётся («Как играть»), а `guide.md` — подробная инструкция для тех, кто хочет разобраться.
- **Формат:** весь синтаксис описан в `agent/handbook.md`, «Инструкция модуля».

## Проверки — checks.js игры

```js
var kit = require('../engine/checks-kit.js');
var checks = kit.standard(__dirname, { firstBuySec: 10, prestigeTrips: [3, 6] });
for (var name in checks) exports[name] = checks[name];
exports['моя проверка'] = function (t) { /* t.ok, t.equal, t.throws, t.skip, t.wait, t.screen */ };
```

**`kit.standard(dir, o)`** — стандартные проверки:

- «новая игра проходит проверку»;
- «сохранение туда и обратно»;
- «старые сохранения читаются (fixtures)»;
- «офлайн 12 ч: формула и шаги совпадают» (до 1 %);
- «темп: три дня по две поездки»;
- «без сети»;
- «экран открывается при 344 и 690».

Опции `o`:

- **Свой ход бота:** `play(state, ctx, sim, taps)` → сколько куплено. Нужен, если у игры нет
  `hammer|tap|click` и `buy`.
- **Темп.**
  - `firstBuySec`: по умолчанию 10.
  - `prestigeTrips`: по умолчанию `[3, 6]`.
  - `pace: false`: темп проверяет своя проверка.
- **Бот.**
  - `taps`: нажатий в секунду, 3.
  - `hz`: тактов в секунду, 1.
  - `days`: 3.
  - `tripMin`: 25.
  - `gaps`: перерывы, мс.
- **Офлайн.**
  - `setupSec`: сколько бот играет перед проверкой офлайна, 300.
  - `zeroOffline: true`: игра без офлайн-дохода.

Вспомогательное: `kit.bot(game, sim, cfg, o)` → `{firstBuySec, prestiges: [{trip, gain}], trips,
state}`, `kit.offlineAgree(game, sim, cfg, setupSec, play)`, `kit.defaultPlay`,
`kit.configOf(game, dir)`, `kit.idOf(dir)`.

Проверки движка (`module_check('games')`) копируют заготовку как `zz-check` и проверяют:

- каталог;
- открытие игры;
- сохранение после перезагрузки;
- сводку после пропуска времени;
- престиж;
- отказ старого `seq`;
- адреса в сети.

Затем прогоняются `checks.js` каждой установленной игры. После себя проверки всё убирают.

## Отличия от описания

Чем сделанное отличается от `docs/modules/games.md`:

- **Файлы и расположение.**
  - Рамка просмотра лежит в `ui/preview.html`. Адрес из описания `engine/preview.html` её
    перенаправляет. `w=both` показывает обе ширины рядом.
  - Добавлены файлы, которых нет в раскладке:
    - `links/sensors.js` (датчики «Пассажира» и шагов);
    - `stock.js` (запас контента);
    - `engine/checks-kit.js` (стандартные проверки игры);
    - `i18n-en.json` (английские тексты Игротеки; у каждой игры такой же файл со словами
      игры — см. «Тексты» выше).
  - «Удалить» не стирает папку: она уходит в `modules/games/archive/<id>-<дата>-…/`, сохранения
    остаются.
  - Заготовка («Искры») служит и игрой для проверок движка: её копия `zz-check`.
- **Логика игры (sim).**
  - Функции получают больше аргументов:
    - `prestigeGain`, `summary`, `next` и `chest` — ещё и `cfg`;
    - `onPrestige` — `(state, layerId, before, ctx)`.
  - Добавлены `sim.bonus` (для листа престижа), `sim.capBonusHours` и `ctx.report`.
  - `meta.seed` — текущее состояние генератора: он продолжается, а не начинается заново.
- **Сохранения.**
  - Таблица `saves` с уникальным индексом `(game, seq)`.
  - В ящике есть операция `stat` (счётчик `meta.stats`, им «Пассажир» считает поездки).
  - `hiddenAt` отдельно не хранится: хватает `lastTick` и `savedAt`.
  - Сохранение старой версии сначала проверяется только как состояние, затем переводится
    `migrate`, затем проверяется полностью.
  - Импорт сохранения тоже переводит старые версии.
- **Платформа.**
  - Проверка «без сети» пропускает пространства имён `w3.org` (`xmlns` в SVG).
  - Вибрация:
    - `vibro.via: auto` идёт узором через движок: `main.vibrate` сам зовёт
      `VibrationEffect.createWaveform`, а если не вышло — одним импульсом `axiko.vibrate(ms)`.
      Правка `lib/axiko.js` для этого не нужна;
    - `web` — через `navigator.vibrate`.
  - Уведомление к сроку — `job(…, {once})`, если оболочка это умеет (`shell.features`). Иначе
    `{at: 'ЧЧ:ММ'}` и `unjob`.
  - Ориентация и «не гасить экран» — через `ui(fn)` в `main.screenMode`, а не через
    `axiko.eval`.
  - Цикл «Пассажира» крутится в `spawn`; задача раз в 15 минут подстраховывает его после
    перезапуска.
- **Связки.**
  - «Ставка на день» держит список ожидающих расчёта ставок (сон проверяется утром).
  - Чистая функция начисления шагов называется `givePure`. `give(hub, amount)` — версия для
    телефона.
- **Числа.**
  - У `Fmt` есть язык `en`, он включается по языку интерфейса.
  - `Fmt.pct` принимает долю (0,15 → «15 %»).
  - Игры дня получают `id` вида `daily/ГГГГ-ММ-ДД`.
- **Поля, которых нет в описании.**
  - `Game.start`: `opts.init` и `opts.help`. `id` можно не указывать: он берётся из пути
    страницы.
  - `game.json`: `lifeText` у ачивки, `lose` / `keep` у слоя престижа, `whole` у валюты,
    `help`.
- **Ключи ачивок связок:** `games/passenger/*` и `games/bet/*`.
- **Проверка кода.** `for (let …)` запрещён и в нестрогой проверке. Rhino связывает `let` цикла
  один раз на все обороты.
- **Обход игр.** Проверки движка прогоняют `checks.js` каждой установленной игры.
  Невыполненные собираются в один отчёт.
- **Строка о движке в `agent/handbook.md`** и дополнения оболочки (`job once`, вибрация узором,
  `app.visible`, `off()`, кнопки уведомлений) — работа оболочки, не этого модуля. Игротека
  проверяет их через `native('shell.features')` и без них работает по-старому.
