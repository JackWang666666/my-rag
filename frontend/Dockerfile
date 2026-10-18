# Next.js frontend.
#
# Relies on `output: "standalone"` in next.config.js: the build traces the modules
# the server actually imports into .next/standalone, so the runtime image carries
# no node_modules tree and no build toolchain.
FROM node:20-alpine AS deps

WORKDIR /app
COPY package.json package-lock.json ./
# `npm ci` (not `install`) so the lockfile is authoritative and the build is
# reproducible; it fails loudly if the lockfile and package.json disagree.
RUN npm ci


FROM node:20-alpine AS builder

WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* variables are inlined into the client bundle at BUILD time, so
# they have to be passed here — setting them on the container at runtime has no
# effect. They are also public by definition: never pass a secret this way.
#
# These are the URLs the *browser* will call, so they must be reachable from the
# host, not from inside the compose network ("http://spring-ai-rag:8080" resolves
# only between containers and would break every request from the page).
ARG NEXT_PUBLIC_SPRING_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_LANGCHAIN_API_URL=http://localhost:8000
ARG NEXT_PUBLIC_DEFAULT_BACKEND=spring
ENV NEXT_PUBLIC_SPRING_API_URL=$NEXT_PUBLIC_SPRING_API_URL \
    NEXT_PUBLIC_LANGCHAIN_API_URL=$NEXT_PUBLIC_LANGCHAIN_API_URL \
    NEXT_PUBLIC_DEFAULT_BACKEND=$NEXT_PUBLIC_DEFAULT_BACKEND \
    NEXT_TELEMETRY_DISABLED=1

RUN npm run build


FROM node:20-alpine

WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# The standalone output contains server.js and a minimal node_modules. Static
# assets are not copied into it by the tracer, so .next/static has to come along
# separately or every stylesheet and chunk 404s.
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static

USER node

EXPOSE 3000

# wget is in busybox on Alpine; there is no curl in this image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:3000/login || exit 1

CMD ["node", "server.js"]
