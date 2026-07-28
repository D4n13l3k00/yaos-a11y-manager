# YAOS Manager

[![Release](https://img.shields.io/github/v/release/D4n13l3k00/yaos-a11y-manager?display_name=tag)](https://github.com/D4n13l3k00/yaos-a11y-manager/releases/latest)
[![Android](https://img.shields.io/badge/Android_TV-6.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/tv)
[![Build](https://github.com/D4n13l3k00/yaos-a11y-manager/actions/workflows/android.yml/badge.svg)](https://github.com/D4n13l3k00/yaos-a11y-manager/actions/workflows/android.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

![YAOS Manager](app/src/main/res/drawable-xhdpi/app_banner.png)

Системный менеджер для Android TV и телевизоров с YAOS. Он возвращает полный
список служб специальных возможностей, управляет установленными приложениями,
принимает APK с телефона и открывает инженерное меню в одном интерфейсе,
рассчитанном на обычный пульт.

На совместимой прошивке YAOS Manager самостоятельно включает локальный ADB,
получает `WRITE_SECURE_SETTINGS` через заводской root и защищает выбранные
службы специальных возможностей от сброса. После первого запуска компьютер
больше не нужен.

Приложение написано на Kotlin без AndroidX, аналитики и рекламы.

![Главный экран](docs/app-screen.png)

> [!IMPORTANT]
> Автономный ADB и root-защита проверены на DEXP 43UCY3 с платой
> TP.MT9632.PB721. На других телевизорах основные экраны могут работать, но
> заводские сервисы и Binder API зависят от прошивки.

## Возможности

### Специальные возможности

- показывает все установленные accessibility-службы и их фактическое состояние;
- включает и отключает несколько служб без урезанного системного меню YAOS;
- перехватывает переходы в штатные экраны
  `android.settings.ACCESSIBILITY_SETTINGS` и
  `android.settings.ACCESSIBILITY_TV_OEM_LINK`;
- восстанавливает защиту после перезагрузки телевизора;
- позволяет временно отключить или повторно запустить защиту из интерфейса.

### Менеджер приложений

- пользовательские, системные, замороженные и удалённые для профиля пакеты;
- запуск, принудительная остановка, заморозка и разморозка;
- очистка кэша отдельного приложения или всех приложений;
- стирание данных с подтверждением;
- удаление пакета для текущего пользователя и восстановление системного пакета;
- полное удаление пользовательского APK;
- установка APK по прямой ссылке.

Системный APK при удалении для пользователя остаётся в прошивке. Его можно
вернуть кнопкой восстановления.

### Установка с телефона

Отдельный TV-экран поднимает временный HTTP-сервер и показывает QR-код. После
сканирования на телефоне открывается страница, куда можно загрузить APK или
передать прямую ссылку. На телевизоре в это время видны журнал передачи,
установка и ответ Package Manager.

Адрес содержит случайный 128-битный токен. Сервер доступен только пока открыт
экран установки; телефон и телевизор должны находиться в одной сети.

### Системные инструменты

- запуск штатного `Factory Menu`;
- полные настройки Android;
- отдельный экран сведений об устройстве;
- живые статусы ADB, `WRITE_SECURE_SETTINGS` и защиты YAOS.

## Совместимость

Проверенная конфигурация:

| Компонент | Значение |
| --- | --- |
| Телевизор | DEXP 43UCY3 |
| Android | 11 / API 30 |
| Плата | `m7332_eu`, TP.MT9632.PB721 |
| ABI | `armeabi-v7a` |
| YAOS Platform Services | `com.yandex.tv.services.platform` 3.340.28 |
| Factory API | `com.cvte.factory.service/.MainService` |
| Заводской root | Binder-сервис `cvte.at_sudo` |
| Локальный ADB | `127.0.0.1:5555`, `ro.adb.secure=0` |

На прошивке без CVTE Factory API приложение не сможет само включить ADB. Сам
менеджер специальных возможностей продолжит работать, если разрешение
`WRITE_SECURE_SETTINGS` уже выдано другим способом.

## Установка

Скачайте APK со страницы
[последнего релиза](https://github.com/D4n13l3k00/yaos-a11y-manager/releases/latest)
и установите его любым доступным способом: с USB-накопителя, через файловый
менеджер или по ADB.

```powershell
adb connect TV_IP:5555
adb install -r .\YAOS-A11Y-Manager-v1.0.0.apk
```

Откройте **YAOS Manager** в списке приложений. Первый запуск на проверенной
прошивке занимает немного больше времени: приложение включает локальный ADB,
выдаёт себе системное разрешение и запускает защиту. Состояние каждого этапа
видно в разделе «Об устройстве».

## Управление с пульта

- крестовина перемещает фокус, `OK` открывает выбранный пункт;
- `Back` или кнопка «Назад» в правом верхнем углу возвращает на предыдущий экран;
- `Вниз` с верхней панели менеджера приложений переводит к общей очистке кэша;
- `Влево` из любой строки списка сразу переводит к действиям;
- `Вправо` возвращает в ту же строку без прокрутки списка вверх;
- длинные названия и имена пакетов прокручиваются, пока строка находится в фокусе.

## Как работает автономный режим

```text
CVTE Factory API
       │ включает ADB
       ▼
127.0.0.1:5555 ── dadb ──► shell
                              │
                              ▼
                         cvte.at_sudo
                          │         │
                          │         └─► native-hook YAOS
                          └─► WRITE_SECURE_SETTINGS
```

### Включение ADB

До запуска локального ADB приложение связывается с экспортированным системным
сервисом `com.cvte.factory.service/.MainService`. CVTE JAR не требуется:
используются две raw Binder-транзакции.

```text
IFactoryApi.getFacApiNetWork()  -> transaction 8
IFacApiNetWork.setAdbStatus(1)  -> transaction 37
```

После ответа сервиса YAOS Manager ждёт порт `127.0.0.1:5555` и подключается к
нему через встроенную библиотеку [dadb](https://github.com/mobile-dev-inc/dadb).

### Заводской root

На проверенной прошивке `/system/bin/at_sudo` публикует Binder-сервис
`cvte.at_sudo`. Это штатный компонент телевизора, а не Magisk или добавленный
в систему `su`.

Встроенный `AtSudoClient` запускается через локальный ADB и использует
Binder-транзакцию `2`. Root выдаёт приложению
`android.permission.WRITE_SECURE_SETTINGS`, после чего YAOS Manager проверяет
разрешение перед изменением системных настроек.

Произвольной shell-консоли в приложении нет. Команды и пути зафиксированы в
исходном коде.

### Защита от watchdog YAOS

`com.yandex.tv.services.platform` следит за двумя настройками и возвращает
разрешённый YAOS список служб:

```text
Settings.Secure.enabled_accessibility_services
Settings.Secure.accessibility_enabled
```

Root запускает `frida-inject` только в процессе YAOS Platform Services. Hook
перехватывает исходящие `PUT_secure` и подменяет имя ключа лишь для этих двух
записей. Остальные Binder-вызовы, настройки и сервисы YAOS продолжают работать.

Фоновый скрипт отслеживает PID платформы и повторяет инъекцию после её
перезапуска. `BootReceiver` восстанавливает автономный режим после загрузки
телевизора.

### Инженерное меню

Сначала приложение вызывает штатный сервис:

```text
com.cvte.fac.menu/.app.TvMenuWindowManagerService
com.cvte.fac.menu.commmand.factory_menu
```

Если компонент недоступен, используются установленное приложение Factory Menu
или сервисная последовательность пульта:

```text
Home → Source → Влево → Вверх → Влево → Вверх → Назад → Source
```

Последовательность описана в теме
[BBK/YAOS на 4PDA](https://4pda.to/forum/index.php?showtopic=990351&st=220).

## Сборка из исходников

Понадобятся JDK 17 или 21 и Android SDK 35.

Windows:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

Linux и macOS:

```bash
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
```

APK появится в:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Основные зависимости:

- [dadb 2.0.0](https://github.com/mobile-dev-inc/dadb) — локальный ADB-клиент;
- [XZ for Java 1.12](https://tukaani.org/xz/java.html) — распаковка root-payload;
- [ZXing Core 3.5.4](https://github.com/zxing/zxing) — QR-код веб-установщика;
- [frida-inject 16.7.19](https://github.com/frida/frida/releases/tag/16.7.19) —
  точечный native-hook для Android ARM.

## Структура проекта

```text
app/src/main/java/dev/d4n13l3k00/yaosa11y/
  MainActivity.kt              главная страница
  AccessibilityActivity.kt     менеджер служб
  AppManagerActivity.kt        менеджер приложений
  WebInstallActivity.kt        установка по QR и ссылке
  DeviceInfoActivity.kt        состояние устройства
  CvteAdbBootstrap.kt          запуск ADB через Factory API
  RootHookManager.kt           локальный ADB, root и защита
  LocalApkServer.kt            временный HTTP-сервер

app/src/main/assets/root/       встроенный root-payload
tools/                          исходники и копии payload для пересборки
```

## Лицензия

Copyright © 2026 **D4n13l3k00**.

Проект распространяется по
[GNU Affero General Public License v3.0 or later](LICENSE). Код можно
использовать, изучать, изменять и распространять. При распространении
оригинала или производной версии необходимо:

- предоставить соответствующий исходный код;
- сохранить ту же лицензию для производной работы;
- сохранить уведомления о лицензии и авторских правах;
- отметить внесённые изменения.

Если изменённая версия взаимодействует с пользователями по сети, этим
пользователям также должен быть доступен её полный исходный код.

## Автор

[D4n13l3k00](https://github.com/D4n13l3k00)
