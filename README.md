# Notification Gateway Service

A standalone Java service that consumes from the AB Bank notification Kafka topics produced by [cdc-stream-processor](https://github.com/darefamuy/cdc-stream-processor) and delivers them to customers via email and SMS through pluggable, multi-provider channel adapters.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Notification Topics](#notification-topics)
- [Channel Providers](#channel-providers)
- [Getting Started](#getting-started)
- [Configuration Reference](#configuration-reference)
- [Routing Rules](#routing-rules)
- [Customer Resolver](#customer-resolver)
- [Retry Policy](#retry-policy)
- [Health Check](#health-check)
- [Running Tests](#running-tests)
- [Docker](#docker)
- [Adding a New Provider](#adding-a-new-provider)

---

## Overview

`notification-gateway-service` sits downstream of the Kafka Streams application. It is a plain Kafka consumer — no Streams DSL, no state stores — that reads `NotificationEvent` JSON messages from five notification topics, resolves each event to a customer contact profile, and dispatches the notification to the configured email and/or SMS providers.

Key characteristics:

- **Provider-agnostic.** Four email and three SMS providers are implemented. Any combination can be enabled simultaneously; the first to succeed wins, with automatic fallback to the next.
- **Severity-aware routing.** `CRITICAL` and `HIGH` severity events are always sent on both email and SMS regardless of the `channel` field on the event.
- **At-least-once delivery.** Kafka offsets are committed after dispatch, so no events are lost on restart at the cost of occasional duplicates. All supported providers handle duplicate sends safely.
- **Zero AWS SDK dependency.** SES is supported via raw SigV4 request signing using the JDK's `javax.crypto` — no fat SDK on the classpath.
- **No Spring, no framework.** Pure Java 17, OkHttp, Jackson, and Typesafe Config. The fat JAR is under 20 MB.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        cdc-stream-processor                           │
│  (Kafka Streams — fraud, high-value, balance, dormancy, spend)  │
└───────────────────────────┬─────────────────────────────────────┘
                            │  5 Kafka notification topics
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                  NotificationConsumer (poll loop)               │
│   • Deserialises NotificationEvent JSON                         │
│   • Manual offset commit (at-least-once)                        │
│   • Per-record error isolation                                  │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CustomerResolver                             │
│   mock (dev/test)  ──or──  http (production)                    │
│   accountId  ──►  CustomerProfile { email, phone }             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                  NotificationDispatcher                         │
│                                                                 │
│  Routing rules:                                                 │
│  • channel = EMAIL  →  email only                               │
│  • channel = SMS    →  SMS only                                 │
│  • channel = BOTH   →  email + SMS                              │
│  • severity = HIGH|CRITICAL  →  force BOTH regardless           │
└───────┬───────────────────────────────────────┬─────────────────┘
        │                                       │
        ▼                                       ▼
┌───────────────────┐                 ┌───────────────────┐
│  Email adapters   │                 │  SMS adapters     │
│  (priority order) │                 │  (priority order) │
│                   │                 │                   │
│  1. SendGrid      │                 │  1. Africa's      │
│  2. SES           │                 │     Talking       │
│  3. MailerSend    │                 │  2. Twilio        │
│  4. Postmark      │                 │  3. Termii        │
│                   │                 │                   │
│  RetryExecutor    │                 │  RetryExecutor    │
│  (exp. backoff)   │                 │  (exp. backoff)   │
└───────────────────┘                 └───────────────────┘
```

---

## Project Structure

```
notification-gateway-service/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── Dockerfile
├── docker-compose-service.yml          # Paste into the main docker-compose.yml
├── src/
│   ├── main/
│   │   ├── java/com/abbank/notification/
│   │   │   ├── NotificationGatewayApp.java       # Main entry point
│   │   │   ├── channel/
│   │   │   │   ├── ChannelAdapter.java            # Provider interface
│   │   │   │   ├── ChannelAdapterFactory.java     # Wires adapters from config
│   │   │   │   ├── SendGridEmailAdapter.java
│   │   │   │   ├── SesEmailAdapter.java
│   │   │   │   ├── MailerSendEmailAdapter.java
│   │   │   │   ├── PostmarkEmailAdapter.java
│   │   │   │   ├── AfricasTalkingSmsAdapter.java
│   │   │   │   ├── TwilioSmsAdapter.java
│   │   │   │   └── TermiiSmsAdapter.java
│   │   │   ├── config/
│   │   │   │   └── GatewayConfig.java             # Typed config wrapper
│   │   │   ├── consumer/
│   │   │   │   ├── CustomerResolver.java          # Interface
│   │   │   │   ├── MockCustomerResolver.java      # Dev / test
│   │   │   │   ├── HttpCustomerResolver.java      # Production
│   │   │   │   ├── NotificationConsumer.java      # Kafka poll loop
│   │   │   │   └── NotificationDispatcher.java    # Routing logic
│   │   │   ├── health/
│   │   │   │   └── HealthServer.java              # HTTP /health endpoints
│   │   │   ├── model/
│   │   │   │   ├── NotificationEvent.java         # Consumed from Kafka
│   │   │   │   ├── CustomerProfile.java           # Resolved contact details
│   │   │   │   └── DeliveryResult.java            # Dispatch outcome
│   │   │   └── retry/
│   │   │       └── RetryExecutor.java             # Exp. backoff with jitter
│   │   └── resources/
│   │       ├── application.conf                   # All configuration
│   │       └── logback.xml
│   └── test/
│       └── java/com/abbank/notification/
│           ├── channel/
│           │   └── SendGridEmailAdapterTest.java
│           └── consumer/
│               ├── NotificationDispatcherTest.java
│               └── RetryExecutorTest.java
```

---

## Notification Topics

The gateway subscribes to all five topics produced by `cdc-stream-processor`:

| Topic | Notification Type | Default Channel | Default Severity |
|---|---|---|---|
| `abbank.notifications.fraud-alerts` | `FRAUD_ALERT` | BOTH | CRITICAL |
| `abbank.notifications.high-value-alerts` | `HIGH_VALUE_ALERT` | BOTH | HIGH |
| `abbank.notifications.balance-updates` | `BALANCE_UPDATE` | EMAIL | MEDIUM |
| `abbank.notifications.dormancy-alerts` | `DORMANCY_ALERT` | EMAIL | LOW |
| `abbank.notifications.daily-spend` | `DAILY_SPEND_SUMMARY` | EMAIL | LOW |

Each message on these topics is a JSON-serialised `NotificationEvent` with the following structure:

```json
{
  "notificationId":   "a3f1c9e2-...",
  "notificationType": "HIGH_VALUE_ALERT",
  "severity":         "HIGH",
  "channel":          "BOTH",
  "accountId":        100042,
  "customerId":       1042,
  "accountNumber":    "0123456789",
  "subject":          "High Value Transaction Alert",
  "body":             "A debit of ₦750,000 was processed on account 0123456789...",
  "eventTime":        "2025-11-14T10:23:45Z",
  "generatedAt":      "2025-11-14T10:23:46Z",
  "metadata": {
    "amount":         750000.00,
    "threshold":      500000.00
  }
}
```

---

## Channel Providers

### Email

Four providers are supported. Enable one or more in `application.conf`; the first enabled provider is the primary and subsequent enabled providers are automatic fallbacks.

| Provider | Env var(s) required | Notes |
|---|---|---|
| **SendGrid** | `SENDGRID_API_KEY` | Default primary. Simple v3 API, per-message event tracking. |
| **AWS SES** | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | Best cost at scale. No AWS SDK — uses raw SigV4 signing. Sender address must be verified in the SES console. |
| **MailerSend** | `MAILERSEND_API_KEY` | 12,000 free emails/month. Good free-tier option for lower volumes. |
| **Postmark** | `POSTMARK_SERVER_TOKEN` | Recommended for production. Industry-leading inbox placement rates for transactional email — well-suited for banking alerts. Sender Signature must be verified. |

### SMS

Three providers are supported, using the same priority-order fallback mechanism.

| Provider | Env var(s) required | Notes |
|---|---|---|
| **Africa's Talking** | `AFRICAS_TALKING_API_KEY`, `AFRICAS_TALKING_USERNAME` | Default primary. Direct carrier connections to MTN, Airtel, Glo, and 9mobile Nigeria. NGN billing, registered sender ID, NCC DND management. Set `AFRICAS_TALKING_SANDBOX=true` for testing. |
| **Twilio** | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | Global fallback. Wider international reach; higher per-message cost for Nigerian traffic. |
| **Termii** | `TERMII_API_KEY` | Lagos-headquartered. NCC-approved DND-bypass route for transactional banking messages. Supports a WhatsApp delivery channel (`channel = "WhatsApp"` in config). NGN billing. |

---

## Getting Started

### Prerequisites

- Java 17+
- Gradle 8.7+ (or use the Gradle wrapper once added)
- A running Kafka broker (the `oracle-xstream-cdc-connector-e2e-demo` stack is the intended environment)
- Credentials for at least one email provider and one SMS provider

### Build

```bash
./gradlew build
# Fat JAR produced at: build/libs/notification-gateway-service-1.0.0.jar
```

### Run locally

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export SENDGRID_API_KEY=SG.xxxxx
export EMAIL_FROM_ADDRESS=noreply@abbank.com
export AFRICAS_TALKING_API_KEY=atsk_xxxxx
export AFRICAS_TALKING_USERNAME=sandbox
export AFRICAS_TALKING_SANDBOX=true

java -jar build/libs/notification-gateway-service-1.0.0.jar
```

The service will log its startup sequence and begin consuming. The health endpoint is available at `http://localhost:8081/health`.

### Run with the full CDC stack

Add the `notification-gateway-service` service block to the main `docker-compose.yml` (see `docker-compose-service.yml`), then start it alongside the rest of the stack:

```bash
docker-compose up -d notification-gateway-service
docker-compose logs -f notification-gateway-service
```

---

## Configuration Reference

All configuration lives in `src/main/resources/application.conf`. Every value can be overridden by an environment variable (Typesafe Config substitution syntax: `${?VAR_NAME}`).

**Secrets must never be committed to source control.** Always inject API keys and auth tokens via environment variables or a secrets manager.

### Kafka

| Environment Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Comma-separated list of Kafka brokers |
| `KAFKA_CONSUMER_GROUP_ID` | `notification-gateway-service` | Consumer group ID |

### Email providers

| Environment Variable | Provider | Description |
|---|---|---|
| `EMAIL_SENDGRID_ENABLED` | SendGrid | `true` to enable (default: `true`) |
| `SENDGRID_API_KEY` | SendGrid | API key with Mail Send permission |
| `EMAIL_SES_ENABLED` | SES | `true` to enable (default: `false`) |
| `AWS_ACCESS_KEY_ID` | SES | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | SES | AWS secret key |
| `AWS_REGION` | SES | AWS region (e.g. `eu-west-1`) |
| `EMAIL_MAILERSEND_ENABLED` | MailerSend | `true` to enable (default: `false`) |
| `MAILERSEND_API_KEY` | MailerSend | API token from MailerSend dashboard |
| `EMAIL_POSTMARK_ENABLED` | Postmark | `true` to enable (default: `false`) |
| `POSTMARK_SERVER_TOKEN` | Postmark | Server API token |
| `POSTMARK_MESSAGE_STREAM` | Postmark | Message stream name (default: `outbound`) |
| `EMAIL_FROM_ADDRESS` | All | Sender address (default: `noreply@abbank.com`) |
| `EMAIL_REPLY_TO` | SendGrid | Reply-to address (default: `support@abbank.com`) |

### SMS providers

| Environment Variable | Provider | Description |
|---|---|---|
| `SMS_AT_ENABLED` | Africa's Talking | `true` to enable (default: `true`) |
| `AFRICAS_TALKING_API_KEY` | Africa's Talking | API key from AT dashboard |
| `AFRICAS_TALKING_USERNAME` | Africa's Talking | AT application username |
| `AFRICAS_TALKING_SANDBOX` | Africa's Talking | `true` to use sandbox environment |
| `SMS_SENDER_ID` | AT, Termii | Registered sender ID (default: `ABBANK`) |
| `SMS_TWILIO_ENABLED` | Twilio | `true` to enable (default: `false`) |
| `TWILIO_ACCOUNT_SID` | Twilio | Account SID |
| `TWILIO_AUTH_TOKEN` | Twilio | Auth token |
| `TWILIO_FROM_NUMBER` | Twilio | From number in E.164 format |
| `SMS_TERMII_ENABLED` | Termii | `true` to enable (default: `false`) |
| `TERMII_API_KEY` | Termii | API key from Termii dashboard |

### Other

| Environment Variable | Default | Description |
|---|---|---|
| `CUSTOMER_RESOLVER_TYPE` | `mock` | `mock` for dev/test, `http` for production |
| `CUSTOMER_SERVICE_URL` | `http://customer-service:8090` | Base URL for the HTTP customer resolver |
| `RETRY_MAX_ATTEMPTS` | `3` | Max dispatch attempts per provider per event |
| `RETRY_ON_EXHAUSTED` | `log` | Action after all retries fail: `log` or `kafka` (DLQ) |
| `HEALTH_PORT` | `8081` | Port for the health check HTTP server |

---

## Routing Rules

Routing is determined by two inputs: the `channel` field on the `NotificationEvent` and the event's `severity`.

```
channel = EMAIL   →  email only
channel = SMS     →  SMS only
channel = BOTH    →  email + SMS

severity = HIGH or CRITICAL  →  email + SMS  (overrides channel field)
```

The `force-both-on-severity` list is configurable in `application.conf`:

```hocon
routing {
  force-both-on-severity = ["CRITICAL", "HIGH"]
}
```

Within each channel, adapters are tried in the order they appear in the `channels.email.providers` or `channels.sms.providers` list. The first adapter to return a successful `DeliveryResult` wins; subsequent adapters for that channel are not called. If an adapter returns `SKIPPED` (e.g. the customer has no phone number on record), the fallback chain stops immediately — it is a permanent condition, not a transient error.

---

## Customer Resolver

The gateway must resolve an `accountId` to a `CustomerProfile` (email address and phone number in E.164 format) before it can dispatch. Two resolver implementations are provided.

**MockCustomerResolver** — used when `CUSTOMER_RESOLVER_TYPE=mock`. Generates deterministic contact details from the `accountId`. Five named fixture profiles are included for repeatable integration testing; all other IDs produce a generated Nigerian profile. Not for production use.

**HttpCustomerResolver** — used when `CUSTOMER_RESOLVER_TYPE=http`. Calls the customer profile service:

```
GET {CUSTOMER_SERVICE_URL}/customers/by-account/{accountId}

Response:
{
  "customerId":  1001,
  "accountId":   100001,
  "firstName":   "Adaeze",
  "lastName":    "Okafor",
  "email":       "adaeze.okafor@email.com",
  "phoneNumber": "+2348031001001"
}
```

A 404 response is treated as a permanent skip (event is logged and the Kafka offset is committed). Any non-2xx response is treated as a transient error and the event is skipped with an error log.

To add a new resolver type (e.g. database lookup), implement the `CustomerResolver` interface and register it in `NotificationGatewayApp.buildCustomerResolver()`.

---

## Retry Policy

Each individual adapter call is wrapped by `RetryExecutor`, which applies exponential back-off with jitter on `FAILURE` results:

```
delay(attempt) = min(initialDelay × factor^(attempt-1) + jitter, maxDelay)
jitter         = random(0, initialDelay)
```

Default values:

| Parameter | Default |
|---|---|
| `retry.max-attempts` | `3` |
| `retry.initial-delay-ms` | `500` |
| `retry.backoff-factor` | `2.0` |
| `retry.max-delay-ms` | `10000` (10 s) |

`SKIPPED` results (no email/phone on record) are never retried — they are permanent. Once all attempts are exhausted for a given adapter, the dispatcher moves on to the next adapter in the fallback chain.

When all adapters for all channels are exhausted, the behaviour is controlled by `retry.on-exhausted`:

- `log` (default) — the failure is logged at `ERROR` level and the Kafka offset is committed.
- `kafka` — the event is published to the dead-letter topic (`abbank.notifications.dlq`) for manual inspection and replay.

---

## Health Check

A lightweight HTTP server runs on port `8081` (configurable via `HEALTH_PORT`).

| Endpoint | Success | Failure | Purpose |
|---|---|---|---|
| `GET /health` | `200 {"status":"UP"}` | `503 {"status":"DOWN"}` | General health (use for Docker `HEALTHCHECK`) |
| `GET /health/live` | `200 {"status":"ALIVE"}` | — | Kubernetes liveness probe |
| `GET /health/ready` | `200 {"status":"READY"}` | `503 {"status":"NOT_READY"}` | Kubernetes readiness probe |

The health server starts before the Kafka consumer and returns `503` during the startup and shutdown windows, ensuring load balancers and orchestrators do not route traffic to an instance that is not yet consuming.

---

## Running Tests

```bash
./gradlew test
```

Test coverage report is generated at `build/reports/jacoco/test/html/index.html`.

The test suite covers:

- `SendGridEmailAdapterTest` — adapter configuration validation and skip-on-no-email behaviour.
- `NotificationDispatcherTest` — all routing combinations (EMAIL, SMS, BOTH, force-BOTH on HIGH/CRITICAL), fallback chain behaviour (first-success-wins, stop-at-success), and no-adapter handling.
- `RetryExecutorTest` — immediate success, success on second attempt, full exhaustion, no-retry on SKIPPED, and exception handling.

Tests use Mockito for adapter mocking and OkHttp `MockWebServer` for HTTP-level adapter tests. No Kafka broker is required to run the test suite.

---

## Docker

### Build the image

```bash
docker build -t notification-gateway-service:latest .
```

### Run standalone

```bash
docker run --rm \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e SENDGRID_API_KEY=SG.xxxxx \
  -e EMAIL_FROM_ADDRESS=noreply@abbank.com \
  -e AFRICAS_TALKING_API_KEY=atsk_xxxxx \
  -e AFRICAS_TALKING_USERNAME=sandbox \
  -e AFRICAS_TALKING_SANDBOX=true \
  -p 8081:8081 \
  notification-gateway-service:latest
```

### Add to the CDC stack

Copy the service block from `docker-compose-service.yml` into the main `oracle-xstream-cdc-connector-e2e-demo/docker-compose.yml` under the `services:` key. The service depends on both `broker` and `cdc-stream-processor` being healthy before it starts consuming.

---

## Adding a New Provider

1. Create a new class in `com.abbank.notification.channel` implementing `ChannelAdapter`:

```java
public class MyNewEmailAdapter implements ChannelAdapter {

    @Override public String providerName() { return "mynewprovider"; }
    @Override public String channelType()  { return "EMAIL"; }

    @Override
    public boolean isConfigured() { /* check credentials are present */ }

    @Override
    public DeliveryResult send(NotificationEvent event, CustomerProfile profile) {
        // Call provider API, return DeliveryResult.builder(...).success(...) or .failure(...)
    }

    @Override public void close() { /* shut down HTTP client */ }
}
```

2. Add a new entry to the `channels.email.providers` (or `channels.sms.providers`) list in `application.conf` with its credentials and an `enabled` flag.

3. Register the new provider name in the `switch` statement in `ChannelAdapterFactory.buildEmailAdapter()` (or `buildSmsAdapter()`).

4. Add unit tests to the `src/test` tree.

No changes to `NotificationDispatcher`, `NotificationConsumer`, or `RetryExecutor` are required.
