<div align="center">

# Pokémon Reloaded para Android

**El juego original de PC, ejecutado de forma nativa en tu móvil. Sin Wine, sin emulación de Windows.**

[![Última versión](https://img.shields.io/github/v/release/waldnerverges27-collab/pokemon-reloaded-android-mejorado?label=versi%C3%B3n&color=d9b567)](../../releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-GPL--2.0-blue)](LICENSE)
![Android](https://img.shields.io/badge/Android-10%2B-3ddc84)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%C2%B7%20armeabi--v7a-555)
![Paquete](https://img.shields.io/badge/paquete-com.ahjin.pr-555)

[Descargar](../../releases/latest) · [Capturas](#capturas) · [Cómo se usa](#cómo-se-usa) · [Funciones](#funciones) · [Compilar](#compilar-desde-el-código) · [Historial](#historial-de-versiones)

</div>

---

## Índice

1. [Qué es](#qué-es)
2. [Capturas](#capturas)
3. [Cómo se usa](#cómo-se-usa)
4. [Funciones](#funciones)
5. [Rendimiento y fotogramas por segundo](#rendimiento-y-fotogramas-por-segundo)
6. [Arquitectura](#arquitectura)
7. [Estructura del repositorio](#estructura-del-repositorio)
8. [Compilar desde el código](#compilar-desde-el-código)
9. [Firma y actualizaciones](#firma-y-actualizaciones)
10. [Herramientas de diagnóstico](#herramientas-de-diagnóstico)
11. [Historial de versiones](#historial-de-versiones)
12. [Estado y problemas conocidos](#estado-y-problemas-conocidos)
13. [Licencia y créditos](#licencia-y-créditos)

---

## Qué es

**Pokémon Reloaded** es un juego de fans hecho en **Game Maker 8** para Windows. Esta aplicación lo
ejecuta en Android **tal cual**: con sus archivos originales intactos, sin reescribirlo ni
recompilarlo. Para ello se apoya en [OpenGMK](https://github.com/OpenGMK/OpenGMK), una
reimplementación nativa, escrita en Rust, del motor de Game Maker 8.

No hay Wine, ni Windows emulado, ni capa de traducción: el motor se compila para el procesador del
móvil y el juego corre prácticamente como un programa nativo. Lo único que se ha portado son las
tripas del motor, no el juego.

Todo lo que rodea al juego lo aporta la app: importación desde un `.zip`, controles táctiles
adaptados a cada pantalla, gestión de partidas, ajustes, actualizaciones y registros.

Este proyecto parte del port de [@Klisee](https://github.com/Klisee)
([pokemon-reloaded-android](https://github.com/Klisee/pokemon-reloaded-android)) y lo desarrolla en una
línea propia, con otro paquete (`com.ahjin.pr`), firma propia, nueva interfaz y numerosas mejoras
de audio, controles, rendimiento y extras.

> **El juego no está incluido.** El repositorio no contiene ningún recurso de Pokémon Reloaded; lo
> importas tú a partir del `.zip` oficial.

## Capturas

Capturas reales en un RedMagic 8S Pro. En las de juego se ve el indicador de rendimiento
(FPS, milisegundos por fotograma y memoria) activado.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/juego-mundo.jpg" alt="Exploración con joystick y botones nativos"><br><sub><b>Exploración.</b> Joystick de 8 direcciones y botones nativos translúcidos.</sub></td>
    <td width="50%"><img src="docs/screenshots/juego-combate.jpg" alt="Combate con la capa táctil de combate"><br><sub><b>Combate.</b> La capa táctil cambia sola y muestra solo lo necesario.</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/juego-laboratorio.jpg" alt="Interior del laboratorio con escala de píxeles perfectos"><br><sub><b>Píxeles perfectos.</b> Escala entera para un píxel nítido y uniforme.</sub></td>
    <td width="50%"><img src="docs/screenshots/juego-dialogo.jpg" alt="Diálogo con el botón de acción contextual"><br><sub><b>Diálogos.</b> Solo aparece el botón de acción, sin ocupar la pantalla.</sub></td>
  </tr>
</table>

<table>
  <tr>
    <td width="33%"><img src="docs/screenshots/ajustes-controles.jpg" alt="Ajustes: aspecto de los controles"><br><sub><b>Ajustes.</b> Aspecto de los controles y tema «Obsidiana y oro».</sub></td>
    <td width="33%"><img src="docs/screenshots/editor-botones.jpg" alt="Editor de posición de los botones"><br><sub><b>Editor de controles.</b> Posición y tamaño por pantalla, botones propios y diseños guardados.</sub></td>
    <td width="33%"><img src="docs/screenshots/ajustes-resolucion.jpg" alt="Ajustes: vibración y resolución"><br><sub><b>Vibración y resolución.</b> Incluye la escala de píxeles perfectos.</sub></td>
  </tr>
</table>

## Cómo se usa

### Requisitos

- Android 10 o superior (API 29).
- Un procesador de 64 bits (arm64-v8a). El APK incluye también el motor de 32 bits (armeabi-v7a).
- El juego en un `.zip`, disponible en el [blog de F3R](https://pokemon-reloaded.blogspot.com/).
- Unos 350 MB libres para el juego descomprimido (el `.zip` pesa unos 200 MB).

### Instalación

1. Descarga el APK de la [última versión](../../releases/latest) e instálalo.
2. Abre la app y pulsa **Importar** para elegir el `.zip` del juego (o **Descargar** para abrir el blog).
3. Pulsa **Jugar**.

Si ya jugabas en PC, tu partida entra desde **Ajustes → Partidas → Importar**.

> **Sobre el paquete.** La app usa el identificador `com.ahjin.pr`. Es independiente de cualquier
> versión anterior con otro identificador: se instala en paralelo, no la actualiza y no comparte datos
> con ella. A partir de aquí, las actualizaciones dentro de la app funcionan con normalidad.

## Funciones

### Controles táctiles

- **Se adaptan al juego.** La capa táctil cambia según lo que ocurre en pantalla (mapa, combate,
  diálogos, menús, elecciones, Zona Safari), de modo que cada situación muestra solo los botones
  que hacen falta.
- **Aspecto nativo.** Botones translúcidos con borde suave, iconos y letras vectoriales,
  animación al pulsar y brillo del color de cada botón. Se puede volver al pixel art clásico del juego.
- **Iconos según la función.** Cada botón del juego muestra lo que hace en vez de su letra:
  mochila, mapa, bici, caña de pescar, correr, huir, entrenador, objeto asignado, equipo…
  El icono sigue a la situación (la tecla `M` es el mapa fuera de combate y la megaevolución en combate) y en
  combate `Z X C V` conservan su letra, porque ahí son los cuatro ataques. Se puede volver a las letras en
  **Ajustes → Aspecto de los controles → Iconos en los botones**.
- **Joystick de 8 direcciones** en todo el juego, con diagonales con histéresis, zona muerta
  ajustable y modo flotante que sigue al pulgar.
- **Editor de posiciones y tamaños.** Arrastra, redimensiona u oculta cada botón por pantalla del
  juego. Admite **diseños guardados** con nombre.
- **Botones propios:** hasta 8, con la tecla, el texto de 3 letras **o uno de 31 iconos** (los de arriba,
  flechas, estrella, corazón, pausa…) y las pantallas que elijas.
- **Preajustes de disposición:** Estándar, Zurdo, Compacto, Grande y Zurdo compacto, con
  confirmación antes de aplicarlos. No tocan los botones propios ni los que hayas ocultado.
- **Respuesta táctil cuidada:** deslizar de un botón a otro sin levantar el dedo, vibración
  ajustable (apagada, ligera, fuerte), difuminado de los botones cuando no se usan y bordes de la
  pantalla excluidos del gesto «atrás» del sistema.
- **Mandos físicos:** detección automática, reasignación de teclas, vibración y atajos propios:
  guardado rápido, cargar, avance rápido, mostrar u ocultar los FPS y cambiar de ranura de guardado rápido.

### Audio

- Mezcla en un **hilo propio**. El callback de AAudio solo copia desde un búfer circular de unos
  60 ms, así que la música y los efectos no se cortan al mezclarse.
- Reapertura automática del dispositivo de audio, limitador suave contra saturación y
  preparación al cargar de los sonidos con frecuencia especial (los gritos).
- Si hubiera huecos, se registran en el log (`[aaudio] N huecos de audio`).
- Música MIDI sintetizada con SoundFont integrado.

### Imagen

- **Escala de píxeles perfectos** (múltiplos enteros), para un píxel nítido y uniforme.
- Filtro suave opcional, **rejilla LCD** y **líneas CRT**.
- Respeto de la muesca y la cámara frontal.

### Extras

Se configuran en **Ajustes → Extras**.

| Extra | Descripción |
| --- | --- |
| **Guardado rápido** | Tres ranuras independientes del guardado del juego. Guardar es un toque; cargar exige pulsar dos veces seguidas, para no perder progreso por error. El archivo se escribe aparte y se renombra, de modo que un fallo nunca estropea la ranura anterior. La música en bucle vuelve a empezar al cargar. |
| **Avance rápido** | x2, x3 o x4, con sonido silenciable. Solo acorta la espera entre fotogramas: la lógica del juego no cambia. Se desactiva solo si el móvil se calienta mucho. |
| **Indicador de rendimiento** | FPS, tiempo por fotograma y memoria residente en una esquina. |
| **Ahorro de batería** | Manual, o automático con el ahorro del sistema o el móvil caliente. |

### Apariencia de la aplicación

Tema **«Obsidiana y oro»**: fondo casi negro, texto marfil y un único acento dorado champán; en
modo claro, marfil con bronce. No usa el color dinámico del sistema, de modo que se ve igual en
todos los móviles. Se elige en **Ajustes → Apariencia** (oscuro por defecto, claro o según el
sistema). La tipografía es Google Sans Flex y la interfaz sigue Material 3 Expressive.

En **Ajustes → Color y negro** se puede cambiar el acento (**Oro**, Zafiro, Esmeralda, Rubí o
Amatista) y activar **Negro puro (AMOLED)** para el modo oscuro. Todas las combinaciones tienen
el contraste de texto comprobado con pruebas automáticas. Nada de esto afecta al juego.

### Copias de seguridad

Las partidas viven en la carpeta del juego, y borrar o reinstalar el juego las borra con ella. Para
que eso nunca sea una pérdida, la app guarda copias aparte, en su propia carpeta:

- **Automáticas:** al abrir el juego (solo si las partidas han cambiado desde la última copia; se
  conservan las 5 últimas) y **siempre** antes de importar partidas, borrar una partida o el juego,
  reinstalarlo o actualizar la app (se conservan las 10 últimas).
- **Manuales:** con *Hacer una copia ahora*. No se borran solas.
- **Restaurar, compartir o borrar** desde **Ajustes → Copias de seguridad**. Restaurar pide
  confirmación, guarda antes lo que hubiera y, si algo falla a medias, devuelve las partidas a como
  estaban.
- Cada copia es un `.zip` con la misma estructura que usa *Mis partidas → Importar*, así que también
  se puede abrir en un PC.
- Las escrituras van a un archivo temporal que se comprueba y se renombra, y se evita duplicar
  copias idénticas.

### Ayuda y diagnóstico

- **Guía de uso** dentro de la app (Ajustes → Ayuda), con secciones desplegables y sin conexión.
- **Informe de diagnóstico** de un toque: versión, dispositivo, memoria, estado del juego, ajustes y
  el final del registro, listo para compartir como `.txt`. No incluye las partidas.

### Gestión de partidas, registros y actualizaciones

- **Partidas:** ver, exportar, importar, borrar y mover entre ranuras.
- **Registros:** la app guarda un log que se puede compartir como archivo de texto desde
  **Ajustes → Logs**, y que sobrevive a un reinicio.
- **Segundo plano:** al salir de la app el bucle se detiene y el audio se corta; al volver, el juego
  continúa donde estaba.
- **Actualizaciones:** la app consulta las releases de este repositorio, descarga el APK y lo
  instala tras verificar que el paquete y la firma coinciden con los de la app instalada.

## Rendimiento y fotogramas por segundo

**El juego va a 30 fotogramas por segundo por diseño, no por una limitación de la aplicación.**
Esto se ha comprobado con datos del propio juego:

- Las **717 rooms** del juego tienen `room_speed = 30`
  (`src/OpenGMK/gm8exe/examples/roomspeed.rs`).
- En todo el GML del juego solo hay un script que modifica `room_speed`, `CheckDaySpeed`, y únicamente
  puede **bajarlo** (de 5 en 5, hasta 5) o devolverlo a 30; nunca lo sube por encima de 30.
- El motor espera `1 / room_speed` segundos entre pasos. Subir ese valor no daría más fluidez:
  aceleraría el juego. Ir más allá de 30 fps visuales requeriría interpolar fotogramas.

Qué hace la aplicación para que esos 30 fps se vean estables:

- **`ANativeWindow_setFrameRate`**: avisa al sistema de que el juego produce 30 fps, para que
  la pantalla use un múltiplo exacto (60 o 120 Hz, nunca 90) y el compositor no tenga que adivinar.
- **ADPF (`PerformanceHintManager`)**: informa de lo que tarda cada fotograma con un objetivo del
  70 % del tiempo disponible, para que el sistema mantenga los relojes del procesador.
- **Memoria**: tras cargar el juego y al pasar a segundo plano se devuelve al sistema lo que el
  asignador retiene (`mallopt` `M_PURGE`); el log muestra `[mem] … MB → … MB`.
- **Arranque**: los atlases de texturas se suben por lotes, lo que redujo la carga inicial de
  ~85 s a unos segundos en las GPU donde era lenta.

Medidas útiles: leer y decodificar todo el `.exe` del juego tarda unos 0,7 s en un PC; los sprites
ocupan unos 115 MB descomprimidos y el atlas ya montado, 256 MB. Con esos números se descartó una
caché de sprites en disco, que habría sido más lenta que regenerarlos.

> **Avance rápido.** El juego vigila el reloj real (`CheckDaySpeed`, que ejecuta
> `obj_DayNightControl`) y baja `room_speed` si considera que el tiempo pasa demasiado deprisa, que es
> justo lo que ve cuando se avanza rápido. Para que x2, x3 y x4 signifiquen lo que dicen, durante el
> avance rápido la room recupera la velocidad con la que fue diseñada.

## Arquitectura

```mermaid
flowchart TB
    subgraph app["App · Kotlin + Jetpack Compose"]
        launcher["Lanzador e interfaz"]
        ajustes["Ajustes · partidas · logs · extras"]
        editor["Editor de controles"]
        upd["Actualizador"]
    end

    subgraph engine["Motor · Rust (OpenGMK)"]
        runner["Runner de Game Maker 8"]
        render["Render OpenGL ES"]
        audio["Audio · AAudio + MIDI"]
        touch["Capa táctil"]
        extras["Guardado rápido · avance rápido · efectos"]
    end

    juego[("Datos del juego original (tu .zip)")]

    launcher -->|importa el .zip| juego
    app <-->|"JNI y ficheros de configuración"| engine
    engine -->|lee y ejecuta| juego
```

La app y el motor son dos mundos separados que se comunican por JNI y mediante ficheros de
configuración en el almacenamiento privado. El motor no sabe de interfaz: solo ejecuta el juego. La
app no sabe de Game Maker: solo lo lanza y lo envuelve. Esa separación permite tocar la interfaz sin
romper el motor, y viceversa.

| Capa | Tecnología |
| --- | --- |
| Interfaz | Kotlin, Jetpack Compose, Material 3 Expressive, Google Sans Flex |
| Motor | Rust sobre OpenGMK (`gm8emulator`, `gm8exe`) |
| Vídeo | OpenGL ES con EGL propio |
| Audio | AAudio por FFI + `rustysynth` para MIDI |
| Entrada | Capa táctil propia, mandos de Android, vibración |
| Empaquetado | Gradle 9.4.1 · AGP 9.2.1 · `minSdk 29` · `targetSdk 36` · `compileSdk 37` |

## Estructura del repositorio

```
.
├── android/                 App Android (Kotlin / Compose)
│   └── app/src/main/
│       ├── java/com/ahjin/pr/   Lanzador, ajustes, editor de controles, copias, diagnóstico, actualizador
│       ├── (src/test)           Pruebas JVM: copias de seguridad, diseños y temas
│       ├── assets/              Sprites de botones, SoundFont, diseño por defecto de los controles
│       └── res/                 Tema, tipografía e iconos
├── docs/screenshots/        Capturas de pantalla del README
├── src/OpenGMK/             OpenGMK con las modificaciones para Android
│   ├── gm8emulator/             Motor: runner, render, audio, capa táctil, extras
│   ├── gm8exe/                  Lector de ejecutables GM8 (+ ejemplos de diagnóstico)
│   └── …
├── vendor/                  Dependencias de ventana y sesión adaptadas (`ramen`, `udon`)
├── patches/                 Parches respecto a OpenGMK, para poder revisarlos
├── tools/                   Utilidades (generador del diseño de controles)
└── LICENSE                  GPL-2.0
```

## Compilar desde el código

### Requisitos

- **Rust** estable con los destinos `aarch64-linux-android` y `armv7-linux-androideabi`
  (`rustup target add …`).
- **Android NDK r27c**.
- **JDK 21** y **Gradle 9.4.1**.
- **Android SDK** con la plataforma 37 y sus licencias aceptadas.

### 1. El motor (`libgm8emulator.so`)

Se compila con el enlazador del NDK para API 29. Con `cargo-ndk`:

```bash
cd src/OpenGMK
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 29 build -p gm8emulator --release
```

O directamente con Cargo, apuntando el enlazador al NDK (ejemplo para arm64):

```bash
export NDK=/ruta/al/android-ndk-r27c
export TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC_aarch64_linux_android=$TC/aarch64-linux-android29-clang
export AR_aarch64_linux_android=$TC/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/aarch64-linux-android29-clang

cd src/OpenGMK
cargo build --release --target aarch64-linux-android -p gm8emulator --lib
```

La biblioteca resultante debe quedar en `src/OpenGMK/target/<triple>/release/libgm8emulator.so`;
Gradle la copia a `jniLibs` antes de empaquetar y se niega a continuar si falta la de arm64 o si
algún `.so` no es un ELF válido. La de 32 bits es opcional.

> La compilación del motor solo está pensada para los destinos Android: `cargo check` en un
> ordenador de escritorio no funciona por dependencias de audio nativas.

### 2. La aplicación

```bash
cd android
./build.sh :app:assembleRelease      # o `gradle :app:assembleRelease`
```

Las pruebas de la parte Kotlin (copias de seguridad, diseños, temas) se lanzan con
`gradle :app:testDebugUnitTest`. Las del motor que no dependen de la ventana (`touch_controls.rs`,
`extras.rs`) se pueden ejecutar con `cargo test` en un crate de prueba que incluya esos dos módulos,
porque el crate completo de `gm8emulator` solo compila para Android.

El APK queda en `android/app/build/outputs/apk/release/`. Con Gradle se necesitan unos 2 GB de
memoria para el proceso; en equipos justos conviene tener swap.

## Firma y actualizaciones

Las versiones publicadas se firman con una **clave de release propia**. La clave nunca se sube al
repositorio: `android/keystore/` y `android/keystore.properties` están en `.gitignore`.

Para firmar tu propia compilación, crea `android/keystore.properties`:

```properties
storeFile=keystore/mi-clave.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Sin ese archivo, Gradle firma con la clave de depuración. **Una compilación firmada con otra clave no
puede instalarse encima de la oficial**, y la app rechaza actualizarse con un APK cuyo paquete o
firma no coincidan con los de la instalada.

El actualizador consulta `releases/latest` de este repositorio (las prereleases se ignoran), toma el
primer asset `.apk`, compara versiones numéricamente y entrega el archivo al instalador del sistema
mediante un `FileProvider`.

## Herramientas de diagnóstico

Pequeños programas de apoyo en `src/OpenGMK/gm8exe/examples/`. Se ejecutan en un PC sobre el `.exe` del juego:

```bash
cd src/OpenGMK
cargo run --release -p gm8exe --example roomspeed -- "juego.exe"            # room_speed de cada room
cargo run --release -p gm8exe --example memstats  -- "juego.exe"            # qué ocupan los recursos
cargo run --release -p gm8exe --example dumpgml   -- "juego.exe" gml.txt    # vuelca todo el GML a texto
```

Además, `tools/generate-layout.py` regenera el diseño por defecto de los controles táctiles
(`android/app/src/main/assets/controls-layout.json`).

## Historial de versiones

| Versión | Novedades |
| --- | --- |
| **0.6.4** | **Z y X vuelven a ser letras** en todas las pantallas. Los dibujos de los botones se ven más nítidos: cada pixel del dibujo ocupa un número entero de pixeles de pantalla y el dibujo se alinea a la cuadrícula, con contorno oscuro si le falta y colores algo más vivos. La mochila es la **mochila roja con Poké Ball de la pantalla de objetos del juego**; el disquete y el regalo también son del juego. |
| **0.6.3** | Los botones que el juego no tiene dibujados (acción, correr, huir, emoticonos, hablar, objeto asignado, cambiar, menú, guardar, cargar y avance rápido) llevan **iconos pixel-art de la app, pintados con el estilo del juego** (contorno oscuro y luz arriba a la izquierda). Esc usa la flecha de volver del propio juego. |
| **0.6.2** | Los botónes usan el **arte del propio juego** donde existe: bici, caña, mapa, bola, Mega-Aro, reloj, lupa del Pokédex, mochila, equipo, medalla y disco de MO. Las imágenes se leen del juego ya instalado en el móvil al arrancarlo (no se incluyen en la app); si falta alguna, el botón conserva su icono dibujado. |
| **0.6.1** | Los botones del juego muestran un **icono de su función** (mochila, mapa, bici, caña, huir…) en lugar de la letra, con opción para volver a las letras. 16 iconos nuevos, también disponibles para los botones propios. |
| **0.6.0** | **Copias de seguridad** automáticas y manuales de las partidas (restaurar, compartir, borrar), **informe de diagnóstico** de un toque, **guía de uso** en la app, cinco **colores de acento** y **negro puro AMOLED**, **iconos** para botones propios y **preajustes** de disposición (zurdo, compacto, grande), y nuevos atajos de mando (FPS y cambio de ranura). Pruebas automáticas nuevas. |
| **0.5.0** | Paquete propio **`com.ahjin.pr`** y firma de release propia. Tema **«Obsidiana y oro»** con ajuste de apariencia (oscuro, claro, sistema), botón Jugar dorado, barra y listas con filete fino, icono nuevo. Corregido el autofrenado del **avance rápido**. Nuevo ejemplo `roomspeed`. README reescrito. |
| **0.4.0** | Guardado rápido (3 ranuras), avance rápido x2/x3/x4, botones propios y diseños guardados, escala de píxeles perfectos, rejilla LCD y CRT, indicador de FPS/memoria, ahorro de batería, vibración y atajos de mando, soporte armeabi-v7a, actualizador apuntando a este repositorio. |
| **0.3.1-mejorado.4** | Joystick de 8 direcciones en todo el juego. |
| **0.3.1-mejorado.3** | Joystick de 8 direcciones en combate. |
| **0.3.1-mejorado.2** | Controles con aspecto nativo, editor de posiciones y tamaños, ajustes de audio. |
| **0.3.1-mejorado.1** | Audio reescrito (sin cortes), mejoras de memoria y rendimiento, primeras mejoras de controles. |
| **0.3.1** | Versión de partida: port original de [@Klisee](https://github.com/Klisee/pokemon-reloaded-android). |

Cada versión, con su APK, está en [Releases](../../releases).

## Estado y problemas conocidos

- Desarrollado y probado en un **RedMagic 8S Pro** (Snapdragon 8 Gen 2, Android 13, arm64).
- El motor de **32 bits (armeabi-v7a)** se compila y empaqueta, pero no se ha probado en ningún móvil de 32 bits.
- El **guardado rápido** es una función experimental. Si algo falla, el log lo registrará.
- La versión **0.6.0** está verificada con pruebas automáticas y compilación, pero todavía no con
  uso real en el móvil: las copias de seguridad, los iconos de botones y los atajos de mando son
  nuevos. Si algo no va bien, comparte el informe de diagnóstico.
- La app sigue en desarrollo y puede fallar. Si ocurre, guarda un log desde **Ajustes → Logs** y
  abre un [issue](../../issues) con él.

Problemas ya resueltos en el camino:

- Pantalla negra en móviles con GPU PowerVR (MediaTek Helio y similares), gracias al arreglo de
  Alexpix0 (ver créditos).
- Escalado de cámara en combate, el movimiento Vuelo, la XP infinita al abrir la mochila tras
  debilitar a un Pokémon y el cierre en combates dobles al cambiar de Pokémon.
- Cortes de audio al mezclar música y efectos.

Ideas para más adelante:

- Imágenes propias (pixel art) para más botones táctiles.
- Más ajustes de rendimiento para móviles modestos.
- Revisar la compatibilidad con mandos de todo tipo.

## Licencia y créditos

Este proyecto se distribuye bajo la licencia **GNU GPL v2** (consulta [`LICENSE`](LICENSE)), la misma
que OpenGMK, de la que deriva.

- **Pokémon Reloaded** es obra de **F3R Productions**: [blog del juego](https://pokemon-reloaded.blogspot.com/).
- **[OpenGMK](https://github.com/OpenGMK/OpenGMK)**: la reimplementación del motor de Game Maker 8 sin la que este proyecto no existiría.
- **[@Klisee](https://github.com/Klisee)**: autor del port original a Android
  ([pokemon-reloaded-android](https://github.com/Klisee/pokemon-reloaded-android)), base de este trabajo.
- **Alexpix0 (Armando)**: arreglo del renderizado que resolvió la pantalla negra en GPU PowerVR (enlazar
  VAO/VBO/programa en cada dibujo, no invalidar el búfer, corregir el `std140` del bloque de uniforms y subir
  los atlases por lotes). [Su PR](https://github.com/Klisee/pokemon-reloaded-android/pull/2).
- **Google Sans Flex**, la tipografía de la aplicación, bajo licencia OFL.

Pokémon es una marca de Nintendo, Game Freak y Creatures. Este proyecto es un trabajo de fans, sin
ánimo de lucro, y no está afiliado ni respaldado por ellos.
