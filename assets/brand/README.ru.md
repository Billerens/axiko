[English](README.md) · Русский

# Исходники маскота

`mascot/poses/` содержит утверждённые WebP без потерь и JSON-настройки трассировки, из которых
собирается поставляемый SVG-маскот. Это входные данные сборки, а не неиспользуемые референсы.
Сохраняйте изображение из каждого поля `source`, в том числе помеченное `skip`, если оно служит
источником для другой позы.

Из корня репозитория:

```powershell
python scripts/brand/build.py
python scripts/brand/icon.py
python scripts/brand/preview.py build/artifacts/brand/preview
```

Первая команда записывает SVG в `app/src/main/assets/factory/ui/mascot/` и лист сравнения в
`build/artifacts/brand/poses-sheet.png`. Вторая пересобирает слои иконок launcher. Обе меняют
поставляемые ресурсы: просмотрите результат перед коммитом. Preview использует подставную
оболочку и не проверяет нативную Android-разметку.

Скриптам нужны Pillow, NumPy, Potracer и Playwright с подходящим Chromium-браузером.
Конфигурация, токены темы и запасное поведение описаны в [mascot contracts](../../docs/brand.md)
([русский перевод](../../docs/brand.ru.md)). Сравнительные изображения, отвергнутые варианты и
исторические задания не входят в поддерживаемую документацию.
