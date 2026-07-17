# Как загрузить исходники Alpha 21.3

Архив `CityCore-0.1.0-alpha.21.3-sources.zip` содержит полное дерево проекта. В корень локального репозитория копируется **содержимое папки `CityCore-alpha21.3`**, а не сама внешняя папка.

1. Откройте ваш локальный репозиторий.
2. Сохраните его резервную копию.
3. Удалите старые файлы проекта, но не удаляйте скрытую папку `.git`.
4. Распакуйте архив и скопируйте содержимое `CityCore-alpha21.3` в корень репозитория.
5. В корне должны лежать `build.gradle`, `settings.gradle`, `README.md`, `.github`, `src` и `docs`.
6. Выполните:

```powershell
git add -A
git commit -m "CityCore Alpha 21.3 oil data repair"
git push origin main
```

Быстрая проверка перед commit:

```powershell
Select-String -Path build.gradle -Pattern "alpha.21.3"
Select-String -Path .github\workflows\build.yml -Pattern "alpha.21.3"
Test-Path src\main\java\ru\citycore\gui\GuiService.java
Test-Path docs\alpha21.3-progress.md
```

Ожидается `alpha.21.3` в первых двух ответах и `True` в последних двух. После зелёного Actions artifact называется `CityCore-0.1.0-alpha.21.3` и содержит одноимённый JAR.
