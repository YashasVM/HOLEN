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
    await ctx.db.patch(userId, { isApproved: approved });
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
