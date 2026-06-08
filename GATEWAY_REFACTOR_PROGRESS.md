# Gateway Refactor Progress

## Objective

Refactor `landgate-trigger` gateway code by functional responsibility, not by mechanical line splitting.

The primary target is `AbstractGatewayHandler`, which currently orchestrates request access checks, client detection, group resolution, account failover, upstream request construction, response handling, and billing settlement. The refactor goal is to reduce coupling while preserving behavior.

## Migration Principles

- Move one functional area at a time.
- Preserve existing gateway behavior during each migration.
- Keep each migration commit small enough to review and roll back.
- Add or update focused tests for each extracted functional module.
- Avoid broad package reshuffles until functionality boundaries are stable.
- Prefer explicit context objects over hidden `ThreadLocal` dependencies.
- Do not refactor business rules while migrating code unless explicitly planned.

## Completed Migrations

### Initial Admin Bootstrap

Commit:

```text
055a216 feat:初始化管理员
```

Purpose:

- Added initial admin account bootstrap from environment variables.
- Supports creating an admin account at startup and optionally resetting its password.

Key files:

- `landgate-app/src/main/java/com/landgate/config/AdminAccountInitializer.java`
- `landgate-app/src/main/resources/application.yml`
- `.env.example`
- `docker-compose.yml`

### Transformer Context Decoupling

Commit:

```text
31436f4 refactor gateway transformer context
```

Purpose:

- Introduced explicit upstream request context for transformers.
- Reduced direct dependency on `GatewayRequestContext.get()` in real gateway request paths.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/UpstreamRequestContext.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/IRequestTransformer.java`
- `OpenAiTransformer`
- `AnthropicTransformer`
- `GeminiTransformer`
- `AbstractGatewayHandler`

Behavior note:

- The legacy `buildUpstreamRequest(String body, AccountEntity account, String accessToken)` entry point remains as a compatibility path.

### Billing Settlement Module

Commit:

```text
c2d6302 refactor gateway billing settlement
```

Purpose:

- Moved usage logging and balance/quota settlement out of `AbstractGatewayHandler`.
- Created a billing-focused gateway service.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/billing/GatewayBillingSettlementService.java`

Migrated behavior:

- Usage log creation.
- Settling status update.
- Balance deduction for non-privileged users.
- Quota accumulation.
- Failed/settling-failed status transitions.

### Group Resolution Module

Commit:

```text
b37b562 refactor gateway group resolver
```

Purpose:

- Moved gateway group loading and Claude Code fallback group resolution out of `AbstractGatewayHandler`.
- Removed direct `IGroupRepository` dependency from the handler.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/group/GatewayGroupResolver.java`
- `landgate-trigger/src/test/java/com/landgate/trigger/gateway/group/GatewayGroupResolverTest.java`

Migrated behavior:

- Loading non-deleted group by ID.
- Resolving `claude_code_only` fallback chains.
- Detecting fallback cycles.
- Handling missing or deleted fallback groups.

### Client Profile Detection Module

Commit:

```text
3dcda34 refactor gateway client profile detection
```

Purpose:

- Moved client-facing request format detection and Claude Code detection out of `AbstractGatewayHandler`.
- Centralized request header extraction for downstream reuse.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/client/ClientProfile.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/client/ClientProfileService.java`
- `landgate-trigger/src/test/java/com/landgate/trigger/gateway/client/ClientProfileServiceTest.java`

Migrated behavior:

- Reading request platform and format from `GatewayDispatcher` request attributes.
- Extracting Anthropic `metadata.user_id`.
- Extracting `max_tokens` and `model` for Claude Code validation.
- Detecting Claude Code clients.
- Capturing request headers once for reuse.

### Request Access Services

Commit:

```text
bc9439b refactor gateway request access services
```

Purpose:

- Moved request attribute parsing and gateway access checks out of `AbstractGatewayHandler`.
- Created request/access-focused gateway modules.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/request/GatewayRequestInfo.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/request/GatewayRequestParser.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/access/GatewayAccessResult.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/access/GatewayAccessService.java`
- `landgate-trigger/src/test/java/com/landgate/trigger/gateway/request/GatewayRequestParserTest.java`
- `landgate-trigger/src/test/java/com/landgate/trigger/gateway/access/GatewayAccessServiceTest.java`

Migrated behavior:

- Extracting `api_key_id`, `user_id`, `group_id`, and request ID.
- API key presence check.
- Group existence and active status check.
- API key quota check.
- User existence check.
- Balance check for non-privileged users.

### Response Handling Service Extraction

Commit:

```text
pending
```

Purpose:

- Moved gateway response writing and response usage parsing out of `AbstractGatewayHandler`.
- Created a response-focused gateway service while keeping the existing handler entry points as thin delegates.

Key files:

- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/response/GatewayResponseService.java`
- `landgate-trigger/src/main/java/com/landgate/trigger/gateway/response/GatewayResponseResult.java`
- `AbstractGatewayHandler`

Migrated behavior:

- Streaming SSE response handling.
- Upstream streaming aggregation into non-streaming Anthropic Messages responses.
- Non-streaming response usage parsing and response protocol translation.
- Streaming client disconnect auditing.
- Streaming concurrency lease renewal.

## Current Functional Modules

The gateway currently has these extracted functional areas:

- `gateway/request`: request metadata parsing.
- `gateway/access`: API key, group, quota, user, and balance access checks.
- `gateway/client`: client format/profile detection and Claude Code detection.
- `gateway/group`: group loading and fallback group resolution.
- `gateway/billing`: usage log and balance/quota settlement.
- `gateway/route`: upstream route strategy resolution.
- `gateway/converter`: protocol conversion and stream translation.
- `gateway/response`: response writing, streaming translation, streaming aggregation, and response usage parsing.
- `gateway/handler`: platform-specific handler shells.

## Remaining Migration Candidates

### Upstream Error / Retry Policy

Recommended next functional migration.

Current location:

- 401 OAuth refresh handling in `AbstractGatewayHandler`.
- 429/529/5xx retry handling.
- `markAccountUnhealthy(...)`.
- masked upstream error writing.

Target package:

```text
landgate-trigger/src/main/java/com/landgate/trigger/gateway/error/
```

Notes:

- Existing `ErrorPassthroughService` already owns part of the policy.
- Migration should avoid changing retry semantics.

### Failover Execution

Current location:

- Main account selection and retry loop in `AbstractGatewayHandler`.

Target package:

```text
landgate-trigger/src/main/java/com/landgate/trigger/gateway/failover/
```

Notes:

- This is likely the highest-risk migration.
- Do it only after response handling and error policy are separated.
- Pay close attention to `ConcurrencySlot` acquisition/release.

## Verification Commands

Useful focused verification:

```bash
mvn -pl landgate-trigger -am compile -DskipTests
mvn -pl landgate-trigger -Dtest=ClientProfileServiceTest,GatewayGroupResolverTest,GatewayAccessServiceTest,GatewayRequestParserTest,AbstractGatewayHandlerTest,OpenAiTransformerTest,AnthropicTransformerTest,GeminiTransformerTest,UpstreamRouteResolverTest test
```

Known caveat:

- A broader `-am test` run has previously been blocked by a Mockito/ByteBuddy agent initialization issue in `landgate-domain` on the local JDK. This was unrelated to the gateway refactor.

## Next Suggested Step

Migrate upstream error and retry policy by function.

Recommended first error/retry step:

1. Add `gateway/error/GatewayUpstreamErrorPolicy` or a similarly focused service.
2. Move status classification and account health marking decisions out of `AbstractGatewayHandler`.
3. Keep `ErrorPassthroughService` behavior unchanged and delegate to it for safe message extraction.
4. Preserve existing retry semantics for 401 OAuth refresh, 429/529/5xx retry, and masked upstream errors.
5. Run focused gateway tests plus a compile.

Avoid extracting the main failover loop until error/retry decisions are separated and stable.
