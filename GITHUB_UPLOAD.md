# Как загрузить исходники Alpha 19

Архив `CityCore-0.1.0-alpha.19.0-source.zip` содержит полное дерево проекта в папке `CityCore-alpha19`. В репозиторий копируется **содержимое этой папки**, а не сама внешняя папка.

1. Сохраните резервную копию текущего локального репозитория.
2. Удалите из рабочей папки старые файлы проекта, но не папку `.git`.
3. Скопируйте всё содержимое `CityCore-alpha19` в корень репозитория. В корне должны лежать `build.gradle`, `README.md`, `.github`, `src` и `docs`.
4. Проверьте, что `.runtime-check` в репозиторий не копировалась.
5. В PowerShell из корня репозитория выполните только:

```powershell
git add -A
git commit -m "Rebuild CityCore GUI for alpha.19.0"
git push origin main
```

Перед commit можно быстро проверить версию:

```powershell
Select-String -Path build.gradle -Pattern "alpha.19.0"
Select-String -Path .github\workflows\build.yml -Pattern "alpha.19.0"
Test-Path src\main\java\ru\citycore\gui\GuiIcon.java
Test-Path docs\alpha19-progress.md
```

Ожидается `alpha.19.0` в первых двух ответах и `True` в последних двух. После зелёного Actions artifact называется `CityCore-0.1.0-alpha.19.0` и содержит `CityCore-0.1.0-alpha.19.0.jar`.
