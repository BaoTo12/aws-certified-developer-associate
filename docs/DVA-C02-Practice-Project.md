# CloudTrack — Serverless Order & Notification Platform
### A DVA-C02 practice project built entirely on AWS "Always Free" services (+ optional costly-API practice modules, run once and torn down)

---

## 1. Project Overview

CloudTrack is a small serverless order-management backend. A client (you, via Postman) creates orders, the system checks and reserves inventory, sends a confirmation or failure notification by email, and produces a daily inventory report. There are no real users or production intent — it exists purely so you touch as many DVA-C02-relevant AWS APIs as possible, hands-on, in Java, at $0 (or a few cents at most).

Two kinds of API practice happen here, and the document keeps them clearly separated:

- **Core (live/running)** — services that are genuinely Always Free (or free at this project's tiny volume) and stay deployed so the whole system actually works end-to-end.
- **Practice-only (code it, run once, tear down)** — services that cost real money if left running. You still write the SDK calls and learn the request/response shapes for the exam, but you either use a free substitute for the *live* app, or you provision the resource just long enough to exercise the API and then delete it in the same session.

---

## 2. Architecture (Step Functions orchestration)

```
Client (Postman, IAM SigV4)
   │
   ▼
Lambda Function URL ──► CreateOrder ──► DynamoDB: Orders.PutItem (idempotent, condition:
   │                                     attribute_not_exists(orderId))
   │                                    KMS: Encrypt customer email (AWS-managed key, free)
   │                                    SQS: OrderQueue.SendMessage
   │
   ├──► GetOrder        (DynamoDB GetItem + KMS Decrypt)
   ├──► ListOrders       (DynamoDB Query on GSI, paginated)
   └──► GetOrderStatus   (DynamoDB GetItem → Step Functions DescribeExecution)

SQS: OrderQueue (+ DLQ) ──► Lambda: ProcessOrder (event source mapping)
                                   │
                                   ▼
                       Step Functions: OrderFulfillment (Standard workflow)
                          ├─ CheckInventory     (Lambda → DynamoDB BatchGetItem)
                          ├─ ReserveStock       (Lambda → DynamoDB conditional UpdateItem, per item)
                          ├─ Choice: all reserved?
                          │     ├─ No  → RollbackReservations (Lambda → DynamoDB UpdateItem, undo partial reserves)
                          │     └─ (either branch) → PublishOutcome (Lambda → SNS Publish, message attrs: status)
                          └─ UpdateOrderStatus  (Lambda → DynamoDB UpdateItem)

SNS: OrderOutcome topic
      └─ subscription (filter policy: status=CONFIRMED|FAILED) → SQS: EmailQueue
                                                                        │
                                                                        ▼
                                                          Lambda: SendCustomerEmail → SES SendEmail

EventBridge Scheduler (rate(1 day), 14M invocations/month always free)
      └─► Lambda: DailyInventoryReport → DynamoDB Scan (Inventory) → SNS Publish (summary)

Cross-cutting (all live, all free at this scale):
- CloudWatch: Lambda logs (auto) + 1 custom metric (OrdersCreated, via PutMetricData) + 1 alarm (DLQ depth > 0)
- X-Ray: active tracing on every Lambda + the state machine
- Parameter Store: table names, queue URL, topic ARN, bus name — via GetParameter/GetParametersByPath
- IAM: one least-privilege execution role per Lambda; STS GetCallerIdentity used as a free "am I logged in as the right role" health check
```

**Why Step Functions here, specifically:** it gives you a real `StartExecution`, `DescribeExecution`, and (optionally) `GetExecutionHistory` to call from your own API (`GetOrderStatus`), plus hands-on practice with `Choice` states, retries/catch on a `Task` state, and the Standard-vs-Express workflow trade-off — all core DVA-C02 "Development with AWS Services" content. At a few dozen executions while you're testing, you'll stay far under the always-free 4,000 state transitions/month, and that free tier does **not** expire after 12 months — it's permanent for both new and existing accounts.

---

## 3. Services & APIs at a glance

| Service | Status | Always-Free limit | Used for |
|---|---|---|---|
| AWS Lambda | **Live** | 1M requests + 400,000 GB-s/month | All compute |
| Lambda Function URLs | **Live** | Free (part of Lambda) | HTTP entry point instead of API Gateway |
| Amazon DynamoDB | **Live** | 25 GB, 25 RCU/WCU | Orders, Inventory tables |
| Amazon SQS | **Live** | 1M requests/month | OrderQueue, EmailQueue (+ DLQs) |
| Amazon SNS | **Live** | 1M publishes, 1,000 email/month | OrderOutcome topic |
| AWS Step Functions | **Live** | 4,000 state transitions/month, permanent | OrderFulfillment workflow |
| Amazon EventBridge Scheduler | **Live** | 14M invocations/month, permanent | Daily report trigger |
| AWS IAM / STS | **Live** | Free | Roles, `GetCallerIdentity` |
| Systems Manager Parameter Store (Standard) | **Live** | Free | Config values |
| Amazon CloudWatch | **Live** | 10 metrics, 5 GB logs, 3 dashboards, 10 alarms | Logs, 1 metric, 1 alarm |
| AWS X-Ray | **Live** | 100,000 traces/month | Tracing |
| AWS KMS | **Live, but AWS-managed key only** | 20,000 requests/month | Encrypt/decrypt customer email |
| Amazon SES | **Live, sandbox mode** | 3,000 messages/month | Confirmation/failure emails |
| AWS Secrets Manager | **Practice-only** | None (legacy accounts) — $0.40/secret/month + $0.05/10K calls | See §5 — create, use, delete same session |
| Amazon API Gateway | **Practice-only** | 12-month tier only (yours has expired) | See §5 — build once, delete after |
| AWS CodeBuild | **Live** (optional) | 100 build minutes/month, permanent | Build the Lambda jars, if you want real CI practice |
| AWS CodePipeline | **Not used** | 1 free pipeline, 12 months only (expired) | Skip — trigger CodeBuild manually or use GitHub Actions instead |
| Amazon Cognito | **Not used yet** | 50,000 MAU, permanent | Deferred — swap in for IAM SigV4 auth as a later phase |

---

## 4. API reference (core, live endpoints)

### 4.1 `POST /orders` — CreateOrder
- **Trigger:** Lambda Function URL, IAM SigV4-authenticated
- **Request body:**
  ```json
  { "customerId": "cust-123", "customerEmail": "a@b.com",
    "items": [{ "sku": "SKU-1", "qty": 2 }] }
  ```
- **Response:** `201` `{ "orderId": "...", "status": "PENDING" }`
- **AWS calls made:** `kms:Encrypt` (encrypt `customerEmail` with the AWS-managed key `aws/dynamodb`) → `dynamodb:PutItem` (condition: `attribute_not_exists(orderId)`, for idempotency if the client retries) → `sqs:SendMessage` (OrderQueue, body = orderId)
- **Errors:** `409` if `ConditionalCheckFailedException` (duplicate orderId); `400` on malformed body

### 4.2 `GET /orders/{orderId}` — GetOrder
- **AWS calls:** `dynamodb:GetItem` → `kms:Decrypt` (customerEmail) → response includes `status` and, if present, `stepFunctionsExecutionArn`
- **Errors:** `404` if not found

### 4.3 `GET /customers/{customerId}/orders?limit=&nextToken=` — ListOrders
- **AWS calls:** `dynamodb:Query` on a `CustomerIndex` GSI, `Limit` + `ExclusiveStartKey` for pagination
- **Response:** `{ "orders": [...], "nextToken": "..." }` — `nextToken` is the base64 of `LastEvaluatedKey`, omitted when there are no more pages
- **Why it's here:** DynamoDB pagination (`LastEvaluatedKey`/`ExclusiveStartKey`) is a recurring DVA-C02 topic and doesn't come up unless you deliberately build a paged list endpoint

### 4.4 `GET /orders/{orderId}/status` — GetOrderStatus
- **AWS calls:** `dynamodb:GetItem` (to fetch the stored `executionArn`) → `states:DescribeExecution`
- **Response:** `{ "workflowStatus": "RUNNING" | "SUCCEEDED" | "FAILED", "startDate": "..." }`
- **Errors:** `404` if the order has no execution yet (still sitting in the queue)

### 4.5 SQS-triggered: ProcessOrder
- **Trigger:** SQS event source mapping on OrderQueue
- **AWS calls:** `states:StartExecution` (state machine = OrderFulfillment, input = order JSON) → `dynamodb:UpdateItem` (store `executionArn` on the order)
- **Failure handling:** returns a partial batch failure response (`batchItemFailures`) so only the failed message is retried, not the whole batch — practice for SQS Lambda integration, not just "it either all succeeds or all retries"
- **DLQ:** after `maxReceiveCount` retries, the message moves to `OrderQueueDLQ`; a CloudWatch alarm watches its depth

### 4.6 Step Functions tasks (state machine: OrderFulfillment, Standard)
| State | AWS calls | Notes |
|---|---|---|
| CheckInventory | `dynamodb:BatchGetItem` across all line-item SKUs in one call | Batch API instead of N single GetItems |
| ReserveStock | `dynamodb:UpdateItem` per item, `ConditionExpression: stock >= :qty` | Catches `ConditionalCheckFailedException` per item to know which reservations succeeded |
| Choice: all reserved? | — | Native ASL `Choice` state, no AWS call |
| RollbackReservations (only if not all reserved) | `dynamodb:UpdateItem` (add back qty for the items that *did* reserve) | Compensating-transaction pattern — a real distributed-systems exam topic |
| PublishOutcome | `sns:Publish` (message attribute `status=CONFIRMED\|FAILED`, drives the SNS subscription filter policy) | |
| UpdateOrderStatus | `dynamodb:UpdateItem` | |

### 4.7 SNS-triggered: SendCustomerEmail
- **Trigger:** SQS EmailQueue, fed by an SNS subscription with a filter policy (`status` in `[CONFIRMED, FAILED]`)
- **AWS calls:** `ses:SendEmail` — sandbox mode, so both sender and recipient addresses must be verified in SES first
- **Errors:** `MessageRejected` if the recipient isn't verified — a genuinely useful failure to see once, since sandbox-mode SES trips people up in real projects too

### 4.8 EventBridge Scheduler-triggered: DailyInventoryReport
- **AWS calls:** `dynamodb:Scan` (deliberately, not Query — a small table makes Scan harmless here, but it's worth noticing *why* Scan doesn't belong in a hot request path at real scale) → `sns:Publish` (summary email)

---

## 5. Practice-only modules — costly APIs, coded but not left running

For each of these: write a small standalone Java class (not wired into the live app), run it once against your account to see the real request/response, then tear the resource down immediately so nothing keeps billing.

| Service | Why it's costly | What you actually run for practice | Free alternative used in the live app instead |
|---|---|---|---|
| **AWS Secrets Manager** | $0.40/secret/month + $0.05/10K API calls, no ongoing free tier on existing accounts | `CreateSecret` → `GetSecretValue` → `PutSecretValue` (rotation practice) → `DeleteSecret` with `ForceDeleteWithoutRecovery=true` in the same run | Systems Manager **Parameter Store** (`SecureString`, backed by the same free AWS-managed KMS key) |
| **Amazon API Gateway** | Your 12-month free tier is used up; any deployed API now bills per request (small, but not $0) | Define one HTTP API in front of a single Lambda, exercise a usage plan / throttling / a Lambda authorizer, then `DeleteApi` when done | **Lambda Function URL** with IAM auth (genuinely free) |
| **AWS KMS customer-managed key (CMK)** | A CMK costs **$1/month** flat, regardless of request volume — the 20,000-free-requests tier doesn't make the key itself free | `CreateKey` → `Encrypt`/`Decrypt` against it → `ScheduleKeyDeletion` (7-day minimum waiting period — note the constraint, don't expect instant deletion) | **AWS-managed key** (`aws/dynamodb`) — same `Encrypt`/`Decrypt` API, genuinely $0, already used live in §4.1/4.2 |
| **AWS CodePipeline** | First pipeline is free for 12 months only — that window has closed for you | Optional: create one pipeline, watch a run, delete the pipeline | Trigger **CodeBuild** (100 free minutes/month, permanent) manually via CLI, or use GitHub Actions |

This is also where the "if something's costly and required to run the project, is there an alternative?" question you asked generalizes: the pattern is always *(a)* find the AWS-managed / Standard-tier equivalent if one exists (KMS, Secrets Manager both have one), or *(b)* if there truly isn't a free equivalent (API Gateway has no free substitute for the API Gateway API itself — Function URLs are a different API surface), practice the real API in a short-lived, deliberately deleted resource instead of leaving it deployed.

---

## 6. Build order (checklist)

1. **Foundation** — IAM roles (one per Lambda), Parameter Store entries
2. **Data layer** — DynamoDB Orders (+ CustomerIndex GSI) and Inventory tables
3. **Core API** — CreateOrder, GetOrder, ListOrders behind Function URLs, IAM SigV4 auth, tested via Postman
4. **Async processing** — OrderQueue + DLQ, ProcessOrder Lambda
5. **Orchestration** — OrderFulfillment state machine (CheckInventory → ReserveStock → Choice → [Rollback] → PublishOutcome → UpdateOrderStatus)
6. **Status API** — GetOrderStatus (DescribeExecution)
7. **Notifications** — SNS topic + filter-policy subscription → EmailQueue → SendCustomerEmail → SES
8. **Scheduled job** — EventBridge Scheduler → DailyInventoryReport
9. **Encryption** — wire KMS `Encrypt`/`Decrypt` (AWS-managed key) into CreateOrder/GetOrder
10. **Observability** — X-Ray on everything, 1 custom CloudWatch metric, 1 alarm
11. **Practice-only modules** — Secrets Manager, API Gateway, KMS CMK, (optionally) CodePipeline — run once each, tear down
12. **Later phase** — swap IAM SigV4 for Cognito auth on the Function URLs

---

## 7. Repo structure (Java 21 + AWS SDK v2)

```
cloudtrack/
├── functions/
│   ├── create-order/
│   ├── get-order/
│   ├── list-orders/
│   ├── get-order-status/
│   ├── process-order/
│   ├── check-inventory/
│   ├── reserve-stock/
│   ├── rollback-reservations/
│   ├── publish-outcome/
│   ├── update-order-status/
│   ├── send-customer-email/
│   └── daily-inventory-report/
├── statemachine/
│   └── order-fulfillment.asl.json
├── practice-only/                # standalone, NOT wired into the app — run manually, then tear down
│   ├── SecretsManagerDemo.java
│   ├── ApiGatewayDemo.java        # + a teardown script
│   ├── KmsCmkDemo.java
│   └── S3Demo.java                # bucket + object lifecycle, run once then delete
├── common/                       # shared DTOs, DynamoDB mapper config
├── deploy-notes.md               # your record of exact CLI commands per resource
└── README.md
```

---

## 8. More APIs (extra coverage)

### 8.1 Free additions — wire these into the live app

| Addition | AWS calls | What it adds |
|---|---|---|
| **DynamoDB Streams → AuditLog** | Enable a stream on Orders (NEW_AND_OLD_IMAGES) → Lambda `AuditLogger` consumes it → writes a line to a small `AuditLog` table | Stream-triggered Lambdas (`GetRecords` under the hood) — a distinct trigger type from SQS/SNS/HTTP you haven't used yet |
| **DynamoDB `TransactWriteItems`** | Replace the separate ReserveStock + UpdateOrderStatus `UpdateItem` calls with one `TransactWriteItems` call across both tables | All-or-nothing multi-item ACID transactions — a real exam differentiator vs. plain `UpdateItem` |
| **DynamoDB TTL** | `UpdateTimeToLive` on the Orders table; set an `expiresAt` attribute on completed orders | Automatic, free item expiry — common cost/cleanup pattern |
| **EventBridge custom bus (audit trail)** | `events:PutEvents` from each state-machine step → a rule routes to CloudWatch Logs | Custom events cost $1/million, but at test volume (a few dozen events) that's a fraction of a cent — worth doing live rather than practice-only |
| **SQS queue introspection** | `sqs:GetQueueAttributes` (ApproximateNumberOfMessages) for a `/health` endpoint; `sqs:PurgeQueue` as a reset utility script | APIs beyond Send/Receive/Delete |
| **SNS subscription management** | `sns:ListSubscriptionsByTopic`, `sns:Unsubscribe` — a small cleanup script | Subscription lifecycle, not just Publish |
| **STS `AssumeRole`** | A script that assumes a narrower, temporary role (e.g. read-only on Orders) and calls `GetItem` with those temp creds | Temporary credentials / cross-role practice, distinct from `GetCallerIdentity` |
| **IAM `SimulatePrincipalPolicy`** | Point it at one of your Lambda roles + an action/resource pair, see allow/deny | The IAM policy simulator — zero resources needed, genuinely free, and a good way to sanity-check least-privilege roles before you deploy them |
| **CloudWatch Logs Insights** | `StartQuery` / `GetQueryResults` — a script that pulls "recent errors across all Lambdas" | Querying logs programmatically, not just reading them in the console |
| **X-Ray `GetTraceSummaries` / `BatchGetTraces`** | A script that pulls the last hour of traces and prints slow segments | Reading trace data via API instead of only the console |
| **Lambda versions & aliases** | `PublishVersion`, `CreateAlias`, `UpdateAlias` (e.g. a `prod` alias pointing at a specific version) | Deployment/rollback mechanics — a real DVA-C02 "Deployment" domain topic |
| **CloudFormation** | `CreateStack` / `DescribeStacks` / `DeleteStack` on a *subset* (e.g. just the AuditLog table + rule) | The service itself is free; a small stack now makes the later full-SAM migration much less intimidating |

### 8.2 New practice-only module: Amazon S3

S3's 5 GB is only free for your first 12 months, which you've used — but a handful of tiny test objects costs a fraction of a cent even outside the free tier, so this is safe to actually run once, just don't leave the bucket around:

- `CreateBucket` → `PutObject` (a couple of small test files) → `GetObject` → `GeneratePresignedUrl` (time-limited download link — a genuinely common real-world S3 API) → `ListObjectsV2` → `DeleteObjects` (batch) → `DeleteBucket`
- Add it to `practice-only/S3Demo.java`, run once, confirm the bucket is gone afterward

### 8.3 Cognito phase-2 — concrete API list (still deferred, but Always Free when you build it)

When you're ready to swap IAM SigV4 for Cognito auth on the Function URLs: `SignUp`, `ConfirmSignUp`, `InitiateAuth` (get a JWT), `AdminCreateUser` / `AdminSetUserPassword` (for seeding a test user without email verification), `GetUser`. Cognito is Always Free up to 50,000 MAU, so unlike the items in §8.2 this one is fine to leave live once you build it.

---

## 9. Still open for your review

- Want the `practice-only/` module code (Secrets Manager, API Gateway, KMS CMK, S3) written now too, or just this reference for later?
- Of the §8.1 free additions, which do you want actually wired into the build order — all of them, or a subset for now?

---

*Updated per your feedback: Step Functions restored as the orchestrator, full API documentation and per-endpoint flow added, API surface maximized (batch ops, pagination, execution status, encryption), and a clear live-vs-practice-only split for anything that costs money.*
