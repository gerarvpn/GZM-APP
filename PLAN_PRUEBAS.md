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

- Sin Internet al iniciar, comprobar que la configuración guardada no se borra ni se hace una descarga de servidores antes de conectar.
- Con el panel inaccesible, iniciar con un perfil local guardado y comprobar que la primera conexión y el tráfico de las demás apps funcionan sin esperar la API.
- Restaurar Internet, actualizar estado del panel y verificar que la cuenta/consumo se muestran de nuevo.
- Actualizar manualmente y luego validar la actualización por intervalo.

## Decisión de encaminamiento Android

Android exige que las conexiones del propio cliente VPN salgan por la red subyacente para que el núcleo no reinyecte sus propias conexiones en el mismo TUN. La versión embebida del motor permite pasar el descriptor TUN y consultar propietarios de sockets, pero no ofrece un callback de protección de sockets. Por eso el proyecto excluye el UID de GZMNET del túnel y enruta las demás apps del dispositivo. Para incluir también la app GZMNET sería necesario añadir y probar una integración explícita de protección de sockets en el motor nativo.

## Estado de verificación

- El arranque de Xray se inicia en un hilo de trabajo dedicado para mantener disponible el hilo principal del servicio. Si llega una orden de desconexión mientras Xray abre el descriptor TUN, el cierre se encola detrás del inicio para evitar la carrera que producía «Already closed».
- La pantalla de inicio y la conexión usan el estado y perfil cifrado guardados localmente; no hacen consulta al panel para permitir la primera conexión ni al volver a primer plano. Los servidores solo se actualizan al pedirlo manualmente o cuando vence el intervalo configurado. Los datos locales imprescindibles de Xray se preparan en segundo plano.
- La búsqueda de actualizaciones consulta el release más reciente de GZM-APP y compara el número de build, manteniendo visible la versión comercial 1.0.
- La descarga del APK se realiza en el proceso de GZMNET, no mediante el UID del DownloadManager del sistema, porque la app está excluida del TUN para que el tráfico de control del núcleo no se reinjecte en la VPN. El archivo se valida como ZIP/APK y se instala desde el almacenamiento privado de caché con FileProvider. Al iniciar nueva descarga se reemplaza solo el APK viejo de actualización; al abrir GZMNET se elimina solo un parcial interrumpido, y el APK completo se elimina tras confirmación de MY_PACKAGE_REPLACED. Perfiles/configuración nunca se borran; hay que probar en el TV la descarga, instalación y continuidad de VPN.
- El informe de diagnóstico contiene únicamente fallos de la app, Xray, conexión o túnel; se excluyen los registros de rendimiento y se filtran también de los informes antiguos. El contador de arranque basado en tiempo de vida del proceso se retiró porque podía incluir minutos en segundo plano y reportar duraciones falsas.
- Cerrar la pantalla o quitar la tarea de Recientes no debe detener el servicio VPN: CoreVpnService sigue como foreground service sticky y Android declara stopWithTask=false. Solo la acción explícita Desconectar envía la orden de parada. Hay que comprobar esta persistencia en TV/teléfono reales y en distintos fabricantes, porque algunos sistemas pueden restringir procesos en segundo plano.
- Se evita escribir dos veces el indicador de reconexión cifrado al iniciar VPN: se migra a una preferencia MMKV no secreta y se elimina la escritura redundante de la confirmación de conexión, que podía frenar el hilo visual después de que Xray iniciaba.
- Las builds distribuibles se firman con una sola clave privada guardada como secreto del repositorio; el proyecto cambió a un ID de app nuevo porque las vistas previas tenían firmas distintas y Android rechazaba su actualización directa.
- El APK universal está firmado con el esquema v2 y requiere Android 7/API 24, mínimo que exige la librería Android de Xray.
- El túnel no espera el ping HTTP antes de avisar que arrancó: la latencia se consulta en segundo plano para que la pantalla responda antes. Si no contesta, muestra el fallo y lo guarda en diagnóstico.
- La interfaz cancela y muestra un error si el inicio queda pendiente por más de 90 segundos; se ignoran callbacks de inicio tardíos.
- Los diagnósticos se guardan localmente, con límite de tamaño y redacción de tokens, contraseñas y enlaces de servidores. Solo se comparten cuando el usuario pulsa “Compartir con el creador”; incluyen versión de la app, Android, modelo y errores de la app, Xray y túnel.
- La build correrá pruebas JVM del tiempo límite, respuesta de ping, categorías de error, redacción de datos sensibles y limpieza solo después de confirmar reemplazo del paquete, además de compilación y verificación de APK.
- No se ha probado esta corrección en una TV física; la compilación y las pruebas automáticas no comprueban tráfico real.
