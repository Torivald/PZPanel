[⬅ К главному README](../../README.md) · [Описание](README.md) · [Установка](INSTALL.md) · [Настройки](CONFIG.md) · [Моды](MODS.md) · [Карта](MAP.md) · [Docker](DOCKER.md) · [Устройство](ARCHITECTURE.md) · [Проблемы](TROUBLESHOOTING.md)

# Техническое описание

## Устройство

Панель — один процесс Java без сторонних зависимостей: HTTP-сервер на `com.sun.net.httpserver`, интерфейс — статические файлы (`index.html`, `app.js`, `style.css`), вшитые в `pzpanel.jar`. SQLite (аккаунты, игроки) и JSON берутся из `projectzomboid.jar` сервера, поэтому панель собирается именно против него (`javac --release 21`).

| Путь | Что там |
|---|---|
| `src/pzpanel/Main.java` | HTTP-сервер, маршруты `/api/...`, авторизация, токен |
| `src/pzpanel/ServerManager.java` | запуск, остановка, перезапуск, консоль, режим `serverMode=remote` |
| `src/pzpanel/Mods.java` | моды: чтение `mod.info`, порядок, Мастерская, «Закрепить» |
| `src/pzpanel/ModTools.java` | автосортировка модов, архивы модов для игроков и их очистка |
| `src/pzpanel/Worlds.java`, `Backups.java` | миры и бэкапы |
| `src/pzpanel/Configs.java`, `Config.java` | ini, песочница, `panel.properties` |
| `src/pzpanel/Players.java`, `Rcon.java`, `Stats.java` | игроки, RCON, нагрузка |
| `src/pzpanel/MapData.java` | данные карты (здания, улицы, зоны) из файлов игры |
| `src/web/` | интерфейс: `index.html`, `app.js`, `style.css`, `i18n.js` + `lang_en.js` (перевод) |
| `dist/windows/`, `dist/linux/` | запускающие скрипты, служба systemd, установщик |
| `scripts/package.sh` | сборка релизных архивов (версия — `Main.VERSION`) |

## HTTP API

Все запросы — JSON; изменяющие запросы (POST) требуют заголовок `X-PZP` с токеном страницы. Основные маршруты:

| Группа | Маршруты |
|---|---|
| Сервер | `/api/status`, `/api/stats`, `/api/server/start|stop|restart|save`, `/api/console`, `/api/rcon` |
| Игроки | `/api/players` |
| Карта | `/api/map/info`, `/api/map/data`, `/api/map/buildings`, `/api/map/building` |
| Моды | `/api/mods`, `/api/mods/sort`, `/api/mods/frozen`, `/api/mods/pack`, `/api/mods/archive/delete`, `/modarchive/<имя>` (скачивание) |
| Мастерская | `/api/workshop/search|item|details|pin|collection` |
| Конфиги | `/api/config/ini`, `/api/config/sandbox`, `/api/file`, `/api/zb` |
| Миры и бэкапы | `/api/worlds[/activate|create|wipe|rename|delete]`, `/api/backups[/create|restore|delete]`, `/backup/<имя>` |
| Панель | `/api/settings` |

## Автосортировка

`ModTools.sort(список, режим)`: устойчивая топологическая сортировка (алгоритм Кана) по рёбрам `require`, `loadModAfter`, `loadModBefore`. Режим `fix` сохраняет текущий порядок, режим `categories` сначала ранжирует моды по эвристической категории (библиотеки → ресурсы → карты → транспорт → код → одежда → интерфейс → прочее → переводы), затем по алфавиту, после чего применяется та же проверка зависимостей. Циклы и зависящие от них моды добавляются в конец и возвращаются в предупреждениях.

## Архивы модов

Папка `PZPanel/mod_archives/`. Сборка идёт в фоне (задача с прогрессом), файл пишется как `.part` и переименовывается по готовности. Очистка (каждые 10 минут и при сборке): остаётся один новейший полный архив `PZ_mods_all_*.zip`; архивы одного мода `PZ_mod_*.zip` старше `modArchiveTtlHours` (24 ч) удаляются; брошенные `.part` удаляются. Скачивание — `/modarchive/<имя>`, только для авторизованных, имя проверяется по шаблону.

## Язык интерфейса

Исходные тексты — русские. Для английского `i18n.js` на лету подменяет русские фразы по словарю `lang_en.js` (текст страницы, атрибуты, `confirm`/`prompt`/`alert`, сообщения сервера); выбор хранится в `localStorage` браузера (`pzp.lang`). Новая строка интерфейса добавляется в словарь парой «русский — английский»; `{}` — подставляемое значение.

## Версии и выпуск

Версия — константа `Main.VERSION`. `scripts/package.sh` собирает `out/PZPanel_<версия>_{Windows,Linux}.zip` (панель, скрипты запуска, исходники, README).
