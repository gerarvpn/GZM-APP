# Plan de pruebas de GZM-APP

La compilación y el análisis del código no demuestran que una VPN pase tráfico. Antes de sustituir o borrar la instalación anterior, probar en un teléfono Android y en una TV Android real.

## Arranque y estabilidad

- Instalación limpia y primera conexión en Wi-Fi.
- Cierre forzado, reapertura y conexión en un solo intento.
- Envío de dos órdenes de inicio consecutivas: debe quedar un solo núcleo y una sola interfaz VPN.
- Cambio Wi-Fi → datos móviles y datos móviles → Wi-Fi con sesión activa.
- Revocar el permiso VPN y verificar que la app muestra un error claro, no un estado indefinido de “Conectando”.

## Tráfico y DNS

- Con la VPN conectada, abrir varios sitios y una app de streaming; comprobar carga real, no solo el icono del sistema.
- Verificar que IPv4, IPv6 cuando lo ofrezca la red y DNS resuelven nombres.
- Comparar IP pública antes y durante la conexión desde otra app.
- Confirmar que los contadores de subida y bajada aumentan mientras se genera tráfico.
- Repetir en Wi-Fi, datos móviles y redes IPv6/NAT64.

## Panel y persistencia

- Sin Internet al iniciar, comprobar que la configuración guardada no se borra.
- Con el panel inaccesible, conectar usando el perfil local y comprobar el tráfico de las demás apps.
- Restaurar Internet, actualizar estado del panel y verificar que la cuenta/consumo se muestran de nuevo.
- Actualizar manualmente y luego validar la actualización por intervalo.

## Decisión de encaminamiento Android

Android exige que las conexiones del propio cliente VPN salgan por la red subyacente para que el núcleo no reinyecte sus propias conexiones en el mismo TUN. La versión embebida del motor permite pasar el descriptor TUN y consultar propietarios de sockets, pero no ofrece un callback de protección de sockets. Por eso el proyecto excluye el UID de GZMNET del túnel y enruta las demás apps del dispositivo. Para incluir también la app GZMNET sería necesario añadir y probar una integración explícita de protección de sockets en el motor nativo.

## Estado de verificación

- El arranque de Xray se inicia en un hilo de trabajo dedicado para mantener disponible el hilo principal del servicio.
- La pantalla de inicio se dibuja con el estado guardado mientras se recuperan perfiles y se preparan los datos de Xray en segundo plano; mientras termina, el botón indica que está preparando.
- La búsqueda de actualizaciones consulta el release más reciente de GZM-APP y compara el número de build, manteniendo visible la versión comercial 1.0.
- La descarga del APK se realiza en el proceso de GZMNET, no mediante el UID del DownloadManager del sistema, porque la app está excluida del TUN para que el tráfico de control del núcleo no se reinjecte en la VPN. El archivo se valida como ZIP/APK y se instala desde el almacenamiento privado de caché con FileProvider. Las pruebas JVM cubren descarga vacía/incompleta y cabecera APK; falta confirmar que el APK se descargue e instale con una TV real mientras el túnel está conectado.
- Las builds distribuibles se firman con una sola clave privada guardada como secreto del repositorio; el proyecto cambió a un ID de app nuevo porque las vistas previas tenían firmas distintas y Android rechazaba su actualización directa.
- El APK universal está firmado con el esquema v2 y requiere Android 7/API 24, mínimo que exige la librería Android de Xray.
- El túnel no espera el ping HTTP antes de avisar que arrancó: la latencia se consulta en segundo plano para que la pantalla responda antes. Si no contesta, muestra el fallo y lo guarda en diagnóstico.
- La interfaz cancela y muestra un error si el inicio queda pendiente por más de 90 segundos; se ignoran callbacks de inicio tardíos.
- Los diagnósticos se guardan localmente, con límite de tamaño y redacción de tokens, contraseñas y enlaces de servidores. Solo se comparten cuando el usuario pulsa “Compartir con el creador”; incluyen versión de la app, Android, modelo, errores de la app y fases de arranque lentas.
- La build correrá pruebas JVM del tiempo límite, respuesta de ping y redacción de datos sensibles, además de compilación y verificación de APK.
- No se ha probado esta corrección en una TV física; la compilación y las pruebas automáticas no comprueban tráfico real.
