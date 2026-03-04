# Android BLE Project — Smart Sensor Hub

## Overview

Smart Sensor Hub to przykładowa aplikacja Android, która pokazuje komunikację z urządzeniami peryferyjnymi Bluetooth Low Energy (BLE) na poziomie produkcyjnym.  
Projekt obejmuje pełny przepływ: skanowanie, łączenie, odkrywanie usług GATT, odczyt/zapis charakterystyk, notyfikacje, kolejkę operacji, reconnection, pracę w tle oraz persystencję danych i wizualizację.

Technologie: **Kotlin, Coroutines, Flow, Jetpack Compose, Hilt, Room, BluetoothGatt API**.

---

## Kontekst działania aplikacji

Aplikacja łączy się z urządzeniem BLE (np. ESP32/nRF52 lub symulator), które udostępnia:

- charakterystykę odczytu (np. temperatura, wilgotność, tętno),
- charakterystykę zapisu (np. konfiguracja interwału pomiarów),
- notyfikacje (ciągły stream danych),
- opcjonalnie tryb DFU (aktualizacja firmware).

Główne scenariusze:

- **Użytkownik**:
    - skanuje dostępne urządzenia BLE,
    - wybiera urządzenie i nawiązuje połączenie,
    - obserwuje bieżące dane z sensora (notyfikacje),
    - przegląda historię pomiarów na wykresach,
    - może zmienić parametry pracy urządzenia (zapis do charakterystyki).

- **System**:
    - zarządza kolejką operacji GATT (read/write/notify),
    - utrzymuje stabilne połączenie (reconnect, timeouty),
    - zapisuje dane w lokalnej bazie (Room),
    - utrzymuje połączenie w tle (foreground service).

---

## Backlog

### EPIC 1: Device Discovery and Connection

- **Scan for BLE devices**
    - Lista urządzeń aktualizuje się dynamicznie.
    - Możliwość filtrowania po nazwie/UUID usług.
    - Obsługa braku uprawnień i wyłączonego Bluetooth.

- **Connect to a selected device**
    - Użytkownik wybiera urządzenie z listy i inicjuje połączenie.
    - Stan połączenia jest widoczny (Connecting / Connected / Disconnected / Error).
    - Błędy połączenia są prezentowane w UI i logowane.

- **Discover GATT services**
    - Po połączeniu aplikacja wykonuje `discoverServices()`.
    - Wszystkie wymagane usługi i charakterystyki są odnajdywane i mapowane do modelu domenowego.
    - Błędy discovery są logowane i sygnalizowane w stanie połączenia.

---

### EPIC 2: BLE Communication Core

- **Operation queue**
    - Wszystkie operacje GATT (read/write/enable notifications) przechodzą przez kolejkę FIFO.
    - Każda operacja ma timeout; po przekroczeniu zgłaszany jest błąd.
    - Brak równoległych operacji na tym samym `BluetoothGatt` (unikanie race condition).

- **Read characteristics**
    - Użytkownik może odczytać aktualną wartość wybranej charakterystyki.
    - Wartości są prezentowane w UI w czytelnej formie (np. °C, %).
    - Błędy odczytu są obsługiwane (toast/snackbar + log).

- **Receive notifications**
    - Aplikacja włącza notyfikacje/indykacje dla wybranych charakterystyk.
    - Dane napływają w czasie rzeczywistym i są emitowane jako `Flow`.
    - UI aktualizuje się na bieżąco (np. wykres/ostatnia wartość).

---

### EPIC 3: Data Persistence and Visualization

- **Store sensor data in Room**
    - Każdy odebrany pomiar jest zapisywany w lokalnej bazie (Room).
    - Dane są powiązane z urządzeniem (np. po adresie MAC/ID).
    - Dane przetrwają restart aplikacji.

- **Display charts of historical data**
    - Użytkownik może zobaczyć historię pomiarów w formie wykresu (np. liniowego).
    - Wykres aktualizuje się wraz z napływem nowych danych.
    - Możliwość filtrowania po zakresie czasu (np. ostatnia godzina/dzień).

---

### EPIC 4: Background Operation

- **Keep connection alive in background**
    - Aplikacja uruchamia foreground service z notyfikacją systemową.
    - Po zminimalizowaniu aplikacji połączenie BLE pozostaje aktywne.
    - System nie ubija procesu bez wyraźnego powodu (zgodnie z ograniczeniami Androida).

- **Automatic reconnection**
    - W przypadku utraty połączenia aplikacja podejmuje próby reconnectu.
    - Próby są logowane (z timestampem i przyczyną).
    - Stosowana jest strategia backoff (np. rosnący interwał).

---

### EPIC 5: Advanced BLE Features

- **Request MTU change**
    - Użytkownik/system może zażądać zmiany MTU (np. 247).
    - Po udanej zmianie MTU nowa wartość jest widoczna w UI/logach.
    - W przypadku błędu użytkownik otrzymuje informację.

- **Bond with device**
    - Aplikacja może zainicjować parowanie/bonding z urządzeniem.
    - Stan bondingu jest widoczny (Not bonded / Bonding / Bonded).
    - Obsługa przypadków: odrzucenie, usunięcie bondingu.

---

## Architecture Diagram (ASCII)

```text
          +-----------------------+
          |       UI Layer        |
          |   Jetpack Compose     |
          +-----------+-----------+
                      |
                      v
          +-----------------------+
          |     Domain Layer      |
          | Use Cases (Connect,   |
          | Read, Write, Notify)  |
          +-----------+-----------+
                      |
                      v
          +-----------------------+
          |      Data Layer       |
          | BLE Repo + Room Repo  |
          +-----------+-----------+
                      |
                      v
          +-----------------------+
          |       BLE Layer       |
          | GATT, Queue, State    |
          +-----------------------+
