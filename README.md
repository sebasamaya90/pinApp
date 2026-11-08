# notifications-lib

Librería Java **agnóstica a frameworks** que unifica el envío de notificaciones (Email, SMS, Push y Slack) detrás de una única API. Cambiar de proveedor (SendGrid → Mailgun, Twilio → Vonage…) es un cambio de una línea de configuración, sin tocar el código cliente.

- Java 25 (compila con `release 25`; usa records, sealed-style `switch` con patrones y hilos virtuales).
- Maven. Sin Spring/Quarkus, sin anotaciones de framework, sin YAML/properties: **toda la configuración es código Java**.
- Los envíos están **simulados** (no hay red). Cada proveedor construye el request real de su API (endpoint, auth, payload, parseo de respuesta) y lo entrega a un `HttpTransport`; para producción solo hay que implementar ese puerto con `java.net.http`.

## Ejecutar con Docker (sin Java local)

```bash
docker build -t pinapp-notifications .   # compila con Java 25 y corre los tests; falla si algún test falla
docker run --rm pinapp-notifications     # ejecuta NotificationExamples (demo de todos los canales)
```

Sin Docker: `mvn clean package` (requiere JDK 25) y
`java -cp "target/notifications-lib-1.0.0.jar:target/lib/*" com.pinapp.notifications.examples.NotificationExamples`.

## Instalación

```xml
<dependency>
    <groupId>com.pinapp</groupId>
    <artifactId>notifications-lib</artifactId>
    <version>1.0.0</version>
</dependency>
```
```groovy
implementation 'com.pinapp:notifications-lib:1.0.0'
```
(`mvn install` local; aún no está publicada en Maven Central.) Dependencias: Jackson (JSON) y SLF4J API. El *binding* de logging lo elige quien consume la librería.

## Quick start

```java
var sendGrid = new SendGridEmailProvider(new SendGridEmailProvider.Config(
        Secret.fromEnv("SENDGRID_API_KEY"), "no-reply@miapp.com", "Mi App"));

try (NotificationService service = NotificationService.builder()
        .register(Channels.email().provider(sendGrid).build())
        .build()) {

    NotificationResult result = service.send(EmailNotification.of("ana@example.com", "Bienvenida", "Hola Ana"));
    System.out.println(result.status() + " " + result.providerMessageId());
}
```

## Configuración

Un **canal** = validador + proveedor(es) + política de reintentos. Se arma con un builder y se registra en el servicio.

```java
NotificationService service = NotificationService.builder()
    .register(Channels.email()
        .provider(sendGrid)                    // principal
        .fallbackTo(mailgun)                   // failover si el principal falla
        .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(200), Duration.ofSeconds(2)))
        .build())
    .register(Channels.sms().provider(new TwilioSmsProvider(
        new TwilioSmsProvider.Config("ACxxxx", Secret.fromEnv("TWILIO_TOKEN"), "+15005550006"))).build())
    .register(Channels.push().provider(new FcmPushProvider(
        new FcmPushProvider.Config("mi-proyecto", Secret.fromEnv("FCM_ACCESS_TOKEN")))).build())
    .register(SlackChannel.builder().provider(slackProvider).build())
    .subscribe(event -> metrics.record(event))   // Pub/Sub de estados
    .executor(miExecutor)                        // opcional; por defecto hilos virtuales
    .build();
```

### Tipos de notificación

| Canal | Clase | Campos | Validación |
|---|---|---|---|
| Email | `EmailNotification` | `to`, `subject`, `body`, `html` | email válido, subject y body no vacíos |
| SMS | `SmsNotification` | `to`, `body` | E.164 (`+5491155550000`), body ≤ 1600 |
| Push | `PushNotification` | `deviceToken`, `title?`, `body`, `data` | token y body no vacíos |
| Slack | `SlackNotification` | `target?`, `body` | `#canal` o `@usuario` si hay target |

### Manejo de errores

Jerarquía (no checked) bajo `NotificationException`:

| Excepción | Cuándo | Datos |
|---|---|---|
| `ValidationException` | notificación inválida; **no se llamó al proveedor** | `errors()` (campo + mensaje), todos juntos |
| `DeliveryException` | el proveedor falló tras los reintentos | `provider()`, `attempts()`, `getCause()` |
| `ChannelNotConfiguredException` | no hay canal registrado para ese tipo | `channel()` |

```java
try {
    service.send(notification);
} catch (ValidationException e) {
    e.errors().forEach(System.out::println);   // "to: must be a valid email address"
} catch (DeliveryException e) {
    log.error("falló {} tras {} intentos", e.provider(), e.attempts());
}
```
Si preferís no usar excepciones: `service.trySend(n)` nunca lanza y devuelve un `NotificationResult` con `status` = `SENT | FAILED | REJECTED`.
A nivel proveedor, `ProviderException.retryable()` distingue fallos transitorios (red, HTTP 429/5xx → se reintenta) de permanentes (HTTP 4xx → no).

### Asincronía y lote

```java
CompletableFuture<NotificationResult> f = service.sendAsync(notification);      // falla con CompletionException(causa = NotificationException)
CompletableFuture<List<NotificationResult>> all = service.sendAll(lista);        // nunca falla en bloque: un resultado por notificación, en orden
```

### Resiliencia HTTP

Implementada a mano (sin Resilience4j) como **decoradores de `NotificationProvider`**, aplicados a *cada* proveedor con estado independiente (el breaker de SendGrid no afecta a Mailgun).

```java
Channels.email()
    .provider(sendGrid).fallbackTo(mailgun)
    .resilience(Resilience.recommended())      // o Resilience.builder()... a medida
    .build();
```

| Mecanismo | Clase | Comportamiento |
|---|---|---|
| **Retry + backoff** | `RetryingProvider`, `RetryPolicy` | `recommended()`: 3 intentos, backoff exponencial 200 ms→2 s con *jitter* 50%. Solo reintenta fallos transitorios (red, timeout, 429/5xx). Respeta el `Retry-After` del proveedor y un **deadline global** (`maxElapsed`, 10 s) |
| **Circuit breaker** | `CircuitBreaker` | Ventana deslizante de 10 llamadas; abre con ≥50% de fallos (mín. 5); 30 s abierto → HALF_OPEN con 2 pruebas. Solo cuentan fallos transitorios (un 4xx no abre el breaker). Abierto = falla rápido y **no reintentable** → el failover pasa al siguiente proveedor al instante |
| **Timeouts** | `JdkHttpTransport` | `connectTimeout` (2 s) y `requestTimeout` (5 s) sobre `java.net.http`; no sigue redirects (no reenvía credenciales). Un timeout → `ProviderException` con `reason()==TIMEOUT`, reintentable |
| **Bulkhead** | `Bulkhead` | Semáforo por proveedor (20 llamadas concurrentes por defecto; hilos virtuales, por eso no es un pool). Lleno = falla rápido, no reintentable |
| **Rate limiter** | `RateLimiter` | *Token bucket* (`permitsPerSecond`, `burst`). No está en `recommended()` porque la cuota depende de tu plan con el vendor: `.rateLimiter(new RateLimiter.Config(50, 100))`. Un rechazo es reintentable e informa cuánto esperar |

Orden del stack por proveedor: `Retry( RateLimiter( Bulkhead( CircuitBreaker( proveedor ))))`. El breaker va más adentro para juzgar solo llamadas reales al vendor; el retry va afuera para que cada intento pase por todas las protecciones.

```java
var http = new JdkHttpTransport(new JdkHttpTransport.Config(Duration.ofSeconds(2), Duration.ofSeconds(5)));
var sendGrid = new SendGridEmailProvider(config, http);   // transporte real en lugar del simulado

Resilience.builder()
    .retryPolicy(RetryPolicy.withJitter(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(200), Duration.ofSeconds(2)), 0.5))
    .maxElapsed(Duration.ofSeconds(10))
    .circuitBreaker(CircuitBreaker.Config.defaults())
    .circuitBreakerListener(t -> metrics.breaker(t.name(), t.to()))   // CLOSED / OPEN / HALF_OPEN
    .bulkhead(new Bulkhead.Config(20, Duration.ZERO))
    .rateLimiter(new RateLimiter.Config(50, 100))
    .build();
```

> **Cuidado con los duplicados:** tras un *request timeout* el proveedor pudo haber procesado el mensaje, así que un reintento puede (raramente) duplicarlo. Los fallos de conexión sí son siempre seguros de reintentar. Twilio, SendGrid y Mailgun no ofrecen idempotency key nativa para estos endpoints; si el duplicado es inaceptable (p. ej. SMS con cobro), usá `RetryPolicy.none()` para ese canal.
> El `requestTimeout` se aplica por intento: con 3 intentos de 5 s el peor caso es ~15 s, pero el deadline de 10 s impide *iniciar* otro intento cuando ya no cabe.

### Templates

```java
String body = MessageTemplate.of("Hola {{name}}, tu pedido {{order}} está listo")
        .render(Map.of("name", "Luis", "order", "#77"));   // falla si falta alguna variable
```

## Proveedores soportados

| Canal | Proveedor | API simulada |
|---|---|---|
| Email | `SendGridEmailProvider` | v3 `POST /mail/send`, Bearer, 202 + `X-Message-Id` |
| Email | `MailgunEmailProvider` | `POST /v3/{domain}/messages`, Basic, form-encoded |
| SMS | `TwilioSmsProvider` | `POST /Accounts/{sid}/Messages.json`, Basic, 201 + `sid` |
| SMS | `VonageSmsProvider` | `POST /sms/json`; error dentro de HTTP 200 (`messages[0].status`) |
| Push | `FcmPushProvider` | HTTP v1 `POST /projects/{id}/messages:send`, Bearer OAuth2 |
| Slack | `SlackWebhookProvider` | Incoming webhook, respuesta `ok` |

## Cómo extender

**Nuevo proveedor** (p. ej. Amazon SES): extender `HttpNotificationProvider<EmailNotification>` e implementar `buildRequest` y `extractMessageId`. Nada más se modifica.

**Nuevo canal** (p. ej. WhatsApp), sin tocar ninguna clase existente (ver `ExtensibilityTest` y el paquete `slack`, que se construyó así):

```java
record WhatsAppNotification(String phone, String body) implements Notification {
    static final ChannelType WHATSAPP = ChannelType.of("whatsapp");
    public ChannelType channel() { return WHATSAPP; }
    public String recipient() { return phone; }
}
// 1) un NotificationProvider<WhatsAppNotification>   2) un validador   3) registrarlo:
service = NotificationService.builder()
    .register(StandardChannel.builder(WhatsAppNotification.WHATSAPP, WhatsAppNotification.class, validator)
        .provider(provider).build())
    .build();
```

## Decisiones de diseño (SOLID y patrones)

| Principio / patrón | Dónde |
|---|---|
| **S**ingle Responsibility | validar (`NotificationValidator`), traducir a la API del vendor (`NotificationProvider`), reintentar (`RetryingProvider`), enrutar (`NotificationService`), notificar estados (`NotificationListener`): cada uno en su clase |
| **O**pen/Closed | `ChannelType` es un value object, **no un enum**; el servicio solo conoce `NotificationChannel`. Agregar canal/proveedor = clases nuevas |
| **L**iskov | cualquier `NotificationProvider<N>` es intercambiable (incluidos decoradores y el composite de failover) |
| **I**nterface Segregation | interfaces pequeñas de un método: `NotificationValidator`, `NotificationListener`, `HttpTransport`, `Sleeper`, `RetryPolicy` |
| **D**ependency Inversion | el servicio depende de `NotificationChannel`; los proveedores del puerto `HttpTransport` (inyectable → tests sin red) |
| Facade | `NotificationService` |
| Strategy | `RetryPolicy`, `NotificationProvider` |
| Decorator | `RetryingProvider`, `CircuitBreakerProvider`, `BulkheadProvider`, `RateLimiterProvider` (apilados por `Resilience`) |
| Composite / Chain | `FallbackProvider` (failover) |
| Template Method | `HttpNotificationProvider` |
| Builder | `NotificationService.builder()`, `StandardChannel.builder()`, `EmailNotification.builder()` |
| Observer (Pub/Sub) | `NotificationListener` + `NotificationEvent`; un suscriptor que falla no afecta al envío ni a otros suscriptores |

Otras decisiones:
- **Diferencias entre canales**: cada canal tiene su propia clase de notificación (el email tiene `subject`, el SMS no) pero todas implementan `Notification`; el cliente siempre llama `service.send(...)`.
- Las notificaciones son *permisivas* (no lanzan al construirse) y los validadores reportan **todos** los problemas a la vez.
- No se usa Lombok a propósito: los `record`s de Java cubren el mismo objetivo sin procesadores de anotaciones.

## Seguridad

- Credenciales envueltas en `Secret`: `toString()` devuelve `Secret[****]`, por lo que no se filtran en logs, excepciones ni `toString()` de las configuraciones (los `Config` son records).
- Obtenerlas con `Secret.fromEnv("VAR")` (o de tu gestor de secretos: Vault, AWS Secrets Manager…). **Nunca** en código ni en el repositorio.
- `HttpRequest.toString()` solo muestra método, host y *nombres* de headers; jamás valores, path/query (el webhook de Slack lleva el token en la URL) ni body.
- Los mensajes de error de proveedores se construyen desde la respuesta del vendor, nunca desde el request.
- La imagen Docker corre con un usuario sin privilegios y no incluye credenciales.
- Usá siempre HTTPS y rotá las claves periódicamente; en FCM, el access token OAuth2 es de corta duración.

## API Reference (principal)

| Clase | Responsabilidad |
|---|---|
| `NotificationService` | `send`, `trySend`, `sendAsync`, `sendAll`, `subscribe`, `close` |
| `NotificationResult` | `id`, `channel`, `status`, `provider`, `providerMessageId`, `attempts`, `timestamp`, `error` |
| `Notification` / `ChannelType` | abstracción común y tipo de canal extensible |
| `Channels`, `StandardChannel.Builder` | `provider()`, `fallbackTo()`, `retryPolicy()`, `build()` |
| `NotificationProvider<N>` | SPI por vendor: `name()`, `send(N)` |
| `RetryPolicy` | `none()`, `fixedDelay()`, `exponentialBackoff()` |
| `Secret`, `MessageTemplate` | credenciales seguras, plantillas `{{var}}` |

## Qué falta / próximos pasos

- Refresco automático del token OAuth2 de FCM y parseo de `Retry-After` en formato HTTP-date (hoy solo segundos).
- Idempotencia (clave de deduplicación) para evitar duplicados al reintentar tras un timeout.
- Rate limiter distribuido (el actual es por instancia/JVM) y métricas del breaker/bulkhead listas para Micrometer.
- Adjuntos, CC/BCC y múltiples destinatarios en email; prioridad/TTL en push.
- Publicación en Maven Central y métricas (Micrometer) como módulo opcional.
- Se priorizó: abstracción + 3 canales obligatorios + errores + tests, y luego los opcionales (async, reintentos, validación, templates, Pub/Sub, Slack, Docker).

## Tests

`mvn test` (101 tests, JUnit 5, sin mocks mágicos: dobles escritos a mano `ScriptedTransport`, `FakeProvider` y un `MutableClock`). Cubren validadores, política de reintentos, failover, payload y manejo de errores de cada proveedor, enrutamiento, eventos, async/lote y la extensión con un canal nuevo definido solo en tests.
La resiliencia se prueba con reloj y *sleeper* inyectados (cero esperas reales): máquina de estados del breaker, bulkhead con concurrencia real, token bucket, `Retry-After`, deadline, y la composición completa (breaker abierto → sin reintentos → failover). Los timeouts de `JdkHttpTransport` se verifican contra un servidor HTTP local en loopback.

## Uso de IA

Esta solución fue desarrollada con **Claude Code** (modelo Claude Sonnet 5.5, de Anthropic), a partir del enunciado del challenge en PDF. La IA propuso la arquitectura, escribió el código, los tests, el Dockerfile y este README, y verificó la compilación y los tests dentro de Docker (Java 25). Las decisiones de alcance (Java 25, dockerización, SOLID como eje) fueron indicadas por la persona autora, quien debe revisar y defender el diseño en la entrevista técnica.
