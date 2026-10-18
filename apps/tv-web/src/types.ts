export interface DeviceSession { sessionId: string; pin: string; pairingToken: string; expiresAt: string }
export interface PairingStatus { status: string; accessToken?: string; lanCapability?: string }
export interface Profile { id: string; name: string; pinSet: boolean }
export interface Source {
  kind?: string; originIdentity?: string; isPrivate: boolean; servingDeviceId?: string;
  availability?: string; archiveState?: string;
}
export interface Title {
  id: string; title: string; posterUrl?: string; backdropUrl?: string; overview?: string; year?: number;
  durationSeconds?: number; resumeSeconds?: number; mediaType: string; season?: number; episode?: number;
  episodeTitle?: string; inMyList: boolean; sources: Source[];
}
export interface Device { id: string; kind: string; revokedAt?: string }
export interface Playback {
  status: "ready" | "denied" | "preparing" | "unsupported";
  url?: string; headers: Record<string, string>; message?: string;
}
