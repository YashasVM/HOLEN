FROM node:22-alpine AS build

WORKDIR /app
# NOTE: Vite inlines VITE_* env vars into the built JS at `npm run build`
# time. The key below is BAKED INTO dist/ — it is public by design (Clerk
# publishable key) but rotating it REQUIRES rebuilding + redeploying the
# image; restarting the container with a new -e value alone does nothing.
# Pass at build time: docker build --build-arg VITE_CLERK_PUBLISHABLE_KEY=pk_... .
ARG VITE_CLERK_PUBLISHABLE_KEY
ENV VITE_CLERK_PUBLISHABLE_KEY=${VITE_CLERK_PUBLISHABLE_KEY}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
