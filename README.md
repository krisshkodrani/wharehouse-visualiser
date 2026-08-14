# Warehouse AI Control

An OpenUI5 and Babylon.js warehouse dashboard backed by Spring Boot, PostgreSQL, MQTT, and a simulated autonomous forklift. The vertical demo accepts inbound pallets, creates an AI-assisted placement plan, validates and reserves storage slots, computes an A* route, dispatches a VDA 5050 v3-profile order, and streams AGV movement back to the browser.

## Run locally with Docker

Requirements: Docker Desktop with Compose v2.

```powershell
Copy-Item .env.example .env
docker compose up --build --wait
```

Open <http://localhost:8080>. The default `AI_PROVIDER=mock` is deterministic and requires no external service.

To use OpenRouter, edit `.env`:

```dotenv
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=your-key
OPENROUTER_MODEL=openai/gpt-4o-mini
OPENROUTER_PROVIDER=groq
```

OpenRouter failures are shown to the operator and do not create jobs or change inventory. The key is used only by the backend.

Reset the demo by removing its local volumes:

```powershell
docker compose down -v
```

This deletes the demo database and broker data.

## Development

The frontend remains a standard UI5 project:

```powershell
npm install
npm run typecheck
npm run build
npm test
```

The repository includes a self-bootstrapping Maven Wrapper, so no global Maven installation is required:

```powershell
.\mvnw.cmd test
```

Java 21 is required for host-side Java development. PostgreSQL and Mosquitto can be started independently with:

```powershell
docker compose up -d --wait postgres mosquitto
```

## Service boundaries

- `backend/`: authoritative inventory, reservations, requests/jobs, A* routing, OpenRouter/mock planning, REST, STOMP/WebSocket, MQTT outbox.
- `simulator/`: independent AGV process; consumes orders and publishes VDA state, visualization, and connection messages over MQTT.
- `vda5050-contracts/`: shared VDA 5050 3.0.0 demo-profile records and JSON-schema validation.
- `webapp/`: OpenUI5 business UI and Babylon visualization. Live mode is backend-driven; Sandbox mode is local and disposable.

REST starts at `/api/v1`, WebSocket/STOMP at `/ws`, and MQTT topics use `vda5050/v3/demo/FL-01/{order,state,visualization,connection}`.

## Deliberate demo limits

This implements a schema-validated VDA 5050 v3 subset for one Linz warehouse and one AGV. Authentication, TLS, multi-AGV traffic coordination, charging, obstacle avoidance, and complete VDA topic coverage are outside this demo.
