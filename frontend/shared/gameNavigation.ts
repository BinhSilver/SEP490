/** A match's room is the authority for every "Về phòng" action. */
export function gameBackUrl(roomId: string | null | undefined, fallback = '/games'): string {
  return roomId ? `/games/room/${encodeURIComponent(roomId)}?fromMatch=1` : fallback;
}
