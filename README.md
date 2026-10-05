# Lapak Free — Source Code

Driver ride auto-accept Android app (Lapak jaisa kaam, bilkul free, simple Hinglish UI).
**v3.0: Naya UI + login fix** — neeche 4 alag pages (Home / AI / History / Settings),
page-switch animation, button press animation. Home par master switch + 5 permissions.
History date-wise (Aaj / Kal / date). AI Commander me ab AI ka jawab SUN bhi sakte ho
(speaker toggle). **Login bug fix**: browser se wapas app me aane ka address theek kiya
(`com.lapakfree://oauth2callback`) — ab login ke baad app me logged-in state dikhega.
**v2.1: AI Commander** — driver AI se baat karke (likh ke ya bol ke) poore app ko
manage karta hai. Jaise: "Porter se Noida ki ride le li, ab Rapido/Uber se usi side
ki parcel ride aaye to accept kar le" → AI samajh ke standing order banata hai,
phir aisi ride aate hi **local turant** accept (network wait nahi). AI ko destination,
aaj ki history aur active orders hamesha yaad rehte hain.

**v2.0: Har driver ka apna Google AI** — jo driver app download karega, wo apne Google
account se login karega, aur AI uske session se connect ho jayega. Koi Firebase nahi,
koi shared API key nahi, developer ka personal account kahin involve nahi (SETUP_OAUTH.md).

**v1.8: Turant Ride Alerts** — ride ki notification aate hi turant floating card:
kitne % manjil ki taraf jayegi (hara) ya door jayegi (laal), Accept/Skip button ke saath.
Bina screen khole, bina 1-2 sec wait ke — % local offline math se nikalta hai.

## App info
- Package: `com.lapakfree`
- Version: 3.0 (versionCode 14)
- Language: Java — **manual Gradle-free build** (`build-apk.sh`: aapt2 → javac → d8)
- Min SDK 26 (Android 8.0), Target SDK 34
- **Zero third-party libraries** — Google login seedha OAuth2+PKCE se (AI scope ke saath),
  Gemini AI seedha HTTPS REST se (driver ke apne OAuth token par — koi Firebase SDK /
  Play Services SDK app me nahi hai)

## Kya karta hai
1. **Turant alerts (v1.8)**: driver apps (Uber, Ola, Rapido, Porter, inDrive) ki **notification**
   aate hi turant floating card — "65% PASS jayegi" / "40% DOOR jayegi" + Accept/Skip.
   % offline map + local math se nikalta hai (millisecond me, koi wait nahi).
   Ek se zyada rides aayen to sab par trigger — har ride ka apna card, apna %.
2. **Auto-accept mode**: itne % pass (setting, default 30%) par notification ke accept
   action se turant accept — driver app kholne ki zaroorat nahi.
3. Accessibility service se driver apps ki ride-offer screen padhta hai (backup rasta).
3. Rules check karta hai (min fare, max pickup, min trip)
4. **Destination mode**: pickup max 2km (setting) + drop manjil ki taraf hona chahiye — tabhi accept
5. **AI faisla (v2.0, login ke baad)**: driver apne Google account se login karta hai —
   AI uske apne session (OAuth token) se Gemini ko poochta hai: accept ya skip, chhote
   Hinglish reason ke saath. Driver ke apne nirdesh ("AI ko apne nirdesh do" wala box)
   har prompt me sabse upar jate hain. AI jawab na de to purane rules se faisla (backup).
   Token 1 ghante me expire hota hai to app chup-chaap refresh kar leta hai.
6. Accept button dabata hai (tap, ya swipe-slider par swipe)
7. Floating ON/OFF bubble, local history, offline Delhi NCR map (69,129 places)

## Build kaise karein
Zaroorat: JDK 17, Android SDK (platform android-34, build-tools 34.0.0). Koi Gradle nahi chahiye.

```bash
# pehli baar: SETUP_OAUTH.md ke hisab se OAuth client banao, phir:
#   app/oauth-client-id.txt aur app/oauth-project-id.txt me client ID + project ID daalo
./build-apk.sh
# output: apk/lapakfree-debug.apk (debug.keystore se signed, password: android)
```

Build steps (script ke andar): google-services.json → firebase values →
aapt2 compile+link → javac → d8 → zipalign → apksigner.

Bina asli `google-services.json` ke bhi build ho jayega (placeholder se), par
Google login aur AI kaam nahi karenge — Firebase setup zaroori hai.

## Firebase setup (5 min, FREE)
`SETUP_FIREBASE.md` dekho — project banao, Android app add karo (SHA-1 andar likhi hai),
google-services.json laao, Cloud console me **Generative Language API** enable karo,
API key ko Android-app par restrict karo. Billing kahin nahi lagta.

## File map
- `app/src/main/AndroidManifest.xml` — permissions, services, receiver
- `app/src/main/java/com/lapakfree/`
  - `MainActivity.java` — ek hi screen: ON/OFF, Google login, AI toggle, permissions, rules, destination, history
  - `RideNotifyService.java` — **(v1.8)** NotificationListenerService: ride notification
    aate hi turant % nikaal ke card/auto-accept trigger karta hai
  - `RideCardOverlay.java` — **(v1.8)** floating % cards (stack, Accept/Skip, AI hint)
  - `GoogleAuth.java` — **(v1.7)** Google Sign-In, seedha OAuth2+PKCE (bina SDK)
  - `AiAdvisor.java` — **(v1.7)** Gemini AI se ride ka faisla, seedha HTTPS REST (bina SDK)
  - `AutoAcceptService.java` — core engine: offer detect, parse, rule check, AI check, auto tap/swipe
  - `OfferParser.java` — screen text se fare/km nikaalta hai
  - `Offer.java` — ride offer ka data
  - `RuleEngine.java` — accept/skip ka faisla (AI ka backup)
  - `OfflineGeo.java` — offline map search (Delhi NCR DB) + online fallback
  - `GeoUtil.java` — haversine distance + Nominatim geocoding
  - `Prefs.java` — settings (SharedPreferences)
  - `HistoryStore.java` — last 100 ride events
  - `BubbleService.java` — floating ON/OFF bubble
  - `BootReceiver.java` — restart par bubble wapas
- `app/src/main/res/` — layout, strings (Hinglish), colors, icons, accessibility config
- `app/src/main/assets/delhi_ncr.db` — offline Delhi NCR place database (OpenStreetMap data, FTS5, v1.6 se FULL coverage)
- `app/google-services.json` — Firebase config (placeholder; asli file Firebase console se)
- `debug.keystore` — debug signing key (password: android). **Isi key se sign karte raho, warna update ke liye uninstall karna padega.**
- `SETUP_FIREBASE.md` — Firebase setup guide (Hinglish)

## Offline map DB
`assets/delhi_ncr.db` — SQLite FTS5 table `places(name, kind, lat, lon, imp)` + `meta`.
v1.6 se FULL coverage: shops, named streets, mohallas, POIs (69,129 rows).
Banane ki script: `~/workspace/osmdata/build_db.py` (Geofabrik India extract + osmium).

## Version history
- 1.0 — pehla build: ON/OFF, rules, bubble, history
- 1.1 — Android 13+ crash fix (registerReceiver flag)
- 1.2 — Google Maps auto-destination (baad me hataya gaya)
- 1.3 — accessibility detect fix (ComponentName)
- 1.4 — simple button labels
- 1.5 — auto-maps feature removed; offline Delhi NCR map; destination mode me pickup (2km) + drop dono check
- 1.6 — FULL Delhi NCR map (69,129 locations: shops, streets, mohallas, POIs)
- 1.7 — Google Login + AI: login karte hi Gemini AI (free tier) inbuilt connect;
  destination-ride ka faisla AI karta hai (~1-2 sec), backup me rule-engine;
  **zero dependencies** (OAuth2+PKCE aur REST, koi SDK nahi); manual build wapas
- 1.8 — Turant Ride Alerts: notification aate hi turant % card (65% PASS / 40% DOOR),
  multiple rides par sab trigger; auto-accept mode me % threshold par turant accept
  (notification action se); % local offline math se — 1-2 sec ka wait khatam

## Pending / known
- Firebase project user ko banana hai (SETUP_FIREBASE.md) — uske baad hi login+AI kaam karenge.
- Real ride par end-to-end testing user ke paas pending hai (offer-screen layouts app ke hisaab se alag ho sakte hain).
- `apksrc/` purana source hai (v1.6 tak, purana build script) — v1.7 se `app/` hi aage badhega.

## In-app update server setup (v2.2)

App din me 1 baar `version.json` check karta hai. Naya `versionCode` mile to
driver ko dialog dikhta hai → Download → install (bina uninstall, data safe).

1. GitHub par public repo banao, jaise `lapakfree-updates`.
2. Root me `version.json` rakho:
   `{"versionCode": 14, "versionName": "2.3", "apkUrl": "<release-apk-url>", "notes": "kya naya hai"}`
3. APK ko GitHub Release me asset ki tarah upload karo (stable URL milta hai) —
   wahi URL `apkUrl` me daalo.
4. `app/update-url.txt` me ye daalo:
   `https://raw.githubusercontent.com/<user>/lapakfree-updates/main/version.json`
5. `./build-apk.sh` se rebuild karo — updater live ho jayega.

Pehli install hamesha link se hoti hai; uske baad saare updates app ke andar se.

## Auto-update pipeline (GitHub → app, bina reinstall ke)

`main` branch par har push par GitHub Actions khud APK bana deta hai
(`.github/workflows/build.yml`): versionCode +1, APK `updates/lapakfree.apk`
me commit, `version.json` update. App roz ek baar `version.json` check karta
hai (UpdateChecker) — naya version mile to download + install prompt.

- **Bina uninstall, bina permission dobara**: har build isi repo ke
  `debug.keystore` se sign hota hai, isliye Android isko *update* manta hai —
  data aur di hui permissions safe rehte hain. Install ke time system ek baar
  "Install" dabane ko bolega (Android ka rule — silent install possible nahi),
  bas.
- **Tumhara flow**: code badlo (github.dev web editor ya apne computer se) →
  commit/push → 4-5 min me APK taiyaar → phone par app kholo, update dialog →
  Install. Koi SDK install karne ki zaroorat nahi.
- Note: `debug.keystore` repo me isliye hai taaki signature hamesha same rahe.
  Play Store release se pehle isko release keystore se badal lena.

## Repo me kya hai
- `app/` — poora Android source (Java, zero libraries)
- `build-apk.sh` — manual build (local: `bash build-apk.sh`)
- `tools/` — build helpers (oauth/update values generate karte hain)
- `debug.keystore` — signing key (upar note padho)
- `version.json` — updater ka source of truth (CI likhta hai)
- `updates/lapakfree.apk` — latest auto-built APK (CI commit karta hai)
