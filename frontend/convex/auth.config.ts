declare const process: { env: Record<string, string | undefined> };

export default {
  providers: [
    {
      domain: process.env.SITE_URL,
      applicationID: "convex",
    },
  ],
};
