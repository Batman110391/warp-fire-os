# SPEC — App WARP minimale per Amazon Fire TV ("install & connect")

> Documento di specifica destinato a **Claude Code**. Obiettivo: costruire, dallo scratch, un'app Android minimale che replichi il comportamento di *1.1.1.1 with WARP* di Cloudflare (auto-registrazione + tunnel), pensata per **Amazon Fire TV**, distribuita via **GitHub Releases** e installabile con l'app **Downloader**.

---

## 0. Principio guida

L'esperienza utente finale deve essere: **installa APK → apri app → un click su "Connect" → fatto**. Nessuna configurazione manuale, nessun file da importare, nessuno scambio di chiavi. Tutta la "magia di config" avviene **al primo avvio, dentro l'app** (auto-registrazione device su Cloudflare, come fa `wgcf` ma in-process).

Ogni installazione si registra come **device WARP indipendente**: la stessa APK funziona identica per chiunque la installi, senza personalizzazione.

**Scope volutamente minimale**: solo full-tunnel WireGuard, un solo bottone, nessun account, nessuna telemetria. NON implementare MASQUE, NON implementare proxy mode, NON implementare split-tunnel. Se in futuro servisse proxy mode si valuterà un client MASQUE separato (`usque`), fuori scope qui.

---

## 1. Stack tecnico obbligatorio

| Ambito | Scelta | Note |
|---|---|---|
| Linguaggio | **Kotlin** | |
| Min SDK | **API 22** (Android 5.1) | Fire OS legacy compatibility. Target SDK: ultimo stabile (≥34). |
| Tunnel | **Libreria ufficiale WireGuard Android** `com.wireguard.android:tunnel` (`GoBackend`, userspace wireguard-go, no root) | Verificare l'ultima versione stabile su Maven prima di pinnare. |
| VPN | `android.net.VpnService` (gestito dal `GoBackend` della libreria) | |
| HTTP client (registrazione) | **OkHttp** | |
| JSON | **kotlinx.serialization** o Moshi | |
| Storage segreti | **`androidx.security:security-crypto`** (`EncryptedSharedPreferences`) | La private key non deve MAI lasciare il device. |
| Build | Gradle Kotlin DSL (`build.gradle.kts`) + wrapper committato | |
| CI | GitHub Actions | Vedi §7. |

> ⚠️ **Non hardcodare versioni a memoria.** Prima di scrivere i `build.gradle.kts`, verifica su Maven Central / repo ufficiali l'ultima versione stabile di ciascuna dipendenza e pinna quella.

---

## 2. Architettura funzionale

Tre sottosistemi:

1. **WarpRegistration** — client dell'API di registrazione Cloudflare. Al primo avvio: genera keypair, registra il device, ottiene la config del peer. Idempotente: se una config valida è già salvata, NON ri-registra.
2. **WireGuardTunnel** — wrapper attorno a `GoBackend` che costruisce il `Config` WireGuard e porta il tunnel UP/DOWN.
3. **UI (MainActivity)** — singola schermata leanback, D-pad-navigabile, con bottone Connect/Disconnect e stato.

Flusso al primo avvio:

```
onCreate
  └─ config salvata valida? ──no──> WarpRegistration.register() ──> salva config cifrata
  │                                                                        │
  └─────────────────────────────sì────────────────────────────────────────┘
                                       │
                        utente preme "Connect"
                                       │
                     VpnService.prepare() (consenso di sistema, una tantum)
                                       │
                        WireGuardTunnel.up(config)
                                       │
                                  stato: CONNECTED
```

---

## 3. Registrazione WARP (il cuore dello "zero config")

**Fonte di verità del protocollo: il progetto open-source `wgcf`** (implementazione Go della registrazione WARP). Claude Code deve **consultare il codice attuale di `wgcf`** (moduli di registrazione / client API) e portarne la logica in Kotlin, perché endpoint, version string e payload cambiano nel tempo e NON vanno indovinati.

Elementi noti e stabili (da confermare comunque contro `wgcf` al momento dell'implementazione):

- **Base API**: `https://api.cloudflareclient.com/` con un path versionato (es. `v0a…`). La version string va presa da `wgcf`.
- **Header client** tipici: `CF-Client-Version`, `User-Agent`, `Content-Type: application/json`. Valori esatti da `wgcf`.
- **Chiave pubblica del peer WARP** (pubblica e stabile): `bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=`
- **Endpoint** WireGuard WARP: host `engage.cloudflarewarp.com` porta `2408` (fallback IP noti `162.159.192.1:2408`, IPv6 `[2606:4700:d0::a29f:c001]:2408`).
- **MTU**: `1280`.
- **AllowedIPs** (full tunnel): `0.0.0.0/0`, `::/0`.
- **DNS**: `1.1.1.1`, `1.0.0.1` (+ IPv6 `2606:4700:4700::1111`).

Passi di registrazione (semantica, non payload letterale):

1. Genera keypair **Curve25519** (la libreria WireGuard fornisce `KeyPair`).
2. `POST` di registrazione con la public key + campi richiesti (install_id, tos timestamp, ecc. → vedi `wgcf`). Ricevi: id/token account, indirizzi IPv4/IPv6 assegnati all'interfaccia, dati del peer.
3. (Se richiesto dal flusso `wgcf`) `PATCH` per impostare `warp_enabled: true`.
4. Salva in `EncryptedSharedPreferences`: private key, indirizzi assegnati, peer public key, endpoint. Salva anche un flag `registered=true`.

Gestione errori obbligatoria: assenza di rete, timeout, risposta non-2xx → stato UI "errore, riprova" con retry manuale + backoff. La registrazione non deve mai bloccare la UI (coroutine su `Dispatchers.IO`).

**Reset**: prevedi un modo nascosto (es. long-press sul titolo, o voce in un menu secondario) per cancellare la config e forzare una nuova registrazione. Utile in debug.

---

## 4. Tunnel WireGuard

Usa `GoBackend` della libreria ufficiale. Costruisci il `Config`:

- `Interface`: private key, `addresses` (IPv4/IPv6 assegnati), `dnsServers`, `mtu = 1280`.
- `Peer`: public key WARP, `endpoint` (`engage.cloudflarewarp.com:2408`), `allowedIps = 0.0.0.0/0, ::/0`, `persistentKeepalive = 25`.

Porta su/giù con `backend.setState(tunnel, Tunnel.State.UP/DOWN, config)`. Implementa l'interfaccia `Tunnel` (nome fisso es. `"warp"`, callback `onStateChange`).

**Consenso VPN**: prima di `UP`, chiama `VpnService.prepare(context)`; se ritorna un `Intent`, lancialo da un'Activity (`registerForActivityResult`) — su Fire TV il dialog di sistema deve essere raggiungibile col telecomando. Solo dopo esito positivo chiama `setState(UP)`.

**Service manifest**: dichiara il `VpnService` della libreria seguendo il **sample ufficiale del modulo `tunnel`**, con:
- `android:permission="android.permission.BIND_VPN_SERVICE"`
- `<intent-filter><action android:name="android.net.VpnService"/></intent-filter>`
- `android:foregroundServiceType` appropriato (vedi §5) e notifica persistente quando il tunnel è attivo.

---

## 5. Manifest, permessi e specificità Fire TV

**Questi punti sono i più frequenti motivi per cui un'app "non appare" o "non parte" su Fire TV. Rispettarli tutti.**

Permessi:
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<!-- API 34+: type esplicito per il foreground service del tunnel -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/> <!-- API 33+ -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/> <!-- opzionale, auto-connect -->
```

Feature flag **critici** (senza questi Fire TV può nascondere/rifiutare l'app):
```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
<uses-feature android:name="android.software.leanback" android:required="false"/>
```

Launcher — deve comparire nella home di Fire TV. Nell'`<activity>` principale, due category:
```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.LAUNCHER"/>
    <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
</intent-filter>
```
E `android:banner="@drawable/banner"` sull'`<application>` o sull'`<activity>` (immagine banner 320×180 richiesta dalla home leanback).

Tema: usa un tema scuro fullscreen adatto alla TV (niente ActionBar obbligatoria). Non è richiesta la Leanback support library per una UI così semplice.

---

## 6. UI (MainActivity)

Volutamente essenziale, ottimizzata per telecomando:

- **Un bottone** grande, `focusable`, con focus di default all'avvio → toggle Connect/Disconnect.
- **Testo di stato**: `Disconnected` / `Registering…` / `Connecting…` / `Connected` / `Error`.
- Quando connesso, mostra l'**IP assegnato** e opzionalmente un check `warp=on` (fetch di `https://www.cloudflare.com/cdn-cgi/trace` e parse della riga `warp=`).
- Tutto navigabile con D-pad; nessun elemento raggiungibile solo via touch. Nessun `<form>`, nessun input testuale.
- Feedback visivo di focus evidente (i telecomandi non hanno hover).

Niente librerie UI pesanti: `ConstraintLayout` + View standard bastano. Compose accettabile solo se non complica il build CI.

---

## 7. Distribuzione: GitHub Releases + Downloader

Deliverable di distribuzione = **APK firmato allegato come asset di una GitHub Release**, così l'URL è diretto e permanente (Downloader lo scarica senza pagine intermedie, a differenza di Google Drive).

### 7.1 Firma
Genera una release keystore. In CI, keystore e password arrivano da **GitHub Secrets** (mai committati):
- `SIGNING_KEYSTORE_BASE64` (keystore .jks in base64)
- `SIGNING_KEYSTORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

`build.gradle.kts` legge questi valori da env per il `signingConfig` del build `release`. In assenza (build locale dev) fallback al debug signing.

### 7.2 Workflow `.github/workflows/build.yml`
Requisiti:
- Trigger: push di tag `v*` **e** `workflow_dispatch` (build manuale).
- Steps: `checkout` → setup **JDK 17** (`temurin`) → cache Gradle → decode keystore da secret → `./gradlew assembleRelease` → verifica che l'APK esista → crea/aggiorna la Release e **allega `app-release.apk`** come asset (usa `softprops/action-gh-release`).
- L'asset deve avere un **nome stabile** (es. `warp-firetv.apk`) così l'URL di download non cambia tra versioni, semplificando il codice AFTVnews.

Output atteso: `https://github.com/<user>/<repo>/releases/latest/download/warp-firetv.apk` → link diretto pronto per Downloader.

### 7.3 Istruzioni utente finale (da mettere nel README)
1. Sul Fire TV: *Impostazioni → My Fire TV → Developer Options → Install unknown apps* → abilita **Downloader**.
2. Apri **Downloader**, inserisci l'URL diretto della release **oppure** un **codice numerico AFTVnews** generato dal loro URL shortener a partire da quell'URL.
3. Scarica → Installa → apri → **Connect**.
4. (Opzionale) *Always-on VPN*: nelle impostazioni di rete del Fire TV, imposta questa app come VPN sempre attiva per riconnessione automatica al boot.

---

## 8. Struttura del repo (attesa)

```
.
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/wrapper/…               # wrapper committato
├── gradlew / gradlew.bat
├── README.md                      # incl. istruzioni §7.3
├── .github/workflows/build.yml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/<pkg>/
        │   ├── MainActivity.kt
        │   ├── warp/WarpApi.kt            # OkHttp client registrazione
        │   ├── warp/WarpRegistration.kt   # orchestrazione + retry
        │   ├── warp/WarpConfigStore.kt    # EncryptedSharedPreferences
        │   ├── vpn/WireGuardTunnel.kt     # GoBackend + Tunnel impl
        │   └── vpn/BootReceiver.kt        # opzionale, auto-connect
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/banner.(png|xml)
            └── values/…                   # theme scuro TV, strings
```

---

## 9. Criteri di accettazione (definizione di "fatto")

L'implementazione è accettata solo se **tutti** i punti sono veri:

1. `./gradlew assembleRelease` compila senza errori; l'APK è firmato.
2. Il workflow CI, su push di un tag `v*`, produce una Release con `warp-firetv.apk` allegato e scaricabile via URL diretto.
3. Su Fire TV l'app **compare nella home** con banner e si apre.
4. L'intera UI è **navigabile solo col telecomando** (D-pad), incluso il dialog di consenso VPN.
5. **Primo avvio senza alcun input**: l'app si auto-registra su Cloudflare e salva la config cifrata.
6. Premendo Connect il tunnel sale; `cdn-cgi/trace` riporta **`warp=on`**.
7. La config **persiste** tra riavvii dell'app; non ri-registra inutilmente.
8. Funziona impostata come **Always-on VPN** di sistema.
9. La private key non compare mai in log, storage in chiaro o output CI.

---

## 10. Vincoli, avvertenze e cose da NON fare

- **NON** committare keystore, chiavi o secret. **NON** loggare la private key o i token di registrazione.
- **NON** indovinare la version string / payload dell'API WARP: portali da `wgcf` corrente.
- **NON** implementare MASQUE, proxy mode, split-tunnel, account, telemetria, o UI extra: sono fuori scope.
- **NON** distribuire via Google Drive (link non diretti + interstiziale antivirus): usare GitHub Releases.
- **Nota legale (documentare nel README, non è un blocker):** la registrazione WARP in-process usa l'API client di Cloudflare in modo non ufficiale ed è tecnicamente contraria ai loro ToS. Uso personale, piccola scala. Ogni utente è un device WARP gratuito indipendente.
- Verifica sempre le **ultime versioni stabili** di libreria WireGuard, AGP, Kotlin, `security-crypto` e action CI prima di pinnarle.

---

## 11. Prima azione richiesta a Claude Code

1. Consultare il codice attuale di `wgcf` per fissare endpoint/version-string/payload di registrazione.
2. Verificare le ultime versioni stabili delle dipendenze.
3. Scaffoldare il progetto secondo §8, poi implementare nell'ordine: `WarpConfigStore` → `WarpApi`/`WarpRegistration` → `WireGuardTunnel` → `MainActivity` → manifest → CI.
4. Consegnare con `README.md` completo di istruzioni §7.3 e note §10.
