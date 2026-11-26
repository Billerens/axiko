[English](README.md) · Русский

# Документация проекта Axiko

Здесь описаны действующие контракты проекта и соглашения для разработчиков. Основная версия —
английская; файлы `*.ru.md` содержат дополнительные русские переводы. При изменении контракта
обновляйте обе версии. Описания выпусков размещаются в GitHub Releases, а отчёты проверок,
снимки экранов и эксперименты — в игнорируемых каталогах `build/artifacts/`.

| Тема | Основной документ | Русский перевод |
|---|---|---|
| Слои приложения и runtime | [Architecture](architecture.md) | [Архитектура](architecture.ru.md) |
| Сборка, проверки, соглашения | [Development](development.md) | [Разработка](development.ru.md) |
| Структура модулей, язык, темы, данные | [Module development](modules.md) | [Модули](modules.ru.md) |
| Общий движок и сохранения игр | [Game development](game-development.md) | [Игры](game-development.ru.md) |
| Возможности оболочки и feature detection | [Native contracts](native-contracts.md) | [Контракты оболочки](native-contracts.ru.md) |
| Репозитории и пакеты | [Repository format](marketplace/repository-format-v1.md) | [Формат](marketplace/repository-format-v1.ru.md) |
| Страницы каталога и языки витрины | [Catalog pages](marketplace/catalog-pages.md) | [Каталог](marketplace/catalog-pages.ru.md) |
| Проверка агентом и уровни правил | [Agent review](marketplace/agent-review.md), [review rules](marketplace/review-rules.md) | [Проверка](marketplace/agent-review.ru.md), [правила](marketplace/review-rules.ru.md) |
| Установка, обновления, откат, доверие | [Store implementation](marketplace/implementation.md) | [Установщик](marketplace/implementation.ru.md) |
| Доработки пакетов | [Mods](marketplace/mods.md) | [Моды](marketplace/mods.ru.md) |
| Зашифрованная резервная копия | [Backup](backup.md) | [Резервная копия](backup.ru.md) |
| История кода и общие объекты снимков | [Snapshot storage](snapshot-storage.md) | [Хранение истории](snapshot-storage.ru.md) |
| Редактируемый web-интерфейс | [Web hub](web-hub.md) | [Web-хаб](web-hub.ru.md) |
| Панель системного ассистента и её настройка | [On-screen assistant](assistant.md) | [Ассистент](assistant.ru.md) |
| Маскот, темы и названия | [Brand and mascot contracts](brand.md) | [Маскот](brand.ru.md) |

Документация отдельного пакета находится вместе с ним в
[axiko-store](https://github.com/Billerens/axiko-store). Общие правила выше заменяют прежние
проектные описания отдельных игр и модулей. В приложении остаются подробные русские справки
для его агента: [руководство](../app/src/main/assets/factory/agent/handbook.md),
[API](../app/src/main/assets/factory/agent/api.md),
[справка движка игр](../app/src/main/assets/factory/modules/games/AGENT.md).

Исходники иллюстраций — в [assets/brand](../assets/brand/README.md), видео из README и его
воспроизводимый renderer — в [assets/promo](../assets/promo/README.md). Они не являются
отдельными спецификациями проекта. Лицензии сторонних компонентов и архивы их исходников
остаются в [`app/src/main/assets/licenses`](../app/src/main/assets/licenses).
