import { http } from './request';

export type ActivityTab = 'active' | 'upcoming' | 'ended';
export type TrendRange = 7 | 28;

export interface OverviewActivity {
  kind: 'EXAM' | 'PRACTICE';
  id: number;
  title: string;
  startAt: string | null;
  endAt: string | null;
  memberCount: number | null;
}

export interface ActivityGroup {
  total: number;
  items: OverviewActivity[];
}

export type OverviewActivities = Record<ActivityTab, ActivityGroup> & { asOf: string };

export interface SubmissionBucket {
  from: string;
  to: string;
  algoCount: number;
  dbCount: number;
}

export interface SubmissionTrend {
  asOf: string;
  buckets: SubmissionBucket[];
}

export const adminOverviewApi = {
  activities: (signal: AbortSignal) =>
    http.get<OverviewActivities>('/admin/overview/activities', { signal }),
  submissionTrend: (days: TrendRange, signal: AbortSignal) =>
    http.get<SubmissionTrend>('/admin/overview/submission-trend', { params: { days }, signal }),
};
