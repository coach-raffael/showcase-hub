# Showcase-Hub Web (demos.coach-raffael.com) — Next.js Standalone-Build.
#
# Build-Args:
#   API_URL    Basis-URL des Backends (ohne /api), wird in die Dev-Rewrite-Regel
#              gebacken. In Produktion routet der Reverse-Proxy /api direkt zum
#              api-Container; serverseitig gilt der ENV-Wert zur Laufzeit.
#   BASE_PATH  Optionaler Pfad-Präfix (Default leer = Domain-Wurzel).

FROM node:22-alpine AS deps
WORKDIR /app
RUN apk add --no-cache libc6-compat
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
ARG API_URL=http://api:8080
ARG BASE_PATH=""
ENV API_URL=$API_URL \
    BASE_PATH=$BASE_PATH \
    NEXT_PUBLIC_BASE_PATH=$BASE_PATH \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ARG BASE_PATH=""
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    API_URL=http://api:8080 \
    BASE_PATH=$BASE_PATH \
    NEXT_PUBLIC_BASE_PATH=$BASE_PATH
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=build --chown=app:app /app/.next/standalone ./
COPY --from=build --chown=app:app /app/.next/static ./.next/static
COPY --from=build --chown=app:app /app/public ./public
USER app
EXPOSE 3000
# Shell-Form, damit $BASE_PATH zur Laufzeit expandiert wird.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:3000${BASE_PATH}/robots.txt" >/dev/null || exit 1
CMD ["node", "server.js"]
