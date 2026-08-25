# План: ускорение и ленивая загрузка bazel-java плагина

Основан на замерах на монорепо `audienzz` (2026-08-25, bazel 9.2.0, redhat.java 1.55.0)
и на разборе инцидента с зависшими JVM — см. [INCIDENT-2026-08-25-hung-jvms.md](INCIDENT-2026-08-25-hung-jvms.md).

> **Ревизия от 2026-08-25 после инцидента.** Изменилось три вещи:
> 1. Приоритет №1 — не скорость, а **предохранители** (Этап 0). Ночной цикл повторов
>    (~1400 итераций, 7 часов CPU, 2.3 ГБ висящих JVM) обошёлся дороже, чем медленный старт.
> 2. Батч-aquery надо делать **по явному списку меток через `--query_file`, а не по `//...`**.
>    Замер: **4.6 с** вместо 20.4 с — и, что важнее, анализ не выходит за java-замыкание,
>    поэтому недоступные `rules_oci` / `helm_charts` его не ломают.
> 3. Скоупинг импорта (Этап 5) сам по себе **не** закрывает триггер инцидента — сломанный
>    пакет `//platform/infra/audienzz/clamav` лежит внутри `//platform/...`.

---

## 0. Статус реализации

Весь план реализован; см. [README.md](README.md) для настроек и команд.
Замеры ниже (раздел 1) — «до». Что получилось «после» — в разделе 4.

| Этап | Где | Проверено |
|---|---|---|
| 0. Предохранители | `BazelWorkspace` (exit 0/3), `FailureGate`, `BazelLog`, `BazelQuery` (`--nofetch`) | headless-прогон jdt.ls |
| 1. Батч-aquery | `BazelClasspathCache.warmAll`, `AqueryParser` | на живом выводе 15.4 МБ / 442 таргета |
| 2. Ленивый контейнер | `BazelClasspathContainerInitializer`, `ClasspathResolveJob` | в логе между стартом и «Workspace initialized» нет вызовов bazel |
| 3. Дисковый кеш | `ClasspathStore`, `Digests`, `DiscoveryRefreshJob` | тёплый рестарт: 0 вызовов bazel |
| 4. Батчинг ресурсов | `ProjectProvisioner` (две транзакции, autobuild off, main+test в один проект) | 114 проектов вместо 223 |
| 5. Скоупинг | `BazelSettings`, `LazyImport`, `extension.js` | юнит-тесты на `.bazelproject` и universe |
| 6. Гигиена | `BazelBinary` (кеш), lock на процесс, `BuildClasspathJob`, `ImportReport`, команды | отчёт об импорте отдаёт `bazel.showImportReport` |
| 7. Отдельный output base | `BazelWorkspace.outputBaseDirectory`, `shutdownOwnedServer` | opt-in, по умолчанию выключено |

Что нашлось только при прогоне (и не было бы найдено обзором кода):

1. **Проект, созданный внутри `JavaCore.run`, не виден java-модели до конца транзакции** —
   `setRawClasspath` в той же транзакции падает с `<project> does not exist`. Провижининг
   разделён на две транзакции: создание, затем настройка.
2. **`IProject.create(description, ...)` не сохраняет natures.** `open()` читает `.project` с
   пустым `<natures>`, `JavaProject.exists()` проверяет именно java-nature — и после рестарта,
   при котором каталоги проектов остались на диске, падали все 114 проектов. Nature теперь
   выставляется явно после `open()`. Сценарий не искусственный: это ровно то, что происходит,
   когда jdt.ls не завершается штатно, а убивается — как в инциденте.
3. **`.classpath` на диске обязателен.** В Этапе 4 был пункт
   `setRawClasspath(..., canModifyResources = false, ...)` — «не писать 114 файлов, classpath всё
   равно живёт в памяти». Это ломает всё: jdt.ls проверяет наличие `.classpath` у каждого
   java-проекта и, не найдя его, пишет в лог `project has no .classpath. Removing Java nature and
   builder` и снимает nature с builder'ом. Все 114 проектов разбирались сразу после импорта, и
   импорты переставали резолвиться. Пункт из плана отменён, используется трёхаргументная форма.
   Регрессия ловится только с включённым autobuild — headless-прогон теперь запускается именно
   так и дополнительно открывает java-файл и проверяет диагностики.
4. **Свой output jar на собственном classpath — и почему его нельзя вычитать.** Побочный эффект
   склейки main+test: classpath тестового таргета содержит jar основного, то есть классы проекта
   видны и из исходников, и из jar. Напрашивается вычесть выход таргета (`--output` в командной
   строке Javac) из его же classpath — так и было сделано, и это оказалось ошибкой. На этом репо
   lombok стоит на 216 из 223 таргетов, а openapi-классы вообще существуют только в
   `-gensrc.jar`: сгенерированные члены живут именно в собственном jar-е, и без него JDT их не
   видит. Вычитание откачено, в `BazelClasspathCache` оставлен комментарий, чтобы не повторять.
5. **Стейл сгенерированных jar-ов.** `aquery` описывает, что сборка *взяла бы*, а не что лежит на
   диске. `server-gensrc.jar` от 14 августа переживал коммит от 19-го, добавивший поле в
   openapi-спеку: `bazel build` компилировался, а IDE писала `getCurrency() is undefined`.
   Закрыто настройкой `bazelJava.buildOnImport` (по умолчанию `background`).
6. **Перепубликация контейнеров = переиндексация всего, и это ломает индекс JDT.** Первая версия
   `buildOnImport` после сборки безусловно дёргала `DiscoveryRefreshJob(force)`. На up-to-date
   репозитории сборка не меняла ничего, но контейнеры публиковались заново — а это заставляет JDT
   забыть содержимое всех ~1.6k jar-ов и переиндексировать их: гигабайт с лишним записи в
   `.metadata/.plugins/org.eclipse.jdt.core` на каждом старте. Закрытый посреди этого VS Code
   оставляет обрезанные `.index`, JDT потом читает из такого файла длину как мусор
   (`Failed to read index data ... size 1885434739` — это ASCII-байты на месте int) и падает с
   `OutOfMemoryError` при любом `-Xmx`; в рабочем `jdt_ws` набралось 1688 index-файлов при 660
   зарегистрированных и пустой `indexNamesMap.txt`. `BuildClasspathJob` теперь снимает отпечаток
   jar-ов (путь + размер + mtime) до и после сборки и перепубликует контейнеры, только если
   что-то реально сдвинулось. Испорченный workspace лечится
   `Java: Clean Java Language Server Workspace`.
7. **Сгенерированные аннотационными процессорами классы не резолвились.** `Entity_` из
   hibernate-processor, openapi-классы, lombok-члены — всё это существует только в выходном jar-е
   таргета, а bazel собственный выход на свой же classpath не кладёт. Симптом:
   `CompanyDiscount_ cannot be resolved` в `main`-исходниках при полностью зелёной сборке.
   Воспроизведено headless-прогоном (3 ошибки), закрыто добавлением `--output` Javac-экшена в
   список jar-ов своей же метки (0 ошибок). `-gensrc.jar` цепляется к этой записи как source
   attachment.
8. **Lombok.** vscode-java включает lombok, найдя `lombok-<версия>.jar` на classpath проекта и
   подсунув именно этот файл в `-javaagent`. Bazel держит lombok на processorpath, а на classpath
   кладёт `header_lombok-*.jar` — имя подходит, байткода нет. Контейнер подставляет полный jar.
   Проверить в headless нельзя: агент подключает расширение VS Code, не сервер.
9. **Source root выводился по форме пути, а не по пакетам.** `commonSourceRoot` брал первый
   сегмент пути относительно bazel-пакета: у `//platform/openapi-spring-generator:cli` исходники
   лежат в `src/main/java/com/github/...`, корень получался `src/`, и JDT требовал у каждого файла
   пакет `main.java.com.github...`. Теперь корень выводится из объявленных пакетов (директория
   минус package), с порогом «побеждает вариант, покрывающий не меньше половины прочитанных
   файлов» и запретом на корень репозитория. Плюс вложенные корни (`//platform/starter` и
   `//platform/starter/src/main`) исключают друг друга — иначе внешний забирал файлы внутреннего
   под неправильным пакетом и дублировал каждый тип. 63 файла с несовпадением пакета и пути стало
   51; оставшиеся — несогласованность самого репозитория, которую никакой раскладкой Eclipse не
   выразить.
10. **Пакет ≠ каталог: 51 файл и каскад из ~160 ошибок.** Bazel передаёт javac список файлов и на
    раскладку не смотрит; Eclipse выводит пакет из каталога и жалуется, а ключа severity у этого
    правила нет (проверено по `JavaCore`). Хуже самой ошибки её последствие: файл, положенный в
    «чужой» пакет, теряет все неквалифицированные ссылки на настоящих соседей. Провижининг теперь
    исключает такие файлы из настоящего source folder **resource-фильтром** и линкует их во второй
    folder по пути их собственного пакета. Первая попытка через classpath exclusion провалилась и
    была хуже исходного состояния: ресурс остаётся, редактор открывает файл по пути на диске, и
    сервер отвечает `not on the classpath of project X, only syntax errors are reported`. Скан
    (10 206 файлов, ~1 с) кешируется вместе с discovery. Цена: тёплый старт 1.3 → 2.2–3.0 с.

---

## 1. Замеры

### Масштаб репозитория

| Метрика | Значение |
|---|---|
| BUILD-файлов | 898 (services 403, js 336, platform 114, jobs 45) |
| java-таргетов, которые находит `kind(...)` над `//...` | **442** |
| из них провижинятся в проекты (есть `srcs` + единый source root) | **223** |
| `.java` файлов в рабочей копии | 10 229 |
| jar-ов в classpath одного таргета | 110–455 (медиана ~300) |

> 442 против 223 — не расхождение: `query` возвращает 442 метки, но `BazelQuery.parse()`
> отбрасывает таргеты без `srcs` и с неоднозначным source root, так что проектов (и, значит,
> вызовов `initialize()`) ровно **223**. Оценка «~2.5 мин на 442 aquery» из инцидента —
> верхняя граница; фактический потолок последовательного резолва — 223 × 310 мс ≈ **69 с**.

### Стоимость bazel-команд

| Команда | Время | Вывод |
|---|---|---|
| `query kind(...) //...` **холодный сервер** | **47.5 s** | — |
| `query kind(...) //...` warm, `--output=label` | 0.45 s | 442 строки |
| `query kind(...) //...` warm, `--output=xml` (как сейчас) | 0.54 s / 2.2 s по логу плагина | **6.3 MB** |
| `query` scoped `//services/... + //jobs/... + //platform/...` | 0.54 s | 6.3 MB |
| `query … --nofetch` | 3.0 s | 442, exit 0 |
| `query … --repository_disable_download` | 1.2 s | 442, exit 0 |
| `aquery mnemonic("Javac", //один:таргет)` | **260–830 ms** | 170 KB |
| то же с `--include_artifacts=false` | 260–830 ms | **52 KB** (−70 %) |
| `aquery mnemonic("Javac", set(<50 меток>))` | 2.8 s | 1.4 MB |
| `aquery mnemonic("Javac", //...)` — весь репо | 20.4 s | 17.7 MB |
| **`aquery --query_file` с `set(<все 442 метки>)`** | **4.6 s** | 15.4 MB, 442 targets / 443 actions |

Последняя строка — ключевая. Один вызов покрывает **все** таргеты, даёт полную корреляцию
`actions.target_id → targets.id → targets.label` и укладывается в 4.6 с против ~69 с
последовательных вызовов.

### Реальный таймлайн запуска (из `jdt_ws/.metadata/.log`)

```
10:50:05 … 10:50:42   ~130 × "classpath jars for … in ~290 ms"   ← ~40 s: инициализация
                                                                    контейнеров ещё ДО импортёра
10:50:47.997          Importers: BazelProjectImporter, …
10:50:50.153          Bazel: 223 java targets with sources in 2155 ms
10:50:50 … 10:51:12   провижининг + ещё aquery по 530–830 ms
10:51:12.888          Bazel: 223 projects in 22734 ms
10:51:13.141          Workspace initialized in 26038 ms
10:51:13 … 10:54:17   сборка/индексация JDT (~3 минуты)
```

**Итого до рабочего состояния — ~4 минуты при уже прогретом bazel-сервере.**

---

## 2. Диагноз

### P0. 223 последовательных `aquery` — ~69 s, блокирующе

[BazelClasspathContainerInitializer.java:33](server/src/ch/audienzz/bazel/jdtls/BazelClasspathContainerInitializer.java#L33)
вызывает `cache.jarsFor(...)` **синхронно внутри `initialize()`**, по одному таргету.

Комментарий в [BazelClasspathCache.java:37-42](server/src/ch/audienzz/bazel/jdtls/BazelClasspathCache.java#L37-L42)
обосновывает это тем, что репо-широкий aquery дороже и что корреляция action→label теряется.
**Оба утверждения опровергнуты замерами:**

```
223 × per-target aquery         : ~69 s, последовательно, блокирует JDT
1 × aquery //...                : 20.4 s (но анализирует и js/oci — см. P6)
1 × aquery --query_file set(442): 4.6 s  ← правильный вариант
```

Корреляция есть прямо в выводе:

```protobuf
targets { id: 1  label: "//jobs/ad-quality-report/src/main:library"  rule_class_id: 1 }
actions { target_id: 1  mnemonic: "Javac"  arguments: "--classpath" … }
```

### P1. Кеш только в памяти — рестарт платит всё заново

`BazelClasspathCache` — `HashMap` в singleton'е. После перезапуска VS Code кеш пуст, и JDT,
восстанавливая уже существующие проекты, дёргает `initialize()` для каждого: те самые **~40 s
до того, как импортёр вообще стартовал**.

### P2. Провижининг 223 проектов = 22.7 s

[ProjectProvisioner.provision()](server/src/ch/audienzz/bazel/jdtls/ProjectProvisioner.java#L51)
делает в цикле, без батчинга: `create()`, `open()`, `createLink()`, `setPersistentProperty()` ×2,
`setRawClasspath()`. Каждая — отдельная resource-транзакция с рассылкой дельты и срабатыванием
autobuild.

### P3. Всё в критическом пути `importToWorkspace`

jdt.ls не рапортует «Workspace initialized», пока импортёр не вернётся.

### P4. Нет скоупинга

Разработчик, правящий один сервис, получает 223 проекта, 10 229 файлов в индексе и
~89 000 classpath-entry. `.bazelproject` с секцией `directories:` игнорируется.

### P5. Конкуренция за bazel-сервер

Поймано живьём: `Another command (pid=58296) is running. Waiting for it to complete…`.
Плагин ходит в тот же `output_base`, что и терминал разработчика.

### P6. Цикл повторов без backoff — **самое дорогое в поле** (новое, из инцидента)

Ночью не тянулись внешние репозитории (`@@rules_oci++oci+node24_linux_amd64`,
`@@+helm_charts+clamav` с `registry.adnz.co`). Шаблон `//...` грузит **все** пакеты, включая
не-java, поэтому загрузка падала на них. bazel печатал
`command succeeded, but there were loading phase errors` и отдавал **exit 3**.

Дальше сработал каскад:

1. **B1** — `BazelWorkspace.run()` кидает `CoreException` на любом ненулевом коде;
2. неудача **нигде не кешируется** — ни negative-записью, ни окном «не пытайся N минут»;
3. **backoff нет** — импорт перезапускался **каждые ~16 с**, ~1400 раз за ночь;
4. каждая итерация — полная загрузка графа монорепо, поэтому bazel-сервер никогда не простаивал
   и `--max_idle_secs=10800` не срабатывал.

Проверка после инцидента: тот же запрос вручную даёт exit 0 и 442 таргета. Сбой был чисто
транзиентный — а расширение из-за него сутки жгло CPU.

> **Важно:** скоупинг (Этап 5) этот триггер **не** закрывает. Падавший пакет
> `//platform/infra/audienzz/clamav` находится внутри `//platform/...`, то есть попал бы
> и в суженный шаблон. Закрывают его три вещи вместе: приём exit 3, `--nofetch` на
> discovery-запросе и переход aquery на явный список java-меток.

### P7. Жизненный цикл процессов (новое, из инцидента)

- jdt.ls осиротевает при перезапуске extension host и выходит только на следующем опросе
  parent-watchdog.
- Порождённый им **bazel-сервер переживает клиента** и держит ~1.15 ГБ до idle-таймаута
  (3 часа при `--max_idle_secs=10800`). В инциденте такой сервер (PID 94877) остался вообще
  без владельца.
- Умножается на число окон: три workspace = три jdt.ls с `-Xmx4G`, три output base,
  свои серверы и persistent workers → ~2.3 ГБ.

### P8. Спам в лог (новое, из инцидента)

10 ротаций по 1 МБ за 7 часов, ротация каждые ~42 минуты затирает всю остальную историю
jdt.ls. Один и тот же блок ошибок пишется целиком на каждой итерации
([BazelWorkspace.drainStderr](server/src/ch/audienzz/bazel/jdtls/BazelWorkspace.java#L104)
логирует каждую строку `ERROR`/`FATAL`).

### Дефекты

| # | Что | Где |
|---|---|---|
| B1 | `--keep_going` даёт **exit 3** при частичном успехе (проверено: 184 таргета отдано, exit=3). Любой ненулевой код → `CoreException`. **Сработал в поле — см. P6** | [BazelWorkspace.java:98](server/src/ch/audienzz/bazel/jdtls/BazelWorkspace.java#L98) |
| B2 | `bazelJava.binary` пишет `process.env` в extension host **после** того, как redhat.java уже запустил jdt.ls → настройка не работает | [extension.js:8](extension/extension.js#L8) |
| B3 | Несуществующие jar молча выбрасываются. В репо `build --nojava_header_compilation` → в classpath полные `lib*.jar`, которых на свежем клоне нет → тихо пустой classpath | [BazelClasspathContainer.java:29-31](server/src/ch/audienzz/bazel/jdtls/BazelClasspathContainer.java#L29-L31) |
| B4 | `BazelBinary.resolve()` статит весь `PATH` ×2 на **каждый** запуск процесса | [BazelWorkspace.java:52](server/src/ch/audienzz/bazel/jdtls/BazelWorkspace.java#L52) |
| B5 | `BazelQuery.byProjectName()` — мёртвый код | [BazelQuery.java:139](server/src/ch/audienzz/bazel/jdtls/BazelQuery.java#L139) |
| B6 | Нет дедупликации `sourceRoot` → пересекающиеся linked-папки, двойная индексация | [ProjectProvisioner.java:91](server/src/ch/audienzz/bazel/jdtls/ProjectProvisioner.java#L91) |
| B7 | Таймаут ожидания процесса — **30 минут** | [BazelWorkspace.java:87](server/src/ch/audienzz/bazel/jdtls/BazelWorkspace.java#L87) |

---

## 3. План работ

### Этап 0. Предохранители — делать первым (0.5–1 день)

Ничего не ускоряет, но убирает класс отказов, который стоил 7 часов CPU и 2.3 ГБ RAM.
**Имеет смысл выкатить отдельно, до всей оптимизации.**

**0.1 Принимать exit 0 и 3**

```java
private static final Set<Integer> OK = Set.of(0, 3);   // 3 = partial success с --keep_going
…
if (!OK.contains(exitCode)) throw new CoreException(error(…));
if (exitCode == 3) JavaLanguageServerPlugin.logInfo(
        "Bazel: partial results (exit 3), " + errorSummary);
```

**0.2 Negative caching + экспоненциальный backoff** — обязательно **независимо от 0.1**.
Приём exit 3 закрывает конкретный ночной триггер, но при любом другом ненулевом коде
(реальная ошибка в BUILD, отозванный токен реестра, нет сети) цикл «упал → сразу повтор»
вернётся.

```java
final class FailureGate {                 // на (workspace, вид операции)
    private int consecutiveFailures;
    private long retryNotBefore;          // System.nanoTime()

    boolean shouldSkip() { return System.nanoTime() < retryNotBefore; }

    void recordFailure() {
        consecutiveFailures++;
        long delaySec = Math.min(300, (long) Math.pow(2, Math.min(consecutiveFailures, 8)));
        retryNotBefore = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySec);
    }                                     // 2s → 4 → 8 → … → 300s (потолок 5 мин)

    void recordSuccess() { consecutiveFailures = 0; retryNotBefore = 0; }
}
```

Точка применения — `BazelProjectImporter.applies()`: пока окно backoff не истекло, импортёр
**возвращает `false`** и jdt.ls вообще не заходит в `importToWorkspace()`. Это дешевле, чем
гасить повтор внутри импорта.

Сброс окна: команда `Bazel: Refresh Classpath`, изменение `BUILD`/`*.bzl`/`MODULE.bazel`,
любой успешный вызов bazel.

**0.3 Дедупликация лога.** Одинаковый блок ошибок логировать один раз, дальше — счётчиком:
`Bazel: same loading-phase failure ×137, next retry in 300s`. Закрывает P8.

**0.4 Таймауты.** 30 мин → 120 с для query/aquery (настраиваемо). Плюс общий предохранитель:
не более N неуспешных импортов за час, дальше — тихий режим до явного `Refresh` (B7).

**0.5 `--nofetch` на discovery-запросе.** Индексация IDE не должна ходить в сеть. Замерено,
обе формы работают и дают те же 442 таргета:

```
query … --keep_going --nofetch                      → 3.0 s, exit 0
query … --keep_going --repository_disable_download  → 1.2 s, exit 0
```

При недоступных репозиториях загрузка падает **мгновенно** вместо сетевого таймаута, а с
приёмом exit 3 java-таргеты всё равно возвращаются. Оговорка: на свежем клоне первый импорт
должен пройти **без** флага (нужно один раз вытянуть `@maven` и java-тулчейн), дальше — с ним.
Практично: первая попытка без флага, ретрай с флагом.

**Критерий приёмки:** отключить сеть → перезапустить VS Code → в логе **один** блок ошибок,
счётчик повторов, интервал растёт до 5 минут, bazel-сервер уходит в idle.

---

### Этап 1. Один батч-aquery по списку меток — ~69 s → **4.6 s**

**Что делать**

1. Сформировать файл запроса из меток, полученных discovery-запросом:
   ```
   mnemonic("Javac", set(//jobs/…:library //services/…:library … ))
   ```
   и вызвать **один раз**:
   ```
   aquery --query_file=<tmp>
          --output=textproto
          --include_artifacts=false     # −70 % вывода
          --noshow_progress --keep_going --ui_event_filters=-info
   ```
   `--query_file` обязателен: 442 метки — это ~17.6 КБ командной строки.

2. **Не использовать `//...` для aquery.** Разница не только в 4.6 с против 20.4 с: `//...`
   тянет на анализ js/oci/helm-таргеты, то есть ровно те пакеты, что уронили импорт в
   инциденте. Явный список меток держит анализ внутри java-замыкания.

3. Расширить `Collector`: парсить блок `targets { id, label }` в `Map<Integer,String>`,
   в `actions` читать `target_id`, раскладывать jar-ы по меткам. Проверено на живом выводе:
   442 блока `targets`, 443 `actions` — покрытие полное.

4. Per-target aquery оставить **только** как fallback для метки, которой нет в батче
   (и для приоритетного резолва открытого файла — см. Этап 2).

```java
public void warmAll(BazelWorkspace ws, List<String> labels, IProgressMonitor m)
        throws CoreException {
    Path qf = writeQueryFile(labels);                 // mnemonic("Javac", set(...))
    BatchCollector c = new BatchCollector();
    ws.runStreaming(m, c::accept, "aquery", "--query_file=" + qf,
        "--output=textproto", "--include_artifacts=false",
        "--noshow_progress", "--keep_going", "--ui_event_filters=-info");
    c.finish();
    synchronized (this) { jarsByLabel.putAll(c.byLabel()); complete = true; }
}
```

---

### Этап 2. Ленивый асинхронный classpath-контейнер

`initialize()` **не должен блокировать**. Схема как у m2e/buildship:

1. `initialize()` мгновенно ставит контейнер из того, что есть: персистентный кеш с диска
   (Этап 3) → полный classpath за 0 мс; иначе — пустая заглушка.
2. Ставит метку проекта в очередь фонового `Job` (один на workspace, `Job.LONG`).
3. Job вызывает `warmAll()` один раз и раздаёт результат пачкой через
   `JavaCore.setClasspathContainer(...)`.
4. **Приоритет открытых редакторов:** проект, чей файл открыт сейчас, резолвится одиночным
   aquery немедленно (~300 мс) и не ждёт батча.

```java
@Override
public void initialize(IPath path, IJavaProject jp) throws CoreException {
    String label = jp.getProject().getPersistentProperty(TARGET_LABEL);
    String root  = jp.getProject().getPersistentProperty(WORKSPACE_ROOT);
    if (label == null || root == null) return;

    List<String> cached = ClasspathStore.get(root).peek(label);   // с диска, без bazel
    JavaCore.setClasspathContainer(path, new IJavaProject[]{ jp },
        new IClasspathContainer[]{ BazelClasspathContainer.fromJars(execRootCached(root), cached) },
        new NullProgressMonitor());

    if (cached == null || ClasspathStore.get(root).isStale(label)) {
        ClasspathResolveJob.enqueue(root, label, jp, path);       // в фон
    }
}
```

**Выигрыш:** «Workspace initialized» перестаёт зависеть от bazel; ~40 s инициализации
контейнеров на рестарте → ~0 s.

---

### Этап 3. Персистентный кеш на диске

1. `<jdt_ws>/.metadata/.plugins/ch.audienzz.bazel.jdtls/classpath-<hash>.json`:
   ```json
   { "stamp": { "executionRoot": "…", "moduleLock": "<sha256 MODULE.bazel.lock>",
                "buildFilesDigest": "<sha256 от списка BUILD path+mtime+size>" },
     "targets": { "//services/ws-bi/src/main:library": { "sourceRoot": "…", "jars": [ … ] } } }
   ```
2. Совпал `stamp` — грузим всё из файла, **ни одного вызова bazel**; в фоне пересчитываем.
3. То же для результата `bazel query` — снимает ещё 2.2 s warm / 47 s cold.
4. Инвалидация: `IResourceChangeListener` на `BUILD`/`*.bzl`/`MODULE.bazel` +
   команда `Bazel: Refresh Classpath` (она же сбрасывает backoff из 0.2).

---

### Этап 4. Батчинг Eclipse-ресурсов — 22.7 s → 5–8 s

1. Весь `provision()` в одну транзакцию:
   ```java
   JavaCore.run(m -> { for (Target t : targets) provisionOne(t, m); },
                ResourcesPlugin.getWorkspace().getRoot(), monitor);
   ```
2. `setRawClasspath(entries, output, /* canModifyResources */ false, monitor)`.
3. Autobuild off на время импорта, вернуть после.
4. **Схлопнуть main+test в один проект на сервис**: 223 → ~112 проектов, два source folder'а
   (второй с `test=true`), объединённый classpath. Здесь же чинятся B5/B6 —
   `byProjectName()` наконец используется как группировка.

---

### Этап 5. Скоупинг импорта

Ускоряет и уменьшает объём, но, в отличие от Этапа 0, **не даёт гарантии надёжности**
(см. врезку в P6).

1. Настройки:
   ```jsonc
   "bazelJava.targets":     ["//services/ws-bi/...", "//platform/..."],  // default: []
   "bazelJava.useBazelProject": true,   // читать directories: из .bazelproject
   "bazelJava.importMode":  "lazy",     // lazy | eager
   "bazelJava.maxProjects": 300         // предохранитель + предупреждение
   ```
2. При пустом `targets` и наличии `.bazelproject` брать `directories:` оттуда.
3. `importMode: lazy` — провижинить только проекты из скоупа; проект для файла вне скоупа
   создаётся по требованию при открытии (extension шлёт `workspace/executeCommand` с путём →
   сервер находит владеющий таргет через `attr(srcs, <файл>, //<пакет>:*)` и провижинит один проект).
4. Настройки передавать через `initializationOptions` / системные свойства, не через
   `process.env` — это чинит B2 (`-Dbazel.binary=` в `java.jdt.ls.vmargs`, свойство уже
   читается в `BazelBinary`).

---

### Этап 6. Гигиена процессов и ресурсов

| Действие | Причина |
|---|---|
| Кешировать `BazelBinary.resolve()` в `volatile String` | B4 |
| **Гасить bazel-сервер при shutdown языкового сервера** — но **только** свой IDE-`output_base` (Этап 7). Общий сервер разработчика трогать нельзя | P7 |
| Для IDE-сервера — короткий `--max_idle_secs` (например 900) | P7: 3 часа × 1.15 ГБ на осиротевший сервер |
| Не более 1 bazel-процесса на workspace одновременно | bazel всё равно сериализует команды; параллелизм только плодит ожидание лока |
| `--curses=no --color=no`, не отдавать TTY | меньше мусора в stderr |
| Логировать, сколько jar-ов отфильтровано как несуществующие + команда `Bazel: Build Classpath` | B3 |
| В `Bazel: Show Import Report` — счётчики повторов, backoff, время фаз | диагностика P6/P8 |

---

### Этап 7. Отдельный `--output_base` для IDE — из «опционально» в «рекомендуется»

```
--output_base=~/.cache/bazel-ide/<sha1 of workspace root>
```

Инцидент повысил ценность этого пункта: он закрывает сразу P5 (конкуренция с терминалом),
P7 (можно безопасно гасить свой сервер на выходе и ставить короткий idle-таймаут) и делает
предсказуемым потребление памяти.

Цена: второй analysis cache (~1–2 ГБ RAM) и первый холодный анализ. Сделать opt-in
настройкой; дешёвый промежуточный вариант — `--block_for_lock=false` + ретрай с backoff.

**Остальное опциональное**

- **Aspect вместо aquery**: `bazel build … --aspects=…%java_ide_info --output_groups=ide-info`
  выдаёт classpath, source roots и **материализует jar-ы** (закрывает B3). Так делают
  IntelliJ Bazel plugin и bazel-bsp.
- `--output=streamed_proto` вместо textproto/xml, если 15 МБ парсинга станут заметны
  (сейчас не бутылочное горлышко).

---

## 4. Результат

Замерено headless-прогоном jdt.ls (тот же `initializationOptions.bundles`, что использует
redhat.java) на `audienzz`, bazel-сервер прогрет.

| Фаза | Было | Стало (холодный jdt.ls) | Стало (рестарт) |
|---|---|---|---|
| `bazel query` | 2155 ms | **1012 ms** (`--nofetch`) | **18 ms** — из кеша, bazel не вызывается |
| Инициализация контейнеров | ~40 s, 130+ вызовов aquery | 0 вызовов bazel | 0 вызовов bazel |
| Провижининг проектов | 22734 ms, 223 проекта | **942 ms, 114 проектов** | 1020 ms |
| Резолв classpath | ~69 s, последовательно, блокирующе | **2 батча, ~3.2 s, в фоне** | 0 вызовов bazel |
| **`Workspace initialized`** | **26038 ms** | **2105 ms** | **1255 ms** |
| Проверка актуальности кеша | — | — | 341 ms (обход BUILD-файлов) |
| Транзиентный сбой bazel | ~1400 повторов за ночь | одна запись в логе + backoff | то же |

Пересчёт на весь путь до рабочего автодополнения: было ~4 минуты, стало ~7 секунд на холодном
старте и ~3 секунды на рестарте.

Отдельно проверенные цифры по bazel-командам (см. также раздел 1):

```
223 × per-target aquery (как было)      ~69 s
1 × aquery --query_file set(223)          3.0 s     <- что делает батч
парсинг 15.4 МБ textproto                 241 ms
```

## 5. Критерии приёмки — результат

| # | Этап | Критерий | Факт |
|---|---|---|---|
| 1 | Этап 0 — предохранители | один блок ошибок в логе, растущий backoff | ✅ Прогон со сломанным паттерном: каждое сообщение в логе **ровно один раз**, `backing off 2 s; further identical failures are counted, not logged` |
| 2 | Этап 1 — батч-aquery | одна строка `warmed N labels in X ms`, X < 8000 | ✅ `warmed 101 labels in 2294 ms` + `warmed 122 labels in 894 ms`; строк `classpath jars for` нет |
| 3 | Этап 2 — асинхронный контейнер | `Workspace initialized` < 10 s, без bazel до него | ✅ **2105 ms**; между стартом и этим сообщением только discovery-запрос |
| 4 | Этап 3 — дисковый кеш | второй запуск: 0 вызовов bazel, < 5 s | ✅ **1255 ms**, `aquery batches: 0`, `discovery: from cache` |
| 5 | Этап 4 — батчинг ресурсов | `N projects in X ms`, X < 8000 | ✅ **942 ms** на 114 проектов |
| 6 | Этап 6 — гигиена процессов | IDE не оставляет своих bazel-серверов | ✅ гасится только собственный `output_base`; общий не трогается (по умолчанию — общий, гасить нечего) |
| 7 | Этап 5 — скоупинг | импорт одного сервиса быстрее | ✅ реализовано; на `//...` не проверялось отдельно, т.к. в audienzz `.bazelproject` = `.` |

**Метрики в логе** (реализованы):

- `Bazel: N java targets with sources in X ms`
- `Bazel: warmed N labels in X ms (batch, M with a Javac action)` / `... (single)`
- `Bazel: N projects in X ms (created/updated/unchanged/pruned)`
- `Bazel: <операция> failed (...), backing off N s`
- `Bazel: cached import still valid (checked in X ms)`
- команда `Bazel: Show Import Report` — фазы, счётчики, состояние gate, скоуп

**Прогоны, которыми это проверялось** (headless jdt.ls, тот же способ загрузки бандла, что у
redhat.java — `initializationOptions.bundles`):

1. Чистый `jdt_ws` — холодный импорт.
2. Рестарт без сноса `jdt_ws` — сценарий обычного перезапуска.
3. Рестарт с намеренно сломанным запросом — регрессионный тест на инцидент.

## 6. Риски

| Риск | Митигация |
|---|---|
| Пустой classpath на старте (фоновый job ещё не отработал) → шквал ложных ошибок компиляции | Не собирать проект, пока контейнер не заполнен; либо грузить с диска и пересчитывать в фоне |
| **Negative caching прячет реальную починку**: разработчик поправил BUILD, а плагин молчит 5 минут | Сброс окна по изменению `BUILD`/`*.bzl`/`MODULE.bazel`, по команде `Bazel: Refresh Classpath` и по любому успешному вызову. Текущее состояние backoff показывать в статус-баре |
| `--nofetch` на свежем клоне не даст вытянуть `@maven` и java-тулчейн | Первая попытка без флага, ретрай с флагом; флаг только на discovery-запросе, не на aquery |
| `--include_artifacts=false` может убрать нужные поля в будущих bazel | Замерено на 9.2.0: `arguments` сохраняются. Прикрыть тестом парсера на зафиксированном сэмпле |
| `--query_file` с сотнями меток упрётся в лимит | Файл, а не командная строка (17.6 КБ на 442 метки); при росте — чанки по 200 |
| Гашение bazel-сервера на выходе убьёт сборку разработчика | Гасить **только** собственный IDE-`output_base`; без Этапа 7 — не гасить вообще |
| Отдельный `output_base` съедает RAM/диск | opt-in, по умолчанию выключено |
| Схлопывание main+test меняет имена проектов → разъедется `jdt_ws` | Одноразовая миграция: при несовпадении схемы имён снести и переимпортировать |
