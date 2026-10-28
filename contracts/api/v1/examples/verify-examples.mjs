import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import * as live from "./endpoints.ts";
import * as draft from "./draft-endpoints.ts";
import {
  createApiClient, ApiHttpError, ApiNetworkError, ApiContractError,
  DraftContractError, ConditionalContractError, queryString
} from "./api-client.ts";

const fixtures = JSON.parse(await readFile(new URL("./fixtures.json", import.meta.url), "utf8"));
const csrfFixture = fixtures.find(f => f.id === "API-IAM-01").response;
const envelope = data => ({ data, meta: { traceId: "90000000-0000-4000-8000-000000000001" } });
const responseOf = (value, status = 200) =>
  status === 204 ? new Response(null, { status }) :
    new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
function store(initial = "fake-instructor-token") {
  let token = initial;
  return { get: () => token, set: value => { token = value; }, clear: () => { token = null; } };
}
function argsOf(f) {
  const args = structuredClone(f.args);
  if (f.form) args.body.audio = new File(["MOCK_AUDIO"], "recording.webm", { type: "audio/webm" });
  return args;
}
let checks = 0;
async function check(name, fn) {
  await fn();
  checks++;
  process.stdout.write("PASS " + name + "\n");
}

for (const f of fixtures.filter(f => !f.unavailable)) {
  await check(f.id + " request/response mock", async () => {
    const calls = [];
    const client = createApiClient({
      origin: "https://mock.invalid", tokenStore: store(), allowDrafts: f.draft, allowConditional: f.conditional,
      fetchImpl: async (input, init) => {
        const url = new URL(String(input));
        calls.push({ url, init });
        assert.equal(init.credentials, "include");
        if (url.pathname === "/api/v1/csrf") {
          assert.equal(new Headers(init.headers).has("Authorization"), false);
          return responseOf(csrfFixture);
        }
        const expectedPath = f.route.replace(/\{(\w+)\}/g, (_, key) => f.args[key]);
        assert.equal(url.pathname, expectedPath);
        assert.equal(init.method, f.method);
        if (init.body && init.body instanceof FormData) assert.deepEqual([...init.body.keys()], ["audio"]);
        const headers = new Headers(init.headers);
        const mode = f.mode === "dual" ? f.args.auth : f.mode;
        assert.equal(headers.has("Authorization"), mode === "instructor");
        if (f.method !== "GET") assert.equal(headers.get("X-XSRF-TOKEN"), csrfFixture.data.token);
        if (f.key) assert.equal(headers.get("Idempotency-Key"), f.args.idempotencyKey);
        if (f.form) {
          assert.equal(headers.has("Content-Type"), false);
          assert.ok(init.body instanceof FormData);
          assert.ok(init.body.get("audio") instanceof File);
        } else if (f.args.body) {
          assert.deepEqual(JSON.parse(init.body), f.args.body);
          assert.match(headers.get("Content-Type"), /application\/json/);
        } else assert.equal(init.body, undefined);
        for (const [key, value] of Object.entries(f.args.query ?? {})) {
          assert.equal(url.searchParams.get(key), String(value));
        }
        return responseOf(f.response, f.status);
      }
    });
    const fn = (f.draft ? draft : live)[f.fn];
    assert.equal(typeof fn, "function");
    const result = await fn(client, argsOf(f));
    if (f.status === 204) assert.equal(result, undefined);
    else assert.deepEqual(result, f.response);
    assert.equal(calls.filter(c => c.url.pathname !== "/api/v1/csrf").length,
      f.id === "API-IAM-01" ? 0 : 1);
  });
}

for (const f of fixtures.filter(f => f.draft)) {
  await check(f.id + " blocked before network by default", async () => {
    let calls = 0;
    const client = createApiClient({
      origin: "https://mock.invalid", tokenStore: store(),
      fetchImpl: async () => { calls++; throw new Error("unexpected network"); }
    });
    await assert.rejects(() => draft[f.fn](client, argsOf(f)), DraftContractError);
    assert.equal(calls, 0);
  });
}

await check("Query preserves 0/false and omits undefined/null", async () => {
  assert.equal(queryString({ page: 0, enabled: false, status: undefined, ignored: null }),
    "?page=0&enabled=false");
});
await check("204 is never JSON parsed", async () => {
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async input => {
      if (String(input).endsWith("/csrf")) return responseOf(csrfFixture);
      const response = new Response(null, { status: 204 });
      response.json = async () => { throw new Error("204 parsed"); };
      return response;
    }
  });
  await live.logoutInstructor(client);
});
await check("401 clears instructor token and invokes app invalidation", async () => {
  const tokens = store();
  let invalidated = 0;
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: tokens,
    onInstructorUnauthorized: () => invalidated++,
    fetchImpl: async () => responseOf({
      error: { status: 401, code: "INVALID_TOKEN", message: "인증이 필요합니다.",
        path: "/api/v1/auth/session", traceId: "example-trace", fieldErrors: [] }
    }, 401)
  });
  await assert.rejects(() => live.getInstructorSession(client),
    error => error instanceof ApiHttpError && error.detail.code === "INVALID_TOKEN");
  assert.equal(tokens.get(), null);
  assert.equal(invalidated, 1);
});
await check("No automatic network retry for idempotent activity POST", async () => {
  let posts = 0;
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async (input, init) => {
      if (String(input).endsWith("/csrf")) return responseOf(csrfFixture);
      if (init.method === "POST") posts++;
      throw new TypeError("network lost");
    }
  });
  const f = fixtures.find(f => f.id === "API-ACT-01");
  await assert.rejects(() => live.createActivity(client, argsOf(f)), ApiNetworkError);
  assert.equal(posts, 1);
});
await check("CSRF error is not retried; manual same-key resend refreshes CSRF", async () => {
  let posts = 0, gets = 0;
  const keys = [];
  const hint = fixtures.find(f => f.id === "API-QUIZ-06");
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async (input, init) => {
      if (String(input).endsWith("/csrf")) { gets++; return responseOf(csrfFixture); }
      keys.push(new Headers(init.headers).get("Idempotency-Key"));
      if (++posts === 1) return responseOf({
        error: { status: 403, code: "CSRF_TOKEN_INVALID", message: "다시 확인해 주세요.",
          path: "/api/v1/example", traceId: "example-trace", fieldErrors: [] }
      }, 403);
      return responseOf(hint.response, 201);
    }
  });
  const args = argsOf(hint);
  await assert.rejects(() => live.issueQuizHint(client, args), ApiHttpError);
  assert.equal(posts, 1);
  await live.issueQuizHint(client, args);
  assert.equal(posts, 2);
  assert.equal(gets, 2);
  assert.deepEqual(keys, [args.idempotencyKey, args.idempotencyKey]);
});
await check("Unexpected success status raises contract error without retry", async () => {
  const f = fixtures.find(f => f.id === "API-CLS-01");
  let posts = 0;
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async (input, init) => {
      if (String(input).endsWith("/csrf")) return responseOf(csrfFixture);
      posts++;
      return responseOf(f.response, 201); // current contract is 200
    }
  });
  await assert.rejects(() => live.createClassroom(client, argsOf(f)), ApiContractError);
  assert.equal(posts, 1);
});
await check("Invalid JSON raises contract error", async () => {
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async () => new Response("not JSON", { status: 200 })
  });
  await assert.rejects(() => live.getInstructorSession(client), ApiContractError);
});
await check("Abort does not imply server cancellation and is propagated", async () => {
  const controller = new AbortController();
  controller.abort();
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(),
    fetchImpl: async () => { throw new DOMException("Aborted", "AbortError"); }
  });
  await assert.rejects(() => live.getInstructorSession(client, {}, { signal: controller.signal }),
    error => error.name === "AbortError");
});
await check("No token means no instructor HTTP call", async () => {
  let calls = 0;
  const client = createApiClient({
    origin: "https://mock.invalid", tokenStore: store(null),
    fetchImpl: async () => { calls++; throw new Error("unexpected network"); }
  });
  await assert.rejects(() => live.getInstructorSession(client), ApiContractError);
  assert.equal(calls, 0);
});
await check("Origin must not duplicate /api/v1", async () => {
  assert.throws(() => createApiClient({
    origin: "https://mock.invalid/api/v1", tokenStore: store()
  }), ApiContractError);
});


for (const f of fixtures.filter(f => f.conditional)) {
  await check(f.id + " conditional endpoint blocked separately from drafts", async () => {
    let calls = 0;
    const client = createApiClient({ origin: "https://mock.invalid", tokenStore: store(), allowDrafts: true,
      fetchImpl: async () => { calls++; throw new Error("unexpected network"); } });
    await assert.rejects(() => live[f.fn](client, argsOf(f)), ConditionalContractError);
    assert.equal(calls, 0);
  });
}
await check("Removed question-bank APIs are not exported", async () => {
  for (const fn of ["listQuizItems", "getQuizItem"]) {
    assert.equal(live[fn], undefined); assert.equal(draft[fn], undefined);
  }
});
await check("All 57 stable IDs have exactly one status and exported active functions", async () => {
  assert.equal(fixtures.length, 57);
  assert.equal(new Set(fixtures.map(f=>f.id)).size, 57);
  assert.equal(fixtures.filter(f=>f.unavailable).length, 2);
  assert.equal(fixtures.filter(f=>f.conditional).length, 1);
  assert.equal(fixtures.filter(f=>f.draft).length, 24);
  assert.equal(fixtures.filter(f=>!f.draft && !f.conditional && !f.unavailable).length, 30);
  const expectedLive = fixtures.filter(f=>!f.draft && !f.unavailable).map(f=>f.fn).sort();
  const expectedDraft = fixtures.filter(f=>f.draft).map(f=>f.fn).sort();
  assert.deepEqual(Object.keys(live).sort(), expectedLive);
  assert.deepEqual(Object.keys(draft).sort(), expectedDraft);
});
async function simulate(f, status, data=f.response, onRequest=()=>{}) {
  const client = createApiClient({origin:"https://mock.invalid",tokenStore:store(),allowConditional:true,
    fetchImpl: async (url,init) => {
      if(String(url).endsWith('/csrf')) return responseOf(csrfFixture);
      onRequest(init);
      return responseOf(data,status);
    }});
  return live[f.fn](client,argsOf(f));
}
await check("Activity create sends inline goal/type/key, not old goalId", async () => {
  const f=fixtures.find(f=>f.id==='API-ACT-01');
  await simulate(f,201,f.response,init=>{
    const body=JSON.parse(init.body);
    assert.equal(body.activityType,'FIRST_COMBINED');assert.ok(body.goal.title);
    assert.equal(Object.hasOwn(body,'goalId'),false);
    assert.equal(new Headers(init.headers).get('Idempotency-Key'),f.args.idempotencyKey);
  });
});
await check("Activity same-key replay 200 is accepted without new key", async () => {
  const f=fixtures.find(f=>f.id==='API-ACT-01');let key;
  const data=await simulate(f,200,f.response,init=>key=new Headers(init.headers).get('Idempotency-Key'));
  assert.equal(key,f.args.idempotencyKey);assert.deepEqual(data,f.response);
});
await check("Activity detail uses nested activity and null projections", async () => {
  const f=fixtures.find(f=>f.id==='API-ACT-03');const result=await simulate(f,200);
  assert.ok(result.data.activity.activityId);assert.ok(Array.isArray(result.data.quizItems));
  assert.equal(result.data.sessionSummary,null);assert.equal(result.data.learningRecord,null);
  assert.equal(result.data.activityId,undefined);
});
await check("Independent quiz request omits goal and response permits null goalId", async () => {
  const f=structuredClone(fixtures.find(f=>f.id==='API-ACT-01'));
  f.args.body={childId:f.args.body.childId,activityType:'SELF_EMOTION_QUIZ'};
  f.response.data.goalId=null;f.response.data.activityType='SELF_EMOTION_QUIZ';
  const result=await simulate(f,201,f.response,init=>assert.equal(Object.hasOwn(JSON.parse(init.body),'goal'),false));
  assert.equal(result.data.goalId,null);
});
await check("Signup requires true consent in transmitted fixture", async () => {
  const f=fixtures.find(f=>f.id==='API-IAM-02');await simulate(f,200,f.response,init=>{
    const body=JSON.parse(init.body);assert.equal(body.termsAgreed,true);assert.equal(body.privacyAgreed,true);
  });
});
await check("Withdrawal uses POST/password/204 and no JSON parsing", async () => {
  const f=fixtures.find(f=>f.id==='API-IAM-06');await simulate(f,204,null,init=>{
    assert.equal(init.method,'POST');assert.equal(JSON.parse(init.body).password,'example-password');
  });
});
await check("Category is present in current goal response", async () => {
  const f=fixtures.find(f=>f.id==='API-GOAL-03');const result=await simulate(f,200);
  assert.equal(Object.hasOwn(result.data,'category'),true);
});
await check("Voice completed 200 sends only audio and returns public projection", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-04');const result=await simulate(f,200,f.response,init=>{
    assert.deepEqual([...init.body.keys()],['audio']);assert.equal(new Headers(init.headers).has('Content-Type'),false);
  });
  assert.equal(result.data.status,'DELIVERED');assert.ok(result.data.messages[0].text);
  assert.equal(result.data.inputId,undefined);assert.equal(result.data.turnId,undefined);
});
await check("Voice duplicate in progress 202 is WAIT, not a turn lookup", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-04');
  const value=envelope({status:'PROCESSING',action:'WAIT',messages:[],acceptedInput:null,checkpointVersion:null,replayed:false});
  assert.deepEqual(await simulate(f,202,value),value);
});
await check("Voice confirmed replay is text only with no copied accepted input", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-04');const value=structuredClone(f.response);
  Object.assign(value.data,{acceptedInput:null,replayed:true});
  value.data.messages[0].speech={available:false,url:null};
  const result=await simulate(f,200,value);assert.equal(result.data.replayed,true);assert.equal(result.data.acceptedInput,null);
});
await check("Voice HTTP 200 can require reinput and is not necessarily delivered", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-04');
  const value=envelope({status:'REINPUT_REQUIRED',action:'REQUEST_NEW_INPUT',messages:[{text:'다시 한 번만 말해줄래?',speech:{available:false,url:null}}],acceptedInput:null,checkpointVersion:null,replayed:false});
  assert.equal((await simulate(f,200,value)).data.status,'REINPUT_REQUIRED');
});
await check("Voice conflict and busy use HTTP errors, not delivered DTOs", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-04');
  for(const [status,code] of [[409,'ROLEPLAY_CONFLICT'],[503,'ROLEPLAY_BUSY']]) {
    await assert.rejects(()=>simulate(f,status,{error:{status,code,message:'요청을 확인해 주세요.',path:f.route,traceId:'test',fieldErrors:[]}}),e=>e instanceof ApiHttpError&&e.detail.code===code);
  }
});
for (const f of fixtures.filter(f => !f.unavailable && f.expectedStatuses.length > 1)) {
  await check(f.id + " accepts every documented success status", async () => {
    for (const status of f.expectedStatuses) {
      const client=createApiClient({origin:'https://mock.invalid',tokenStore:store(),allowDrafts:true,allowConditional:true,
        fetchImpl:async url=>String(url).endsWith('/csrf')?responseOf(csrfFixture):responseOf(f.response,status)});
      assert.deepEqual(await (f.draft?draft:live)[f.fn](client,argsOf(f)),f.response);
    }
  });
}
await check("Activity detail uses instructor auth to match current security filter",async()=>{
  const f=fixtures.find(f=>f.id==='API-ACT-03');
  await simulate(f,200,f.response,init=>assert.equal(new Headers(init.headers).get('Authorization'),'Bearer fake-instructor-token'));
  assert.equal(f.mode,'instructor');
});
await check("Roleplay start is implemented and restored requests accept 200 with current checkpoint", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-01');
  assert.equal(draft.startRoleplay,undefined);
  const resumed=structuredClone(f.response);
  resumed.data.lastDeliveredTurnId='83000000-0000-4000-8000-000000000002';
  resumed.data.checkpointVersion=4;
  resumed.data.delivery.messages[0].messageId=resumed.data.lastDeliveredTurnId;
  resumed.data.delivery.messages[0].purpose='RESUME';
  const result=await simulate(f,200,resumed,init=>{
    assert.equal(init.body,undefined);
    assert.equal(new Headers(init.headers).has('Authorization'),false);
    assert.equal(new Headers(init.headers).get('Idempotency-Key'),f.args.idempotencyKey);
  });
  assert.equal(result.data.checkpointVersion,4);
  assert.equal(result.data.delivery.messages[0].purpose,'RESUME');
});
await check("Roleplay start preserves paused/completed empty delivery without generating a question", async () => {
  const f=fixtures.find(f=>f.id==='API-RPL-01');
  for(const status of ['PAUSED','COMPLETED']) {
    const stopped=structuredClone(f.response);
    stopped.data.status=status;stopped.data.activityStatus=status;
    stopped.data.delivery.messages=[];
    const result=await simulate(f,200,stopped);
    assert.equal(result.data.status,status);
    assert.deepEqual(result.data.delivery.messages,[]);
  }
});
// Portable GitHub documentation checks: no access to the backend checkout is needed.
const { readdir, access } = await import('node:fs/promises');
const root = new URL('../',import.meta.url);
const apiDocs=[];const allDocs=[];
for(const entry of await readdir(root,{withFileTypes:true})) {
  if(entry.isFile()&&entry.name.endsWith('.md')) allDocs.push(new URL(entry.name,root));
  if(entry.isDirectory()&&entry.name!=='examples') {
    for(const name of await readdir(new URL(entry.name+'/',root))) if(name.endsWith('.md')) {
      const url=new URL(entry.name+'/'+name,root);apiDocs.push(url);allDocs.push(url);
    }
  }
}
await check("57 API documents keep stable IDs and endpoint metadata",async()=>{
  assert.equal(apiDocs.length,57);
  const byId=new Map();
  for(const url of apiDocs) {
    const md=await readFile(url,'utf8');
    const id=md.match(/^# (API-[A-Z]+-\d{2}) · /)?.[1];
    assert.ok(id,url.pathname+' API ID');assert.ok(!byId.has(id),'duplicate '+id);
    byId.set(id,md);
  }
  for(const f of fixtures) {
    const md=byId.get(f.id);assert.ok(md,f.id);
    assert.ok(md.includes(f.route),f.id+' route');
    const expected=f.unavailable?'미제공':f.draft?'설계':f.conditional?'조건부 구현':'구현';
    assert.ok(md.includes('> '+expected+' —'),f.id+' state');
    if(!f.unavailable) {
      const mod=f.draft?'draft-endpoints.ts':'endpoints.ts';
      const text=await readFile(new URL(mod,import.meta.url),'utf8');
      const markers=[...text.matchAll(/^export (?:interface|type) (\w+)Args\b/gm)];
      const start=markers.findIndex(m=>m[1][0].toLowerCase()+m[1].slice(1)===f.fn);
      assert.notEqual(start,-1,f.fn+' args');
      const chunk=text.slice(markers[start].index,markers[start+1]?.index??text.length).trim();
      assert.ok(md.includes(chunk),f.id+' exact function snippet');
    }
    for(const block of md.matchAll(/~~~json\n([\s\S]*?)\n~~~/g)) JSON.parse(block[1]);
  }
});
await check("Repository documentation paths use lowercase kebab-case except README.md",async()=>{
  async function inspect(dir) {
    for(const entry of await readdir(dir,{withFileTypes:true})) {
      assert.ok(entry.name==='README.md'||/^[a-z0-9]+(?:-[a-z0-9]+)*(?:\.[a-z0-9]+)?$/.test(entry.name),entry.name);
      if(entry.isDirectory()) await inspect(new URL(entry.name+'/',dir));
    }
  }
  await inspect(root);
});
await check("Markdown relative links resolve within the uploadable v1 folder",async()=>{
  for(const url of allDocs) {
    const md=await readFile(url,'utf8');
    assert.ok(!md.includes('/Users/'),url.pathname+' local path');
    for(const match of md.matchAll(/\]\((?:<([^>]+)>|([^\s)]+))\)/g)) {
      const href=match[1]??match[2];if(/^(https?:|#|mailto:)/.test(href))continue;
      const target=new URL(href.split('#')[0],url);
      assert.ok(target.href.startsWith(root.href),url.pathname+' external relative link '+href);
      await access(target);
    }
  }
});
process.stdout.write("SUMMARY " + checks + " document/transport mock checks passed; no real server requests.\n");
