package com.ahjin.pr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private class HelpTopic(val icon: ImageVector, val title: String, val paragraphs: List<String>)

private val TOPICS = listOf(
    HelpTopic(
        Icons.Filled.PlayArrow,
        "Primeros pasos",
        listOf(
            "La app no incluye el juego. En Inicio, pulsa Descargar para abrir el blog del juego y bajar el .zip, o ve directo a Importar si ya lo tienes en el telefono.",
            "Con Importar eliges el .zip. La primera vez se descomprime (unos 320 MB; necesitas unos 350 MB libres) y despues solo hay que pulsar Jugar.",
            "Tambien puedes mantener pulsado el .zip en tu administrador de archivos y elegir Abrir con > Pokemon Reloaded (o Compartir). La app mira que trae, te lo dice y no cambia nada hasta que confirmes. Vale para el juego, para copias de seguridad y para una partida suelta.",
        ),
    ),
    HelpTopic(
        Icons.Filled.TouchApp,
        "Controles en pantalla",
        listOf(
            "En Ajustes > Aspecto de los controles eliges entre Nativo y Pixel art clasico. En Controles tactiles ajustas opacidad, tamano, joystick flotante y zona muerta, y en Vibracion al tocar la respuesta tactil.",
            "Los botones del juego muestran un icono de lo que hacen (mochila, mapa, bici, cana, huir...) en vez de la letra; Z y X mantienen siempre su letra, y en combate tambien C y V porque ahi son los cuatro ataques. Siempre que el juego tiene el dibujo (bici, cana, mapa, bola, Mega-Aro, reloj, lupa, mochila...) se usa el suyo; los demas llevan iconos pixel-art pintados al estilo del juego. Puedes volver a las letras con Iconos en los botones.",
            "Posicion y tamano de los botones te deja arrastrar cada boton con el dedo y cambiar su tamano. Cada pestana (Mundo, Menus, Combate, Safari...) tiene sus propios botones.",
            "Los Preajustes (Zurdo, Compacto, Grande...) colocan todos los botones de golpe; son un buen punto de partida y despues puedes retocar. No tocan tus botones propios.",
            "En Botones propios puedes anadir hasta 8 botones: eliges que tecla pulsan, que muestran (texto o un icono) y en que pantallas salen. Los Disenos guardados te dejan tener varias disposiciones y cambiar entre ellas.",
        ),
    ),
    HelpTopic(
        Icons.Filled.SportsEsports,
        "Mando fisico",
        listOf(
            "Conecta el mando por Bluetooth o cable antes de abrir el juego. Con un mando activo, los controles tactiles se ocultan solos; tocar la pantalla los trae de vuelta.",
            "En Ajustes > Mando > Controles asignas cada boton. Ademas de las teclas del juego hay acciones de la app: guardado rapido, cargar, avance rapido, mostrar el indicador de FPS y cambiar de ranura de guardado rapido. Estas dos ultimas no tienen boton de fabrica.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Save,
        "Guardar partida",
        listOf(
            "Guardar dentro del juego funciona igual que en el original y es lo que debes usar para jugar de verdad.",
            "El guardado rapido (experimental, en Ajustes > Extras) saca una foto de toda la partida en 3 ranuras. Es aparte del guardado del juego. Cargar pide pulsar dos veces para evitar accidentes, y la musica en bucle vuelve a empezar.",
            "La velocidad del juego se elige en Extras: camara lenta (x0,5 y x0,75) o avance rapido (x1,5, x2, x3 y x4). El boton de avance rapido alterna entre velocidad normal y la elegida. A otra velocidad el sonido se silencia (se puede cambiar en Extras).",
            "En Botones especiales (dentro de Posicion y tamano de los botones) hay cuatro botones que no existen por defecto: Correr fijo (un toque deja X pulsada y otro la suelta), Boton turbo (mantenerlo pulsa Z una y otra vez), Velocidad (cada toque pasa a la siguiente velocidad) y Tabla de tipos (en combate, muestra las ventajas y debilidades del juego; un toque la cierra). Tambien puedes dar el modo Turbo o Fijo a cualquier boton propio.",
            "Si mantienes pulsado el icono de la app en el lanzador de Android salen dos atajos: Continuar (entra directo al juego) y Guardado rapido (entra y carga la ranura de guardado rapido elegida).",
        ),
    ),
    HelpTopic(
        Icons.Filled.Backup,
        "Copias de seguridad",
        listOf(
            "La app guarda copias de tus partidas por su cuenta: al abrir el juego (si han cambiado) y siempre antes de importar, borrar, reinstalar el juego o actualizar la app.",
            "Estan en Ajustes > Copias de seguridad. Desde ahi puedes restaurar una, compartirla o guardarla fuera del telefono. Restaurar guarda antes lo que tengas, asi que se puede deshacer.",
            "Las copias viven aparte del juego: borrar o reinstalar el juego no las toca. Desinstalar la app si las borra, asi que comparte una copia si vas a cambiar de telefono.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Speed,
        "Imagen y rendimiento",
        listOf(
            "El juego esta hecho para 30 fotogramas por segundo, y eso no es un fallo de la app: el diseno original no pasa de ahi. Un telefono de 120 Hz no lo hace ir mas rapido.",
            "En Ajustes > Resolucion: Pixeles perfectos mantiene la imagen nitida sin deformar los pixeles; Rellenar la pantalla la estira; Mantener la proporcion deja bandas a los lados.",
            "En Extras estan los efectos de imagen (suavizado, rejilla LCD, CRT), que son solo visuales, y el ahorro de bateria, util si el telefono se calienta.",
            "El indicador de FPS (Extras > Mostrar FPS, tiempo y memoria) ayuda a ver como va.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Tune,
        "Apariencia de la app",
        listOf(
            "En Ajustes > Apariencia eliges modo oscuro, claro o el del sistema. En Color y negro cambias el color de acento (Oro, Zafiro, Esmeralda, Rubi o Amatista).",
            "Negro puro (AMOLED) deja el fondo totalmente negro en el modo oscuro, lo que ahorra algo de bateria en pantallas OLED. Nada de esto afecta al juego.",
        ),
    ),
    HelpTopic(
        Icons.Filled.BugReport,
        "Si algo falla",
        listOf(
            "Pantalla negra o cierre al abrir: reimporta el .zip del juego y comprueba que tienes espacio libre. Tus partidas estan a salvo en las copias de seguridad.",
            "Sin sonido: sube el volumen multimedia, desconecta auriculares Bluetooth y reabre el juego.",
            "Va lento o se calienta: activa el ahorro de bateria, quita el efecto de imagen y cierra otras apps.",
            "Para avisar de un problema, pulsa Informe de diagnostico en Ajustes > Ayuda y comparte el archivo. Reune version, dispositivo, ajustes y el final del registro; no incluye tus partidas.",
        ),
    ),
)

/**
 * A short guide inside the app, one folded card per subject, so the answer to "how do I..." does not
 * need a browser. Text only: nothing here loads from the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    // -1 means every card is folded. Saved so rotating the phone does not close what was open.
    var open by rememberSaveable { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guia de uso") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            TOPICS.forEachIndexed { index, topic ->
                val expanded = open == index
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open = if (expanded) -1 else index }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                        ) {
                            Icon(topic.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(topic.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (expanded) "Cerrar" else "Abrir",
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Column(
                                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                topic.paragraphs.forEach {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
