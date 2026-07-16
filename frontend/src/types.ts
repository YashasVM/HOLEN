export type AppUser = {
  id: string;
  name?: string;
  email?: string;
  github_username?: string;
  is_admin: boolean;
  is_owner: boolean;
  usage_limit_bytes: number;
  ingress_bytes: number;
  egress_bytes: number;
  used_bytes: number;
  remaining_bytes: number;
  created_at: string;
};
