# LABstore

Sklep z aplikacjami DK-LAB na Androida, w wyglądzie Google Play. Lista aplikacji to [`catalog.json`](catalog.json), a pliki APK pochodzą z wydań (Releases) repozytoriów poszczególnych aplikacji.

**Funkcje:** instalacja i aktualizacja jednym przyciskiem, pobieranie w tle z powiadomieniem i wznawianiem po przerwie, weryfikacja SHA-256 z GitHuba, powiadomienia o nowych wersjach, historia wersji, wersje testowe (pre-release), pełnoekranowe zrzuty, samoaktualizacja.

## Instalacja

Pobierz `labstore.apk` z [Releases](../../releases/latest). Przy pierwszej instalacji aplikacji z LABstore Android poprosi o zgodę na instalowanie aplikacji z tego źródła.

## Dodanie aplikacji do LABstore

Dopisz wpis do `catalog.json` i wypchnij zmianę. LABstore pobierze nowy katalog przy następnym odświeżeniu, bez przebudowy:

```json
{
  "package": "com.przyklad.app",
  "name": "Nazwa",
  "repo": "gangg111/Repozytorium",
  "category": "Narzędzia",
  "summary": "Jedno zdanie na listę",
  "description": "Pełny opis na stronę aplikacji",
  "icon": "icons/nazwa.png",
  "screenshots": ["https://raw.githubusercontent.com/..."],
  "apk": "nazwa-pliku.apk"
}
```

- `apk` jest opcjonalne. Bez niego LABstore bierze pierwszy plik `.apk` z wydania.
- Wersja to tag wydania (np. `v1.2.0`) porównywany z `versionName`. Dokładniej działa plik `<nazwa-apk>.json` z polem `versionCode` dołączony do wydania obok APK.
- Wydanie oznaczone jako pre-release widać tylko po włączeniu „Pokaż wersje testowe”.

## Budowanie

`./gradlew :app:assembleRelease` (compileSdk 34, Kotlin + Compose). Podpis własnym kluczem spoza repozytorium.
