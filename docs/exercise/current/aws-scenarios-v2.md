# AWS Self-Study Scenarios — Always-Free Build Track + Paid-Service Design Track

Split into two tracks based on what you asked for:

- **Track A** — 100% Always Free services. Full Design → Code → Deploy → Maintain. Safe to leave running indefinitely at $0.
- **Track B** — Realistic paid-service architectures (RDS, ALB, NAT Gateway, WAF, etc.), for Solutions Architect practice specifically. **Design only** — you produce the diagram and the written decisions, you don't deploy and hold these running.

## Quick reference — what's actually $0 forever vs what bills

| Service | Status | Notes |
|---|---|---|
| Lambda | **Always Free** | 1M requests + 400,000 GB-seconds/month |
| DynamoDB | **Always Free** | 25 GB storage, 25 RCU/WCU |
| SNS | **Always Free** | 1M publishes/month |
| SQS | **Always Free** | 1M requests/month |
| EventBridge | **Always Free** | AWS-service events free; custom events have a small always-free allowance |
| Step Functions (Standard) | **Always Free** | 4,000 state transitions/month |
| Cognito (Lite or Essentials tier) | **Always Free, indefinite** | 10,000 MAU for direct/social sign-in — you must pick Lite or Essentials when creating the pool; Plus tier has no free tier |
| CloudWatch (basic) | **Always Free** | 10 metrics, 10 alarms, 5 GB log ingestion, 1M API requests |
| CloudFront | **Always Free** | 1 TB data out, 10M requests/month |
| Lambda Function URL | **Always Free** | no charge beyond standard Lambda invocation pricing — use this instead of API Gateway when you don't need API Gateway's extra features, and it skips that cost entirely |
| IAM / CloudFormation (the tool itself) | **Always Free** | you only pay for whatever resources they create |
| S3 | Pay-as-you-go | the old 5 GB free tier is 12-months-only now; standard storage is ~$0.023/GB/month — a few cents/month at hobby scale, not literally $0 |
| API Gateway | Pay-as-you-go | $1.00/million requests (HTTP API) to $3.50/million (REST API) — cents at hobby volume, but avoidable with Lambda Function URLs |
| RDS, EC2, ElastiCache, OpenSearch, ALB/NLB | Real recurring cost | roughly $15–100+/month each, billed hourly whether you use them or not |
| NAT Gateway | Real recurring cost | ~$33/month just for existing, plus data processing charges — the single most common "surprise" AWS bill |
| Route 53 hosted zone | Real recurring cost | ~$0.50/month |
| WAF | Real recurring cost | ~$5–10/month even at light usage |

---

# Track A — Build it for real ($0 forever)

## Scenario A1 — Read-It-Later API

A "save a link, tag it, get a daily unread digest" service. Smallest surface area, still touches auth, async processing, and a scheduled job.

**Services:** Lambda (Java) + Lambda Function URL (no API Gateway) + DynamoDB + Cognito (Lite tier) + SQS + EventBridge Scheduler + CloudWatch — all Always Free at hobby scale.

### Phase 1 — Design (Solutions Architect)
- Access patterns first: "all bookmarks for user X", "unread bookmarks for user X", "bookmarks by tag" — design your DynamoDB table/GSIs from these.
- Lambda Function URL vs API Gateway: what would you actually lose by skipping API Gateway here (custom domains, request throttling, usage plans), and does any of that matter for a solo project?
- Why decouple tag processing into SQS instead of doing it inline in `createBookmark`? What does that cost you (latency to "tag applied")?
- What happens if the client retries a `POST /bookmarks` after a timeout — how do you prevent a duplicate?

**Deliverable:** architecture diagram + a short written answer to each question above.

### Phase 2 — Build (Developer)
- Java 21 Lambda handlers (AWS SDK v2). Lambda Function URLs use the same payload format as API Gateway HTTP API v2.0, so your handler code is portable if you ever do add API Gateway in front later.
- Write each Lambda's IAM policy by hand, scoped to exactly what that function calls — not the console's broad default.
- Unit tests (JUnit 5 + Mockito); integration tests against DynamoDB Local or Testcontainers.
- **Ask-why checkpoints:** Java Lambda cold start — why worse than Node/Python, does it matter here? DynamoDB conditional writes for your idempotency fix. SQS visibility timeout vs your Lambda's timeout — what breaks if they're mismatched?

### Phase 3 — Deploy
- IaC: AWS CDK (Java) or plain CloudFormation.
- CI/CD: GitHub Actions (free minutes) with an OIDC role assumed into AWS — no CodePipeline/CodeBuild charges.
- Verify: curl the Function URL directly, then check CloudWatch Logs Insights for cold-start duration and errors.

### Phase 4 — Maintain
- Alarms: Lambda error rate, DynamoDB throttled requests, SQS DLQ message count > 0.
- Failure-injection prompt: *"Make my Lambda intermittently throw a DynamoDB ProvisionedThroughputExceededException, then have me diagnose it from CloudWatch Logs and metrics alone before telling me the fix."*
- Cost review: at this design, nothing here bills — the only thing to watch is Cognito tier (must stay Lite/Essentials, not Plus).

## Scenario A2 — Cross-System Inventory Sync

Two independent services ("Storefront" and "Warehouse") that must stay in sync when stock changes — the classic "two systems that must agree" problem.

**Services:** SNS + 2× SQS (one per subscriber) + DLQ per queue + Lambda consumers (Java) + DynamoDB (per-service state) + Step Functions (reserve-stock saga) + EventBridge + CloudWatch — all Always Free at this scale (Step Functions Standard, under 4,000 transitions/month).

### Phase 1 — Design
- Why SNS fan-out instead of each producer calling each consumer directly? What does it decouple, and what does it *not* solve?
- Standard vs FIFO for the stock-events topic — does ordering matter here? What breaks if two "sold" events for the same SKU process out of order?
- Message design: full item state in the event, or "SKU X changed, go re-fetch"? What's the trade-off (payload size vs staleness vs coupling)?
- Idempotency: both systems can see the same event twice (at-least-once delivery) — how do you guarantee you don't double-decrement stock?
- Saga design for "reserve → charge → confirm" — what's the compensating action if step 3 fails after step 1 succeeded?

### Phase 2 — Build
- Java Lambda consumers using SQS batch processing with **partial batch failure reporting** (`batchItemFailures`) — most people skip this and then wonder why one bad message blocks the whole batch.
- Hand-write the Step Functions ASL JSON at least once before letting a CDK builder generate it.
- **Ask-why checkpoints:** SQS batch failure response format. Step Functions retry/backoff (`IntervalSeconds`, `BackoffRate`, `MaxAttempts`) — why do the defaults exist? SNS message filtering by attribute vs separate topics per event type.

### Phase 3 — Deploy
- IaC: CDK, two separate stacks (one per "system") deployed independently.
- CI/CD: one GitHub Actions workflow per stack, path-filtered so a Storefront change doesn't redeploy Warehouse.

### Phase 4 — Maintain
- Dashboard: DLQ depth per queue, Step Functions failed-execution count, SNS delivery failures.
- Failure-injection prompt: *"Make one of my SQS consumers throw on every 5th message so failures land in its DLQ. Walk me through diagnosing it from the DLQ message body plus CloudWatch Logs alone, then have me write the redrive process."*

---

# Track B — Design-only (Solutions Architect practice, paid services)

For these, the deliverable is the **design artifact** — diagram, written decisions, cost estimate, runbook — not a running deployment. If you ever want to prove a design works, deploy it in a fresh sandbox for the shortest window that lets you test the thing you're trying to prove, then destroy everything — each scenario notes what that would cost.

## Scenario B1 — Highly Available 3-Tier Order Processing Platform

The classic SAA-style design: a web app that must survive an AZ failure, auto-scale with traffic, and run a relational database with a read replica for reporting.

**Services (all paid, several with fixed hourly cost):** Route 53 + CloudFront (still Always Free) + WAF + ALB + EC2 Auto Scaling Group or ECS Fargate + RDS Multi-AZ with a read replica + ElastiCache + VPC across 2–3 AZs (public/private subnets) + NAT Gateway + Secrets Manager + S3 + CloudWatch + AWS Backup.

### Design deliverables
- Full architecture diagram: AZs, subnets, tiers, security group chain (ALB SG → app SG → DB SG).
- Written justification for each of these decisions:
  - ALB vs NLB — why for this workload?
  - ASG scaling policy — target tracking vs step scaling, and which metric (CPU? request count per target?) with actual threshold numbers.
  - RDS Multi-AZ vs Aurora vs single-AZ-with-snapshots — trade-off in cost vs your RTO/RPO target.
  - ElastiCache's actual job here — session store or query cache? — and the eviction policy that fits it.
  - NAT Gateway (~$33/month fixed + data processing) vs a self-managed NAT instance (~$3/month but you own patching and availability) — which do you pick and why?
  - Where do WAF managed rule groups attach, and what are they actually protecting against?
- Security design: least-privilege IAM boundary policies, Secrets Manager rotation Lambda.
- **DR/HA runbook** — write out exactly what happens, referencing the specific AWS mechanism, for: an AZ going down, the RDS primary failing, a bad deploy needing rollback.
- Cost estimate: build it in the AWS Pricing Calculator, get a monthly total, and identify the single most expensive line item (usually NAT Gateway or RDS).
- Optional short proof-of-concept: deploy for 1–2 hours to actually trigger and watch a failover, then tear down completely — budget a few dollars for that window, not the full monthly cost. Full teardown checklist matters here: RDS snapshots don't auto-delete, and an unattached Elastic IP keeps billing even with nothing running.

## Scenario B2 — Multi-Region Platform with Automated Failover

A global-user-base platform that must keep serving traffic if an entire AWS region goes down.

**Services:** Route 53 (health checks + failover/latency routing) + CloudFront + regional stacks (ALB + ECS or Lambda) in two regions + DynamoDB Global Tables or Aurora Global Database + S3 Cross-Region Replication.

### Design deliverables
- State the RTO/RPO targets you'd commit to, then show which architecture choice actually hits them.
- Active-active vs active-passive — write up the trade-off for this specific platform.
- DynamoDB Global Tables uses last-writer-wins conflict resolution — what does that mean for your data model, and is it acceptable, or does it push you toward Aurora Global Database instead?
- Failover runbook: what a Route 53 health check failure actually triggers, how long real-world failover takes end to end, and what a user experiences during it.
- Cost estimate that makes the multiplier explicit — you're paying for compute in two regions plus the replication cost (Global Tables replicated writes, or S3 CRR data transfer).

---

## Suggested order

A1 → A2 first — build real, working things at zero cost and get comfortable debugging them. Then B1 → B2 as design exercises you can do entirely on paper/diagram/cost-calculator without touching your wallet, and only spend real money on a short, deliberate proof-of-concept if you want to see a specific mechanism (like Multi-AZ failover) actually happen.

Tell me which scenario you want to start on and I can generate the DynamoDB access-pattern table, the IAM policy skeleton, the architecture diagram, or a ready-to-paste prompt for whatever AI tool is generating your code.
