# S1-JIN-02 prompt integration

## 현재 BE 연결 상태 (2026-10-07)

HTTP 컨트롤러·아동 세션/CSRF 보안 경로·실행 설정·요청/오류 응답 연결까지 구현했다.
최신 실제 계약·설정·운영 연동 한계는 [roleplay-http-api.md](roleplay-http-api.md)와
[설정 예시](roleplay-http.example.yml)를 따른다. 아래 내용은 단계별 구현 기록이며,
이전 단계의 미연결 설명은 최신 HTTP 구현 상태를 덮어쓰지 않는다.

HTTP 기능은 기본 비활성이고, 활성화 시 필수 운영 포트/업로드 정책이 빠지면 시작에 실패한다.
테스트 제공자는 test 소스에만 존재한다. FE·공통 계약 파일·배포·커밋/푸시는 변경하지 않았다.
최종 BE 전체 754개 테스트(실패/오류/건너뜀 0)와 spotlessCheck 통과.


The prerequisite `ai.application.prompt` sources, `prompts/roleplay/v1` resources and their tests
are imported unchanged from PR #42, commit `bb33282` (the exact hash is available in the locally
fetched `origin/pr-42` ref). The feature branch still starts from develop `c644dc2`; the PR's provider and configuration changes are not included in this import.

`StructuredRoleplayTurnSteps` is the new request-scoped adapter between the retry coordinator,
`RoleplayPromptFactory` and `StructuredLlmExecutor`. Create a fresh instance per execution. It shares
one immutable input snapshot and request trace across analysis, generation and evaluation. Each
actual analysis call gets an analysis trace ID; generation and evaluation retain that ID. A
candidate ID is allocated by the retry coordinator before generation.

Technical retries preserve the coordinator's candidate ID and stage attempt counts. A generation
revision is created only from a real REGENERATE or SAFETY_REGENERATE evaluation. REANALYZE is mapped
to AnalysisRetry using the latest failure codes and optional change/required/avoid instructions.
As required by the imported factory contract, generation after REANALYZE uses no GenerationRetry.
The coordinator still retains rejected history in memory for subsequent revision requests.

Strict parsers validate turn/candidate/goal IDs, strategy consistency, allowed codes and PASS
conditions before any candidate is returned. No HTTP controller, persistence, speech adapter or
real provider invocation is added by this integration. Turn limits in RoleplayTurnInput must come
from the calling service's agreed policy; test fixture values are not production defaults.

## Speech pipeline integration

Speech ports/models, AiOperation's speech categories, LlmRequest's speech-operation rejection
and SpeechModelsTest are reused from the same PR #42 commit. RoleplaySpeechTurnPipeline starts
with an externally owned audio request and a shared deadline created before STT. Its required
RoleplayInputProcessor must implement canonicalization, privacy removal and input safety; no
production processor or pass-through default is supplied. Low/unknown confidence or silence
never calls the processor/LLM/TTS, and returns NOT_ASSESSABLE plus DO_NOT_STORE through the resolver.
Input-quality and provider-error notice text must be injected using the approved notice catalog.

Speech technical retries allow initial execution plus two retries, preserving the same audio
or approved candidate text. They do not represent new child input attempts. Session-level
combined failure counts and text-input suggestions after three failed inputs are still separate
work. A speech-only TTS failure returns approved text with null audio; overall budget expiration
returns the deadline notice instead. Input and synthesized audio are transient in this pipeline;
actual uploaded-resource cleanup, explicit byte-buffer destruction, recording-duration validation,
checkpoints, API/audio representation, production speech adapters and data-retention jobs are
not implemented here. The caller must provide resource ownership and storage enforcement.

## Disposable upload and checkpoint boundary

RoleplayTurnResponder.executeVoice owns the RoleplayAudioResource through try-with-resources,
including provider errors, input rejection and deadline expiration. TemporaryRoleplayAudioFile
is an infrastructure adapter for an exclusively owned application-created upload spool. It
rejects non-regular files/symlinks, bounds reads to the provider request limit, clears its read
buffer, and removes the owned file on close. This is not a session-retention copy. DEC-008 allows
development retention and temporary production retention through the roleplay session; these
retention stores, end-of-session deletion and abandoned-input jobs are separate work. In-flight
provider request copies and immutable strings cannot be forcibly erased by this adapter.

RoleplayCheckpointCommand can only be built from SpokenReady, whose Ready value enforces the
candidate/evaluation delivery gate. It contains the approved candidate/goal/support/text and
canonical utterance with caller-supplied session version and turn number; it contains no source
input, synthesized audio or rejected candidate. Its log representation redacts all content.
The canonical text remains subject to DEC-039 completion/24-hour deletion.

RoleplayCheckpointStore defines atomic approved-turn/session-checkpoint/idempotency-result
commit, authorization and processing ownership, expected-version/turn checks, and expiry/cancel
fencing. Committed/replayed, conflict and expired results are explicit. The PostgreSQL adapter and commit-before-response path are described below. Resource tests
verify cleanup and command preparation; integration tests verify database atomicity, replay and
concurrency.

## PostgreSQL checkpoint adapter

V7 adds roleplay_session and approved-only roleplay_turn. Sessions bind activity/child with a
composite FK; the final checkpoint references a turn from the same session using a deferred FK.
A unique session/idempotency-key result reference is part of the approved turn itself, not a
separate cache containing duplicate utterances. Correlation requestId and caller-supplied
idempotencyKey are distinct; inputFingerprint must be a server-computed SHA-256 value.
No policy value for cache TTL, turn limits or processing leases is introduced.

JpaRoleplayCheckpointStore uses a short REQUIRES_NEW transaction and pessimistic session lock.
It checks child/activity/scenario scope, then replays a matching key/fingerprint before checking
the expected session version and next turn number. An approved turn and checkpoint version
advance commit together; a constraint failure or expiry before the final flush check rolls both
back. PostgreSQL lock/statement timeouts are derived from the remaining shared deadline. The
precise policy boundary between the final in-transaction deadline check and physical COMMIT
completion remains subject to the existing DEC-010 boundary decision; this is not a distributed
commit-time cancellation guarantee.

RoleplayCheckpointedTurnExecutor executes AI outside the transaction and returns success only
after commit. Replays discard newly generated candidate/audio and return the original stored
approved candidate/text without copying canonical text into a second result cache. Its voice
entry point owns upload cleanup through the commit/error paths.

PostgreSQL integration tests verify atomicity/rollback, same-key replay, input-key conflict,
stale version/turn conflict, foreign-child scope, concurrent requests and lock-wait expiry.
The API authentication/start-session/scenario lookup, HTTP wiring, goal-evidence updates, activity/reward completion and source-input session
retention stores are still not connected. Canonical-text cleanup is implemented below. The ephemeral request guard described below now prevents
normal duplicate execution before external calls; API contracts and failure recovery remain
separate work.

## Ephemeral processing guard before external calls

RoleplayCheckpointedTurnExecutor now requires RoleplayRequestGuard. The worker acquires ownership
before confirmed-result lookup, STT/input processing/LLM/TTS and commit. Processing returns without
starting a duplicate pipeline; a confirmed key/fingerprint returns the stored response directly.
Conflicting input or foreign session scope is rejected before the pipeline. The existing commit
constraints remain the final persistence fence for concurrent different request keys.

PostgresRoleplayRequestGuard uses a session advisory lock on an autocommit connection, keyed by
a domain-separated 64-bit SHA-256 digest of session/key. No PROCESSING/event row or input fingerprint
is stored before input safety decisions. A digest collision can serialize unrelated requests but
cannot replay another user's data because confirmed-result lookup still checks scope and key.
Ownership is released explicitly on worker exit or by PostgreSQL on connection end; there is no
TTL, automatic takeover or invented processing-lease duration. See PostgreSQL 18 advisory locks:
https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS

The injected Hikari pool must already be configured with at least two connections. Per-process
processing slots are derived as floor(maximumPoolSize/2), reserving connections for lookup/commit;
slot exhaustion returns CapacityUnavailable separately from duplicate Processing. Each active
worker holds an advisory connection but no long DB transaction. Deployment must size aggregate
connection capacity across application instances. Invalid release aborts the physical connection
to prevent a pooled session from retaining a lock.

The whole guarded unit runs inside the bounded runner. On caller timeout/interruption, an
uncooperative provider's worker still owns its lease until it actually exits; late results cannot
commit. A provider that never returns can retain its slot/connection, requiring operational
termination rather than an unagreed takeover policy. If the database connection itself fails,
PostgreSQL releases the lease; persistence version/key constraints still prevent duplicate writes,
but exactly-once external provider execution across that failure requires provider idempotency
contracts and is not guaranteed by advisory locking.

PostgreSQL tests cover one pipeline invocation for simultaneous duplicates, zero additional
invocations on confirmed replay, timeout ownership retention, shared locks across guard instances,
release after pipeline failure, key/scope rejection before execution and reserved pool capacity.

## Canonical text deletion (DEC-039)

RoleplayCanonicalRetentionService reuses the existing application Clock. Normal completion
locks the authenticated child/activity session and sets COMPLETED plus nulls all canonical
utterances in the same REQUIRED transaction; deletion errors roll back completion. It can join
a future activity/reward/record completion transaction, but this is not that full completion API.
Never use this normal-completion entry point to persist a dangerous-input event.

The cleanup service selects COMPLETED sessions or sessions with last_activity_at at or before
now minus exactly 24 hours, only when canonical text remains. PostgreSQL FOR UPDATE SKIP LOCKED
uses the same session lock as checkpoint commit. The locked eligibility is rechecked, and only
canonical_utterance is nulled; response, goal/support focus, checkpoint, fingerprint and key
references remain. Cleanup does not mark incomplete sessions completed or grant rewards.

RoleplayCanonicalRetentionJob is registered by default. Its fixed delay/initial delay default
to 60000ms and are operational cadence, not the 24-hour policy value; configure with
roleplay.retention.cleanup-delay-ms and roleplay.retention.cleanup-enabled. Physical deletion
of expired incomplete text happens on the next successful sweep; lock contention/outages and
the configurable interval can delay it. Normal completion deletes within the completion commit.
last_activity_at currently advances on committed turns and normal completion; input/hint/pause
API activity semantics still need their own integration. Already constructed immutable in-memory
responses and client copies are outside this DB cleanup; replay responses contain no canonical text.

PostgreSQL tests cover immediate and repeated completion, exactly-24-hour eligibility, younger
active sessions, paused/completed cleanup, rollback on deletion failure, foreign-child rejection,
replay after deletion without regeneration, locked recent activity, completion racing late AI
results, parallel cleanup and scheduler registration. Source audio/STT session retention remains
a distinct DEC-008 store/lifecycle contract.


## Server request scope (API entry prerequisite)

RoleplayRequestScopeService.load requires a child ID obtained from verified authentication;
no controller accepts a child ID in a request body for this service. It reads active child,
owned activity and owned session, rejects activity/session scenario mismatch, and returns only
server identifiers and a checkpoint snapshot. Scope.trace constructs the AI trace without
client-supplied scenario, class or child identifiers. No names or utterances are read into Scope.
This read-only transaction does not hold session locks during external AI calls or advance state.

Scope.acceptsNewTurn is a snapshot flag, not authorization to commit. Both activity and session
must currently be IN_PROGRESS for this flag. Closed sessions still load so confirmed idempotent
replay can be checked before denying a new turn. The eventual entry service must check replay
first, then this flag; existing checkpoint version/status validation remains required, and
activity lifecycle concurrency still needs to be wired at the entry/commit boundary.

Correction: baseline c644dc2 already contains ChildPrincipal, ChildAccessSessionController and
server-session authentication. Earlier notes incorrectly attributed that prerequisite only to
PR #41. It is reused without reimporting or modifying child login/security configuration.
The baseline and locally fetched PR #41 still provide no approved scenario content source.
This scope service is not an HTTP endpoint or a complete prompt-context loader. Approved scenario
facts, goal progress/counters, policy limits and recent retained dialogue remain source contracts.
PostgreSQL tests cover owned scope/trace, unchanged checkpoint, foreign child/activity/session,
inactive children, changed/unassigned scenario and replay-compatible closed-state snapshots.


## Approved context assembly boundary

RoleplayContextSource is a required trusted server port, with no production implementation or
Spring bean default. It supplies approved scenario/version provenance, learning progress,
explicit policy limits and retained chronological dialogue for one child/activity/goal/session
and checkpoint version. A future adapter must actually validate approval and read a coherent
checkpoint snapshot; echoing the requested key is not evidence of matching stored state.
An approvalReference is provenance supplied by that trusted adapter, not an authorization token.

RoleplayContextAssembler.prepareVoice is intended to run inside the existing bounded worker,
after processing ownership and confirmed replay lookup. It denies new work for a closed scope,
loads under the shared deadline, checks all snapshot identity/version fields and checks the
current turn number (last committed turn + 1), goal and support against the session snapshot.
The first turn may obtain initial goal/support from the trusted source if the session has no
focus yet. Subsequent turns cannot invent missing focus. Missing data or inconsistent state
cannot produce an input; limits are supplied explicitly, with no production 6/4 defaults.

Prepared contains a server-derived trace and RoleplayTurnInput with an empty learner text.
The speech pipeline replaces that text only after STT and required input/safety processing.
Existing RoleplayTurnInput trims dialogue to the latest eight lines. Snapshot/Prepared string
representations exclude scenario and dialogue content. No state is written by assembly.
Preparation does not initialize a roleplay session or update goals/support counters.

Tests exercise cross-scope/version rejection, focus/turn consistency, source absence,
initial focus, explicit policy preservation, cancelled/late reads, immutable recent dialogue
and redacted representations. A test feeds the assembled input through the real structured
prompt/parser/retry pipeline with fake speech/LLM providers; ownership reads use PostgreSQL.
Production source storage and roleplay HTTP/controller wiring remain unconnected; existing
child-session authentication is reused, and guarded entry orchestration is implemented below. This boundary does not establish a live approved-scenario/provider E2E.


## Guarded scoped voice execution

RoleplayScopedVoiceTurnExecutor joins the scoped server snapshot, context assembler and speech
pipeline through the existing RoleplayCheckpointedTurnExecutor. It is an internal entry object,
not a controller or default Spring bean. The caller must supply Scope from authenticated server
lookup, server-issued request/turn IDs and an owned disposable upload. The scoped entry now computes
its fingerprint from one immutable input snapshot; it still does not authenticate a UUID.

The scoped order is upload snapshot/fingerprint preparation, processing ownership, confirmed
lookup, context assembly, STT/input processing, structured analysis/generation/evaluation,
approved TTS, checkpoint commit and response. The existing processing lease stays alive through assembly and commit. A confirmed
replay, conflicting fingerprint, stale checkpoint or already-running duplicate never reaches
context lookup or STT; upload reading is now needed first to compute the server fingerprint. The upload closes on all exits, including failures before
the callback. The speech request trace must equal the server-prepared trace before STT starts.

Expected checkpoint version and next turn number are derived from the Scope snapshot; the DB
lookup and commit still reject stale versions. Completed replay remains possible because the
assembler's new-turn status gate is only called for an absent result. Source absence, closed
activity and wrong upload trace propagate an internal failure, release processing ownership and
cannot commit a turn. This does not replace activity lifecycle validation at final commit; an
activity status change during an AI call remains a future entry/commit integration concern.

Eight PostgreSQL integration tests connect real ownership lookup, advisory guard and transactional
checkpoint writes to fake approved-context, input-safety and speech/LLM sources. They cover the
complete successful order, completion/deletion replay, fingerprint conflict, unavailable-context
retry, stale snapshots, closed activity, upload trace mismatch and simultaneous duplicates while
context lookup is blocked. The related 93-test run passed. This is not a live vendor or roleplay HTTP E2E. The response DTO and server fingerprint were
subsequently implemented below; endpoint, approved source and FE wiring remain unconnected.


## Proposed child response projection

RoleplayTurnResponseMapper converts checkpoint execution Result into an explicit record DTO.
This is a proposed public projection, not an agreed endpoint/status code or the draft asynchronous
202 acceptance response. Endpoint timing, polling and FE field contracts remain unconnected.
RoleplayDeliveryStatus/Action live outside dto to preserve the repository rule that dto classes
are records; no automatic retry, new key generation or polling interval is implied by actions.

Committed fresh spoken responses expose the approved response text and validated canonical
acceptedInput, plus checkpointVersion. Replay exposes the saved approved response/version and
replayed=true, without canonical input or audio regeneration. Recovery exposes only an approved
child notice and action, never a learning result or checkpoint. SafetyStop omits the teacher
notice; delivery of that separate teacher notice is not implemented by this child mapper.
Missing notices return an empty messages list and retain the required action, including STOP.
Conflict/processing/capacity cannot be serialized as delivered. Inconsistent internal envelopes
(e.g. recovery inside Success, spoken replay or version zero) fail closed.

RoleplaySpeechDelivery is a trusted request-scoped port for an approved candidate's transient
speech resource URL. No implementation, resource retention TTL, vendor storage or access scheme
is supplied. A real adapter must enforce the authenticated recipient's access and return an
empty Optional for expected publication unavailability. Unexpected adapter errors propagate;
the committed checkpoint remains available for replay. No TTS or publication is attempted for
replay or recovery, and no URL is fabricated when speech/publication is absent. This boundary
must not publish audio before successful checkpoint commit or expose provider credentials.

Public JSON contains only status, action, messages(text and speech available/url), acceptedInput,
checkpointVersion and replayed. It excludes candidate/analysis/evaluation details, assessment,
retention and notice-approval metadata, teacher text, raw speech/STT and provider/model/voice
metadata or audio bytes. DTO log representations also redact text and resource URLs. Standard
acceptedInput can still be in the first authorized success response as required by chat UX;
DB canonical cleanup and replay do not retain/reconstruct client copies.

Fourteen mapper tests plus real-checkpoint success/replay projection assertions verify the above.
The related 52-test run passed, with real PostgreSQL and fake approved-context, safety, AI and
speech delivery sources. Roleplay controller/security route, public upload validation, real approved
content and audio resource publication remain production integration work. FE is unchanged.


## Server voice fingerprint and single snapshot

RoleplayScopedVoiceTurnExecutor no longer accepts a caller fingerprint. It prepares RoleplayVoiceInput
inside the same bounded worker as the guard/lookup/AI/commit path. RoleplayCheckpointedTurnExecutor
executePrepared shares the existing ownership routine, without nested executor submissions that
would deadlock a single-worker pool. The shared deadline is checked before/after resource read and
after hashing; expired/cancelled preparation cannot acquire a guard or commit. Upload ownership
remains with the scoped entry's try-with-resources on every exit.

RoleplayVoiceInput captures a validated SpeechTranscriptionRequest once, validates the complete
server trace and initial attempt, then hashes UTF-8 "neuringo:voice-input:v1" + NUL + canonical
AudioFormat.name() + NUL + exact audio bytes using SHA-256 (lowercase 64 hex). Request/turn IDs,
checkpoint versions and key are excluded so fresh retry trace IDs do not change the fingerprint;
session/child authorization and key scope remain enforced separately. MIME aliases normalize
through the existing AudioFormat parser. This is exact upload identity, not waveform/semantic
normalization: re-encoding/re-recording the same spoken words produces a different input.

The exact captured request is later passed to STT; there is no second file read that could observe
changed bytes. Local temporary digest bytes are zeroed; the immutable request/provider copies
are not claimed forcibly erased. The fingerprint and raw payload are omitted from the input's
log representation. The existing prerequisite request's 2MB bound is unchanged and is not newly
ratified as the public API limit; MIME/size/duration endpoint policy remains unresolved.

Replay/processing/conflict still perform one upload read to establish actual input identity, then
skip context/STT/LLM/TTS when the result permits. Same-key changed bytes conflict before AI. Tests
cover a known digest vector, trace-independent retry, changed bytes/format, MIME aliases, immutable
payload, wrong trace/attempt, cancellation before read and cancellation during preparation. The
PostgreSQL scoped tests now submit actual changed bytes instead of hand-written fingerprints and
verify the provider receives the captured bytes. The related 84-test run passed. Public upload validation, roleplay controller/security route and real content/provider/audio
publication remain; existing child authentication is connected to scope lookup below.


## Existing child-session authentication connected to scope

RoleplayRequestScopeService.loadForChild consumes Authentication from Spring's verified security
context, never a body childId. It requires authenticated non-anonymous identity, a ChildPrincipal
with non-null child ID and ROLE_CHILD, then reuses the owned active-child/activity/session lookup.
Missing/anonymous/unauthenticated identity returns 401 AUTHENTICATION_REQUIRED; wrong identity or
role returns 403 ACCESS_DENIED. Error wording reuses existing security responses. Instructor
identity cannot become a child just by adding ROLE_CHILD. No repository lookup occurs for these
invalid identities. Child status and ownership are rechecked from the database on valid access.

Baseline c644dc2 already has child-code entry, ChildPrincipal and saved server SecurityContext;
this corrects the earlier missing-authentication assessment. None of those existing sources or
the global security filter/configuration was changed. The new method does not authorize a roleplay
HTTP route, persist a new login or change session expiration policy. The low-level UUID scope
method remains for trusted internal use; a future roleplay controller must call loadForChild.

Five PostgreSQL/MockMvc tests enter through the existing HTTP child API, use the resulting saved
session Authentication for scope lookup, deny cross-child resources, deny newly paused children,
reject an instructor with an added child authority and retain login CSRF enforcement. Three unit
tests check invalid identities fail before repository queries. Including scope/voice, local and
default security regressions and architecture, 63 tests passed. Roleplay upload policy/validation,
controller/security-route and real approved-context/provider/publication wiring remain.


## Explicit upload policy and disposable spool creation

RoleplayUploadPolicy requires a positive explicit byte cap and nonempty canonical format set,
with no production defaults. The cap must fit SpeechTranscriptionRequest's existing 2MB capacity;
this does not ratify that value as the public endpoint policy. RoleplayUploadSpoolFactory takes
an existing trusted server directory, a source stream, declared content type, server-issued trace
and shared deadline. No client filename/path or size metadata is accepted. Missing, unknown and
disallowed declared formats are rejected before creating a file. Canonical MIME aliases use the
existing AudioFormat parser; actual media/container/codec and duration are not validated here.

The source stream is owned/closed on every exit. A server-named temporary file is streamed with
at most cap+1 bytes consumed, without trusting a claimed length. Exact cap is accepted, one byte
over or empty input is rejected. Deadline checks bracket each read and run before ownership
transfer. Reads/writes/errors/cancellation remove partial files; even an input.close failure
cannot orphan the prepared file. Cleanup errors are propagated or suppressed onto the primary
failure rather than silently ignored. Buffer contents are cleared on exit. Success transfers
RoleplayAudioResource ownership to the existing scoped executor for final deletion.

Factory deadline checks cannot forcibly interrupt a blocked InputStream; HTTP/multipart ingress
still needs its own bounded-I/O and request-size integration. This factory is not an endpoint or
a DEC-008 retention store; its spool is disposable and independent of separately retained source
copies. Policy injection, directory configuration, decoder/duration validation, multipart binding
and error-to-HTTP mapping are still production ingress contracts. No automatic Spring bean or
public upload policy was added.

Tests verify exact-limit content and format, empty/oversized input, rejected declared MIME,
read/close errors, before/during-read cancellation, cap+1 consumption and explicit policy bounds.
A PostgreSQL scoped test now creates this real spool and sends it through server fingerprint,
fake speech/safety/LLM and real checkpoint commit, verifying file deletion after response.
The related 56-test run passed; FE and existing authentication/security configuration are unchanged.


## BE voice entry service

RoleplayVoiceInputService.submitVoice connects verified Spring Authentication, child-owned scope,
explicit upload policy/spooling, server-generated request/turn IDs, scoped fingerprint/guard/AI/
checkpoint execution and the proposed public response mapper. No body child ID, candidate ID,
fingerprint or caller file path is accepted. Ownership/authentication failures happen before
source read or temp-file creation. No transaction surrounds upload or external calls; the existing
scope read and checkpoint commit keep their own short transaction boundaries.

The entry takes ownership of InputStream, wrapping it so the source closes exactly once whether
scope validation, spool creation, execution or response mapping fails. The spool factory closes
the wrapper after capture; the executor owns/deletes the spool. Before/after preflight scope read,
and inside upload and execution, the caller's shared deadline is checked. Ordinary preflight
expiry maps to the approved timeout response. Expired now preserves suppressed cleanup errors;
when entry expiry carries a cleanup error it propagates rather than falsely reporting clean
recovery. Stack traces remain disabled for this control-flow exception.

Response publication runs after checkpoint commit and spool deletion. An unexpected publication
error cannot erase the committed result; the same key/payload can replay its saved text without
another provider or publication call. Expected absent speech publication still delivers text.
The publication adapter's own timeout/access/retention contract remains separate and unimplemented.

This service is explicitly assembled with required dependencies, with no default Spring bean,
controller route, CSRF matcher or fake production provider. Scope and spool preflight checks do
not forcibly interrupt blocked DB/InputStream IO; future HTTP ingress still needs bounded IO and
multipart/request-size limits. Deadline start and physical commit/publication boundaries are not
newly ratified. This is BE-only work; no FE, vendor accounts or deployment were changed.

Seven added PostgreSQL entry cases cover authenticated normal delivery, denied unread inputs,
oversize rejection, byte-based replay/conflict, preflight timeout, publication-failure replay and
expiry plus cleanup failure. Real file/DB/guard/prompt/parser paths use simulated authenticated
principals and fake context/input-safety/speech/LLM sources; the separate existing HTTP-login
scope tests verify the actual saved child principal. The related 76-test run passed.
Next BE unit: controller/security-route and public request/error binding, without FE changes.


## HTTP wiring, configuration and final state fence

RoleplayVoiceController exposes the synchronous voice route only with roleplay.http.enabled=true.
SecurityConfig reserves POST /api/v1/roleplay-sessions/*/voice-inputs for ROLE_CHILD and retains
session CSRF. The session-only service path derives activity from authenticated owned storage,
ignoring client identity/digest extras. Controlled upload/context errors use the shared error
envelope; conflict is 409, in-flight duplicate is 202 and saturated workers/DB capacity is 503.
No polling interval or new result-query endpoint was invented. Successful data is no-store.

RoleplayHttpConfiguration requires explicit upload/request byte limits, format set, spool path,
worker count/queue capacity and real provider/context/safety/publication beans. Missing interfaces
are not replaced with permissive or fake runtime beans. Properties are validated, the optional
turn budget defaults to 60 seconds and cannot exceed it, and workers use a bounded queue with
explicit rejection. Servlet MultipartConfigElement enforces transport/file/request limits before
controller binding; the spool factory independently checks actual file bytes. Only localhost
HTTP tests disable Secure session cookies; production cookie settings are unchanged.

Checkpoint commit now locks child, activity, then session and rechecks active child, running
owned activity and scenario identity before a new insert. State changes during AI execution
cannot produce a committed/returned new candidate. Existing exact replay is checked before
new-turn state restrictions. Parent locks are short and never held around providers; other
completion/lifecycle transactions locking both parent and session must keep the same order.

Seventeen MockMvc cases, two real localhost Tomcat multipart cases and six configuration startup
cases validate this wiring. The complete backend regression includes the original common API
contract cases. The temporary backend-only verification checkout initially omitted ../contracts;
copying the unchanged c644dc2 contract restored those cases. All production/test files edited in
this final unit are byte-identical to the verified files in the implementation checkout.

This finishes the requested BE HTTP/configuration/verification batch. It does not claim live
approved-content or safety/vendor/audio publication integration, FE wiring, source-retention
lifecycle, failure-count reset rules or other undecided task-wide release policy is complete.
