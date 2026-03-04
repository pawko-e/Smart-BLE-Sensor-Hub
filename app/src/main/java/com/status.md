# Android BLE Project - Smart Sensor Hub (README rozszerzony)

Ten plik rozszerza `app/src/main/java/com/readme.md` o wstawki pokazujące, gdzie dana funkcjonalność jest realizowana w kodzie oraz czy implementacja jest pelna.

## Kontekst dzialania aplikacji

Aplikacja laczy sie z urządzeniem BLE (np. ESP32/nRF52), obsługuje skanowanie, laczenie, GATT, odczyt/zapis/notyfikacje, persystencje danych i prosty wykres historii.

**Implementacja w kodzie**
- Wejscie UI: `app/src/main/java/com/appmstudio/bletutorial/ui/BleScreen.kt`
- Logika stanu: `app/src/main/java/com/appmstudio/bletutorial/ui/BleViewModel.kt`
- Warstwa BLE: `app/src/main/java/com/appmstudio/bletutorial/ble/BleRepositoryImpl.kt`
- Room: `app/src/main/java/com/appmstudio/bletutorial/data/db/*`, `app/src/main/java/com/appmstudio/bletutorial/data/repository/SensorRepositoryImpl.kt`
- Foreground service: `app/src/main/java/com/appmstudio/bletutorial/ble/BleForegroundService.kt`

---

## Backlog + wstawki implementacyjne

### EPIC 1: Device Discovery and Connection

#### 1. Scan for BLE devices
- Dynamiczna lista urzadzen: **TAK**
  - `BleRepositoryImpl.startScan()` aktualizuje `_scannedDevices` na podstawie callbackow skanera.
- Filtrowanie po nazwie/UUID uslug: **CZESCIOWO**
  - Filtrowanie po nazwie: `startScan(nameFilter)` + `ScanFilter.Builder().setDeviceName(...)`.
  - Filtrowanie po UUID uslug: **BRAK** (nie ma `setServiceUuid(...)`).
- Obsluga braku uprawnien i wylaczonego Bluetooth: **CZESCIOWO**
  - Uprawnienia runtime: `BleScreen` (`RequestMultiplePermissions`).
  - Brak skanera: `ConnectionState.Error("Bluetooth scanner unavailable")`.
  - Dedykowana obsluga "Bluetooth disabled" (prompt/intencja wlaczenia): **BRAK**.

#### 2. Connect to a selected device
- Wybor urzadzenia i inicjacja polaczenia: **TAK**
  - UI: `DeviceRow` -> `onConnect` w `BleScreen.kt`.
  - VM: `BleViewModel.connect(device)`.
  - Repo: `BleRepositoryImpl.connect(address)`.
- Widoczny stan polaczenia: **TAK**
  - `ConnectionState` (`Connecting/Connected/Disconnected/Error`) + wyswietlanie w UI.
- Bledy polaczenia w UI i logowane: **CZESCIOWO**
  - W UI: `errorMessage` w `BleViewModel` i tekst bledu w `BleScreen`.
  - Logowanie (`Log.*`) bledow: **BRAK**.

#### 3. Discover GATT services
- `discoverServices()` po polaczeniu: **TAK**
  - `onConnectionStateChange` -> `gatt.discoverServices()`.
- Mapowanie wymaganych uslug/charakterystyk: **TAK**
  - `mapCharacteristics(...)` + fallback po `properties`.
- Bledy discovery logowane i sygnalizowane: **CZESCIOWO**
  - Sygnalizacja stanu: `ConnectionState.Error("Service discovery failed: ...")`.
  - Logowanie: **BRAK**.

### EPIC 2: BLE Communication Core

#### 4. Operation queue
- FIFO dla read/write/notify: **TAK**
  - `operationChannel = Channel<GattOperation>(UNLIMITED)` + pojedynczy procesor `for (op in operationChannel)`.
- Timeout operacji: **TAK**
  - `withTimeoutOrNull(10_000)` + ustawienie bledu.
- Brak rownoleglych operacji na tym samym GATT: **TAK**
  - `activeOperation` + sekwencyjne wykonywanie w jednym jobie.

#### 5. Read characteristics
- Manualny odczyt z UI: **TAK**
  - Przycisk `Read` -> `BleViewModel.readNow()` -> `BleRepository.read()`.
- Czytelna prezentacja jednostek (np. C, %): **CZESCIOWO**
  - Pokazywana jest liczba (`latestReading.value`), ale bez jednostek.
- Obsluga bledow odczytu (toast/snackbar + log): **CZESCIOWO**
  - Wspolny kanal bledu stanu jest wyswietlany tekstowo.
  - Brak dedykowanego toast/snackbar i brak logowania.

#### 6. Receive notifications
- Wlaczanie notify/indicate: **TAK**
  - `setNotificationsEnabled` -> `setNotify` + zapis CCCD.
- Real-time stream jako Flow: **TAK**
  - `_notifications: MutableSharedFlow<ByteArray>`.
- UI aktualizuje sie na biezaco: **TAK**
  - `BleViewModel` zbiera notyfikacje, zapisuje odczyt i aktualizuje `latestReading`/`history`.

### EPIC 3: Data Persistence and Visualization

#### 7. Store sensor data in Room
- Kazdy pomiar zapisywany lokalnie: **TAK**
  - `BleViewModel` (collect notifications) -> `sensorRepository.insert(reading)`.
- Powiazanie danych z urzadzeniem (MAC/ID): **TAK**
  - Pole `deviceAddress` w encji i zapytaniach DAO.
- Dane przetrwaja restart aplikacji: **TAK**
  - Room DB `ble_sensors.db`.

#### 8. Display charts of historical data
- Wykres historii: **TAK**
  - `LineChart(readings)` w `BleScreen.kt`.
- Aktualizacja wykresu wraz z nowymi danymi: **TAK**
  - `observeHistory(address, 200)` + odswiezanie stanu.
- Filtrowanie po zakresie czasu (ostatnia godzina/dzien): **BRAK**
  - Obecnie jest tylko limit ilosciowy (`limit = 200`), bez filtra czasowego.

### EPIC 4: Background Operation

#### 9. Keep connection alive in background
- Foreground service z notyfikacja: **TAK**
  - Manifest + `BleForegroundService.startForeground(...)`.
  - Start/stop serwisu sterowany stanem polaczenia w `BleScreen`.
- Polaczenie aktywne po zminimalizowaniu: **CZESCIOWO**
  - Serwis podtrzymuje proces, ale logika BLE jest w repozytorium w procesie aplikacji (best effort).
- "System nie ubija procesu": **CZESCIOWO / deklaratywne**
  - Foreground service zmniejsza ryzyko ubicia, ale nie gwarantuje tego bezwarunkowo.

#### 10. Automatic reconnection
- Proby reconnect po utracie polaczenia: **TAK**
  - `onDisconnected()` -> `scheduleReconnect()`.
- Logowanie prob (timestamp + przyczyna): **BRAK**
  - Mechanizm reconnect jest, ale bez logowania prob.
- Strategia backoff: **TAK**
  - Interwal rosnacy `1s -> 2s -> ... -> max 60s`.

### EPIC 5: Advanced BLE Features

#### 11. Request MTU change
- Zadanie zmiany MTU: **TAK**
  - `requestMtu()` w repo + przycisk `Request MTU 247` w UI.
- Widoczna nowa wartosc MTU w UI/logach: **CZESCIOWO**
  - Widoczna w UI (`Text("MTU: ...")`).
  - Logowanie: **BRAK**.
- Informacja o bledzie: **CZESCIOWO**
  - Brak dedykowanego komunikatu MTU; ewentualnie tylko ogolny `connectionState` error.

#### 12. Bond with device
- Inicjacja bondingu: **TAK**
  - `BleRepositoryImpl.bond()` -> `device.createBond()`.
- Widoczny stan bonding: **TAK**
  - `BroadcastReceiver(ACTION_BOND_STATE_CHANGED)` -> `BondState` -> UI.
- Odrzucenie/usuniecie bondingu: **CZESCIOWO**
  - Odrzucenie jest posrednio mapowane do `NotBonded`.
  - Brak jawnej operacji usuniecia bondingu (remove bond).

