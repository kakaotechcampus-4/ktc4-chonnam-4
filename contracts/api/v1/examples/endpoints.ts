// 현재 구현 함수 29개. 2026-10-22 / 63e4314.
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
