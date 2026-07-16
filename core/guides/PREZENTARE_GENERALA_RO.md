# Echoes of the Heir — Document de prezentare

**Autori:** Ciucă Andrei Corneliu, Lupu Iulian Nicolae
**Unitate de învățământ:** Liceul „Atanasie Marienescu” Lipova
**Profesor coordonator:** Prof. Recheștean Dorina

---

Echoes of the Heir este un joc 2D de tip RPG (Role Playing Game), cu elemente de aventură și puzzle-uri, dezvoltat integral de la zero, motor de joc inclus.

Ideea proiectului a pornit de la o observație simplă: majoritatea jocurilor RPG 2D independente rămân experiențe strict single-player, iar componenta socială — jocul alături de un prieten — este fie absentă, fie condiționată de conturi, internet stabil și configurări greoaie. Echoes of the Heir a fost gândit din start ca un joc care poate fi jucat atât online, cu prietenii online, cât și offline, față în față, fără cont și fără conexiune la internet — pur și simplu apropiind două telefoane.

Pe lângă componenta narativă și de gameplay clasică unui RPG, jocul include un motor grafic propriu, construit peste libGDX, cu un sistem de iluminat și umbre randat pe GPU, gândit să scaleze de la telefoane mai modeste până la desktop-uri performante.

## Ce cuprinde jocul nostru

- Poveste și lume proprii, cu hărți multiple, NPC-uri și inamici;
- Motor grafic propriu construit peste libGDX, cu iluminat dinamic, umbre și bloom, adaptabil pe trei nivele de calitate grafică;
- Multiplayer online, server-autoritativ, cu până la 8 jucători simultan pe aceeași hartă;
- Multiplayer local prin Bluetooth, complet offline, fără cont și fără internet;
- Invitație la joc printr-un simplu tap NFC între telefoane — fără introducerea manuală de IP-uri sau coduri;
- Listă de prieteni și sistem de reconectare automată pentru salvările din cloud;
- Sistem de licențiere online

## Inovații aduse

Multiplayer local prin BLE, fără internet. Pe lângă multiplayer-ul clasic prin server, jocul oferă o cale complet separată de joc împreună, construită pentru situația foarte comună în care doi prieteni sunt în aceeași cameră, dar nu au (sau nu pot folosi) o conexiune la internet stabilă. Un telefon devine „gazdă” și pornește un server Bluetooth Low Energy local (rol GATT server), celălalt se conectează direct la el (rol GATT central). Nu este nevoie de cont, de licență sau de criptare complexă — sesiunea trăiește strict pe distanța de acoperire Bluetooth, exact cât timp cei doi jucători sunt aproape unul de celălalt.

Invitație prin tap NFC, nu prin adrese. În loc ca jucătorii să introducă manual adrese sau coduri de sesiune, invitația la o partidă locală se face printr-un simplu tap NFC între cele două telefoane: gazda transmite prin NFC adresa sa Bluetooth, un token de sesiune și harta pe care se joacă, iar telefonul invitat se conectează automat la sesiunea BLE corespunzătoare. Practic, „a te alătura unui prieten” devine un gest fizic — apropii telefoanele — nu un formular de rețea. Același mecanism NFC este folosit și pentru adăugarea rapidă de prieteni în joc, printr-un simplu tap între conturi.

Aducerea jucătorilor împreună, în alt mod. Cele două căi de multiplayer — server online, autoritativ, cu criptare și sincronizare pe internet, versus BLE+NFC local, instant și fără cont — coexistă în același joc și sunt randate prin același sistem, astfel încât experiența jucătorului rămâne identică indiferent cum s-a conectat. Scopul a fost să nu punem jucătorii în fața unei alegeri „joc singur” sau „joc online cu cont”, ci să le oferim și o a treia variantă, gândită special pentru momentul în care doi prieteni sunt pur și simplu unul lângă altul.

## Tehnologii

Echoes of the Heir este scris integral în Java și construit peste libGDX, un framework open-source pentru dezvoltarea de jocuri, ales pentru randarea pe GPU prin OpenGL, portabilitatea pe mai multe platforme (desktop și Android, din același cod de bază) și controlul fin pe care îl oferă asupra motorului de joc — spre deosebire de un motor gata construit, aici întreg stratul de randare, coliziune, fonturi și efecte grafice (gfx.) a fost proiectat peste fundația oferită de libGDX.

Proiectul este organizat modular, pe trei componente Gradle: core (logica jocului și stratul grafic, independent de platformă), desktop (lansator LWJGL3, pentru Windows/Linux/macOS) și android (lansator nativ Android). Hărțile sunt construite în editorul Tiled și încărcate direct de motor, iar quest-urile, NPC-urile și obiectele sunt definite declarativ, în fișiere JSON, permițând extinderea conținutului fără a modifica motorul de bază.

Pentru iluminat, umbre și efecte de tip bloom, jocul folosește shadere GLSL scrise de la zero, cu două variante de calitate — una completă, cu umbre randate prin raymarching, și una simplificată, pentru dispozitive mai puțin performante — comutate automat în funcție de nivelul de calitate grafică ales.

Componenta de multiplayer online rulează pe un server Python (asyncio), care comunică cu clientul Java printr-un protocol propriu, criptat (schimb de chei RSA, sesiune criptată AES-GCM, protecție anti-replay), la un tick rate de 20 Hz. Pentru a garanta că serverul și clientul nu ajung niciodată să calculeze rezultate diferite în luptă, serverul lansează un subproces Java care reutilizează exact motorul de joc al clientului ca arbitru autoritativ.

Multiplayer-ul local folosește API-urile native Bluetooth Low Energy (BLE) și Near Field Communication (NFC) de pe Android, fără nicio dependență de server sau de internet. Salvările jucătorilor și sistemul de licențiere sunt sincronizate printr-un server dedicat în cloud.

## Cerințe de sistem

- Conexiune la internet (pentru multiplayer online, salvări în cloud și activarea licenței — nu este necesară pentru multiplayer local prin BLE/NFC)
- Windows, Linux sau macOS (pentru versiunea desktop), sau Android 8.0 ori o versiune mai nouă (pentru versiunea mobilă)
- Bluetooth și NFC, pentru funcțiile de multiplayer local și adăugare rapidă de prieteni (versiunea Android)
