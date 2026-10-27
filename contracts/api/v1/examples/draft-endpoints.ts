// 설계·미구현 함수 26개. 2026-10-22 / 63e4314.
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export type GetChildSessionArgs = Record<string, never>;

export function getChildSession(
  api: ApiClient,
  _args: GetChildSessionArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildEntryData>> {
  return api.request<T.ChildEntryData>("/api/v1/child-access-sessions/current", {
    method: "GET",
    auth: "child",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface UpdateClassroomArgs {
  classId: T.UUID;
  body: T.ClassBody;
}

export function updateClassroom(
  api: ApiClient,
  args: UpdateClassroomArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassData>> {
  return api.request<T.ClassData>("/api/v1/classrooms/" + encodeURIComponent(args.classId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    body: args.body
  });
}

export interface UpdateChildArgs {
  childId: T.UUID;
  body: T.ChildBody;
}

export function updateChild(
  api: ApiClient,
  args: UpdateChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildData>> {
  return api.request<T.ChildData>("/api/v1/children/" + encodeURIComponent(args.childId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    body: args.body
  });
}

export interface UpdateLearningGoalArgs {
  goalId: T.UUID;
  body: T.DraftGoalPatch;
}

export function updateLearningGoal(
  api: ApiClient,
  args: UpdateLearningGoalArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData>> {
  return api.request<T.GoalData>("/api/v1/learning-goals/" + encodeURIComponent(args.goalId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    body: args.body
  });
}

export interface GetLearningGoalLineageArgs {
  goalId: T.UUID;
}

export function getLearningGoalLineage(
  api: ApiClient,
  args: GetLearningGoalLineageArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData[]>> {
  return api.request<T.GoalData[]>("/api/v1/learning-goals/" + encodeURIComponent(args.goalId) + "/lineage", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface GetActivityStatusArgs {
  activityId: T.UUID;
  auth: "instructor" | "child";
}

export function getActivityStatus(
  api: ApiClient,
  args: GetActivityStatusArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftActivityStatusData>> {
  return api.request<T.DraftActivityStatusData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/status", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface PrepareScenarioArgs {
  body: T.DraftScenarioBody;
  idempotencyKey: T.UUID;
}

export function prepareScenario(
  api: ApiClient,
  args: PrepareScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioPrepareData>> {
  return api.request<T.DraftScenarioPrepareData>("/api/v1/scenarios", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201, 202],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface ListScenariosArgs {
  query?: {
    classId?: T.UUID;
    goalId?: T.UUID;
    status?: string;
    scenarioLevel?: T.ScenarioLevel;
  };
}

export function listScenarios(
  api: ApiClient,
  args: ListScenariosArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioData[]>> {
  return api.request<T.DraftScenarioData[]>("/api/v1/scenarios" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface GetScenarioArgs {
  scenarioId: T.UUID;
}

export function getScenario(
  api: ApiClient,
  args: GetScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioData>> {
  return api.request<T.DraftScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ReviewScenarioArgs {
  scenarioId: T.UUID;
  body: T.DraftScenarioPatch;
}

export function reviewScenario(
  api: ApiClient,
  args: ReviewScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioData>> {
  return api.request<T.DraftScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    body: args.body
  });
}

export interface CreateScenarioVersionArgs {
  scenarioId: T.UUID;
  body: T.DraftScenarioVersionBody;
  idempotencyKey: T.UUID;
}

export function createScenarioVersion(
  api: ApiClient,
  args: CreateScenarioVersionArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftScenarioData>> {
  return api.request<T.DraftScenarioData>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId) + "/versions", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface DeleteScenarioArgs {
  scenarioId: T.UUID;
}

export function deleteScenario(
  api: ApiClient,
  args: DeleteScenarioArgs,
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId), {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal,
    draft: true
  });
}

export interface ListScenarioMicroGoalsArgs {
  scenarioId: T.UUID;
}

export function listScenarioMicroGoals(
  api: ApiClient,
  args: ListScenarioMicroGoalsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftMicroGoal[]>> {
  return api.request<T.DraftMicroGoal[]>("/api/v1/scenarios/" + encodeURIComponent(args.scenarioId) + "/sub-goals", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface StartRoleplayArgs {
  activityId: T.UUID;
  idempotencyKey: T.UUID;
}

export function startRoleplay(
  api: ApiClient,
  args: StartRoleplayArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRoleplaySession>> {
  return api.request<T.DraftRoleplaySession>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/roleplay-sessions", {
    method: "POST",
    auth: "child",
    expectedStatuses: [201],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey
  });
}

export interface GetRoleplaySessionArgs {
  sessionId: T.UUID;
  auth: "instructor" | "child";
}

export function getRoleplaySession(
  api: ApiClient,
  args: GetRoleplaySessionArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRoleplaySession>> {
  return api.request<T.DraftRoleplaySession>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId), {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface UpdateRoleplaySessionArgs {
  sessionId: T.UUID;
  body: T.DraftSessionPatch;
  idempotencyKey: T.UUID;
}

export function updateRoleplaySession(
  api: ApiClient,
  args: UpdateRoleplaySessionArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRoleplaySession>> {
  return api.request<T.DraftRoleplaySession>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId), {
    method: "PATCH",
    auth: "child",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface SubmitTextInputArgs {
  sessionId: T.UUID;
  body: T.DraftTextBody;
  idempotencyKey: T.UUID;
}

export function submitTextInput(
  api: ApiClient,
  args: SubmitTextInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftInputAccepted>> {
  return api.request<T.DraftInputAccepted>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/text-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [202],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface GetRoleplayTurnArgs {
  turnId: T.UUID;
  auth: "instructor" | "child";
}

export function getRoleplayTurn(
  api: ApiClient,
  args: GetRoleplayTurnArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftTurnResult>> {
  return api.request<T.DraftTurnResult>("/api/v1/turns/" + encodeURIComponent(args.turnId), {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ListRoleplayTurnsArgs {
  sessionId: T.UUID;
  auth: "instructor" | "child";
}

export function listRoleplayTurns(
  api: ApiClient,
  args: ListRoleplayTurnsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftTurnResult[]>> {
  return api.request<T.DraftTurnResult[]>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/turns", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ListRoleplayGoalStatusesArgs {
  sessionId: T.UUID;
}

export function listRoleplayGoalStatuses(
  api: ApiClient,
  args: ListRoleplayGoalStatusesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftGoalObservation[]>> {
  return api.request<T.DraftGoalObservation[]>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/goal-statuses", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ListSupportChangesArgs {
  sessionId: T.UUID;
}

export function listSupportChanges(
  api: ApiClient,
  args: ListSupportChangesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftSupportChange[]>> {
  return api.request<T.DraftSupportChange[]>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/support-level-changes", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface GetLearningReportArgs {
  activityId: T.UUID;
}

export function getLearningReport(
  api: ApiClient,
  args: GetLearningReportArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftReportData>> {
  return api.request<T.DraftReportData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/learning-record", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ListLearningReportsArgs {
  query: {
    childId: T.UUID;
    page?: number;
    size?: number;
  };
}

export function listLearningReports(
  api: ApiClient,
  args: ListLearningReportsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftReportData[]>> {
  return api.request<T.DraftReportData[]>("/api/v1/learning-records" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface ListRecommendationsArgs {
  recordId: T.UUID;
}

export function listRecommendations(
  api: ApiClient,
  args: ListRecommendationsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRecommendationData[]>> {
  return api.request<T.DraftRecommendationData[]>("/api/v1/learning-records/" + encodeURIComponent(args.recordId) + "/recommendations", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface GetRecommendationArgs {
  recommendationId: T.UUID;
}

export function getRecommendation(
  api: ApiClient,
  args: GetRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRecommendationData>> {
  return api.request<T.DraftRecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true
  });
}

export interface DecideRecommendationArgs {
  recommendationId: T.UUID;
  body: T.DraftRecommendationDecision;
  idempotencyKey: T.UUID;
}

export function decideRecommendation(
  api: ApiClient,
  args: DecideRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.DraftRecommendationData>> {
  return api.request<T.DraftRecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    draft: true,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}
