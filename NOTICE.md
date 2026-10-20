# Сторонние компоненты

- **pzmap2dzi** — https://github.com/cff29546/pzmap2dzi (MIT). Формат файлов карты `lotheader`/`lotpack`
  в `src/pzpanel/MapData.java` разобран по его коду. Сам pzmap2dzi в репозиторий не входит:
  `dist/windows/render_top_view.ps1` скачивает зафиксированную версию при запуске (подложка «вид сверху»).
- **Project Zomboid** (The Indie Stone). Панель компилируется и работает с `projectzomboid.jar` из
  установленного выделенного сервера (оттуда берутся Jackson и sqlite-jdbc). Файлы игры в репозиторий не входят
  и распространять их нельзя — у каждого свой сервер.
