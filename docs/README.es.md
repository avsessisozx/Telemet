<p align="center"><img src="icon.png" width="100" alt="Telemet"></p>

<h1 align="center">Telemet</h1>

<p align="center"><b>Telemetría GPS gratuita para cámaras DJI Osmo Action. Usa tu teléfono Android en lugar del mando GPS Bluetooth de DJI.</b></p>

<p align="center">[English](../README.md) · [Türkçe](README.tr.md) · **Español** · [Français](README.fr.md) · [Italiano](README.it.md) · [Deutsch](README.de.md) · [Português](README.pt-BR.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md) · [العربية](README.ar.md)</p>

---

Las cámaras DJI Osmo Action solo graban velocidad, ruta y altitud en el vídeo cuando está conectado el **mando GPS Bluetooth** de DJI. Telemet convierte tu teléfono en ese mando: se conecta por Bluetooth, envía el GPS del teléfono 10 veces por segundo y la cámara lo guarda dentro del vídeo. Abre el vídeo en **DJI Mimo** o **DJI Studio** y añade el panel de velocidad, ruta y altitud. Sin convertir archivos FIT ni hardware extra. Pensado para moteros, funciona igual en bici, coche o a pie.

## Funciones

- 📍 **Emula el mando GPS de DJI:** los datos se incrustan en el vídeo
- 🎬 **Control de la cámara:** inicia/detén la grabación desde la app o la notificación; modo, estado y batería
- 🗂️ **Cada ruta por separado:** "Mis rutas" agrupadas por día con distancia, duración, velocidad máx./media y desnivel
- 💾 **Copia FIT + GPX de cada ruta:** importa el FIT en DJI Studio si la cámara no recibió datos
- 🏍️ **Ángulo de inclinación y fuerza G** a partir del giroscopio y el GPS, sin importar cómo esté montado el teléfono
- 🎞️ **Sincronizar con vídeos**: la cámara divide las grabaciones largas en partes; elígelas (o compártelas con Telemet desde la galería) y obtén un FIT + GPX por parte o un único archivo unido
- 🟩 **Vídeo de indicadores**: velocidad, inclinación, G, altitud, distancia y minimapa sobre fondo verde, con el tamaño y la duración de cada parte; colócalo sobre tu vídeo con chroma key
- 🎛️ **Inclinación y G en DJI Mimo** (opcional): también se escriben en los campos de cadencia y potencia
- 🤖 **Grabación automática** (opcional): la cámara graba al arrancar y se detiene tras 3 minutos parado
- 📈 **Altitud barométrica** en teléfonos con barómetro
- 🔋 **Funciona como una app de navegación:** sigue grabando con la pantalla apagada u otra app delante
- ⚡ **Primera señal rápida:** el GPS se prepara al abrir la app; se rellenan los huecos antes de la señal y en túneles
- 🌍 **12 idiomas** · 🔓 **Gratis y de código abierto:** sin anuncios, sin cuenta, sin internet

## Descarga e instalación

1. En el teléfono abre la **[última versión](https://github.com/avsessisozx/Telemet/releases/latest)**.
2. Toca **Telemet-x.y.z.apk** para descargarlo y ábrelo.
3. Si Android avisa de orígenes desconocidos, permite instalar desde el navegador o el gestor de archivos.
4. Las nuevas versiones se instalan encima de la anterior y conservan tus rutas.

## Inicio rápido

1. En la **cámara**, activa la conexión inalámbrica. **Cierra DJI Mimo**: la cámara solo habla con un mando a la vez.
2. Abre Telemet. La tarjeta **Configuración** te pide los permisos necesarios (ubicación "Permitir todo el tiempo", Bluetooth, notificaciones, batería; en Xiaomi también inicio automático y ahorro de batería).
3. Pulsa **Iniciar**. La primera vez, aprueba la conexión en la pantalla de la cámara (el código debe coincidir).
4. Cuando el punto GPS esté en verde, empieza a grabar desde la cámara o desde Telemet.
5. Al terminar pulsa **Detener**. La ruta aparece en **Mis rutas** y los archivos FIT/GPX se guardan en `Descargas/Telemet`.

## Usar la copia FIT en DJI Studio

Comparte la ruta desde **Mis rutas** al ordenador → abre el vídeo en DJI Studio → **Panel** → **Fuente de datos** → **Importar archivo FIT** → si hace falta, **Alineación de tiempo**.

---

Cámaras compatibles, sensores, preguntas frecuentes y detalles técnicos: [página principal en inglés](../README.md).

Licencia MIT. Este proyecto no está afiliado a DJI. DJI, Osmo y Osmo Action son marcas de SZ DJI Technology Co., Ltd.
