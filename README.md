# Cargadores privados Iberdrola

Prototipo Android de **solo lectura** para consultar cargadores autorizados del usuario y presentarlos como un listado en Android Auto.

## Qué incluye esta primera versión

- Proyecto Android nativo en Kotlin, API 36.
- Esqueleto de inicio de sesión OAuth 2.0 Authorization Code + PKCE mediante navegador del sistema.
- Los datos de sesión se guardan únicamente en el teléfono, cifrados con Android Keystore.
- Inicio de sesión manual contra la página de Iberdrola; la aplicación no solicita ni conserva contraseña o MFA.
- Adaptador local de solo lectura: favoritos autorizados y detalle de sus conectores. Para esta primera prueba, marca con el corazón los cargadores privados que quieras ver en la app oficial de Movilidad.
- Pantalla Android Auto con el listado de favoritos: nombre, ID, disponibilidad y potencia.
- Botón para borrar toda la sesión local.

## Límite deliberado

No hay contraseña, código MFA, token, pago ni reserva en el repositorio. Tras una sesión válida, el token se guarda cifrado solo en el teléfono y se usa únicamente para consultas de lectura. Android Auto no ofrece mapa, acción «Ir» ni ninguna ruta de navegación.

El `redirectUri` actualmente utilizado es:

`rv://callback/android/es.iberdrola.recargaverde/callback`

Es una integración personal no oficial y puede dejar de funcionar si Iberdrola modifica su servicio. Si la devolución del navegador la abre en la aplicación oficial en vez de este prototipo, Android no permitirá completar el flujo y no hay un mecanismo alternativo implementado.

## Abrir el proyecto

1. Instala Android Studio con JDK 17 o posterior y el SDK Android 16 (API 36).
2. Abre esta carpeta en Android Studio y permite la sincronización de Gradle.
3. Ejecuta la variante `debug` en un teléfono Android 8 o posterior.

La configuración usa Android Gradle Plugin 9.3.0 y `compileSdk`/`targetSdk` 36, según la documentación actual de Android.
