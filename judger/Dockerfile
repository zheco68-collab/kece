# Judger runtime image (Go)
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
# 中国大陆构建可通过 --build-arg GOPROXY=https://goproxy.cn,direct 加速依赖下载。
ARG GOPROXY=https://goproxy.cn,direct
RUN GOPROXY=${GOPROXY} go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /judger ./cmd/judger

FROM alpine:3.19
RUN apk add --no-cache docker-cli socat
COPY --from=build /judger /usr/local/bin/judger
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["judger"]
