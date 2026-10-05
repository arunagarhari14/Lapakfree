# SETUP_OAUTH.md — ek baar ka setup (v2.0)

Tumhare personal Google account se KUCH NAHI karna hai. App ke liye ek **alag
naya Google account** banao (2 minute, jaise `lapakfreeapp@gmail.com`) — OAuth
client usi account ke Cloud project me banega.

**Step 1 — naya Google account banao** (phone se, 2 min). Isi se aage login karna hai.

**Step 2 — Cloud project + API enable**
1. console.cloud.google.com kholo (naye account se login)
2. Naya project banao — naam: `Lapak Free` (project ID kahin note kar lo,
   jaise `lapak-free-123456`)
3. "APIs & Services" → Library → **Generative Language API** → Enable

**Step 3 — OAuth consent screen** (ek baar)
1. "APIs & Services" → OAuth consent screen → User type: **External** → Create
2. App name: `Lapak Free`, User support email: naya wala email → Save
3. Scopes → Add: `https://www.googleapis.com/auth/generative-language.peruserquota`
   (non-sensitive — "Use your Google Account to access generative AI capabilities") → Save
4. **Testing** mode me rakho → Test users me har driver ka Gmail add karo
   (jo app use karega). 100 tak free.

**Step 4 — OAuth client ID** (Android type)
1. "APIs & Services" → Credentials → Create Credentials → OAuth client ID
2. Application type: **Android**
3. Name: `Lapak Free`, Package name: `com.lapakfree`
4. SHA-1 certificate fingerprint: `FE:A5:7C:62:07:42:67:8B:FC:3C:3F:54:F4:5D:9F:EC:67:5A:BA:D6`
5. Create → client ID copy karo (`.apps.googleusercontent.com` wala)

**Step 5 — mujhe bhejo**
- OAuth client ID
- Project ID (Step 2 wala)

Main inhe app me daal ke final APK bana dunga. Uske baad jo driver app
download karega, wo **apne Google account se login** karega → AI uske session
se connect ho jayega. Tumhara personal account kahin use nahi hota.

**Billing**: kuch nahi lagta. Free tier limits shared project quota par lagti hain
— kuch drivers ke liye kaafi hai.
