FROM node:21-alpine

RUN addgroup -S kubefn && adduser -S kubefn -G kubefn

WORKDIR /opt/kubefn
COPY src/ ./src/
COPY package.json .

RUN mkdir -p /var/kubefn/functions && chown -R kubefn:kubefn /var/kubefn

# Bake in API gateway example
COPY examples/api-gateway/*.js /var/kubefn/functions/api-gateway/

USER kubefn
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=3s --retries=3 \
    CMD wget -q -O- http://localhost:8080/admin/health || exit 1

ENTRYPOINT ["node", "src/index.js"]
