# Sistemul de licensing și cum funcționează el

## Introducere

Echoes of the Heir folosește un sistem de licențiere **online**, activat automat la pornirea
jocului, identic pe toate platformele (desktop și Android). Spre deosebire de o schemă clasică
offline (cheie + semnătură verificate local pe mașina jucătorului), aici verificarea are loc pe
un server dedicat, iar clientul nu deține niciodată pe disc cheia de licență în forma ei brută.

## 1. De ce activare online, nu offline

Varianta veche lega licența de mașina jucătorului: pe desktop, un `license.properties` semnat
era legat de o amprentă din registry-ul Windows; pe Android, un `license.properties` semnat
separat era împachetat direct în APK. Ambele erau scheme **offline**, bazate pe semnătură
RSA — orice pereche cheie+amprentă care trecea verificarea criptografică era acceptată, fără ca
serverul să știe vreodată ce instalări există sau să poată revoca una singură.

Sistemul curent elimină complet logica specifică fiecărei platforme: o singură clasă,
**`platform.LicenseActivation`**, este folosită identic pe desktop și pe Android. Nu mai există
amprentă de mașină, nu mai există fișier de licență semnat împachetat la build — licența trăiește
pe serverul de salvare, iar clientul doar dovedește, la fiecare pornire, că are dreptul să o
folosească.

## 2. Fluxul de activare (prima pornire)

La prima rulare a jocului pe o instalare nouă, `MichiGame#create()` apelează
`platform.LicenseActivation.ensureActivated()` imediat ce `Gdx` este disponibil, pe orice backend.
Deoarece acesta face un round-trip de rețea de ordinul secundelor, activarea rulează pe un thread
separat, ca fereastra jocului să nu înghețe. Restul jocului nu citește un rezultat pe jumătate
gata: `LicenseActivation.awaitSettled(timeoutMs)` blochează pe un `CountDownLatch` până când
activarea s-a încheiat (reușit sau eșuat), iar `SETTLED.countDown()` rulează într-un bloc
`finally`, deci niciun path de ieșire (return devreme, excepție) nu poate lăsa apelantul blocat
la infinit.

Pentru o instalare fără licență locală, fluxul este:

1. Jocul obține o dovadă de cumpărare (token itch.io prin `ItchAuthProvider`), sau, pentru
   dezvoltator, un secret local separat citit din `owner_secret.dat` (`ownerkey:<secret>`), care
   ocolește complet fluxul OAuth itch.io — necesar pentru că itch nu emite niciodată un
   `access_token` către contul dezvoltatorului însuși, ci doar către conturile care au
   cumpărat efectiv jocul din magazin. `owner_secret.dat` nu este niciodată livrat cu jocul
   (exclus prin regula generică `*.dat` din `.gitignore`) și trebuie păstrat ca orice alt secret.
2. Clientul deschide un socket TCP către server și face un handshake pe trei pași:
   `HELLO v2 <client_nonce>` → serverul răspunde `OK <server_nonce>` → clientul trimite
   `ACTIVATE <payload_RSA-OAEP> [<token_itch_AES-GCM>]`. Payload-ul JSON (timestamp + ambele
   nonce-uri) este criptat RSA-OAEP cu cheia publică a serverului — aceeași pereche de chei
   folosită și de `CloudSaveService`/`MultiplayerClient` — dar cheia RSA servește doar la
   protejarea handshake-ului în tranzit, nu la semnarea vreunei licențe.
3. Tokenul itch.io (o credențială vie de cont) **nu** călătorește în plicul RSA, ci într-o cutie
   AES-GCM separată, cu cheia derivată prin HKDF din cele două nonce-uri deja partajate. Motivul e
   strict de dimensiune: RSA-2048/OAEP-SHA256 poate căra cel mult 190 de octeți în clar, iar cei
   doi nonce deja consumă 117 — un token itch real depășea limita, `rsaOaepEncrypt` arunca
   excepție, iar `ACTIVATE`-ul cumpărătorului nici măcar nu ajungea să fie trimis. Separarea într-o
   cutie AES-GCM proprie nu costă niciun round-trip suplimentar și nu are limită de dimensiune.
4. Serverul răspunde `AUTH_OK <sesiune_criptată> <activation_id> <bloc_criptat> <cheie_criptată>`:
   emite o cheie de licență nouă și trimite cheia de licență în clar (împachetată AEAD, cu o cheie
   de emitere derivată tot prin HKDF din nonce-uri — dezvăluită o singură dată clientului), un
   `activation_id` opac și un bloc criptat AES-GCM al cheii de licență (criptat cu o cheie pe care
   doar serverul o cunoaște).
5. Dacă serverul refuză, răspunsul distinge explicit trei cazuri, ca jucătorul să știe ce se
   întâmplă: `ITCH_NOT_OWNED` ("acest cont nu a cumpărat jocul pe itch.io"), `ITCH_UNAVAILABLE`
   ("itch.io nu a putut fi contactat, încearcă din nou") și `AUTH_FAIL` (server generic). Fără
   această distincție, o pană temporară a serverului de licențiere ar arăta jucătorului ca o
   acuzație de piraterie, fără să știe dacă să cumpere jocul din nou sau doar să aștepte.
6. Clientul păstrează cheia de licență doar **în memorie**, pentru durata procesului curent.

Serverul de licențiere nu este un singur host fix: `LicenseActivation` citește o listă de
endpoint-uri din `save_servers.txt` (format `host` sau `host:port`, o linie pe server, comentarii
cu `#`), iar dacă acest fișier lipsește sau e gol, cade pe o listă `FALLBACK_HOSTS` hardcodată în
cod. Această listă include atât hosturi publice, cât și câteva adrese `192.168.*` de rețea locală,
păstrate ca o comoditate de dezvoltare — un build livrat jucătorilor care ar ajunge să depindă
doar de acestea nu s-ar putea activa niciodată, de-aia hosturile publice sunt încercate primele.
La eșec pe un endpoint, clientul încearcă următorul din listă — dar doar dacă eșecul a fost o
problemă de rețea (endpoint inaccesibil); un verdict definitiv de la server (cont nelicențiat,
itch indisponibil) oprește imediat încercările, ca să nu ardă degeaba tokenul itch (de unică
folosință) împotriva unui server care l-a respins deja.

## 3. Ce se salvează local și ce nu

Doar `activation_id` și blocul criptat sunt persistate local, într-un fișier (`activation.dat`),
prin `platform.GameStorage` — același strat de stocare portabil folosit și pentru configurări și
salvări, deci funcționează identic și în interiorul spațiului privat al unei aplicații Android.
**Cheia de licență în clar nu este scrisă niciodată pe disc.**

La orice pornire ulterioară, clientul trimite `activation_id` și blocul criptat înapoi către
server printr-un handshake `LOGIN`. Serverul, singurul care deține cheia de decriptare a blocului,
îl decriptează, confirmă licența și retrimite cheia de licență (protejată în tranzit), pentru ca
sesiunea curentă să o poată folosi în memorie. Confirmarea propriu-zisă a licenței este chiar
reușita acestui `LOGIN`, nu simpla prezență a cheii în memorie.

## 4. Ce se întâmplă fără conexiune la server

Dacă niciun server de licențiere nu poate fi contactat (offline la prima pornire, sau o
întrerupere temporară la o pornire ulterioară), `ensureActivated()` întoarce `null`, iar jocul
continuă să ruleze — doar fără salvare în cloud și fără multiplayer pentru sesiunea curentă.
Acest comportament este identic pe toate platformele: nu există o excepție specială de tipul
"fără verificare de licență pe mobil".

Codul care așteaptă un rezultat de activare (de exemplu încărcarea unei salvări din cloud) nu
citește un răspuns pe jumătate inițializat, ci așteaptă explicit finalizarea handshake-ului
(reușit sau eșuat) înainte de a decide dacă folosește salvarea din cloud sau una locală.

## 5. Cum este folosită licența de restul jocului

`MultiplayerClient` și `CloudSaveService` nu mai citesc nicio amprentă de mașină sau semnătură
locală. În schimb, trimit către server `activation_id` și blocul criptat al licenței, iar
serverul rezolvă cheia de licență pe partea lui, corelând-o cu contul jucătorului. Astfel, orice
verificare de proprietate a licenței rămâne responsabilitatea serverului, iar clientul este doar
un purtător al celor două valori opace necesare pentru a se identifica.

## Pe scurt

Sistemul de licențiere al Echoes of the Heir mută toată logica de verificare pe server: clientul
nu mai calculează sau verifică nimic criptografic pe cont propriu în privința dreptului de a
rula jocul, ci doar păstrează local un identificator de activare și un bloc criptat, reînnoite la
fiecare pornire printr-un handshake cu serverul. Rezultatul este un singur sistem, identic pe
desktop și Android, fără cod specific per-platformă și fără nicio cheie de licență scrisă
vreodată în clar pe disc.
