# IMS для Pixel

> Включение VoLTE, VoNR (звонки 5G) и звонков по Wi-Fi на смартфонах Google Pixel — **без root**.

[![Платформа](https://img.shields.io/badge/platform-Android%2010--17-brightgreen)](https://developer.android.com)
[![API](https://img.shields.io/badge/minSdk-28-blue)](https://developer.android.com/about/versions/10)
[![Лицензия](https://img.shields.io/badge/license-MIT-orange)](#лицензия)
[![Версия](https://img.shields.io/badge/version-1.0.0--ru-informational)](app/build.gradle)
[![Проверено](https://img.shields.io/badge/tested-Android%2017-success)](https://developer.android.com)

Это русская версия проекта [svenuks/ImsForPixel](https://github.com/svenuks/ImsForPixel): интерфейс, уведомления и документация переведены на русский язык, сборка настроена под **Google Pixel 9 на Android 17**.

---

## Обзор

**IMS для Pixel** — инструмент для переопределения настроек оператора (carrier config) без root на Google Pixel и других устройствах на AOSP. Он включает VoLTE, VoNR (звонки 5G) и звонки по Wi-Fi у операторов, для которых в прошивке нет готового или полноценного профиля, и работает даже на **Android 17**, где доступ сторонних приложений к привилегированным API телефонии ещё сильнее ограничен.

### Вдохновлено Shizuku и IMS

Проект опирается на идеи двух отличных open-source проектов:

- **[Shizuku](https://github.com/RikkaApps/Shizuku)** первым использовал встроенную в Android беспроводную отладку, чтобы временно выдать пользовательскому приложению права `shell` без root. *IMS для Pixel* применяет ту же идею, но полностью самостоятельно: устанавливать Shizuku не нужно. Сопряжение, подключение и запуск `am instrument` выполняются внутри приложения с помощью библиотеки [Kadb](https://github.com/flyfishxu/Kadb).

- **[IMS Patch / инструменты carrier config](https://github.com/imsforpixel)** показали, какие ключи `CarrierConfigManager` открывают VoLTE/VoNR/VoWiFi на Pixel. *IMS для Pixel* объединяет эти ключи с подходом через права `shell` в одно простое самодостаточное решение.

| | Решения на Shizuku | IMS для Pixel |
|---|---|---|
| Нужно приложение Shizuku | ✅ Да | ❌ Нет |
| Нужен root | ❌ Нет | ❌ Нет |
| Нужен ПК / кабель ADB | ❌ Нет | ❌ Нет |
| Работает на Android 15 / 16 / 17 | ⚠️ По-разному | ✅ Да |
| Один самодостаточный APK | ❌ Нет | ✅ Да |
| Показывает реальный статус регистрации IMS | ⚠️ По-разному | ✅ Да (в уведомлении) |

---

## Возможности

- ✅ **VoLTE** — HD-звонки через LTE
- ✅ **VoNR** — звонки в сети 5G (Voice over New Radio)
- ✅ **Звонки по Wi-Fi (VoWiFi)**
- ✅ **Звонки по Wi-Fi в роуминге** — не отключаются за границей
- ✅ **Дополнительные услуги (UT)** — переадресация, ожидание вызова и т. п.
- ✅ **Две SIM-карты** — независимые настройки для каждого слота
- ✅ **Сброс в одно касание** — удаляет все переопределения и возвращает настройки оператора
- ✅ **Статус IMS в реальном времени** — фоновая проверка каждые 3 с
- ✅ **Результат в уведомлении** — реальный статус регистрации IMS по каждому слоту после активации
- ✅ **Проверено на Android 10–17**
- 🚫 **Root** не нужен
- 🚫 **Shizuku** и другие менеджеры прав не нужны
- 🚫 **Интернет** не используется — весь трафик ADB идёт только через локальный loopback

---

## Как это работает

```
┌─────────────────────┐      loopback ADB        ┌──────────────────────────┐
│ Интерфейс IMS для   │ ───────────────────────► │  BrokerInstrumentation   │
│ Pixel (Compose)     │   am instrument -w       │  (Instrumentation runner)│
└─────────────────────┘                          └──────────┬───────────────┘
                                                            │ adoptShellPermissionIdentity()
                                                            ▼
                                                  CarrierConfigManager
                                                  .overrideConfig(subId, bundle)
                                                            │
                                                            ▼
                                                  TelephonyManager.resetIms()
                                                            │
                                                            ▼
                                                  Ожидание регистрации IMS (≤ 30 с)
                                                            │
                                                            ▼
                                                  Уведомление с результатом
```

1. Приложение подключается к порту **беспроводной отладки** своего же устройства через loopback (`127.0.0.1`).
2. Через `am instrument` запускается `BrokerInstrumentation`, который работает с правами `shell` — тот же механизм, что у Shizuku, но встроенный.
3. `BrokerInstrumentation` вызывает `CarrierConfigManager.overrideConfig` с выбранными настройками, перезапускает IMS и до 30 секунд ждёт регистрации.
4. По завершении в **шторке уведомлений** появляется реальный результат регистрации IMS по каждому слоту.
5. Фоновый процесс `ImsQueryTool` каждые 3 секунды обновляет индикаторы статуса IMS в приложении.

---

## Требования

| Требование | Подробности |
|---|---|
| Версия Android | Android 9 (API 28) и выше |
| Проверено до | **Android 17** ✅ |
| Целевое устройство сборки | **Google Pixel 9** (arm64-v8a) |
| Wi-Fi | Телефон должен быть подключён к Wi-Fi |
| Беспроводная отладка | Должна быть включена в разделе «Для разработчиков» |
| Root / Shizuku | ❌ Не нужны |

---

## Быстрый старт

### 1. Включите режим разработчика и беспроводную отладку

1. Откройте **Настройки → О телефоне** и 7 раз нажмите на **Номер сборки**.
2. Откройте **Настройки → Система → Для разработчиков**.
3. Включите **Отладку по Wi-Fi** (беспроводную отладку).

### 2. Установите приложение

Скачайте свежий APK (`IMS-for-Pixel-ru-…-pixel9-arm64.apk`) из раздела [Releases](../../releases) и установите его, либо соберите из исходников (см. [Сборка](#сборка)).

> Если у вас установлен оригинальный *IMS for Pixel* — сначала удалите его: у этой сборки тот же идентификатор пакета (`com.svenuks.imsforpixel`), но другая подпись.

### 3. Сопряжение и активация

1. Откройте **IMS для Pixel**.
2. Нажмите **«Открыть настройки беспроводной отладки»**, затем **«Подключить устройство с помощью кода»**. Приложение само найдёт порт через mDNS. В появившемся уведомлении нажмите **«Отправить код»** и введите **6-значный код сопряжения**.
3. Настройте переключатели для каждой SIM-карты (VoLTE, VoNR, VoWiFi и т. д.).
4. Нажмите **«Активировать»**.
5. Подождите до ~30 секунд. Системное уведомление покажет статус регистрации IMS по каждому слоту.

---

## Сборка

### Готовая сборка через GitHub Actions

Каждый push запускает workflow [`.github/workflows/build.yml`](.github/workflows/build.yml): он собирает release-APK для Pixel 9 (только `arm64-v8a`) и публикует его в [Releases](../../releases) с тегом `v<версия>`, а также прикладывает к запуску как artifact.

Подпись: если в секретах репозитория заданы `SIGNING_KEYSTORE_BASE64` (keystore в base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` и `SIGNING_KEY_PASSWORD`, APK подписывается этим ключом. Без них используется debug-ключ сборочной машины — APK устанавливается, но каждая новая сборка подписана другим ключом, и перед обновлением старую версию придётся удалить.

### Требования для локальной сборки

- Android Studio (Ladybug или новее)
- JDK 17 (входит в Android Studio)
- Android SDK API 36

### Debug-сборка

```bash
git clone https://github.com/taidaru/PixelIMS_ru.git
cd PixelIMS_ru
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release-сборка

```bash
./gradlew assembleRelease
```

> **macOS:** если системного JDK нет, используйте JRE из Android Studio:
> ```bash
> JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
> ```

---

## Структура проекта

```
app/src/main/java/com/svenuks/imsforpixel/
├── MainActivity.kt          # Интерфейс на Jetpack Compose + поиск портов через mDNS + фоновая проверка IMS
├── BrokerInstrumentation.kt # Запуск с правами shell: переопределяет carrier config, ждёт IMS, показывает уведомление
└── ImsQueryTool.kt          # Лёгкая точка входа app_process для фоновых запросов статуса IMS
```

| Файл | Назначение |
|---|---|
| `MainActivity.kt` | Весь интерфейс (Material 3 / Compose), поиск сервисов mDNS, ADB-клиент Kadb, фоновое обновление IMS |
| `BrokerInstrumentation.kt` | Привилегированный исполнитель — переопределяет carrier config, перезапускает IMS, ждёт регистрации, показывает уведомление |
| `ImsQueryTool.kt` | Минимальная точка входа `app_process` — запрашивает состояние IMS без накладных расходов UiAutomation |

---

## Разрешения

| Разрешение | Зачем |
|---|---|
| `INTERNET` | Нужно Kadb для локального loopback-сокета ADB |
| `ACCESS_NETWORK_STATE` | Проверка подключения к Wi-Fi перед сопряжением |
| `CHANGE_WIFI_MULTICAST_STATE` | Поиск порта беспроводной отладки через mDNS (NSD) |
| `POST_NOTIFICATIONS` | Результат активации и ввод кода сопряжения в шторке уведомлений |

> Никакие данные не отправляются на внешние серверы. Весь сетевой трафик идёт только на `127.0.0.1`.

---

## Зависимости

| Библиотека | Версия | Назначение |
|---|---|---|
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx/releases/core) | 1.13.1 | Расширения Kotlin |
| [androidx.activity:activity-compose](https://developer.android.com/jetpack/androidx/releases/activity) | 1.9.1 | Activity для Compose |
| [androidx.compose BOM](https://developer.android.com/jetpack/compose/bom) | 2024.06.00 | Compose UI, Material 3 |
| [hiddenapibypass](https://github.com/LSPosed/HiddenApiBypass) | 4.3 | Доступ к закрытым API телефонии на Android 9+ |
| [kadb](https://github.com/flyfishxu/Kadb) | 2.1.1 | ADB по Wi-Fi на чистом Kotlin |
| [adblib](https://github.com/tananaev/adblib) | 1.3 | Поддержка протокола ADB |

---

## Вопросы и ответы

**В: Сохраняются ли настройки после перезагрузки?**
О: Нет. Переопределения `CarrierConfigManager.overrideConfig` хранятся в памяти и сбрасываются при перезагрузке. После каждой перезагрузки снова нажмите **«Активировать»**.

**В: Это может что-то сломать?**
О: В любой момент нажмите **«Сбросить»** — все переопределения удалятся, и вернутся настройки оператора.

**В: Мой оператор требует провижининг — это сработает?**
О: Приложение выставляет `carrier_volte_provisioning_required_bool = false`. У большинства операторов этого достаточно. Если оператор проверяет IMS на своей стороне, регистрация может не пройти независимо от приложения.

**В: Поддерживаются две SIM-карты?**
О: Да. Каждый активный слот настраивается отдельно и имеет свой индикатор IMS.

**В: Почему не просто Shizuku?**
О: Shizuku отличный, но требует отдельного запущенного приложения. *IMS для Pixel* полностью самодостаточен — один APK без зависимостей от других приложений. Механизм тот же (беспроводная отладка + права `shell`), но всё делается внутри.

**В: Порт беспроводной отладки доступен из сети?**
О: Приложение подключается только через `127.0.0.1` (loopback) и не обращается к другим устройствам в сети.

---

## Безопасность и прозрачность

- **Никаких внешних сетевых запросов.** Ни телеметрии, ни аналитики, ни сбора данных.
- **ADB только через loopback.** Все соединения идут на `127.0.0.1` (само устройство).
- **Три файла исходников.** Всё приложение — около 1200 строк Kotlin в трёх файлах, его легко проверить целиком.
- **Только стандартные механизмы.** Закрытые API доступны через известную библиотеку `HiddenApiBypass` и рефлексию Java — без нативного кода и бинарных вставок.

---

## Участие в разработке

Pull request'ы и issue приветствуются. Перед крупными изменениями, пожалуйста, откройте issue.

1. Сделайте форк репозитория
2. Создайте ветку: `git checkout -b feature/my-feature`
3. Закоммитьте изменения: `git commit -m 'Add my feature'`
4. Отправьте: `git push origin feature/my-feature`
5. Откройте Pull Request

---

## Лицензия

Текст лицензии MIT приведён в оригинале — юридическую силу имеет английская версия.

```
MIT License

Copyright (c) 2025 Sven

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Благодарности

Спасибо проектам, чьи идеи сделали *IMS для Pixel* возможным:

- **[svenuks/ImsForPixel](https://github.com/svenuks/ImsForPixel)** — оригинальный проект, на котором основана эта русская версия.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** от RikkaW — за идею «беспроводная отладка как мост к привилегиям». *IMS для Pixel* заимствует эту идею и реализует её полностью самостоятельно.
- **[LSPosed/HiddenApiBypass](https://github.com/LSPosed/HiddenApiBypass)** — за практичный доступ к закрытым API телефонии в современных Android.
- **[flyfishxu/Kadb](https://github.com/flyfishxu/Kadb)** — за реализацию ADB по Wi-Fi на чистом Kotlin.
- Сообществу Android-моддинга — за исследования IMS/carrier config и документирование действительно важных ключей `CarrierConfigManager`.
