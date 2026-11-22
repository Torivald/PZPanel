# Changelog / История изменений

Full release notes / Полные описания выпусков: [Releases](https://github.com/Torivald/PZPanel/releases)

## 1.6.2

- 🇷🇺 Вкладка ZombieBuddy: версии агента сервера и копий мода, скачивание последней версии через SteamCMD и установка одной кнопкой (`ZombieBuddy.jar` по `-javaagent`/рядом с сервером, на Windows и `zbNative.dll`; замороженный мод в `Zomboid/mods`); прежние версии — в `file_backups` и `mods_replaced`
- 🇬🇧 ZombieBuddy tab: versions of the server agent and mod copies, download of the latest version via SteamCMD and one-click install (`ZombieBuddy.jar` per `-javaagent`/next to the server, plus `zbNative.dll` on Windows; the frozen mod in `Zomboid/mods`); old versions go to `file_backups` and `mods_replaced`
- 🇷🇺 «Обновить authors.json»: свежий список доверенных авторов с GitHub ZombieBuddy в `~/.zombie_buddy/authors.json` (если GitHub недоступен — из копии мода)
- 🇬🇧 "Update authors.json": the fresh trusted-authors list from ZombieBuddy's GitHub into `~/.zombie_buddy/authors.json` (from a mod copy if GitHub is unreachable)

## 1.6.1

- 🇷🇺 «Моды на диске»: список модов сервера с размерами, удаление выбранных или всех (`Zomboid/mods` и Мастерская сервера), по желанию — и из списка модов мира
- 🇬🇧 "Mods on disk": the server's mods with sizes, delete selected or all (`Zomboid/mods` and the server Workshop), optionally also from the world's mod list
- 🇷🇺 Прежние версии в `Zomboid/mods_replaced`: удалить или вернуть из панели; автоочистка через `modReplacedKeepDays` дней (30)
- 🇬🇧 Old versions in `Zomboid/mods_replaced`: delete or restore from the panel; auto-cleanup after `modReplacedKeepDays` days (30)

## 1.6

- 🇷🇺 Загрузка мода zip-архивом на вкладке «Моды» (мод не из Мастерской, своя сборка, архив модов для игроков): папки модов ищутся по `mod.info`, ставятся в `Zomboid/mods`, моды включаются; прежняя папка уходит в `Zomboid/mods_replaced`
- 🇬🇧 Upload a mod as a zip archive on the "Mods" tab (a non-Workshop mod, your own build, a players' mod archive): mod folders are found by `mod.info`, installed into `Zomboid/mods` and enabled; an older folder goes to `Zomboid/mods_replaced`
- 🇷🇺 Параметр `modUploadMaxMb` — предельный размер загружаемого архива (по умолчанию 4096 МБ)
- 🇬🇧 `modUploadMaxMb` option — the maximum size of an uploaded archive (default 4096 MB)
- 🇷🇺 Windows: `.bat`-файлы только в ASCII — `PZPanel.bat` больше не ломается из-за кодировки
- 🇬🇧 Windows: `.bat` files are ASCII-only — `PZPanel.bat` no longer breaks because of the encoding

## 1.5

- 🇷🇺 Логи панели: переключатель «Сервер / Панель» в окне консоли, журнал также пишется в `pzpanel.log`
- 🇬🇧 Panel logs: a "Server / Panel" switch in the console window, the log is also written to `pzpanel.log`
- 🇷🇺 Кнопка «Перезапустить панель» (systemd, `PZPanel.bat` или `panelRestart=on` при restart policy контейнера)
- 🇬🇧 "Restart panel" button (systemd, `PZPanel.bat`, or `panelRestart=on` with a container restart policy)
- 🇷🇺 После установки обновления панель можно перезапустить сразу
- 🇬🇧 After installing an update the panel can be restarted right away

## 1.4

- 🇷🇺 Версия панели в боковом меню и кнопка «Что нового» (на языке интерфейса)
- 🇬🇧 Panel version in the sidebar and a "What's new" button (in the interface language)
- 🇷🇺 Кнопка «Проверить обновление»: сверяет версию с GitHub и предлагает установить новую
- 🇬🇧 "Check for updates" button: compares the version with GitHub and offers to install a newer one
- 🇷🇺 Установка обновления одной кнопкой (pzpanel.jar заменяется, прежний — `.bak`)
- 🇬🇧 One-click update install (pzpanel.jar is replaced, the old one kept as `.bak`)
- 🇷🇺 `pz-metrics.sh`: в контейнере показывает потребление всего контейнера, надёжнее находит процесс, пишет диагностику
- 🇬🇧 `pz-metrics.sh`: inside a container shows the consumption of the whole container, finds the process more reliably, prints diagnostics
- 🇷🇺 Исправлены мелкие неточности английского перевода интерфейса
- 🇬🇧 Fixed small inaccuracies in the English interface translation

## 1.3

- 🇷🇺 Автосортировка модов: «исправить порядок» или «по категориям и алфавиту», с предпросмотром
- 🇬🇧 Mod auto-sorter: "fix the order" or "by category and alphabet", with a preview
- 🇷🇺 Кнопка «⬇ Скачать с сервера» у замороженного мода: один мод или архив всех модов
- 🇬🇧 "⬇ Download from server" button on a frozen mod: one mod or an archive of all mods
- 🇷🇺 Хранение архивов: один полный архив, архив одного мода удаляется через 24 ч (`modArchiveTtlHours`)
- 🇬🇧 Archive retention: one full archive, a single-mod archive is deleted after 24 h (`modArchiveTtlHours`)
- 🇷🇺 Язык интерфейса: русский и English, выбор запоминается у каждого пользователя
- 🇬🇧 Interface language: Russian and English, remembered per user
- 🇷🇺 Документация разделена по файлам: `docs/RU` и `docs/EN`; README и чейнджлог на двух языках
- 🇬🇧 Documentation split into files: `docs/RU` and `docs/EN`; bilingual README and changelog

## 1.2

- 🇷🇺 «📌 Закрепить» мод из Мастерской (Linux и Windows), одобрение Java-модов в ZombieBuddy
- 🇬🇧 "📌 Pin" a Workshop mod (Linux and Windows), Java mod approval in ZombieBuddy
- 🇷🇺 Режим `serverMode=remote`: панель и сервер в разных контейнерах, `rconHost`, `pz-metrics.sh`
- 🇬🇧 `serverMode=remote`: panel and server in separate containers, `rconHost`, `pz-metrics.sh`
- 🇷🇺 Закрепление на Windows без настройки (SteamCMD скачивается сам)
- 🇬🇧 Pinning on Windows without setup (SteamCMD is downloaded automatically)
- 🇷🇺 Исходники и упаковка в репозитории, скриншоты в документации
- 🇬🇧 Sources and packaging in the repository, screenshots in the docs

## 1.1

- 🇷🇺 Вкладка «Миры»: создать, сделать активным, сбросить, переименовать, удалить
- 🇬🇧 "Worlds" tab: create, make active, reset, rename, delete
- 🇷🇺 Автобэкап перед опасными действиями, восстановление из бэкапа
- 🇬🇧 Automatic backup before dangerous actions, restore from a backup
- 🇷🇺 Исправлено: бэкап мира захватывал конфиги похожих миров
- 🇬🇧 Fixed: a world backup included configs of similarly named worlds

## 1.0

- 🇷🇺 Первый выпуск: обзор и консоль, нагрузка, карта, игроки, моды, настройки, песочница, файлы, ZombieBuddy, бэкапы
- 🇬🇧 First release: overview and console, load, map, players, mods, settings, sandbox, files, ZombieBuddy, backups
