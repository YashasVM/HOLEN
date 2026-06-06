import { query, mutation, internalAction } from "./_generated/server";
import { v } from "convex/values";
import { getAuthUserId } from "@convex-dev/auth/server";
import { internal } from "./_generated/api";

declare const process: { env: Record<string, string | undefined> };

export const currentUser = query({
  handler: async (ctx) => {
    const userId = await getAuthUserId(ctx);
    if (!userId) return null;
    const user = await ctx.db.get(userId);
    if (!user) return null;
    return {
      _id: user._id,
      name: user.name,
      email: user.email,
      isApproved: user.isApproved ?? false,
      isAdmin: user.isAdmin ?? false,
      _creationTime: user._creationTime,
    };
  },
});

export const setupNewUser = mutation({
  handler: async (ctx) => {
    const userId = await getAuthUserId(ctx);
    if (!userId) throw new Error("Not authenticated");
    const user = await ctx.db.get(userId);
    if (!user) throw new Error("User not found");
    if (user.isApproved !== undefined) return;

    // Check if email is banned
    if (user.email) {
      const banned = await ctx.db
        .query("bannedEmails")
        .withIndex("email", (q) => q.eq("email", user.email!))
        .first();
      if (banned) throw new Error("This email address has been banned.");
    }

    const allUsers = await ctx.db.query("users").collect();
    const hasAdmin = allUsers.some((u) => u.isAdmin === true);
    await ctx.db.patch(userId, {
      isApproved: !hasAdmin,
      isAdmin: !hasAdmin,
    });
  },
});

export const listAllUsers = query({
  handler: async (ctx) => {
    const userId = await getAuthUserId(ctx);
    if (!userId) return [];
    const admin = await ctx.db.get(userId);
    if (!admin?.isAdmin) return [];
    const users = await ctx.db.query("users").order("desc").collect();
    return users.map((u) => ({
      _id: u._id,
      name: u.name,
      email: u.email,
      isApproved: u.isApproved ?? false,
      isAdmin: u.isAdmin ?? false,
      _creationTime: u._creationTime,
    }));
  },
});

export const setApproval = mutation({
  args: { userId: v.id("users"), approved: v.boolean() },
  handler: async (ctx, { userId, approved }) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    const target = await ctx.db.get(userId);
    await ctx.db.patch(userId, { isApproved: approved });
    // Send approval email notification
    if (approved && target?.email) {
      await ctx.scheduler.runAfter(0, internal.users.sendApprovalEmail, {
        email: target.email,
        name: target.name,
      });
    }
  },
});

export const sendApprovalEmail = internalAction({
  args: { email: v.string(), name: v.optional(v.string()) },
  handler: async (_ctx, { email, name }) => {
    const apiKey = process.env.AUTH_RESEND_KEY;
    if (!apiKey) return;
    const displayName = name || email.split("@")[0];
    await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        from: "HOLEN <onboarding@resend.dev>",
        to: [email],
        subject: "You've been approved — HOLEN",
        html: approvalEmail(displayName),
      }),
    });
  },
});

export const setAdmin = mutation({
  args: { userId: v.id("users"), isAdmin: v.boolean() },
  handler: async (ctx, { userId, isAdmin }) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    if (userId === adminId && !isAdmin)
      throw new Error("Cannot remove your own admin status");
    await ctx.db.patch(userId, { isAdmin });
  },
});

export const deleteUser = mutation({
  args: { userId: v.id("users") },
  handler: async (ctx, { userId }) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    if (userId === adminId) throw new Error("Cannot delete yourself");
    await ctx.db.delete(userId);
  },
});

export const banEmail = mutation({
  args: { email: v.string() },
  handler: async (ctx, { email }) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    const existing = await ctx.db
      .query("bannedEmails")
      .withIndex("email", (q) => q.eq("email", email))
      .first();
    if (existing) return; // already banned
    await ctx.db.insert("bannedEmails", {
      email,
      bannedAt: Date.now(),
      bannedBy: admin.email,
    });
    // Also revoke approval for any existing user with this email
    const user = await ctx.db
      .query("users")
      .withIndex("email", (q) => q.eq("email", email))
      .first();
    if (user) await ctx.db.patch(user._id, { isApproved: false });
  },
});

export const unbanEmail = mutation({
  args: { email: v.string() },
  handler: async (ctx, { email }) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    const record = await ctx.db
      .query("bannedEmails")
      .withIndex("email", (q) => q.eq("email", email))
      .first();
    if (record) await ctx.db.delete(record._id);
  },
});

export const listBannedEmails = query({
  handler: async (ctx) => {
    const userId = await getAuthUserId(ctx);
    if (!userId) return [];
    const admin = await ctx.db.get(userId);
    if (!admin?.isAdmin) return [];
    return ctx.db.query("bannedEmails").order("desc").collect();
  },
});

export const clearAllUsers = mutation({
  handler: async (ctx) => {
    const adminId = await getAuthUserId(ctx);
    if (!adminId) throw new Error("Not authenticated");
    const admin = await ctx.db.get(adminId);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    const allUsers = await ctx.db.query("users").collect();
    for (const u of allUsers) {
      await ctx.db.delete(u._id);
    }
  },
});

function approvalEmail(name: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Access Approved</title>
</head>
<body style="margin:0;padding:0;background:#0a0a0a;font-family:'DM Sans',Arial,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background:#0a0a0a;padding:40px 0;">
    <tr><td align="center">
      <table width="480" cellpadding="0" cellspacing="0" style="background:#111;border:2px solid #222;max-width:480px;width:100%;">

        <!-- Bauhaus header bar -->
        <tr>
          <td style="padding:0;">
            <table width="100%" cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#e63329;width:33.33%;height:6px;"></td>
                <td style="background:#1d7ce0;width:33.33%;height:6px;"></td>
                <td style="background:#f0c419;width:33.33%;height:6px;"></td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Logo row -->
        <tr>
          <td style="padding:32px 40px 0;">
            <table cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#e63329;width:36px;height:36px;text-align:center;vertical-align:middle;">
                  <span style="color:#fff;font-size:18px;font-weight:900;line-height:36px;">▼</span>
                </td>
                <td style="padding-left:12px;">
                  <div style="color:#fff;font-size:18px;font-weight:900;letter-spacing:0.08em;text-transform:uppercase;">HOLEN</div>
                  <div style="color:#666;font-size:11px;letter-spacing:0.12em;text-transform:uppercase;margin-top:1px;">Private Downloader</div>
                </td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Approved badge -->
        <tr>
          <td style="padding:28px 40px 0;">
            <table cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#1d7ce0;padding:4px 10px;">
                  <span style="color:#fff;font-size:10px;font-weight:700;letter-spacing:0.14em;text-transform:uppercase;">Access Granted</span>
                </td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Title -->
        <tr>
          <td style="padding:16px 40px 0;">
            <div style="color:#fff;font-size:22px;font-weight:700;line-height:1.3;">
              You're in,<br/>${name}.
            </div>
          </td>
        </tr>

        <!-- Body text -->
        <tr>
          <td style="padding:16px 40px 28px;">
            <div style="color:#888;font-size:14px;line-height:1.7;">
              An admin has approved your access to <strong style="color:#ccc;">HOLEN</strong>.<br/>
              You can now sign in and start downloading.
            </div>

            <!-- Accent line -->
            <table cellpadding="0" cellspacing="0" style="margin-top:24px;">
              <tr>
                <td style="background:#f0c419;width:32px;height:3px;"></td>
                <td style="background:#e63329;width:16px;height:3px;"></td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Bottom bar -->
        <tr>
          <td style="padding:0;">
            <table width="100%" cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#f0c419;height:3px;"></td>
              </tr>
            </table>
          </td>
        </tr>

      </table>
    </td></tr>
  </table>
</body>
</html>`;
}
