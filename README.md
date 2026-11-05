<div align="center">

<img src="branding/play-store-icon-512.png" width="140" alt="EthicVPN">

# EthicVPN

**Закрытый VPN для iPhone, Android и Windows**

VLESS · REALITY · XHTTP на ядре [Xray-core](https://github.com/XTLS/Xray-core)

[![Релиз](https://img.shields.io/github/v/release/cats1223/EthicVPN-app?label=%D1%80%D0%B5%D0%BB%D0%B8%D0%B7&color=00D1FF)](https://github.com/cats1223/EthicVPN-app/releases/latest)
![iOS 16+](https://img.shields.io/badge/iOS-16%2B-7B5CFF)
![Android 8+](https://img.shields.io/badge/Android-8%2B-2BD98B)
![Windows 10+](https://img.shields.io/badge/Windows-10%2B-0078D4)
[![Сборка](https://img.shields.io/github/actions/workflow/status/cats1223/EthicVPN-app/build.yml?branch=main&label=%D1%81%D0%B1%D0%BE%D1%80%D0%BA%D0%B0)](https://github.com/cats1223/EthicVPN-app/actions)

[**Скачать**](https://github.com/cats1223/EthicVPN-app/releases/latest) · [Добавить подписку](https://cats1223.github.io/EthicVPN-app/)

</div>

---

## Возможности

- 🌍 **Глобус** — показывает, где вы по IP до включения VPN и где вас видят сайты после.
- 🔌 **Одна кнопка** — подключение, таймер сессии и статус соединения.
- 🔗 **Подписка в одно касание** — скопируйте ссылку подписки, откройте приложение, и оно само предложит её добавить.
- 📷 **Любой способ добавить ключ** — ссылка подписки, ключ `vless://` или `trojan://`, QR-код или ссылка из мессенджера.
- 📊 **Подписка под рукой** — остаток трафика, срок действия и список серверов с пингом.
- 🛡 **Протоколы** — VLESS и Trojan (REALITY или TLS; XHTTP, TCP, WebSocket, gRPC, HTTPUpgrade).
- 🇷🇺 **Раздельное туннелирование** — российские сайты и адреса открываются напрямую; на Android можно выбрать приложения (банки, Госуслуги…), которые работают мимо VPN и не видят его.
- 🌐 **Без утечек** — DNS идёт через туннель (DNS-over-HTTPS), локальная сеть — напрямую.
- 🔔 **Проверка обновлений** — приложение само сообщит о новой версии.
- 🌓 **Чёрно-белое оформление**, тёмная и светлая тема.
- 🏠 **Домашняя сеть видна** — ПК, принтер и приставка доступны по Wi-Fi при включённом VPN.
- 📱 **Android** — плитка в шторке быстрых настроек и «Постоянная VPN».
- 🍏 **iPhone** — виджеты, Dynamic Island и Siri.
- 🖥 **Windows** — системный прокси или весь трафик (TUN), значок в трее, автозапуск.

## Установка

Все версии — на странице [**Релизы**](https://github.com/cats1223/EthicVPN-app/releases/latest).

| Платформа | Файл | Как установить |
|---|---|---|
| **Android 8+** | `EthicVPN-vX.Y.Z.apk` | Скачайте и откройте файл, разрешите установку из этого источника. |
| **iPhone, iOS 16+** | `EthicVPN-vX.Y.Z-unsigned.ipa` | Подпишите своим сертификатом (ESign, Feather, Sideloadly и т. п.). В профиле должны быть **Network Extensions → Packet Tunnel**. |
| **Windows 10/11** | `EthicVPN-Setup-X.Y.Z.exe` | Запустите установщик, права администратора не нужны. |

## Как добавить подписку

1. Скопируйте ссылку подписки, которую вам прислал администратор.
2. Откройте [cats1223.github.io/EthicVPN-app](https://cats1223.github.io/EthicVPN-app/) и нажмите **«Открыть EthicVPN»**.
3. Приложение спросит **«Добавить подписку?»** — нажмите «Добавить». На iPhone разрешите вставку из буфера.

Можно и вручную: **Серверы → «+»** → вставьте ссылку или отсканируйте QR-код.

Приложение открывает ссылки `vless://…` и `ethicvpn://add?url=<ссылка подписки>`.

---

## Для разработчиков

<details>
<summary><b>Устройство проекта</b></summary>

```
android/                     Kotlin, Jetpack Compose, VpnService
  app/src/main/java/.../core VLESS-ссылки, конфиг Xray, подписка, deep link
  app/src/main/java/.../vpn  VpnService: TUN → Xray
ios/                         Swift, SwiftUI (проект генерирует XcodeGen)
  Shared/                    общий код приложения и VPN-расширения
  EthicVPN/               приложение
  PacketTunnel/              Network Extension: находит fd utun и запускает Xray
docs/                        страница «Открыть EthicVPN» (GitHub Pages)
server/link-page/            та же страница для своего сервера
geodata/                     российские IP и сайты для раздельного туннелирования
scripts/update-geodata.py    обновление geodata/ из списков v2fly
scripts/build-libxray.sh     сборка ядра libXray
scripts/server/              проверка VPN-ноды с сервера
```

Xray сам читает пакеты из TUN: приложение передаёт номер дескриптора через `env["xray.tun.fd"]`, отдельный tun2socks не нужен.
Логика разбора ссылок и конфига продублирована в `android/.../core` и `ios/Shared` и покрыта тестами на обеих платформах.

</details>

<details>
<summary><b>Сборка</b></summary>

Всё собирается в **GitHub Actions** (`.github/workflows/build.yml`): ядро libXray, тесты, APK и неподписанный IPA.
Артефакты лежат на странице запуска. Ручной запуск с параметром `version` (например, `v1.2.0`) публикует релиз
и создаёт тег.

Локально:

```bash
scripts/build-libxray.sh android        # нужны Go 1.25+ и Android NDK
cd android && ./gradlew assembleDebug

scripts/build-libxray.sh ios            # только macOS + Xcode
cd ios && xcodegen && open EthicVPN.xcodeproj
```

</details>

<details>
<summary><b>Подпись iOS в GitHub</b></summary>

Если добавить секреты репозитория, CI выпустит ещё и подписанный IPA (`EthicVPN-ios-signed`):

| Секрет | Что положить |
|---|---|
| `IOS_P12_BASE64` | сертификат `.p12` в base64 |
| `IOS_P12_PASSWORD` | пароль от `.p12` |
| `IOS_PROFILE_APP_BASE64` | `.mobileprovision` приложения в base64 |
| `IOS_PROFILE_TUNNEL_BASE64` | `.mobileprovision` VPN-расширения в base64 |

Нужны два явных App ID (приложение и `….tunnel`). У обоих должны быть **Network Extensions → Packet Tunnel** и
одна общая **App Group**. Bundle id, команда и группа берутся из профилей.

</details>

<details>
<summary><b>Формат ключа</b></summary>

```
vless://<uuid>@<host>:<port>?type=xhttp&security=reality&pbk=<ключ>&sid=<short id>&sni=<домен>&fp=chrome&path=/…&mode=stream-one#Название
```

Подписка — текст или base64 со ссылками по одной на строку (Marzban, 3x-ui, Remnawave, mikan и др.).
При загрузке приложение отправляет заголовки `x-hwid`, `x-device-os`, `x-ver-os` и `x-device-model` для панелей,
которые привязывают ключи к устройствам.

</details>
