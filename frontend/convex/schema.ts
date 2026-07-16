import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";
import { authTables } from "@convex-dev/auth/server";

export default defineSchema({
  ...authTables,
  users: defineTable({
    name: v.optional(v.string()),
    image: v.optional(v.string()),
    email: v.optional(v.string()),
    emailVerificationTime: v.optional(v.float64()),
    phone: v.optional(v.string()),
    phoneNumberVerificationTime: v.optional(v.float64()),
    isAnonymous: v.optional(v.boolean()),
    clerkId: v.optional(v.string()),
    isApproved: v.optional(v.boolean()),
    isAdmin: v.optional(v.boolean()),
  })
    .index("email", ["email"])
    .index("clerkId", ["clerkId"])
    .index("phone", ["phone"]),
});
