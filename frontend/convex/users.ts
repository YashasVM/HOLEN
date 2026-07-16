import { query, mutation, internalMutation } from "./_generated/server";
import { v } from "convex/values";

async function getIdentity(ctx: any) {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity;
}

async function getUserForIdentity(ctx: any) {
  const identity = await getIdentity(ctx);
  const user = await ctx.db
    .query("users")
    .withIndex("clerkId", (q: any) => q.eq("clerkId", identity.subject))
    .unique();
  return { identity, user };
}

export const currentUser = query({
  handler: async (ctx) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) return null;
    const user = await ctx.db
      .query("users")
      .withIndex("clerkId", (q) => q.eq("clerkId", identity.subject))
      .unique();
    // Existing Convex-auth users are linked to Clerk by email in setupNewUser.
    const legacyUser = !user && identity.email
      ? await ctx.db.query("users").withIndex("email", (q) => q.eq("email", identity.email!)).unique()
      : null;
    const profile = user ?? legacyUser;
    if (!profile) return null;
    return {
      _id: profile._id,
      name: profile.name,
      email: profile.email,
      isApproved: profile.isApproved ?? false,
      isAdmin: profile.isAdmin ?? false,
      _creationTime: profile._creationTime,
    };
  },
});

export const setupNewUser = mutation({
  handler: async (ctx) => {
    const { identity, user: linkedUser } = await getUserForIdentity(ctx);
    const email = identity.email;
    const legacyUser = !linkedUser && email
      ? await ctx.db.query("users").withIndex("email", (q) => q.eq("email", email)).unique()
      : null;
    const user = linkedUser ?? legacyUser;
    if (user) {
      if (user.clerkId !== identity.subject) {
        await ctx.db.patch(user._id, { clerkId: identity.subject, name: identity.name ?? user.name });
      }
      return;
    }
    const allUsers = await ctx.db.query("users").collect();
    const hasAdmin = allUsers.some((u) => u.isAdmin === true);
    await ctx.db.insert("users", {
      clerkId: identity.subject,
      email,
      name: identity.name,
      isApproved: !hasAdmin,
      isAdmin: !hasAdmin,
    });
  },
});

export const listAllUsers = query({
  handler: async (ctx) => {
    const { user: admin } = await getUserForIdentity(ctx);
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
    const { user: admin } = await getUserForIdentity(ctx);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    await ctx.db.patch(userId, { isApproved: approved });
  },
});

export const setAdmin = mutation({
  args: { userId: v.id("users"), isAdmin: v.boolean() },
  handler: async (ctx, { userId, isAdmin }) => {
    const { user: admin } = await getUserForIdentity(ctx);
    if (!admin?.isAdmin) throw new Error("Not an admin");
    if (userId === admin._id && !isAdmin)
      throw new Error("Cannot remove your own admin status");
    await ctx.db.patch(userId, { isAdmin });
  },
});

// One-time cleanup for the retired Convex access model. The active access
// records now live beside bandwidth accounting in the backend SQLite database.
export const purgeLegacyUsers = internalMutation({
  args: {},
  handler: async (ctx) => {
    const users = await ctx.db.query("users").take(100);
    for (const user of users) await ctx.db.delete(user._id);
    return { deleted: users.length, hasMore: users.length === 100 };
  },
});
