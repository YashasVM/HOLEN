import { query, mutation } from "./_generated/server";
import { v } from "convex/values";
import { getAuthUserId } from "@convex-dev/auth/server";

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
    if (user.email) {
      const banned = await ctx.db
        .query("bannedEmails")
        .withIndex("email", (q) => q.eq("email", user.email!))
        .first();
      if (banned) throw new Error("This email address has been banned.");
    }
    const allUsers = await ctx.db.query("users").collect();
    const hasAdmin = allUsers.some((u) => u.isAdmin === true);
    await ctx.db.patch(userId, { isApproved: !hasAdmin, isAdmin: !hasAdmin });
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
    await ctx.db.patch(userId, { isApproved: approved });
    // Approval email is sent by the frontend via /api/notify/approved (FastAPI + Gmail SMTP)
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
    if (existing) return;
    await ctx.db.insert("bannedEmails", { email, bannedAt: Date.now(), bannedBy: admin.email });
    const user = await ctx.db.query("users").withIndex("email", (q) => q.eq("email", email)).first();
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
    const record = await ctx.db.query("bannedEmails").withIndex("email", (q) => q.eq("email", email)).first();
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
    for (const u of allUsers) await ctx.db.delete(u._id);
  },
});
