[English](README.md) · **Русский**

# TarnVPN

VPN-клиент для Android под заблокированные сети: **VLESS + REALITY**, **XHTTP** и остальной набор
протоколов sing-box — но с интерфейсом, сделанным под задачу, а не с редактором конфигов.

> Форк [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) со своей
> оболочкой, работающий на [Amesu-afk/sing-box-lx](https://github.com/Amesu-afk/sing-box-lx) —
> форке sing-box с клиентскими патчами, от которых зависит поведение приложения.
> **Проект не аффилирован с sing-box и SagerNet.**

## Что умеет

- **Сервер — это строка списка, а не файл конфигурации.** Вставь ссылку `vless://` (а также
  `trojan`, `ss`, `vmess`, `hysteria2`, `tuic`, `anytls`) или URL подписки — каждый сервер станет
  отдельной строкой со своим пингом. Пинг меряется TCP-хендшейком мимо туннеля, поэтому цифры есть
  ещё до подключения.
- **Подписки** как полноценная сущность: обновление считает разницу по ссылке, сохраняет выбор,
  убирает дубли.
- **Раздельное туннелирование** по приложениям, в режимах «только эти» и «все кроме».
- **Kill switch** — приложениям запрещено ходить мимо поднятого туннеля.
- **Понятный DNS**: DoH-пресеты или свой резолвер, плюс переключатель, идут ли запросы через
  туннель (отвечает регион выхода) или напрямую (быстрее, но виден твой регион). Холодные запросы
  к медиа-CDN намеренно уведены мимо туннеля, иначе лента коротких видео подгружается на каждом
  клипе.
- **Ручки против DPI**, которые ядро действительно исполняет: фрагментация TLS поверх REALITY,
  политика QUIC, MTU, стратегия IPv6, отправка адресата именем или адресом.
- Светлая и тёмная темы, журнал внутри приложения, а под оболочкой по-прежнему доступен полный
  интерфейс sing-box для всего, что оболочка не покрывает.

## Установка

Бери **universal** APK из [релизов](https://github.com/Amesu-afk/TarnVPN/releases).

| Сборка | Android |
|---|---|
| `TarnVPN-<версия>-universal.apk` | 6.0+ (API 23) |
| universal-сборка, в имени которой есть `legacy-android-5` | 5.0–5.1 (API 21) |

Релизы подписаны собственным ключом проекта, а **не** тем, что лежит в апстримовом
`app/release.keystore`: тот файл публично доступен в репозитории SFA, то есть подписанное им может
изготовить кто угодно. Сборка с другим ключом не встанет как обновление — придётся удалить
приложение, а вместе с ним уедут сохранённые профили.

Приложение само проверяет свои релизы здесь и умеет ставить обновление на месте. Никуда больше
ничего не отправляется, а проверку обновлений можно выключить.

## Сборка из исходников

Версии важны, и две из них не обсуждаются:

- **строго JDK 17** (на более новых gomobile падает);
- Android SDK с **NDK 28.0.13004108**;
- **Go 1.25+** с `gomobile` — только если пересобираешь ядро (`make lib_install` в репозитории ядра).

```bash
# 1. ядро — если нужен свой libbox вместо закоммиченного
git clone https://github.com/Amesu-afk/sing-box-lx
cd sing-box-lx
go run ./cmd/internal/build_libbox -target android   # даёт libbox.aar + libbox-legacy.aar

# 2. приложение
cp libbox*.aar clients/android/app/libs/
cd clients/android
./gradlew assembleOtherRelease        # подписанный релиз, нужен keystore (ниже)
./gradlew assembleOtherDebug          # отладочная сборка, keystore не нужен
```

Подпись читается из `local.properties` (под gitignore):

```properties
KEYSTORE_PASS=…
ALIAS_NAME=…
ALIAS_PASS=…
```

Свой `app/tarn-release.keystore` генерируется локально — и бэкапится вместе с этими тремя строками.
Потеря любого из двух означает, что установленные копии больше никогда не обновить.

После замены `libbox.aar` собирай с `--rerun-tasks`: инкрементальная сборка Gradle уже отдавала из
устаревшего кэша APK на 40% больше нужного.

## Лицензия

GPLv3, унаследованная от апстрима и не изменённая. Слой интерфейса (`io.nekohasekai.sfa.tarn`),
импортёр share-ссылок и клиентские правки — дополнения к той же работе и на тех же условиях;
всё остальное принадлежит апстриму.

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Последний пункт — апстримовый, и именно поэтому у приложения своё имя, свой `applicationId`
(`app.tarnvpn`), своя иконка и свой ключ подписи, и поэтому оно нигде не числится как SFA.
Соответствующие исходники ядра, вшитого в каждый релиз, — репозиторий `sing-box-lx` по ссылке
выше; эта ссылка и есть исполнение GPL, а не любезность.
