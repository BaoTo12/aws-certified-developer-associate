# Self-Directed AWS Scenarios: Solutions Architect + Developer

Three real scenarios, each spanning **Design → Build → Deploy → Maintain**, so each one exercises both the Solutions Architect mindset (trade-offs, architecture decisions) and the Developer mindset (code, IAM, testing, debugging). You bring the code (self-written or AI-generated) — this doc is the spec, the "why" checkpoints, and the failure-injection prompts to run the same loop as the journey you shared:

```
Design → Build → Fail → Screenshot → Ask "Why?" → Research (AWS Docs) → Fix → Test again
```

**Cost note (important since you don't have free-tier credits left):** AWS's *Always Free* tier (no expiration, resets monthly) covers Lambda, DynamoDB, SNS, SQS, EventBridge, Step Functions (4,000 transitions/month), Cognito (50k MAU), CloudWatch (basic metrics/alarms/logs), and CloudFront (1TB out / 10M requests). Everything below is built primarily on those. A few pieces (API Gateway, S3 beyond a few GB, Route 53 hosted zones, WAF) aren't in that always-free list — at hobby scale they cost cents to a few dollars, not the free-tier zero, so each scenario flags exactly which pieces those are so you're not surprised on the bill.

---

## Scenario 1 — Read-It-Later API (foundational, Developer-heavy)

A serverless "save a link, tag it, get a daily unread digest" service. This is the one to build first — it's the smallest surface area but still touches auth, async processing, and a scheduled job.

**Services:** API Gateway (HTTP API) · Lambda (Java) · DynamoDB · Cognito User Pool · SQS · EventBridge Scheduler · CloudWatch
**Cost note:** Lambda/DynamoDB/SQS/EventBridge/CloudWatch/Cognito → Always Free. API Gateway HTTP API is pay-per-request outside the legacy 12-month window, but at a few thousand calls/month it's fractions of a cent — use HTTP API, not REST API (it's ~3.5x cheaper per request).

### Phase 1 — Design (Solutions Architect)
Work these out on paper/diagram before touching the console:
- Access patterns first: "all bookmarks for user X", "unread bookmarks for user X", "bookmarks by tag" — design your DynamoDB table/GSIs from these, not the other way around.
- Why decouple tag processing into SQS instead of doing it inline in the `createBookmark` handler? What does that buy you, and what does it cost you (latency to "tag applied", complexity)?
- Cognito User Pool + JWT authorizer vs a simple API key — which one actually models "many users, each with their own data"?
- What happens if the client retries a `POST /bookmarks` call after a timeout — do you get a duplicate? How would you prevent that?

**Deliverable:** a one-page architecture diagram + a short doc answering the four questions above in your own words.

### Phase 2 — Build (Developer)
- Java 21 Lambda handlers (AWS SDK v2) for create/list/markRead/delete, backed by a single-table DynamoDB design.
- Write the IAM policy for each Lambda by hand, scoped to only the actions/resources that function actually calls — don't use the console's auto-generated broad policy.
- Unit tests (JUnit 5 + Mockito) for handler logic; integration tests against DynamoDB Local or Testcontainers.
- **Ask-why checkpoints to pause on:** Java Lambda cold start — why is it worse than Node/Python, and does it matter here? DynamoDB conditional writes for your idempotency fix from Phase 1. SQS visibility timeout vs your Lambda's timeout — what happens if they're mismatched?

### Phase 3 — Deploy
- IaC: AWS CDK (Java) fits your stack directly, or plain CloudFormation/SAM if you want the raw YAML practice.
- CI/CD: GitHub Actions (free minutes) building → testing → deploying via an OIDC role assumed into AWS — no CodePipeline/CodeBuild charges.
- Verify: smoke-test each endpoint with curl/Postman, then check CloudWatch Logs Insights for cold-start duration and errors.

### Phase 4 — Maintain
- Alarms: Lambda error rate, DynamoDB throttled requests, SQS DLQ message count > 0.
- Failure-injection prompt for your AI tool: *"Make my Lambda intermittently throw a DynamoDB ProvisionedThroughputExceededException, then have me diagnose it from CloudWatch Logs and metrics alone before telling me the fix."*
- Review: confirm nothing is sitting outside Always Free (check API Gateway request count, S3 storage class if you added exports), rotate the Cognito app client secret.

---

## Scenario 2 — Cross-System Inventory Sync (event-driven, intermediate — SA-heavy)

Two independent services ("Storefront" and "Warehouse") that must stay in sync when stock changes — the classic "two systems that must agree" problem, and a good stand-in for real microservices work.

**Services:** SNS (fan-out topic) · 2× SQS (one per subscriber) + DLQ per queue · Lambda consumers (Java) · DynamoDB (per-service state) · Step Functions (reserve-stock saga with compensation) · EventBridge · CloudWatch
**Cost note:** SNS/SQS/Step Functions (Standard, under 4k transitions/month)/Lambda/DynamoDB/EventBridge are all Always Free at this scale — this scenario should cost you $0 if you tear down after.

### Phase 1 — Design (Solutions Architect)
- Why SNS fan-out instead of each producer calling each consumer's endpoint directly? What does it decouple, and what does it *not* solve?
- Standard vs FIFO for the stock-events topic — does ordering actually matter for this use case? What breaks if two "sold" events for the same SKU process out of order?
- Message design: ship the full item state in the event, or just "SKU X changed, go re-fetch"? What are you trading off (payload size vs staleness vs coupling)?
- Idempotency: both systems can see the same event twice (at-least-once delivery) — how do you guarantee you don't double-decrement stock?
- Saga design for "reserve → charge → confirm": what's the compensating action if step 3 fails after step 1 already succeeded?

### Phase 2 — Build (Developer)
- Java Lambda consumers using SQS batch processing — implement **partial batch failure reporting** (`batchItemFailures`), a detail most people skip and then wonder why one bad message blocks the whole batch.
- Step Functions state machine — hand-write the ASL JSON at least once before letting a CDK builder generate it for you.
- **Ask-why checkpoints:** SQS batch failure response format. Step Functions retry/backoff (`IntervalSeconds`, `BackoffRate`, `MaxAttempts`) — why do the defaults exist? SNS message filtering by attribute vs separate topics per event type.

### Phase 3 — Deploy
- IaC: CDK, two separate stacks (one per "system") deployed independently — simulates two real teams owning their own service.
- CI/CD: one GitHub Actions workflow per stack, triggered by path filters, so a change to Storefront doesn't redeploy Warehouse.

### Phase 4 — Maintain
- Dashboard: DLQ depth per queue, Step Functions failed-execution count, SNS delivery failures.
- Failure-injection prompt: *"Make one of my two SQS consumers throw on every 5th message so failures land in its DLQ. Walk me through diagnosing it from the DLQ message body plus CloudWatch Logs alone, then have me write the redrive process."*
- Review: watch Step Functions Standard vs Express pricing if you scale transition volume up during testing — Express isn't in the always-free bucket the same way.

---

## Scenario 3 — Public Content Platform with Access Control (SA-heavy, global delivery + security)

A "premium content" site: a public landing page served globally from cache, with some content gated behind login. Tests CDN/edge thinking and where authorization actually belongs in a request path.

**Services:** S3 (static assets) · CloudFront (Origin Access Control) · CloudFront Functions (edge auth check) · Route 53 + ACM (optional custom domain) · API Gateway · Lambda (Java, publish/read API) · DynamoDB · Cognito · WAF (optional) · CloudWatch
**Cost note — read this one carefully:** CloudFront (1TB/10M requests) and everything from Scenario 1 stays Always Free. The two pieces here that bill continuously regardless of usage are a **Route 53 hosted zone** (~$0.50/month) and **WAF** (~$5-10/month even at light usage). Spin those two up to learn them, then tear them down — don't leave them running between sessions. Skip the custom domain and WAF entirely and this scenario is still $0.

### Phase 1 — Design (Solutions Architect)
- Why put a CDN in front of even the "dynamic" parts of the API? What's actually cacheable here vs what must always hit origin?
- CloudFront Functions vs Lambda@Edge for the auth check at the edge — compare cost, latency, and what each is actually capable of.
- Where does authorization get enforced — CDN edge, API Gateway authorizer, or application logic? Is there a reason to enforce it at more than one layer?
- Is the custom domain + WAF worth the always-on cost for a learning project, or do you deliberately treat those two as "spin up, test, tear down" rather than permanent?

### Phase 2 — Build (Developer)
- Java Lambda publish/read APIs; DynamoDB content table with a GSI on "published" status.
- CloudFront Function (JavaScript, edge-native) that checks a signed cookie/header before allowing a request through to gated paths.
- Test specifically for the easy real-world mistake: verify your cache configuration doesn't let a gated response get cached and then served to a logged-out user.
- **Ask-why checkpoints:** CloudFront cache-key configuration (what should and shouldn't be part of the key). Origin Access Control vs the older Origin Access Identity. Scoping the S3 bucket policy to only your CloudFront distribution.

### Phase 3 — Deploy
- IaC: single CDK stack — S3, CloudFront, API Gateway, Lambda together.
- CI/CD: GitHub Actions deploys static assets to S3, invalidates the CloudFront cache, then deploys the Lambda.

### Phase 4 — Maintain
- Metrics: CloudFront 4xx/5xx rate, cache hit ratio, API latency.
- Failure-injection prompt: *"Break my CloudFront cache behavior so gated content gets served to logged-out users from cache. Have me catch it with a curl test and CloudFront logs, then fix the cache policy myself."*
- Review: tear down WAF and the Route 53 hosted zone when you're not actively demoing this one — they're the two things here that bill whether you use them or not.

---

## Suggested order

Scenario 1 → 2 → 3 — each adds one more real-world dimension (async decoupling, then multi-system consistency, then edge/security) on top of the last, without re-teaching what you already covered.

When you pick one to start, I can generate a detailed DynamoDB access-pattern table, an IAM policy skeleton, or a ready-to-paste prompt for whatever AI tool you're using to generate the starter code — just say which scenario and which phase.
