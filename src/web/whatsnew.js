// «Что нового» по версиям: тексты на двух языках, показывается нужный (window.LANG). Новая версия — запись сверху.
window.PZ_WHATSNEW = {
  '1.6.1': {
    ru: [
      'Карточка «Моды на диске» на вкладке «Моды»: список модов сервера с размерами, удаление выбранных или всех (Zomboid/mods и скачанные сервером предметы Мастерской); удалённые моды можно сразу убрать из списка модов мира',
      'Прежние версии модов (mods_replaced): удалить или вернуть вместо текущей; старше 30 дней удаляются сами (modReplacedKeepDays)',
    ],
    en: [
      'A "Mods on disk" card on the "Mods" tab: the server\'s mods with sizes, delete selected or all of them (Zomboid/mods and Workshop items downloaded by the server); deleted mods can be removed from the world\'s mod list right away',
      'Old mod versions (mods_replaced): delete or restore in place of the current one; older than 30 days are deleted automatically (modReplacedKeepDays)',
    ],
  },
  '1.6': {
    ru: [
      'Загрузка мода zip-архивом прямо на вкладке «Моды»: мод не из Мастерской, своя сборка, скачанный вручную предмет или архив модов для игроков. Панель сама находит в архиве папки модов, кладёт их в Zomboid/mods и включает моды; обновлять их из Steam сервер не будет',
      'Прежняя папка мода с тем же именем не удаляется, а переносится в Zomboid/mods_replaced',
      'Windows: PZPanel.bat и другие .bat больше не ломаются из-за кодировки («\'exist\' is not recognized…»)',
    ],
    en: [
      'Upload a mod as a zip archive right on the "Mods" tab: a non-Workshop mod, your own build, a manually downloaded item or a players\' mod archive. The panel finds the mod folders in the archive, puts them into Zomboid/mods and enables the mods; the server will not update them from Steam',
      'An older mod folder with the same name is not deleted but moved to Zomboid/mods_replaced',
      'Windows: PZPanel.bat and the other .bat files no longer break because of the encoding ("\'exist\' is not recognized…")',
    ],
  },
  '1.5': {
    ru: [
      'Логи панели: в окне консоли переключатель «Сервер / Панель» показывает журнал самой панели (он же пишется в pzpanel.log)',
      'Кнопка «Перезапустить панель» (systemd, PZPanel.bat или panelRestart=on при restart policy контейнера)',
      'После установки обновления можно сразу перезапустить панель',
    ],
    en: [
      'Panel logs: a "Server / Panel" switch in the console window shows the panel\'s own log (also written to pzpanel.log)',
      '"Restart panel" button (systemd, PZPanel.bat, or panelRestart=on with a container restart policy)',
      'After installing an update you can restart the panel right away',
    ],
  },
  '1.4': {
    ru: [
      'Версия панели в боковом меню и кнопка «Что нового» со списком изменений',
      'Кнопка «Проверить обновление»: панель смотрит последний релиз на GitHub и предлагает установить его',
      'pz-metrics.sh: в контейнере показывает потребление всего контейнера (cgroup), надёжнее находит процесс сервера и пишет диагностику',
      'Установка обновления одной кнопкой: скачивается архив релиза, подменяется pzpanel.jar (прежний сохраняется как pzpanel.jar.bak), затем остаётся перезапустить панель',
    ],
    en: [
      'Panel version in the sidebar and a "What\'s new" button with the list of changes',
      '"Check for updates" button: the panel looks at the latest GitHub release and offers to install it',
      'pz-metrics.sh: inside a container it shows the consumption of the whole container (cgroup), finds the server process more reliably and prints diagnostics',
      'One-click update: the release archive is downloaded, pzpanel.jar is replaced (the old one is kept as pzpanel.jar.bak), then you only need to restart the panel',
    ],
  },
  '1.3': {
    ru: [
      'Автосортировка модов: «исправить порядок» или «по категориям и алфавиту», с предпросмотром',
      'Кнопка «⬇ Скачать с сервера» у замороженного мода: один мод или архив всех модов',
      'Хранение архивов: один полный архив, архив одного мода удаляется через 24 ч',
      'Язык интерфейса: русский и English, выбор запоминается у каждого пользователя',
    ],
    en: [
      'Mod auto-sorter: "fix the order" or "by category and alphabet", with a preview',
      '"⬇ Download from server" button on a frozen mod: one mod or an archive of all mods',
      'Archive retention: one full archive, a single-mod archive is deleted after 24 h',
      'Interface language: Russian and English, remembered per user',
    ],
  },
  '1.2': {
    ru: [
      '«📌 Закрепить» мод из Мастерской (Linux и Windows), одобрение Java-модов в ZombieBuddy',
      'Режим serverMode=remote: панель и сервер в разных контейнерах',
      'Закрепление на Windows без настройки (SteamCMD скачивается сам)',
    ],
    en: [
      '"📌 Pin" a Workshop mod (Linux and Windows), Java mod approval in ZombieBuddy',
      'serverMode=remote: panel and server in separate containers',
      'Pinning on Windows without setup (SteamCMD is downloaded automatically)',
    ],
  },
  '1.1': {
    ru: ['Вкладка «Миры»: создать, сделать активным, сбросить, переименовать, удалить', 'Автобэкап перед опасными действиями, восстановление из бэкапа'],
    en: ['"Worlds" tab: create, make active, reset, rename, delete', 'Automatic backup before dangerous actions, restore from a backup'],
  },
  '1.0': {
    ru: ['Первый выпуск: обзор и консоль, нагрузка, карта, игроки, моды, настройки, песочница, файлы, ZombieBuddy, бэкапы'],
    en: ['First release: overview and console, load, map, players, mods, settings, sandbox, files, ZombieBuddy, backups'],
  },
};
