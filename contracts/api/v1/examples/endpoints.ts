// 구현 함수 42개·조건부 음성·텍스트 입력 함수 2개. 2026-11-06 develop f017637 + PR #90 리포트·추천 기준.
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export type GetCsrfArgs = Record<string, never>;

export function getCsrf(
  api: ApiClient,
  _args: GetCsrfArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.CsrfData>> {
  return api.getCsrf(options.signal);
}

export interface SignupArgs {
  body: T.SignupBody;
}

export function signup(
  api: ApiClient,
  args: SignupArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.UserData>> {
  return api.request<T.UserData>("/api/v1/users", {
    method: "POST",
    auth: "public",
    expectedStatuses: [200],
    signal: options.signal,
    body: args.body
  });
}

export interface LoginArgs {
  body: T.LoginBody;
}

export function login(
  api: ApiClient,
  args: LoginArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.LoginData>> {
  return api.request<T.LoginData>("/api/v1/auth/sessions", {
    method: "POST",
    auth: "public",
    expectedStatuses: [200],
    signal: options.signal,
    body: args.body
  });
}

export type GetInstructorSessionArgs = Record<string, never>;

export function getInstructorSession(
  api: ApiClient,
  _args: GetInstructorSessionArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.SessionData>> {
  return api.request<T.SessionData>("/api/v1/auth/session", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export type LogoutInstructorArgs = Record<string, never>;

export function logoutInstructor(
  api: ApiClient,
  _args: LogoutInstructorArgs = {},
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/auth/session", {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal
  });
}

export interface WithdrawInstructorArgs { body: T.WithdrawalBody; }
export function withdrawInstructor(api: ApiClient, args: WithdrawInstructorArgs, options: CallOptions = {}): Promise<void> {
  return api.requestEmpty("/api/v1/users/me/withdrawal", {
    method: "POST", auth: "instructor", expectedStatuses: [204], signal: options.signal, body: args.body
  });
}

export interface IssueChildCodeArgs {
  childId: T.UUID;
  idempotencyKey: T.UUID;
}

export function issueChildCode(
  api: ApiClient,
  args: IssueChildCodeArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.AccessCodeData>> {
  return api.request<T.AccessCodeData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/access-codes", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}

export interface EnterChildArgs {
  body: T.ChildEntryBody;
}

export function enterChild(
  api: ApiClient,
  args: EnterChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildEntryData>> {
  return api.request<T.ChildEntryData>("/api/v1/child-access-sessions", {
    method: "POST",
    auth: "public",
    expectedStatuses: [201],
    signal: options.signal,
    body: args.body
  });
}

export type ExitChildArgs = Record<string, never>;

export function exitChild(
  api: ApiClient,
  _args: ExitChildArgs = {},
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/child-access-sessions/current", {
    method: "DELETE",
    auth: "child",
    expectedStatuses: [204],
    signal: options.signal
  });
}

export interface CreateClassroomArgs {
  body: T.ClassBody;
}

export function createClassroom(
  api: ApiClient,
  args: CreateClassroomArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassData>> {
  return api.request<T.ClassData>("/api/v1/classrooms", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    body: args.body
  });
}

export type ListClassroomsArgs = Record<string, never>;

export function listClassrooms(
  api: ApiClient,
  _args: ListClassroomsArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassData[]>> {
  return api.request<T.ClassData[]>("/api/v1/classrooms", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetClassroomArgs {
  classId: T.UUID;
}

export function getClassroom(
  api: ApiClient,
  args: GetClassroomArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassData>> {
  return api.request<T.ClassData>("/api/v1/classrooms/" + encodeURIComponent(args.classId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface DeleteClassroomArgs {
  classId: T.UUID;
}

export function deleteClassroom(
  api: ApiClient,
  args: DeleteClassroomArgs,
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/classrooms/" + encodeURIComponent(args.classId), {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal,
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
    body: args.body
  });
}

export interface CreateChildArgs {
  classId: T.UUID;
  body: T.ChildBody;
}

export function createChild(
  api: ApiClient,
  args: CreateChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildData>> {
  return api.request<T.ChildData>("/api/v1/classrooms/" + encodeURIComponent(args.classId) + "/children", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    body: args.body
  });
}

export interface ListChildrenArgs {
  classId: T.UUID;
}

export function listChildren(
  api: ApiClient,
  args: ListChildrenArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildData[]>> {
  return api.request<T.ChildData[]>("/api/v1/classrooms/" + encodeURIComponent(args.classId) + "/children", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetChildArgs {
  childId: T.UUID;
}

export function getChild(
  api: ApiClient,
  args: GetChildArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ChildData>> {
  return api.request<T.ChildData>("/api/v1/children/" + encodeURIComponent(args.childId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
  });
}

export interface DeleteChildArgs {
  childId: T.UUID;
}

export function deleteChild(
  api: ApiClient,
  args: DeleteChildArgs,
  options: CallOptions = {}
): Promise<void> {
  return api.requestEmpty("/api/v1/children/" + encodeURIComponent(args.childId), {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [204],
    signal: options.signal,
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
    body: args.body
  });
}

export interface CreateLearningGoalArgs {
  childId: T.UUID;
  body: T.GoalBody;
}

export function createLearningGoal(
  api: ApiClient,
  args: CreateLearningGoalArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData>> {
  return api.request<T.GoalData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/learning-goals", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201],
    signal: options.signal,
    body: args.body
  });
}

export interface ListLearningGoalsArgs {
  childId: T.UUID;
}

export function listLearningGoals(
  api: ApiClient,
  args: ListLearningGoalsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData[]>> {
  return api.request<T.GoalData[]>("/api/v1/children/" + encodeURIComponent(args.childId) + "/learning-goals", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetLearningGoalArgs {
  goalId: T.UUID;
}

export function getLearningGoal(
  api: ApiClient,
  args: GetLearningGoalArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.GoalData>> {
  return api.request<T.GoalData>("/api/v1/learning-goals/" + encodeURIComponent(args.goalId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface CreateActivityArgs {
  body: T.ActivityBody;
  idempotencyKey: T.UUID;
}

export function createActivity(
  api: ApiClient,
  args: CreateActivityArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityData>> {
  return api.request<T.ActivityData>("/api/v1/activities", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [201, 200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface ListChildActivitiesArgs {
  childId: T.UUID;
  auth: "instructor" | "child";
  query?: {
    status?: T.ActivityStatus;
    page?: number;
    size?: number;
  };
}

export function listChildActivities(
  api: ApiClient,
  args: ListChildActivitiesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityData[]>> {
  return api.request<T.ActivityData[]>("/api/v1/children/" + encodeURIComponent(args.childId) + "/activities" + queryString(args.query), {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface ListClassroomActivitiesArgs {
  classId: T.UUID;
  query?: {
    page?: number;
    size?: number;
  };
}

export function listClassroomActivities(
  api: ApiClient,
  args: ListClassroomActivitiesArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ClassroomActivityData[]>> {
  return api.request<T.ClassroomActivityData[]>("/api/v1/classrooms/" + encodeURIComponent(args.classId) + "/activities" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetActivityArgs {
  activityId: T.UUID;
}

export function getActivity(
  api: ApiClient,
  args: GetActivityArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ActivityDetailData>> {
  return api.request<T.ActivityDetailData>("/api/v1/activities/" + encodeURIComponent(args.activityId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
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
): Promise<T.ApiResponse<T.ActivityStatusData>> {
  return api.request<T.ActivityStatusData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/status", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface ListAssignedQuizItemsArgs {
  activityId: T.UUID;
  auth: "instructor" | "child";
}

export function listAssignedQuizItems(
  api: ApiClient,
  args: ListAssignedQuizItemsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.AssignedQuizData[]>> {
  return api.request<T.AssignedQuizData[]>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/quiz-items", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface PutQuizAttemptArgs {
  activityQuizId: T.UUID;
  body: T.QuizAttemptBody;
}

export function putQuizAttempt(
  api: ApiClient,
  args: PutQuizAttemptArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizAttemptData>> {
  return api.request<T.QuizAttemptData>("/api/v1/activity-quiz-items/" + encodeURIComponent(args.activityQuizId) + "/attempt", {
    method: "PUT",
    auth: "child",
    expectedStatuses: [201, 200],
    signal: options.signal,
    body: args.body
  });
}

export interface GetQuizAttemptArgs {
  activityQuizId: T.UUID;
  auth: "instructor" | "child";
}

export function getQuizAttempt(
  api: ApiClient,
  args: GetQuizAttemptArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizAttemptData>> {
  return api.request<T.QuizAttemptData>("/api/v1/activity-quiz-items/" + encodeURIComponent(args.activityQuizId) + "/attempt", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface IssueQuizHintArgs {
  activityQuizId: T.UUID;
  idempotencyKey: T.UUID;
}

export function issueQuizHint(
  api: ApiClient,
  args: IssueQuizHintArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizHintData>> {
  return api.request<T.QuizHintData>("/api/v1/activity-quiz-items/" + encodeURIComponent(args.activityQuizId) + "/hints", {
    method: "POST",
    auth: "child",
    expectedStatuses: [201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}

export interface GetQuizResultArgs {
  activityId: T.UUID;
  auth: "instructor" | "child";
}

export function getQuizResult(
  api: ApiClient,
  args: GetQuizResultArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.QuizResultData>> {
  return api.request<T.QuizResultData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/quiz-result", {
    method: "GET",
    auth: args.auth,
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface SubmitVoiceInputArgs {
  sessionId: T.UUID;
  body: T.VoiceInputBody;
  idempotencyKey: T.UUID;
}

export function submitVoiceInput(
  api: ApiClient,
  args: SubmitVoiceInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayVoiceData>> {
  const form = new FormData();
  form.set("audio", args.body.audio);
  return api.request<T.RoleplayVoiceData>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/voice-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 202],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    conditional: true,
    form
  });
}

export interface SubmitTextInputArgs {
  sessionId: T.UUID;
  body: T.TextInputBody;
  idempotencyKey: T.UUID;
}

export function submitTextInput(
  api: ApiClient,
  args: SubmitTextInputArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayVoiceData>> {
  return api.request<T.RoleplayVoiceData>("/api/v1/roleplay-sessions/" + encodeURIComponent(args.sessionId) + "/text-inputs", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 202],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    conditional: true,
    body: args.body
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
): Promise<T.ApiResponse<T.RoleplayStartData>> {
  return api.request<T.RoleplayStartData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/roleplay-sessions", {
    method: "POST",
    auth: "child",
    expectedStatuses: [200, 201],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey
  });
}

export interface PrepareScenarioArgs {
  activityId: T.UUID;
  body: T.ScenarioReviewBody;
  idempotencyKey: T.UUID;
}

export function prepareScenario(
  api: ApiClient,
  args: PrepareScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioReviewData>> {
  return api.request<T.ScenarioReviewData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario-review", {
    method: "POST",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface ReviewScenarioArgs {
  activityId: T.UUID;
  body: T.ScenarioConfirmBody;
  idempotencyKey: T.UUID;
}

export function reviewScenario(
  api: ApiClient,
  args: ReviewScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioReviewClosure>> {
  return api.request<T.ScenarioReviewClosure>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario", {
    method: "PUT",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface DeleteScenarioArgs {
  activityId: T.UUID;
  body: T.ScenarioReviewCancelBody;
  idempotencyKey: T.UUID;
}

export function deleteScenario(
  api: ApiClient,
  args: DeleteScenarioArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioReviewClosure>> {
  return api.request<T.ScenarioReviewClosure>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/scenario-review", {
    method: "DELETE",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export type ListScenarioApprovalsArgs = Record<string, never>;

export function listScenarioApprovals(
  api: ApiClient,
  _args: ListScenarioApprovalsArgs = {},
  options: CallOptions = {}
): Promise<T.ApiResponse<T.ScenarioApprovalItem[]>> {
  return api.request<T.ScenarioApprovalItem[]>("/api/v1/scenario-approvals", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetLearningReportArgs {
  activityId: T.UUID;
}

export function getLearningReport(
  api: ApiClient,
  args: GetLearningReportArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.LearningReportData>> {
  return api.request<T.LearningReportData>("/api/v1/activities/" + encodeURIComponent(args.activityId) + "/learning-record", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

/** childId 와 classId 중 하나만 보낸다. 둘 다 있거나 둘 다 없으면 400 INVALID_REQUEST */
export interface ListLearningReportsArgs {
  query: {
    childId?: T.UUID;
    classId?: T.UUID;
    page?: number;
    size?: number;
  };
}

export function listLearningReports(
  api: ApiClient,
  args: ListLearningReportsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.LearningReportData[]>> {
  return api.request<T.LearningReportData[]>("/api/v1/learning-records" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface ListRecommendationsArgs {
  recordId: T.UUID;
}

export function listRecommendations(
  api: ApiClient,
  args: ListRecommendationsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RecommendationData[]>> {
  return api.request<T.RecommendationData[]>("/api/v1/learning-records/" + encodeURIComponent(args.recordId) + "/recommendations", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface GetRecommendationArgs {
  recommendationId: T.UUID;
}

export function getRecommendation(
  api: ApiClient,
  args: GetRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RecommendationData>> {
  return api.request<T.RecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}

export interface DecideRecommendationArgs {
  recommendationId: T.UUID;
  body: T.RecommendationDecisionBody;
  idempotencyKey: T.UUID;
}

export function decideRecommendation(
  api: ApiClient,
  args: DecideRecommendationArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RecommendationData>> {
  return api.request<T.RecommendationData>("/api/v1/recommendations/" + encodeURIComponent(args.recommendationId), {
    method: "PATCH",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal,
    idempotencyKey: args.idempotencyKey,
    body: args.body
  });
}

export interface GetRoleplayDifficultyArgs {
  childId: T.UUID;
}

export function getRoleplayDifficulty(
  api: ApiClient,
  args: GetRoleplayDifficultyArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.RoleplayDifficultyData>> {
  return api.request<T.RoleplayDifficultyData>("/api/v1/children/" + encodeURIComponent(args.childId) + "/roleplay-difficulty", {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
