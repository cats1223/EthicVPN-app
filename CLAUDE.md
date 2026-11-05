# EthicVPN (бывш. EthicVPN) — правила проекта

- Название для пользователя — **EthicVPN** (с v1.5.0 / windows-v1.2.0). Внутренние имена (пакет com.ethic.vpn,
  bundle id, EthicVPN.exe, папки данных, AppId установщика, репозиторий) не менять — иначе обновление не встанет поверх.
- Сервер 213.108.23.40 (Aeza): панель mikan, домены ethicpanel.duckdns.org (панель) и ethicvpn.duckdns.org (VPN);
  порт 80 держит Caddy мессенджера Ethic, сертификаты mikan — через `scripts/server/mikan-acme-proxy.sh`.
  Блокировщик торрентов mikan банит пользователя целиком — для своих он выключен.

- **Сборка — всегда через GitHub Actions** (`.github/workflows/build.yml`). У владельца нет Mac и сейчас нет Android-устройства:
  не предлагать локальную сборку в Xcode/Android Studio, а пушить в ветку и отдавать артефакты из Actions.
- iOS подписывается в CI секретами репозитория (см. README → «Подпись iOS в GitHub»); сертификаты и профили никогда не коммитить.
- Протокол сервиса: VLESS + REALITY + XHTTP (порт 443, нода принимает только режим stream-one), ключи выдаются ссылкой подписки панели.
  Протокол не менять. Маскировка под www.microsoft.com резалась провайдером — после смены сайта маскировки в панели заработало.
- Панель (mikan) привязывает ключи к устройствам по заголовкам x-hwid/x-device-*; приложения их отправляют при загрузке подписки.
- Проверка ноды с сервера в обход сети клиента: `scripts/server/node-selftest.py <ссылка подписки>`.
- Общая логика (разбор ссылок, конфиг Xray) продублирована в `android/.../core` и `ios/Shared` — менять в обоих местах и в тестах.
- Раздельное туннелирование: `geodata/geoip.dat` (тег RU) и `geodata/geosite.dat` (CATEGORY-RU, CATEGORY-ADS-ALL для
  «Блокировать рекламу») — урезанные списки v2fly, обновляются `scripts/update-geodata.py`. Без них Xray не стартует
  с включённым «Российские сайты напрямую» или блокировкой рекламы.
- Версия приложений берётся из тега релиза (CI → APP_VERSION); по ней работает проверка обновлений.
- Перед релизом дописать раздел `## vX.Y.Z` в `CHANGELOG.md` — он становится описанием релиза. Беты — теги с суффиксом
  (`v1.2.0-beta.3`), публикуются как pre-release и не предлагаются как обновление.
- APK подписывается постоянным ключом из секрета `ANDROID_KEYSTORE_BASE64` (debug-формат: пароль android, alias
  androiddebugkey; отпечаток SHA-256 698e8d29…2bb2). CI кладёт его в файл и передаёт Gradle через ANDROID_KEYSTORE_PATH;
  без него каждая сборка с новым ключом не ставится поверх старой и обновление из приложения не работает.
- iOS-виджет (`ios/Widget`, цель EthicWidget, bundle id `<app>.widget`) не видит настроек VPN: статус он берёт по
  интерфейсу utun с адресом 172.19.0.1, а переключение — `SetVPNIntent` (LiveActivityIntent), который iOS выполняет в
  самом приложении. Подпись в CI (`scripts/ci/ios-sign.sh`) профиль для виджета пока не умеет — IPA подписывают в Feather.
- Windows (`windows/`): WPF + WebView2, интерфейс — веб-страница `windows/EthicVPN/Web` (тот же дизайн и глобус
  MapLibre, что на Android; без WebView2 открывается в браузере в демо-режиме — так проверяется дизайн). Логика —
  `windows/EthicVPN.Core` (порт android/.../core, тесты `EthicVPN.Core.Tests`, идут на Linux). Режимы: системный
  прокси (без прав) и TUN через встроенный в Xray Wintun (`autoSystemRoutingTable`, нужны права администратора —
  приложение перезапускается elevated). Собирается отдельно: `.github/workflows/windows.yml` по пушу в ветку `windows-release` (версия из `windows/VERSION`; пуш тегов из этой среды не проходит), тег
  `windows-vX.Y.Z` (раздел `## windows-vX.Y.Z` в CHANGELOG), релиз с make_latest: false, чтобы телефоны не приняли его
  за своё обновление; Windows ищет обновления только среди тегов windows-v*. Выпускается одним установщиком Inno Setup
  (`windows/installer.iss`, EthicVPN-Setup-X.Y.Z.exe, ставится в профиль без прав) с xray.exe, wintun.dll, geodata, MapLibre.

## Память между сжатиями контекста

- **После каждой очистки/сжатия контекста — первым делом перечитать все сообщения владельца** из полной переписки
  (файлы `~/.claude/projects/*/*.jsonl`), а не полагаться только на краткое содержание:
  ```
  python3 - <<'PY'
  import json,glob,os
  for f in sorted(glob.glob(os.path.expanduser('~/.claude/projects/*/*.jsonl'))):
      for l in open(f):
          try: e=json.loads(l)
          except: continue
          c=e.get('message',{}).get('content') if e.get('type')=='user' else None
          if isinstance(c,list): c=' '.join(x.get('text','') for x in c if isinstance(x,dict) and x.get('type')=='text')
          if c and not c.startswith('<') and 'session is being continued' not in c: print('-',c[:600])
  PY
  ```
- Общаться с владельцем на «ты», по-русски. Владелец в Москве — время писать по МСК (UTC+3).
- Не собирать тестовые сборки — сразу рабочую (релиз/бету) через Actions; сборку запускать только когда владелец просит.
- План: v1.2.0-beta.10 — последняя бета, дальше релиз v1.2.0 (раздел `## v1.2.0` в CHANGELOG собрать из всех бет).
