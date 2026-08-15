# DVA-C02 Code-Practice Checklist (Expanded)

Domain weights: **Development 32% | Security 26% | Deployment 24% | Troubleshooting & Optimization 18%**
Your original list = almost entirely Domain 1. Domains 2–4 (68% of the exam) needed their own modules.

---

## DOMAIN 1 — Development with AWS Services (32%)

### 01-s3
- ListBuckets, CreateBucket, PutObject, GetObject, DeleteObject *(you have these)*
- CopyObject, ListObjectsV2 (pagination), multipart upload (CreateMultipartUpload/UploadPart/CompleteMultipartUpload)
- Object versioning enable/read
- Server-side encryption on PutObject (SSE-S3 vs SSE-KMS vs SSE-C)
- Storage classes (Standard, IA, Glacier) via PutObject headers
- S3 event notifications → Lambda/SQS/SNS
- S3 Transfer Acceleration

### 02-dynamodb
- CreateTable, PutItem, GetItem, Query, Scan, UpdateItem, DeleteItem *(you have these)*
- BatchGetItem, BatchWriteItem
- Global Secondary Index (GSI) vs Local Secondary Index (LSI) — create and query both
- Conditional writes (ConditionExpression) — put separately since it overlaps Module 08
- TTL configuration
- On-demand vs provisioned capacity, auto scaling

### 03-sqs
- SendMessage, ReceiveMessage, DeleteMessage, Visibility Timeout *(you have these)*
- Standard vs FIFO queues (MessageGroupId, MessageDeduplicationId)
- Long polling (WaitTimeSeconds)
- Dead-letter queues (redrive policy, maxReceiveCount)
- SendMessageBatch

### 04-sns
- Publish, Publish to Topic *(you have these)*
- Subscribe (email, SQS, Lambda, HTTP/S endpoints)
- Message filtering (filter policies)
- Fan-out pattern (SNS → multiple SQS queues)
- FIFO topics

### 05-lambda
- Invoke Lambda *(you have this)*
- Environment variables, layers, versions, aliases
- Cold starts / provisioned concurrency
- Function URLs (IAM auth vs NONE)
- Destinations (on success/failure) vs DLQ — the difference is tested
- Concurrency limits (reserved vs unreserved), throttling behavior
- Event source mappings (SQS, DynamoDB Streams, Kinesis) and batch size/window

### 06-eventbridge
- PutEvents *(you have this)*
- Rules with event patterns
- Scheduled rules (cron/rate expressions)
- Custom event buses
- Archive & replay

### 07-s3-presigned-url
- Presigner *(you have this)*
- Presigned URL for PUT (uploads) not just GET
- Expiration handling

### 08-dynamodb-advanced
- Query pagination, Conditional expressions, Transactions, DynamoDB Streams *(you have these)*
- TransactWriteItems vs TransactGetItems specifically
- Optimistic locking pattern with ConditionExpression
- Streams → Lambda trigger end-to-end

### 09-api-gateway *(NEW — you have zero API Gateway; this is a major gap)*
- REST API vs HTTP API (cost/feature differences — commonly tested)
- Lambda proxy integration vs Lambda custom (mapping templates/VTL)
- Stages, deployments, stage variables
- Throttling (account/stage/method level), usage plans, API keys
- CORS configuration
- Request validation
- Direct AWS service integration (e.g., API Gateway → SQS, no Lambda)

### 10-step-functions *(NEW — you're already using this per your project, add a dedicated module)*
- StartExecution, DescribeExecution
- Standard vs Express workflows
- Task, Choice, Parallel, Map states
- Error handling/retry in state machine definitions
- Integration with Lambda, SQS, SNS, DynamoDB (service integrations, not just Lambda)

### 11-kinesis *(NEW)*
- PutRecord, PutRecords, GetRecords, shard iterators
- Kinesis Data Streams vs Kinesis Data Firehose vs SQS (exam loves this comparison)

### 12-elasticache / rds *(NEW, lighter — mostly conceptual for the exam)*
- RDS connection basics, IAM database authentication
- ElastiCache as a caching layer in front of DynamoDB/RDS

---

## DOMAIN 2 — Security (26%) *(NEW — your list has none of this)*

### 13-iam
- CreateRole, AttachRolePolicy, PutRolePolicy
- IAM policy evaluation logic: explicit deny > allow, identity-based vs resource-based policies
- Trust policies vs permission policies
- Cross-account role assumption

### 14-sts
- AssumeRole, GetSessionToken, GetCallerIdentity
- Temporary credentials in Lambda vs long-lived keys

### 15-cognito
- User pools (sign-up/sign-in, hosted UI) vs Identity pools (federated identity → temp AWS creds) — the #1 confused pair on this exam
- Authorizer integration with API Gateway
- Tokens: ID token vs access token vs refresh token

### 16-kms
- Encrypt, Decrypt, GenerateDataKey
- Envelope encryption pattern
- Key policies vs IAM policies for KMS
- Customer managed key vs AWS managed key

### 17-secrets-manager-and-parameter-store
- GetSecretValue, PutSecretValue, RotateSecret
- Parameter Store GetParameter (SecureString) vs Secrets Manager — cost, rotation, size differences (frequently tested)

### 18-application-security
- Encryption in transit (TLS) vs at rest — where each is configured per service
- Signing requests with SigV4 (you've already used this for Lambda Function URL — generalize it)

---

## DOMAIN 3 — Deployment (24%) *(NEW — your list has none of this)*

### 19-sam-and-cloudformation
- SAM template basics (AWS::Serverless::Function, Api, SimpleTable)
- `sam build`, `sam deploy`, `sam local invoke`
- CloudFormation stack create/update, change sets, drift detection
- Nested stacks

### 20-codepipeline-codebuild-codedeploy
- CodeCommit/CodeBuild/CodeDeploy/CodePipeline as a chain (conceptual + buildspec.yml, appspec.yml basics)
- Deployment strategies: in-place vs blue/green (CodeDeploy), canary vs linear (Lambda aliases + CodeDeploy), rolling (Elastic Beanstalk)
- Lambda traffic shifting with weighted aliases

### 21-elastic-beanstalk
- Environment types (single instance vs load-balanced), deployment policies (all at once, rolling, rolling with additional batch, immutable, blue/green)
- .ebextensions basics

### 22-ecs-ecr
- Register task definition, run task/service
- ECR PutImage/push-pull flow
- Fargate vs EC2 launch type

### 23-cicd-cli
- `aws cloudformation deploy`, `aws lambda update-function-code`, `aws deploy create-deployment` from CLI (exam gives CLI snippets, not just SDK)

---

## DOMAIN 4 — Troubleshooting & Optimization (18%) *(NEW — your list has none of this)*

### 24-cloudwatch
- PutMetricData, custom metrics, metric filters from logs
- Alarms (thresholds, composite alarms)
- CloudWatch Logs Insights queries
- Lambda-specific: Duration, Throttles, Errors, ConcurrentExecutions metrics

### 25-x-ray
- Instrumenting Lambda/API code with the X-Ray SDK
- Segments vs subsegments, annotations vs metadata
- Tracing across Lambda → DynamoDB/SQS

### 26-cloudtrail
- Reading CloudTrail events for API call auditing (mostly conceptual, but know LookupEvents)

### 27-error-handling-patterns
- Exponential backoff + jitter (implement manually once, then note SDK does it automatically)
- Idempotency patterns (DynamoDB conditional writes, SQS dedup)
- DLQ inspection and redrive

---

## Suggested build order for your project
Given CloudTrack already uses Step Functions + Lambda Function URL + IAM SigV4, natural next additions in priority order:
1. **API Gateway** (09) — biggest gap, very high exam weight, and a natural front door for CloudTrack instead of/alongside the Function URL
2. **Cognito** (15) — lets you demo real user auth instead of just SigV4
3. **Secrets Manager / Parameter Store** (17) — store a config value or fake API key
4. **SAM** (19) — package what you've built by hand into IaC; also builds the skill AWS assumes you have
5. **CloudWatch + X-Ray** (24, 25) — instrument the existing Step Functions/Lambda flow for observability
6. **KMS** (16) — encrypt something in S3/DynamoDB with a customer-managed key

Everything else (Kinesis, ECS, Beanstalk, RDS) is lower-frequency on the exam and fine to study conceptually via docs/practice tests without full code implementation, given your Always-Free-tier constraint.
