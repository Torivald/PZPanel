[⬅ К главному README](../../README.md) · [Описание](README.md) · [Установка](INSTALL.md) · [Настройки](CONFIG.md) · [Моды](MODS.md) · [Карта](MAP.md) · [Docker](DOCKER.md) · [Устройство](ARCHITECTURE.md) · [Проблемы](TROUBLESHOOTING.md)

# Установка

Готовые архивы — на странице [Releases](https://github.com/Torivald/PZPanel/releases): `PZPanel_<версия>_Windows.zip` и `PZPanel_<версия>_Linux.zip`. Панели не нужно ничего ставить: Java и библиотеки берутся из самого сервера.

## Windows (сервер на этом ПК)

1. Папка `PZPanel` должна лежать **внутри** папки сервера: `...\steamapps\common\Project Zomboid Dedicated Server\PZPanel\`.
2. Двойной клик по **`PZPanel.bat`**. Откроется браузер: http://127.0.0.1:8766/
3. Окно консоли панели не закрывайте, пока пользуетесь панелью. Сервер, запущенный из панели, пишет консоль прямо в неё. Если закрыть панель, сервер продолжит работать (но ответить ZombieBuddy уже будет негде — тогда запускайте сервер через `StartServer64.bat`).

Если сервер запущен не из панели (через `StartServer64.bat`), панель его увидит. Команды пойдут через RCON (нужен `RCONPassword` в ini), консоль будет недоступна.

## Linux

### Установка

Скачайте `PZPanel_<версия>_Linux.zip` со страницы [Releases](https://github.com/Torivald/PZPanel/releases), распакуйте и из папки `PZPanel` выполните от пользователя `pzuser` (сервер в `~/pzserver`):

```bash
bash install_panel.sh
```

Скрипт кладёт панель в `~/pzserver/PZPanel/` и создаёт `panel.properties` для Linux.

### Вариант А — панель как служба (рекомендуется)

Панель запускается при загрузке машины, сама стартует сервер, а при остановке службы или перезагрузке сохраняет мир и корректно выключает сервер.

```bash
sudo bash install_panel.sh --service
```

Скрипт отключит старую службу `pzserver` (tmux), если она была включена: двумя способами одновременно сервер запускать нельзя.

```bash
systemctl status pzpanel          # состояние
journalctl -u pzpanel -f          # лог панели и сервера
sudo systemctl restart pzpanel    # перезапуск панели (и сервера: save + quit, затем снова старт)
```

Консоль сервера и вопросы ZombieBuddy — на вкладке «Обзор и консоль».

### Вариант Б — сервер в tmux, панель рядом

Сервер как раньше (`scripts/start.sh` или служба `pzserver`), панель запускается вручную: `~/pzserver/PZPanel/pzpanel.sh`.
Панель увидит сервер и будет работать через RCON. В `panel.properties` поставьте `autoStartServer=false` и `stopServerOnExit=false`.
Консоль сервера в этом варианте — только в tmux (`tmux attach -t pz`).

### Как открыть панель с вашего ПК

Панель слушает только `127.0.0.1` на сервере — снаружи её не видно. Откройте SSH-туннель с вашего ПК:

```bash
ssh -L 8766:127.0.0.1:8766 pzuser@IP_СЕРВЕРА
```

и, пока соединение открыто, заходите в браузере на **http://127.0.0.1:8766/**.
На Windows это работает в PowerShell (OpenSSH встроен) или в PuTTY: Connection → SSH → Tunnels, Source port `8766`, Destination `127.0.0.1:8766`.

Открывать панель в интернет напрямую (`bind=0.0.0.0`) не рекомендуется: в ней нет HTTPS. Если очень нужно — задайте `panelPassword`, откройте порт только для своего IP и перезапустите панель.

## Безопасность

- По умолчанию панель слушает только `127.0.0.1`; снаружи её не видно.
- Доступ через домен или прокси — только с паролем `panelPassword`. В панели нет HTTPS: ставьте перед ней обратный прокси с TLS.
- Каждый запрос на изменение защищён токеном (против CSRF), заголовок Host проверяется (ответ «Forbidden host» — заходите через `127.0.0.1` / `localhost`).

## Сборка из исходников

Нужны **JDK 21+** и **`projectzomboid.jar`** из вашего выделенного сервера (`<папка сервера>/java/projectzomboid.jar`). Файлы игры в репозиторий не входят.

```bash
# только pzpanel.jar
PZ_JAR=/путь/к/projectzomboid.jar bash src/build.sh

# pzpanel.jar + готовые архивы для Windows и Linux в out/
PZ_JAR=/путь/к/projectzomboid.jar bash scripts/package.sh
```

На Windows: `src\build.bat "C:\...\Project Zomboid Dedicated Server\java\projectzomboid.jar"`. Если папка `src` лежит внутри `PZPanel` в папке сервера, путь можно не указывать.

JDK: Linux — `sudo apt install -y openjdk-21-jdk-headless`, Windows — `winget install EclipseAdoptium.Temurin.21.JDK`.
