# LABstore

Sklep z aplikacjami DK-LAB na Androida, w wyglądzie Google Play. Lista aplikacji to `catalog.json` w osobnym repozytorium [gangg111/LABstore-katalog](https://github.com/gangg111/LABstore-katalog), a pliki APK pochodzą z wydań (Releases) repozytoriów poszczególnych aplikacji.

**Funkcje:** instalacja i aktualizacja jednym przyciskiem, pobieranie w tle z powiadomieniem i wznawianiem po przerwie, weryfikacja SHA-256 z GitHuba, powiadomienia o nowych wersjach, historia wersji, wersje testowe (pre-release), pełnoekranowe zrzuty, samoaktualizacja.

## Instalacja

Pobierz `labstore.apk` z [Releases](../../releases/latest). Przy pierwszej instalacji aplikacji z LABstore Android poprosi o zgodę na instalowanie aplikacji z tego źródła.

## Dodanie aplikacji do LABstore

Dopisz wpis do `catalog.json` w [LABstore-katalog](https://github.com/gangg111/LABstore-katalog) i wypchnij zmianę. LABstore pobierze nowy katalog przy następnym odświeżeniu, bez wydawania nowej wersji. Format wpisu opisuje README tamtego repozytorium.

## Budowanie

`./gradlew :app:assembleRelease` (compileSdk 34, Kotlin + Compose). Podpis własnym kluczem spoza repozytorium.
