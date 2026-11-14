# VPN Transfer

Сквозная передача VPN-конфигов и текстовых ключей между Windows и Android через Magic Wormhole.

[Скачать для Windows и Android](https://github.com/JohnWarrior01/vpn-transfer/releases/latest)

## Установка

- **Windows x64.** Распакуйте Windows-архив и запустите VPN-Transfer.exe.
- **Android 6.0 и новее.** Скачайте VPN-Transfer-Android.apk из релиза и откройте его на телефоне. Сборка поддерживает ARM64, ARMv7 и x86-64.

## Передача

1. На отправителе выберите файл или вставьте текстовый ключ. Лимит — 32 КБ за сеанс.
2. Передайте получателю код. В режиме «Своя фраза» используйте 4–8 случайных слов. Приложение добавит номер сеанса.
3. При диктовке назовите номер по цифрам, затем слова по порядку. Получатель может ввести код через пробелы или дефисы.
4. Получатель нажимает «Получить» и сохраняет файл или копирует полученный текст.

Оба устройства должны быть онлайн. Сеанс длится до пяти минут. Сообщение «Данные доставлены» подтверждает получение приложением. Для файла место сохранения выбирается отдельно. Обе стороны должны использовать версию 1.0.0 или новее.

## Обновления

Кнопка «Проверить обновления» запрашивает последний стабильный релиз GitHub. Если доступна новая версия, приложение предлагает открыть официальную страницу загрузки. Установка выполняется пользователем. Ключи и коды передачи в запрос на GitHub не входят.

## Сборка

- Windows. Python 3.11, зависимости из windows/requirements.txt и PyInstaller. Команда из корня репозитория:

      python -m PyInstaller --windowed --onefile --name VPN-Transfer --hidden-import autobahn.twisted.websocket --hidden-import twisted.internet.ssl --collect-data autobahn --collect-data spake2 --copy-metadata magic-wormhole windows/vpn_transfer.py

- Android. Скрипт android/build.ps1 использует JDK, Python 3, Go 1.26, gomobile и gobind версии, указанной в android/go.mod. Он ожидает Go в go/bin, Go-модули в gopath, кэш в gocache и Android SDK в android-sdk рядом с папкой android. Нужны Platform 35, Build Tools 35.0.0 и NDK 28.2.13676358. Скачайте исходники wormhole-william v1.0.8 в android/wormhole-william и примените android/mailbox-limit.patch. APK записывается в папку outputs на уровень выше корня репозитория. При первой сборке создаётся локальный ключ подписи. Сохраните свой прежний ключ, если собираете обновление для уже установленной сборки.

## Шифрование

Передача использует [Magic Wormhole](https://magic-wormhole.readthedocs.io/en/latest/client-protocol.html), на Android — [Wormhole William](https://github.com/psanford/wormhole-william). Публичный сервер relay.magic-wormhole.io пересылает зашифрованные сообщения и видит сетевые подключения. Передавайте код только получателю.
