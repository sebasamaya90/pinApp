# notifications-lib

Librería Java **agnóstica a frameworks** que unifica el envío de notificaciones (Email, SMS, Push y Slack) detrás de una única API. Cambiar de proveedor (SendGrid → Mailgun, Twilio → Vonage…) es un cambio de una línea de configuración, sin tocar el código cliente.

- Java 25 (compila con `release 25`; usa records, sealed-style `switch` con patrones y hilos virtuales).
- Maven. Sin Spring/Quarkus, sin anotaciones de framework, sin YAML/properties: **toda la configuración es código Java**.
- Los envíos están **simulados** (no hay red). Cada proveedor construye el request real de su API (endpoint, auth, payload, parseo de respuesta) y lo entrega a un `HttpTransport`; para producción solo hay que implementar ese puerto con `java.net.http`.

## Ejecutar con Docker (sin Java local)

```bash
docker build -t pinapp-notifications .   # compila con Java 25 y corre los tests; falla si alguno falla
docker run --rm pinapp-notifications     # ejecuta NotificationExamples (demo de todos los canales)

# Recomendado: la demo no necesita escribir en disco, red ni privilegios
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges --network none pinapp-notifications
```

Sin Docker: `mvn clean package` (requiere JDK 25) y
`java -cp "target/notifications-lib-1.0.0.jar:target/lib/*" com.pinapp.notifications.examples.NotificationExamples`.

Un solo módulo Maven (`pom.xml` en la raíz). Dependencias de la librería: Jackson y SLF4J API.

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
Hoy se usa con `mvn install` local. El *binding* de logging lo elige quien consume la librería.

**Publicación en Maven Central:** el `pom` ya está preparado (perfil `release` con sources, javadoc, firma GPG y `central-publishing-maven-plugin`; `mvn -Prelease -Dgpg.skip verify` genera los jars sin firmar). Antes de publicar hay que confirmar los datos marcados con `TODO` en el `pom.xml` raíz: un `groupId` de un namespace verificado en Sonatype Central (`com.pinapp` no lo está), `url`/`scm`, y la licencia (se asumió Apache-2.0; falta el archivo `LICENSE`). Luego: `mvn -Prelease clean deploy` con clave GPG y token `central` en `~/.m2/settings.xml`.

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

### ¿De dónde sale `Secret.fromEnv("SENDGRID_API_KEY")`?

`Secret.fromEnv("X")` lee la **variable de entorno** `X` del proceso que ejecuta tu aplicación. La librería no lee ningún archivo: vos definís la variable antes de arrancar.

1. **Obtené la clave en el panel del proveedor.** En SendGrid: *Settings → API Keys → Create API Key*; conviene una clave restringida solo a "Mail Send". Se muestra una única vez.
2. **Exportala como variable de entorno** antes de ejecutar la aplicación:

   ```bash
   export SENDGRID_API_KEY="SG.xxxxxxxx"                     # Linux / macOS
   ```
   ```powershell
   $env:SENDGRID_API_KEY = "SG.xxxxxxxx"                     # Windows (PowerShell)
   ```
   ```bash
   docker run --rm -e SENDGRID_API_KEY=SG.xxxxxxxx mi-imagen  # Docker (o --env-file; en Kubernetes, un Secret)
   ```
   En producción la variable la inyecta tu plataforma (secrets de Kubernetes, CI/CD, un gestor como Vault o AWS Secrets Manager); nunca la escribas en el código ni la subas al repositorio.

> **Para probar el Quick start sin una clave real:** los proveedores usan por defecto un transporte **simulado** (no salen a internet), así que sirve cualquier valor, p. ej. `export SENDGRID_API_KEY=SG.demo`. Ojo: si la variable **no existe**, `fromEnv` lanza `IllegalStateException` a propósito, para no arrancar con una credencial vacía.

Si preferís obtener el valor de otro sitio (un gestor de secretos, por ejemplo), usá `Secret.of(valor)`.

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
        new FcmPushProvider.Config("mi-proyecto", fcmTokens))).build())   // fcmTokens: ver "Token OAuth2 de FCM"
    .register(SlackChannel.builder().provider(slackProvider).build())
    .subscribe(event -> metrics.record(event))   // Pub/Sub de estados
    .executor(miExecutor)                        // opcional; por defecto hilos virtuales
    .build();
```

### Tipos de notificación

| Canal | Clase | Campos | Validación |
|---|---|---|---|
| Email | `EmailNotification` | `to` (varios), `cc`, `bcc`, `subject`, `body`, `html` | ≥1 `to`; todas las direcciones válidas; ≤ 50 destinatarios en total; subject sin saltos de línea y ≤ 998; body ≤ 500 000 |
| SMS | `SmsNotification` | `to`, `body` | E.164 (`+5491155550000`), body ≤ 1600 |
| Push | `PushNotification` | `deviceToken`, `title?`, `body`, `data`, `priority`, `ttl?` | token y body no vacíos; body ≤ 4096; `data` ≤ 4096 bytes; `ttl` entre 0 y 28 días |
| Slack | `SlackNotification` | `body` | body no vacío y ≤ 40 000. El canal lo fija el webhook (no se puede cambiar por mensaje) |

```java
EmailNotification.builder().to("ana@x.com").to("luis@x.com").cc("jefe@x.com").bcc("auditoria@x.com")
        .subject("Factura #42").body("...").build();

PushNotification.builder().deviceToken(token).title("Alerta").body("Nuevo acceso")
        .priority(PushNotification.Priority.HIGH).ttl(Duration.ofMinutes(10)).build();
```
Los adjuntos de email **no** están implementados.

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
Si preferís no usar excepciones: `service.trySend(n)` nunca lanza y devuelve un `NotificationResult` con `status` = `SENT | FAILED | REJECTED | DUPLICATE`.
A nivel proveedor, `ProviderException.retryable()` distingue fallos transitorios (red, HTTP 429/5xx → se reintenta) de permanentes (HTTP 4xx → no).

### Asincronía y lote

```java
CompletableFuture<NotificationResult> f = service.sendAsync(notification);      // falla con CompletionException(causa = NotificationException)
CompletableFuture<List<NotificationResult>> all = service.sendAll(lista);        // nunca falla en bloque: un resultado por notificación, en orden
```
`sendAll` rechaza lotes mayores a `maxBatchSize` (1000 por defecto, configurable en el builder del servicio) con `IllegalArgumentException`, para proteger memoria y cuotas del vendor.

**Ejemplo de lote** (puede mezclar canales; cada notificación va al suyo):

```java
List<Notification> lote = List.of(
    EmailNotification.of("a@x.com", "Hola", "..."),
    new SmsNotification("+5491155550000", "Tu código: 1234"),
    PushNotification.builder().deviceToken("tok").body("Novedad").build());

List<NotificationResult> resultados = service.sendAll(lote).join();   // join() espera a que terminen todas
```

### Idempotencia (evitar duplicados)

Todos los métodos de envío tienen una variante con clave de idempotencia: `send(n, "factura-42-email")`, `trySend`, `sendAsync`.

```java
NotificationService.builder()
    .register(...)
    .deduplication(new InMemoryDeduplicationStore(), Duration.ofHours(24))   // opt-in
    .build();

service.send(email, "factura-42-email");   // SENT
service.send(email, "factura-42-email");   // DUPLICATE: no se envía, no es una excepción
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
| **Timeouts** | `JdkHttpTransport` | `connectTimeout` (2 s) y `requestTimeout` (5 s) sobre `java.net.http`; solo HTTPS; respuesta acotada a 64 KB; no sigue redirects. Un timeout → `ProviderException` con `reason()==TIMEOUT`, reintentable |
| **Bulkhead** | `Bulkhead` | Semáforo por proveedor (20 llamadas concurrentes por defecto; hilos virtuales, por eso no es un pool). Lleno = falla rápido, no reintentable |
| **Rate limiter** | `RateLimiter` (interfaz), `TokenBucketRateLimiter` | *Token bucket* por JVM (`permitsPerSecond`, `burst`). No está en `recommended()` porque la cuota depende de tu plan con el vendor: `.rateLimiter(new RateLimiter.Config(50, 100))`. Un rechazo es reintentable e informa cuánto esperar |

`Retry-After` se interpreta tanto en segundos como en formato HTTP-date (`Wed, 21 Oct 2026 07:28:00 GMT`), con un tope de 5 minutos para que un vendor defectuoso no pueda bloquear el envío.

**Rate limiter distribuido:** no se incluye una implementación porque exigiría infraestructura (Redis), lo que contradice una librería sin dependencias de servidor. En su lugar `RateLimiter` es un puerto: implementalo sobre un store compartido y enchufalo con
`Resilience.builder().rateLimiterFactory(providerName -> new MiRedisRateLimiter(providerName, ...))`.

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

### Token OAuth2 de FCM (refresco automático)

FCM v1 usa un access token que dura ~1 hora. `FcmPushProvider.Config` recibe un `TokenProvider`; `RefreshingTokenProvider` lo cachea y lo **renueva antes de que expire** (`refreshSkew`). Si la renovación falla pero el token vigente aún sirve, lo reutiliza. Un HTTP 401 invalida el token cacheado y se reintenta con uno nuevo. La librería no depende de Google: vos aportás un `TokenFetcher`.

```java
// Ejemplo con google-auth-library (dependencia de TU aplicación, no de esta librería)
GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(System.getenv("GOOGLE_APPLICATION_CREDENTIALS")))
        .createScoped("https://www.googleapis.com/auth/firebase.messaging");

TokenProvider fcmTokens = new RefreshingTokenProvider(() -> {
    credentials.refresh();
    AccessToken t = credentials.getAccessToken();
    return new com.pinapp.notifications.config.AccessToken(Secret.of(t.getTokenValue()), t.getExpirationTime().toInstant());
}, Duration.ofMinutes(5));

new FcmPushProvider(new FcmPushProvider.Config("mi-proyecto", fcmTokens), new JdkHttpTransport());
```
Para un token fijo (tests): `new FcmPushProvider.Config("proyecto", Secret.of("..."))`.

### Métricas y observabilidad

La librería no depende de ningún sistema de métricas; expone tres puntos de enganche y vos los conectás al que uses (Micrometer, Prometheus, logs…):

| Hook | Qué recibe |
|---|---|
| `NotificationService.builder().subscribe(listener)` | `NotificationEvent` por cada envío: `SENT`, `FAILED`, `REJECTED`, `DUPLICATE` (canal, proveedor, detalle; nunca destinatario ni contenido) |
| `Resilience.builder().circuitBreakerListener(...)` | transiciones del breaker (`CLOSED` / `OPEN` / `HALF_OPEN`) por proveedor |
| `Resilience.builder().bulkheadObserver(...)` | `(proveedor, Bulkhead)` al crearse; `Bulkhead.availablePermits()` sirve para un gauge |

Ejemplo con Micrometer (dependencia de TU aplicación, no de la librería):

```java
NotificationService.builder()
    .subscribe(e -> registry.counter("notifications.events",
            "channel", e.channel().name(), "type", e.type().name(), "provider", e.provider()).increment())
    .build();

Resilience.builder()
    .circuitBreaker(CircuitBreaker.Config.defaults())
    .circuitBreakerListener(t -> registry.counter("notifications.breaker",
            "provider", t.name(), "to", t.to().name()).increment())
    .bulkhead(Bulkhead.Config.defaults())
    .bulkheadObserver((provider, bulkhead) -> Gauge.builder("notifications.bulkhead.available",
            bulkhead, Bulkhead::availablePermits).tag("provider", provider).register(registry))
    .build();
```
Mantené los tags acotados (canal, tipo, proveedor) y nunca pongas destinatarios en ellos: evita explosión de cardinalidad y fugas de datos personales.

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

### Verificación contra la documentación oficial

Los escenarios simulados se contrastaron con la documentación oficial de cada proveedor.

**Confirmado en la documentación**

| Proveedor | Confirmado |
|---|---|
| [SendGrid](https://www.twilio.com/docs/sendgrid/api-reference/mail-send/mail-send) | `POST https://api.sendgrid.com/v3/mail/send`, `Authorization: Bearer`; obligatorios `personalizations` (con `to`) y `from`; `content` como array de `{type, value}`; `cc`/`bcc` por personalización (máx. 1000); éxito **202**; errores como `{"errors":[{"message","field","help"}]}`; host EU `api.eu.sendgrid.com` solo para subusuarios regionales |
| [Mailgun](https://documentation.mailgun.com/docs/mailgun/api-reference/send/mailgun/messages/post-v3--domain-name--messages) | `POST https://api.mailgun.net/v3/{domain}/messages`; Basic con usuario `api`; varios destinatarios separados por comas (o `to` repetido); hasta 1000 por llamada; requiere `text`, `html`, `amp-html` o `template`; host EU distinto |
| [Twilio](https://www.twilio.com/docs/messaging/api/message-resource) | `POST /2010-04-01/Accounts/{sid}/Messages.json`; form `To`/`From`/`Body`; `Body` ≤ 1600; estado inicial `queued`; creación = **201**; errores `{status, message, code, more_info}`; 429 = límite de concurrencia, 503 = reintentar |
| [Vonage](https://developer.vonage.com/en/messaging/sms/guides/troubleshooting-sms) | `POST https://rest.nexmo.com/sms/json`; HTTP 200 aunque falle: el resultado real está en `messages[].status` (0 ok, 1 throttled, 5 error interno, 4 credenciales, 9 sin saldo, 12 mensaje largo, 15 remitente inválido…) |
| [FCM v1](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send) | `POST https://fcm.googleapis.com/v1/projects/{id}/messages:send`; scope `firebase.messaging`; cuerpo `{"message": {...}}`; `data` solo strings y con claves reservadas; `android.priority` `normal`/`high`; `android.ttl` en segundos con sufijo `s` (0 a 28 días, `0s` = ahora o nunca); `apns-expiration` como epoch absoluto; límite 4096 bytes; errores `UNREGISTERED` 404, `QUOTA_EXCEEDED` 429 (backoff mínimo de 1 minuto), `UNAVAILABLE` 503 (respetar `Retry-After`), `INVALID_ARGUMENT` 400 |
| [Slack](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/) | `POST` al webhook con `{"text": "..."}`; respuesta 200 `ok`; errores 400/403/404 con `invalid_payload`, `no_service`, `no_text`, `channel_not_found`…; ~1 mensaje/s por canal con ráfagas permitidas y HTTP 429 con `Retry-After`; truncado a 40 000 caracteres |

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

**Credenciales**
- Envueltas en `Secret`: `toString()` devuelve `Secret[****]`, por lo que no se filtran en logs, excepciones ni `toString()` de las configuraciones (los `Config` son records).
- Obtenerlas con `Secret.fromEnv("VAR")` (o de tu gestor de secretos: Vault, AWS Secrets Manager…). **Nunca** en código ni en el repositorio.
- Una API key por entorno y con permisos mínimos (p. ej. en SendGrid una key restringida a "Mail Send"); rotarlas periódicamente. El token de FCM se renueva solo (`RefreshingTokenProvider`).

**Transporte**
- `JdkHttpTransport` **solo acepta HTTPS** (HTTP únicamente hacia loopback, para tests). El mensaje de error nombra solo el host, no el path, porque el webhook de Slack lleva el token en la URL.
- Respuestas acotadas a 64 KB (un endpoint comprometido no puede agotar memoria), sin seguir redirects (no se reenvían credenciales a otro host) y con timeouts de conexión y de request.
- `HttpRequest.toString()` solo muestra método, host y *nombres* de headers; jamás valores, path/query ni body. Los errores de proveedor se construyen desde la respuesta del vendor, nunca desde el request.

**Entrada y abuso**
- El subject no admite saltos de línea (**inyección de cabeceras**: un subject `Hi\r\nBcc: atacante@x.com` se rechaza). Las direcciones se validan con una expresión que excluye espacios y CR/LF.
- Límites de tamaño: destinatarios (50), subject, bodies, payload `data` de push (4 KB, límite de FCM), TTL (28 días) y lotes (`maxBatchSize`).
- `Retry-After` acotado a 5 minutos.

**Datos personales**
- La librería no registra destinatarios ni contenido. Los eventos y métricas tampoco los incluyen. Si necesitás referirlos en tus logs, usá `Masking.email(...)`, `Masking.phone(...)`, `Masking.token(...)`. Regla: nunca loguear el body.

**Imagen Docker**
- Imágenes base fijadas por **digest** (builds reproducibles; Dependabot propone las actualizaciones), usuario sin privilegios, sin credenciales. Ejecutar con `--read-only --cap-drop ALL --security-opt no-new-privileges --network none` (la demo es offline). No hay `HEALTHCHECK` porque es un runner de demo y no un servicio.

**CI** (`.github/workflows/ci.yml`, `.github/dependabot.yml`)
- Build y tests con Java 25, OWASP Dependency-Check (falla con CVSS ≥ 7; requiere el secret `NVD_API_KEY`) y Trivy sobre la imagen (HIGH/CRITICAL). Dependabot semanal para Maven, Docker y GitHub Actions. *Los workflows están escritos pero no se ejecutaron localmente: se validan en el primer push.*

## API Reference (principal)

| Clase | Responsabilidad |
|---|---|
| `NotificationService` | `send`, `trySend`, `sendAsync` (cada uno con variante `(n, idempotencyKey)`), `sendAll`, `subscribe`, `close`. Builder: `register`, `subscribe`, `executor`, `deduplication`, `maxBatchSize` |
| `NotificationResult` | `id`, `channel`, `status` (`SENT/FAILED/REJECTED/DUPLICATE`), `provider`, `providerMessageId`, `attempts`, `timestamp`, `error` |
| `Notification` / `ChannelType` | abstracción común y tipo de canal extensible |
| `Channels`, `StandardChannel.Builder` | `provider()`, `fallbackTo()`, `resilience()`, `retryPolicy()`, `build()` |
| `NotificationProvider<N>` | SPI por vendor: `name()`, `send(N)` |
| `Resilience` | `recommended()`, `builder()`: `retryPolicy`, `maxElapsed`, `circuitBreaker`, `bulkhead`, `rateLimiter`, `rateLimiterFactory`, listeners |
| `RetryPolicy` | `none()`, `fixedDelay()`, `exponentialBackoff()`, `withJitter()` |
| `DeduplicationStore` | `tryClaim`, `release` (`InMemoryDeduplicationStore` incluido) |
| `TokenProvider` / `RefreshingTokenProvider` | token de acceso con refresco automático |
| `DeliveryContext` | id de notificación y clave de idempotencia visibles para los proveedores |
| `Secret`, `MessageTemplate`, `Masking` | credenciales seguras, plantillas `{{var}}`, enmascarado de datos personales |

## Tests

`mvn test` ejecuta 148 tests (JUnit 5). Los principales, por área:

**Núcleo y errores**
- `NotificationServiceTest`: enrutamiento al canal correcto, `ValidationException` sin llegar al proveedor, `DeliveryException` con proveedor e intentos, canal no registrado, `trySend` sin excepciones, failover, eventos a suscriptores, suscriptor defectuoso aislado, `sendAsync` y `sendAll` (orden y fallos individuales).
- `ExtensibilityTest`: un canal "whatsapp" definido solo en el código de test funciona con el servicio sin modificarlo (principio abierto/cerrado).
- `ChannelTypeTest`: normalización y validación de `ChannelType`.
- `TemplateAndSecretTest`: templates `{{var}}` (variables faltantes) y `Secret` (nunca se revela por accidente).

**Validación**
- `ValidatorsTest`: reglas de Email, SMS (E.164), Push y Slack; todos los errores se reportan a la vez.
- `SecurityHardeningTest`: inyección de cabeceras, límites de tamaño, lotes demasiado grandes, rechazo de HTTP no seguro, respuestas truncadas, `Masking` y `Retry-After` (segundos y fecha HTTP).

**Proveedores (payload, respuesta y errores simulados)**
- `EmailProvidersTest` y `EmailRecipientsTest`: SendGrid y Mailgun; CC, BCC y varios destinatarios.
- `SmsProvidersTest`: Twilio y Vonage (incluido el error dentro de un HTTP 200).
- `PushAndSlackProvidersTest`: FCM y el webhook de Slack.
- `FcmTokensAndOptionsTest`: refresco automático del token, 401, 429 y 503 de FCM, prioridad y TTL.

**Resiliencia**
- `RetryPolicyTest` y `RetryingAndFallbackProviderTest`: backoff, reintentos solo de fallos transitorios y failover entre proveedores.
- `CircuitBreakerTest`: transiciones cerrado, abierto y semiabierto.
- `ProviderDecoratorsTest`: circuit breaker, bulkhead (con concurrencia real) y rate limiter como decoradores.
- `ResilienceTest`: `Retry-After`, deadline global y la composición completa (breaker abierto, sin reintentos, failover inmediato).
- `HttpResilienceTest`: `JdkHttpTransport` contra un servidor local (respuesta, timeout y puerto cerrado) y clasificación de errores por proveedor.

**Idempotencia**
- `IdempotencyTest`: deduplicación (expiración, liberación al fallar), id de entrega estable entre reintentos y etiquetado en SendGrid y Mailgun.

**Cómo están hechos.** No se usa Mockito: los dobles están escritos a mano (`ScriptedTransport` reproduce respuestas HTTP y fallos, `FakeProvider` simula proveedores que fallan, `MutableClock` controla el tiempo). El reloj y el *sleeper* son inyectables, así que los tests de reintentos, breaker y token no esperan tiempo real. Ningún test sale a internet: lo único que usa sockets reales son `HttpResilienceTest` y una prueba de `SecurityHardeningTest` (respuestas truncadas), contra un servidor en loopback. El `Dockerfile` ejecuta estos tests al construir la imagen, de modo que no se genera si alguno falla.
