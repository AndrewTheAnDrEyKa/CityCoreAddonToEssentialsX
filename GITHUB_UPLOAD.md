# Обновление репозитория исходниками alpha.17.1

Загрузка через **Add file → Upload files** не подходит для этой операции: GitHub может перезаписать только выбранную часть дерева и оставить старые вложенные файлы. В результате получается гибрид разных версий.

Ниже используется отдельный свежий clone. Существующая резервная папка и её `.git` не изменяются.

## 1. Распаковать исходники

Распакуйте архив `CityCore-0.1.0-alpha.17.1-source-hotfix.zip`. Внутри должна находиться папка `CityCore-alpha17`.

## 2. Создать отдельный свежий clone

Откройте PowerShell:

```powershell
cd C:\Users\Legion\Desktop\Repository
git clone https://github.com/AndrewTheAnDrEyKa/CityCoreAddonToEssentialsX.git CityCoreAlpha17Clean
cd CityCoreAlpha17Clean
```

Старая папка `CityCoreAddonToEssentialsX` остаётся резервной копией. Все дальнейшие команды выполняются только в новой папке `CityCoreAlpha17Clean`.

## 3. Удалить старое дерево из нового clone

```powershell
git rm -r .
```

Команда не удаляет `.git`. Она только готовит отдельный новый commit, который удалит старые и смешанные файлы репозитория.

## 4. Скопировать чистые исходники

Укажите реальный путь к распакованной папке `CityCore-alpha17`:

```powershell
$source = "C:\Users\Legion\Downloads\CityCore-alpha17"
Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination . -Recurse -Force
```

Параметр `-Force` важен: он копирует `.github` и `.gitignore`.

## 5. Добавить и проверить замену

```powershell
git add -A
git status
```

Затем выполните проверки:

```powershell
Select-String -Path .github\workflows\build.yml -Pattern "alpha.17.1"
Select-String -Path src\main\resources\plugin.yml -Pattern "citycoreadmin"
Select-String -Path src\main\resources\config.yml -Pattern "config-version: 3"
Test-Path src\main\java\ru\citycore\economy\EmissionService.java
Test-Path src\main\java\ru\citycore\permission\Capability.java
Test-Path docs\ideas\settlements.md
```

Ожидаемый результат:

- первые три команды находят нужные строки;
- последние три команды выводят `True`;
- `git status` показывает замену файлов полным деревом alpha.17.1;
- `.github/workflows/build.yml` не остаётся удалённым.

Если проверка не совпала, не выполняйте commit. Исходное состояние нового clone можно просто удалить вместе с папкой `CityCoreAlpha17Clean` и начать заново; резервная папка не затрагивается.

## 6. Отправить чистую версию

```powershell
git commit -m "Fix optional LuckPerms startup in alpha.17.1"
git push origin main
```

После push откройте новый GitHub Actions run. В шаге загрузки artifact должны использоваться:

```text
CityCore-0.1.0-alpha.17.1
build/libs/CityCore-0.1.0-alpha.17.1.jar
```

Готовый artifact скачивается только после зелёного выполнения workflow.
