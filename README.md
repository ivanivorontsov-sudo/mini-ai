# Мини ИИ — офлайн-чат с нейросетью на Android

Основано на официальном примере [llama.cpp/examples/llama.android](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android) (MIT), llama.cpp подключён сабмодулем `third_party/llama.cpp`.

Возможности:
- чат с потоковым ответом, кнопка «Очистить»;
- встроенный загрузчик моделей с Hugging Face: прогресс, пауза/продолжение (HTTP Range), фоновая загрузка (foreground service + уведомление), проверка свободного места и ОЗУ, проверка SHA256, удаление/смена модели;
- запасной вариант: выбрать свой файл `.gguf` на телефоне;
- «Честный режим» (по умолчанию): строгий системный промпт против выдумок и поддакивания + temp 0.3, top_p 0.9, repeat penalty 1.1;
- необязательный поиск в интернете (Google или Яндекс) через скрытый WebView; при капче — встроенный мини-браузер, затем повтор поиска. Без переключателя приложение полностью офлайн.

## Модели

| Модель | Размер | ОЗУ | Лицензия | SHA256 |
|---|---|---|---|---|
| [Qwen2.5-7B-Instruct Q4_K_M](https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/blob/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf) | 4.68 ГБ | 8+ ГБ | Apache 2.0 | 65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423 |
| [Qwen3-4B-Instruct-2507 Q4_K_M](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/blob/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf) | 2.50 ГБ | 6+ ГБ | Apache 2.0 | 3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597 |
| [Qwen2.5-1.5B-Instruct Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf) | 1.12 ГБ | 3+ ГБ | Apache 2.0 | 6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e |
| [Qwen2.5-0.5B-Instruct Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/qwen2.5-0.5b-instruct-q4_k_m.gguf) | 0.49 ГБ | 2+ ГБ | Apache 2.0 | 74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db |

### Ivan GPT (модель Ивана)
Маленькая модель Ивана (≈10 млн параметров), обучена с нуля, пишет в его стиле, факты путает. Репозиторий `ivan-gpt` приватный, поэтому:
* приложение берёт **последний релиз** через GitHub API (`releases/latest`) и ищет в нём `*.gguf` или zip с ним; если там нет — смотрит копию в последнем релизе `mini-ai` (тоже приватный);
* нужен **ваш** GitHub-токен (право чтения), он вводится один раз и хранится в EncryptedSharedPreferences; в APK токена нет;
* или загрузите `ivan-gpt.gguf` кнопкой «Файл с телефона».

Чат идёт ровно в формате обучения (`ivan-gpt/prep_chat.py`): строки `Человек: …` / `ИИ: …`, ответ до перевода строки, фильтр `safety.py` перенесён в приложение. Показывается версия модели и предлагается обновление.

Также есть «Своя модель по ссылке (.gguf)».

## Сборка
```
git clone --recursive <repo>; echo sdk.dir=... > local.properties
./gradlew assembleRelease   # подпись берётся из файла вне репозитория (MINI_AI_SIGNING)
```
`assembleRelease` в конце запускает `scripts/check_jni.py`: каждая `external fun` должна иметь символ `Java_*` в `libai-chat.so` для каждой ABI, иначе сборка падает.
Хост-тест JNI (загрузка модели, setSampling, генерация на Linux): `tests/host_jni/run.sh model.gguf`.

Ключи и веса моделей в репозиторий не входят.
