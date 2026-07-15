# Как загрузить исходники alpha.17 в GitHub

Важно: в корне репозитория должны лежать `build.gradle`, `settings.gradle`, папки `src`, `.github` и `docs`. Не загружайте папку `CityCore-alpha17` как единственную вложенную папку.

## Вариант через сайт GitHub

1. Распакуйте ZIP с исходниками.
2. Откройте репозиторий `AndrewTheAnDrEyKa/CityCoreAddonToEssentialsX`.
3. Нажмите **Add file → Upload files**.
4. Перетащите содержимое папки `CityCore-alpha17`, включая `.github` и `.gitignore`.
5. Убедитесь, что `build.gradle` показывается в корне будущего коммита.
6. В сообщении коммита укажите `Release alpha.17.0 sources`.
7. Нажмите **Commit changes**.
8. Откройте вкладку **Actions** и дождитесь зелёного выполнения `Build CityCore`.
9. В завершившемся run скачайте artifact `CityCore-0.1.0-alpha.17.0`.

## Надёжный вариант через Git

```bash
git clone https://github.com/AndrewTheAnDrEyKa/CityCoreAddonToEssentialsX.git CityCore-repo
cd CityCore-repo
```

Скопируйте внутрь `CityCore-repo` содержимое распакованной папки `CityCore-alpha17`. Не удаляйте папку `.git` внутри клона.

Затем:

```bash
git status
git add -A
git status
git commit -m "Release alpha.17.0 sources"
git push origin main
```

Перед `commit` второй `git status` должен показывать изменения исходников, миграций, ресурсов, тестов и документации. Он не должен показывать удаление `.github/workflows/build.yml` или всей папки `src`.
