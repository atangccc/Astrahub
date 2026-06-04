export type PendingInboxRefreshCause = "review" | "cancel" | "delete" | "sync";

const PENDING_INBOX_REFRESH_CAUSES = new Set<PendingInboxRefreshCause>([
  "review",
  "cancel",
  "delete",
  "sync"
]);

const PENDING_INBOX_REFRESH_REALTIME_EVENTS = new Set([
  "friend_invitation_created",
  "friend_invitation_reviewed",
  "friend_invitation_cancelled",
  "friend_invitation_deleted"
]);

export function shouldRefreshPendingInboxCountAfterManagerAction(
  cause: PendingInboxRefreshCause
) {
  return PENDING_INBOX_REFRESH_CAUSES.has(cause);
}

export function shouldRefreshPendingInboxCountAfterRealtimeEvent(eventType: string) {
  return PENDING_INBOX_REFRESH_REALTIME_EVENTS.has(String(eventType || "").trim());
}
